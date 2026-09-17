package com.rainalarm.app.domain

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln

/** Five radar minutes per real second at 1×, wrapping without reloading frames. */
object RadarPlaybackClock {
    const val BASE_SECONDS_PER_SECOND = 300.0
    // A GL texture miss or resumed surface can delay one frame. Do not turn that one
    // delayed frame into a visible jump across a significant part of the radar interval.
    const val MAX_FRAME_ELAPSED_SECONDS = 0.05

    fun advance(cursorSeconds: Float, elapsedSeconds: Double, endSeconds: Float, speed: Float): Float {
        if (!cursorSeconds.isFinite() || !elapsedSeconds.isFinite() || !endSeconds.isFinite() ||
            endSeconds <= 0f || speed <= 0f || !speed.isFinite()) return 0f
        val elapsed = elapsedSeconds.coerceIn(0.0, MAX_FRAME_ELAPSED_SECONDS) * BASE_SECONDS_PER_SECOND * speed
        return ((cursorSeconds.coerceIn(0f, endSeconds).toDouble() + elapsed) % endSeconds).toFloat()
    }
}

/** Enter paused at the actual clock when a cached forecast covers it, otherwise show latest observation. */
object RadarEntryClock {
    fun initialCursor(
        firstEpochSeconds: Long,
        latestObservationEpochSeconds: Long,
        endEpochSeconds: Long,
        forecastAvailable: Boolean,
        nowEpochSeconds: Long,
    ): Float {
        val latest = (latestObservationEpochSeconds - firstEpochSeconds).coerceAtLeast(0L)
        return if (forecastAvailable && nowEpochSeconds in latestObservationEpochSeconds..endEpochSeconds) {
            (nowEpochSeconds - firstEpochSeconds).toFloat()
        } else latest.toFloat()
    }
}

/** Web-Mercator camera zoom giving a geographic span across the available map width. */
object RadarEntryZoom {
    private const val EQUATOR_METRES_PER_PIXEL = 156543.03392804097
    private const val METRES_PER_MILE = 1609.344

    fun forHorizontalMiles(latitude: Double, widthPixels: Int, miles: Double = 20.0): Double {
        require(latitude.isFinite() && latitude in -85.0..85.0)
        require(widthPixels > 0 && miles > 0.0 && miles.isFinite())
        val metresPerPixel = miles * METRES_PER_MILE / widthPixels
        return (ln(EQUATOR_METRES_PER_PIXEL * cos(latitude * PI / 180.0) / metresPerPixel) / ln(2.0))
            .coerceIn(3.0, 21.0)
    }

    fun horizontalMiles(latitude: Double, widthPixels: Int, zoom: Double): Double =
        EQUATOR_METRES_PER_PIXEL * cos(latitude * PI / 180.0) /
            Math.pow(2.0, zoom) * widthPixels / METRES_PER_MILE
}

data class RadarTimeTick(val epochSeconds: Long, val fraction: Float, val label: String)

object RadarTimelineTicks {
    const val SPACING_SECONDS = 30 * 60L

    fun between(startEpochSeconds: Long, endEpochSeconds: Long, zone: ZoneId): List<RadarTimeTick> {
        require(endEpochSeconds > startEpochSeconds)
        val first = Math.floorDiv(startEpochSeconds + SPACING_SECONDS - 1, SPACING_SECONDS) * SPACING_SECONDS
        val formatter = DateTimeFormatter.ofPattern("HH:mm")
        return generateSequence(first) { previous -> previous + SPACING_SECONDS }
            .takeWhile { it <= endEpochSeconds }
            .map { timestamp ->
                RadarTimeTick(
                    timestamp,
                    ((timestamp - startEpochSeconds).toDouble() /
                        (endEpochSeconds - startEpochSeconds)).toFloat().coerceIn(0f, 1f),
                    formatter.format(Instant.ofEpochSecond(timestamp).atZone(zone)),
                )
            }.toList()
    }
}
