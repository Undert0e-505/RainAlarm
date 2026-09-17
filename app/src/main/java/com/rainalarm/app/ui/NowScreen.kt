package com.rainalarm.app.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.snap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.rainalarm.app.ForecastUiState
import com.rainalarm.app.R
import com.rainalarm.app.NowRefreshStatus
import com.rainalarm.app.LocationUiState
import com.rainalarm.app.data.ForecastSnapshot
import com.rainalarm.app.data.PlaceCollection
import com.rainalarm.app.data.CurrentWeather
import com.rainalarm.app.data.NowWeatherMetric
import com.rainalarm.app.domain.NowVisualGeometry
import com.rainalarm.app.domain.RainMinuteAnalysis
import com.rainalarm.app.domain.RainMinuteAvailability
import com.rainalarm.app.domain.RainMinuteSeries
import com.rainalarm.app.domain.RainMinuteSeriesAnalyzer
import com.rainalarm.app.domain.cardinalDirection
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val NowSurface: Color @Composable get() = LocalRainAlarmPalette.current.surface
private val NowSurfaceHigh: Color @Composable get() = LocalRainAlarmPalette.current.elevated
private val NowText: Color @Composable get() = LocalRainAlarmPalette.current.text
private val NowMuted: Color @Composable get() = LocalRainAlarmPalette.current.muted
private val NowBorder: Color @Composable get() = LocalRainAlarmPalette.current.border
private val NowAccent: Color @Composable get() = LocalRainAlarmPalette.current.accent
private val NowDeepBlue = Color(0xFF028BFE)
private val CenterGlyphShadow = Shadow(Color.Black.copy(alpha = 0.82f), Offset.Zero, blurRadius = 3f)

internal object NowSourceClock {
    fun ageMinutes(series: RainMinuteSeries, currentEpochSeconds: Long): Long =
        ((currentEpochSeconds - series.latestObservationEpochSeconds).coerceAtLeast(0L)) / 60L

    fun label(startEpochSeconds: Long, minute: Int, zone: ZoneId): String =
        DateTimeFormatter.ofPattern("HH:mm").format(
            Instant.ofEpochSecond(startEpochSeconds + minute * 60L).atZone(zone),
        )
}

@Composable
fun NowScreen(
    state: ForecastUiState,
    refresh: () -> Unit,
    selectedLocationName: String? = null,
    places: PlaceCollection,
    locationState: LocationUiState,
    selectPlace: (String) -> Unit,
    useCurrentLocation: () -> Unit,
    weather: CurrentWeather? = null,
    visibleWeatherMetrics: Set<NowWeatherMetric> = NowWeatherMetric.entries.toSet(),
    refreshStatus: NowRefreshStatus = NowRefreshStatus.Idle,
    visitGeneration: Int = 0,
    selectedLocationKey: String? = null,
    onChartTimeSelected: (Double) -> Unit = {},
) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val density = LocalDensity.current
        val metrics = NowLayoutPolicy.measure(maxWidth.value.toInt(), maxHeight.value.toInt(), density.fontScale)
        val availableWidthDp = maxWidth.value.toInt()
        Column(
            Modifier.fillMaxWidth().widthIn(max = 760.dp).height(maxHeight)
                .padding(horizontal = metrics.horizontalPaddingDp.dp, vertical = metrics.verticalPaddingDp.dp),
            verticalArrangement = Arrangement.spacedBy(metrics.gapDp.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().height(metrics.headerHeightDp.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    selectedLocationName ?: if (state is ForecastUiState.Ready) state.forecast.locationName else "Current location",
                    fontSize = if (metrics.simplifyText) 22.sp else 26.sp,
                    lineHeight = if (metrics.simplifyText) 26.sp else 30.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                PlaceSwitcher(places, locationState, selectPlace, useCurrentLocation,
                    icon = Icons.Default.SwapHoriz)
            }
            when (state) {
                ForecastUiState.Loading -> NowPlaceholder("Reading the local radar", "Building the next hour…", metrics, refresh,
                    refreshStatus)
                is ForecastUiState.Error -> NowPlaceholder("Radar unavailable", state.message, metrics, refresh,
                    refreshStatus)
                is ForecastUiState.Ready -> NowForecastContent(state.forecast, metrics, availableWidthDp,
                    refresh, weather, visibleWeatherMetrics, refreshStatus, visitGeneration, selectedLocationKey,
                    onChartTimeSelected)
            }
        }
    }
}

