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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rainalarm.app.LocationUiState
import com.rainalarm.app.FollowUiCapability
import com.rainalarm.app.PointRefreshCompletion
import com.rainalarm.app.ProviderMapNoticeEvent
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
import com.rainalarm.app.data.WindGridFailureDiagnostics
import com.rainalarm.app.data.WindViewport
import com.rainalarm.app.data.WindTimelinePolicy
import com.rainalarm.app.data.EumetLayerMetadata
import com.rainalarm.app.data.EumetProduct
import com.rainalarm.app.data.CloudProductSelectionPolicy
import com.rainalarm.app.data.EumetViewRepository
import com.rainalarm.app.data.WeatherLayerRepository
import com.rainalarm.app.data.FollowRefreshCoordinator
import com.rainalarm.app.data.FollowRefreshStream
import com.rainalarm.app.data.FollowRefreshTicket
import com.rainalarm.app.data.ProviderCadencePolicy
import com.rainalarm.app.data.ProviderPublicationClock
import com.rainalarm.app.data.RadarProviderKind
import com.rainalarm.app.data.RadarProviderSelection
import com.rainalarm.app.data.forecastSelectionKey
import com.rainalarm.app.domain.RadarResolutionTier
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import androidx.compose.runtime.rememberUpdatedState

private val Surface: Color @Composable get() = LocalRainAlarmPalette.current.surface
private val Secondary: Color @Composable get() = LocalRainAlarmPalette.current.muted
private val Accent: Color @Composable get() = LocalRainAlarmPalette.current.accent

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

internal object SatellitePreparationLabelPolicy {
    /** Active preparation belongs exclusively to the bottom-right progress stack. */
    fun activeLabel(layer: RadarMapLayer, status: SatellitePreparationStatus): String? = when (status) {
        is SatellitePreparationStatus.Preparing -> WeatherDataStatusPolicy.loading(
            layer.weatherDataKind(), status.ready, status.total,
        )
        SatellitePreparationStatus.Rendering ->
            WeatherDataStatusPolicy.loading(layer.weatherDataKind())
        SatellitePreparationStatus.Ready, is SatellitePreparationStatus.Failed -> null
    }

    private fun RadarMapLayer.weatherDataKind(): WeatherDataKind = when (this) {
        RadarMapLayer.FOG -> WeatherDataKind.CLOUDS
        RadarMapLayer.LIGHTNING -> WeatherDataKind.LIGHTNING
        else -> error("Only satellite layers have preparation status")
    }
}

internal object RadarPreparationStackPolicy {
    const val maximumEntries = 5
    const val endInsetDp = 8
    const val bottomInsetDp = 40
    const val rowSpacingDp = 2
    const val rowLineHeightSp = 13
    const val minimumTopClearanceDp = RadarTopControlsPolicy.statusTopDp + 20

    fun entries(
        radar: RadarRefreshOverlay?,
        enabledLayers: Set<RadarMapLayer>,
        statuses: Map<RadarMapLayer, AncillaryStatus>,
        preparation: Map<RadarMapLayer, SatellitePreparationStatus>,
        location: RadarRefreshOverlay? = null,
    ): List<RadarRefreshOverlay> = buildList {
        location?.let(::add)
        radar?.let(::add)
        if (RadarMapLayer.WIND in enabledLayers) {
            val label = when (statuses[RadarMapLayer.WIND]) {
                AncillaryStatus.Loading, AncillaryStatus.Off, null ->
                    WeatherDataStatusPolicy.loading(WeatherDataKind.WIND)
                is AncillaryStatus.Unavailable ->
                    WeatherDataStatusPolicy.unavailable(WeatherDataKind.WIND)
                is AncillaryStatus.Wind -> null
                else -> WeatherDataStatusPolicy.unavailable(WeatherDataKind.WIND)
            }
            label?.let { add(RadarRefreshOverlay(it, it)) }
        }
        SatelliteLayerRenderPolicy.renderOrder.forEach { layer ->
            if (layer !in enabledLayers) return@forEach
            val label = layerLabel(layer, statuses[layer], preparation[layer])
            label?.let {
                add(RadarRefreshOverlay(it, it))
            }
        }
    }.distinctBy(RadarRefreshOverlay::label)

    fun layerLabel(
        layer: RadarMapLayer,
        ancillary: AncillaryStatus?,
        preparation: SatellitePreparationStatus?,
    ): String? = when {
        ancillary == AncillaryStatus.Loading || ancillary == AncillaryStatus.Off ->
            WeatherDataStatusPolicy.loading(layer.weatherDataKind())
        preparation is SatellitePreparationStatus.Preparing ||
            preparation == SatellitePreparationStatus.Rendering ->
            SatellitePreparationLabelPolicy.activeLabel(layer, preparation)
        preparation is SatellitePreparationStatus.Failed ->
            WeatherDataStatusPolicy.unavailable(layer.weatherDataKind())
        ancillary is AncillaryStatus.Unavailable ->
            WeatherDataStatusPolicy.unavailable(layer.weatherDataKind())
        preparation == SatellitePreparationStatus.Ready -> null
        ancillary == null -> WeatherDataStatusPolicy.loading(layer.weatherDataKind())
        ancillary is AncillaryStatus.Satellite -> WeatherDataStatusPolicy.loading(layer.weatherDataKind())
        else -> null
    }

    private fun RadarMapLayer.weatherDataKind(): WeatherDataKind = when (this) {
        RadarMapLayer.FOG -> WeatherDataKind.CLOUDS
        RadarMapLayer.LIGHTNING -> WeatherDataKind.LIGHTNING
        else -> error("Only satellite layers are handled here")
    }

    fun maxWidthDp(mapWidthDp: Int): Int =
        (mapWidthDp - endInsetDp * 2).coerceAtLeast(1).coerceAtMost(208)

    /** Used by tests/layout policy to keep concurrent rows above the lower notice/attribution rail. */
    fun estimatedHeightDp(entryCount: Int): Int {
        val count = entryCount.coerceIn(0, maximumEntries)
        return count * 17 + (count - 1).coerceAtLeast(0) * rowSpacingDp
    }

    fun topDp(mapHeightDp: Int, entryCount: Int): Int =
        (mapHeightDp - bottomInsetDp - estimatedHeightDp(entryCount)).coerceAtLeast(0)

    fun minimumSafeMapHeightDp(entryCount: Int): Int =
        minimumTopClearanceDp + bottomInsetDp + estimatedHeightDp(entryCount)
}

internal object RadarLayerGatePolicy {
    fun unavailableReason(hasPlace: Boolean): String? =
        if (hasPlace) null else "Select a place for this layer"
}

/** Keeps verified cloud imagery visible while its day/night replacement is checked. */
internal object CloudsSwapPolicy {
    fun placeKey(place: SavedPlace): String =
        "${place.id}:${place.latitude.toBits()}:${place.longitude.toBits()}"

    fun canRetain(
        metadata: EumetLayerMetadata,
        verifiedPlaceKey: String?,
        place: SavedPlace,
        nowEpochSeconds: Long,
    ): Boolean = verifiedPlaceKey == placeKey(place) && metadata.covers(place) &&
        metadata.freshAt(nowEpochSeconds)

    fun canRetainDuringReplacement(
        metadata: EumetLayerMetadata,
        verifiedPlaceKey: String?,
        place: SavedPlace,
    ): Boolean = verifiedPlaceKey == placeKey(place) && metadata.covers(place)
}

