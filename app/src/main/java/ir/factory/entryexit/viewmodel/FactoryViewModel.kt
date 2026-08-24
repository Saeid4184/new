package ir.factory.entryexit.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.switchMap
import androidx.lifecycle.viewModelScope
import ir.factory.entryexit.data.AppDatabase
import ir.factory.entryexit.data.CloudSync
import ir.factory.entryexit.data.InspectionEntity
import ir.factory.entryexit.data.InspectionPartResult
import ir.factory.entryexit.data.LogEntity
import ir.factory.entryexit.data.PersonEntity
import ir.factory.entryexit.data.PersonType
import ir.factory.entryexit.data.ReportEntity
import ir.factory.entryexit.data.ReportType
import ir.factory.entryexit.data.Repository
import ir.factory.entryexit.data.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Single ViewModel shared by MainActivity and all four tab fragments
 * (via `by activityViewModels()`), so every screen sees the same live data.
 */
class FactoryViewModel(app: Application) : AndroidViewModel(app) {

    val repository: Repository = run {
        val db = AppDatabase.getInstance(app)
        Repository(app.applicationContext, db.personDao(), db.logDao(), CloudSync(db), db.inspectionDao(), db.itemLogDao(), db.reportDao())
    }

    init {
        // Only starts pulling data once a user is actually signed in (LoginActivity gates this).
        if (Session.isSignedIn()) {
            repository.startSync()
            viewModelScope.launch {
                // Each of these is a separate one-time migration guarded by its own Firestore
                // flag. They're intentionally isolated with their own try/catch: if one throws
                // (e.g. a transient network error), the rest should still get a chance to run
                // instead of the whole chain dying on the first failure.
                runCatching { repository.ensureFleetSeeded() }
                runCatching { repository.ensureDriversSeeded() }
                runCatching { repository.ensureMachineryPlateNamesCleaned() }
                runCatching { repository.ensureMachineryDriverNamesBackfilled() }
                runCatching { repository.ensureDriverCategoriesSeeded() }
            }
        }
    }

    fun personsByType(type: PersonType): LiveData<List<PersonEntity>> = repository.getPersonsByType(type)

    fun insideByType(type: PersonType): LiveData<List<PersonEntity>> = repository.getInsidePersonsByType(type)

    fun allCurrentlyInside(): LiveData<List<PersonEntity>> = repository.getAllCurrentlyInside()

    fun recentActivity(type: PersonType): LiveData<List<LogEntity>> = repository.getRecentActivityByType(type)

    private val searchQuery = MutableLiveData("")
    val searchResults: LiveData<List<PersonEntity>> = searchQuery.switchMap { query ->
        if (query.isBlank()) {
            MutableLiveData<List<PersonEntity>>(emptyList())
        } else {
            repository.search(query)
        }
    }

    fun setSearchQuery(query: String) {
        searchQuery.value = query
    }

    fun search(query: String): LiveData<List<PersonEntity>> = repository.search(query)

    fun addPerson(
        name: String,
        type: PersonType,
        group: String?,
        extraInfo: String?,
        onResult: (Result<String>) -> Unit
    ) {
        viewModelScope.launch { onResult(repository.addPerson(name, type, group, extraInfo)) }
    }

    fun checkIn(personId: String, detail: String? = null, onResult: (Result<PersonEntity>) -> Unit) {
        val user = Session.currentUser
        val checkpoint = Session.currentCheckpoint?.name
        viewModelScope.launch {
            val result = repository.checkIn(personId, detail, user?.uid, user?.name, checkpoint)
            if (result.isSuccess) triggerBackup()
            onResult(result)
        }
    }

    fun checkOut(personId: String, detail: String? = null, onResult: (Result<PersonEntity>) -> Unit) {
        val user = Session.currentUser
        val checkpoint = Session.currentCheckpoint?.name
        viewModelScope.launch {
            val result = repository.checkOut(personId, detail, user?.uid, user?.name, checkpoint)
            if (result.isSuccess) triggerBackup()
            onResult(result)
        }
    }

