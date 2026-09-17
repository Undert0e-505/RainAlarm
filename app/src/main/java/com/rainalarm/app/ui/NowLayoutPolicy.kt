package com.rainalarm.app.ui

import com.rainalarm.app.data.NowWeatherMetric
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.sqrt
import kotlin.math.ceil

internal data class NowLayoutMetrics(
    val horizontalPaddingDp: Int,
    val verticalPaddingDp: Int,
    val gapDp: Int,
    val headerHeightDp: Int,
    val compassHeightDp: Int,
    val chartHeightDp: Int,
    val simplifyText: Boolean,
) {
    val totalHeightDp: Int
        get() = verticalPaddingDp * 2 + headerHeightDp + compassHeightDp + chartHeightDp + gapDp * 2
}

internal object NowLayoutPolicy {
    const val usesVerticalScroll = false
    const val minimumCompassDp = 128
    const val minimumChartDp = 132
    const val cardHeaderHeightDp = 52
    const val chartTitleHeightDp = 25
    const val chartAxisHeightDp = 30

    fun measure(widthDp: Int, heightDp: Int, fontScale: Float = 1f): NowLayoutMetrics {
        require(widthDp > 0 && heightDp > 0 && fontScale > 0f)
        val compact = NowHeaderLayoutPolicy.compact(widthDp, heightDp, fontScale)
        val constrained = heightDp < 400
        val horizontalPadding = NowHeaderLayoutPolicy.horizontalPaddingDp(widthDp, heightDp, fontScale)
        val verticalPadding = NowHeaderLayoutPolicy.verticalPaddingDp
        val gap = 4
        val header = NowHeaderLayoutPolicy.heightDp
        val flexible = (heightDp - verticalPadding * 2 - header - gap * 2).coerceAtLeast(0)
        val minChart = if (constrained) 64 else if (heightDp < 720) 155 else minimumChartDp
        val idealCompass = (flexible * if (widthDp > heightDp) 0.50f else 0.69f).toInt()
        val minCompass = if (constrained) 72 else minimumCompassDp
        val compass = idealCompass.coerceIn(
            minCompass.coerceAtMost(flexible),
            (flexible - minChart).coerceAtLeast(minCompass.coerceAtMost(flexible)),
        )
        val chart = (flexible - compass).coerceAtLeast(0)
        return NowLayoutMetrics(horizontalPadding, verticalPadding, gap, header, compass, chart,
            NowHeaderLayoutPolicy.simplifyText(widthDp, heightDp, fontScale))
    }
}

/** Balance the switcher's width on the left so the title remains geometrically centred. */
internal object NowHeaderLayoutPolicy {
    const val switchDp = 48
    const val gapDp = 8
    const val sideReserveDp = switchDp + gapDp
    const val heightDp = 48
    const val verticalPaddingDp = 4
    fun compact(widthDp: Int, heightDp: Int, fontScale: Float): Boolean =
        heightDp < 620 || widthDp < 360 || fontScale > 1.2f
    fun horizontalPaddingDp(widthDp: Int, heightDp: Int, fontScale: Float): Int =
        if (compact(widthDp, heightDp, fontScale)) 12 else 16
    fun simplifyText(widthDp: Int, heightDp: Int, fontScale: Float): Boolean =
        compact(widthDp, heightDp, fontScale) || fontScale > 1.1f
    fun titleFontSizeSp(simplify: Boolean): Int = if (simplify) 22 else 26
    fun titleLineHeightSp(simplify: Boolean): Int = if (simplify) 26 else 30
    fun titleMaxWidthDp(headerWidthDp: Float): Float =
        (headerWidthDp - 2f * sideReserveDp).coerceAtLeast(0f)
}

internal object NowWeatherReadoutPolicy {
    const val directionLineHeightDp = 16
    fun fontSizeSp(contentWidthDp: Int, fontScale: Float): Int =
        if (contentWidthDp < 260 && fontScale > 1.3f) 9 else 10

    fun rowMinimumHeightDp(fontSizeSp: Int, fontScale: Float = 1f): Int {
        val base = if (fontSizeSp <= 10) 13 else 24
        return if (fontScale <= 1.05f) base
            else maxOf(base, ceil(fontSizeSp * fontScale * 1.25f).toInt() + 1)
    }

    fun directionHeightDp(fontScale: Float, stacked: Boolean): Int =
        if (fontScale <= 1.05f) directionLineHeightDp
        else maxOf(directionLineHeightDp,
            ceil((if (stacked) 10f else 12f) * fontScale * 1.25f).toInt() + 2)

    fun stack(widthDp: Int, fontScale: Float): Boolean = widthDp < 280 || fontScale > 1.4f

