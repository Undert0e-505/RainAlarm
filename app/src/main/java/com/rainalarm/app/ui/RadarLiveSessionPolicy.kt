package com.rainalarm.app.ui

import com.rainalarm.app.data.RadarSession
import com.rainalarm.app.data.RegionalRadarAreas
import com.rainalarm.app.data.SavedPlace
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

    fun canReuse(session: RadarSession, selected: SavedPlace): Boolean =
        canReuse(session.place, selected, session.region?.id)

    fun canReuse(loaded: SavedPlace, selected: SavedPlace, regionId: String?): Boolean {
        if (loaded.id != selected.id) return false
        // A previously misrouted session must be replaced even when the fix itself is unchanged.
        if (regionId != null && RegionalRadarAreas.forPoint(selected.latitude, selected.longitude)?.id != regionId) {
            return false
        }
        if (loaded.latitude == selected.latitude && loaded.longitude == selected.longitude) return true
        if (!selected.isCurrentLocation) return false
        if (regionId != null) {
            return true
        }
        val a = Math.toRadians(selected.latitude - loaded.latitude)
        val b = Math.toRadians(selected.longitude - loaded.longitude)
        val arc = sin(a / 2) * sin(a / 2) +
            cos(Math.toRadians(loaded.latitude)) * cos(Math.toRadians(selected.latitude)) *
            sin(b / 2) * sin(b / 2)
        return 2 * EARTH_RADIUS_METRES * asin(min(1.0, sqrt(arc))) < OPEN_RELOAD_METRES
    }
}