    fun checkInVisitor(name: String, department: String, onResult: (Result<Unit>) -> Unit) {
        val user = Session.currentUser
        val checkpoint = Session.currentCheckpoint?.name
        viewModelScope.launch {
            val result = repository.checkInVisitor(name, department, user?.uid, user?.name, checkpoint)
            if (result.isSuccess) triggerBackup()
            onResult(result)
        }
    }

    fun checkInDriver(name: String, vehicle: String, onResult: (Result<Unit>) -> Unit) {
        val user = Session.currentUser
        val checkpoint = Session.currentCheckpoint?.name
        viewModelScope.launch {
            val result = repository.checkInDriver(name, vehicle, user?.uid, user?.name, checkpoint)
            if (result.isSuccess) triggerBackup()
            onResult(result)
        }
    }

    /** Machinery roster + check-in/out for the parking guard's screen — see
     *  [ir.factory.entryexit.data.PersonEntity.insideParking] for why this is independent of
     *  the regular [checkIn]/[checkOut] above. */
    fun machineryForParking(): LiveData<List<PersonEntity>> = repository.getMachineryForParking()

    /** "My logged events" screen: guards see only their own, admins see everyone's. */
    fun myLogs(): LiveData<List<LogEntity>> {
        val user = Session.currentUser
        return if (Session.isAdmin() || user?.uid == null) repository.getRecentLogs() else repository.getMyLogs(user.uid)
    }

    fun editLogTimestamp(logId: String, newTimestamp: Long, onResult: (Result<Unit>) -> Unit) {
        val user = Session.currentUser
        val isAdmin = Session.isAdmin()
        viewModelScope.launch {
            val result = repository.editLogTimestamp(logId, newTimestamp, user?.uid, user?.name, isAdmin)
            if (result.isSuccess) triggerBackup()
            onResult(result)
        }
    }

    /** Deletes a mistakenly logged event — e.g. an accidental double-tap or the wrong person
     *  picked by mistake. Same permission rule as [editLogTimestamp]: guards can only delete
     *  their own logs, admins can delete any. */
    fun deleteLog(logId: String, onResult: (Result<Unit>) -> Unit) {
        val user = Session.currentUser
        val isAdmin = Session.isAdmin()
        viewModelScope.launch {
            val result = repository.deleteLog(logId, user?.uid, isAdmin)
            if (result.isSuccess) triggerBackup()
            onResult(result)
        }
    }

    fun checkInParking(personId: String, onResult: (Result<PersonEntity>) -> Unit) {
        val user = Session.currentUser
        viewModelScope.launch {
            val result = repository.checkInParking(personId, user?.uid, user?.name)
            if (result.isSuccess) triggerBackup()
            onResult(result)
        }
    }

    fun checkOutParking(personId: String, reason: String? = null, onResult: (Result<PersonEntity>) -> Unit) {
        val user = Session.currentUser
        viewModelScope.launch {
            val result = repository.checkOutParking(personId, reason, user?.uid, user?.name)
            if (result.isSuccess) triggerBackup()
            onResult(result)
        }
    }

    private suspend fun triggerBackup() {
        withContext(Dispatchers.IO) {
            ir.factory.entryexit.util.BackupManager.backupNow(getApplication())
        }
    }

    fun updatePersonImage(personId: String, imageUri: String?, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch { onResult(repository.updatePersonImage(personId, imageUri)) }
    }

    fun updatePerson(personId: String, name: String, group: String?, extraInfo: String?, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch { onResult(repository.updatePerson(personId, name, group, extraInfo)) }
    }

    fun setBlacklisted(personId: String, blacklisted: Boolean, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch { onResult(repository.setBlacklisted(personId, blacklisted)) }
    }

    fun loadRosterOnce(type: PersonType, onResult: (List<PersonEntity>) -> Unit) {
        viewModelScope.launch {
            val roster = withContext(Dispatchers.IO) { repository.getRosterOnce(type) }
            onResult(roster)
        }
    }

    fun exportRange(startInclusive: Long, endInclusive: Long, onResult: (List<LogEntity>) -> Unit) {
        viewModelScope.launch {
            val logs = withContext(Dispatchers.IO) { repository.getLogsInRange(startInclusive, endInclusive) }
            onResult(logs)
        }
    }

