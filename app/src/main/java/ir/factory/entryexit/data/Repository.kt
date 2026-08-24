package ir.factory.entryexit.data

import android.content.Context
import androidx.lifecycle.LiveData
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import ir.factory.entryexit.util.NetworkMonitor
import kotlinx.coroutines.tasks.await

/**
 * Firestore is the source of truth; Room (via [CloudSync]) is a local, offline-friendly mirror
 * that the UI reads from. Every write here goes to Firestore — Firestore's own offline queue
 * means a write made with no signal is queued locally and sent automatically once reconnected,
 * and CloudSync's listeners then reflect it into every device's Room cache, including this one.
 *
 * The core business rule (a person cannot be checked in again until checked out) is still
 * enforced here, reading the latest known state from the local Room mirror before writing.
 *
 * OFFLINE BEHAVIOR: calling `.set()`/`.update()` on a Firestore document queues the change into
 * the on-device persistent cache synchronously, whether or not there is a network connection —
 * that part happens for free. The one thing that does NOT happen for free is the returned
 * [Task]: by design it stays pending until the server acknowledges the write, which — with no
 * connection — can be indefinitely. Every write below is routed through [awaitWrite] so that,
 * when offline, we don't sit there waiting on a round-trip that can't happen yet; the record is
 * already saved on the device and Firestore delivers it to the server automatically once
 * connectivity returns (see [NetworkMonitor], [FactoryApp]).
 */
