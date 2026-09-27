package com.rainalarm.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
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
import androidx.core.content.ContextCompat
import com.rainalarm.app.ForecastUiState
import com.rainalarm.app.R
import com.rainalarm.app.NowRefreshStatus
import com.rainalarm.app.LocationUiState
import com.rainalarm.app.data.ForecastSnapshot
import com.rainalarm.app.data.FeatureTourScenario
import com.rainalarm.app.data.PlaceCollection
import com.rainalarm.app.data.CurrentWeather
import com.rainalarm.app.data.NowWeatherMetric
import com.rainalarm.app.domain.EntryTransitionPhase
import com.rainalarm.app.domain.EntryTransitionPolicy
import com.rainalarm.app.domain.EntryTransitionState
import com.rainalarm.app.data.NowCardAppearance
import com.rainalarm.app.data.CurrentLocationSelectionPolicy
import com.rainalarm.app.domain.NowVisualGeometry
import com.rainalarm.app.domain.RainMinuteAnalysis
import com.rainalarm.app.domain.RainMinuteAvailability
import com.rainalarm.app.domain.RainMinuteSeries
import com.rainalarm.app.domain.RainMinuteSeriesAnalyzer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
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

    fun label(
        startEpochSeconds: Long,
        minute: Int,
        zone: ZoneId,
        use24Hour: Boolean = true,
        locale: Locale = Locale.getDefault(),
    ): String =
        DateTimeFormatter.ofPattern(if (use24Hour) "HH:mm" else "h:mm a", locale).format(
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
    compassAppearance: NowCardAppearance = NowCardAppearance.FOLLOW_APP,
    graphAppearance: NowCardAppearance = NowCardAppearance.FOLLOW_APP,
    refreshStatus: NowRefreshStatus = NowRefreshStatus.Idle,
    entryTransition: EntryTransitionState = EntryTransitionPolicy.initial(active = true),
    onEntrySettled: (Int) -> Unit = {},
    selectedLocationKey: String? = null,
    onChartTimeSelected: (Double) -> Unit = {},
    screenActive: Boolean = true,
    featureTourScenario: FeatureTourScenario? = null,
    onFeatureTourTarget: (FeatureTourTargetBounds) -> Unit = {},
) {
    val context = LocalContext.current
    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result -> if (result.values.any { it }) useCurrentLocation() }
    val refreshOrRetryLocation: () -> Unit = {
        if (CurrentLocationSelectionPolicy.needsFixRetry(places.selectedId, selectedLocationKey != null)) {
            val granted = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
            if (granted) useCurrentLocation() else locationPermission.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            )
        } else refresh()
    }
    val currentSelected = places.selectedId == com.rainalarm.app.data.CURRENT_LOCATION_ID
    val currentLocationMessage = if (currentSelected) when (locationState) {
        LocationUiState.Locating -> WeatherDataStatusPolicy.loading(WeatherDataKind.LOCATION)
        is LocationUiState.Active -> locationState.message?.let {
            WeatherDataStatusPolicy.unavailable(WeatherDataKind.LOCATION)
        }
        is LocationUiState.Unavailable -> WeatherDataStatusPolicy.unavailable(WeatherDataKind.LOCATION)
        LocationUiState.Idle -> if (selectedLocationKey == null)
            WeatherDataStatusPolicy.unavailable(WeatherDataKind.LOCATION) else null
    } else null
    val displayedState = featureTourScenario?.let { ForecastUiState.Ready(it.forecast) } ?: state
    val unresolvedCurrent = currentSelected && selectedLocationKey == null && featureTourScenario == null
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val density = LocalDensity.current
        val metrics = NowLayoutPolicy.measure(maxWidth.value.toInt(), maxHeight.value.toInt(), density.fontScale)
        Column(
            Modifier.fillMaxWidth().widthIn(max = 760.dp).height(maxHeight)
                .padding(horizontal = metrics.horizontalPaddingDp.dp, vertical = metrics.verticalPaddingDp.dp),
            verticalArrangement = Arrangement.spacedBy(metrics.gapDp.dp),
        ) {
            BoxWithConstraints(
                Modifier.fillMaxWidth().height(metrics.headerHeightDp.dp),
                contentAlignment = Alignment.Center,
            ) {
                val maxTitleWidth = NowHeaderLayoutPolicy.titleMaxWidthDp(maxWidth.value).dp
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.featureTourTarget(
                        FeatureTourTarget.NOW_PLACE,
                        enabled = screenActive,
                        cornerRadiusDp = 18f,
                        paddingDp = 4f,
                        onBounds = onFeatureTourTarget,
                    ),
                ) {
                    Spacer(Modifier.width(NowHeaderLayoutPolicy.sideReserveDp.dp))
                    Text(
                        selectedLocationName ?: if (displayedState is ForecastUiState.Ready) displayedState.forecast.locationName
                        else stringResource(R.string.current_location),
                    fontSize = NowHeaderLayoutPolicy.titleFontSizeSp(metrics.simplifyText).sp,
                    lineHeight = NowHeaderLayoutPolicy.titleLineHeightSp(metrics.simplifyText).sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(max = maxTitleWidth).semantics { heading() },
                    )
                    Spacer(Modifier.width(NowHeaderLayoutPolicy.gapDp.dp))
                    PlaceSwitcher(places, locationState, selectPlace, useCurrentLocation)
                }
            }
            when (displayedState) {
                ForecastUiState.Loading -> if (unresolvedCurrent) NowPlaceholder(
                    title = currentLocationMessage ?: WeatherDataStatusPolicy.unavailable(WeatherDataKind.LOCATION),
                    detail = currentLocationMessage ?: WeatherDataStatusPolicy.unavailable(WeatherDataKind.LOCATION),
                    metrics = metrics,
                    refresh = refreshOrRetryLocation,
                    refreshStatus = refreshStatus,
                    compassAppearance = compassAppearance,
                    graphAppearance = graphAppearance,
                    pending = locationState is LocationUiState.Locating,
                    sourceLabel = currentLocationMessage
                        ?: WeatherDataStatusPolicy.unavailable(WeatherDataKind.LOCATION),
                    footerLabel = currentLocationMessage
                        ?: WeatherDataStatusPolicy.unavailable(WeatherDataKind.LOCATION),
                ) else NowPlaceholder(
                    WeatherDataStatusPolicy.loading(WeatherDataKind.RADAR),
                    WeatherDataStatusPolicy.loading(WeatherDataKind.RADAR), metrics,
                    refreshOrRetryLocation, refreshStatus, compassAppearance, graphAppearance,
                    pending = true,
                    sourceLabel = WeatherDataStatusPolicy.loading(WeatherDataKind.RADAR),
                    footerLabel = WeatherDataStatusPolicy.loading(WeatherDataKind.RADAR),
                )
                is ForecastUiState.Error -> if (unresolvedCurrent) NowPlaceholder(
                    title = currentLocationMessage ?: WeatherDataStatusPolicy.unavailable(WeatherDataKind.LOCATION),
                    detail = currentLocationMessage ?: WeatherDataStatusPolicy.unavailable(WeatherDataKind.LOCATION),
                    metrics = metrics,
                    refresh = refreshOrRetryLocation,
                    refreshStatus = refreshStatus,
                    compassAppearance = compassAppearance,
                    graphAppearance = graphAppearance,
                    pending = locationState is LocationUiState.Locating,
                    sourceLabel = currentLocationMessage
                        ?: WeatherDataStatusPolicy.unavailable(WeatherDataKind.LOCATION),
                    footerLabel = currentLocationMessage
                        ?: WeatherDataStatusPolicy.unavailable(WeatherDataKind.LOCATION),
                ) else NowPlaceholder(
                    WeatherDataStatusPolicy.unavailable(WeatherDataKind.RADAR),
                    WeatherDataStatusPolicy.unavailable(WeatherDataKind.RADAR),
                    metrics, refreshOrRetryLocation, refreshStatus, compassAppearance, graphAppearance,
                    sourceLabel = WeatherDataStatusPolicy.unavailable(WeatherDataKind.RADAR),
                    footerLabel = WeatherDataStatusPolicy.unavailable(WeatherDataKind.RADAR),
                )
                is ForecastUiState.Ready -> NowForecastContent(displayedState.forecast, metrics,
                    refreshOrRetryLocation, weather, visibleWeatherMetrics, compassAppearance, graphAppearance,
                    refreshStatus, entryTransition, onEntrySettled, selectedLocationKey, screenActive,
                    onChartTimeSelected,
                    if (featureTourScenario != null) featureTourScenario.forecast.sourceLabel else null,
                    if (featureTourScenario != null) null else currentLocationMessage,
                    screenActive,
                    onFeatureTourTarget,
                )
            }
        }
    }
}

