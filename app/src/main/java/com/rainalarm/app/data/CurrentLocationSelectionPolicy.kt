package com.rainalarm.app.data

/** A granted request selects the virtual place immediately; fix acquisition is a separate job. */
data class CurrentLocationTransition(
    val allowed: Boolean,
    val selectedId: String,
    val requestGeneration: Long,
)

object CurrentLocationSelectionPolicy {
    fun request(selectedId: String, generation: Long, permissionGranted: Boolean): CurrentLocationTransition =
        if (permissionGranted) {
            CurrentLocationTransition(true, CURRENT_LOCATION_ID, generation + 1)
        } else {
            CurrentLocationTransition(false, selectedId, generation)
        }
}
