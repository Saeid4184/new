package ir.factory.entryexit.data

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ReportDao {

    /** Used by the Firestore sync mirror and by submit/correct's instant local echo. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(report: ReportEntity)

    @Query("DELETE FROM reports WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM reports ORDER BY timestamp DESC")
    fun getAllLive(): LiveData<List<ReportEntity>>

    @Query("SELECT * FROM reports WHERE type = :type ORDER BY timestamp DESC")
    fun getByType(type: String): LiveData<List<ReportEntity>>

    @Query("SELECT * FROM reports WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ReportEntity?
}
