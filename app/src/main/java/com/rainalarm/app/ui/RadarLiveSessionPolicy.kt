package com.rainalarm.app.ui

import com.rainalarm.app.data.RadarProviderKind
import com.rainalarm.app.data.RadarProviderCapabilityResolver
import com.rainalarm.app.data.RadarSession
import com.rainalarm.app.data.RadarSessionCoverage
import com.rainalarm.app.data.PlaceCoordinatePolicy
import com.rainalarm.app.data.RegionalRadarAreas
import com.rainalarm.app.data.SavedPlace
import com.rainalarm.app.data.forecastSelectionKey
import com.rainalarm.app.domain.GeoQuad
import com.rainalarm.app.domain.GeoPoint
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A moving live marker need not invalidate cached regional imagery. */
internal object RadarLiveSessionPolicy {
    private const val OPEN_RELOAD_METRES = 5_000.0
    private const val EARTH_RADIUS_METRES = 6_371_000.0

    /** Null -> a resolved virtual Current fix starts its first load; later fixes keep the key. */
    fun loadIdentity(selected: SavedPlace?): String? = selected?.id

    fun canReuse(
        session: RadarSession,
        selected: SavedPlace,
        acquisitionAnchor: SavedPlace = session.place,
    ): Boolean {
        if (session.isReleased) return false
        // This is deliberately the same quantised alpha/polygon predicate as the map's shadow.
        // A broad domain, crop rectangle, or partially transparent edge is not reusable coverage.
        if (RadarSessionCoverage.coverageAt(
                session,
                GeoPoint(selected.latitude, selected.longitude),
            ) != true
        ) return false
        // A regional or retained raster is location-independent inside its published footprint.
        // Saved -> Travel and subsequent live fixes must not discard it merely because the point
        // moved more than the duplicate-place tolerance.
        if (session.region == null && session.regional == null && session.detail == null &&
            !selectionCompatible(acquisitionAnchor, selected)
        ) return false
        return canReuse(
            loaded = acquisitionAnchor,
            selected = selected,
            regionId = session.region?.id,
            // Open-provider screen sessions contain a place-centred detail crop as well as a
            // broader presentation raster. A new saved place may share the same coarse Meteo
            // region while lying outside the installed detail data. Prefer that exact crop for
            // saved selections; Current/Travel deliberately keeps using broad retained coverage.
            retainedBounds = if (selected.isCurrentLocation) {
                session.regional?.bounds ?: session.detail?.bounds
            } else {
                session.detail?.bounds ?: session.regional?.bounds
            },
        )
    }

    /**
     * Saved selections must re-evaluate the installed crop even when they share a coarse region.
     * Current keeps a region-stable key so high-cadence Travel fixes cannot cancel an acquisition.
     */
    fun acquisitionLocationKey(
        selected: SavedPlace?,
        session: RadarSession? = null,
        acquisitionAnchor: SavedPlace? = session?.place,
    ): String? = selected?.let selectedPlace@ { place ->
        if (!place.isCurrentLocation) return@selectedPlace forecastSelectionKey(place)
        // Outside the fixed provider regions, open providers install a place-centred regional
        // crop. Keep the effect key stable for every high-rate fix inside that exact crop, then
        // change it once at the real raster edge. Falling back to current:unbounded while the old
        // session is removed also prevents the replacement request from cancelling itself.
        if (session != null && acquisitionAnchor != null &&
            canReuse(session, place, acquisitionAnchor)
        ) {
            (session.regional?.bounds ?: session.detail?.bounds)?.let { retained ->
                return@selectedPlace "current:retained:${retained.stableKey()}"
            }
            session.region?.id?.let { return@selectedPlace "current:region:$it" }
        }
        RegionalRadarAreas.forPoint(place.latitude, place.longitude)?.id?.let { "current:region:$it" }
            ?: "current:unbounded"
    }

    fun providerCanServe(provider: RadarProviderKind, selected: SavedPlace): Boolean =
        RadarProviderCapabilityResolver.capability(
            provider,
            GeoPoint(selected.latitude, selected.longitude),
        ).geographicallyUsable

