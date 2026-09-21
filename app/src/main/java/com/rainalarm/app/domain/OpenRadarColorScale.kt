package com.rainalarm.app.domain

import kotlin.math.sqrt

enum class OpenPrecipitationType { RAIN, LIKELY_SNOW }

data class OpenRadarDecoded(
    val severity: Float,
    val sharedIntensity: Float,
    val coverage: Float,
    val type: OpenPrecipitationType,
    val snowConfidence: Float,
)

/** Universal Blue inversion shared by renderer preprocessing and point forecasts. */
object OpenRadarColorScale {
    private data class Anchor(val dbz: Float, val rgb: Int)
    private data class Match(val dbz: Float, val distance: Float)

    private val rain = listOf(
        Anchor(-10f, 0x636159), Anchor(-5f, 0x726E61), Anchor(0f, 0x827B69),
        Anchor(5f, 0x928871), Anchor(10f, 0xCEC087), Anchor(14f, 0xDED097),
        Anchor(15f, 0x88DDEE), Anchor(20f, 0x00A3E0), Anchor(25f, 0x0077AA),
        Anchor(30f, 0x005588), Anchor(34f, 0x004768), Anchor(35f, 0xFFEE00),
        Anchor(40f, 0xFFAA00), Anchor(44f, 0xFF8100), Anchor(45f, 0xFF4400),
        Anchor(50f, 0xC10000), Anchor(54f, 0x5D0000), Anchor(55f, 0xFFAAFF),
        Anchor(60f, 0xFF77FF), Anchor(64f, 0xFF4EFF), Anchor(65f, 0xFFFFFF),
    )
    private val snow = listOf(
        Anchor(-10f, 0xCFFFFF), Anchor(0f, 0xC7FFFF), Anchor(10f, 0xBFFFFF),
        Anchor(15f, 0x9FDFFF), Anchor(20f, 0x7FBFFF), Anchor(25f, 0x5F9FFF),
        Anchor(30f, 0x4F8FFF), Anchor(35f, 0x3F7FFF), Anchor(40f, 0x2F6FFF),
        Anchor(45f, 0x1F5FFF), Anchor(50f, 0x0F4FFF), Anchor(55f, 0x003FFF),
        Anchor(60f, 0x002FFF), Anchor(65f, 0x001FFF),
    )

    const val WET_SEVERITY_THRESHOLD = RAIN_INTENSITY_THRESHOLD
    const val SNOW_CLASSIFICATION_THRESHOLD = 0.60f

    fun fromArgb(argb: Int): Float = decode(argb, false).severity

    fun decode(argb: Int, distinguishSnow: Boolean): OpenRadarDecoded {
        val coverage = ((argb ushr 24) and 0xff) / 255f
        if (coverage == 0f) return OpenRadarDecoded(0f, 0f, 0f, OpenPrecipitationType.RAIN, 0f)
        val rgb = argb and 0x00ffffff
        val rainMatch = match(rgb, rain)
        val snowMatch = if (distinguishSnow) match(rgb, snow) else null
        val denominator = rainMatch.distance + (snowMatch?.distance ?: 0f)
        val snowConfidence = if (snowMatch == null || snowMatch.distance >= rainMatch.distance) 0f
        else if (denominator <= 0.0001f) 1f else (rainMatch.distance / denominator).coerceIn(0f, 1f)
        val likelySnow = snowConfidence >= SNOW_CLASSIFICATION_THRESHOLD
        val dbz = if (likelySnow) requireNotNull(snowMatch).dbz else rainMatch.dbz
        val sharedIntensity = sharedIntensityForDbz(dbz)
        return OpenRadarDecoded(
            pointSeverity(sharedIntensity, coverage),
            sharedIntensity,
            coverage,
            if (likelySnow) OpenPrecipitationType.LIKELY_SNOW else OpenPrecipitationType.RAIN,
            snowConfidence,
        )
    }

    /** Shared app intensity; retained under the old name for source compatibility. */
    fun severityForDbz(dbz: Float): Float = sharedIntensityForDbz(dbz)