@Composable
private fun NowPlaceholder(title: String, detail: String, metrics: NowLayoutMetrics,
    refresh: (() -> Unit)? = null, refreshStatus: NowRefreshStatus = NowRefreshStatus.Idle) {
    val border = NowBorder
    val muted = NowMuted
    Card(colors = CardDefaults.cardColors(containerColor = NowSurface), shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth().height(metrics.compassHeightDp.dp)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp)) {
            NowCardHeader(title, if (title == "Reading the local radar") "RADAR · updating" else "RADAR · unavailable",
                refresh, refreshStatus)
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center) {
                val diameter = minOf(maxWidth, maxHeight, 300.dp)
                Canvas(Modifier.size(diameter)) {
                    drawCircle(border.copy(alpha = 0.75f), radius = size.minDimension * 0.43f,
                        style = Stroke(width = 2f))
                    drawCircle(border.copy(alpha = 0.18f), radius = size.minDimension * 0.30f,
                        style = Stroke(width = size.minDimension * 0.05f))
                }
                if (title == "Reading the local radar") CircularProgressIndicator(color = NowAccent)
                else Text("—", color = NowAccent, fontSize = 25.sp, fontWeight = FontWeight.Bold)
            }
            Text(if (title == "Reading the local radar") "Waiting for radar frames" else "Forecast unavailable",
                color = NowMuted, fontSize = 12.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally).height(22.dp))
        }
    }
    Card(colors = CardDefaults.cardColors(containerColor = NowSurfaceHigh), shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().height(metrics.chartHeightDp.dp)) {
        Column(Modifier.fillMaxSize().padding(start = 10.dp, top = 7.dp, end = 10.dp, bottom = 4.dp)) {
            Text("Next hour", color = NowText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().height(NowLayoutPolicy.chartTitleHeightDp.dp))
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.width(56.dp).fillMaxHeight()) {
                    listOf("Severe", "Medium", "Light").forEach { band ->
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                            Text(band, color = NowMuted, fontSize = 11.sp)
                        }
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize()) {
                        val band = size.height / 3f
                        drawRect(border.copy(alpha = 0.10f), size = androidx.compose.ui.geometry.Size(size.width, band))
                        drawRect(border.copy(alpha = 0.07f), topLeft = Offset(0f, band),
                            size = androidx.compose.ui.geometry.Size(size.width, band))
                        drawRect(border.copy(alpha = 0.04f), topLeft = Offset(0f, band * 2),
                            size = androidx.compose.ui.geometry.Size(size.width, band))
                        drawLine(muted.copy(alpha = 0.6f), Offset(0f, size.height - 1f),
                            Offset(size.width, size.height - 1f), 1f)
                    }
                    Text(detail, color = NowMuted, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 18.dp))
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth().height(NowLayoutPolicy.chartAxisHeightDp.dp).padding(start = 56.dp)) {
                val inset = 24.dp.coerceAtMost(maxWidth / 5f)
                listOf(0, 30, 60).forEachIndexed { index, minute ->
                    val x = NowChartLayout.plotX(minute, maxWidth.value, inset.value).dp - 23.dp
                    Text(listOf("Now", "+30", "+60")[index], color = NowMuted,
                        fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 1,
                        modifier = Modifier.width(46.dp).offset(x = x, y = 7.dp))
                }
            }
        }
    }
}

@Composable
private fun NowCardHeader(status: String, source: String, refresh: (() -> Unit)?, refreshStatus: NowRefreshStatus) {
    val sourceWithRefresh = when (refreshStatus) {
        NowRefreshStatus.Idle -> source
        NowRefreshStatus.Refreshing -> "Refreshing · $source"
        NowRefreshStatus.Updated -> "Updated · $source"
        is NowRefreshStatus.Failed -> "Refresh failed · $source"
    }
    Row(Modifier.fillMaxWidth().height(NowLayoutPolicy.cardHeaderHeightDp.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(status, fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sourceWithRefresh, color = NowMuted, fontSize = 12.sp, lineHeight = 16.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { contentDescription = if (refreshStatus is NowRefreshStatus.Failed)
                    "Refresh failed: ${refreshStatus.message}. $source" else sourceWithRefresh })
        }
        refresh?.let { RefreshNowButton(it, refreshStatus) }
    }
}