    fun footerHeightDp(metricCount: Int, widthDp: Int, fontScale: Float): Int {
        val row = rowMinimumHeightDp(fontSizeSp(widthDp, fontScale), fontScale)
        return if (stack(widthDp, fontScale)) (metricCount + 2) * row + 6
        else maxOf(metricCount, 2) * row + 4
    }

    /** Keep the circular rim above the bottom-corner readouts, without a separate footer row. */
    fun dialDiameterDp(widthDp: Int, bodyHeightDp: Int, footerHeightDp: Int): Int =
        minOf(widthDp, ((bodyHeightDp - footerHeightDp).coerceAtLeast(0) / 0.93f).toInt(), 340)

    fun label(metric: NowWeatherMetric): String = when (metric) {
        NowWeatherMetric.TEMPERATURE -> "Temp"
        NowWeatherMetric.PRESSURE -> "Pressure"
        NowWeatherMetric.HUMIDITY -> "Humidity"
        NowWeatherMetric.UV_INDEX -> "UV"
        NowWeatherMetric.WIND -> "Wind"
    }
}

internal object NowChartLayout {
    fun horizonTitle(endMinute: Int): String = when (val end = endMinute.coerceIn(0, 60)) {
        0 -> "Current radar"
        60 -> "Next hour"
        else -> "Next $end minutes"
    }

    data class ClockTick(val offsetMinutes: Float, val label: String, val showLabel: Boolean)

    /** Mark every local ten-minute boundary; show only labels that fit without collision. */
    fun clockTicks(
        startEpochSeconds: Long,
        endMinute: Int,
        zone: ZoneId,
        plotWidthDp: Int,
        fontScale: Float,
    ): List<ClockTick> {
        val end = endMinute.coerceIn(0, 60)
        val origin = ClockTick(0f, "Now", true)
        if (end == 0) return listOf(origin)
        val endEpoch = startEpochSeconds + end * 60L
        val firstMinute = Math.floorDiv(startEpochSeconds, 60L) * 60L + 60L
        val formatter = DateTimeFormatter.ofPattern("HH:mm")
        val candidateEpochs = generateSequence(firstMinute) { it + 60L }
            .takeWhile { it <= endEpoch }
            .filter { epoch -> Instant.ofEpochSecond(epoch).atZone(zone).minute % 10 == 0 }
            .toList()
        val labels = candidateEpochs.map { formatter.format(Instant.ofEpochSecond(it).atZone(zone)) }
        val counts = labels.groupingBy { it }.eachCount()
        val candidates = candidateEpochs.mapIndexed { index, epoch ->
            val local = Instant.ofEpochSecond(epoch).atZone(zone)
            ClockTick(
                (epoch - startEpochSeconds) / 60f,
                if (counts.getValue(labels[index]) > 1) "${labels[index]} ${local.format(DateTimeFormatter.ofPattern("z", java.util.Locale.ENGLISH))}"
                else labels[index],
                false,
            )
        }
        val plotWidth = plotWidthDp.coerceAtLeast(0).toFloat()
        val result = mutableListOf(origin)
        // "Now" is centred on x=0 and may extend into the severity-label gutter.
        var previousLabelRight = labelWidthDp(origin.label, fontScale) / 2f
        for (tick in candidates) {
            val x = plotX(tick.offsetMinutes, plotWidth, end)
            val labelWidth = labelWidthDp(tick.label, fontScale).coerceAtMost(plotWidth)
            val left = labelLeft(x, plotWidth, labelWidth)
            if (left >= previousLabelRight + 6f) {
                result.add(tick.copy(showLabel = true))
                previousLabelRight = left + labelWidth
            }
            else result.add(tick)
        }
        return result
    }

    /** Match the rendered clock-label boxes when deciding which labels can coexist. */
    fun labelWidthDp(label: String, fontScale: Float): Float =
        (when {
            label == "Now" -> 26f
            label.length > 5 -> 72f
            else -> 46f
        }) * fontScale.coerceAtLeast(0f)

    fun plotX(minute: Int, width: Float, endMinute: Int = 60): Float {
        return plotX(minute.toFloat(), width, endMinute)
    }

    /** The plot domain occupies the full coloured-band width, including both ends. */
    fun plotX(minute: Float, width: Float, endMinute: Int = 60): Float {
        val end = endMinute.coerceIn(0, 60)
        if (!width.isFinite() || width <= 0f || end == 0) return 0f
        return width * minute.coerceIn(0f, end.toFloat()) / end
    }