@Composable
private fun NowPlaceholder(title: String, detail: String, metrics: NowLayoutMetrics,
    refresh: (() -> Unit)? = null, refreshStatus: NowRefreshStatus = NowRefreshStatus.Idle,
    compassAppearance: NowCardAppearance, graphAppearance: NowCardAppearance,
    pending: Boolean = title == WeatherDataStatusPolicy.loading(WeatherDataKind.RADAR),
    sourceLabel: String = if (pending) WeatherDataStatusPolicy.loading(WeatherDataKind.RADAR)
        else WeatherDataStatusPolicy.unavailable(WeatherDataKind.RADAR),
    footerLabel: String = if (pending) WeatherDataStatusPolicy.loading(WeatherDataKind.RADAR)
        else WeatherDataStatusPolicy.unavailable(WeatherDataKind.RADAR),
) {
    NowCardTheme(compassAppearance) {
    val border = NowBorder
    Card(colors = CardDefaults.cardColors(containerColor = NowSurface), shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth().height(metrics.compassHeightDp.dp)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp)) {
            NowCardHeader(title, sourceLabel, refresh, refreshStatus)
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center) {
                val diameter = minOf(maxWidth, maxHeight, 300.dp)
                Canvas(Modifier.size(diameter)) {
                    drawCircle(border.copy(alpha = 0.75f), radius = size.minDimension * 0.43f,
                        style = Stroke(width = 2f))
                    drawCircle(border.copy(alpha = 0.18f), radius = size.minDimension * 0.30f,
                        style = Stroke(width = size.minDimension * 0.05f))
                }
                if (pending) CircularProgressIndicator(color = NowAccent)
                else Text("—", color = NowAccent, fontSize = 25.sp, fontWeight = FontWeight.Bold)
            }
            Text(localizedWeatherStatus(footerLabel),
                color = NowMuted, fontSize = NowCompassStatusTypography.targetSp(false).sp,
                lineHeight = (NowCompassStatusTypography.targetSp(false) * 1.08f).sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().heightIn(min = 28.dp))
        }
    }
    }
    NowCardTheme(graphAppearance) {
    val border = NowBorder
    val muted = NowMuted
    val accent = NowAccent
    Card(colors = CardDefaults.cardColors(containerColor = NowSurfaceHigh), shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().height(metrics.chartHeightDp.dp)) {
        Column(Modifier.fillMaxSize().padding(start = 10.dp, top = 7.dp, end = 10.dp, bottom = 4.dp)) {
            Text(stringResource(R.string.now_next_hour), color = NowText, fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().heightIn(min = NowLayoutPolicy.chartTitleHeightDp.dp))
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.width(56.dp).fillMaxHeight()) {
                    listOf(stringResource(R.string.now_severe), stringResource(R.string.now_medium),
                        stringResource(R.string.now_light)).forEach { band ->
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
                        drawLine(accent.copy(alpha = 0.35f), Offset(0f, 0f),
                            Offset(0f, size.height), 1f)
                    }
                    Text(localizedWeatherStatus(detail), color = NowMuted, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 18.dp))
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth().height(NowLayoutPolicy.chartAxisHeightDp.dp).padding(start = 56.dp)) {
                val labelWidth = 46.dp.coerceAtMost(maxWidth)
                Text(stringResource(R.string.now_now), color = NowMuted, fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1, modifier = Modifier.width(labelWidth).offset(y = 7.dp))
            }
        }
    }
    }
}