@Composable
private fun NowForecastContent(forecast: ForecastSnapshot, metrics: NowLayoutMetrics, availableWidthDp: Int,
    refresh: () -> Unit, weather: CurrentWeather?, visibleWeatherMetrics: Set<NowWeatherMetric>,
    refreshStatus: NowRefreshStatus, visitGeneration: Int, selectedLocationKey: String?,
    onChartTimeSelected: (Double) -> Unit) {
    val series = forecast.nowcastSeries
    val analysis = series?.let(RainMinuteSeriesAnalyzer::analyze)
    val age = series?.let { NowSourceClock.ageMinutes(it, Instant.now().epochSecond) } ?: 0L
    val freshness = if (age == 0L) "now" else "${age}m ago"
    if (series == null || series.availability == RainMinuteAvailability.UNAVAILABLE || analysis == null) {
        NowPlaceholder("Next hour unavailable", series?.unavailableReason ?: "No local radar series", metrics, refresh,
            refreshStatus)
        return
    }

    val title = when {
        analysis.rainingNow -> "Raining now"
        analysis.arrivalMinute != null -> "Rain will start in"
        series.availability == RainMinuteAvailability.PARTIAL -> "No rain detected yet"
        else -> "No rain expected in the next hour"
    }
    val conciseTitle = if (metrics.simplifyText && series.availability == RainMinuteAvailability.AVAILABLE &&
        analysis.arrivalMinute == null && !analysis.rainingNow) {
        "Dry next hour"
    } else title
    val coverage = if (series.availability == RainMinuteAvailability.PARTIAL) {
        " · through +${series.points.last().minute} min"
    } else ""
    val sourceKind = when {
        series.intensityEncoding == com.rainalarm.app.domain.RadarIntensityEncoding.REGIONAL_AREA_CHART -> "AREA FORECAST"
        forecast.sourceLabel.contains("RainViewer", true) -> "EST"
        else -> "RADAR"
    }
    val source = "$sourceKind · $freshness$coverage"
    val context = LocalContext.current
    val animationsEnabled = remember(context) {
        runCatching {
            android.provider.Settings.Global.getFloat(
                context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
            ) > 0f
        }.getOrDefault(true)
    }
    val entryProgress = remember(visitGeneration, selectedLocationKey) { Animatable(0f) }
    LaunchedEffect(visitGeneration, selectedLocationKey, animationsEnabled) {
        if (animationsEnabled) entryProgress.animateTo(
            1f, tween(NowMotionPolicy.durationMillis, easing = FastOutSlowInEasing),
        ) else entryProgress.snapTo(1f)
    }
    val visibleProgress = if (animationsEnabled) entryProgress.value else 1f
    RainCompass(series, analysis, conciseTitle, source, refresh, refreshStatus, visibleProgress, animationsEnabled,
        weather, visibleWeatherMetrics,
        Modifier.fillMaxWidth().height(metrics.compassHeightDp.dp))
    RainTimeline(series, Modifier.fillMaxWidth().height(metrics.chartHeightDp.dp), availableWidthDp,
        visibleProgress, onChartTimeSelected)
}