    /** Dump-truck/dump-trailer 24h dispatch-count safety check (see [Repository
     *  .countDispatchesInLast24h]) — checked right after a machinery checkout completes. */
    fun countDispatchesInLast24h(personId: String, onResult: (Int) -> Unit) {
        viewModelScope.launch {
            val count = withContext(Dispatchers.IO) { repository.countDispatchesInLast24h(personId) }
            onResult(count)
        }
    }

    /** Live per-vehicle "سرویس امروز" feed for mixers — see [Repository.getMachineryDispatchesSince]. */
    fun machineryDispatchesSince(sinceMillis: Long): LiveData<List<LogEntity>> =
        repository.getMachineryDispatchesSince(sinceMillis)

    fun currentlyInsideCounts(onResult: (Map<PersonType, Int>) -> Unit) {
        viewModelScope.launch {
            val counts = withContext(Dispatchers.IO) {
                PersonType.values().associateWith { repository.countCurrentlyInside(it) }
            }
            onResult(counts)
        }
    }

    // ---- Weekly machinery inspections ("بازدید ظاهری") ----

    fun allInspections(): LiveData<List<InspectionEntity>> = repository.getAllInspections()

    fun getPerson(personId: String, onResult: (PersonEntity?) -> Unit) {
        viewModelScope.launch {
            val person = withContext(Dispatchers.IO) { repository.getPersonById(personId) }
            onResult(person)
        }
    }

    fun submitInspection(
        person: PersonEntity,
        parts: List<InspectionPartResult>,
        notes: String?,
        onResult: (Result<Unit>) -> Unit
    ) {
        val user = Session.currentUser
        viewModelScope.launch {
            val result = repository.submitInspection(person, parts, notes, user?.uid, user?.name)
            if (result.isSuccess) triggerBackup()
            onResult(result)
        }
    }