@Composable
private fun NowCardTheme(mode: NowCardAppearance, content: @Composable () -> Unit) {
    val palette = NowCardPalettePolicy.resolve(mode, LocalRainAlarmPalette.current)
    CompositionLocalProvider(LocalRainAlarmPalette provides palette) {
        MaterialTheme(colorScheme = palette.materialScheme(), content = content)
    }
}

@Composable
private fun NowCardHeader(status: String, source: String, refresh: (() -> Unit)?, refreshStatus: NowRefreshStatus) {
    val sourceWithRefresh = when (refreshStatus) {
        NowRefreshStatus.Idle -> source
        NowRefreshStatus.Refreshing -> WeatherDataStatusPolicy.loading(WeatherDataKind.RADAR)
        NowRefreshStatus.Updated -> source
        is NowRefreshStatus.Failed -> WeatherDataStatusPolicy.unavailable(WeatherDataKind.RADAR)
    }
    val displayedStatus = localizedWeatherStatus(status)
    val displayedSource = localizedWeatherStatus(sourceWithRefresh)
    Row(Modifier.fillMaxWidth().height(NowLayoutPolicy.cardHeaderHeightDp.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(displayedStatus, fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(displayedSource, color = NowMuted, fontSize = 12.sp, lineHeight = 16.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { contentDescription = displayedSource })
        }
        refresh?.let { RefreshNowButton(it, refreshStatus) }
    }
}

@Composable
private fun NowForecastContent(forecast: ForecastSnapshot, metrics: NowLayoutMetrics,
    refresh: () -> Unit, weather: CurrentWeather?, visibleWeatherMetrics: Set<NowWeatherMetric>,
    compassAppearance: NowCardAppearance, graphAppearance: NowCardAppearance,
    refreshStatus: NowRefreshStatus,
    entryTransition: EntryTransitionState,
    onEntrySettled: (Int) -> Unit,
    selectedLocationKey: String?,
    screenActive: Boolean,
    onChartTimeSelected: (Double) -> Unit,
    exampleLabel: String? = null,
    locationNotice: String? = null,
    featureTourTargetsEnabled: Boolean = false,
    onFeatureTourTarget: (FeatureTourTargetBounds) -> Unit = {},
) {
    val series = forecast.nowcastSeries
    val analysis = series?.let(RainMinuteSeriesAnalyzer::analyze)
    val age = series?.let { NowSourceClock.ageMinutes(it, Instant.now().epochSecond) } ?: 0L
    val freshness = if (age == 0L) stringResource(R.string.now_fresh_now)
        else stringResource(R.string.now_fresh_minutes, age)
    if (series == null || series.availability == RainMinuteAvailability.UNAVAILABLE || analysis == null) {
        val unavailable = WeatherDataStatusPolicy.unavailable(WeatherDataKind.RADAR)
        NowPlaceholder(unavailable, unavailable, metrics, refresh,
            refreshStatus, compassAppearance, graphAppearance,
            sourceLabel = unavailable, footerLabel = unavailable)
        return
    }

    val likelySnow = series.likelySnowFor(analysis)
    val title = when {
        analysis.rainingNow && likelySnow -> stringResource(R.string.now_snow_likely)
        analysis.rainingNow -> stringResource(R.string.now_raining)
        analysis.arrivalMinute != null && likelySnow -> stringResource(R.string.now_snow_in)
        analysis.arrivalMinute != null -> stringResource(R.string.now_rain_in)
        series.availability == RainMinuteAvailability.PARTIAL -> stringResource(R.string.now_no_rain_yet)
        else -> stringResource(R.string.now_no_rain_hour)
    }
    val conciseTitle = if (metrics.simplifyText && series.availability == RainMinuteAvailability.AVAILABLE &&
        analysis.arrivalMinute == null && !analysis.rainingNow) {
        stringResource(R.string.now_dry_next_hour)
    } else title
    val coverage = if (series.availability == RainMinuteAvailability.PARTIAL) {
        stringResource(R.string.now_coverage_through, series.points.last().minute)
    } else ""
    val sourceKind = when {
        series.intensityEncoding == com.rainalarm.app.domain.RadarIntensityEncoding.REGIONAL_AREA_CHART ->
            stringResource(R.string.now_source_area_forecast)
        forecast.sourceLabel.contains("RainViewer", true) ||
            forecast.sourceLabel.contains("OPERA", true) -> stringResource(R.string.now_source_estimate)
        else -> stringResource(R.string.now_source_radar)
    }
    val source = locationNotice ?: exampleLabel ?: "$sourceKind · $freshness$coverage"
    val context = LocalContext.current
    val animationsEnabled = remember(context) {
        runCatching {
            android.provider.Settings.Global.getFloat(
                context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
            ) > 0f
        }.getOrDefault(true)
    }
    var animatedLocationKey by remember { mutableStateOf(selectedLocationKey) }
    val locationChanged = animatedLocationKey != selectedLocationKey
    val entryProgress = remember(entryTransition.generation, selectedLocationKey) {
        Animatable(
            if (entryTransition.phase == EntryTransitionPhase.SETTLED && !locationChanged) 1f else 0f,
        )
    }
    LaunchedEffect(
        entryTransition.generation,
        entryTransition.phase,
        selectedLocationKey,
        animationsEnabled,
    ) {
        val shouldAnimateLocationChange = animatedLocationKey != selectedLocationKey
        animatedLocationKey = selectedLocationKey
        when (entryTransition.phase) {
            EntryTransitionPhase.PREPARED -> entryProgress.snapTo(0f)
            EntryTransitionPhase.PLAY_REQUESTED -> {
                if (animationsEnabled) {
                    entryProgress.animateTo(
                        1f,
                        tween(NowMotionPolicy.durationMillis, easing = FastOutSlowInEasing),
                    )
                } else entryProgress.snapTo(1f)
                onEntrySettled(entryTransition.generation)
            }
            EntryTransitionPhase.SETTLED -> {
                // A selected-place change intentionally receives the same focus motion, while an
                // ordinary refresh/recomposition reuses the already-settled Animatable.
                if (shouldAnimateLocationChange && screenActive && animationsEnabled) {
                    entryProgress.animateTo(
                        1f,
                        tween(NowMotionPolicy.durationMillis, easing = FastOutSlowInEasing),
                    )
                } else entryProgress.snapTo(1f)
            }
        }
    }
    val visibleProgress = if (animationsEnabled) entryProgress.value else 1f
    NowCardTheme(compassAppearance) {
        RainCompass(series, analysis, conciseTitle, source, refresh, refreshStatus, visibleProgress, animationsEnabled,
            weather, visibleWeatherMetrics,
            Modifier.fillMaxWidth().height(metrics.compassHeightDp.dp)
                .featureTourTarget(
                    FeatureTourTarget.NOW_COMPASS,
                    enabled = featureTourTargetsEnabled,
                    cornerRadiusDp = 28f,
                    paddingDp = 3f,
                    onBounds = onFeatureTourTarget,
                ))
    }
    NowCardTheme(graphAppearance) {
        RainTimeline(series, Modifier.fillMaxWidth().height(metrics.chartHeightDp.dp)
            .featureTourTarget(
                FeatureTourTarget.NOW_GRAPH,
                enabled = featureTourTargetsEnabled,
                cornerRadiusDp = 24f,
                paddingDp = 3f,
                onBounds = onFeatureTourTarget,
            ),
            visibleProgress, onChartTimeSelected)
    }
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
    val likelySnow = series.likelySnowFor(analysis)
    val textureKind = NowPrecipitationTexturePolicy.select(
        analysis.rainingNow, analysis.arrivalMinute, likelySnow,
    )
    val hasRain = textureKind != null
    val resources = LocalResources.current
    val precipitationTexture = remember(resources, textureKind) {
        textureKind?.let { kind ->
            val resource = when (kind) {
                NowPrecipitationTextureKind.RAIN -> R.drawable.now_rain_drops
                NowPrecipitationTextureKind.LIKELY_SNOW -> R.drawable.now_snowflakes
            }
            BitmapFactory.decodeResource(resources, resource,
                BitmapFactory.Options().apply { inSampleSize = 2 })?.asImageBitmap()
        }
    }
    val precipitationTextureCrop = remember(precipitationTexture, textureKind) {
        precipitationTexture?.let { texture ->
            textureKind?.let { NowRainTexturePolicy.discCrop(it, texture.width, texture.height) }
        }
    }
    val pointerTextureCrop = remember(precipitationTexture, textureKind) {
        precipitationTexture?.let { texture ->
            textureKind?.let { NowRainTexturePolicy.pointerCrop(it, texture.width, texture.height) }
        }
    }
    val rainFill = NowPeakRainColorPolicy.forSeries(series, analysis)?.let {
        Color(it.argb).copy(alpha = 0.92f)
    }
    val pointerFill = if (sourceBearing != null) rainFill else null
    val centerFill = rainFill ?: NowDeepBlue
    val centerTextMeasurer = rememberTextMeasurer()
    val footerTextMeasurer = rememberTextMeasurer()
    val animatedBearing by animateFloatAsState(
        targetValue = (sourceBearing ?: 0.0).toFloat(),
        animationSpec = if (animationsEnabled) tween(450) else snap(),
        label = "rain-source-bearing",
    )
    val centerLabel = when {
        analysis.rainingNow -> stringResource(R.string.now_now)
        analysis.arrivalMinute != null -> analysis.arrivalMinute.toString()
        else -> weather?.temperatureC?.roundToInt()?.let { "$it°" } ?: "—"
    }
    val directionLabel = if (!hasRain) stringResource(R.string.now_incoming_clear) else
        sourceBearing?.let {
            stringResource(
                if (series.likelySnowFor(analysis)) R.string.now_likely_snow_from else R.string.now_from,
                localizedCardinalDirectionLabel(it),
            )
        } ?: stringResource(R.string.now_direction_unavailable)
    val northUp = stringResource(R.string.now_north_up)
    val stateDescription = when {
        !hasRain -> buildString {
            append(stringResource(R.string.now_incoming_clear)).append(". ")
            weather?.temperatureC?.let {
                append(stringResource(R.string.now_model_temperature, it.roundToInt()))
            }
        }.trim()
        analysis.rainingNow -> if (likelySnow) stringResource(R.string.now_snow_likely)
            else stringResource(R.string.now_raining)
        else -> stringResource(R.string.now_arrival_minutes, centerLabel)
    }
    val description = if (sourceBearing == null || !hasRain) stringResource(
        R.string.now_compass_no_direction_description,
        stateDescription,
        if (hasRain) stringResource(R.string.now_direction_unavailable) else directionLabel,
        northUp,
    ) else stringResource(
        R.string.now_compass_direction_description,
        stateDescription,
        directionLabel,
        sourceBearing.toInt(),
        northUp,
    )
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
                if (stackedReadouts) NowWeatherReadoutPolicy.directionHeightDp(fontScale, true) else 0
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
                        if (precipitationTexture != null && pointerTextureCrop != null) {
                            val textureSide = NowRainTexturePolicy.pointerTextureDiameterPx(radius)
                            val halfTexture = textureSide / 2f
                            clipPath(silhouette) {
                                rotate(animatedBearing, pivot = indicator) {
                                    val sidePx = textureSide.roundToInt().coerceAtLeast(1)
                                    drawImage(precipitationTexture,
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
                    if (hasRain && precipitationTexture != null && precipitationTextureCrop != null) {
                        val clip = Path().apply {
                            addOval(Rect(center.x - discRadius, center.y - discRadius,
                                center.x + discRadius, center.y + discRadius))
                        }
                        val diameterPx = (discRadius * 2f).roundToInt().coerceAtLeast(1)
                        clipPath(clip) {
                            drawImage(precipitationTexture,
                                srcOffset = IntOffset(precipitationTextureCrop.left, precipitationTextureCrop.top),
                                srcSize = IntSize(precipitationTextureCrop.side, precipitationTextureCrop.side),
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
                Text(stringResource(R.string.direction_north_short), color = NowText, fontSize = 24.sp,
                    lineHeight = 24.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.TopCenter))
                Text(stringResource(R.string.direction_east_short), color = NowMuted, fontSize = 24.sp,
                    lineHeight = 24.sp,
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = NowCompassCardinalPolicy.eastEndInsetDp.dp))
                Text(stringResource(R.string.direction_south_short), color = NowMuted, fontSize = 24.sp,
                    lineHeight = 24.sp,
                    modifier = Modifier.align(Alignment.BottomCenter))
                Text(stringResource(R.string.direction_west_short), color = NowMuted, fontSize = 24.sp,
                    lineHeight = 24.sp,
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 3.dp))
                Column(Modifier.align(Alignment.Center).graphicsLayer {
                    val scale = NowMotionPolicy.centerScale(entryProgress)
                    scaleX = scale
                    scaleY = scale
                }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(centerLabel, color = Color.White, fontSize = centerFontSize, fontWeight = FontWeight.Bold,
                        style = TextStyle(shadow = CenterGlyphShadow),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!analysis.rainingNow && analysis.arrivalMinute != null) Text(
                        stringResource(R.string.now_minutes_short), color = Color.White,
                        style = TextStyle(shadow = CenterGlyphShadow),
                        fontSize = NowMotionPolicy.centerUnitFontSizeSp(mainCenterFontSize).sp)
                }
            }
            NowWeatherReadouts(weather, visibleWeatherMetrics,
                Modifier.align(Alignment.BottomCenter).fillMaxWidth())
            val footerDirection = when {
                !hasRain -> stringResource(R.string.now_no_rain)
                sourceBearing == null -> stringResource(R.string.now_from, "—")
                else -> directionLabel
            }
            val targetFooterFontSp = NowCompassStatusTypography.targetSp(stackedReadouts)
            val measuredFooterWidth = remember(
                footerDirection,
                targetFooterFontSp,
                footerTextMeasurer,
            ) {
                footerTextMeasurer.measure(
                    AnnotatedString(footerDirection),
                    style = TextStyle(fontSize = targetFooterFontSp.sp),
                    maxLines = 1,
                    softWrap = false,
                ).size.width
            }
            val availableFooterWidthPx = with(density) {
                NowCompassStatusTypography.availableWidthDp(
                    maxWidth.value,
                    stackedReadouts,
                ).dp.toPx()
            }
            val fittedFooterFontSp = NowCompassStatusTypography.fittedSp(
                stackedReadouts,
                measuredFooterWidth,
                availableFooterWidthPx,
            )
            Text(footerDirection, color = if (sourceBearing == null) NowMuted else NowAccent,
                fontSize = fittedFooterFontSp.sp,
                lineHeight = (fittedFooterFontSp * 1.08f).sp,
                maxLines = 1, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = if (stackedReadouts) readoutHeightDp.dp else 0.dp)
                    .heightIn(min = NowWeatherReadoutPolicy.directionHeightDp(fontScale, stackedReadouts).dp))
        }
        }
    }
}