class Repository(
    private val context: Context,
    private val personDao: PersonDao,
    private val logDao: LogDao,
    private val cloudSync: CloudSync,
    private val inspectionDao: InspectionDao,
    private val itemLogDao: ItemLogDao,
    private val reportDao: ReportDao
) {
    private val firestore = FirebaseFirestore.getInstance()
    private val personsCol = firestore.collection(CloudSync.COLLECTION_PERSONS)
    private val logsCol = firestore.collection(CloudSync.COLLECTION_LOGS)
    private val inspectionsCol = firestore.collection(CloudSync.COLLECTION_INSPECTIONS)
    private val itemLogsCol = firestore.collection(CloudSync.COLLECTION_ITEM_LOGS)
    private val reportsCol = firestore.collection(CloudSync.COLLECTION_REPORTS)

    fun startSync() = cloudSync.start()
    fun stopSync() = cloudSync.stop()

    /** True if writes are going out to the server right now; false if they're being queued
     *  on-device because we're offline (still a success — see class docstring). Lets the UI show
     *  "ذخیره شد، پس از اتصال به اینترنت ارسال می‌شود" instead of a plain success message. */
    fun isOnline(): Boolean = NetworkMonitor.isOnline(context)

    /**
     * Waits for [task] to finish only when we currently have a connection. Offline, the write
     * has already been placed in Firestore's local queue the moment [task] was created (that
     * part is synchronous), so we return right away instead of suspending on a server ack that
     * has nothing to arrive on yet. If we *are* online but the task still fails because
     * connectivity dropped mid-flight, that's treated the same way — queued, not an error. Any
     * other failure (permission denied, bad data, etc.) is rethrown so callers still see it.
     */
    private suspend fun awaitWrite(task: Task<Void>) {
        if (!NetworkMonitor.isOnline(context)) return
        try {
            task.await()
        } catch (e: Exception) {
            if (!isConnectivityFailure(e)) throw e
        }
    }

    fun getPersonsByType(type: PersonType): LiveData<List<PersonEntity>> = personDao.getByType(type.name)

    fun getInsidePersonsByType(type: PersonType): LiveData<List<PersonEntity>> =
        personDao.getInsideByType(type.name)

    /** Everyone currently inside, across every category — for the admin dashboard. */
    fun getAllCurrentlyInside(): LiveData<List<PersonEntity>> = personDao.getAllInside()

    fun getRecentActivityByType(type: PersonType, limit: Int = 10): LiveData<List<LogEntity>> =
        logDao.getRecentByType(type.name, limit)

    fun search(query: String): LiveData<List<PersonEntity>> = personDao.searchAll(query)

    /** Inserts the fixed machinery fleet AND personnel roster exactly once ACROSS ALL DEVICES,
     *  using a Firestore transaction on a flag document so two guards opening the app for the
     *  first time at the same moment can't both seed duplicates. */
    suspend fun ensureFleetSeeded() {
        // already mirrored locally -> nothing to do, whichever device/order got there first
        if (personDao.countByType(PersonType.MACHINERY.name) > 0 && personDao.countByType(PersonType.PERSONNEL.name) > 0) return

        val flagRef = firestore.document(CloudSync.META_DOC_FLEET_SEEDED)
        val wonRace = try {
            firestore.runTransaction { tx ->
                val snap = tx.get(flagRef)
                if (snap.exists()) {
                    false
                } else {
                    tx.set(flagRef, mapOf("seeded" to true, "seededAt" to System.currentTimeMillis()))
                    true
                }
            }.await()
        } catch (e: Exception) {
            false
        }

        if (wonRace == true) {
            val batch = firestore.batch()
            for (entity in Fleet.buildInitialRoster() + Staff.buildInitialRoster()) {
                val docRef = personsCol.document()
                batch.set(docRef, entity.copy(id = docRef.id))
            }
            batch.commit().await()
        }
    }

    /** Backfills the Driver roster from the usual-driver names already on [Fleet], for installs
     *  that ran [ensureFleetSeeded] before it seeded drivers (or ran it on a version of [Fleet]
     *  that didn't have driver names yet). Uses its own flag/transaction, independent of
     *  [CloudSync.META_DOC_FLEET_SEEDED], so it still runs exactly once across every device even
     *  though the fleet/staff seed itself has already happened and will never run again. */
    suspend fun ensureDriversSeeded() {
        if (personDao.countByType(PersonType.DRIVER.name) > 0) return

        val flagRef = firestore.document(CloudSync.META_DOC_DRIVERS_SEEDED)
        val wonRace = try {
            firestore.runTransaction { tx ->
                val snap = tx.get(flagRef)
                if (snap.exists()) {
                    false
                } else {
                    tx.set(flagRef, mapOf("seeded" to true, "seededAt" to System.currentTimeMillis()))
                    true
                }
            }.await()
        } catch (e: Exception) {
            false
        }

        if (wonRace == true) {
            val batch = firestore.batch()
            for (entity in Fleet.buildInitialDriverRoster()) {
                val docRef = personsCol.document()
                batch.set(docRef, entity.copy(id = docRef.id))
            }
            batch.commit().await()
        }
    }

    /** One-time cleanup: earlier versions of [Fleet] baked the word "پلاک" into every machinery
     *  record's stored name (e.g. "پلاک ۴۷۷۶۹"), which reads as redundant now that the field is
     *  correctly labeled "شماره پلاک" on its own — this strips that literal prefix off every
     *  machinery record that still has it, leaving just the plate number (e.g. "۴۷۷۶۹"). Guarded
     *  by its own Firestore flag so it only ever runs once across every device. */
    suspend fun ensureMachineryPlateNamesCleaned() {
        val flagRef = firestore.document(CloudSync.META_DOC_MACHINERY_PLATE_NAMES_CLEANED)
        val wonRace = try {
            firestore.runTransaction { tx ->
                val snap = tx.get(flagRef)
                if (snap.exists()) {
                    false
                } else {
                    tx.set(flagRef, mapOf("cleaned" to true, "cleanedAt" to System.currentTimeMillis()))
                    true
                }
            }.await()
        } catch (e: Exception) {
            false
        }

        if (wonRace == true) {
            val machineryPersons = personDao.getByTypeOnce(PersonType.MACHINERY.name)
            val toFix = machineryPersons.mapNotNull { person ->
                val trimmed = person.name.trim()
                if (!trimmed.startsWith(LEGACY_PLATE_PREFIX)) return@mapNotNull null
                val cleaned = trimmed.removePrefix(LEGACY_PLATE_PREFIX).trim()
                if (cleaned.isEmpty()) null else person to cleaned
            }
            if (toFix.isNotEmpty()) {
                val batch = firestore.batch()
                for ((person, cleaned) in toFix) {
                    batch.update(personsCol.document(person.id), "name", cleaned)
                }
                batch.commit().await()
            }
        }
    }

    /** One-time migration: the machinery "extra info" field used to be mislabeled as license
     *  plate, so the driver's name ended up typed into it instead (see
     *  [ensureMachineryPlateNamesCleaned] for the matching name-field cleanup). Sweeps every
     *  machinery record's extraInfo and creates a matching Driver roster entry for each distinct
     *  non-empty name that doesn't already have one, so those drivers show up in the ورود و خروج
     *  رانندگان tab too. Guarded by its own Firestore flag so it only ever runs once across every
     *  device. */
    suspend fun ensureMachineryDriverNamesBackfilled() {
        val flagRef = firestore.document(CloudSync.META_DOC_MACHINERY_DRIVER_NAMES_BACKFILLED)
        val wonRace = try {
            firestore.runTransaction { tx ->
                val snap = tx.get(flagRef)
                if (snap.exists()) {
                    false
                } else {
                    tx.set(flagRef, mapOf("seeded" to true, "seededAt" to System.currentTimeMillis()))
                    true
                }
            }.await()
        } catch (e: Exception) {
            false
        }

        if (wonRace == true) {
            val machineryPersons = personDao.getByTypeOnce(PersonType.MACHINERY.name)
            val existingDriverNames = personDao.getByTypeOnce(PersonType.DRIVER.name).map { it.name }.toSet()
            val driverNames = machineryPersons
                .mapNotNull { it.extraInfo?.trim()?.takeIf { name -> name.isNotEmpty() } }
                .distinct()
                .filterNot { it in existingDriverNames }

            if (driverNames.isNotEmpty()) {
                val batch = firestore.batch()
                for (driverName in driverNames) {
                    val docRef = personsCol.document()
                    batch.set(docRef, PersonEntity(id = docRef.id, name = driverName, type = PersonType.DRIVER.name))
                }
                batch.commit().await()
            }
        }
    }

    /** One-time migration: organizes the Driver roster into the factory's own
     *  کمپرسی‌تریلی/پمپ/میکسر/لودر sections (see [Fleet.categorizedDrivers]), so the ورود و خروج
     *  رانندگان tab groups by vehicle category the same way the Machinery tab already does. For
     *  each name on that list: if a driver with that exact name already exists (e.g. seeded
     *  earlier from a vehicle's usual-driver field), its group is updated to match; otherwise a
     *  new roster entry is created with that group.
     *
     *  Unlike the older seed/backfill functions above, the "already done" flag is only written
     *  AFTER the batch write actually succeeds (not before, inside a race-guard transaction) —
     *  every step here is also safe to redo (name-based existence checks, no duplicates
     *  created), so if this fails partway (e.g. no connectivity on first launch after update) it
     *  simply retries on the next app start instead of being permanently marked done with the
     *  data never having actually landed. */
    suspend fun ensureDriverCategoriesSeeded() {
        val flagRef = firestore.document(CloudSync.META_DOC_DRIVER_CATEGORIES_SEEDED)
        val alreadySeeded = try {
            flagRef.get().await().exists()
        } catch (e: Exception) {
            return // offline / unknown -- skip this run rather than write while unsure
        }
        if (alreadySeeded) return

        val batch = firestore.batch()
        for ((name, group) in Fleet.categorizedDrivers()) {
            val existing = personDao.getByNameAndType(PersonType.DRIVER.name, name)
            if (existing == null) {
                val docRef = personsCol.document()
                batch.set(docRef, PersonEntity(id = docRef.id, name = name, type = PersonType.DRIVER.name, group = group))
            } else if (existing.group != group) {
                batch.update(personsCol.document(existing.id), "group", group)
            }
        }
        try {
            batch.commit().await()
            awaitWrite(flagRef.set(mapOf("seeded" to true, "seededAt" to System.currentTimeMillis())))
        } catch (e: Exception) {
            // Flag stays unset -- next launch simply retries (harmless: every write above is
            // name-based and idempotent).
        }
    }

    /** Registers a brand-new person/machine (name-only, or with a department/group). */
    suspend fun addPerson(name: String, type: PersonType, group: String? = null, extraInfo: String? = null): Result<String> {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("نام نمی‌تواند خالی باشد"))
        }
        if (personDao.countByNameAndType(type.name, trimmed, excludeId = "") > 0) {
            return Result.failure(IllegalStateException("این نام قبلاً ثبت شده است"))
        }
        val docRef = personsCol.document()
        val entity = PersonEntity(
            id = docRef.id,
            name = trimmed,
            type = type.name,
            group = group?.trim()?.ifEmpty { null },
            extraInfo = extraInfo?.trim()?.ifEmpty { null }
        )
        return try {
            awaitWrite(docRef.set(entity))
            personDao.upsert(entity) // instant local visibility, and durable even if we're offline
            Result.success(docRef.id)
        } catch (e: Exception) {
            Result.failure(IllegalStateException(networkAwareMessage(e)))
        }
    }

    /** Photos are local-only (a content:// URI only makes sense on the device that picked it),
     *  so this updates Room directly and is intentionally NOT synced to Firestore. */
    suspend fun updatePersonImage(personId: String, imageUri: String?): Result<Unit> {
        val fresh = personDao.getById(personId) ?: return Result.failure(IllegalStateException("فرد یافت نشد"))
        personDao.update(fresh.copy(imageUri = imageUri))
        return Result.success(Unit)
    }

    /** Edits an existing person/machine's name, department/group, and extra info. */
    suspend fun updatePerson(personId: String, name: String, group: String?, extraInfo: String?): Result<Unit> {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return Result.failure(IllegalArgumentException("نام نمی‌تواند خالی باشد"))
        val fresh = personDao.getById(personId) ?: return Result.failure(IllegalStateException("مورد یافت نشد"))

        if (!trimmed.equals(fresh.name, ignoreCase = false)) {
            val duplicateCount = personDao.countByNameAndType(fresh.type, trimmed, excludeId = personId)
            if (duplicateCount > 0) {
                return Result.failure(IllegalStateException("این نام قبلاً برای مورد دیگری ثبت شده است"))
            }
        }

        return try {
            awaitWrite(
                personsCol.document(personId).update(
                    mapOf(
                        "name" to trimmed,
                        "group" to group?.trim()?.ifEmpty { null },
                        "extraInfo" to extraInfo?.trim()?.ifEmpty { null }
                    )
                )
            )
            personDao.update(fresh.copy(name = trimmed, group = group?.trim()?.ifEmpty { null }, extraInfo = extraInfo?.trim()?.ifEmpty { null }))
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(IllegalStateException(networkAwareMessage(e)))
        }
    }

    suspend fun getRosterOnce(type: PersonType): List<PersonEntity> = personDao.getByTypeOnce(type.name)

    /** Blocks or unblocks future check-ins for this person/machine. */
    suspend fun setBlacklisted(personId: String, blacklisted: Boolean): Result<Unit> {
        val fresh = personDao.getById(personId)
        return try {
            awaitWrite(personsCol.document(personId).update("isBlacklisted", blacklisted))
            fresh?.let { personDao.update(it.copy(isBlacklisted = blacklisted)) }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(IllegalStateException(networkAwareMessage(e)))
        }
    }

    /**
     * Check a person **in**. Fails if they are already marked as inside, or blacklisted.
     * [performedByUid]/[performedByName] record which signed-in guard did this.
     */
    suspend fun checkIn(
        personId: String,
        detail: String? = null,
        performedByUid: String?,
        performedByName: String?,
        checkpoint: String? = null
    ): Result<PersonEntity> {
        val fresh = personDao.getById(personId) ?: return Result.failure(IllegalStateException("فرد یافت نشد"))

        if (fresh.isBlacklisted) {
            return Result.failure(IllegalStateException("${fresh.name} در لیست سیاه قرار دارد و اجازه ورود ندارد"))
        }
        if (fresh.isInside) {
            return Result.failure(IllegalStateException("${fresh.name} قبلاً ورود ثبت کرده و هنوز خروج نزده است"))
        }

        val now = System.currentTimeMillis()
        if (fresh.lastEventAt > 0 && now - fresh.lastEventAt < MIN_EVENT_INTERVAL_MS) {
            return Result.failure(IllegalStateException("برای ${fresh.name} چند ثانیه پیش رویدادی ثبت شده؛ از ثبت تکراری جلوگیری شد."))
        }
        val updated = fresh.copy(isInside = true, lastEventAt = now)

        return try {
            awaitWrite(personsCol.document(personId).update(mapOf("isInside" to true, "lastEventAt" to now)))
            val logRef = logsCol.document()
            val logEntity = LogEntity(
                id = logRef.id,
                personId = fresh.id,
                personName = fresh.name,
                type = fresh.type,
                group = fresh.group,
                action = ACTION_IN,
                timestamp = now,
                detail = detail?.trim()?.ifEmpty { null },
                performedByUid = performedByUid,
                performedByName = performedByName,
                checkpoint = checkpoint
            )
            awaitWrite(logRef.set(logEntity))
            personDao.update(updated) // instant local visibility, durable even offline
            logDao.upsert(logEntity)
            Result.success(updated)
        } catch (e: Exception) {
            Result.failure(IllegalStateException(networkAwareMessage(e)))
        }
    }

    /**
     * Check a person **out**. Fails if they are not currently inside. [detail] optionally
     * records the cargo/load type for a machinery departure.
     */
    suspend fun checkOut(
        personId: String,
        detail: String? = null,
        performedByUid: String?,
        performedByName: String?,
        checkpoint: String? = null
    ): Result<PersonEntity> {
        val fresh = personDao.getById(personId) ?: return Result.failure(IllegalStateException("فرد یافت نشد"))

        if (!fresh.isInside) {
            return Result.failure(IllegalStateException("${fresh.name} ورودی ثبت‌شده‌ای ندارد"))
        }

        val now = System.currentTimeMillis()
        if (fresh.lastEventAt > 0 && now - fresh.lastEventAt < MIN_EVENT_INTERVAL_MS) {
            return Result.failure(IllegalStateException("برای ${fresh.name} چند ثانیه پیش رویدادی ثبت شده؛ از ثبت تکراری جلوگیری شد."))
        }
        val updated = fresh.copy(isInside = false, lastEventAt = now)

        return try {
            awaitWrite(personsCol.document(personId).update(mapOf("isInside" to false, "lastEventAt" to now)))
            val logRef = logsCol.document()
            val logEntity = LogEntity(
                id = logRef.id,
                personId = fresh.id,
                personName = fresh.name,
                type = fresh.type,
                group = fresh.group,
                action = ACTION_OUT,
                timestamp = now,
                detail = detail?.trim()?.ifEmpty { null },
                performedByUid = performedByUid,
                performedByName = performedByName,
                checkpoint = checkpoint
            )
            awaitWrite(logRef.set(logEntity))
            personDao.update(updated)
            logDao.upsert(logEntity)
            Result.success(updated)
        } catch (e: Exception) {
            Result.failure(IllegalStateException(networkAwareMessage(e)))
        }
    }

    /** One-step flow for a guest: register + immediately check in against the department
     *  they're visiting. Every visit creates a fresh record (guests are transient). */
    suspend fun checkInVisitor(name: String, department: String, performedByUid: String?, performedByName: String?, checkpoint: String? = null): Result<Unit> {
        val trimmedName = name.trim()
        val trimmedDept = department.trim()
        if (trimmedName.isEmpty()) return Result.failure(IllegalArgumentException("نام مهمان نمی‌تواند خالی باشد"))
        if (trimmedDept.isEmpty()) return Result.failure(IllegalArgumentException("وارد کردن واحد مورد مراجعه الزامی است"))

        val docRef = personsCol.document()
        val entity = PersonEntity(id = docRef.id, name = trimmedName, type = PersonType.VISITOR.name)
        return try {
            awaitWrite(docRef.set(entity))
            personDao.upsert(entity) // make it visible locally immediately, and durable if we're offline
            checkIn(docRef.id, trimmedDept, performedByUid, performedByName, checkpoint).map { }
        } catch (e: Exception) {
            Result.failure(IllegalStateException(networkAwareMessage(e)))
        }
    }

    /** One-step flow for a driver: register + immediately check in against the vehicle
     *  they're assigned to for this trip. */
    suspend fun checkInDriver(name: String, vehicle: String, performedByUid: String?, performedByName: String?, checkpoint: String? = null): Result<Unit> {
        val trimmedName = name.trim()
        val trimmedVehicle = vehicle.trim()
        if (trimmedName.isEmpty()) return Result.failure(IllegalArgumentException("نام راننده نمی‌تواند خالی باشد"))

        val docRef = personsCol.document()
        val entity = PersonEntity(id = docRef.id, name = trimmedName, type = PersonType.DRIVER.name)
        return try {
            awaitWrite(docRef.set(entity))
            personDao.upsert(entity)
            // ورود راننده یعنی حضور برای شیفت/سرویس، نه یک سفر مشخص با یک ماشین خاص — بنابراین
            // ماشین صرفاً یک یادداشت اختیاری در همان اولین ثبت است، نه فیلد الزامی.
            checkIn(docRef.id, trimmedVehicle.ifEmpty { null }, performedByUid, performedByName, checkpoint).map { }
        } catch (e: Exception) {
            Result.failure(IllegalStateException(networkAwareMessage(e)))
        }
    }

    /** Real-time count (not range-bound) — used for the "currently inside right now" metric. */
    suspend fun countCurrentlyInside(type: PersonType): Int = personDao.countInsideByType(type.name)

    /**
     * Machinery-only roster for the parking guard's screen, and the two functions below —
     * [checkInParking]/[checkOutParking] — that toggle [PersonEntity.insideParking] instead of
     * [PersonEntity.isInside]. See [PersonEntity.insideParking]'s doc for why this needs to be
     * a completely separate status from the factory-level one.
     */
    fun getMachineryForParking(): LiveData<List<PersonEntity>> = personDao.getByTypeForParking(PersonType.MACHINERY.name)

    /** Every log entry a specific guard/admin has personally created — the "my logged events"
     *  screen. Admins use [getRecentLogs] instead to review everyone's. */
    fun getMyLogs(uid: String): LiveData<List<LogEntity>> = logDao.getByPerformedByUid(uid, limit = 500)

    /** Every recent log entry across all guards — admin-only view for the same screen. */
    fun getRecentLogs(limit: Int = 500): LiveData<List<LogEntity>> = logDao.getRecent(limit)

    /**
     * Corrects a previously logged event's timestamp — e.g. a guard logs a check-in a few
     * minutes late and wants the record to reflect when it actually happened, not when they
     * happened to tap the button. Only the guard who created the log, or an admin, may edit it
     * — enforced here AND in firestore.rules (defense in depth: this check alone wouldn't stop
     * someone from writing to Firestore directly). The very first edit preserves the original
     * value in [LogEntity.originalTimestamp] so a correction is visible, not a silent rewrite.
     */
    suspend fun editLogTimestamp(
        logId: String,
        newTimestamp: Long,
        editedByUid: String?,
        editedByName: String?,
        isAdmin: Boolean
    ): Result<Unit> {
        val fresh = logDao.getById(logId) ?: return Result.failure(IllegalStateException("رویداد یافت نشد"))
        if (!isAdmin && (editedByUid == null || fresh.performedByUid != editedByUid)) {
            return Result.failure(IllegalStateException("فقط رویدادهای ثبت‌شده توسط خودتان قابل اصلاح است"))
        }
        val editedAt = System.currentTimeMillis()
        val preservedOriginal = fresh.originalTimestamp ?: fresh.timestamp
        val updated = fresh.copy(
            timestamp = newTimestamp,
            originalTimestamp = preservedOriginal,
            editedAt = editedAt,
            editedByUid = editedByUid,
            editedByName = editedByName
        )
        return try {
            awaitWrite(
                logsCol.document(logId).update(
                    mapOf(
                        "timestamp" to newTimestamp,
                        "originalTimestamp" to preservedOriginal,
                        "editedAt" to editedAt,
                        "editedByUid" to editedByUid,
                        "editedByName" to editedByName
                    )
                )
            )
            logDao.upsert(updated)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(IllegalStateException(networkAwareMessage(e)))
        }
    }

    /**
     * Permanently removes a mistakenly logged event — e.g. an accidental double-tap, or the
     * wrong person/vehicle picked by mistake — for the "اصلاح رویدادهای ثبت‌شده" screen. Same
     * permission rule as [editLogTimestamp]: only the guard who created the log, or an admin,
     * may delete it — enforced here AND in firestore.rules. Unlike an edit, a delete can also
     * change whether the affected person/vehicle should currently show as inside, so
     * [reconcilePersonStatusAfterDelete] recomputes that from whatever log is now the most
     * recent one for them at that same checkpoint, instead of leaving a stale status behind.
     */
    suspend fun deleteLog(
        logId: String,
        requestedByUid: String?,
        isAdmin: Boolean
    ): Result<Unit> {
        val fresh = logDao.getById(logId) ?: return Result.failure(IllegalStateException("رویداد یافت نشد"))
        if (!isAdmin && (requestedByUid == null || fresh.performedByUid != requestedByUid)) {
            return Result.failure(IllegalStateException("فقط رویدادهای ثبت‌شده توسط خودتان قابل حذف است"))
        }
        return try {
            awaitWrite(logsCol.document(logId).delete())
            logDao.deleteById(logId)
            reconcilePersonStatusAfterDelete(fresh)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(IllegalStateException(networkAwareMessage(e)))
        }
    }

    /** After a log is deleted, re-derives the person/vehicle's inside status from whichever log
     *  is now the most recent for them at that same checkpoint (PARKING vs GATE/other), rather
     *  than assuming the deleted event was necessarily the last one. If the write to Firestore
     *  fails (offline), the local DB still gets the corrected value so the app stays usable;
     *  the next sync will reconcile Firestore once connectivity returns. */
    private suspend fun reconcilePersonStatusAfterDelete(deletedLog: LogEntity) {
        val person = personDao.getById(deletedLog.personId) ?: return
        if (deletedLog.checkpoint == Checkpoint.PARKING.name) {
            val latest = logDao.getLatestParkingLogForPerson(person.id)
            val shouldBeInsideParking = latest?.action == ACTION_IN
            if (person.insideParking != shouldBeInsideParking) {
                try {
                    awaitWrite(personsCol.document(person.id).update("insideParking", shouldBeInsideParking))
                } catch (_: Exception) {
                    // offline — local value below still keeps the device usable
                }
                personDao.update(person.copy(insideParking = shouldBeInsideParking))
            }
        } else {
            val latest = logDao.getLatestGateLogForPerson(person.id)
            val shouldBeInside = latest?.action == ACTION_IN
            if (person.isInside != shouldBeInside) {
                try {
                    awaitWrite(personsCol.document(person.id).update("isInside", shouldBeInside))
                } catch (_: Exception) {
                }
                personDao.update(person.copy(isInside = shouldBeInside))
            }
        }
    }

    /** Log a vehicle returning to the internal parking area. Always tagged checkpoint = PARKING
     *  since this flow only exists for that guard post. */
    suspend fun checkInParking(personId: String, performedByUid: String?, performedByName: String?): Result<PersonEntity> {
        val fresh = personDao.getById(personId) ?: return Result.failure(IllegalStateException("ماشین یافت نشد"))
        if (fresh.insideParking) {
            return Result.failure(IllegalStateException("${fresh.name} در حال حاضر داخل پارکینگ ثبت شده است"))
        }
        val now = System.currentTimeMillis()
        if (fresh.lastEventAt > 0 && now - fresh.lastEventAt < MIN_EVENT_INTERVAL_MS) {
            return Result.failure(IllegalStateException("برای ${fresh.name} چند ثانیه پیش رویدادی ثبت شده؛ از ثبت تکراری جلوگیری شد."))
        }
        val updated = fresh.copy(insideParking = true, lastEventAt = now)

        return try {
            awaitWrite(personsCol.document(personId).update(mapOf("insideParking" to true, "lastEventAt" to now)))
            val logRef = logsCol.document()
            val logEntity = LogEntity(
                id = logRef.id,
                personId = fresh.id,
                personName = fresh.name,
                type = fresh.type,
                group = fresh.group,
                action = ACTION_IN,
                timestamp = now,
                performedByUid = performedByUid,
                performedByName = performedByName,
                checkpoint = Checkpoint.PARKING.name
            )
            awaitWrite(logRef.set(logEntity))
            personDao.update(updated)
            logDao.upsert(logEntity)
            Result.success(updated)
        } catch (e: Exception) {
            Result.failure(IllegalStateException(networkAwareMessage(e)))
        }
    }

    /** Log a vehicle leaving the internal parking area — e.g. to the in-house repair shop or
     *  for photos — without it necessarily leaving the factory through the main gate. [reason]
     *  is an optional free-text note (e.g. "تعمیرگاه داخلی", "عکس‌برداری"). */
    suspend fun checkOutParking(personId: String, reason: String?, performedByUid: String?, performedByName: String?): Result<PersonEntity> {
        val fresh = personDao.getById(personId) ?: return Result.failure(IllegalStateException("ماشین یافت نشد"))
        if (!fresh.insideParking) {
            return Result.failure(IllegalStateException("${fresh.name} داخل پارکینگ ثبت نشده است"))
        }
        val now = System.currentTimeMillis()
        if (fresh.lastEventAt > 0 && now - fresh.lastEventAt < MIN_EVENT_INTERVAL_MS) {
            return Result.failure(IllegalStateException("برای ${fresh.name} چند ثانیه پیش رویدادی ثبت شده؛ از ثبت تکراری جلوگیری شد."))
        }
        val updated = fresh.copy(insideParking = false, lastEventAt = now)

        return try {
            awaitWrite(personsCol.document(personId).update(mapOf("insideParking" to false, "lastEventAt" to now)))
            val logRef = logsCol.document()
            val logEntity = LogEntity(
                id = logRef.id,
                personId = fresh.id,
                personName = fresh.name,
                type = fresh.type,
                group = fresh.group,
                action = ACTION_OUT,
                timestamp = now,
                detail = reason?.trim()?.ifEmpty { null },
                performedByUid = performedByUid,
                performedByName = performedByName,
                checkpoint = Checkpoint.PARKING.name
            )
            awaitWrite(logRef.set(logEntity))
            personDao.update(updated)
            logDao.upsert(logEntity)
            Result.success(updated)
        } catch (e: Exception) {
            Result.failure(IllegalStateException(networkAwareMessage(e)))
        }
    }

    /** How many times this vehicle has been dispatched (checked OUT) in the rolling 24 hours
     *  ending now. Backs the dump-truck/dump-trailer safety check: 4 mine-to-factory runs a day
     *  is normal, 5 can happen with light city traffic, but 6+ in one day should raise a flag —
     *  possible driver fatigue/impairment, an unlogged driver swap, or unsafe over-driving. */
    suspend fun countDispatchesInLast24h(personId: String): Int =
        logDao.countActionsSince(personId, ACTION_OUT, System.currentTimeMillis() - TWENTY_FOUR_HOURS_MS)

    /** Every dispatch (OUT) for the whole Machinery roster since [sinceMillis] — used to build
     *  the mixers' "سرویس امروز" per-vehicle count. For mixers what matters is the running count
     *  of services, not exact timing, so this is intentionally a live feed the fragment can
     *  re-tally client-side rather than a one-shot query. */
    fun getMachineryDispatchesSince(sinceMillis: Long): LiveData<List<LogEntity>> =
        logDao.getActionsSince(PersonType.MACHINERY.name, ACTION_OUT, sinceMillis)

    /**
     * Queries Firestore DIRECTLY for the exact date range (not the bounded local mirror), so
     * Excel exports and AI summaries are always complete regardless of how much history the
     * "recent activity" window happens to be mirroring locally at that moment.
     */
    suspend fun getLogsInRange(startInclusive: Long, endInclusive: Long): List<LogEntity> {
        return try {
            val snapshot = logsCol
                .whereGreaterThanOrEqualTo("timestamp", startInclusive)
                .whereLessThanOrEqualTo("timestamp", endInclusive)
                .orderBy("timestamp", Query.Direction.ASCENDING)
                .get()
                .await()
            snapshot.documents.mapNotNull { it.toObject(LogEntity::class.java) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ---- Weekly machinery inspections ("بازدید ظاهری") ----

    /** Every submitted inspection, newest first — the tab-5 list groups this client-side to
     *  show only the latest record per vehicle, same lightweight pattern as grouping the
     *  roster by department. */
    fun getAllInspections(): LiveData<List<InspectionEntity>> = inspectionDao.getAllLive()

    suspend fun getPersonById(personId: String): PersonEntity? = personDao.getById(personId)

    /** The vehicle's previous inspection, if any — used by [ir.factory.entryexit.ui
     *  .InspectionFormActivity] to pre-flag on the diagram anything that was WARN/BAD last
     *  time and never got marked repaired, before the guard has tapped anything this week. */
    suspend fun getLatestInspectionForPerson(personId: String): InspectionEntity? =
        inspectionDao.getLatestForPerson(personId)

    /** Used by [ir.factory.entryexit.ui.InspectionFormActivity]'s edit/correction mode to load
     *  the record being fixed. */
    suspend fun getInspectionById(inspectionId: String): InspectionEntity? =
        inspectionDao.getById(inspectionId)

    /** Same idea as [getLatestInspectionForPerson] but skips [excludeId] — used while
     *  correcting an inspection so "still open" reflects the record before it, not itself. */
    suspend fun getLatestInspectionForPersonExcluding(personId: String, excludeId: String): InspectionEntity? =
        inspectionDao.getPreviousExcluding(personId, excludeId)

    /** Saves one vehicle's weekly checklist. [parts] must list every item from
     *  [InspectionCatalog.partsFor] for that vehicle's category, in order. */
    suspend fun submitInspection(
        person: PersonEntity,
        parts: List<InspectionPartResult>,
        notes: String?,
        performedByUid: String?,
        performedByName: String?
    ): Result<Unit> {
        // Carry forward "still broken since ..." for anything that was already WARN/BAD last
        // time and still isn't OK now, so the diagram/report can tell a recurring defect from
        // a brand-new one instead of everything looking equally fresh every week.
        val previous = inspectionDao.getLatestForPerson(person.id)
        val previousByName = previous?.let { InspectionJson.parse(it.partsJson).associateBy { p -> p.name } }.orEmpty()
        val partsWithRecurrence = parts.map { part ->
            if (part.status == PartStatus.OK) return@map part
            val prior = previousByName[part.name] ?: return@map part
            if (prior.status == PartStatus.OK) return@map part
            part.copy(recurringSinceTimestamp = prior.recurringSinceTimestamp ?: previous?.timestamp)
        }

        val approved = partsWithRecurrence.count { it.ok }
        val rejected = partsWithRecurrence.size - approved
        val partsJson = InspectionJson.serialize(partsWithRecurrence)

        val docRef = inspectionsCol.document()
        val entity = InspectionEntity(
            id = docRef.id,
            personId = person.id,
            personName = person.name,
            driverName = person.extraInfo,
            group = person.group,
            category = MachineryCategory.classify(person.group).name,
            partsJson = partsJson,
            approvedCount = approved,
            rejectedCount = rejected,
            notes = notes?.trim()?.ifEmpty { null },
            performedByUid = performedByUid,
            performedByName = performedByName,
            timestamp = System.currentTimeMillis()
        )
        return try {
            awaitWrite(docRef.set(entity))
            inspectionDao.upsert(entity) // instant local visibility, and durable if we're offline
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(IllegalStateException(networkAwareMessage(e)))
        }
    }

    /**
     * Overwrites an already-submitted weekly inspection in place (same document ID, same
     * original [InspectionEntity.timestamp] so it stays counted in the week it actually
     * happened in) — for when a guard taps the wrong part or picks the wrong vehicle by
     * mistake. Recurrence ("still broken since ...") is recomputed against the record that
     * came before the one being corrected, not against the correction itself.
     */
    suspend fun correctInspection(
        inspectionId: String,
        parts: List<InspectionPartResult>,
        notes: String?,
        correctedByName: String?
    ): Result<Unit> {
        val existing = inspectionDao.getById(inspectionId)
            ?: return Result.failure(IllegalStateException("بازدید یافت نشد"))

        val previous = inspectionDao.getPreviousExcluding(existing.personId, inspectionId)
        val previousByName = previous?.let { InspectionJson.parse(it.partsJson).associateBy { p -> p.name } }.orEmpty()
        val partsWithRecurrence = parts.map { part ->
            if (part.status == PartStatus.OK) return@map part
            val prior = previousByName[part.name] ?: return@map part
            if (prior.status == PartStatus.OK) return@map part
            part.copy(recurringSinceTimestamp = prior.recurringSinceTimestamp ?: previous?.timestamp)
        }

        val approved = partsWithRecurrence.count { it.ok }
        val rejected = partsWithRecurrence.size - approved
        val updated = existing.copy(
            partsJson = InspectionJson.serialize(partsWithRecurrence),
            approvedCount = approved,
            rejectedCount = rejected,
            notes = notes?.trim()?.ifEmpty { null },
            correctedAt = System.currentTimeMillis(),
            correctedByName = correctedByName
        )
        return try {
            awaitWrite(inspectionsCol.document(inspectionId).set(updated))
            inspectionDao.upsert(updated)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(IllegalStateException(networkAwareMessage(e)))
        }
    }

    /** Closes the loop on one defect: stamps [InspectionPartResult.repairedAt] on the named
     *  part of the given inspection record, leaving everything else (including its own
     *  approved/rejected counts — this is history, not a re-inspection) untouched. Used by the
     *  open-defects list so "3 مورد ایراد" doesn't just sit there forever once someone actually
     *  fixes the mirror. */
    suspend fun markPartRepaired(inspectionId: String, partName: String): Result<Unit> {
        val fresh = inspectionDao.getById(inspectionId)
            ?: return Result.failure(IllegalStateException("بازدید یافت نشد"))
        val updatedParts = InspectionJson.parse(fresh.partsJson).map { part ->
            if (part.name == partName) part.copy(repairedAt = System.currentTimeMillis()) else part
        }
        val updated = fresh.copy(partsJson = InspectionJson.serialize(updatedParts))
        return try {
            awaitWrite(inspectionsCol.document(inspectionId).set(updated))
            inspectionDao.upsert(updated)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(IllegalStateException(networkAwareMessage(e)))
        }
    }

    /** Queries Firestore directly for the exact date range, same reasoning as getLogsInRange:
     *  exports must be complete regardless of what the unbounded-but-still-local mirror has
     *  pulled down at this exact moment. */
    suspend fun getInspectionsInRange(startInclusive: Long, endInclusive: Long): List<InspectionEntity> {
        return try {
            val snapshot = inspectionsCol
                .whereGreaterThanOrEqualTo("timestamp", startInclusive)
                .whereLessThanOrEqualTo("timestamp", endInclusive)
                .orderBy("timestamp", Query.Direction.ASCENDING)
                .get()
                .await()
            snapshot.documents.mapNotNull { it.toObject(InspectionEntity::class.java) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ---- Goods entry/exit ("ورود و خروج اقلام و کالاها") ----

    fun getItemLogs(direction: String): LiveData<List<ItemLogEntity>> = itemLogDao.getByDirection(direction)

    /** OUT records not yet marked returned — backs the "در انتظار برگشت" sub-tab. Deliberately
     *  not surfaced anywhere as an alert/badge, just a filterable list (see chat: return status
     *  isn't considered urgent enough to warrant one). */
    fun getPendingReturnItemLogs(): LiveData<List<ItemLogEntity>> = itemLogDao.getPendingReturns()

    fun getAllItemLogs(): LiveData<List<ItemLogEntity>> = itemLogDao.getAllLive()

    fun getItemLogsSince(sinceMillis: Long): LiveData<List<ItemLogEntity>> = itemLogDao.getSince(sinceMillis)

    /** Registers a "ورود کالا": item type, store, buyer, who ordered the purchase, department,
     *  invoice number. */
    suspend fun addItemLogIn(
        itemName: String,
        storeName: String?,
        buyerName: String?,
        orderedByName: String?,
        department: String?,
        invoiceNumber: String?,
        performedByUid: String?,
        performedByName: String?,
        checkpoint: String? = null
    ): Result<Unit> {
        val trimmedItem = itemName.trim()
        if (trimmedItem.isEmpty()) return Result.failure(IllegalArgumentException("نام قطعه یا جنس نمی‌تواند خالی باشد"))

        val docRef = itemLogsCol.document()
        val entity = ItemLogEntity(
            id = docRef.id,
            direction = ITEM_DIRECTION_IN,
            itemName = trimmedItem,
            timestamp = System.currentTimeMillis(),
            storeName = storeName?.trim()?.ifEmpty { null },
            buyerName = buyerName?.trim()?.ifEmpty { null },
            department = department?.trim()?.ifEmpty { null },
            invoiceNumber = invoiceNumber?.trim()?.ifEmpty { null },
            orderedByName = orderedByName?.trim()?.ifEmpty { null },
            performedByUid = performedByUid,
            performedByName = performedByName,
            checkpoint = checkpoint
        )
        return try {
            awaitWrite(docRef.set(entity))
            itemLogDao.upsert(entity) // instant local visibility, and durable if we're offline
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(IllegalStateException(networkAwareMessage(e)))
        }
    }

    /** Registers a "خروج کالا": item type, exit-slip number, carrier, whose order it left
     *  under, and the reason. [isReturned] always starts false — flipped later via
     *  [markItemReturned] once/if the item comes back. */
    suspend fun addItemLogOut(
        itemName: String,
        exitSlipNumber: String?,
        carrierName: String?,
        orderedByName: String?,
        reason: String?,
        performedByUid: String?,
        performedByName: String?,
        checkpoint: String? = null
    ): Result<Unit> {
        val trimmedItem = itemName.trim()
        if (trimmedItem.isEmpty()) return Result.failure(IllegalArgumentException("نام قطعه یا جنس نمی‌تواند خالی باشد"))

        val docRef = itemLogsCol.document()
        val entity = ItemLogEntity(
            id = docRef.id,
            direction = ITEM_DIRECTION_OUT,
            itemName = trimmedItem,
            timestamp = System.currentTimeMillis(),
            exitSlipNumber = exitSlipNumber?.trim()?.ifEmpty { null },
            carrierName = carrierName?.trim()?.ifEmpty { null },
            reason = reason?.trim()?.ifEmpty { null },
            isReturned = false,
            orderedByName = orderedByName?.trim()?.ifEmpty { null },
            performedByUid = performedByUid,
            performedByName = performedByName,
            checkpoint = checkpoint
        )
        return try {
            awaitWrite(docRef.set(entity))
            itemLogDao.upsert(entity)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(IllegalStateException(networkAwareMessage(e)))
        }
    }

    /** Ticks the "برگشت" checkbox: the item exited (e.g. for repair) has physically come back
     *  to the factory. */
    suspend fun markItemReturned(itemLogId: String): Result<Unit> {
        val fresh = itemLogDao.getById(itemLogId) ?: return Result.failure(IllegalStateException("مورد یافت نشد"))
        val now = System.currentTimeMillis()
        val updated = fresh.copy(isReturned = true, returnedAt = now)
        return try {
            awaitWrite(itemLogsCol.document(itemLogId).update(mapOf("isReturned" to true, "returnedAt" to now)))
            itemLogDao.upsert(updated)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(IllegalStateException(networkAwareMessage(e)))
        }
    }

    /** Queries Firestore directly for the exact date range, same reasoning as getLogsInRange —
     *  reports/exports must be complete regardless of what's mirrored locally right now. */
    suspend fun getItemLogsInRange(startInclusive: Long, endInclusive: Long): List<ItemLogEntity> {
        return try {
            val snapshot = itemLogsCol
                .whereGreaterThanOrEqualTo("timestamp", startInclusive)
                .whereLessThanOrEqualTo("timestamp", endInclusive)
                .orderBy("timestamp", Query.Direction.ASCENDING)
                .get()
                .await()
            snapshot.documents.mapNotNull { it.toObject(ItemLogEntity::class.java) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Every distinct value ever entered per field, fetched together in one round trip — powers
     *  the autocomplete dropdowns on the ورود/خروج forms. The suggestion pool grows on its own
     *  as new records are added (from any device), since it's read straight from the table. */
    suspend fun getItemLogSuggestions(): ItemLogSuggestions = ItemLogSuggestions(
        itemNames = itemLogDao.distinctItemNames(),
        stores = itemLogDao.distinctStores(),
        buyers = itemLogDao.distinctBuyers(),
        orderedBy = itemLogDao.distinctOrderedBy(),
        departments = itemLogDao.distinctDepartments(),
        carriers = itemLogDao.distinctCarriers(),
        reasons = itemLogDao.distinctReasons()
    )

    data class ItemLogSuggestions(
        val itemNames: List<String>,
        val stores: List<String>,
        val buyers: List<String>,
        val orderedBy: List<String>,
        val departments: List<String>,
        val carriers: List<String>,
        val reasons: List<String>
    )

    companion object {
        const val ACTION_IN = "IN"
        const val ACTION_OUT = "OUT"

        const val ITEM_DIRECTION_IN = "IN"
        const val ITEM_DIRECTION_OUT = "OUT"

        /** Minimum time between two events for the same person/machine — blocks accidental
         *  double-taps or a race between two guards tapping the same card at once. Short on
         *  purpose (just enough to catch an accidental double-tap): legitimate back-to-back
         *  check-ins/outs for the same person happen often enough in practice (e.g. a driver
         *  in and out again within a minute) that a longer window blocked real, intended uses. */
        private const val MIN_EVENT_INTERVAL_MS = 2_000L

        private const val TWENTY_FOUR_HOURS_MS = 24 * 60 * 60 * 1000L

        /** The redundant prefix earlier versions of [Fleet] baked into every machinery name —
         *  see [ensureMachineryPlateNamesCleaned]. */
        private const val LEGACY_PLATE_PREFIX = "پلاک"
    }

    // ---- Security reports ("گزارشات حراست": تخلف/حادثه/تقدیر/عمومی) ----

    fun getAllReports(): LiveData<List<ReportEntity>> = reportDao.getAllLive()

    suspend fun getReportById(reportId: String): ReportEntity? = reportDao.getById(reportId)

    /** Saves one report. [summaryText] is built by the caller (see [ReportCatalog.buildSummary])
     *  from the same fields, so the stored sentence always matches what the guard previewed on
     *  the form. [photoUri] is local-only, never sent to Firestore (see [ReportEntity] docs). */
    suspend fun submitReport(
        type: ReportType,
        category: String,
        severity: String?,
        subjectName: String?,
        location: String?,
        actionTaken: String?,
        description: String,
        summaryText: String,
        photoUri: String?,
        performedByUid: String?,
        performedByName: String?
    ): Result<Unit> {
        val docRef = reportsCol.document()
        val entity = ReportEntity(
            id = docRef.id,
            type = type.name,
            category = category.trim(),
            severity = severity,
            subjectName = subjectName?.trim()?.ifEmpty { null },
            location = location?.trim()?.ifEmpty { null },
            actionTaken = actionTaken,
            description = description.trim(),
            summaryText = summaryText,
            photoUri = photoUri,
            performedByUid = performedByUid,
            performedByName = performedByName,
            timestamp = System.currentTimeMillis()
        )
        return try {
            // photoUri is deliberately left out of what goes to Firestore (local-only, see class
            // docs) — write the version without it, but keep it in the local Room echo below.
            awaitWrite(reportsCol.document(docRef.id).set(entity.copy(photoUri = null)))
            reportDao.upsert(entity) // instant local visibility, and durable if we're offline
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(IllegalStateException(networkAwareMessage(e)))
        }
    }

    /** Overwrites an already-submitted report in place — same reasoning/pattern as
     *  [correctInspection]: a guard fixing a mistaken entry, not a new report. */
    suspend fun correctReport(
        reportId: String,
        category: String,
        severity: String?,
        subjectName: String?,
        location: String?,
        actionTaken: String?,
        description: String,
        summaryText: String,
        photoUri: String?,
        correctedByName: String?
    ): Result<Unit> {
        val existing = reportDao.getById(reportId)
            ?: return Result.failure(IllegalStateException("گزارش یافت نشد"))
        val updated = existing.copy(
            category = category.trim(),
            severity = severity,
            subjectName = subjectName?.trim()?.ifEmpty { null },
            location = location?.trim()?.ifEmpty { null },
            actionTaken = actionTaken,
            description = description.trim(),
            summaryText = summaryText,
            photoUri = photoUri ?: existing.photoUri,
            correctedAt = System.currentTimeMillis(),
            correctedByName = correctedByName
        )
        return try {
            awaitWrite(reportsCol.document(reportId).set(updated.copy(photoUri = null)))
            reportDao.upsert(updated)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(IllegalStateException(networkAwareMessage(e)))
        }
    }
}

/** Turns a raw Firestore/network exception into a Persian message safe to show directly. */
private fun networkAwareMessage(e: Exception): String {
    val msg = e.message ?: return "خطا در ارتباط با سرور. اتصال اینترنت را بررسی کنید."
    return when {
        msg.contains("UNAVAILABLE", ignoreCase = true) -> "اتصال اینترنت برقرار نیست. تغییرات پس از اتصال مجدد ثبت می‌شود."
        msg.contains("PERMISSION_DENIED", ignoreCase = true) -> "دسترسی مجاز نیست. با مدیر سیستم تماس بگیرید."
        else -> msg
    }
}

/** True for a failure that just means "couldn't reach the server right now" — connectivity
 *  dropping mid-write, a timeout, etc. — as opposed to a real error (bad permissions, invalid
 *  data). These are swallowed by [Repository.awaitWrite] because the write itself already sits
 *  safely in Firestore's local queue and will reach the server on its own once reconnected. */
private fun isConnectivityFailure(e: Exception): Boolean {
    if (e is FirebaseFirestoreException) {
        return e.code == FirebaseFirestoreException.Code.UNAVAILABLE ||
            e.code == FirebaseFirestoreException.Code.DEADLINE_EXCEEDED
    }
    val msg = e.message ?: return false
    return msg.contains("UNAVAILABLE", ignoreCase = true) || msg.contains("DEADLINE_EXCEEDED", ignoreCase = true)
}