    /**
     * Mode/identity changes are free only when they represent the same effective coordinate.
     * Once Current owns the acquisition, its rapid fixes are governed by retained coverage below.
     */
    fun selectionCompatible(loaded: SavedPlace, selected: SavedPlace): Boolean = when {
        loaded.id == selected.id && loaded.isCurrentLocation -> true
        else -> PlaceCoordinatePolicy.distanceMetres(loaded, selected) <=
            PlaceCoordinatePolicy.DUPLICATE_DISTANCE_METRES
    }

    fun canReuse(
        loaded: SavedPlace,
        selected: SavedPlace,
        regionId: String?,
        retainedBounds: GeoQuad? = null,
    ): Boolean {
        // Open-provider crops are more specific than the logical regional label they carry.
        // Reuse them only while the selected point remains inside the installed raster.
        if (retainedBounds != null) return retainedBounds.contains(selected)
        // Fixed Meteo composites have no decoded tier bounds; their published region is the
        // actual reusable raster.
        if (regionId != null) {
            return RegionalRadarAreas.forPoint(selected.latitude, selected.longitude)?.id == regionId
        }
        if (loaded.latitude == selected.latitude && loaded.longitude == selected.longitude) return true
        if (!selected.isCurrentLocation && !loaded.isCurrentLocation) return false
        val a = Math.toRadians(selected.latitude - loaded.latitude)
        val b = Math.toRadians(selected.longitude - loaded.longitude)
        val arc = sin(a / 2) * sin(a / 2) +
            cos(Math.toRadians(loaded.latitude)) * cos(Math.toRadians(selected.latitude)) *
            sin(b / 2) * sin(b / 2)
        return 2 * EARTH_RADIUS_METRES * asin(min(1.0, sqrt(arc))) < OPEN_RELOAD_METRES
    }

    private fun GeoQuad.contains(place: SavedPlace): Boolean {
        val north = maxOf(topLeft.latitude, topRight.latitude)
        val south = minOf(bottomLeft.latitude, bottomRight.latitude)
        if (place.latitude !in south..north) return false
        val west = topLeft.longitude
        val east = topRight.longitude
        return if (west <= east) place.longitude in west..east
        else place.longitude >= west || place.longitude <= east
    }

    private fun GeoQuad.stableKey(): String = listOf(
        topLeft.latitude, topLeft.longitude,
        topRight.latitude, topRight.longitude,
        bottomRight.latitude, bottomRight.longitude,
        bottomLeft.latitude, bottomLeft.longitude,
    ).joinToString(":") { it.toBits().toString(16) }
}

internal data class RadarRainLoadIdentity(
    val requestedProvider: RadarProviderKind,
    val firstEligibleProvider: RadarProviderKind?,
    val showLikelySnow: Boolean,
)

/**
 * Visibility is not rain-data invalidation. A retained page loads only when its logical request,
 * explicit refresh generation, or reusable-session state says that it must.
 */
internal object RadarRainSessionLoadPolicy {
    fun identity(
        place: SavedPlace?,
        requestedProvider: RadarProviderKind,
        showLikelySnow: Boolean,
    ): RadarRainLoadIdentity? = place?.let {
        val point = GeoPoint(it.latitude, it.longitude)
        RadarRainLoadIdentity(
            requestedProvider = requestedProvider,
            // The preferred setting alone is not an acquisition identity: a point can remain in
            // one broad regional rectangle while crossing from Meteo coverage into OPERA.
            firstEligibleProvider = RadarProviderCapabilityResolver.firstEligible(
                requestedProvider,
                point,
            ),
            // Preserve the existing explicit config invalidation. This also covers a preferred
            // provider falling back to RainViewer without relying on the old session's outcome.
            showLikelySnow = showLikelySnow,
        )
    }

    fun shouldLoad(
        screenActive: Boolean,
        requested: RadarRainLoadIdentity?,
        installed: RadarRainLoadIdentity?,
        refreshGeneration: Int,
        installedRefreshGeneration: Int,
        hasReusableSession: Boolean,
    ): Boolean = screenActive && requested != null && (
        !hasReusableSession || requested != installed ||
            refreshGeneration != installedRefreshGeneration
        )
}
