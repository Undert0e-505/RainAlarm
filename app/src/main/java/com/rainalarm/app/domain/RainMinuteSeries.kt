package com.rainalarm.app.domain

import com.rainalarm.app.data.RadarPointSample
import kotlin.math.atan2
import kotlin.math.roundToInt

// The regional default LUT first becomes visible at source value 63/255.
// Open radar is alpha-encoded and retains its separately calibrated r10 cutoff.
const val RAIN_INTENSITY_THRESHOLD = 63f / 255f
const val OPEN_RAIN_INTENSITY_THRESHOLD = RainAlarmPalette.OPEN_FIRST_VISIBLE_ALPHA

enum class RadarIntensityEncoding { REGIONAL_GRAYSCALE, REGIONAL_AREA_CHART, OPEN_ALPHA, OPEN_REFLECTIVITY }

enum class RainMinuteAvailability { AVAILABLE, PARTIAL, UNAVAILABLE }

data class RainMinutePoint(
    val minute: Int,
    val minimum: Float,
    val average: Float,
    val maximum: Float,
    val forecast: Boolean,
) {
    init {
        require(minute in 0..60)
        require(minimum in 0f..1f && average in 0f..1f && maximum in 0f..1f)
        require(minimum <= average && average <= maximum)
    }
}

data class RainMinuteSeries(
    val startEpochSeconds: Long,
    val points: List<RainMinutePoint>,
    val sourceLabel: String,
    val confidence: Double,
    val availability: RainMinuteAvailability,
    val unavailableReason: String? = null,
    val latestObservationEpochSeconds: Long = startEpochSeconds,
    val travelBearingDegrees: Double? = null,
    val sourceBearingDegrees: Double? = travelBearingDegrees?.let { normalizeBearing(it + 180.0) },
    val intensityEncoding: RadarIntensityEncoding = RadarIntensityEncoding.REGIONAL_GRAYSCALE,
) {
    val wetThreshold: Float get() = when (intensityEncoding) {
        RadarIntensityEncoding.OPEN_ALPHA -> OPEN_RAIN_INTENSITY_THRESHOLD
        RadarIntensityEncoding.OPEN_REFLECTIVITY -> OpenRadarColorScale.WET_SEVERITY_THRESHOLD
        RadarIntensityEncoding.REGIONAL_GRAYSCALE -> RAIN_INTENSITY_THRESHOLD
        RadarIntensityEncoding.REGIONAL_AREA_CHART -> 0.25f
    }

    fun displayIntensity(value: Float): Float = if (intensityEncoding == RadarIntensityEncoding.OPEN_ALPHA) {
        RainAlarmPalette.fromOpenAlpha(value)
    } else value

    fun displayColor(value: Float): Int = if (intensityEncoding == RadarIntensityEncoding.OPEN_ALPHA) {
        RainAlarmPalette.colorAtOpen(displayIntensity(value))
    } else RainAlarmPalette.colorAt(value)

    /** Presentation only: zero at the source's wet boundary, without changing alert inputs. */
    fun chartSeverity(value: Float): Float = RadarChartSeverity.forSeries(value, intensityEncoding)

    init {
        require(confidence in 0.0..1.0)
        if (availability != RainMinuteAvailability.UNAVAILABLE) {
            require(points.isNotEmpty() && points.map { it.minute } == (0..points.last().minute).toList())
            if (availability == RainMinuteAvailability.AVAILABLE) require(points.last().minute == 60)
        }
    }

    companion object {
        fun unavailable(start: Long, source: String, reason: String) = RainMinuteSeries(
            start, emptyList(), source, 0.0, RainMinuteAvailability.UNAVAILABLE, reason,
        )
    }
}

data class RainMinuteAnalysis(
    val rainingNow: Boolean,
    val arrivalMinute: Int?,
    val endMinute: Int?,
    val maximum: Float,
)

object RainMinuteSeriesAnalyzer {
    fun analyze(series: RainMinuteSeries, threshold: Float = series.wetThreshold): RainMinuteAnalysis? {
        if (series.availability == RainMinuteAvailability.UNAVAILABLE || series.points.isEmpty()) return null
        val rainingNow = series.points.first().average >= threshold
        val arrival = if (rainingNow) 0 else series.points.indexOfFirst { it.average >= threshold }
            .takeIf { it >= 0 }
        val rainStarts = arrival ?: return RainMinuteAnalysis(false, null, null, series.points.maxOf { it.average })
        val ending = series.points.drop(rainStarts + 1).indexOfFirst { it.average < threshold }
            .takeIf { it >= 0 }
            ?.let { rainStarts + 1 + it }
        return RainMinuteAnalysis(rainingNow, arrival, ending, series.points.maxOf { it.average })
    }
}

