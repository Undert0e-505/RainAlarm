package com.rainalarm.app.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import android.util.LruCache
import com.rainalarm.app.R
import com.rainalarm.app.domain.MiniCompassPresentation
import com.rainalarm.app.domain.NowVisualGeometry
import com.rainalarm.app.domain.PrecipitationPresentationKind
import com.rainalarm.app.domain.RainMinuteSeries
import com.rainalarm.app.domain.WeatherPresentationPolicy
import kotlin.math.roundToInt

enum class WidgetPaletteKind { DARK, LIGHT, SLATE }

data class WidgetPalette(
    val background: Int,
    val surface: Int,
    val text: Int,
    val muted: Int,
    val accent: Int,
    val border: Int,
) {
    companion object {
        fun forKind(kind: WidgetPaletteKind): WidgetPalette = when (kind) {
            WidgetPaletteKind.DARK -> WidgetPalette(
                0xff0d0d0d.toInt(), 0xff1a1a1a.toInt(), 0xffe8e8ea.toInt(),
                0xffa0a0a8.toInt(), 0xff4fc3f7.toInt(), 0xff303138.toInt(),
            )
            WidgetPaletteKind.LIGHT -> WidgetPalette(
                0xfff5f8fa.toInt(), Color.WHITE, 0xff132630.toInt(), 0xff536875.toInt(),
                0xff006d9d.toInt(), 0xffc7d5dc.toInt(),
            )
            WidgetPaletteKind.SLATE -> WidgetPalette(
                0xff45516e.toInt(), 0xff45516e.toInt(), Color.WHITE, 0xffd7dce7.toInt(),
                0xff4fc3f7.toInt(), 0xff9eaac1.toInt(),
            )
        }
    }
}

data class WidgetPrimaryPresentation(
    val centre: String,
    val unit: String? = null,
    val headline: String,
    val detail: String? = null,
    val lightningBadge: Boolean = false,
    val unavailable: Boolean = false,
)

/**
 * Selects the single payload painted in the mini-compass centre. Compact widgets use the disc as
 * their primary presentation, while wider widgets keep primary text in the adjacent status column.
 */
enum class WidgetCompassContentMode { COMPASS_ONLY, TEXT_BEARING }

object WidgetCompassContentPolicy {
    fun presentation(
        mode: WidgetCompassContentMode,
        @Suppress("UNUSED_PARAMETER") selectedContent: WidgetPrimaryContent,
        rain: MiniCompassPresentation,
        primary: WidgetPrimaryPresentation,
    ): MiniCompassPresentation = when (mode) {
        WidgetCompassContentMode.TEXT_BEARING -> if (rain.hasPrecipitation) {
            rain
        } else {
            neutralFace(rain, "—", null, unavailable = rain.kind == PrecipitationPresentationKind.UNKNOWN)
        }

        WidgetCompassContentMode.COMPASS_ONLY -> if (rain.hasPrecipitation) rain
        else neutralFace(rain, primary.centre, primary.unit, primary.unavailable)
    }

    private fun neutralFace(
        rain: MiniCompassPresentation,
        centre: String,
        unit: String?,
        unavailable: Boolean,
    ): MiniCompassPresentation = rain.copy(
        kind = if (unavailable) PrecipitationPresentationKind.UNKNOWN
        else PrecipitationPresentationKind.CLEAR,
        centerText = centre,
        centerUnit = unit,
        rainingNow = false,
        arrivalMinute = null,
        confirmedStopMinute = null,
        peakSeverity = null,
        peakColorArgb = null,
        qualitativeIntensity = null,
        sourceBearingDegrees = null,
    )
}

