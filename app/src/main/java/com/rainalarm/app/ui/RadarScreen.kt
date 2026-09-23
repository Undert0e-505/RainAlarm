package com.rainalarm.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rainalarm.app.LocationUiState
import com.rainalarm.app.data.RadarSession
import com.rainalarm.app.data.RadarProviderCoordinator
import com.rainalarm.app.data.RadarSettingsRepository
import com.rainalarm.app.data.SavedPlace
import com.rainalarm.app.data.CURRENT_LOCATION_ID
import com.rainalarm.app.data.PlaceCollection
import com.rainalarm.app.data.RadarPlaybackSpeed
import com.rainalarm.app.data.RadarMapLayer
import com.rainalarm.app.data.RadarMapStyle
import com.rainalarm.app.data.CurrentWeather
import com.rainalarm.app.data.WindGrid
import com.rainalarm.app.data.WindViewport
import com.rainalarm.app.data.EumetLayerMetadata
import com.rainalarm.app.data.EumetViewRepository
import com.rainalarm.app.data.WeatherLayerRepository
import com.rainalarm.app.data.CurrentLocationSelectionPolicy
import com.rainalarm.app.domain.RadarCameraMemory
import com.rainalarm.app.domain.RadarSelectedCenterPolicy
import com.rainalarm.app.domain.RadarPlaybackClock
import com.rainalarm.app.domain.RadarEntryClock
import com.rainalarm.app.domain.GeoPoint
import com.rainalarm.app.domain.RadarMotionPolicy
import com.rainalarm.app.domain.RadarTimeline
import com.rainalarm.app.domain.RadarTimelineBracket
import com.rainalarm.app.domain.RadarTimelineTicks
import com.rainalarm.app.domain.cardinalDirection
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private val Surface: Color @Composable get() = LocalRainAlarmPalette.current.surface
private val Secondary: Color @Composable get() = LocalRainAlarmPalette.current.muted
private val Accent: Color @Composable get() = LocalRainAlarmPalette.current.accent
private val Danger: Color @Composable get() = LocalRainAlarmPalette.current.danger

internal object RadarTopControlsPolicy {
    const val timeTopDp = 12
    const val controlSizeDp = 48
    const val segmentSizeDp = controlSizeDp
    const val segmentDividerDp = 1
    const val segmentGroupWidthDp = controlSizeDp * 3
    const val controlsEndDp = 4
    const val segmentGapDp = 4
    const val segmentTopDp = controlsEndDp + controlSizeDp + segmentGapDp
    const val statusGapDp = 4
    const val statusTopDp = segmentTopDp + segmentSizeDp + statusGapDp
    const val statusEndDp = controlsEndDp
    fun timeLabelSp(mapWidthDp: Int): Int = if (mapWidthDp < 340) 18 else 20
    fun timeLabelMaxWidthDp(mapWidthDp: Int, showFollow: Boolean): Int {
        val controlCount = if (showFollow) 3 else 2
        return (mapWidthDp - 12 - controlsEndDp - controlCount * controlSizeDp - 8)
            .coerceAtLeast(72)
    }
    fun statusMaxWidthDp(mapWidthDp: Int): Int =
        (mapWidthDp - statusEndDp - 12).coerceIn(96, 220)
}

internal data class SatelliteFeedbackState(
    val enabled: Boolean = false,
    val visible: Boolean = false,
    val holdingTerminal: Boolean = false,
    val generation: Int = 0,
)

internal object SatelliteFeedbackPolicy {
    const val terminalHoldMillis = 1_000L
    const val fadeMillis = 180

    /** One activation owns one feedback lifetime; later refresh/freshness changes cannot reopen it. */
    fun onInput(
        previous: SatelliteFeedbackState,
        enabled: Boolean,
        terminal: Boolean,
    ): SatelliteFeedbackState {
        if (!enabled) return if (!previous.enabled && !previous.visible) previous else
            SatelliteFeedbackState(generation = previous.generation + 1)
        var next = if (!previous.enabled) previous.copy(
            enabled = true,
            visible = true,
            holdingTerminal = false,
            generation = previous.generation + 1,
        ) else previous
        if (!next.visible) return next
        next = when {
            terminal && !next.holdingTerminal -> next.copy(
                holdingTerminal = true,
                generation = next.generation + 1,
            )
            !terminal && next.holdingTerminal -> next.copy(
                holdingTerminal = false,
                generation = next.generation + 1,
            )
            else -> next
        }
        return next
    }

    fun afterTerminalElapsed(
        state: SatelliteFeedbackState,
        generation: Int,
        elapsedMillis: Long,
    ): SatelliteFeedbackState = if (
        state.enabled && state.visible && state.holdingTerminal &&
        state.generation == generation && elapsedMillis >= terminalHoldMillis
    ) state.copy(visible = false, holdingTerminal = false) else state
}

internal object RadarLayerGatePolicy {
    fun unavailableReason(layer: RadarMapLayer, hasPlace: Boolean, daylight: Boolean?): String? = when {
        !hasPlace -> "Select a place for this layer"
        layer == RadarMapLayer.FOG && daylight == true -> "Night only · fog / low cloud"
        layer == RadarMapLayer.FOG && daylight == null -> "Daylight status unavailable"
        else -> null
    }
}