@Composable
private fun RainCompass(
    series: RainMinuteSeries,
    analysis: RainMinuteAnalysis,
    status: String,
    source: String,
    refresh: () -> Unit,
    refreshStatus: NowRefreshStatus,
    entryProgress: Float,
    animationsEnabled: Boolean,
    weather: CurrentWeather?,
    visibleWeatherMetrics: Set<NowWeatherMetric>,
    modifier: Modifier = Modifier,
) {
    val NowSurface = LocalRainAlarmPalette.current.surface
    val NowText = LocalRainAlarmPalette.current.text
    val NowMuted = LocalRainAlarmPalette.current.muted
    val NowBorder = LocalRainAlarmPalette.current.border
    val NowAccent = LocalRainAlarmPalette.current.accent
    val sourceBearing = series.sourceBearingDegrees
    val hasRain = NowRainTexturePolicy.shouldShow(analysis.rainingNow, analysis.arrivalMinute)
    val resources = LocalContext.current.resources
    val rainTexture = if (hasRain) remember(resources) {
        BitmapFactory.decodeResource(resources, R.drawable.now_rain_drops,
            BitmapFactory.Options().apply { inSampleSize = 2 })?.asImageBitmap()
    } else null
    val rainTextureCrop = remember(rainTexture) {
        rainTexture?.let { NowRainTexturePolicy.crop(it.width, it.height) }
    }
    val pointerTextureCrop = remember(rainTexture) {
        rainTexture?.let { NowRainTexturePolicy.pointerCrop(it.width, it.height) }
    }
    val pointerFill = if (sourceBearing != null && hasRain)
        Color(series.displayColor(analysis.maximum)).copy(alpha = 0.92f) else null
    val centerFill = pointerFill ?: NowDeepBlue
    val centerTextMeasurer = rememberTextMeasurer()
    val animatedBearing by animateFloatAsState(
        targetValue = (sourceBearing ?: 0.0).toFloat(),
        animationSpec = if (animationsEnabled) tween(450) else snap(),
        label = "rain source bearing",
    )
    val centerLabel = when {
        analysis.rainingNow -> "Now"
        analysis.arrivalMinute != null -> analysis.arrivalMinute.toString()
        else -> weather?.temperatureC?.roundToInt()?.let { "$it°" } ?: "—"
    }
    val directionLabel = if (!hasRain) "No incoming rain detected" else
        sourceBearing?.let { "From ${cardinalDirection(it)}" } ?: "Direction unavailable"
    val description = if (!hasRain) {
        "No incoming rain detected in the available forecast. " +
            (weather?.temperatureC?.let { "Model temperature ${it.roundToInt()} degrees Celsius. " } ?: "") + "North is up."
    } else if (sourceBearing == null) {
        if (analysis.rainingNow) "Raining now. Rain direction unavailable. North is up."
        else "$centerLabel minutes. Rain direction unavailable. North is up."
    } else {
        if (analysis.rainingNow) "Raining now. $directionLabel at ${sourceBearing.toInt()} degrees. North is up."
        else if (hasRain) "$centerLabel minutes. $directionLabel at ${sourceBearing.toInt()} degrees. North is up."
        else "No incoming rain detected. North is up."
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = NowSurface),
        shape = RoundedCornerShape(28.dp),
        modifier = modifier.widthIn(max = 560.dp).semantics { contentDescription = description },
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) { NowCardHeader(status, source, refresh, refreshStatus) }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val bodyWidthDp = maxWidth.value.toInt()
            val density = LocalDensity.current
            val fontScale = density.fontScale
            val readoutHeightDp = NowWeatherReadoutPolicy.footerHeightDp(
                visibleWeatherMetrics.size, bodyWidthDp, fontScale,
            )
            val stackedReadouts = NowWeatherReadoutPolicy.stack(bodyWidthDp, fontScale)
            val footerHeightDp = readoutHeightDp +
                if (stackedReadouts) NowWeatherReadoutPolicy.directionLineHeightDp else 0
            val diameter = NowWeatherReadoutPolicy.dialDiameterDp(
                bodyWidthDp, maxHeight.value.toInt(), footerHeightDp,
            ).dp
            val measuredTargetWidthDp = remember(centerLabel, density, centerTextMeasurer) {
                val widthPx = centerTextMeasurer.measure(
                    AnnotatedString(centerLabel),
                    style = TextStyle(fontSize = 48.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1,
                ).size.width
                with(density) { widthPx.toDp().value }
            }
            val mainCenterFontSize = NowMotionPolicy.centerFontSizeSp(
                diameter.value, fontScale, centerLabel, measuredTargetWidthDp,
            )
            val centerFontSize = mainCenterFontSize.sp
            Box(Modifier.align(Alignment.TopCenter).size(diameter)) {
                Canvas(Modifier.fillMaxSize()) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val outer = size.minDimension * 0.43f
                    val inner = size.minDimension * 0.30f
                    drawCircle(NowBorder, outer, center, style = Stroke(width = 2f))
                    drawCircle(NowBorder.copy(alpha = 0.24f), inner, center, style = Stroke(width = size.minDimension * 0.055f))
                    for (bearing in 0 until 360 step 30) {
                        val outerPoint = NowVisualGeometry.compassPoint(bearing.toDouble(), outer)
                        val tickLength = if (bearing % 90 == 0) 10f else 5f
                        val innerPoint = NowVisualGeometry.compassPoint(bearing.toDouble(), outer - tickLength)
                        drawLine(
                            if (bearing % 90 == 0) NowMuted else NowBorder,
                            center + Offset(innerPoint.first, innerPoint.second),
                            center + Offset(outerPoint.first, outerPoint.second),
                            strokeWidth = if (bearing % 90 == 0) 2f else 1f,
                        )
                    }
                    if (sourceBearing != null && hasRain) {
                        val intensity = analysis.maximum.coerceIn(0.25f, 1f)
                        val radius = size.minDimension * (0.055f + intensity * 0.045f)
                        val finalDistance = inner + (outer - inner) * 0.48f
                        val at = NowVisualGeometry.compassPoint(
                            animatedBearing.toDouble(),
                            NowVisualGeometry.markerDistance(
                                outer + radius * 0.8f, finalDistance, entryProgress,
                            ),
                        )
                        val indicator = center + Offset(at.first, at.second)
                        drawCircle(NowAccent.copy(alpha = 0.17f), radius * 1.8f, indicator)
                        val outline = NowVisualGeometry.rainDropOutline(animatedBearing.toDouble(), radius)
                        val silhouette = Path().apply {
                            moveTo(indicator.x + outline.firstJoin.first, indicator.y + outline.firstJoin.second)
                            lineTo(indicator.x + outline.tip.first, indicator.y + outline.tip.second)
                            lineTo(indicator.x + outline.secondJoin.first, indicator.y + outline.secondJoin.second)
                            arcTo(Rect(indicator.x - radius, indicator.y - radius,
                                indicator.x + radius, indicator.y + radius),
                                outline.arcStartDegrees, outline.arcSweepDegrees, forceMoveTo = false)
                            close()
                        }
                        drawPath(silhouette, requireNotNull(pointerFill))
                        if (rainTexture != null && pointerTextureCrop != null) {
                            val textureSide = NowRainTexturePolicy.pointerTextureDiameterPx(radius)
                            val halfTexture = textureSide / 2f
                            clipPath(silhouette) {
                                rotate(animatedBearing, pivot = indicator) {
                                    val sidePx = textureSide.roundToInt().coerceAtLeast(1)
                                    drawImage(rainTexture,
                                        srcOffset = IntOffset(pointerTextureCrop.left, pointerTextureCrop.top),
                                        srcSize = IntSize(pointerTextureCrop.side, pointerTextureCrop.side),
                                        dstOffset = IntOffset((indicator.x - halfTexture).roundToInt(),
                                            (indicator.y - halfTexture).roundToInt()),
                                        dstSize = IntSize(sidePx, sidePx),
                                        filterQuality = FilterQuality.Medium)
                                }
                            }
                        }
                        drawPath(silhouette, NowAccent.copy(alpha = 0.85f),
                            style = Stroke(width = 2.5f))
                    }
                    val discScale = NowMotionPolicy.centerDiscScale(entryProgress)
                    val discRadius = NowRainTexturePolicy.discDiameterPx(size.minDimension, entryProgress) / 2f
                    drawCircle(centerFill, discRadius, center)
                    if (hasRain && rainTexture != null && rainTextureCrop != null) {
                        val clip = Path().apply {
                            addOval(Rect(center.x - discRadius, center.y - discRadius,
                                center.x + discRadius, center.y + discRadius))
                        }
                        val diameterPx = (discRadius * 2f).roundToInt().coerceAtLeast(1)
                        clipPath(clip) {
                            drawImage(rainTexture,
                                srcOffset = IntOffset(rainTextureCrop.left, rainTextureCrop.top),
                                srcSize = IntSize(rainTextureCrop.side, rainTextureCrop.side),
                                dstOffset = IntOffset((center.x - discRadius).roundToInt(),
                                    (center.y - discRadius).roundToInt()),
                                dstSize = IntSize(diameterPx, diameterPx),
                                filterQuality = FilterQuality.Medium)
                        }
                    }
                    drawCircle(NowAccent.copy(alpha = 0.32f),
                        size.minDimension * (NowMotionPolicy.centerDiscRadiusFraction + 0.009f) * discScale, center,
                        style = Stroke(width = 2f))
                }
                Text("N", color = NowText, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.TopCenter))
                Text("E", color = NowMuted, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 3.dp))
                Text("S", color = NowMuted, fontSize = 12.sp, modifier = Modifier.align(Alignment.BottomCenter))
                Text("W", color = NowMuted, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterStart).padding(start = 3.dp))
                Column(Modifier.align(Alignment.Center).graphicsLayer {
                    val scale = NowMotionPolicy.centerScale(entryProgress)
                    scaleX = scale
                    scaleY = scale
                }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(centerLabel, color = Color.White, fontSize = centerFontSize, fontWeight = FontWeight.Bold,
                        style = TextStyle(shadow = CenterGlyphShadow),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!analysis.rainingNow && analysis.arrivalMinute != null) Text("min", color = Color.White,
                        style = TextStyle(shadow = CenterGlyphShadow),
                        fontSize = NowMotionPolicy.centerUnitFontSizeSp(mainCenterFontSize).sp)
                }
            }
            NowWeatherReadouts(weather, visibleWeatherMetrics,
                Modifier.align(Alignment.BottomCenter).fillMaxWidth())
            val footerDirection = when {
                !hasRain -> "No rain"
                sourceBearing == null -> "From —"
                else -> directionLabel
            }
            Text(footerDirection, color = if (sourceBearing == null) NowMuted else NowAccent,
                fontSize = if (stackedReadouts) 10.sp else 12.sp,
                maxLines = 1, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = if (stackedReadouts) readoutHeightDp.dp else 0.dp)
                    .height(NowWeatherReadoutPolicy.directionLineHeightDp.dp))
        }
        }
    }
}