object WidgetPresentationPolicy {
    fun primary(
        context: Context,
        config: RainAlarmWidgetConfig,
        snapshot: WidgetWeatherSnapshot?,
    ): WidgetPrimaryPresentation {
        if (snapshot == null) return unavailable(context)
        val series = snapshot.rainSeries()
        val rain = WidgetRainPresentationPolicy.from(snapshot, series)
        val lightningDetected = snapshot.lightning == WidgetLightningState.DETECTED
        return when {
            rain.hasPrecipitation -> rainPresentation(context, rain, lightningDetected)
            rain.kind == PrecipitationPresentationKind.CLEAR ->
                snapshot.temperatureC?.takeIf(Double::isFinite)?.let {
                    val temperature = "${it.roundToInt()}°"
                    WidgetPrimaryPresentation(
                        temperature,
                        null,
                        temperature,
                        lightningBadge = lightningDetected,
                    )
                } ?: WidgetPrimaryPresentation(
                    "—",
                    null,
                    context.getString(
                        if (rain.completeHour) R.string.widget_dry_next_hour
                        else R.string.now_no_rain,
                    ),
                    lightningBadge = lightningDetected,
                )
            else -> unavailable(context, lightningDetected)
        }
    }

    private fun rainPresentation(
        context: Context,
        rain: MiniCompassPresentation,
        lightning: Boolean,
    ): WidgetPrimaryPresentation = WidgetPrimaryPresentation(
        centre = rain.centerText,
        unit = rain.centerUnit,
        headline = when {
            rain.kind == PrecipitationPresentationKind.LIKELY_SNOW && rain.rainingNow ->
                context.getString(R.string.widget_snow_now)
            rain.kind == PrecipitationPresentationKind.LIKELY_SNOW ->
                context.getString(R.string.widget_snow_in, rain.arrivalMinute)
            rain.rainingNow -> context.getString(R.string.widget_rain_now)
            rain.arrivalMinute != null -> context.getString(R.string.widget_rain_in, rain.arrivalMinute)
            else -> context.getString(
                if (rain.completeHour) R.string.widget_dry_next_hour else R.string.now_no_rain,
            )
        },
        detail = rain.qualitativeIntensity?.let {
            context.getString(
                when (it) {
                    com.rainalarm.app.domain.QualitativeIntensity.LIGHT -> R.string.now_light
                    com.rainalarm.app.domain.QualitativeIntensity.MEDIUM -> R.string.now_medium
                    com.rainalarm.app.domain.QualitativeIntensity.SEVERE -> R.string.now_severe
                },
            )
        },
        lightningBadge = lightning,
    )

    private fun unavailable(context: Context, lightning: Boolean = false) = WidgetPrimaryPresentation(
        "—",
        null,
        context.getString(R.string.widget_update_unavailable),
        lightningBadge = lightning,
        unavailable = true,
    )

}

enum class WidgetRainAnswer { KNOWN, INCOMPLETE, UNAVAILABLE }

/** Keeps a successful partial rain horizon distinct from a failed whole-widget update. */
object WidgetRainAnswerPolicy {
    fun answer(snapshot: WidgetWeatherSnapshot, series: RainMinuteSeries?): WidgetRainAnswer = when {
        series == null || snapshot.updateUnavailable -> WidgetRainAnswer.UNAVAILABLE
        series.availability == com.rainalarm.app.domain.RainMinuteAvailability.PARTIAL ->
            WidgetRainAnswer.INCOMPLETE
        else -> WidgetRainAnswer.KNOWN
    }
}

/**
 * Widget-only rendering of a successful partial horizon. Available wet samples keep the shared
 * rain presentation; a dry partial window is a neutral current state, never a complete-hour clear.
 * The stored PARTIAL availability remains unchanged for notification arming.
 */
object WidgetRainPresentationPolicy {
    fun from(
        snapshot: WidgetWeatherSnapshot,
        series: RainMinuteSeries? = snapshot.rainSeries(),
    ): MiniCompassPresentation {
        val shared = WeatherPresentationPolicy.from(series, snapshot.temperatureC)
        if (shared.kind != PrecipitationPresentationKind.UNKNOWN ||
            WidgetRainAnswerPolicy.answer(snapshot, series) != WidgetRainAnswer.INCOMPLETE
        ) return shared
        return MiniCompassPresentation(
            kind = PrecipitationPresentationKind.CLEAR,
            centerText = "—",
            knownHorizonMinutes = series?.points?.lastOrNull()?.minute ?: 0,
            completeHour = false,
        )
    }
}

