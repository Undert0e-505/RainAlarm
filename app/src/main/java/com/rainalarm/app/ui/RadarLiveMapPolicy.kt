package com.rainalarm.app.ui

import com.rainalarm.app.data.CURRENT_LOCATION_ID
import com.rainalarm.app.data.SavedPlace

/** A live marker is independent of the materially updated forecast/session place. */
internal object RadarLiveMapPolicy {
    fun marker(selected: SavedPlace, liveFix: SavedPlace?): SavedPlace =
        if (selected.isCurrentLocation) liveFix ?: selected else selected

    fun canFollow(selectedId: String, liveFix: SavedPlace?): Boolean =
        selectedId == CURRENT_LOCATION_ID && liveFix?.id == CURRENT_LOCATION_ID

    fun shouldCenterOnFix(selectedId: String, liveFix: SavedPlace?, following: Boolean): Boolean =
        following && canFollow(selectedId, liveFix)
}
