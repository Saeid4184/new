package ir.factory.entryexit.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.firebase.firestore.DocumentId

/**
 * One entry from the "گزارشات حراست" tab — a violation, incident/near-miss, positive
 * (commendation) or general report, filled out from [ReportCatalog]'s dropdowns rather than
 * free-form, then rendered into [summaryText] (see [ReportCatalog.buildSummary]) so the list
 * screen and any future export can show one clean sentence without re-deriving it.
 *
 * [type] is [ReportType.name]. [photoUri] is local-only (content:// URI), same reasoning as
 * [PersonEntity.imageUri] / [InspectionPartResult.photoUri] — never synced through Firestore,
 * only meaningful on the device that attached it.
 */
@Entity(tableName = "reports")
data class ReportEntity(
    @PrimaryKey
    @DocumentId
    val id: String = "",
    val type: String = "", // ReportType.name
    val category: String = "",
    val severity: String? = null,
    val subjectName: String? = null,
    val location: String? = null,
    val actionTaken: String? = null,
    val description: String = "",
    val summaryText: String = "",
    val photoUri: String? = null,
    val performedByUid: String? = null,
    val performedByName: String? = null,
    val timestamp: Long = 0L,
    val correctedAt: Long? = null,
    val correctedByName: String? = null
)
