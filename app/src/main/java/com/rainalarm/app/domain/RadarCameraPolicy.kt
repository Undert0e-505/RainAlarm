package com.rainalarm.app.domain

import com.rainalarm.app.data.SavedPlace
import kotlinx.serialization.Serializable

/** Scalar-only, in-memory camera state; no MapLibre view or network session is retained. */
@Serializable
data class RadarCameraSnapshot(
    val placeId: String,
    val placeLatitude: Double,
    val placeLongitude: Double,
    val centerLatitude: Double,
    val centerLongitude: Double,
    val zoom: Double,
)

data class RadarCameraTarget(val latitude: Double, val longitude: Double, val zoom: Double)

/** A header recenter is available only for the actively selected, resolved place. */
object RadarSelectedCenterPolicy {
    fun canCenter(selectedPlaceId: String, place: SavedPlace?): Boolean =
        place != null && place.id == selectedPlaceId &&
            place.latitude.isFinite() && place.longitude.isFinite()
}

object RadarCameraPolicy {
    private const val MAX_LATITUDE = 85.05112878
    private const val MAX_ZOOM = 21.0

    fun capture(place: SavedPlace, centerLatitude: Double, centerLongitude: Double, zoom: Double): RadarCameraSnapshot {
        require(centerLatitude.isFinite() && centerLongitude.isFinite() && zoom.isFinite())
        return RadarCameraSnapshot(
            place.id, place.latitude, place.longitude,
            centerLatitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE), wrapLongitude(centerLongitude),
            zoom.coerceIn(0.0, MAX_ZOOM),
        )
    }

    fun target(
        place: SavedPlace,
        widthPixels: Int,
        previous: RadarCameraSnapshot?,
        recenter: Boolean = false,
    ): RadarCameraTarget {
        val zoom = previous?.zoom?.takeIf { it.isFinite() }?.coerceIn(0.0, MAX_ZOOM)
            ?: RadarEntryZoom.forHorizontalMiles(place.latitude, widthPixels)
        if (previous == null || recenter) return RadarCameraTarget(place.latitude, place.longitude, zoom)
        return if (previous.placeId == place.id) {
            // A same-selection session replacement, tab return, refresh, or live Current
            // coordinate update must retain the user's exact viewport.
            RadarCameraTarget(previous.centerLatitude, previous.centerLongitude, zoom)
        } else {
            // A newly selected identity starts at its actual coordinate. Carry only zoom;
            // transferring the old place-relative pan offset made new saved places appear random.
            RadarCameraTarget(place.latitude, place.longitude, zoom)
        }
    }
    private fun wrapLongitude(value: Double) = ((value + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
}

/** Owned above tab navigation; a tick is consumed exactly once, including delayed map creation. */
class RadarCameraMemory(initialRecenterTick: Int = 0) {
    var snapshot: RadarCameraSnapshot? = null
        private set
    private var handledRecenterTick = initialRecenterTick

    fun hasPendingRecenter(recenterTick: Int): Boolean = recenterTick != handledRecenterTick

    fun capture(place: SavedPlace, centerLatitude: Double, centerLongitude: Double, zoom: Double) {
        snapshot = RadarCameraPolicy.capture(place, centerLatitude, centerLongitude, zoom)
    }

    fun target(place: SavedPlace, widthPixels: Int, recenterTick: Int): RadarCameraTarget {
        val recenter = recenterTick != handledRecenterTick
        handledRecenterTick = recenterTick
        return RadarCameraPolicy.target(place, widthPixels, snapshot, recenter)
    }
}