object ProviderMinuteSeriesBuilder {
    const val MAX_OBSERVATION_AGE_SECONDS = 60 * 60L

    fun fromSamples(samples: List<RadarPointSample>, sourceLabel: String, nowEpochSeconds: Long): RainMinuteSeries {
        val observation = samples.indexOfLast { !it.forecast }
        if (observation < 0) return RainMinuteSeries.unavailable(nowEpochSeconds, sourceLabel, "No radar observation")
        val base = samples[observation]
        val age = nowEpochSeconds - base.time
        if (age < -300L || age > MAX_OBSERVATION_AGE_SECONDS) {
            return RainMinuteSeries.unavailable(nowEpochSeconds, sourceLabel, "Radar observation is stale or dated in the future")
        }
        val usable = samples.drop(observation).filter { it.time >= base.time }
        if (usable.size < 2 || usable.last().time < nowEpochSeconds) {
            return RainMinuteSeries.unavailable(nowEpochSeconds, sourceLabel, "Provider forecast does not reach the current time")
        }
        val bearing = base.travelBearingDegrees
        val lastMinute = ((usable.last().time - nowEpochSeconds) / 60L).toInt().coerceIn(0, 60)
        val points = (0..lastMinute).map { minute ->
            val target = nowEpochSeconds + minute * 60L
            val rightIndex = usable.indexOfFirst { it.time >= target }.coerceAtLeast(0)
            val right = usable[rightIndex]
            val left = usable.getOrElse((rightIndex - 1).coerceAtLeast(0)) { right }
            val fraction = if (right.time == left.time) 0f else
                ((target - left.time).toFloat() / (right.time - left.time)).coerceIn(0f, 1f)
            val average = RegionalPointInterpolation.atFraction(left, right, fraction)
            val minimum = minOf(lerp(left.minimum, right.minimum, fraction), average)
            val maximum = maxOf(lerp(left.maximum, right.maximum, fraction), average)
            RainMinutePoint(
                minute,
                minimum,
                average,
                maximum,
                target > base.time,
            )
        }
        return RainMinuteSeries(
            nowEpochSeconds,
            points,
            sourceLabel,
            1.0,
            if (lastMinute == 60) RainMinuteAvailability.AVAILABLE else RainMinuteAvailability.PARTIAL,
            unavailableReason = if (lastMinute == 60) null else "Provider forecast ends at +$lastMinute min",
            latestObservationEpochSeconds = base.time,
            travelBearingDegrees = bearing,
        )
    }

    private fun lerp(a: Float, b: Float, fraction: Float): Float = a + (b - a) * fraction
}

/** The compact point equivalent of the regional GLES shader's symmetric local-velocity warp. */
object RegionalPointInterpolation {
    fun atFraction(left: RadarPointSample, right: RadarPointSample, fraction: Float): Float {
        val t = fraction.coerceIn(0f, 1f)
        val from = left.regionalPatch
        val to = right.regionalPatch
        if (from == null || to == null) return left.intensity + (right.intensity - left.intensity) * t
        // The renderer binds the first frame's velocity texture; a missing texture is neutral.
        val vx = left.localVelocityX ?: 0f
        val vy = left.localVelocityY ?: 0f
        val a = from.sample(-vx * t, -vy * t)
        val b = to.sample(vx * (1f - t), vy * (1f - t))
        return (a + (b - a) * t).coerceIn(0f, 1f)
    }
}

object OpenMinuteSeriesBuilder {
    const val MINIMUM_CONFIDENCE = 0.18
    const val MAX_OBSERVATION_AGE_SECONDS = 15 * 60L

