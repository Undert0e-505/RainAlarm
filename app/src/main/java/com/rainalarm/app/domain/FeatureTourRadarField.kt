package com.rainalarm.app.domain

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin

/**
 * Deterministic, local precipitation used only by the feature tour.
 *
 * The field is generated once and translated as a whole by the renderer. Keeping the texture and
 * motion separate means scrubbing cannot regenerate or flicker the synthetic weather system.
 */
internal object FeatureTourRadarField {
    const val WIDTH = 240
    const val HEIGHT = 360
    const val SEED = 0x51A7C3
    const val FIELD_PADDING = 0.18f

    data class MotionOffset(val xFraction: Float, val yFraction: Float)

    fun generate(width: Int = WIDTH, height: Int = HEIGHT): FloatArray {
        require(width > 1 && height > 1)
        return FloatArray(width * height) { index ->
            val fieldSpan = 1f + FIELD_PADDING * 2f
            val x = -FIELD_PADDING + (index % width).toFloat() / (width - 1) * fieldSpan
            val y = -FIELD_PADDING + (index / width).toFloat() / (height - 1) * fieldSpan
            intensityAt(x, y)
        }
    }

    fun motionOffset(progress: Float): MotionOffset {
        val safe = progress.coerceIn(0f, 1f)
        return MotionOffset(
            xFraction = -0.10f + 0.20f * safe,
            yFraction = 0.11f - 0.21f * safe,
        )
    }

    /** Samples the translated field in viewport coordinates; useful for deterministic validation. */
    fun intensityAtViewport(x: Float, y: Float, progress: Float): Float {
        val offset = motionOffset(progress)
        return intensityAt(x - offset.xFraction, y - offset.yFraction)
    }

    fun intensityAt(x: Float, y: Float): Float {
        if (x < -0.18f || x > 1.18f || y < -0.18f || y > 1.18f) return 0f

        // A broad southern system, a broken curved feeder band and detached northeastern showers.
        // Overlapping ellipses are only the low-frequency envelope; seeded noise defines every
        // visible edge, gap and core so none of the source geometry reads as a primitive.
        var envelope = 0f
        envelope = maxOf(envelope, 1.00f * lobe(x, y, 0.35f, 0.88f, 0.49f, 0.30f, -0.10f))
        envelope = maxOf(envelope, 0.91f * lobe(x, y, 0.72f, 0.83f, 0.41f, 0.27f, 0.14f))
        envelope = maxOf(envelope, 0.82f * lobe(x, y, -0.02f, 0.80f, 0.33f, 0.22f, -0.25f))
        envelope = maxOf(envelope, 0.62f * lobe(x, y, 0.13f, 0.74f, 0.20f, 0.095f, -0.72f))
        envelope = maxOf(envelope, 0.66f * lobe(x, y, 0.27f, 0.66f, 0.20f, 0.085f, -0.70f))
        envelope = maxOf(envelope, 0.73f * lobe(x, y, 0.40f, 0.57f, 0.19f, 0.080f, -0.68f))
        envelope = maxOf(envelope, 0.79f * lobe(x, y, 0.53f, 0.46f, 0.20f, 0.088f, -0.66f))
        envelope = maxOf(envelope, 0.70f * lobe(x, y, 0.67f, 0.35f, 0.21f, 0.092f, -0.65f))
        envelope = maxOf(envelope, 0.67f * lobe(x, y, 0.80f, 0.25f, 0.23f, 0.105f, -0.62f))
        envelope = maxOf(envelope, 0.89f * lobe(x, y, 0.91f, 0.20f, 0.32f, 0.23f, -0.36f))

        // Detached showers make the presentation read as radar rather than one continuous object.
        envelope = maxOf(envelope, 0.47f * lobe(x, y, 0.05f, 0.55f, 0.055f, 0.040f, -0.3f))
        envelope = maxOf(envelope, 0.43f * lobe(x, y, 0.22f, 0.47f, 0.045f, 0.060f, 0.7f))
        envelope = maxOf(envelope, 0.42f * lobe(x, y, 0.58f, 0.20f, 0.045f, 0.035f, 0.1f))
        envelope = maxOf(envelope, 0.52f * lobe(x, y, 0.91f, 0.57f, 0.060f, 0.085f, -0.4f))
        envelope = maxOf(envelope, 0.39f * lobe(x, y, 0.74f, 0.69f, 0.040f, 0.055f, 0.5f))

        val broad = fbm(x * 4.6f, y * 4.6f, SEED)
        val detail = fbm(x * 12.5f + 7.1f, y * 12.5f - 3.7f, SEED xor 0x2C91)
        val speckle = valueNoise(x * 29f - 5.3f, y * 29f + 8.6f, SEED xor 0x793D)
        var signal = envelope * (0.48f + broad * 0.76f) + (detail - 0.54f) * 0.27f

        // Irregular dry slots within the large southern and northeastern masses.
        signal -= 0.42f * lobe(x, y, 0.45f, 0.82f, 0.13f, 0.085f, -0.5f)
        signal -= 0.34f * lobe(x, y, 0.70f, 0.90f, 0.085f, 0.125f, 0.2f)
        signal -= 0.31f * lobe(x, y, 0.88f, 0.20f, 0.095f, 0.070f, -0.2f)

        // A variable threshold fragments weak sections while keeping strong areas coherent.
        val threshold = 0.205f + (0.5f - speckle) * 0.11f
        val precipitation = smoothStep(threshold, threshold + 0.47f, signal)
        return (precipitation * (0.58f + detail * 0.24f)).coerceIn(0f, 1f)
    }

