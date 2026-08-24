package ir.factory.entryexit.data

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface LogDao {

    /** Used only by the Firestore sync mirror: insert-or-replace a document snapshot as-is. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(log: LogEntity)

    @Query("SELECT * FROM logs WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): LogEntity?

    @Query("DELETE FROM logs WHERE id = :id")
    suspend fun deleteById(id: String)

    /** After deleting a mistaken PARKING-checkpoint log, this is what's left for that vehicle
     *  at that checkpoint — used to recompute [PersonEntity.insideParking] so the deletion
     *  doesn't leave its status stuck wrong. */
    @Query("SELECT * FROM logs WHERE personId = :personId AND checkpoint = 'PARKING' ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestParkingLogForPerson(personId: String): LogEntity?

    /** Same as [getLatestParkingLogForPerson] but for every other checkpoint (GATE, or null for
     *  events logged before checkpoints existed) — used to recompute [PersonEntity.isInside]. */
    @Query("SELECT * FROM logs WHERE personId = :personId AND (checkpoint IS NULL OR checkpoint != 'PARKING') ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestGateLogForPerson(personId: String): LogEntity?

    /** Everything one specific guard/admin has personally logged, most recent first — the
     *  "my logged events" screen where they can go back and correct a timestamp. Capped so a
     *  very long-serving account doesn't try to load years of history at once. Admins use the
     *  existing [getRecent] instead to see every guard's logs, not just their own. */
    @Query("SELECT * FROM logs WHERE performedByUid = :uid ORDER BY timestamp DESC LIMIT :limit")
    fun getByPerformedByUid(uid: String, limit: Int = 500): LiveData<List<LogEntity>>

    @Query("SELECT * FROM logs WHERE personId = :personId ORDER BY timestamp DESC")
    fun getLogsForPerson(personId: String): LiveData<List<LogEntity>>

    @Query("SELECT * FROM logs ORDER BY timestamp DESC LIMIT :limit")
    fun getRecent(limit: Int = 20): LiveData<List<LogEntity>>

    @Query("SELECT * FROM logs WHERE type = :type ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentByType(type: String, limit: Int = 10): LiveData<List<LogEntity>>

    /** Used for the dump-truck "چند سرویس در ۲۴ ساعت" safety check: how many times has this one
     *  vehicle been dispatched (OUT) since [sinceMillis]. */
    @Query("SELECT COUNT(*) FROM logs WHERE personId = :personId AND action = :action AND timestamp >= :sinceMillis")
    suspend fun countActionsSince(personId: String, action: String, sinceMillis: Long): Int

    /** Used for the mixer "تعداد سرویس امروز" badge: every dispatch (OUT) for the whole
     *  Machinery roster since [sinceMillis] (local midnight), so the fragment can group it into
     *  a per-vehicle count client-side without one query per row. */
    @Query("SELECT * FROM logs WHERE type = :type AND action = :action AND timestamp >= :sinceMillis ORDER BY timestamp DESC")
    fun getActionsSince(type: String, action: String, sinceMillis: Long): LiveData<List<LogEntity>>
}