    fun fromDenseField(
        grid: IntensityGrid,
        field: RadarVelocityField?,
        startEpochSeconds: Long,
        sourceLabel: String,
        nowEpochSeconds: Long = startEpochSeconds,
    ): RainMinuteSeries {
        val age = nowEpochSeconds - startEpochSeconds
        if (age < -300L || age > MAX_OBSERVATION_AGE_SECONDS) {
            return RainMinuteSeries.unavailable(nowEpochSeconds, sourceLabel, "Open radar observation is stale")
        }
        val reliable = field?.takeIf { it.confidence >= MINIMUM_CONFIDENCE }
            ?: return RainMinuteSeries.unavailable(
                startEpochSeconds,
                sourceLabel,
                "No reliable local radar motion is available",
            )
        val displacement = reliable.displacementAt(0.5, 0.5)
        val travelBearing = bearingForVector(displacement.first, displacement.second)
        val points = (0..60).map { minute ->
            val elapsedMinutes = (nowEpochSeconds - startEpochSeconds) / 60.0 + minute
            val source = DenseRadarAdvection.sourcePoint(
                0.5, 0.5, elapsedMinutes, reliable, grid.width, grid.height,
            )
            val envelope = grid.envelopeAround(source.first, source.second)
            RainMinutePoint(minute, envelope.minimum, envelope.average, envelope.maximum, minute > 0 || age > 0)
        }
        return RainMinuteSeries(
            nowEpochSeconds,
            points,
            sourceLabel,
            reliable.confidence,
            RainMinuteAvailability.AVAILABLE,
            latestObservationEpochSeconds = startEpochSeconds,
            travelBearingDegrees = travelBearing,
            intensityEncoding = RadarIntensityEncoding.OPEN_REFLECTIVITY,
        )
    }
}

data class IntensityEnvelope(val minimum: Float, val average: Float, val maximum: Float)

fun IntensityGrid.envelopeAround(centerX: Double, centerY: Double, radius: Int = 2): IntensityEnvelope {
    val values = ArrayList<Float>((radius * 2 + 1) * (radius * 2 + 1))
    val x = centerX.roundToInt()
    val y = centerY.roundToInt()
    for (py in y - radius..y + radius) for (px in x - radius..x + radius) {
        if (px in 0 until width && py in 0 until height) values += get(px, py).coerceIn(0f, 1f)
    }
    if (values.isEmpty()) return IntensityEnvelope(0f, 0f, 0f)
    return IntensityEnvelope(values.min(), values.average().toFloat(), values.max())
}

fun bearingForVector(dx: Double, dy: Double): Double? {
    if (!dx.isFinite() || !dy.isFinite() || kotlin.math.hypot(dx, dy) < 0.05) return null
    return normalizeBearing(Math.toDegrees(atan2(dx, -dy)))
}

fun normalizeBearing(value: Double): Double = ((value % 360.0) + 360.0) % 360.0

fun cardinalDirection(bearing: Double): String {
    val labels = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    return labels[((normalizeBearing(bearing) + 22.5) / 45.0).toInt() % labels.size]
}

object NowVisualGeometry {
    data class RainDropOutline(
        val firstJoin: Pair<Float, Float>,
        val tip: Pair<Float, Float>,
        val secondJoin: Pair<Float, Float>,
        val arcStartDegrees: Float,
        val arcSweepDegrees: Float,
    )

    fun compassPoint(bearingDegrees: Double, radius: Float): Pair<Float, Float> {
        val radians = Math.toRadians(normalizeBearing(bearingDegrees) - 90.0)
        return (kotlin.math.cos(radians) * radius).toFloat() to
            (kotlin.math.sin(radians) * radius).toFloat()
    }

    /** Tangent sides and a 270° back arc form one circular-bodied drop with a 90° outward tip. */
    fun rainDropOutline(bearingDegrees: Double, radius: Float): RainDropOutline {
        require(radius > 0f && radius.isFinite() && bearingDegrees.isFinite())
        val canvasAngle = (normalizeBearing(bearingDegrees) - 90.0).toFloat()
        return RainDropOutline(
            firstJoin = compassPoint(bearingDegrees - 45.0, radius),
            tip = compassPoint(bearingDegrees, radius * kotlin.math.sqrt(2f)),
            secondJoin = compassPoint(bearingDegrees + 45.0, radius),
            arcStartDegrees = canvasAngle + 45f,
            arcSweepDegrees = 270f,
        )
    }

    fun markerDistance(initial: Float, final: Float, progress: Float): Float =
        initial + (final - initial) * progress.coerceIn(0f, 1f)

    fun shouldAnimateRainMarker(hasRain: Boolean, bearingDegrees: Double?, animationsEnabled: Boolean): Boolean =
        hasRain && bearingDegrees?.isFinite() == true && animationsEnabled

    fun chartX(minute: Int, width: Float): Float = minute.coerceIn(0, 60) / 60f * width
    fun chartY(intensity: Float, height: Float): Float = (1f - intensity.coerceIn(0f, 1f)) * height
}