    private fun lobe(
        x: Float,
        y: Float,
        centerX: Float,
        centerY: Float,
        radiusX: Float,
        radiusY: Float,
        rotationRadians: Float,
    ): Float {
        val dx = x - centerX
        val dy = y - centerY
        val cosine = cos(rotationRadians)
        val sine = sin(rotationRadians)
        val rotatedX = (dx * cosine + dy * sine) / radiusX
        val rotatedY = (-dx * sine + dy * cosine) / radiusY
        return exp((-2.15f * (rotatedX * rotatedX + rotatedY * rotatedY)).toDouble()).toFloat()
    }

    private fun fbm(x: Float, y: Float, seed: Int): Float {
        var frequency = 1f
        var amplitude = 0.56f
        var total = 0f
        var normalizer = 0f
        repeat(4) { octave ->
            total += valueNoise(x * frequency, y * frequency, seed + octave * 0x1F31) * amplitude
            normalizer += amplitude
            frequency *= 2.03f
            amplitude *= 0.49f
        }
        return total / normalizer
    }

    private fun valueNoise(x: Float, y: Float, seed: Int): Float {
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val tx = smoothCurve(x - x0)
        val ty = smoothCurve(y - y0)
        val a = hashUnit(x0, y0, seed)
        val b = hashUnit(x0 + 1, y0, seed)
        val c = hashUnit(x0, y0 + 1, seed)
        val d = hashUnit(x0 + 1, y0 + 1, seed)
        return lerp(lerp(a, b, tx), lerp(c, d, tx), ty)
    }

    private fun hashUnit(x: Int, y: Int, seed: Int): Float {
        var value = x * 0x1F123BB5 + y * 0x5F356495 + seed
        value = value xor (value ushr 15)
        value *= 0x2C1B3C6D
        value = value xor (value ushr 12)
        value *= 0x297A2D39
        value = value xor (value ushr 15)
        return (value ushr 8 and 0x00FFFFFF) / 16777215f
    }

    private fun smoothCurve(value: Float): Float = value * value * (3f - 2f * value)

    private fun smoothStep(edge0: Float, edge1: Float, value: Float): Float {
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun lerp(start: Float, end: Float, amount: Float): Float =
        start + (end - start) * amount
}