@Composable
private fun RadarLayerStatus(
    mapLayer: RadarMapLayer,
    ancillaryStatus: AncillaryStatus,
    weather: CurrentWeather?,
    mapWidthDp: Int,
    modifier: Modifier = Modifier,
) {
    if (mapLayer == RadarMapLayer.OFF) return
    val (status, accessibilityStatus) = when (mapLayer) {
        RadarMapLayer.OFF -> return
        RadarMapLayer.WIND -> {
            val wind = weather?.takeIf { it.freshAt(Instant.now().epochSecond) }
            val speed = wind?.windSpeedKmh
            val from = wind?.windFromDegrees
            when {
                ancillaryStatus == AncillaryStatus.Loading || ancillaryStatus == AncillaryStatus.Off ->
                    "Wind loading" to "Wind layer loading"
                ancillaryStatus !is AncillaryStatus.Wind || speed == null || from == null ->
                    "Wind unavailable" to "Selected-place wind unavailable"
                else -> "${speed.toInt()} km/h ${cardinalDirection(from)}" to
                    "Selected-place wind from ${cardinalDirection(from)} at ${speed.toInt()} kilometres per hour"
            }
        }
        RadarMapLayer.LIGHTNING -> when (ancillaryStatus) {
            AncillaryStatus.Loading, AncillaryStatus.Off -> "Lightning loading"
            is AncillaryStatus.Satellite -> "Lightning available"
            is AncillaryStatus.Unavailable -> ancillaryStatus.message
            else -> "Lightning unavailable"
        }.let { it to it }
        RadarMapLayer.FOG -> when (ancillaryStatus) {
            AncillaryStatus.Loading, AncillaryStatus.Off -> "Fog loading"
            is AncillaryStatus.Satellite -> "Fog available"
            is AncillaryStatus.Unavailable -> ancillaryStatus.message
            else -> "Fog unavailable"
        }
        .let { it to it }
    }
    Text(status,
        color = LocalRainAlarmPalette.current.mapLabelText,
        fontSize = 10.sp,
        textAlign = TextAlign.End,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.widthIn(max = RadarTopControlsPolicy.statusMaxWidthDp(mapWidthDp).dp)
            .background(LocalRainAlarmPalette.current.mapLabelSurface, RoundedCornerShape(5.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp)
            .semantics { contentDescription = accessibilityStatus },
    )
}

@Composable
private fun RadarLayerStatuses(
    enabledMapLayers: Set<RadarMapLayer>,
    statuses: Map<RadarMapLayer, AncillaryStatus>,
    weather: CurrentWeather?,
    mapWidthDp: Int,
    modifier: Modifier = Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.End) {
        if (RadarMapLayer.WIND in enabledMapLayers) {
            RadarLayerStatus(RadarMapLayer.WIND,
                statuses[RadarMapLayer.WIND] ?: AncillaryStatus.Off, weather, mapWidthDp)
        }
        SatelliteActivationStatus(RadarMapLayer.LIGHTNING,
            RadarMapLayer.LIGHTNING in enabledMapLayers,
            statuses[RadarMapLayer.LIGHTNING] ?: AncillaryStatus.Off,
            weather, mapWidthDp)
        SatelliteActivationStatus(RadarMapLayer.FOG,
            RadarMapLayer.FOG in enabledMapLayers,
            statuses[RadarMapLayer.FOG] ?: AncillaryStatus.Off,
            weather, mapWidthDp)
    }
}

