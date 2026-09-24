package com.rainalarm.app.ui

import com.rainalarm.app.data.CURRENT_LOCATION_ID

/** Non-fatal messages that belong inside the map without changing its layout. */
internal enum class RadarMapNoticeKind(
    val priority: Int,
    val lifetimeMillis: Long?,
) {
    RENDERER_FAILURE(priority = 450, lifetimeMillis = null),
    MAP_STYLE(priority = 400, lifetimeMillis = null),
    RENDERER_COMPATIBILITY(priority = 200, lifetimeMillis = 4_500L),
    CHART_TIME(priority = 100, lifetimeMillis = 4_500L),
}

internal data class RadarMapNotice(
    val kind: RadarMapNoticeKind,
    val message: String,
)

internal object RadarMapNoticePolicy {
    /** Leaves MapLibre's bottom-left attribution/info control unobstructed. */
    const val attributionStartReservationDp = 44
    const val bottomInsetDp = 8
    const val trailingGapDp = 8
    const val bottomRightStatusReservationDp = 216
    const val absoluteMaxWidthDp = 240
    const val maximumNoticeHeightDp = 30

    fun select(vararg candidates: RadarMapNotice?): RadarMapNotice? = candidates
        .asSequence()
        .filterNotNull()
        .filter { it.message.isNotBlank() }
        .maxWithOrNull(compareBy<RadarMapNotice> { it.kind.priority }.thenBy { it.message })

    fun isVisible(kind: RadarMapNoticeKind, elapsedMillis: Long): Boolean =
        kind.lifetimeMillis?.let { elapsedMillis < it } ?: true

    fun maxWidthDp(mapWidthDp: Int, bottomRightOccupied: Boolean): Int {
        val trailing = if (bottomRightOccupied) bottomRightStatusReservationDp else trailingGapDp
        return (mapWidthDp - attributionStartReservationDp - trailing)
            .coerceIn(72, absoluteMaxWidthDp)
    }

    /** On narrow maps the system rail moves above the weather stack instead of overlapping it. */
    fun noticeBottomInsetDp(mapWidthDp: Int, bottomRightEntryCount: Int): Int =
        if (mapWidthDp < 320 && bottomRightEntryCount > 0) {
            RadarPreparationStackPolicy.bottomInsetDp +
                RadarPreparationStackPolicy.estimatedHeightDp(bottomRightEntryCount) + 4
        } else bottomInsetDp

    fun sharesBottomRow(mapWidthDp: Int, bottomRightEntryCount: Int): Boolean =
        mapWidthDp >= 320 && bottomRightEntryCount > 0

    fun minimumSafeCombinedMapHeightDp(bottomRightEntryCount: Int): Int =
        RadarPreparationStackPolicy.minimumSafeMapHeightDp(bottomRightEntryCount) +
            maximumNoticeHeightDp + 4
}

/** Keeps unresolved live-location state separate from a real radar-provider failure. */
internal object CurrentLocationPresentationPolicy {
    fun retainCurrentSession(
        currentSelected: Boolean,
        hasResolvedPlace: Boolean,
        sessionBelongsToCurrent: Boolean,
    ): Boolean = currentSelected && !hasResolvedPlace && sessionBelongsToCurrent

    fun operationalStatus(
        currentSelected: Boolean,
        hasResolvedPlace: Boolean,
        locating: Boolean,
        capabilityMessage: String?,
        requestMessage: String?,
    ): RadarRefreshOverlay? = when {
        !currentSelected -> null
        locating -> WeatherDataStatusPolicy.loading(WeatherDataKind.LOCATION).asOverlay()
        !requestMessage.isNullOrBlank() || !capabilityMessage.isNullOrBlank() || !hasResolvedPlace ->
            WeatherDataStatusPolicy.unavailable(WeatherDataKind.LOCATION).asOverlay()
        else -> null
    }

    private fun String.asOverlay(): RadarRefreshOverlay = RadarRefreshOverlay(this, this)

    fun retainCurrentForecast(currentSelected: Boolean, displayedForecastKey: String?): Boolean =
        currentSelected && displayedForecastKey?.startsWith("$CURRENT_LOCATION_ID:") == true
}

/** Coordinates, not radar-frame readiness, decide whether the interactive basemap can exist. */
internal object RadarBaseMapPresentationPolicy {
    fun showInteractiveMap(hasResolvedPlace: Boolean, hasRetainedCurrentCoordinates: Boolean): Boolean =
        hasResolvedPlace || hasRetainedCurrentCoordinates
}