@Composable
private fun NowWeatherReadouts(weather: CurrentWeather?, metrics: Set<NowWeatherMetric>, modifier: Modifier) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val solar = weather?.solarToday(
        Instant.now().epochSecond,
        use24Hour = DateFormat.is24HourFormat(context),
        locale = locale,
    )
    val selected = NowWeatherMetric.entries.filter(metrics::contains)
    val values = selected.map { metric ->
        metric to (when (metric) {
            NowWeatherMetric.TEMPERATURE -> weather?.temperatureC?.roundToInt()?.let { "$it°" }
            NowWeatherMetric.PRESSURE -> weather?.pressureHpa?.roundToInt()?.let { "$it hPa" }
            NowWeatherMetric.HUMIDITY -> weather?.humidityPercent?.let { "$it%" }
            NowWeatherMetric.UV_INDEX -> weather?.uvIndex?.roundToInt()?.toString()
            NowWeatherMetric.WIND -> if (weather?.windSpeedKmh != null && weather.windFromDegrees != null)
                stringResource(
                    R.string.wind_value,
                    weather.windSpeedKmh.roundToInt(),
                    localizedCardinalDirectionLabel(weather.windFromDegrees),
                ) else null
        } ?: "—")
    }
    val solarRows = listOf(
        stringResource(R.string.now_sunrise) to (solar?.first ?: "—"),
        stringResource(R.string.now_sunset) to (solar?.second ?: "—"),
    )
    val metricRows = values.map { (metric, value) -> localizedNowMetricLabel(metric) to value }
    val weatherSource = stringResource(R.string.now_weather_source)
    BoxWithConstraints(modifier.padding(vertical = 2.dp)) {
        val fontScale = LocalDensity.current.fontScale
        val font = NowWeatherReadoutPolicy.fontSizeSp(maxWidth.value.toInt(), fontScale).sp
        if (NowWeatherReadoutPolicy.stack(maxWidth.value.toInt(), fontScale)) {
            Column(Modifier.fillMaxWidth()) {
                DividerReadout(solarRows, font, Modifier.align(Alignment.Start), weatherSource)
                if (metricRows.isNotEmpty()) DividerReadout(metricRows, font,
                    Modifier.align(Alignment.End), weatherSource)
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom) {
                DividerReadout(solarRows, font, Modifier, weatherSource)
                if (metricRows.isNotEmpty()) DividerReadout(metricRows, font, Modifier, weatherSource)
            }
        }
    }
}