@Composable
private fun SatelliteActivationStatus(
    layer: RadarMapLayer,
    enabled: Boolean,
    status: AncillaryStatus,
    weather: CurrentWeather?,
    mapWidthDp: Int,
) {
    var feedback by remember(layer) { mutableStateOf(SatelliteFeedbackState()) }
    val terminal = status !is AncillaryStatus.Off && status !is AncillaryStatus.Loading
    LaunchedEffect(enabled, terminal) {
        feedback = SatelliteFeedbackPolicy.onInput(feedback, enabled, terminal)
    }
    LaunchedEffect(feedback.generation, feedback.holdingTerminal) {
        if (!feedback.holdingTerminal) return@LaunchedEffect
        val generation = feedback.generation
        delay(SatelliteFeedbackPolicy.terminalHoldMillis)
        feedback = SatelliteFeedbackPolicy.afterTerminalElapsed(
            feedback, generation, SatelliteFeedbackPolicy.terminalHoldMillis,
        )
    }
    // Disabling removes the message immediately and also cancels its keyed coroutine.
    if (enabled) AnimatedVisibility(
        visible = feedback.visible,
        enter = fadeIn(tween(90)),
        exit = fadeOut(tween(SatelliteFeedbackPolicy.fadeMillis)),
    ) {
        RadarLayerStatus(layer, status, weather, mapWidthDp, Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun RadarLayerSegments(
    enabledMapLayers: Set<RadarMapLayer>,
    darkMap: Boolean,
    setMapLayerEnabled: (RadarMapLayer, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val outerShape = RoundedCornerShape(8.dp)
    val inactiveTint = if (darkMap) Color.White else Color.Black
    Box(
        modifier
            .size(RadarTopControlsPolicy.segmentGroupWidthDp.dp,
                RadarTopControlsPolicy.segmentSizeDp.dp)
            .clip(outerShape)
            .border(1.dp, inactiveTint.copy(alpha = 0.45f), outerShape),
    ) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            RadarMapLayer.overlays.forEach { layer ->
                val active = layer in enabledMapLayers
                val icon = when (layer) {
                    RadarMapLayer.WIND -> Icons.Default.Air
                    RadarMapLayer.LIGHTNING -> Icons.Default.Bolt
                    RadarMapLayer.FOG -> Icons.Default.CloudQueue
                    RadarMapLayer.OFF -> error("Off is not a map overlay segment")
                }
                Box(
                    Modifier.size(RadarTopControlsPolicy.segmentSizeDp.dp)
                        .background(if (active) Accent.copy(alpha = 0.18f) else Color.Transparent)
                        .toggleable(
                            value = active,
                            role = Role.Switch,
                            onValueChange = { setMapLayerEnabled(layer, it) },
                        )
                        .semantics {
                            contentDescription = "${layer.label} map layer"
                            stateDescription = if (active) "Enabled" else "Disabled"
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null,
                        tint = if (active) Accent else inactiveTint,
                        modifier = Modifier.size(24.dp))
                }
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            val dividerWidth = RadarTopControlsPolicy.segmentDividerDp.dp.toPx()
            val dividerInset = 6.dp.toPx()
            for (join in 1 until RadarMapLayer.overlays.size) {
                val x = RadarTopControlsPolicy.segmentSizeDp.dp.toPx() * join
                drawLine(inactiveTint.copy(alpha = 0.3f),
                    Offset(x, dividerInset), Offset(x, size.height - dividerInset), dividerWidth)
            }
        }
    }
}

private sealed interface AncillaryStatus {
    data object Off : AncillaryStatus
    data object Loading : AncillaryStatus
    data class Wind(val grid: WindGrid) : AncillaryStatus
    data class Satellite(val metadata: EumetLayerMetadata) : AncillaryStatus
    data class Unavailable(val message: String) : AncillaryStatus
}

@Composable
@SuppressLint("LogNotTimber") // Local lifecycle diagnostics for device-only radar failures.
fun LiveRadarScreen(
    place: SavedPlace?,
    liveMapPlace: SavedPlace?,
    places: PlaceCollection,
    playbackSpeed: RadarPlaybackSpeed,
    mapStyle: RadarMapStyle,
    saveAndSelect: (SavedPlace) -> Unit,
    locationState: LocationUiState,
    currentRecenterTick: Int,
    useCurrentLocation: () -> Unit,
    recenterToCurrentLocation: () -> Unit,
    recenterToSelectedPlace: () -> Unit,
    cameraMemory: RadarCameraMemory,
    selectPlace: (String) -> Unit,
    enabledMapLayers: Set<RadarMapLayer>,
    windArrowScale: Float,
    setMapLayerEnabled: (RadarMapLayer, Boolean) -> Unit,
    currentWeather: CurrentWeather?,
    refreshPointWeather: () -> Unit,
    selectedPlaceId: String,
    chartTimeRequest: RadarChartTimeRequest? = null,
    onChartTimeConsumed: (Int) -> Unit = {},
    showLikelySnow: Boolean = false,
) {
    val context = LocalContext.current
    val loader = remember { RadarProviderCoordinator(RadarSettingsRepository(context)) }
    var session by remember { mutableStateOf<RadarSession?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0 to 0) }
    var reload by remember { mutableIntStateOf(0) }
    var layerRefresh by remember { mutableIntStateOf(0) }
    var previousLightningRefresh by remember { mutableIntStateOf(0) }
    var previousFogRefresh by remember { mutableIntStateOf(0) }
    var previousWindRefresh by remember { mutableIntStateOf(0) }
    var windStatus by remember { mutableStateOf<AncillaryStatus>(AncillaryStatus.Off) }
    var lightningStatus by remember { mutableStateOf<AncillaryStatus>(AncillaryStatus.Off) }
    var fogStatus by remember { mutableStateOf<AncillaryStatus>(AncillaryStatus.Off) }
    var windViewport by remember { mutableStateOf<WindViewport?>(null) }
    var layerNow by remember { mutableStateOf(Instant.now().epochSecond) }
    LaunchedEffect(enabledMapLayers.isNotEmpty()) {
        if (enabledMapLayers.isNotEmpty()) while (true) {
            delay(60_000)
            layerNow = Instant.now().epochSecond
        }
    }
    var longPressed by remember { mutableStateOf<GeoPoint?>(null) }
    var locationMessage by remember { mutableStateOf<String?>(null) }
    var locationRequested by remember { mutableStateOf(false) }
    var pendingMapRecenter by remember { mutableStateOf(false) }
    var followLive by remember { mutableStateOf(false) }
    LaunchedEffect(selectedPlaceId, liveMapPlace) {
        if (!RadarLiveMapPolicy.canFollow(selectedPlaceId, liveMapPlace)) followLive = false
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.any { it }) {
            locationMessage = null
            if (pendingMapRecenter) recenterToCurrentLocation() else useCurrentLocation()
        } else locationMessage = "Location permission was not granted. Selection unchanged."
        pendingMapRecenter = false
    }
    val requestCurrentLocation: (Boolean) -> Unit = { recenterMap ->
        locationRequested = true
        locationMessage = null
        pendingMapRecenter = recenterMap
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            if (recenterMap) recenterToCurrentLocation() else useCurrentLocation()
            pendingMapRecenter = false
        } else permissionLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
        )
    }

    // Live fixes move the marker immediately, but must not cancel a regional download or
    // destroy its GL session for every 250 m location update.
    LaunchedEffect(RadarLiveSessionPolicy.loadIdentity(place), reload, showLikelySnow) {
        val keepVisible = place != null && session?.let { RadarLiveSessionPolicy.canReuse(it, place) } == true
        if (!keepVisible) session = null
        refreshing = keepVisible
        error = null
        progress = 0 to 0
        if (place == null) {
            error = "Current location unavailable. Select a saved place or get a fresh device fix."
            Log.i("RainRadarScreen", "No resolved place; radar load deferred")
            return@LaunchedEffect
        }
        Log.i("RainRadarScreen", "Starting ${if (place.isCurrentLocation) "current" else "saved"} radar load")
        session = try {
            loader.load(place, onProgress = { completed, total -> progress = completed to total }).also {
                Log.i("RainRadarScreen", "Radar session ready provider=${it.providerSelection.active} frames=${it.timelineFrames.size}")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.e("RainRadarScreen", "Radar session load failed", failure)
            error = failure.message ?: "Radar session could not be loaded"
            session.takeIf { keepVisible }
        } finally {
            refreshing = false
        }
    }
    LaunchedEffect(place?.latitude, place?.longitude, session) {
        val active = session ?: return@LaunchedEffect
        val selected = place ?: return@LaunchedEffect
        if (active.place.id == selected.id && !RadarLiveSessionPolicy.canReuse(active, selected)) {
            Log.i("RainRadarScreen", "Current fix left cached coverage; replacing radar session")
            reload++
        }
    }
    LaunchedEffect(locationState) {
        if (locationState is LocationUiState.Active) {
            locationRequested = false
            locationMessage = null
        }
    }
    val regionLatitude = place?.latitude?.times(10)?.toInt()
    val regionLongitude = place?.longitude?.times(10)?.toInt()
    val daylight = currentWeather?.takeIf { it.daylightFreshAt(layerNow) }?.isDay
    val lightningEnabled = RadarMapLayer.LIGHTNING in enabledMapLayers
    LaunchedEffect(lightningEnabled, place?.id, regionLatitude, regionLongitude, layerRefresh) {
        if (!lightningEnabled) {
            lightningStatus = AncillaryStatus.Off
            return@LaunchedEffect
        }
        val selected = place
        val reason = RadarLayerGatePolicy.unavailableReason(
            RadarMapLayer.LIGHTNING, selected != null, daylight,
        )
        if (reason != null) {
            lightningStatus = AncillaryStatus.Unavailable(reason)
            return@LaunchedEffect
        }
        val force = layerRefresh != previousLightningRefresh
        previousLightningRefresh = layerRefresh
        lightningStatus = AncillaryStatus.Loading
        lightningStatus = try {
            AncillaryStatus.Satellite(EumetViewRepository.metadata(
                RadarMapLayer.LIGHTNING, requireNotNull(selected), force,
            ))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            Log.w("RainRadarLayers", "LIGHTNING layer unavailable", failure)
            AncillaryStatus.Unavailable("Lightning unavailable")
        }
    }
    val fogEnabled = RadarMapLayer.FOG in enabledMapLayers
    LaunchedEffect(fogEnabled, place?.id, regionLatitude, regionLongitude, layerRefresh, daylight) {
        if (!fogEnabled) {
            fogStatus = AncillaryStatus.Off
            return@LaunchedEffect
        }
        val selected = place
        val reason = RadarLayerGatePolicy.unavailableReason(RadarMapLayer.FOG, selected != null, daylight)
        if (reason != null) {
            fogStatus = AncillaryStatus.Unavailable(reason)
            return@LaunchedEffect
        }
        val force = layerRefresh != previousFogRefresh
        previousFogRefresh = layerRefresh
        fogStatus = AncillaryStatus.Loading
        fogStatus = try {
            AncillaryStatus.Satellite(EumetViewRepository.metadata(
                RadarMapLayer.FOG, requireNotNull(selected), force,
            ))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            Log.w("RainRadarLayers", "FOG layer unavailable", failure)
            AncillaryStatus.Unavailable("Fog unavailable")
        }
    }
    val windEnabled = RadarMapLayer.WIND in enabledMapLayers
    val viewportKey = windViewport?.requestKey()
    LaunchedEffect(windEnabled, viewportKey, layerRefresh) {
        if (!windEnabled) {
            windStatus = AncillaryStatus.Off
            return@LaunchedEffect
        }
        val viewport = windViewport
        if (viewport == null) {
            windStatus = AncillaryStatus.Unavailable("Wind viewport unavailable")
            return@LaunchedEffect
        }
        val force = layerRefresh != previousWindRefresh
        previousWindRefresh = layerRefresh
        windStatus = AncillaryStatus.Loading
        windStatus = try {
            AncillaryStatus.Wind(WeatherLayerRepository.wind(viewport, force = force))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            Log.w("RainRadarLayers", "Viewport wind unavailable", failure)
            AncillaryStatus.Unavailable("Wind model unavailable")
        }
    }
    val visibleWindStatus = when (val active = windStatus) {
        is AncillaryStatus.Wind -> if (active.grid.viewportKey == viewportKey &&
            active.grid.points.all { it.freshAt(layerNow) }) active
        else AncillaryStatus.Unavailable("Wind model stale · refresh")
        else -> active
    }
    val visibleLightningStatus = when (val active = lightningStatus) {
        is AncillaryStatus.Satellite -> if (active.metadata.freshAt(layerNow)) active
            else AncillaryStatus.Unavailable("Satellite image delayed · refresh")
        else -> active
    }
    val visibleFogStatus = when (val active = fogStatus) {
        is AncillaryStatus.Satellite -> when {
            !active.metadata.freshAt(layerNow) -> AncillaryStatus.Unavailable("Satellite image delayed · refresh")
            daylight != false -> AncillaryStatus.Unavailable(if (daylight == true)
                "Night only · fog / low cloud" else "Daylight status unavailable")
            else -> active
        }
        else -> active
    }
    val visibleAncillary = mapOf(
        RadarMapLayer.WIND to visibleWindStatus,
        RadarMapLayer.LIGHTNING to visibleLightningStatus,
        RadarMapLayer.FOG to visibleFogStatus,
    )
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val screenWidthDp = maxWidth.value.toInt()
    val screenHeightDp = maxHeight.value.toInt()
    val fontScale = LocalDensity.current.fontScale
    val compactTitle = NowHeaderLayoutPolicy.simplifyText(screenWidthDp, screenHeightDp, fontScale)
    Column(Modifier.fillMaxSize().padding(
        horizontal = NowHeaderLayoutPolicy.horizontalPaddingDp(screenWidthDp, screenHeightDp, fontScale).dp,
        vertical = NowHeaderLayoutPolicy.verticalPaddingDp.dp,
    )) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(NowHeaderLayoutPolicy.heightDp.dp),
            contentAlignment = Alignment.Center) {
            val canCenterSelected = RadarSelectedCenterPolicy.canCenter(selectedPlaceId, place)
            val maxTitleWidth = NowHeaderLayoutPolicy.titleMaxWidthDp(maxWidth.value).dp
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = recenterToSelectedPlace, enabled = canCenterSelected,
                    modifier = Modifier.size(NowHeaderLayoutPolicy.switchDp.dp)) {
                    Icon(Icons.Default.CenterFocusStrong,
                        contentDescription = if (canCenterSelected) "Center map on ${place!!.name}"
                            else "Center map unavailable until the selected location is resolved")
                }
                Spacer(Modifier.width(NowHeaderLayoutPolicy.gapDp.dp))
                Text(place?.name ?: "Current location",
                    fontSize = NowHeaderLayoutPolicy.titleFontSizeSp(compactTitle).sp,
                    lineHeight = NowHeaderLayoutPolicy.titleLineHeightSp(compactTitle).sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = maxTitleWidth).semantics { heading() })
                Spacer(Modifier.width(NowHeaderLayoutPolicy.gapDp.dp))
                PlaceSwitcher(places, locationState, selectPlace, { requestCurrentLocation(false) })
            }
        }
        if (locationMessage != null || (locationRequested && locationState is LocationUiState.Unavailable)) {
            Text(locationMessage ?: (locationState as LocationUiState.Unavailable).message,
                color = Danger, fontSize = 12.sp)
        }
        Spacer(Modifier.height(6.dp))
        when {
            session != null && place != null && RadarLiveSessionPolicy.canReuse(session!!, place) -> RadarPlayer(
                session = session!!,
                mapPlace = place,
                markerPlace = RadarLiveMapPolicy.marker(place, liveMapPlace),
                hasFreshLiveFix = RadarLiveMapPolicy.canFollow(selectedPlaceId, liveMapPlace),
                followLive = followLive,
                onFollowLiveChange = { followLive = it },
                selectedPlaceId = selectedPlaceId,
                chartTimeRequest = chartTimeRequest,
                onChartTimeConsumed = onChartTimeConsumed,
                playbackSpeed = playbackSpeed,
                mapStyle = mapStyle,
                onLongPress = { longPressed = it },
                locationState = locationState,
                currentRecenterTick = currentRecenterTick,
                onCurrentLocation = { requestCurrentLocation(true) },
                cameraMemory = cameraMemory,
                enabledMapLayers = enabledMapLayers,
                windArrowScale = windArrowScale,
                setMapLayerEnabled = setMapLayerEnabled,
                ancillaryStatuses = visibleAncillary,
                currentWeather = currentWeather,
                onWindViewportChanged = { windViewport = it },
                onRefresh = { reload++; layerRefresh++; refreshPointWeather() },
                onLayerError = { layer, message ->
                    when (layer) {
                        RadarMapLayer.LIGHTNING -> lightningStatus = AncillaryStatus.Unavailable(message)
                        RadarMapLayer.FOG -> fogStatus = AncillaryStatus.Unavailable(message)
                        else -> Unit
                    }
                },
                refreshing = refreshing,
                refreshProgress = progress,
                refreshError = error,
            )
            error != null -> Card(
                colors = CardDefaults.cardColors(containerColor = Surface),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text("Radar unavailable", color = Danger, fontWeight = FontWeight.Bold)
                    Text(error!!, color = Secondary, modifier = Modifier.padding(vertical = 10.dp))
                    Button(onClick = {
                        if (CurrentLocationSelectionPolicy.needsFixRetry(selectedPlaceId, place != null))
                            requestCurrentLocation(false)
                        else reload++
                    }) { Text("Try again") }
                }
            }
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Accent)
                    if (progress.second > 0) {
                        Text(
                            "Loading radar ${progress.first}/${progress.second}",
                            color = Secondary,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            }
        }
    }
    }

    longPressed?.let { point ->
        var name by remember(point) {
            mutableStateOf(String.format(Locale.ROOT, "%.4f, %.4f", point.latitude, point.longitude))
        }
        AlertDialog(
            onDismissRequest = { longPressed = null },
            title = { Text("Save this place") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Place name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        saveAndSelect(SavedPlace(name.trim(), point.latitude, point.longitude))
                        longPressed = null
                    },
                ) { Text("Save & select") }
            },
            dismissButton = {
                TextButton(onClick = { longPressed = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ColumnScope.RadarPlayer(
    session: RadarSession,
    mapPlace: SavedPlace,
    markerPlace: SavedPlace,
    hasFreshLiveFix: Boolean,
    followLive: Boolean,
    onFollowLiveChange: (Boolean) -> Unit,
    selectedPlaceId: String,
    chartTimeRequest: RadarChartTimeRequest?,
    onChartTimeConsumed: (Int) -> Unit,
    playbackSpeed: RadarPlaybackSpeed,
    mapStyle: RadarMapStyle,
    onLongPress: (GeoPoint) -> Unit,
    locationState: LocationUiState,
    currentRecenterTick: Int,
    onCurrentLocation: () -> Unit,
    cameraMemory: RadarCameraMemory,
    enabledMapLayers: Set<RadarMapLayer>,
    windArrowScale: Float,
    setMapLayerEnabled: (RadarMapLayer, Boolean) -> Unit,
    ancillaryStatuses: Map<RadarMapLayer, AncillaryStatus>,
    currentWeather: CurrentWeather?,
    onWindViewportChanged: (WindViewport) -> Unit,
    onRefresh: () -> Unit,
    onLayerError: (RadarMapLayer, String) -> Unit,
    refreshing: Boolean,
    refreshProgress: Pair<Int, Int>,
    refreshError: String?,
) {
    val darkMap = mapStyle.darkControls
    val secondaryColor = Secondary
    val times = remember(session) { session.timelineFrames.map { it.time } }
    val forecastFlags = remember(session) { session.timelineFrames.map { it.forecast } }
    val latestObservationIndex = forecastFlags.indexOfLast { !it }.coerceAtLeast(0)
    val latestOffset = (times[latestObservationIndex] - times.first()).toFloat()
    val providerForecast = forecastFlags.any { it }
    val preferredOpenTier = if (session.detail != null) {
        com.rainalarm.app.domain.RadarResolutionTier.DETAIL
    } else {
        com.rainalarm.app.domain.RadarResolutionTier.REGIONAL
    }
    val estimatedForecast = !providerForecast &&
        session.velocity(preferredOpenTier)?.futureField != null
    val forecastAvailable = providerForecast || estimatedForecast
    val endOffset = if (providerForecast) {
        (times.last() - times.first()).toFloat()
    } else {
        (times.last() - times.first() + if (estimatedForecast) RadarTimeline.FORECAST_HORIZON_SECONDS else 0L).toFloat()
    }
    val initialCursor = remember(session) {
        RadarEntryClock.initialCursor(
            times.first(), times[latestObservationIndex], times.first() + endOffset.toLong(),
            forecastAvailable, Instant.now().epochSecond,
        )
    }
    val chartDecision = chartTimeRequest?.let {
        RadarChartTimeLink.decide(it, selectedPlaceId, session.place.id, times.first(),
            times.first() + endOffset.toDouble())
    }
    var cursor by remember(session) { mutableFloatStateOf(
        (chartDecision as? RadarChartTimeDecision.Apply)?.cursorSeconds ?: initialCursor,
    ) }
    var playing by remember(session) { mutableStateOf(false) }
    var chartTimeMessage by remember(session) { mutableStateOf<String?>(null) }
    LaunchedEffect(session, chartTimeRequest?.token, chartDecision) {
        val request = chartTimeRequest ?: return@LaunchedEffect
        when (val decision = chartDecision) {
            is RadarChartTimeDecision.Apply -> {
                cursor = decision.cursorSeconds
                playing = false
                chartTimeMessage = null
                onChartTimeConsumed(request.token)
            }
            RadarChartTimeDecision.Unavailable -> {
                chartTimeMessage = "Chart time unavailable in this radar session; showing normal entry time."
                onChartTimeConsumed(request.token)
            }
            else -> Unit
        }
    }
    var rendererStatus by remember(session) { mutableStateOf<RadarRendererStatus>(RadarRendererStatus.Loading) }
    var mapStyleError by remember(session) { mutableStateOf<String?>(null) }
    val safeCursor = cursor.takeIf { it.isFinite() }?.coerceIn(0f, endOffset) ?: initialCursor
    val bracket = if (providerForecast) {
        RadarTimeline.bracket(times, forecastFlags, times.first() + safeCursor.toDouble())
    } else {
        RadarTimeline.bracket(times, times.first() + safeCursor.toDouble())
    }

    LaunchedEffect(playing, session, playbackSpeed) {
        if (!playing) return@LaunchedEffect
        var lastNanos = withFrameNanos { it }
        while (playing) {
            val nanos = withFrameNanos { it }
            val elapsedSeconds = (nanos - lastNanos) / 1_000_000_000.0
            lastNanos = nanos
            val stopAt = if (forecastAvailable) endOffset else latestOffset
            val currentCursor = cursor.takeIf { it.isFinite() }?.coerceIn(0f, stopAt) ?: latestOffset
            cursor = RadarPlaybackClock.advance(currentCursor, elapsedSeconds, stopAt, playbackSpeed.multiplier)
        }
    }
    val label = timelineLabel(times, bracket, safeCursor, latestOffset, forecastAvailable)
    Card(
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth().weight(1f),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val mapWidthDp = maxWidth.value.toInt()
            RadarImageMap(
                session,
                bracket,
                mapPlace,
                markerPlace = markerPlace,
                followLive = RadarLiveMapPolicy.shouldCenterOnFix(
                    selectedPlaceId, if (hasFreshLiveFix) markerPlace else null, followLive),
                onManualCameraGesture = { if (followLive) onFollowLiveChange(false) },
                onLongPress = onLongPress,
                recenterSignal = currentRecenterTick,
                cameraMemory = cameraMemory,
                isPlaying = playing,
                onRendererStatus = { rendererStatus = it },
                mapStyle = mapStyle,
                windGrid = (ancillaryStatuses[RadarMapLayer.WIND] as? AncillaryStatus.Wind)?.grid,
                windArrowScale = windArrowScale,
                satelliteLayers = listOfNotNull(
                    (ancillaryStatuses[RadarMapLayer.FOG] as? AncillaryStatus.Satellite)?.metadata,
                    (ancillaryStatuses[RadarMapLayer.LIGHTNING] as? AncillaryStatus.Satellite)?.metadata,
                ),
                onLayerError = onLayerError,
                onMapStyleError = { mapStyleError = it },
                onWindViewportChanged = onWindViewportChanged,
            )
            mapStyleError?.let { message ->
                Text(message,
                    color = LocalRainAlarmPalette.current.mapLabelText,
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.Center)
                        .background(LocalRainAlarmPalette.current.mapLabelSurface, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp))
            }
            if (rendererStatus is RadarRendererStatus.Error) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = LocalRainAlarmPalette.current.mapLabelSurface),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.align(Alignment.Center).padding(20.dp),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Radar display unavailable", color = Danger, fontWeight = FontWeight.Bold)
                        Text(
                            (rendererStatus as RadarRendererStatus.Error).message,
                            color = Secondary,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
            if (rendererStatus is RadarRendererStatus.Compatibility) {
                Text(
                    (rendererStatus as RadarRendererStatus.Compatibility).message,
                    color = Accent,
                    fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.TopCenter).padding(horizontal = 54.dp, vertical = 42.dp),
                )
            }
            Text(
                label,
                color = LocalRainAlarmPalette.current.mapLabelText,
                fontWeight = FontWeight.Bold,
                fontSize = RadarTopControlsPolicy.timeLabelSp(mapWidthDp).sp,
                lineHeight = (RadarTopControlsPolicy.timeLabelSp(mapWidthDp) + 2).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.TopStart)
                    .padding(start = 12.dp, top = RadarTopControlsPolicy.timeTopDp.dp)
                    .widthIn(max = RadarTopControlsPolicy.timeLabelMaxWidthDp(
                        mapWidthDp, selectedPlaceId == CURRENT_LOCATION_ID).dp)
                    .background(LocalRainAlarmPalette.current.mapLabelSurface, RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp),
            )
            val refreshOverlay = RadarRefreshOverlayPolicy.status(
                hasSession = true, refreshing = refreshing,
                completed = refreshProgress.first, total = refreshProgress.second,
                error = refreshError,
            )
            Row(Modifier.align(Alignment.TopEnd).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (selectedPlaceId == CURRENT_LOCATION_ID) {
                    IconButton(onClick = { onFollowLiveChange(!followLive) },
                        enabled = hasFreshLiveFix,
                        modifier = Modifier.size(RadarTopControlsPolicy.controlSizeDp.dp)) {
                        Icon(Icons.Default.Navigation,
                            contentDescription = if (followLive) "Stop following live location" else "Follow live location",
                            tint = if (followLive) Accent.copy(alpha = 0.85f)
                                else if (darkMap) Color.White else Color.Black)
                    }
                }
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh radar and map layer",
                        tint = if (darkMap) Color.White else Color.Black)
                }
                IconButton(onClick = onCurrentLocation,
                    enabled = locationState !is LocationUiState.Locating) {
                    if (locationState is LocationUiState.Locating) CircularProgressIndicator(Modifier.size(22.dp),
                        color = if (darkMap) Color.White else Color.Black, strokeWidth = 2.dp)
                    else Icon(Icons.Default.MyLocation, contentDescription = "Use current device location",
                        tint = if (darkMap) Color.White else Color.Black)
                }
            }
            RadarLayerSegments(enabledMapLayers, darkMap, setMapLayerEnabled,
                Modifier.align(Alignment.TopEnd).padding(
                    top = RadarTopControlsPolicy.segmentTopDp.dp,
                    end = RadarTopControlsPolicy.controlsEndDp.dp,
                ))
            RadarLayerStatuses(enabledMapLayers, ancillaryStatuses, currentWeather, mapWidthDp,
                Modifier.align(Alignment.TopEnd).padding(
                    top = RadarTopControlsPolicy.statusTopDp.dp,
                    end = RadarTopControlsPolicy.statusEndDp.dp))
            refreshOverlay?.let { status ->
                Text(status.label,
                    color = LocalRainAlarmPalette.current.mapLabelText,
                    fontSize = 11.sp, lineHeight = 13.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 32.dp)
                        .widthIn(max = (mapWidthDp - 80).coerceIn(100, 208).dp)
                        .background(LocalRainAlarmPalette.current.mapLabelSurface, RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                        .semantics { contentDescription = status.accessibilityLabel })
            }
        }
    }
    session.providerSelection.fallbackMessage?.let { message ->
        Text(message, color = Danger, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
    }
    chartTimeMessage?.let { message ->
        Text(message, color = Danger, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { playing = !playing }, modifier = Modifier.size(48.dp)) {
            Icon(
                if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (playing) "Pause radar timeline" else "Play radar timeline",
            )
        }
        Slider(
            value = safeCursor,
            onValueChange = {
                cursor = it.takeIf { value -> value.isFinite() }
                    ?.coerceIn(0f, endOffset) ?: safeCursor
                playing = false
            },
            valueRange = 0f..endOffset,
            modifier = Modifier.weight(1f).semantics {
                contentDescription = "Radar timeline"
                stateDescription = label
            },
        )
    }
    if (!forecastAvailable) Text("Prediction unavailable for this radar session", color = Danger, fontSize = 11.sp)
    if (endOffset > 0f) {
        val ticks = remember(session, endOffset) {
            RadarTimelineTicks.between(times.first(), times.first() + endOffset.toLong(), ZoneId.systemDefault())
        }
        // Match the 48dp play target, 6dp row gap and Slider's 10dp thumb inset.
        BoxWithConstraints(Modifier.fillMaxWidth().height(21.dp).padding(start = 54.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                ticks.forEach { tick ->
                    val x = 10.dp.toPx() + (size.width - 20.dp.toPx()) * tick.fraction
                    drawLine(secondaryColor, Offset(x, 0f), Offset(x, 4.dp.toPx()), 1.dp.toPx())
                }
            }
            ticks.forEach { tick ->
                val x = (10.dp + (maxWidth - 20.dp) * tick.fraction - 19.dp)
                    .coerceIn(0.dp, (maxWidth - 38.dp).coerceAtLeast(0.dp))
                Text(tick.label, color = Secondary, fontSize = 9.sp,
                    modifier = Modifier.offset(x = x, y = 4.dp).width(38.dp), maxLines = 1)
            }
        }
    }
}

private fun timelineLabel(
    times: List<Long>,
    bracket: RadarTimelineBracket,
    cursor: Float,
    latestOffset: Float,
    forecastAvailable: Boolean,
): String {
    val instant = Instant.ofEpochSecond((times.first() + cursor).toLong())
    val time = DateTimeFormatter.ofPattern("HH:mm").format(instant.atZone(ZoneId.systemDefault()))
    return when {
        cursor == latestOffset -> "Latest radar · ${time}"
        bracket.isForecast && forecastAvailable ->
            if (
                cursor <= (times.last() - times.first()).toFloat() &&
                latestOffset < (times.last() - times.first()).toFloat()
            ) {
                "Forecast · ${time}"
            } else {
                "Estimate · ${time}"
            }
        bracket.isForecast ->
            "Forecast unavailable · ${time}"
        else -> "Observed · ${time}"
    }
}