@Composable
private fun NowWeatherReadouts(weather: CurrentWeather?, metrics: Set<NowWeatherMetric>, modifier: Modifier) {
    val solar = weather?.solarToday(Instant.now().epochSecond)
    val selected = NowWeatherMetric.entries.filter(metrics::contains)
    val values = selected.map { metric ->
        metric to (when (metric) {
            NowWeatherMetric.TEMPERATURE -> weather?.temperatureC?.roundToInt()?.let { "$it°" }
            NowWeatherMetric.PRESSURE -> weather?.pressureHpa?.roundToInt()?.let { "$it hPa" }
            NowWeatherMetric.HUMIDITY -> weather?.humidityPercent?.let { "$it%" }
            NowWeatherMetric.UV_INDEX -> weather?.uvIndex?.roundToInt()?.toString()
            NowWeatherMetric.WIND -> if (weather?.windSpeedKmh != null && weather.windFromDegrees != null)
                "${weather.windSpeedKmh.roundToInt()} km/h ${cardinalDirection(weather.windFromDegrees)}" else null
        } ?: "—")
    }
    val solarRows = listOf("Sunrise" to (solar?.first ?: "—"), "Sunset" to (solar?.second ?: "—"))
    val metricRows = values.map { (metric, value) -> NowWeatherReadoutPolicy.label(metric) to value }
    BoxWithConstraints(modifier.padding(vertical = 2.dp)) {
        val fontScale = LocalDensity.current.fontScale
        val font = NowWeatherReadoutPolicy.fontSizeSp(maxWidth.value.toInt(), fontScale).sp
        if (NowWeatherReadoutPolicy.stack(maxWidth.value.toInt(), fontScale)) {
            Column(Modifier.fillMaxWidth()) {
                DividerReadout(solarRows, font, Modifier.align(Alignment.Start), "Open-Meteo model")
                if (metricRows.isNotEmpty()) DividerReadout(metricRows, font,
                    Modifier.align(Alignment.End), "Open-Meteo model")
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom) {
                DividerReadout(solarRows, font, Modifier, "Open-Meteo model")
                if (metricRows.isNotEmpty()) DividerReadout(metricRows, font, Modifier, "Open-Meteo model")
            }
        }
    }
}