    /** Inverse of [plotX] for taps on the plot canvas, excluding its severity-label column. */
    fun minuteAtX(x: Float, width: Float, endMinute: Int): Float? {
        if (!x.isFinite() || !width.isFinite() || width <= 0f) return null
        val end = endMinute.coerceIn(0, 60)
        if (end == 0) return 0f
        return (x / width).coerceIn(0f, 1f) * end
    }

    fun epochAtX(startEpochSeconds: Long, x: Float, width: Float, endMinute: Int): Double? =
        minuteAtX(x, width, endMinute)?.let { startEpochSeconds.toDouble() + it.toDouble() * 60.0 }

    /** Keep edge tick labels entirely in the plot, without moving their tick marks. */
    fun labelLeft(tickX: Float, plotWidth: Float, labelWidth: Float): Float =
        (tickX - labelWidth / 2f).coerceIn(0f, (plotWidth - labelWidth).coerceAtLeast(0f))

    fun severityBand(intensity: Float): Int = when {
        intensity >= 2f / 3f -> 0 // severe, top
        intensity >= 1f / 3f -> 1 // medium, middle
        else -> 2 // light, bottom
    }
}

/** Shared entry motion: marker travel, center label scale and plot reveal use one progress. */
internal object NowMotionPolicy {
    const val durationMillis = 1000
    const val centerDiscRadiusFraction = 0.16875f // 25% smaller than the r38 settled radius
    private const val centerEntryScale = 1.16f
    fun centerScale(progress: Float): Float = 1f + (centerEntryScale - 1f) * (1f - progress.coerceIn(0f, 1f))
    fun centerDiscScale(progress: Float): Float = centerScale(progress)

    fun centerUsableDiameterDp(diameterDp: Float): Float =
        (2f * centerDiscRadiusFraction * diameterDp - 6f).coerceAtLeast(0f)

    /** Disc and text share a scale, so a fitted settled label also fits at every animation checkpoint. */
    fun centerFontSizeSp(diameterDp: Float, fontScale: Float, label: String,
                         measuredTargetWidthDp: Float): Float {
        val target = 48f
        val usableDiameter = centerUsableDiameterDp(diameterDp)
        val scaledFont = fontScale.coerceAtLeast(1f)
        val widthLimit = if (measuredTargetWidthDp > 0f)
            target * usableDiameter / measuredTargetWidthDp else target
        // A countdown also has a second line. Limit the full animated stack, not just its widest line.
        val heightLimit = if (label.isNotEmpty() && label.all(Char::isDigit))
            usableDiameter / (1.42f * 1.2f * scaledFont)
        else usableDiameter / (1.2f * scaledFont)
        return minOf(target, widthLimit, heightLimit)
    }

    fun centerUnitFontSizeSp(mainFontSizeSp: Float): Float = minOf(20f, mainFontSizeSp * 0.42f)
    fun revealRight(startX: Float, endX: Float, progress: Float): Float =
        startX + (endX - startX).coerceAtLeast(0f) * progress.coerceIn(0f, 1f)
}

internal data class NowRainTextureCrop(val left: Int, val top: Int, val side: Int)

/** The alpha PNG is an exact square crop of the supplied droplet circle (black converted to alpha). */
internal object NowRainTexturePolicy {
    fun shouldShow(rainingNow: Boolean, arrivalMinute: Int?): Boolean =
        rainingNow || arrivalMinute != null

    fun crop(width: Int, height: Int): NowRainTextureCrop {
        require(width > 0 && height > 0)
        val side = minOf(width, height)
        return NowRainTextureCrop((width - side) / 2, (height - side) / 2, side)
    }

    /** Interior source window: its top-centre contains a droplet, unlike the transparent outer rim. */
    fun pointerCrop(width: Int, height: Int): NowRainTextureCrop {
        require(width > 0 && height > 0)
        val scale = minOf(width, height) / 1088f
        val side = (680f * scale).toInt().coerceAtLeast(1)
        val left = (224f * scale).toInt() + (width - minOf(width, height)) / 2
        val top = (144f * scale).toInt() + (height - minOf(width, height)) / 2
        return NowRainTextureCrop(left, top, side)
    }

    fun discDiameterPx(dialDiameterPx: Float, entryProgress: Float): Float =
        2f * dialDiameterPx * NowMotionPolicy.centerDiscRadiusFraction *
            NowMotionPolicy.centerDiscScale(entryProgress)

    /** Enclosing square reaches the 90-degree tip at sqrt(2) radii for every bearing. */
    fun pointerTextureDiameterPx(pointerRadiusPx: Float): Float =
        2f * sqrt(2f) * pointerRadiusPx.coerceAtLeast(0f)
}
