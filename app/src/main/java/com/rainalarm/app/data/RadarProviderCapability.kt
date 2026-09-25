package com.rainalarm.app.data

import com.rainalarm.app.domain.GeoPoint
import com.rainalarm.app.domain.MeteoNominalCoverage

/**
 * Geographic/provider capability is deliberately separate from a transient download failure.
 * SUPPORTED means the provider has a product domain at the point but no live coverage mask has
 * been inspected yet; COVERED/UNCOVERED are evidence from the provider's current/static mask.
 */
enum class RadarProviderCoverageState {
    SUPPORTED,
    COVERED,
    UNCOVERED,
    OUTSIDE_DOMAIN,
}

data class RadarProviderCapability(
    val provider: RadarProviderKind,
    val state: RadarProviderCoverageState,
) {
    val geographicallyUsable: Boolean
        get() = state != RadarProviderCoverageState.OUTSIDE_DOMAIN &&
            state != RadarProviderCoverageState.UNCOVERED
}

/** Pure location policy shared by foreground Radar, Now, alerts and Settings. */
object RadarProviderCapabilityResolver {
    fun capability(provider: RadarProviderKind, point: GeoPoint): RadarProviderCapability {
        val state = when (provider) {
            RadarProviderKind.METEOGROUP_REGIONAL ->
                RegionalRadarAreas.forPoint(point.latitude, point.longitude)?.let { area ->
                    when (MeteoNominalCoverage.covers(area.id, point)) {
                        true -> RadarProviderCoverageState.COVERED
                        false -> RadarProviderCoverageState.UNCOVERED
                        null -> RadarProviderCoverageState.SUPPORTED
                    }
                } ?: RadarProviderCoverageState.OUTSIDE_DOMAIN

            RadarProviderKind.EUMETNET_OPERA ->
                if (OperaProductDomain.contains(point)) RadarProviderCoverageState.SUPPORTED
                else RadarProviderCoverageState.OUTSIDE_DOMAIN

            // RainViewer's API is worldwide. Actual radar reach is refined to COVERED or
            // UNCOVERED from its published coverage product after that mask is downloaded.
            RadarProviderKind.OPEN_RAINVIEWER -> RadarProviderCoverageState.SUPPORTED
        }
        return RadarProviderCapability(provider, state)
    }

    fun capabilities(point: GeoPoint): Map<RadarProviderKind, RadarProviderCapability> =
        RadarProviderKind.entries.associateWith { capability(it, point) }

    /** A missing/failed mask is unknown and therefore leaves static eligibility unchanged. */
    fun refine(
        capability: RadarProviderCapability,
        publishedCovered: Boolean?,
    ): RadarProviderCapability = when (publishedCovered) {
        true -> capability.copy(state = RadarProviderCoverageState.COVERED)
        false -> capability.copy(state = RadarProviderCoverageState.UNCOVERED)
        null -> capability
    }

    fun providersFor(preferred: RadarProviderKind, point: GeoPoint): List<RadarProviderKind> {
        val eligible = listOf(
            RadarProviderKind.METEOGROUP_REGIONAL,
            RadarProviderKind.EUMETNET_OPERA,
            RadarProviderKind.OPEN_RAINVIEWER,
        ).filter { capability(it, point).geographicallyUsable }
        // Preference controls only the first attempt. Remaining candidates keep the quality
        // order regional -> European composite -> worldwide composite without moving the pin.
        return buildList {
            if (preferred in eligible) add(preferred)
            addAll(eligible.filterNot { it == preferred })
        }
    }

    fun firstEligible(
        preferred: RadarProviderKind,
        point: GeoPoint,
        rainViewerCovered: Boolean? = null,
    ): RadarProviderKind? = providersFor(preferred, point).firstOrNull { provider ->
        provider != RadarProviderKind.OPEN_RAINVIEWER || rainViewerCovered != false
    }

    fun displayName(provider: RadarProviderKind): String = when (provider) {
        RadarProviderKind.METEOGROUP_REGIONAL -> "MeteoGroup"
        RadarProviderKind.EUMETNET_OPERA -> "OPERA"
        RadarProviderKind.OPEN_RAINVIEWER -> "RainViewer"
    }

    fun fallbackMessage(selection: RadarProviderSelection): String? {
        if (selection.requested == selection.active) return null
        return "${displayName(selection.requested)} unavailable here · using " +
            displayName(selection.active)
    }
}

object RadarProviderNoticePolicy {
    fun identity(placeKey: String, selection: RadarProviderSelection): String? = when {
        selection.coverageState == RadarProviderCoverageState.UNCOVERED ->
            "$placeKey:${selection.requested}:${selection.active}:uncovered"
        selection.requested != selection.active ->
            "$placeKey:${selection.requested}:${selection.active}:fallback"
        else -> null
    }

    fun message(selection: RadarProviderSelection): String? = when {
        selection.coverageState == RadarProviderCoverageState.UNCOVERED ->
            "Radar unavailable at this location"
        selection.requested != selection.active ->
            RadarProviderCapabilityResolver.fallbackMessage(selection)
        else -> null
    }
}

class RadarProviderNoticeDeduplicator {
    private var lastIdentity: String? = null

    @Synchronized
    fun shouldShow(logicalPlaceKey: String, selection: RadarProviderSelection): Boolean {
        val identity = RadarProviderNoticePolicy.identity(logicalPlaceKey, selection)
        if (identity == null) {
            lastIdentity = null
            return false
        }
        if (identity == lastIdentity) return false
        lastIdentity = identity
        return true
    }
}