@Composable
private fun RadarLayerStatus(
    mapLayer: RadarMapLayer,
    weather: CurrentWeather?,
    mapWidthDp: Int,
    modifier: Modifier = Modifier,
) {
    if (mapLayer != RadarMapLayer.WIND) return
    val (status, accessibilityStatus) = when (mapLayer) {
        RadarMapLayer.OFF -> return
        RadarMapLayer.WIND -> {
            val wind = weather?.takeIf { it.freshAt(Instant.now().epochSecond) }
            val speed = wind?.windSpeedKmh
            val from = wind?.windFromDegrees
            if (speed == null || from == null) return
            "${speed.toInt()} km/h ${cardinalDirection(from)}" to
                "Selected-place wind from ${cardinalDirection(from)} at ${speed.toInt()} kilometres per hour"
        }
        RadarMapLayer.LIGHTNING, RadarMapLayer.FOG -> return
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
    weather: CurrentWeather?,
    mapWidthDp: Int,
    modifier: Modifier = Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.End) {
        if (RadarMapLayer.WIND in enabledMapLayers) {
            RadarLayerStatus(RadarMapLayer.WIND, weather, mapWidthDp)
        }
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

internal sealed interface AncillaryStatus {
    data object Off : AncillaryStatus
    data object Loading : AncillaryStatus
    data class Wind(val grid: WindGrid) : AncillaryStatus
    data class Satellite(
        val metadata: EumetLayerMetadata,
        val catalog: List<EumetLayerMetadata> = listOf(metadata),
    ) : AncillaryStatus
    data class Unavailable(val message: String) : AncillaryStatus
}

private data class PendingFollowRefresh(
    val ticket: FollowRefreshTicket,
    val requestGeneration: Int,
)

private fun WindGridAcquisitionState.windDiagnosticState(): String = when (this) {
    is WindGridAcquisitionState.Disabled -> "disabled"
    is WindGridAcquisitionState.AwaitingViewport -> "awaiting_viewport"
    is WindGridAcquisitionState.Loading -> "loading"
    is WindGridAcquisitionState.DataReadyPendingRender -> "data_ready_pending_render"
    is WindGridAcquisitionState.Ready -> "ready"
    is WindGridAcquisitionState.Unavailable -> "unavailable"
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
    appForeground: Boolean,
    followLive: Boolean,
    followCapability: FollowUiCapability,
    liveFixElapsedRealtimeNanos: Long,
    onFollowLiveChange: (Boolean) -> Boolean,
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
    refreshPointWeather: () -> Long,
    refreshForecastForFollow: () -> Unit,
    pointRefreshCompletion: PointRefreshCompletion,
    selectedPlaceId: String,
    chartTimeRequest: RadarChartTimeRequest? = null,
    onChartTimeConsumed: (Int) -> Unit = {},
    showLikelySnow: Boolean = false,
    providerNoticeEvent: ProviderMapNoticeEvent? = null,
    onProviderNoticeConsumed: (Long) -> Unit = {},
    onProviderSelection: (SavedPlace, RadarProviderSelection) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val loader = remember { RadarProviderCoordinator(RadarSettingsRepository(context)) }
    val windEnabled = RadarMapLayer.WIND in enabledMapLayers
    var session by remember { mutableStateOf<RadarSession?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0 to 0) }
    var reload by remember { mutableIntStateOf(0) }
    var lightningRefresh by remember { mutableIntStateOf(0) }
    var cloudsRefresh by remember { mutableIntStateOf(0) }
    var completedRadarReload by remember { mutableIntStateOf(0) }
    var completedLightningRefresh by remember { mutableIntStateOf(0) }
    var completedCloudsRefresh by remember { mutableIntStateOf(0) }
    var completedWindRefresh by remember { mutableIntStateOf(0) }
    var previousLightningRefresh by remember { mutableIntStateOf(0) }
    var previousCloudsRefresh by remember { mutableIntStateOf(0) }
    var windAcquisition by remember {
        mutableStateOf<WindGridAcquisitionState>(
            if (windEnabled) WindGridAcquisitionState.AwaitingViewport(1, force = false)
            else WindGridAcquisitionState.Disabled(),
        )
    }
    var lightningStatus by remember { mutableStateOf<AncillaryStatus>(AncillaryStatus.Off) }
    var cloudsStatus by remember { mutableStateOf<AncillaryStatus>(AncillaryStatus.Off) }
    var lightningReplacementPhase by remember { mutableStateOf(WeatherReplacementPhase.IDLE) }
    var cloudsReplacementPhase by remember { mutableStateOf(WeatherReplacementPhase.IDLE) }
    var lastLightningStaleIdentity by remember { mutableStateOf<String?>(null) }
    var lastCloudsStaleIdentity by remember { mutableStateOf<String?>(null) }
    var lightningVerifiedPlaceKey by remember { mutableStateOf<String?>(null) }
    var cloudsVerifiedPlaceKey by remember { mutableStateOf<String?>(null) }
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
    var pendingMapRecenter by remember { mutableStateOf(false) }
    var locationRequestPending by remember { mutableStateOf(false) }
    LaunchedEffect(selectedPlaceId, liveMapPlace) {
        if (followLive && !RadarLiveMapPolicy.canFollow(selectedPlaceId, liveMapPlace))
            onFollowLiveChange(false)
    }
    val radarView = LocalView.current
    val keepScreenOn = appForeground && selectedPlaceId == CURRENT_LOCATION_ID && followLive
    DisposableEffect(radarView, keepScreenOn) {
        radarView.keepScreenOn = keepScreenOn
        onDispose { if (keepScreenOn) radarView.keepScreenOn = false }
    }
    DisposableEffect(Unit) {
        onDispose { onFollowLiveChange(false) }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.any { it }) {
            locationMessage = null
            if (pendingMapRecenter) recenterToCurrentLocation() else useCurrentLocation()
        } else {
            locationMessage = "Location permission was not granted. Selection unchanged."
            locationRequestPending = false
        }
        pendingMapRecenter = false
    }
    val requestCurrentLocation: (Boolean) -> Unit = { recenterMap ->
        locationMessage = null
        locationRequestPending = true
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
        val requestReload = reload
        val keepVisible = when {
            place != null -> session?.let { RadarLiveSessionPolicy.canReuse(it, place) } == true
            else -> CurrentLocationPresentationPolicy.retainCurrentSession(
                currentSelected = selectedPlaceId == CURRENT_LOCATION_ID,
                hasResolvedPlace = false,
                sessionBelongsToCurrent = session?.place?.isCurrentLocation == true,
            )
        }
        if (!keepVisible) session = null
        refreshing = keepVisible && place != null
        error = null
        progress = 0 to 0
        if (place == null) {
            Log.i("RainRadarScreen", "No resolved place; radar load deferred")
            completedRadarReload = requestReload
            return@LaunchedEffect
        }
        Log.i("RainRadarScreen", "Starting ${if (place.isCurrentLocation) "current" else "saved"} radar load")
        var candidate: RadarSession? = null
        try {
            val loaded = loader.load(place, onProgress = { completed, total -> progress = completed to total }).also {
                Log.i("RainRadarScreen", "Radar session ready provider=${it.providerSelection.active} frames=${it.timelineFrames.size}")
            }
            candidate = loaded
            currentCoroutineContext().ensureActive()
            if (requestReload != reload) {
                loaded.release()
                candidate = null
                return@LaunchedEffect
            }
            onProviderSelection(place, loaded.providerSelection)
            session = loaded
            candidate = null
        } catch (cancelled: CancellationException) {
            candidate?.release()
            throw cancelled
        } catch (failure: Exception) {
            candidate?.release()
            Log.e("RainRadarScreen", "Radar session load failed", failure)
            error = failure.message ?: "Radar session could not be loaded"
            if (!keepVisible) session = null
        } finally {
            completedRadarReload = requestReload
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
        when (locationState) {
            is LocationUiState.Active -> {
                locationRequestPending = false
                locationMessage = locationState.message
            }
            is LocationUiState.Unavailable -> {
                locationRequestPending = false
                locationMessage = locationState.message
            }
            LocationUiState.Idle, LocationUiState.Locating -> Unit
        }
    }
    LaunchedEffect(selectedPlaceId) {
        if (selectedPlaceId != CURRENT_LOCATION_ID) {
            locationRequestPending = false
            locationMessage = null
        }
    }
    val regionLatitude = place?.latitude?.times(10)?.toInt()
    val regionLongitude = place?.longitude?.times(10)?.toInt()
    val lightningEnabled = RadarMapLayer.LIGHTNING in enabledMapLayers
    LaunchedEffect(lightningEnabled, place?.id, regionLatitude, regionLongitude, lightningRefresh) {
        val requestRefresh = lightningRefresh
        if (!lightningEnabled) {
            lightningStatus = AncillaryStatus.Off
            lightningReplacementPhase = WeatherReplacementPhase.IDLE
            lightningVerifiedPlaceKey = null
            lastLightningStaleIdentity = null
            return@LaunchedEffect
        }
        val selected = place
        val reason = RadarLayerGatePolicy.unavailableReason(selected != null)
        if (reason != null) {
            lightningStatus = AncillaryStatus.Unavailable(reason)
            lightningReplacementPhase = WeatherReplacementPhase.UNAVAILABLE
            lightningVerifiedPlaceKey = null
            return@LaunchedEffect
        }
        val resolvedPlace = requireNotNull(selected)
        val retained = (lightningStatus as? AncillaryStatus.Satellite)?.takeIf {
            CloudsSwapPolicy.canRetainDuringReplacement(
                it.metadata, lightningVerifiedPlaceKey, resolvedPlace,
            )
        }
        val force = lightningRefresh != previousLightningRefresh
        previousLightningRefresh = lightningRefresh
        lightningReplacementPhase = WeatherReplacementPhase.LOADING
        if (retained == null) lightningStatus = AncillaryStatus.Loading
        try {
            lightningStatus = AncillaryStatus.Satellite(EumetViewRepository.metadata(
                RadarMapLayer.LIGHTNING, resolvedPlace, force,
            )).also {
                lightningVerifiedPlaceKey = CloudsSwapPolicy.placeKey(resolvedPlace)
            }
            lightningReplacementPhase = WeatherReplacementPhase.IDLE
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            Log.w("RainRadarLayers", "LIGHTNING layer unavailable", failure)
            lightningStatus = retained ?: AncillaryStatus.Unavailable("Lightning unavailable")
            lightningReplacementPhase = WeatherReplacementPhase.UNAVAILABLE
        } finally { completedLightningRefresh = requestRefresh }
    }
    val cloudsEnabled = RadarMapLayer.FOG in enabledMapLayers
    val preferredCloudProduct = place?.let {
        CloudProductSelectionPolicy.preferred(currentWeather, it, layerNow)
    }
    LaunchedEffect(
        cloudsEnabled, place?.id, regionLatitude, regionLongitude, cloudsRefresh,
        preferredCloudProduct,
    ) {
        val requestRefresh = cloudsRefresh
        if (!cloudsEnabled) {
            cloudsStatus = AncillaryStatus.Off
            cloudsReplacementPhase = WeatherReplacementPhase.IDLE
            cloudsVerifiedPlaceKey = null
            lastCloudsStaleIdentity = null
            return@LaunchedEffect
        }
        val selected = place
        val reason = RadarLayerGatePolicy.unavailableReason(selected != null)
        if (reason != null) {
            cloudsStatus = AncillaryStatus.Unavailable(reason)
            cloudsReplacementPhase = WeatherReplacementPhase.UNAVAILABLE
            cloudsVerifiedPlaceKey = null
            return@LaunchedEffect
        }
        val resolvedPlace = requireNotNull(selected)
        val retained = (cloudsStatus as? AncillaryStatus.Satellite)?.takeIf {
            CloudsSwapPolicy.canRetainDuringReplacement(
                it.metadata, cloudsVerifiedPlaceKey, resolvedPlace,
            )
        }
        val force = cloudsRefresh != previousCloudsRefresh
        previousCloudsRefresh = cloudsRefresh
        cloudsReplacementPhase = WeatherReplacementPhase.LOADING
        if (retained == null) cloudsStatus = AncillaryStatus.Loading
        try {
            val products = EumetViewRepository.cloudProducts(
                requireNotNull(preferredCloudProduct), resolvedPlace, force,
            )
            val primary = products.firstOrNull { it.product == preferredCloudProduct }
                ?: products.first()
            cloudsStatus = AncillaryStatus.Satellite(primary, products).also {
                cloudsVerifiedPlaceKey = CloudsSwapPolicy.placeKey(resolvedPlace)
            }
            cloudsReplacementPhase = WeatherReplacementPhase.IDLE
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            Log.w("RainRadarLayers", "Clouds layer unavailable", failure)
            cloudsStatus = retained ?: AncillaryStatus.Unavailable("Clouds unavailable")
            cloudsReplacementPhase = WeatherReplacementPhase.UNAVAILABLE
        } finally { completedCloudsRefresh = requestRefresh }
    }
    val viewportKey = windViewport?.requestKey()
    val windTimelineWindow = WindTimelinePolicy.requestWindow(
        session?.timelineFrames?.firstOrNull()?.time ?: layerNow - 3 * 3_600L,
        session?.timelineFrames?.lastOrNull()?.time?.plus(3_600L) ?: layerNow + 3_600L,
    )
    LaunchedEffect(windEnabled, viewportKey, windTimelineWindow.cacheKey) {
        val before = windAcquisition
        val after = WindGridAcquisitionReducer.reduce(
            before,
            WindGridAcquisitionEvent.Environment(
                enabled = windEnabled,
                viewportKey = viewportKey,
                timelineKey = windTimelineWindow.cacheKey,
                nowElapsedMillis = WindGridAcquisitionDeadlinePolicy.monotonicNowMillis(),
                nowEpochSeconds = Instant.now().epochSecond,
            ),
        )
        if (after != before) Log.d(
            "RainRadarWind",
            "environment generation=${after.generation} viewportKey=${viewportKey ?: "pending"} " +
                "state=${after.windDiagnosticState()}",
        )
        windAcquisition = after
    }
    val windLoading = windAcquisition as? WindGridAcquisitionState.Loading
    val windLoadingRequest = windLoading?.request
    LaunchedEffect(windLoadingRequest) {
        val loading = windLoading ?: return@LaunchedEffect
        val viewport = windViewport?.takeIf {
            it.requestKey() == loading.request.viewportKey
        } ?: return@LaunchedEffect
        try {
            val loaded = WeatherLayerRepository.wind(
                viewport, windTimelineWindow, force = loading.force,
            )
            val before = windAcquisition
            val after = WindGridAcquisitionReducer.reduce(
                before,
                WindGridAcquisitionEvent.Succeeded(
                    loading.generation, loading.request, loaded,
                ),
            )
            if (after is WindGridAcquisitionState.DataReadyPendingRender) Log.d(
                "RainRadarWind",
                "data ready pending render generation=${after.generation} " +
                    "viewportKey=${after.request.viewportKey}",
            )
            windAcquisition = after
        } catch (cancelled: CancellationException) {
            Log.d(
                "RainRadarWind",
                "Wind grid request ended generation=${loading.generation} " +
                    "viewportKey=${loading.request.viewportKey} " +
                    WindGridFailureDiagnostics.logFields(cancelled),
            )
            throw cancelled
        } catch (failure: Exception) {
            val diagnostic = WindGridFailureDiagnostics.classify(failure)
            Log.w(
                "RainRadarWind",
                "Wind grid failure recorded generation=${loading.generation} " +
                    "viewportKey=${loading.request.viewportKey} " +
                    WindGridFailureDiagnostics.logFields(failure),
            )
            windAcquisition = WindGridAcquisitionReducer.reduce(
                windAcquisition,
                WindGridAcquisitionEvent.Failed(
                    loading.generation, loading.request, diagnostic,
                ),
            )
        }
    }
    val windDeadline = WindGridAcquisitionReducer.deadline(windAcquisition)
    LaunchedEffect(windDeadline) {
        val deadline = windDeadline ?: return@LaunchedEffect
        val remaining = (deadline.deadlineAtMillis -
            WindGridAcquisitionDeadlinePolicy.monotonicNowMillis()).coerceAtLeast(0L)
        if (remaining > 0L) delay(remaining)
        val before = windAcquisition
        val after = WindGridAcquisitionReducer.reduce(
            before,
            WindGridAcquisitionEvent.DeadlineReached(
                deadline.generation,
                deadline.request,
                WindGridAcquisitionDeadlinePolicy.monotonicNowMillis(),
                Instant.now().epochSecond,
            ),
        )
        windAcquisition = after
        if (after !== before &&
            (after is WindGridAcquisitionState.Ready ||
                after is WindGridAcquisitionState.Unavailable)) {
            Log.d(
                "RainRadarWind",
                "deadline generation=${deadline.generation} viewportKey=${deadline.request.viewportKey} " +
                    "state=${after.windDiagnosticState()}",
            )
            completedWindRefresh = deadline.generation
        }
    }
    val activeWind = WindGridAcquisitionReducer.latestGrid(windAcquisition)
    val windPresentationNow = WindGridPresentationClockPolicy.reconcile(layerNow, activeWind)
    val staleWindIdentity = WeatherDataReplacementPolicy.staleWindIdentity(
        activeWind, viewportKey, windPresentationNow,
    )
    val staleLightningIdentity = WeatherDataReplacementPolicy.staleSatelliteIdentity(
        (lightningStatus as? AncillaryStatus.Satellite)?.metadata, layerNow,
    )
    val staleCloudsIdentity = WeatherDataReplacementPolicy.staleSatelliteIdentity(
        (cloudsStatus as? AncillaryStatus.Satellite)?.metadata, layerNow,
    )
    LaunchedEffect(windEnabled, staleWindIdentity, viewportKey) {
        // Entering Loading makes this transition self-deduplicating: a stale Ready grid requests
        // exactly one replacement, while its arrows remain available for the same viewport.
        if (windEnabled && windAcquisition is WindGridAcquisitionState.Ready &&
            activeWind?.viewportKey == viewportKey && staleWindIdentity != null) {
            windAcquisition = WindGridAcquisitionReducer.reduce(
                windAcquisition,
                WindGridAcquisitionEvent.Refresh(
                    viewportKey,
                    windTimelineWindow.cacheKey,
                    WindGridAcquisitionDeadlinePolicy.monotonicNowMillis(),
                    Instant.now().epochSecond,
                ),
            ).also {
                Log.d(
                    "RainRadarWind",
                    "stale replacement generation=${it.generation} viewportKey=${viewportKey ?: "pending"} " +
                        "state=${it.windDiagnosticState()}",
                )
            }
        }
    }
    LaunchedEffect(lightningEnabled, staleLightningIdentity) {
        if (WeatherDataReplacementPolicy.shouldRequest(
                staleLightningIdentity, lastLightningStaleIdentity,
            )) {
            lastLightningStaleIdentity = staleLightningIdentity
            lightningReplacementPhase = WeatherReplacementPhase.LOADING
            lightningRefresh++
        }
    }
    LaunchedEffect(cloudsEnabled, staleCloudsIdentity) {
        if (WeatherDataReplacementPolicy.shouldRequest(
                staleCloudsIdentity, lastCloudsStaleIdentity,
            )) {
            lastCloudsStaleIdentity = staleCloudsIdentity
            cloudsReplacementPhase = WeatherReplacementPhase.LOADING
            cloudsRefresh++
        }
    }
    val windRenderRequest = WindGridAcquisitionReducer.renderRequest(
        windAcquisition, viewportKey, windPresentationNow,
    )
    val renderWindStatus = windRenderRequest?.grid?.let { AncillaryStatus.Wind(it) } ?: if (windEnabled) {
        AncillaryStatus.Loading
    } else AncillaryStatus.Off
    val weatherWindStatus = WindGridAcquisitionReducer.presentationStatus(
        windAcquisition, windEnabled, viewportKey, windPresentationNow,
    )
    val weatherLightningStatus = WeatherReplacementPresentationPolicy.status(
        WeatherDataKind.LIGHTNING, lightningStatus, lightningReplacementPhase,
        staleLightningIdentity, lastLightningStaleIdentity,
    )
    val weatherCloudsStatus = WeatherReplacementPresentationPolicy.status(
        WeatherDataKind.CLOUDS, cloudsStatus, cloudsReplacementPhase,
        staleCloudsIdentity, lastCloudsStaleIdentity,
    )
    val renderAncillary = mapOf(
        RadarMapLayer.WIND to renderWindStatus,
        RadarMapLayer.LIGHTNING to lightningStatus,
        RadarMapLayer.FOG to cloudsStatus,
    )
    val weatherAncillary = mapOf(
        RadarMapLayer.WIND to weatherWindStatus,
        RadarMapLayer.LIGHTNING to weatherLightningStatus,
        RadarMapLayer.FOG to weatherCloudsStatus,
    )
    val refreshClocks = buildMap {
        session?.let { active ->
            val observations = active.timelineFrames.filterNot { it.forecast }.map { it.time }
            observations.maxOrNull()?.let { latest ->
                put(FollowRefreshStream.RADAR_NOW, ProviderPublicationClock(
                    latestEpochSeconds = latest,
                    cadenceSeconds = ProviderCadencePolicy.radarCadence(
                        observations,
                        regional = active.providerSelection.active == RadarProviderKind.METEOGROUP_REGIONAL,
                    ),
                ))
            }
        }
        if (cloudsEnabled) {
            val metadata = (cloudsStatus as? AncillaryStatus.Satellite)?.metadata
            val cadence = metadata?.cadenceSeconds ?: EumetProduct.CLOUD_TYPE.nominalCadenceSeconds
            put(FollowRefreshStream.CLOUDS, ProviderPublicationClock(
                metadata?.latestEpochSeconds ?: Math.floorDiv(layerNow, cadence) * cadence,
                cadence,
            ))
        }
        if (lightningEnabled) {
            val metadata = (lightningStatus as? AncillaryStatus.Satellite)?.metadata
            val cadence = metadata?.cadenceSeconds ?: EumetProduct.LIGHTNING.nominalCadenceSeconds
            put(FollowRefreshStream.LIGHTNING, ProviderPublicationClock(
                metadata?.latestEpochSeconds ?: Math.floorDiv(layerNow, cadence) * cadence,
                cadence,
            ))
        }
        if (windEnabled) {
            val cadence = ProviderCadencePolicy.modelCadenceSeconds
            val latest = WindGridAcquisitionReducer.latestGrid(windAcquisition)?.timelineFrames
                ?.maxOfOrNull { it.validEpochSeconds }
                ?: Math.floorDiv(layerNow, cadence) * cadence
            put(FollowRefreshStream.WIND, ProviderPublicationClock(latest, cadence))
        }
        val modelCadence = ProviderCadencePolicy.modelCadenceSeconds
        put(FollowRefreshStream.POINT_WEATHER, ProviderPublicationClock(
            currentWeather?.validEpochSeconds ?: Math.floorDiv(layerNow, modelCadence) * modelCadence,
            modelCadence,
        ))
    }
    val latestRefreshClocks by rememberUpdatedState(refreshClocks)
    val refreshCoordinator = remember { FollowRefreshCoordinator() }
    val automaticRefreshActive = appForeground && followLive &&
        selectedPlaceId == CURRENT_LOCATION_ID && place != null
    var pendingRadarRefresh by remember { mutableStateOf<PendingFollowRefresh?>(null) }
    var pendingCloudsRefresh by remember { mutableStateOf<PendingFollowRefresh?>(null) }
    var pendingLightningRefresh by remember { mutableStateOf<PendingFollowRefresh?>(null) }
    var pendingWindRefresh by remember { mutableStateOf<PendingFollowRefresh?>(null) }
    var pendingPointRefresh by remember { mutableStateOf<Pair<FollowRefreshTicket, Long>?>(null) }

    LaunchedEffect(automaticRefreshActive) {
        if (!automaticRefreshActive) {
            refreshCoordinator.pause()
            pendingRadarRefresh = null
            pendingCloudsRefresh = null
            pendingLightningRefresh = null
            pendingWindRefresh = null
            pendingPointRefresh = null
            return@LaunchedEffect
        }
        try {
            while (true) {
                val now = Instant.now().epochSecond
                refreshCoordinator.synchronize(latestRefreshClocks, now)
                refreshCoordinator.due(now).forEach { stream ->
                    val ticket = refreshCoordinator.start(stream, now) ?: return@forEach
                    when (stream) {
                        FollowRefreshStream.RADAR_NOW -> {
                            val requested = reload + 1
                            pendingRadarRefresh = PendingFollowRefresh(ticket, requested)
                            reload = requested
                            refreshForecastForFollow()
                        }
                        FollowRefreshStream.CLOUDS -> {
                            val requested = cloudsRefresh + 1
                            pendingCloudsRefresh = PendingFollowRefresh(ticket, requested)
                            cloudsRefresh = requested
                        }
                        FollowRefreshStream.LIGHTNING -> {
                            val requested = lightningRefresh + 1
                            pendingLightningRefresh = PendingFollowRefresh(ticket, requested)
                            lightningRefresh = requested
                        }
                        FollowRefreshStream.WIND -> {
                            val next = WindGridAcquisitionReducer.reduce(
                                windAcquisition,
                                WindGridAcquisitionEvent.Refresh(
                                    viewportKey,
                                    windTimelineWindow.cacheKey,
                                    WindGridAcquisitionDeadlinePolicy.monotonicNowMillis(),
                                    Instant.now().epochSecond,
                                ),
                            )
                            windAcquisition = next
                            Log.d(
                                "RainRadarWind",
                                "automatic refresh generation=${next.generation} " +
                                    "viewportKey=${viewportKey ?: "pending"} state=${next.windDiagnosticState()}",
                            )
                            pendingWindRefresh = PendingFollowRefresh(ticket, next.generation)
                        }
                        FollowRefreshStream.POINT_WEATHER -> {
                            pendingPointRefresh = ticket to refreshPointWeather()
                        }
                    }
                }
                delay(5_000)
            }
        } finally {
            refreshCoordinator.pause()
        }
    }

    LaunchedEffect(completedRadarReload, session) {
        val pending = pendingRadarRefresh ?: return@LaunchedEffect
        if (completedRadarReload != pending.requestGeneration) return@LaunchedEffect
        val latest = session?.timelineFrames?.filterNot { it.forecast }?.maxOfOrNull { it.time }
        refreshCoordinator.complete(pending.ticket, latest, session != null, Instant.now().epochSecond)
        pendingRadarRefresh = null
    }
    LaunchedEffect(completedCloudsRefresh, cloudsStatus) {
        val pending = pendingCloudsRefresh ?: return@LaunchedEffect
        if (completedCloudsRefresh != pending.requestGeneration) return@LaunchedEffect
        val latest = (cloudsStatus as? AncillaryStatus.Satellite)?.metadata?.latestEpochSeconds
        refreshCoordinator.complete(pending.ticket, latest, latest != null, Instant.now().epochSecond)
        pendingCloudsRefresh = null
    }
    LaunchedEffect(completedLightningRefresh, lightningStatus) {
        val pending = pendingLightningRefresh ?: return@LaunchedEffect
        if (completedLightningRefresh != pending.requestGeneration) return@LaunchedEffect
        val latest = (lightningStatus as? AncillaryStatus.Satellite)?.metadata?.latestEpochSeconds
        refreshCoordinator.complete(pending.ticket, latest, latest != null, Instant.now().epochSecond)
        pendingLightningRefresh = null
    }
    LaunchedEffect(completedWindRefresh, windAcquisition) {
        val pending = pendingWindRefresh ?: return@LaunchedEffect
        if (completedWindRefresh != pending.requestGeneration) return@LaunchedEffect
        val latest = WindGridAcquisitionReducer.latestGrid(windAcquisition)?.timelineFrames
            ?.maxOfOrNull { it.validEpochSeconds }
        val succeeded = windAcquisition is WindGridAcquisitionState.Ready && latest != null
        refreshCoordinator.complete(
            pending.ticket, latest, succeeded, Instant.now().epochSecond,
        )
        pendingWindRefresh = null
    }
    LaunchedEffect(pointRefreshCompletion) {
        val pending = pendingPointRefresh ?: return@LaunchedEffect
        if (pointRefreshCompletion.generation != pending.second) return@LaunchedEffect
        refreshCoordinator.complete(
            pending.first,
            pointRefreshCompletion.latestProviderEpochSeconds,
            pointRefreshCompletion.succeeded,
            Instant.now().epochSecond,
        )
        pendingPointRefresh = null
    }
    val currentSelected = selectedPlaceId == CURRENT_LOCATION_ID
    val displayedMapPlace = place ?: session?.place?.takeIf {
        CurrentLocationPresentationPolicy.retainCurrentSession(
            currentSelected = currentSelected,
            hasResolvedPlace = false,
            sessionBelongsToCurrent = it.isCurrentLocation,
        )
    }
    val sessionReusable = session != null && displayedMapPlace != null && when {
        place != null -> RadarLiveSessionPolicy.canReuse(session!!, place)
        else -> currentSelected && displayedMapPlace.isCurrentLocation
    }
    val showInteractiveMap = RadarBaseMapPresentationPolicy.showInteractiveMap(
        hasResolvedPlace = place != null,
        hasRetainedCurrentCoordinates = place == null && displayedMapPlace?.isCurrentLocation == true,
    )
    val locationCapabilityMessage = when (locationState) {
        is LocationUiState.Active -> locationState.message
        is LocationUiState.Unavailable -> locationState.message
        else -> null
    }
    val locationOperationalStatus = CurrentLocationPresentationPolicy.operationalStatus(
        currentSelected = currentSelected,
        hasResolvedPlace = place != null,
        locating = locationRequestPending || locationState is LocationUiState.Locating,
        capabilityMessage = locationCapabilityMessage,
        requestMessage = locationMessage,
    )
    val startManualWindRefresh: () -> Unit = {
        val before = windAcquisition
        val after = WindGridAcquisitionReducer.reduce(
            before,
            WindGridAcquisitionEvent.Refresh(
                viewportKey,
                windTimelineWindow.cacheKey,
                WindGridAcquisitionDeadlinePolicy.monotonicNowMillis(),
                Instant.now().epochSecond,
            ),
        )
        windAcquisition = after
        Log.d(
            "RainRadarWind",
            "manual refresh generation=${after.generation} viewportKey=${viewportKey ?: "pending"} " +
                "state=${after.windDiagnosticState()}",
        )
    }
    val performManualRefresh: () -> Unit = {
        if (currentSelected && place == null) {
            if (windEnabled) {
                pendingWindRefresh = null
                startManualWindRefresh()
            }
            requestCurrentLocation(false)
        } else {
            refreshCoordinator.manualRebase(latestRefreshClocks, Instant.now().epochSecond)
            pendingRadarRefresh = null
            pendingCloudsRefresh = null
            pendingLightningRefresh = null
            pendingWindRefresh = null
            pendingPointRefresh = null
            error = null
            progress = 0 to 0
            refreshing = session != null
            reload++
            val retryLayers = ManualWeatherRefreshPolicy.enabledLayers(enabledMapLayers)
            if (RadarMapLayer.LIGHTNING in retryLayers) {
                lightningReplacementPhase = WeatherReplacementPhase.LOADING
                lightningRefresh++
            }
            if (RadarMapLayer.FOG in retryLayers) {
                cloudsReplacementPhase = WeatherReplacementPhase.LOADING
                cloudsRefresh++
            }
            if (RadarMapLayer.WIND in retryLayers) {
                // Synchronous with the click: the previous Unavailable generation cannot be
                // rendered for even one replacement frame, including unresolved Current.
                startManualWindRefresh()
            }
            refreshForecastForFollow()
            refreshPointWeather()
        }
    }
    val setLayerEnabled: (RadarMapLayer, Boolean) -> Unit = { layer, enabled ->
        if (layer == RadarMapLayer.WIND) {
            val before = windAcquisition
            val after = WindGridAcquisitionReducer.reduce(
                windAcquisition,
                WindGridAcquisitionEvent.Environment(
                    enabled = enabled,
                    viewportKey = viewportKey,
                    timelineKey = windTimelineWindow.cacheKey,
                    nowElapsedMillis = WindGridAcquisitionDeadlinePolicy.monotonicNowMillis(),
                    nowEpochSeconds = Instant.now().epochSecond,
                ),
            )
            windAcquisition = after
            if (after != before) Log.d(
                "RainRadarWind",
                "toggle enabled=$enabled generation=${after.generation} " +
                    "viewportKey=${viewportKey ?: "pending"} state=${after.windDiagnosticState()}",
            )
        }
        setMapLayerEnabled(layer, enabled)
    }
    val providerNotice = providerNoticeEvent?.takeIf { event ->
        place?.let(::forecastSelectionKey) == event.placeKey
    }
    LaunchedEffect(providerNotice?.token) {
        val event = providerNotice ?: return@LaunchedEffect
        delay(requireNotNull(RadarMapNoticeKind.PROVIDER_FALLBACK.lifetimeMillis))
        onProviderNoticeConsumed(event.token)
    }
    val providerMapNotice = providerNotice?.let {
        RadarMapNotice(RadarMapNoticeKind.PROVIDER_FALLBACK, it.message)
    }
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
        Spacer(Modifier.height(6.dp))
        when {
            showInteractiveMap -> RadarPlayer(
                session = session?.takeIf { sessionReusable },
                mapPlace = requireNotNull(displayedMapPlace),
                markerPlace = RadarLiveMapPolicy.marker(displayedMapPlace, liveMapPlace),
                hasFreshLiveFix = RadarLiveMapPolicy.canFollow(selectedPlaceId, liveMapPlace),
                followLive = followLive,
                followCapability = followCapability,
                liveFixElapsedRealtimeNanos = liveFixElapsedRealtimeNanos,
                onFollowLiveChange = { enabled ->
                    val accepted = onFollowLiveChange(enabled)
                    if (enabled && !accepted) locationMessage = followCapability.message
                },
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
                windRenderToken = windRenderRequest?.token,
                onWindRenderObservation = { token, drawnArrowCount ->
                    val before = windAcquisition
                    val after = WindGridAcquisitionReducer.reduce(
                        before,
                        WindGridAcquisitionEvent.RenderObserved(
                            token,
                            drawnArrowCount,
                            WindGridAcquisitionDeadlinePolicy.monotonicNowMillis(),
                        ),
                    )
                    windAcquisition = after
                    if (after !== before && after is WindGridAcquisitionState.Ready) {
                        Log.d(
                            "RainRadarWind",
                            "render ready generation=${after.generation} " +
                                "viewportKey=${after.request.viewportKey} arrows=$drawnArrowCount",
                        )
                        completedWindRefresh = after.generation
                    }
                },
                onWindRendererReset = {
                    val before = windAcquisition
                    val after = WindGridAcquisitionReducer.reduce(
                        before,
                        WindGridAcquisitionEvent.RendererReset(
                            WindGridAcquisitionDeadlinePolicy.monotonicNowMillis(),
                        ),
                    )
                    windAcquisition = after
                    if (after != before) Log.d(
                        "RainRadarWind",
                        "renderer reset generation=${after.generation} state=${after.windDiagnosticState()}",
                    )
                },
                setMapLayerEnabled = setLayerEnabled,
                ancillaryStatuses = renderAncillary,
                weatherStatuses = weatherAncillary,
                locationStatus = locationOperationalStatus,
                currentWeather = currentWeather,
                onWindViewportChanged = { windViewport = it },
                onRefresh = performManualRefresh,
                onLayerError = { layer, message ->
                    when (layer) {
                        RadarMapLayer.LIGHTNING -> {
                            lightningReplacementPhase = WeatherReplacementPhase.UNAVAILABLE
                            if (lightningStatus !is AncillaryStatus.Satellite) {
                                lightningStatus = AncillaryStatus.Unavailable(message)
                            }
                        }
                        RadarMapLayer.FOG -> {
                            cloudsReplacementPhase = WeatherReplacementPhase.UNAVAILABLE
                            if (cloudsStatus !is AncillaryStatus.Satellite) {
                                cloudsStatus = AncillaryStatus.Unavailable(message)
                            }
                        }
                        else -> Unit
                    }
                },
                refreshing = refreshing,
                refreshProgress = progress,
                refreshError = error,
                externalNotice = providerMapNotice,
            )
            else -> RadarLoadingShell(
                mapStyle = mapStyle,
                progress = progress,
                locationState = locationState,
                currentSelected = currentSelected,
                onCurrentLocation = { requestCurrentLocation(true) },
                onRefresh = performManualRefresh,
                notice = providerMapNotice,
                locationStatus = locationOperationalStatus,
                radarLoading = place != null && error == null,
            )
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
private fun ColumnScope.RadarLoadingShell(
    mapStyle: RadarMapStyle,
    progress: Pair<Int, Int>,
    locationState: LocationUiState,
    currentSelected: Boolean,
    onCurrentLocation: () -> Unit,
    onRefresh: () -> Unit,
    notice: RadarMapNotice?,
    locationStatus: RadarRefreshOverlay?,
    radarLoading: Boolean,
) {
    val shape = RoundedCornerShape(22.dp)
    val status = RadarRefreshOverlayPolicy.initialLoading(progress.first, progress.second)
    val darkMap = mapStyle.darkControls
    Card(
        colors = CardDefaults.cardColors(
            containerColor = Color(RadarMapAppearance.loadingBackgroundArgb(mapStyle)),
        ),
        shape = shape,
        modifier = Modifier.fillMaxWidth().weight(1f)
            .border(1.dp, LocalRainAlarmPalette.current.border, shape),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val mapWidthDp = maxWidth.value.toInt()
            val operationalEntries = listOfNotNull(
                locationStatus,
                status.takeIf { radarLoading },
            )
            Row(
                Modifier.align(Alignment.TopEnd).padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onRefresh) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Refresh radar and map layer",
                        tint = if (darkMap) Color.White else Color.Black,
                    )
                }
                IconButton(
                    onClick = onCurrentLocation,
                    enabled = locationState !is LocationUiState.Locating,
                    modifier = Modifier.size(RadarTopControlsPolicy.controlSizeDp.dp)
                        .clearAndSetSemantics {
                            contentDescription = if (locationState is LocationUiState.Locating && currentSelected)
                                WeatherDataStatusPolicy.loading(WeatherDataKind.LOCATION)
                            else "Use current device location"
                        },
                ) {
                    if (locationState is LocationUiState.Locating && currentSelected) CircularProgressIndicator(
                        Modifier.size(22.dp), color = if (darkMap) Color.White else Color.Black,
                        strokeWidth = 2.dp,
                    ) else Icon(
                        Icons.Default.MyLocation,
                        contentDescription = null,
                        tint = if (darkMap) Color.White else Color.Black,
                    )
                }
            }
            RadarOperationalStatusStack(
                operationalEntries,
                mapWidthDp,
                Modifier.align(Alignment.BottomEnd).padding(
                    end = RadarPreparationStackPolicy.endInsetDp.dp,
                    bottom = RadarPreparationStackPolicy.bottomInsetDp.dp,
                ),
            )
            RadarMapNoticeRail(
                notice,
                mapWidthDp = mapWidthDp,
                bottomRightEntryCount = operationalEntries.size,
                modifier = Modifier.align(Alignment.BottomStart),
            )
        }
    }
    // Reserve the player controls/ticks footprint so the map card does not jump when ready.
    Spacer(Modifier.fillMaxWidth().height(69.dp))
}

