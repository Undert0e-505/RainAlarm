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

/** Keeps successive Follow camera movements smooth without building an animation queue. */
internal object RadarFollowCameraPolicy {
    const val defaultDurationMillis = 1_000
    const val minimumDurationMillis = 500
    const val maximumDurationMillis = 1_200

    fun durationMillis(previousElapsedNanos: Long, currentElapsedNanos: Long): Int {
        if (previousElapsedNanos <= 0L || currentElapsedNanos <= previousElapsedNanos)
            return defaultDurationMillis
        return ((currentElapsedNanos - previousElapsedNanos) / 1_000_000L)
            .coerceIn(minimumDurationMillis.toLong(), maximumDurationMillis.toLong())
            .toInt()
    }
}
