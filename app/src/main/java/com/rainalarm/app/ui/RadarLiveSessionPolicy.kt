package com.rainalarm.app.ui

import com.rainalarm.app.data.RadarProviderKind
import com.rainalarm.app.data.RadarSession
import com.rainalarm.app.data.PlaceCoordinatePolicy
import com.rainalarm.app.data.RegionalRadarAreas
import com.rainalarm.app.data.SavedPlace
import com.rainalarm.app.domain.GeoQuad
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
        if (!selectionCompatible(acquisitionAnchor, selected)) return false
        return canReuse(
            loaded = acquisitionAnchor,
            selected = selected,
            regionId = session.region?.id,
            retainedBounds = session.regional?.bounds ?: session.detail?.bounds,
        )
    }

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
        // Saved <-> virtual Current is a presentation/marker change, not an acquisition change.
        // Fixed regional composites remain reusable while both points resolve to the same feed.
        if (regionId != null) {
            return RegionalRadarAreas.forPoint(selected.latitude, selected.longitude)?.id == regionId
        }
        // Open-provider screen sessions retain a regional raster. Reuse it for any point it
        // already covers, regardless of whether that point arrived as Saved or virtual Current.
        if (retainedBounds != null) return retainedBounds.contains(selected)
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
}

internal data class RadarRainLoadIdentity(
    val requestedProvider: RadarProviderKind,
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
        RadarRainLoadIdentity(
            requestedProvider = requestedProvider,
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