@Composable
private fun DividerReadout(rows: List<Pair<String, String>>, font: androidx.compose.ui.unit.TextUnit,
    modifier: Modifier, source: String) {
    val rowLineHeight = (font.value * 1.2f).sp
    val rowHeight = NowWeatherReadoutPolicy.rowMinimumHeightDp(font.value.toInt()).dp
    Row(modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.Bottom) {
        Column(horizontalAlignment = Alignment.End) {
            rows.forEach { (label, _) -> Text(label, color = NowMuted, fontSize = font, lineHeight = rowLineHeight,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.heightIn(min = rowHeight)) }
        }
        Spacer(Modifier.width(2.dp))
        Box(Modifier.width(1.dp).fillMaxHeight().background(NowBorder))
        Spacer(Modifier.width(2.dp))
        Column {
            rows.forEach { (label, value) -> Text(value, color = NowText, fontSize = font, lineHeight = rowLineHeight,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.heightIn(min = rowHeight)
                    .semantics { contentDescription = when {
                        value == "—" -> "$label: unavailable; $source"
                        label == "Wind" -> "Wind from ${value.substringAfterLast(' ')} at ${value.substringBeforeLast(' ')}; $source"
                        else -> "$label: $value; $source"
                    } }) }
        }
    }
}

@Composable
private fun RefreshNowButton(refresh: () -> Unit, refreshStatus: NowRefreshStatus) {
    IconButton(onClick = refresh, modifier = Modifier.size(48.dp)) {
        if (refreshStatus is NowRefreshStatus.Refreshing) {
            CircularProgressIndicator(Modifier.size(22.dp).semantics {
                contentDescription = "Refreshing radar nowcast; tap to retry"
            }, color = NowAccent, strokeWidth = 2.dp)
        } else Icon(Icons.Default.Refresh, contentDescription = "Refresh radar nowcast", tint = NowAccent)
    }
}