    fun inspectionsInRange(startInclusive: Long, endInclusive: Long, onResult: (List<InspectionEntity>) -> Unit) {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { repository.getInspectionsInRange(startInclusive, endInclusive) }
            onResult(list)
        }
    }

    /** Used by [ir.factory.entryexit.ui.InspectionFormActivity] to pre-flag still-open defects
     *  from this vehicle's previous inspection on the diagram before the guard taps anything. */
    fun latestInspectionFor(personId: String, onResult: (InspectionEntity?) -> Unit) {
        viewModelScope.launch {
            val inspection = withContext(Dispatchers.IO) { repository.getLatestInspectionForPerson(personId) }
            onResult(inspection)
        }
    }

    /** Loads the record being fixed, for [ir.factory.entryexit.ui.InspectionFormActivity]'s
     *  edit/correction mode. */
    fun inspectionById(inspectionId: String, onResult: (InspectionEntity?) -> Unit) {
        viewModelScope.launch {
            val inspection = withContext(Dispatchers.IO) { repository.getInspectionById(inspectionId) }
            onResult(inspection)
        }
    }

    fun latestInspectionForExcluding(personId: String, excludeId: String, onResult: (InspectionEntity?) -> Unit) {
        viewModelScope.launch {
            val inspection = withContext(Dispatchers.IO) {
                repository.getLatestInspectionForPersonExcluding(personId, excludeId)
            }
            onResult(inspection)
        }
    }

    /** Overwrites an already-submitted inspection in place — a guard fixing a mistaken entry,
     *  not a new week's inspection (see [ir.factory.entryexit.data.Repository.correctInspection]). */
    fun correctInspection(
        inspectionId: String,
        parts: List<InspectionPartResult>,
        notes: String?,
        onResult: (Result<Unit>) -> Unit
    ) {
        val user = Session.currentUser
        viewModelScope.launch {
            val result = repository.correctInspection(inspectionId, parts, notes, user?.name)
            if (result.isSuccess) triggerBackup()
            onResult(result)
        }
    }

    /** Marks one part on one past inspection record as repaired — the repair-closure step of
     *  the open-defects list. */
    fun markPartRepaired(inspectionId: String, partName: String, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            val result = repository.markPartRepaired(inspectionId, partName)
            if (result.isSuccess) triggerBackup()
            onResult(result)
        }
    }

    // ---- Goods entry/exit ("ورود و خروج اقلام و کالاها") ----

    fun itemLogsByDirection(direction: String): LiveData<List<ir.factory.entryexit.data.ItemLogEntity>> =
        repository.getItemLogs(direction)

    fun pendingReturnItemLogs(): LiveData<List<ir.factory.entryexit.data.ItemLogEntity>> =
        repository.getPendingReturnItemLogs()

    fun itemLogsSince(sinceMillis: Long): LiveData<List<ir.factory.entryexit.data.ItemLogEntity>> =
        repository.getItemLogsSince(sinceMillis)

    fun addItemLogIn(
        itemName: String,
        storeName: String?,
        buyerName: String?,
        orderedByName: String?,
        department: String?,
        invoiceNumber: String?,
        onResult: (Result<Unit>) -> Unit
    ) {
        val user = Session.currentUser
        val checkpoint = Session.currentCheckpoint?.name
        viewModelScope.launch {
            val result = repository.addItemLogIn(itemName, storeName, buyerName, orderedByName, department, invoiceNumber, user?.uid, user?.name, checkpoint)
            if (result.isSuccess) triggerBackup()
            onResult(result)
        }
    }

    fun addItemLogOut(
        itemName: String,
        exitSlipNumber: String?,
        carrierName: String?,
        orderedByName: String?,
        reason: String?,
        onResult: (Result<Unit>) -> Unit
    ) {
        val user = Session.currentUser
        val checkpoint = Session.currentCheckpoint?.name
        viewModelScope.launch {
            val result = repository.addItemLogOut(itemName, exitSlipNumber, carrierName, orderedByName, reason, user?.uid, user?.name, checkpoint)
            if (result.isSuccess) triggerBackup()
            onResult(result)
        }
    }

    fun markItemReturned(itemLogId: String, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            val result = repository.markItemReturned(itemLogId)
            if (result.isSuccess) triggerBackup()
            onResult(result)
        }
    }

    fun itemLogsInRange(startInclusive: Long, endInclusive: Long, onResult: (List<ir.factory.entryexit.data.ItemLogEntity>) -> Unit) {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { repository.getItemLogsInRange(startInclusive, endInclusive) }
            onResult(list)
        }
    }

    /** Fetched fresh every time a ورود/خروج dialog opens, so suggestions always reflect the
     *  latest values entered from any device. */
    fun itemLogSuggestions(onResult: (Repository.ItemLogSuggestions) -> Unit) {
        viewModelScope.launch {
            val suggestions = withContext(Dispatchers.IO) { repository.getItemLogSuggestions() }
            onResult(suggestions)
        }
    }

    override fun onCleared() {
        super.onCleared()
        repository.stopSync()
    }

    // ---- Security reports ("گزارشات حراست") ----

    fun allReports(): LiveData<List<ReportEntity>> = repository.getAllReports()

    fun reportById(reportId: String, onResult: (ReportEntity?) -> Unit) {
        viewModelScope.launch {
            val report = withContext(Dispatchers.IO) { repository.getReportById(reportId) }
            onResult(report)
        }
    }

    fun submitReport(
        type: ReportType,
        category: String,
        severity: String?,
        subjectName: String?,
        location: String?,
        actionTaken: String?,
        description: String,
        summaryText: String,
        photoUri: String?,
        onResult: (Result<Unit>) -> Unit
    ) {
        val user = Session.currentUser
        viewModelScope.launch {
            val result = repository.submitReport(
                type, category, severity, subjectName, location, actionTaken,
                description, summaryText, photoUri, user?.uid, user?.name
            )
            if (result.isSuccess) triggerBackup()
            onResult(result)
        }
    }

    fun correctReport(
        reportId: String,
        category: String,
        severity: String?,
        subjectName: String?,
        location: String?,
        actionTaken: String?,
        description: String,
        summaryText: String,
        photoUri: String?,
        onResult: (Result<Unit>) -> Unit
    ) {
        val user = Session.currentUser
        viewModelScope.launch {
            val result = repository.correctReport(
                reportId, category, severity, subjectName, location, actionTaken,
                description, summaryText, photoUri, user?.name
            )
            if (result.isSuccess) triggerBackup()
            onResult(result)
        }
    }
}
