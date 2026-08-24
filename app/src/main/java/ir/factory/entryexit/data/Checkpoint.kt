package ir.factory.entryexit.data

/**
 * Which physical guard post recorded an event. Guards pick one each time they sign in (see
 * [ir.factory.entryexit.ui.CheckpointPickerActivity]) rather than it being fixed on the account,
 * since the same person may cover either post on different days/shifts. Admins skip the picker
 * entirely — [Session.currentCheckpoint] stays null for them, which means "no filter, show
 * everything" everywhere this is checked.
 */
enum class Checkpoint(val displayName: String) {
    /** درب ورودی — controls every person/vehicle entering or leaving the factory grounds. */
    GATE("درب ورودی"),

    /** پارکینگ داخلی — machinery entering/leaving the internal parking area, mechanics/other
     *  visitors entering it, and parts/items leaving it. */
    PARKING("پارکینگ داخلی");

    companion object {
        fun fromStringOrNull(value: String?): Checkpoint? = runCatching { value?.let { valueOf(it) } }.getOrNull()
    }
}
