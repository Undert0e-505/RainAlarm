package com.rainalarm.app.domain

import kotlin.math.floor
import kotlin.math.roundToInt

/** Clean-room hues with a gentle low-end opacity adaptation for the dark basemap. */
object RainAlarmPalette {
    data class Stop(val intensity: Float, val argb: Int)

    const val FIRST_VISIBLE_RAW = 63
    const val REGIONAL_TRACE_START_RAW = 52
    const val REGIONAL_RAMP_END_RAW = 83
    const val OPEN_FIRST_VISIBLE_ALPHA = 0.12f

    val stops = listOf(
        Stop(0.00f, 0x00000000),
        Stop(REGIONAL_TRACE_START_RAW / 255f, 0x00000000),
        Stop(83f / 255f, 0x4C83ECFF),
        Stop(93f / 255f, 0xFF23C5FF.toInt()),
        Stop(123f / 255f, 0xFF0077F7.toInt()),
        Stop(153f / 255f, 0xFF560039.toInt()),
        Stop(173f / 255f, 0xFF3F0015.toInt()),
        Stop(193f / 255f, 0xFF330000.toInt()),
        Stop(1.00f, 0xFF1F0000.toInt()),
    )

    fun colorAt(intensity: Float): Int {
        val value = intensity.coerceIn(0f, 1f)
        // Values below 52/255 stay transparent. Between 52 and 83 the source's
        // cyan hue is unchanged, but alpha rises continuously instead of
        // jumping at 63/255 against a near-black map.
        if (value <= REGIONAL_TRACE_START_RAW / 255f) return 0
        if (value < REGIONAL_RAMP_END_RAW / 255f) {
            val fraction = ((value * 255f - REGIONAL_TRACE_START_RAW) /
                (REGIONAL_RAMP_END_RAW - REGIONAL_TRACE_START_RAW)).coerceIn(0f, 1f)
            val eased = fraction * fraction * (3f - 2f * fraction)
            return (76f * eased).roundToInt().coerceIn(0, 76) shl 24 or 0x0083ECFF
        }
        val upper = stops.indexOfFirst { value <= it.intensity }.let { if (it < 0) stops.lastIndex else it }
        if (upper == 0) return stops.first().argb
        val from = stops[upper - 1]
        val to = stops[upper]
        val fraction = ((value - from.intensity) / (to.intensity - from.intensity)).coerceIn(0f, 1f)
        return interpolateArgb(from.argb, to.argb, fraction)
    }

    fun alphaAt(intensity: Float): Int = colorAt(intensity).ushr(24)

    /** Preserve RainViewer's r13 alpha-to-colour calibration unchanged. */
    fun colorAtOpen(intensity: Float): Int {
        val value = intensity.coerceIn(0f, 1f)
        if (value < FIRST_VISIBLE_RAW / 255f) return 0
        if (value < REGIONAL_RAMP_END_RAW / 255f) {
            val fraction = ((value * 255f - FIRST_VISIBLE_RAW) /
                (REGIONAL_RAMP_END_RAW - FIRST_VISIBLE_RAW)).coerceIn(0f, 1f)
            val alpha = (38f + fraction * 38f).roundToInt().coerceIn(38, 76)
            return alpha shl 24 or 0x0083ECFF
        }
        return colorAt(value)
    }

    /** RainViewer supplies opacity rather than regional grayscale; retain its r10 wet boundary. */
    fun fromOpenAlpha(alpha: Float): Float {
        val value = alpha.coerceIn(0f, 1f)
        if (value < OPEN_FIRST_VISIBLE_ALPHA) return 0f
        return FIRST_VISIBLE_RAW / 255f +
            (value - OPEN_FIRST_VISIBLE_ALPHA) / (1f - OPEN_FIRST_VISIBLE_ALPHA) *
            (1f - FIRST_VISIBLE_RAW / 255f)
    }

    private fun interpolateArgb(from: Int, to: Int, fraction: Float): Int {
        fun channel(shift: Int): Int {
            val a = (from ushr shift) and 0xff
            val b = (to ushr shift) and 0xff
            return (a + (b - a) * fraction).roundToInt().coerceIn(0, 255)
        }
        return (channel(24) shl 24) or (channel(16) shl 16) or
            (channel(8) shl 8) or channel(0)
    }
}

/** Reference bilinear transfer used to validate the one-fetch GL_LINEAR texture contract. */
object RadarLinearSampling {
    const val GPU_TEXTURE_TAPS = 1

    /** Bilinear lookup at source-pixel coordinates, with edge clamping. */
    fun bilinear(values: FloatArray, width: Int, height: Int, x: Double, y: Double): Float {
        require(width > 0 && height > 0 && values.size == width * height)
        val sx = x.coerceIn(0.0, (width - 1).toDouble())
        val sy = y.coerceIn(0.0, (height - 1).toDouble())
        val x0 = floor(sx).toInt()
        val y0 = floor(sy).toInt()
        val x1 = (x0 + 1).coerceAtMost(width - 1)
        val y1 = (y0 + 1).coerceAtMost(height - 1)
        val fx = (sx - x0).toFloat()
        val fy = (sy - y0).toFloat()
        val top = values[y0 * width + x0] * (1f - fx) + values[y0 * width + x1] * fx
        val bottom = values[y1 * width + x0] * (1f - fx) + values[y1 * width + x1] * fx
        return top * (1f - fy) + bottom * fy
    }
}