    fun sharedIntensityForDbz(dbz: Float): Float = when {
        dbz <= -10f -> 0f
        dbz < 0f -> lerp(0f, RainAlarmPalette.REGIONAL_TRACE_START_RAW / 255f, (dbz + 10f) / 10f)
        dbz < 15f -> lerp(RainAlarmPalette.REGIONAL_TRACE_START_RAW / 255f, 83f / 255f, dbz / 15f)
        dbz < 30f -> lerp(83f / 255f, 123f / 255f, (dbz - 15f) / 15f)
        dbz < 45f -> lerp(123f / 255f, 153f / 255f, (dbz - 30f) / 15f)
        dbz < 65f -> lerp(153f / 255f, 193f / 255f, (dbz - 45f) / 20f)
        else -> lerp(193f / 255f, 1f, ((dbz - 65f) / 30f).coerceIn(0f, 1f))
    }

    /** Match point classification to a non-zero 8-bit map result after layer opacity. */
    fun pointSeverity(sharedIntensity: Float, coverage: Float): Float {
        val renderedAlpha = RainAlarmPalette.alphaAt(sharedIntensity).toFloat() *
            coverage.coerceIn(0f, 1f) * 0.90f
        return if (renderedAlpha >= 1f) sharedIntensity.coerceIn(0f, 1f) else 0f
    }

    private fun match(rgb: Int, anchors: List<Anchor>): Match {
        val red = ((rgb ushr 16) and 0xff).toFloat()
        val green = ((rgb ushr 8) and 0xff).toFloat()
        val blue = (rgb and 0xff).toFloat()
        var best = Match(anchors.first().dbz, Float.POSITIVE_INFINITY)
        for (index in 0 until anchors.lastIndex) {
            val a = anchors[index]; val b = anchors[index + 1]
            val ar = ((a.rgb ushr 16) and 0xff).toFloat(); val ag = ((a.rgb ushr 8) and 0xff).toFloat()
            val ab = (a.rgb and 0xff).toFloat(); val dr = ((b.rgb ushr 16) and 0xff) - ar
            val dg = ((b.rgb ushr 8) and 0xff) - ag; val db = (b.rgb and 0xff) - ab
            val lengthSquared = dr * dr + dg * dg + db * db
            val t = if (lengthSquared == 0f) 0f else
                (((red - ar) * dr + (green - ag) * dg + (blue - ab) * db) / lengthSquared).coerceIn(0f, 1f)
            val rr = red - (ar + dr * t); val gg = green - (ag + dg * t); val bb = blue - (ab + db * t)
            val distance = sqrt(rr * rr + gg * gg + bb * bb)
            if (distance < best.distance) best = Match(a.dbz + (b.dbz - a.dbz) * t, distance)
        }
        return best
    }

    private fun lerp(from: Float, to: Float, fraction: Float) = from + (to - from) * fraction.coerceIn(0f, 1f)
}

object SnowAlarmPalette {
    fun colorAt(intensity: Float): Int {
        val value = intensity.coerceIn(0f, 1f)
        val alpha = RainAlarmPalette.alphaAt(value)
        if (alpha == 0) return 0
        val rgb = when {
            value <= 83f / 255f -> interpolate(0xD9E5FF, 0xC8B7FF, value / (83f / 255f))
            value <= 123f / 255f -> interpolate(0xC8B7FF, 0x8264E8, (value - 83f / 255f) / (40f / 255f))
            value <= 153f / 255f -> interpolate(0x8264E8, 0x4B3198, (value - 123f / 255f) / (30f / 255f))
            else -> interpolate(0x4B3198, 0x180B46, (value - 153f / 255f) / (102f / 255f))
        }
        return (alpha shl 24) or rgb
    }

    private fun interpolate(from: Int, to: Int, fraction: Float): Int {
        val f = fraction.coerceIn(0f, 1f)
        fun channel(shift: Int) = ((((from ushr shift) and 0xff) * (1f - f) +
            ((to ushr shift) and 0xff) * f).toInt()).coerceIn(0, 255)
        return (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}
