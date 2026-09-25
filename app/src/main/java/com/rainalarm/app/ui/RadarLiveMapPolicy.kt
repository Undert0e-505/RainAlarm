package com.rainalarm.app.ui

import com.rainalarm.app.data.CURRENT_LOCATION_ID
import com.rainalarm.app.data.RadarProviderKind
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

/**
 * Playback belongs to the logical place/provider choice, not to one downloaded RadarSession.
 * Refreshes replace the session object, while place/provider changes intentionally get a fresh
 * paused player.
 */
internal data class RadarPlaybackRefreshIdentity(
    val selectedPlaceId: String,
    val requestedProvider: RadarProviderKind?,
)

internal object RadarPlaybackRefreshPolicy {
    fun identity(
        selectedPlaceId: String,
        requestedProvider: RadarProviderKind?,
    ): RadarPlaybackRefreshIdentity = RadarPlaybackRefreshIdentity(selectedPlaceId, requestedProvider)
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