@Composable
private fun DividerReadout(rows: List<Pair<String, String>>, font: androidx.compose.ui.unit.TextUnit,
    modifier: Modifier, source: String) {
    val rowLineHeight = (font.value * 1.2f).sp
    val rowHeight = NowWeatherReadoutPolicy.rowMinimumHeightDp(font.value.toInt(),
        LocalDensity.current.fontScale).dp
    Row(modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.Bottom) {
        Column(horizontalAlignment = Alignment.End) {
            rows.forEach { (label, _) -> Text(label, color = NowMuted, fontSize = font, lineHeight = rowLineHeight,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.heightIn(min = rowHeight)) }
        }
        Spacer(Modifier.width(2.dp))
        Box(Modifier.width(1.dp).fillMaxHeight().background(NowBorder))
        Spacer(Modifier.width(2.dp))
        Column {
            rows.forEach { (label, value) ->
                val valueDescription = if (value == "—") {
                    stringResource(R.string.now_value_unavailable, label, source)
                } else stringResource(R.string.now_value_description, label, value, source)
                Text(value, color = NowText, fontSize = font, lineHeight = rowLineHeight,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.heightIn(min = rowHeight)
                        .semantics { contentDescription = valueDescription })
            }
        }
    }
}

@Composable
private fun RefreshNowButton(refresh: () -> Unit, refreshStatus: NowRefreshStatus) {
    val loadingDescription = localizedWeatherStatus(
        WeatherDataStatusPolicy.loading(WeatherDataKind.RADAR),
    )
    IconButton(onClick = refresh, modifier = Modifier.size(48.dp)) {
        if (refreshStatus is NowRefreshStatus.Refreshing) {
            CircularProgressIndicator(Modifier.size(22.dp).semantics {
                contentDescription = loadingDescription
            }, color = NowAccent, strokeWidth = 2.dp)
        } else Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.now_refresh), tint = NowAccent)
    }
}

