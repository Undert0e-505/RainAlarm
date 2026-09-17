package com.rainalarm.app.domain

import com.rainalarm.app.data.RadarSession
import com.rainalarm.app.data.SavedPlace
import kotlin.math.floor
import kotlin.math.roundToInt
import androidx.core.graphics.get

data class RadarPointTimeline(
    val currentlyWet: Boolean,
    val wetForecastMinutes: List<Int>,
    val latestObservationEpochSeconds: Long,
)

object ProviderRadarPointEvaluator {
    const val WET_INTENSITY = RAIN_INTENSITY_THRESHOLD

    fun evaluate(session: RadarSession, place: SavedPlace = session.place): RadarPointTimeline? {
        session.legacyArchive?.let { archive ->
            val observationIndex = archive.pointSamples.indexOfLast { !it.forecast }
            if (observationIndex < 0) return null
            val current = archive.pointSamples[observationIndex]
            val wetForecast = archive.pointSamples.drop(observationIndex + 1).mapNotNull { sample ->
                if (!sample.forecast) return@mapNotNull null
                val minutes = ((sample.time - current.time) / 60.0).roundToInt()
                minutes.takeIf { it in 1..60 && sample.intensity >= WET_INTENSITY }
            }
            return RadarPointTimeline(current.intensity >= WET_INTENSITY, wetForecast, current.time)
        }
        val tier = session.regional ?: session.detail ?: return null
        val observationIndex = tier.frames.indexOfLast { !it.frame.forecast }
        if (observationIndex < 0) return null
        val current = sample(tier.frames[observationIndex].bitmap, tier.bounds, place, session.region) ?: return null
        val observationTime = tier.frames[observationIndex].frame.time
        val wetForecast = tier.frames.drop(observationIndex + 1).mapNotNull { frame ->
            if (!frame.frame.forecast) return@mapNotNull null
            val minutes = ((frame.frame.time - observationTime) / 60.0).roundToInt()
            if (minutes !in 1..60) return@mapNotNull null
            minutes.takeIf { (sample(frame.bitmap, tier.bounds, place, session.region) ?: 0f) >= WET_INTENSITY }
        }
        return RadarPointTimeline(current >= WET_INTENSITY, wetForecast, observationTime)
    }

    fun sample(
        bitmap: android.graphics.Bitmap,
        bounds: GeoQuad,
        place: SavedPlace,
        region: com.rainalarm.app.data.RegionalRadarArea? = null,
    ): Float? {
        if (bitmap.isRecycled) return null
        val north = bounds.topLeft.latitude
        val west = bounds.topLeft.longitude
        val south = bounds.bottomRight.latitude
        val east = bounds.bottomRight.longitude
        if (place.latitude !in south..north || place.longitude !in west..east) return null
        val projected = region?.let { RegionalProjection.pixelFor(it, place.latitude, place.longitude) }
        val x = (projected?.x?.let(::floor)?.toInt()
            ?: floor((place.longitude - west) / (east - west) * bitmap.width).toInt())
            .coerceIn(0, bitmap.width - 1)
        val y = (projected?.y?.let(::floor)?.toInt()
            ?: floor((north - place.latitude) / (north - south) * bitmap.height).toInt())
            .coerceIn(0, bitmap.height - 1)
        var wet = 0
        var samples = 0
        for (py in (y - 2).coerceAtLeast(0)..(y + 2).coerceAtMost(bitmap.height - 1)) {
            for (px in (x - 2).coerceAtLeast(0)..(x + 2).coerceAtMost(bitmap.width - 1)) {
                val color = bitmap[px, py]
                if (((color shr 16) and 0xff) / 255f >= WET_INTENSITY) wet++
                samples++
            }
        }
        return if (samples == 0) null else wet.toFloat() / samples
    }
}