@Composable
private fun RadarMapNoticeRail(
    notice: RadarMapNotice?,
    mapWidthDp: Int,
    bottomRightEntryCount: Int,
    modifier: Modifier = Modifier,
) {
    var retainedNotice by remember { mutableStateOf(notice) }
    LaunchedEffect(notice) {
        if (notice != null) retainedNotice = notice
    }
    AnimatedVisibility(
        visible = notice != null,
        modifier = modifier.padding(
            start = RadarMapNoticePolicy.attributionStartReservationDp.dp,
            bottom = RadarMapNoticePolicy.noticeBottomInsetDp(
                mapWidthDp, bottomRightEntryCount,
            ).dp,
        ),
        enter = fadeIn(tween(120)),
        exit = fadeOut(tween(180)),
    ) {
        retainedNotice?.let { current ->
            Text(
                current.message,
                color = LocalRainAlarmPalette.current.mapLabelText,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .widthIn(max = RadarMapNoticePolicy.maxWidthDp(
                        mapWidthDp,
                        RadarMapNoticePolicy.sharesBottomRow(mapWidthDp, bottomRightEntryCount),
                    ).dp)
                    .background(
                        LocalRainAlarmPalette.current.mapLabelSurface,
                        RoundedCornerShape(5.dp),
                    )
                    .padding(horizontal = 5.dp, vertical = 3.dp)
                    .clearAndSetSemantics { contentDescription = current.message },
            )
        }
    }
}

