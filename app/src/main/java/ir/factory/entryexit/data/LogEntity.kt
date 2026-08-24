package ir.factory.entryexit.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.firebase.firestore.DocumentId

/**
 * An immutable historical record of a single check-in or check-out event — the basis for the
 * Excel export and the admin dashboard's activity feed. [id] is the Firestore document ID.
 * [personName]/[type]/[group] are denormalized (copied at event time) so history remains
 * accurate even if the person record is later edited.
 * [performedByUid]/[performedByName] record WHICH signed-in guard/admin made this entry —
 * this is the whole point of per-guard accounts: every record is attributable.
 * [detail] holds context specific to the event: department visited (visitors), assigned
 * vehicle (drivers), or cargo/load type (machinery departures).
 * [checkpoint] records which physical guard post logged this event — [Checkpoint.GATE] or
 * [Checkpoint.PARKING] — chosen by the guard at sign-in (see [Session.currentCheckpoint]).
 * Null for events logged before this existed, and always null for admin-performed events
 * since admins don't pick a post.
 *
 * Events are otherwise append-only, but [timestamp] can be corrected afterward — e.g. a guard
 * logs something a few minutes late and wants it to reflect when it actually happened (see
 * [Repository.editLogTimestamp]). [originalTimestamp] preserves the very first value so nothing
 * is silently lost, and [editedAt]/[editedByUid]/[editedByName] record that a correction
 * happened and by whom, for accountability. All null until the first edit.
 */
@Entity(tableName = "logs")
data class LogEntity(
    @PrimaryKey
    @DocumentId
    val id: String = "",
    val personId: String = "",
    val personName: String = "",
    val type: String = "",
    @ColumnInfo(name = "group_name") val group: String? = null,
    val action: String = "", // "IN" or "OUT"
    val timestamp: Long = 0L,
    val detail: String? = null,
    val performedByUid: String? = null,
    val performedByName: String? = null,
    val checkpoint: String? = null,
    val originalTimestamp: Long? = null,
    val editedAt: Long? = null,
    val editedByUid: String? = null,
    val editedByName: String? = null
)