@Composable
private fun RainTimeline(
    series: RainMinuteSeries,
    modifier: Modifier = Modifier,
    entryProgress: Float,
    onTimeSelected: (Double) -> Unit,
) {
    val context = LocalContext.current
    val NowSurfaceHigh = LocalRainAlarmPalette.current.elevated
    val NowMuted = LocalRainAlarmPalette.current.muted
    val NowBorder = LocalRainAlarmPalette.current.border
    val NowAccent = LocalRainAlarmPalette.current.accent
    val endMinute = series.points.last().minute.coerceIn(0, 60)
    val chartTitle = when (endMinute) {
        0 -> stringResource(R.string.now_current_radar)
        60 -> stringResource(R.string.now_next_hour)
        else -> stringResource(R.string.now_next_minutes, endMinute)
    }
    val description = "$chartTitle. ${stringResource(R.string.now_graph_description)}"
    val graphDescription = stringResource(R.string.now_graph_description)
    val inspectDescription = stringResource(R.string.now_chart_inspect)
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
                listOf(stringResource(R.string.now_severe), stringResource(R.string.now_medium),
                    stringResource(R.string.now_light)).forEach { band ->
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                        Text(band, color = NowMuted, fontSize = 11.sp)
                    }
                }
            }
            Canvas(Modifier.weight(1f).fillMaxSize()
                .pointerInput(series.startEpochSeconds, endMinute, onTimeSelected) {
                    detectTapGestures { tap ->
                        NowChartLayout.epochAtX(series.startEpochSeconds, tap.x, size.width.toFloat(),
                            endMinute)?.let(onTimeSelected)
                    }
                }
                .semantics {
                    contentDescription = graphDescription
                    onClick(label = inspectDescription) {
                        onTimeSelected(series.startEpochSeconds + endMinute * 60.0)
                        true
                    }
                }) {
                val chartHeight = size.height
                fun plotX(minute: Int) = NowChartLayout.plotX(minute, size.width, endMinute)
                drawRect(Color(series.displayColor(0.88f)).copy(alpha = 0.045f), size = androidx.compose.ui.geometry.Size(size.width, chartHeight / 3f))
                drawRect(Color(series.displayColor(0.62f)).copy(alpha = 0.045f), topLeft = Offset(0f, chartHeight / 3f), size = androidx.compose.ui.geometry.Size(size.width, chartHeight / 3f))
                drawRect(Color(series.displayColor(0.34f)).copy(alpha = 0.045f), topLeft = Offset(0f, chartHeight * 2f / 3f), size = androidx.compose.ui.geometry.Size(size.width, chartHeight / 3f))
                listOf(1f / 3f, 2f / 3f).forEach { level ->
                    val y = NowVisualGeometry.chartY(level, chartHeight)
                    drawLine(NowBorder, Offset(0f, y), Offset(size.width, y), 1f)
                }
                drawLine(NowMuted.copy(alpha = 0.9f),
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
                    series.points.zipWithNext().forEach { (previous, point) ->
                        val segment = Path().apply {
                            val px = plotX(previous.minute)
                            val py = NowVisualGeometry.chartY(series.chartSeverity(previous.average), chartHeight)
                            val x = plotX(point.minute)
                            val y = NowVisualGeometry.chartY(series.chartSeverity(point.average), chartHeight)
                            moveTo(px, py)
                            quadraticTo((px + x) / 2f, py, x, y)
                        }
                        val segmentColor = if (point.likelySnow) {
                            Color(series.precipitationColor(point.average, true))
                        } else NowAccent
                        drawPath(segment, segmentColor, style = Stroke(width = 3f, cap = StrokeCap.Round))
                    }
                    if (series.points.size == 1) drawPath(average, NowAccent,
                        style = Stroke(width = 3f, cap = StrokeCap.Round))
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
            val fontScale = LocalDensity.current.fontScale
            val locale = LocalConfiguration.current.locales[0]
            val ticks = NowChartLayout.clockTicks(
                series.startEpochSeconds, endMinute, ZoneId.systemDefault(),
                maxWidth.value.toInt(), fontScale,
                use24Hour = DateFormat.is24HourFormat(context), locale = locale,
                nowLabel = stringResource(R.string.now_now),
            )
            Canvas(Modifier.fillMaxSize()) {
                ticks.forEach { tick ->
                    val x = NowChartLayout.plotX(tick.offsetMinutes, size.width, endMinute)
                    drawLine(NowMuted, Offset(x, 0f), Offset(x, 5.dp.toPx()), 1.5f)
                }
            }
            ticks.forEachIndexed { index, tick ->
                if (!tick.showLabel) return@forEachIndexed
                val labelWidth = NowChartLayout.labelWidthDp(tick.label, fontScale)
                    .coerceAtMost(maxWidth.value + if (index == 0) 56f else 0f)
                val x = NowChartLayout.plotX(tick.offsetMinutes, maxWidth.value, endMinute)
                val left = if (index == 0) -labelWidth / 2f
                    else NowChartLayout.labelLeft(x, maxWidth.value, labelWidth)
                Text(tick.label, color = if (index == 0) NowAccent else NowMuted,
                    fontSize = 11.sp, textAlign = TextAlign.Center, lineHeight = 13.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(labelWidth.dp).offset(x = left.dp, y = 7.dp))
            }
        }
        }
    }
}

@Composable
private fun localizedNowMetricLabel(metric: NowWeatherMetric): String = stringResource(when (metric) {
    NowWeatherMetric.TEMPERATURE -> R.string.metric_temperature
    NowWeatherMetric.PRESSURE -> R.string.metric_pressure
    NowWeatherMetric.HUMIDITY -> R.string.metric_humidity
    NowWeatherMetric.UV_INDEX -> R.string.metric_uv
    NowWeatherMetric.WIND -> R.string.metric_wind
})