@Composable
private fun RadarOperationalStatusStack(
    entries: List<RadarRefreshOverlay>,
    mapWidthDp: Int,
    modifier: Modifier = Modifier,
) {
    if (entries.isEmpty()) return
    Column(
        modifier = modifier.widthIn(max = RadarPreparationStackPolicy.maxWidthDp(mapWidthDp).dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(RadarPreparationStackPolicy.rowSpacingDp.dp),
    ) {
        entries.forEach { status ->
            Text(
                status.label,
                color = LocalRainAlarmPalette.current.mapLabelText,
                fontSize = 11.sp,
                lineHeight = RadarPreparationStackPolicy.rowLineHeightSp.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .widthIn(max = RadarPreparationStackPolicy.maxWidthDp(mapWidthDp).dp)
                    .background(
                        LocalRainAlarmPalette.current.mapLabelSurface,
                        RoundedCornerShape(4.dp),
                    )
                    .padding(horizontal = 4.dp, vertical = 2.dp)
                    .semantics { contentDescription = status.accessibilityLabel },
            )
        }
    }
}

@Composable
private fun ColumnScope.RadarPlayer(
    session: RadarSession?,
    mapPlace: SavedPlace,
    markerPlace: SavedPlace,
    hasFreshLiveFix: Boolean,
    followLive: Boolean,
    followCapability: FollowUiCapability,
    liveFixElapsedRealtimeNanos: Long,
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
    windRenderToken: WindGridRenderToken?,
    onWindRenderObservation: (WindGridRenderToken, Int) -> Unit,
    onWindRendererReset: () -> Unit,
    setMapLayerEnabled: (RadarMapLayer, Boolean) -> Unit,
    ancillaryStatuses: Map<RadarMapLayer, AncillaryStatus>,
    weatherStatuses: Map<RadarMapLayer, AncillaryStatus>,
    locationStatus: RadarRefreshOverlay?,
    currentWeather: CurrentWeather?,
    onWindViewportChanged: (WindViewport) -> Unit,
    onRefresh: () -> Unit,
    onLayerError: (RadarMapLayer, String) -> Unit,
    refreshing: Boolean,
    refreshProgress: Pair<Int, Int>,
    refreshError: String?,
    externalNotice: RadarMapNotice?,
) {
    val darkMap = mapStyle.darkControls
    val secondaryColor = Secondary
    val times = remember(session) { session?.timelineFrames?.map { it.time }.orEmpty() }
    val forecastFlags = remember(session) { session?.timelineFrames?.map { it.forecast }.orEmpty() }
    val hasRadarSession = session != null && times.isNotEmpty()
    val loadingEpochSeconds = remember(mapPlace.id) {
        Math.floorDiv(Instant.now().epochSecond, 60L) * 60L
    }
    val timelineStartEpochSeconds = times.firstOrNull() ?: loadingEpochSeconds
    val latestObservationIndex = forecastFlags.indexOfLast { !it }.coerceAtLeast(0)
    val latestOffset = if (hasRadarSession) {
        (times[latestObservationIndex] - times.first()).toFloat()
    } else 0f
    val providerForecast = forecastFlags.any { it }
    var displayedRadarTier by remember(session) {
        mutableStateOf(if (session?.regional != null || session?.legacyArchive != null) {
            RadarResolutionTier.REGIONAL
        } else RadarResolutionTier.DETAIL)
    }
    val preferredOpenTier = displayedRadarTier.takeIf { session?.tier(it) != null }
        ?: if (session?.detail != null) RadarResolutionTier.DETAIL else RadarResolutionTier.REGIONAL
    val estimatedForecast = session != null && !providerForecast &&
        session.velocity(preferredOpenTier)?.futureField != null
    val forecastAvailable = providerForecast || estimatedForecast
    val endOffset = if (!hasRadarSession) {
        RadarTimeline.FORECAST_HORIZON_SECONDS.toFloat()
    } else if (providerForecast) {
        (times.last() - times.first()).toFloat()
    } else {
        (times.last() - times.first() + if (estimatedForecast) RadarTimeline.FORECAST_HORIZON_SECONDS else 0L).toFloat()
    }
    val initialCursor = remember(session) {
        if (!hasRadarSession) 0f else RadarEntryClock.initialCursor(
            times.first(), times[latestObservationIndex], times.first() + endOffset.toLong(),
            forecastAvailable, Instant.now().epochSecond,
        )
    }
    val chartDecision = if (!hasRadarSession) null else chartTimeRequest?.let {
        RadarChartTimeLink.decide(it, selectedPlaceId, requireNotNull(session).place.id, times.first(),
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
    var compatibilityNotice by remember(session) { mutableStateOf<String?>(null) }
    LaunchedEffect(rendererStatus) {
        (rendererStatus as? RadarRendererStatus.Compatibility)?.message?.let {
            compatibilityNotice = it
        }
    }
    LaunchedEffect(compatibilityNotice) {
        val captured = compatibilityNotice ?: return@LaunchedEffect
        delay(requireNotNull(RadarMapNoticeKind.RENDERER_COMPATIBILITY.lifetimeMillis))
        if (compatibilityNotice == captured) compatibilityNotice = null
    }
    LaunchedEffect(chartTimeMessage) {
        val captured = chartTimeMessage ?: return@LaunchedEffect
        delay(requireNotNull(RadarMapNoticeKind.CHART_TIME.lifetimeMillis))
        if (chartTimeMessage == captured) chartTimeMessage = null
    }
    var mapStyleError by remember { mutableStateOf<String?>(null) }
    var satellitePreparation by remember {
        mutableStateOf<Map<RadarMapLayer, SatellitePreparationStatus>>(emptyMap())
    }
    val safeCursor = cursor.takeIf { it.isFinite() }?.coerceIn(0f, endOffset) ?: initialCursor
    val bracket = if (!hasRadarSession) null else if (providerForecast) {
        RadarTimeline.bracket(times, forecastFlags, times.first() + safeCursor.toDouble())
    } else {
        RadarTimeline.bracket(times, times.first() + safeCursor.toDouble())
    }

    LaunchedEffect(playing, session, playbackSpeed) {
        if (!playing || !hasRadarSession) return@LaunchedEffect
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
    val label = bracket?.let {
        timelineLabel(times, it, safeCursor, latestOffset, forecastAvailable)
    } ?: "Radar loading"
    val refreshOverlay = if (!hasRadarSession && refreshError.isNullOrBlank()) {
        RadarRefreshOverlayPolicy.initialLoading(refreshProgress.first, refreshProgress.second)
    } else RadarRefreshOverlayPolicy.status(
        hasSession = hasRadarSession, refreshing = refreshing,
        completed = refreshProgress.first, total = refreshProgress.second, error = refreshError,
    )
    val preparationEntries = RadarPreparationStackPolicy.entries(
        refreshOverlay, enabledMapLayers, weatherStatuses, satellitePreparation, locationStatus,
    )
    val mapNotice = RadarMapNoticePolicy.select(
        (rendererStatus as? RadarRendererStatus.Error)?.let {
            RadarMapNotice(
                RadarMapNoticeKind.RENDERER_FAILURE,
                "Radar renderer issue · ${it.message}",
            )
        },
        mapStyleError?.let { RadarMapNotice(RadarMapNoticeKind.MAP_STYLE, it) },
        externalNotice,
        compatibilityNotice?.let {
            RadarMapNotice(RadarMapNoticeKind.RENDERER_COMPATIBILITY, it)
        },
        chartTimeMessage?.let { RadarMapNotice(RadarMapNoticeKind.CHART_TIME, it) },
    )
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
                liveFixElapsedRealtimeNanos = liveFixElapsedRealtimeNanos,
                onManualCameraGesture = { if (followLive) onFollowLiveChange(false) },
                onLongPress = onLongPress,
                recenterSignal = currentRecenterTick,
                cameraMemory = cameraMemory,
                isPlaying = playing,
                onRendererStatus = { rendererStatus = it },
                mapStyle = mapStyle,
                windGrid = (ancillaryStatuses[RadarMapLayer.WIND] as? AncillaryStatus.Wind)?.grid,
                windArrowScale = windArrowScale,
                windRenderToken = windRenderToken,
                onWindRenderObservation = onWindRenderObservation,
                onWindRendererReset = onWindRendererReset,
                satelliteCatalogs = listOfNotNull(
                    ancillaryStatuses[RadarMapLayer.FOG] as? AncillaryStatus.Satellite,
                    ancillaryStatuses[RadarMapLayer.LIGHTNING] as? AncillaryStatus.Satellite,
                ).flatMap(AncillaryStatus.Satellite::catalog),
                enabledSatelliteLayers = enabledMapLayers.filterTo(mutableSetOf()) {
                    it == RadarMapLayer.FOG || it == RadarMapLayer.LIGHTNING
                },
                satelliteDisplayEpochSeconds =
                    (timelineStartEpochSeconds + safeCursor.toDouble()).toLong(),
                satelliteTimelineStartEpochSeconds = timelineStartEpochSeconds,
                satelliteTimelineEndEpochSeconds = timelineStartEpochSeconds + endOffset.toLong(),
                satelliteWeather = currentWeather,
                onSatellitePreparation = { layer, status ->
                    satellitePreparation = satellitePreparation.toMutableMap().apply {
                        if (status == null) remove(layer) else put(layer, status)
                    }
                },
                onLayerError = onLayerError,
                onMapStyleError = { mapStyleError = it },
                onWindViewportChanged = onWindViewportChanged,
                onRadarTierChanged = { displayedRadarTier = it },
            )
            RadarMapNoticeRail(
                mapNotice,
                mapWidthDp = mapWidthDp,
                bottomRightEntryCount = preparationEntries.size,
                modifier = Modifier.align(Alignment.BottomStart),
            )
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
            Row(Modifier.align(Alignment.TopEnd).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (selectedPlaceId == CURRENT_LOCATION_ID) {
                    IconButton(onClick = { onFollowLiveChange(!followLive) },
                        enabled = hasFreshLiveFix || followCapability.message != null,
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
            RadarLayerStatuses(
                enabledMapLayers, currentWeather, mapWidthDp,
                Modifier.align(Alignment.TopEnd).padding(
                    top = RadarTopControlsPolicy.statusTopDp.dp,
                    end = RadarTopControlsPolicy.statusEndDp.dp))
            RadarOperationalStatusStack(
                preparationEntries,
                mapWidthDp,
                Modifier.align(Alignment.BottomEnd).padding(
                    end = RadarPreparationStackPolicy.endInsetDp.dp,
                    bottom = RadarPreparationStackPolicy.bottomInsetDp.dp,
                ),
            )
        }
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = { playing = !playing },
            enabled = hasRadarSession,
            modifier = Modifier.size(48.dp),
        ) {
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
            enabled = hasRadarSession,
            modifier = Modifier.weight(1f).semantics {
                contentDescription = "Radar timeline"
                stateDescription = label
            },
        )
    }
    if (hasRadarSession && endOffset > 0f) {
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
    } else Spacer(Modifier.fillMaxWidth().height(21.dp))
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