@Composable
private fun RainTimeline(
    series: RainMinuteSeries,
    modifier: Modifier = Modifier,
    availableWidthDp: Int,
    entryProgress: Float,
    onTimeSelected: (Double) -> Unit,
) {
    val NowSurfaceHigh = LocalRainAlarmPalette.current.elevated
    val NowMuted = LocalRainAlarmPalette.current.muted
    val NowBorder = LocalRainAlarmPalette.current.border
    val NowAccent = LocalRainAlarmPalette.current.accent
    val endMinute = series.points.last().minute.coerceIn(0, 60)
    val chartTitle = NowChartLayout.horizonTitle(endMinute)
    val ticks = NowChartLayout.clockTicks(
        series.startEpochSeconds, endMinute, ZoneId.systemDefault(),
        availableWidthDp, LocalDensity.current.fontScale,
    )
    val labelInset = if (ticks.any { it.showLabel && it.label.length > 5 }) 38.dp else 24.dp
    val density = LocalDensity.current
    val description = buildString {
        append("$chartTitle ${if (series.intensityEncoding == com.rainalarm.app.domain.RadarIntensityEncoding.REGIONAL_AREA_CHART) "area forecast intensity" else "radar intensity"}. ")
        val analysis = RainMinuteSeriesAnalyzer.analyze(series)
        if (analysis?.rainingNow == true) append("Raining now. ")
        else if (analysis?.arrivalMinute != null) append("Rain starts in ${analysis.arrivalMinute} minutes. ")
        else if (series.availability == RainMinuteAvailability.PARTIAL) {
            append("No rain detected before +${series.points.last().minute} minutes; later minutes unavailable. ")
        } else append("No rain expected. ")
        append("Light, medium and severe intensity bands are shown. ")
        if (endMinute > 0) append("The axis runs from now through ${NowSourceClock.label(series.startEpochSeconds, endMinute, ZoneId.systemDefault())}.")
        else append("Only the current radar point is available.")
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = NowSurfaceHigh),
        shape = RoundedCornerShape(24.dp),
        modifier = modifier.widthIn(max = 720.dp).semantics { contentDescription = description },
    ) {
        Column(Modifier.fillMaxSize().padding(start = 10.dp, top = 7.dp, end = 10.dp, bottom = 4.dp)) {
        Box(Modifier.fillMaxWidth().height(NowLayoutPolicy.chartTitleHeightDp.dp)) {
            Text(chartTitle, color = NowText, fontSize = 16.sp, lineHeight = 20.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1,
                modifier = Modifier.align(Alignment.Center), textAlign = TextAlign.Center)
        }
        Row(Modifier.fillMaxWidth().weight(1f)) {
            Column(Modifier.width(56.dp).fillMaxHeight()) {
                listOf("Severe", "Medium", "Light").forEach { band ->
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                        Text(band, color = NowMuted, fontSize = 11.sp)
                    }
                }
            }
            Canvas(Modifier.weight(1f).fillMaxSize()
                .pointerInput(series.startEpochSeconds, endMinute, labelInset, onTimeSelected) {
                    detectTapGestures { tap ->
                        val insetPx = with(density) { labelInset.toPx() }.coerceAtMost(size.width / 5f)
                        NowChartLayout.epochAtX(series.startEpochSeconds, tap.x, size.width.toFloat(),
                            insetPx, endMinute)?.let(onTimeSelected)
                    }
                }
                .semantics {
                    contentDescription = "Rain intensity plot. Double tap to inspect the current forecast end on radar."
                    onClick(label = "Inspect forecast end on radar") {
                        onTimeSelected(series.startEpochSeconds + endMinute * 60.0)
                        true
                    }
                }) {
                val chartHeight = size.height
                val inset = labelInset.toPx().coerceAtMost(size.width / 5f)
                fun plotX(minute: Int) = NowChartLayout.plotX(minute, size.width, inset, endMinute)
                drawRect(Color(series.displayColor(0.88f)).copy(alpha = 0.045f), size = androidx.compose.ui.geometry.Size(size.width, chartHeight / 3f))
                drawRect(Color(series.displayColor(0.62f)).copy(alpha = 0.045f), topLeft = Offset(0f, chartHeight / 3f), size = androidx.compose.ui.geometry.Size(size.width, chartHeight / 3f))
                drawRect(Color(series.displayColor(0.34f)).copy(alpha = 0.045f), topLeft = Offset(0f, chartHeight * 2f / 3f), size = androidx.compose.ui.geometry.Size(size.width, chartHeight / 3f))
                listOf(1f / 3f, 2f / 3f).forEach { level ->
                    val y = NowVisualGeometry.chartY(level, chartHeight)
                    drawLine(NowBorder, Offset(0f, y), Offset(size.width, y), 1f)
                }
                if (endMinute > 0) drawLine(NowMuted.copy(alpha = 0.9f),
                    Offset(plotX(0), chartHeight - 1f), Offset(plotX(endMinute), chartHeight - 1f), 1.5f)
                val envelope = Path()
                series.points.forEachIndexed { index, point ->
                    val x = plotX(point.minute)
                    val y = NowVisualGeometry.chartY(series.chartSeverity(point.maximum), chartHeight)
                    if (index == 0) envelope.moveTo(x, y) else envelope.lineTo(x, y)
                }
                series.points.asReversed().forEach { point ->
                    envelope.lineTo(
                        plotX(point.minute),
                        NowVisualGeometry.chartY(series.chartSeverity(point.minimum), chartHeight),
                    )
                }
                envelope.close()
                val average = Path()
                series.points.forEachIndexed { index, point ->
                    val x = plotX(point.minute)
                    val y = NowVisualGeometry.chartY(series.chartSeverity(point.average), chartHeight)
                    if (index == 0) average.moveTo(x, y) else {
                        val previous = series.points[index - 1]
                        val px = plotX(previous.minute)
                        val py = NowVisualGeometry.chartY(series.chartSeverity(previous.average), chartHeight)
                        average.quadraticTo((px + x) / 2f, py, x, y)
                    }
                }
                val revealRight = NowMotionPolicy.revealRight(plotX(0), plotX(endMinute), entryProgress)
                if (entryProgress > 0f) clipRect(left = 0f, top = 0f, right = revealRight, bottom = chartHeight) {
                    drawPath(envelope, NowAccent.copy(alpha = 0.15f))
                    drawPath(average, NowAccent, style = Stroke(width = 3f, cap = StrokeCap.Round))
                }
                if (endMinute > 0) drawLine(NowAccent.copy(alpha = 0.35f),
                    Offset(plotX(0), 0f), Offset(plotX(0), chartHeight), 1f)
                else if (entryProgress > 0f) drawCircle(
                    NowAccent,
                    radius = 3f,
                    center = Offset(
                        plotX(0),
                        NowVisualGeometry.chartY(series.chartSeverity(series.points.first().average), chartHeight),
                    ),
                )
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().height(NowLayoutPolicy.chartAxisHeightDp.dp).padding(start = 56.dp)) {
            val inset = labelInset.coerceAtMost(maxWidth / 5f)
            Canvas(Modifier.fillMaxSize()) {
                ticks.forEach { tick ->
                    val x = NowChartLayout.plotX(tick.offsetMinutes, maxWidth.toPx(), inset.toPx(), endMinute)
                    drawLine(NowMuted, Offset(x, 0f), Offset(x, 5.dp.toPx()), 1.5f)
                }
            }
            ticks.forEachIndexed { index, tick ->
                if (!tick.showLabel) return@forEachIndexed
                val labelWidth = if (tick.label.length > 5) 72.dp else 46.dp
                val x = NowChartLayout.plotX(tick.offsetMinutes, maxWidth.value, inset.value, endMinute).dp - labelWidth / 2
                Text(tick.label, color = if (index == 0) NowAccent else NowMuted,
                    fontSize = 11.sp, textAlign = TextAlign.Center, lineHeight = 13.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(labelWidth).offset(x = x, y = 7.dp))
            }
        }
        }
    }
}