object WidgetBitmapRenderer {
    private val graphCache = object : LruCache<String, Bitmap>(4 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    fun compass(
        context: Context,
        presentation: MiniCompassPresentation,
        palette: WidgetPalette,
        sizePx: Int = 180,
    ): Bitmap {
        val size = sizePx.coerceIn(96, 320)
        val fontScale = context.resources.configuration.fontScale.coerceIn(1f, 1.2f)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val centre = size / 2f
        val outer = size * 0.45f
        val inner = size * 0.31f
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.border
            style = Paint.Style.STROKE
            strokeWidth = (size * 0.012f).coerceAtLeast(1.5f)
        }
        canvas.drawCircle(centre, centre, outer, line)
        line.alpha = 90
        line.strokeWidth = size * 0.05f
        canvas.drawCircle(centre, centre, inner, line)
        line.strokeWidth = (size * 0.01f).coerceAtLeast(1f)
        for (bearing in 0 until 360 step 30) {
            val a = Math.toRadians(bearing - 90.0)
            val tick = if (bearing % 90 == 0) size * 0.045f else size * 0.025f
            line.color = if (bearing % 90 == 0) palette.muted else palette.border
            line.alpha = 255
            canvas.drawLine(
                centre + kotlin.math.cos(a).toFloat() * (outer - tick),
                centre + kotlin.math.sin(a).toFloat() * (outer - tick),
                centre + kotlin.math.cos(a).toFloat() * outer,
                centre + kotlin.math.sin(a).toFloat() * outer,
                line,
            )
        }

        val discRadius = size * 0.215f
        val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = when (presentation.kind) {
                PrecipitationPresentationKind.UNKNOWN -> palette.border
                else -> presentation.peakColorArgb ?: 0xff028bfe.toInt()
            }
            style = Paint.Style.FILL
        }
        canvas.drawCircle(centre, centre, discRadius, discPaint)
        if (presentation.hasPrecipitation) {
            val resource = if (presentation.kind == PrecipitationPresentationKind.LIKELY_SNOW) {
                R.drawable.now_snowflakes
            } else R.drawable.now_rain_drops
            BitmapFactory.decodeResource(context.resources, resource)?.let { texture ->
                try {
                    val shader = BitmapShader(texture, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
                    val scale = discRadius * 2f / texture.width.coerceAtLeast(1)
                    val matrix = Matrix().apply {
                        setScale(scale, scale)
                        postTranslate(centre - discRadius, centre - discRadius)
                    }
                    shader.setLocalMatrix(matrix)
                    val texturePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        this.shader = shader
                        alpha = 175
                    }
                    canvas.drawCircle(centre, centre, discRadius, texturePaint)
                } finally {
                    texture.recycle()
                }
            }
        }
        presentation.sourceBearingDegrees?.takeIf { presentation.hasPrecipitation }?.let { bearing ->
            val radius = size * 0.055f
            val position = NowVisualGeometry.compassPoint(bearing, (inner + outer) / 2f)
            val x = centre + position.first
            val y = centre + position.second
            val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.accent; alpha = 60 }
            canvas.drawCircle(x, y, radius * 1.65f, glow)
            val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = presentation.peakColorArgb ?: palette.accent
                style = Paint.Style.FILL
            }
            val outline = NowVisualGeometry.rainDropOutline(bearing, radius)
            val path = Path().apply {
                moveTo(x + outline.firstJoin.first, y + outline.firstJoin.second)
                lineTo(x + outline.tip.first, y + outline.tip.second)
                lineTo(x + outline.secondJoin.first, y + outline.secondJoin.second)
                close()
            }
            canvas.drawPath(path, marker)
            canvas.drawCircle(x, y, radius, marker)
        }

        val displayText = if (presentation.rainingNow) context.getString(R.string.now_now)
            else presentation.centerText
        val displayUnit = if (presentation.arrivalMinute != null && !presentation.rainingNow) {
            context.getString(R.string.now_minutes_short)
        } else presentation.centerUnit
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            textSize = (if (displayText.length <= 3) size * 0.18f else size * 0.13f) * fontScale
        }
        val baseline = centre - (text.ascent() + text.descent()) / 2f -
            if (displayUnit != null) size * 0.035f else 0f
        canvas.drawText(displayText, centre, baseline, text)
        displayUnit?.let { unit ->
            text.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            text.textSize = size * 0.075f * fontScale
            canvas.drawText(unit, centre, baseline + size * 0.12f, text)
        }
        return bitmap
    }

    fun graph(
        context: Context,
        series: RainMinuteSeries?,
        palette: WidgetPalette,
        widthPx: Int = 560,
        heightPx: Int = 228,
    ): Bitmap {
        val width = widthPx.coerceIn(180, 1_200)
        val height = heightPx.coerceIn(64, 320)
        val cacheKey = listOf(
            width,
            height,
            context.resources.displayMetrics.densityDpi,
            palette.hashCode(),
            series?.hashCode() ?: 0,
        ).joinToString(":")
        graphCache.get(cacheKey)?.let { return it }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val strokeWidth = (context.resources.displayMetrics.density * 1.25f).coerceAtLeast(2f)
        val inset = strokeWidth / 2f + 1f
        val plotHeight = height.toFloat()
        repeat(3) { band ->
            val severity = when (band) { 0 -> 0.88f; 1 -> 0.62f; else -> 0.34f }
            paint.color = series?.displayColor(severity) ?: palette.border
            paint.alpha = 12
            canvas.drawRect(
                inset,
                band * plotHeight / 3f,
                width - inset,
                (band + 1) * plotHeight / 3f,
                paint,
            )
        }
        val usable = series?.points.orEmpty()
        if (usable.size > 1) {
            fun x(index: Int): Float = WidgetGraphLayoutPolicy.sampleX(
                index, usable.size, width, inset,
            )
            fun y(value: Float): Float = WidgetGraphLayoutPolicy.sampleY(value, height, inset)
            val envelope = Path()
            usable.forEachIndexed { index, point ->
                val severity = series!!.chartSeverity(point.maximum)
                if (index == 0) envelope.moveTo(x(index), y(severity))
                else envelope.lineTo(x(index), y(severity))
            }
            usable.indices.reversed().forEach { index ->
                envelope.lineTo(x(index), y(series!!.chartSeverity(usable[index].minimum)))
            }
            envelope.close()
            paint.style = Paint.Style.FILL
            paint.color = palette.accent
            paint.alpha = 62
            canvas.drawPath(envelope, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = strokeWidth
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            paint.alpha = 255
            usable.zipWithNext().forEachIndexed { index, (previous, point) ->
                val segment = Path().apply {
                    val px = x(index)
                    val py = y(series!!.chartSeverity(previous.average))
                    val x = x(index + 1)
                    val y = y(series.chartSeverity(point.average))
                    moveTo(px, py)
                    quadTo((px + x) / 2f, py, x, y)
                }
                paint.color = series!!.precipitationColor(point.average, point.likelySnow)
                canvas.drawPath(segment, paint)
            }
        } else {
            paint.style = Paint.Style.FILL
            paint.color = palette.muted
            paint.alpha = 220
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            paint.textSize = plotHeight * 0.34f
            canvas.drawText("—", width / 2f, plotHeight * 0.64f, paint)
        }
        graphCache.put(cacheKey, bitmap)
        return bitmap
    }
}
