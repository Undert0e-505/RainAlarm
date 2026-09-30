package com.rainalarm.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect as AndroidRect
import android.os.Build
import android.util.Log
import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.rainalarm.app.LocationUiState
import com.rainalarm.app.R
import com.rainalarm.app.FollowUiCapability
import com.rainalarm.app.PointRefreshCompletion
import com.rainalarm.app.ProviderMapNoticeEvent
import com.rainalarm.app.alerts.RadarLightningControlState
import com.rainalarm.app.data.RadarSession
import com.rainalarm.app.data.FeatureTourScenario
import com.rainalarm.app.data.RadarProviderCoordinator
import com.rainalarm.app.data.RadarSettingsRepository
import com.rainalarm.app.data.SavedPlace
import com.rainalarm.app.data.CURRENT_LOCATION_ID
import com.rainalarm.app.data.PlaceCollection
import com.rainalarm.app.data.PlaceCollectionRules
import com.rainalarm.app.data.PlaceCoordinatePolicy
import com.rainalarm.app.data.PlaceDuplicateFeedbackPolicy
import com.rainalarm.app.data.PlaceNameSuggestionRepository
import com.rainalarm.app.data.PlacePickerNamePolicy
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
import com.rainalarm.app.data.TravelModeDiagnostics
import com.rainalarm.app.data.LocationCadenceDiagnostics
import com.rainalarm.app.data.TravelDisplayTarget
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
import com.rainalarm.app.domain.FeatureTourRadarField
import com.rainalarm.app.domain.RadarTimelineLabelLayout
import com.rainalarm.app.domain.RadarTravelTimelinePolicy
import com.rainalarm.app.domain.RadarTravelClockPolicy
import com.rainalarm.app.domain.RadarTravelTransitionReason
import com.rainalarm.app.domain.EntryTransitionPhase
import com.rainalarm.app.domain.EntryTransitionPolicy
import com.rainalarm.app.domain.EntryTransitionState
import com.rainalarm.app.domain.RadarEntryFocusPolicy
import com.rainalarm.app.domain.RadarEntryFocusActivationPolicy
import com.rainalarm.app.domain.RadarMarkerFocusAction
import com.rainalarm.app.domain.RadarMarkerFocusPolicy
import com.rainalarm.app.domain.RadarMarkerFocusRequest
import com.rainalarm.app.domain.RadarPageEdge
import com.rainalarm.app.domain.RadarPageSwipePolicy
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
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

internal object RadarPageSwipeLayoutPolicy {
    /** Full interactive strip, measured from the physical page edge rather than the map card. */
    const val widthDp = 64
    const val leftTopInsetDp = 0
    const val rightTopInsetDp = RadarTopControlsPolicy.statusTopDp + 28
    /** Bottom-left cut-out protects the information icon and its 48 dp target plus margin. */
    const val leftInfoCutoutHeightDp = 64
    const val exclusionHeightDp = 200
    const val leftExclusionTopDp = 48
    const val rightExclusionTopDp = rightTopInsetDp

    fun topInsetDp(direction: Int): Int =
        if (direction < 0) leftTopInsetDp else rightTopInsetDp

    fun directionForStart(
        xDp: Float,
        yDp: Float,
        screenWidthDp: Float,
        mapTopDp: Float,
        mapBottomDp: Float,
    ): Int = when {
        xDp in 0f..widthDp.toFloat() &&
            yDp >= mapTopDp + leftTopInsetDp &&
            yDp < mapBottomDp - leftInfoCutoutHeightDp -> -1
        xDp in (screenWidthDp - widthDp)..screenWidthDp &&
            yDp >= mapTopDp + rightTopInsetDp && yDp < mapBottomDp -> 1
        else -> 0
    }
}

sealed interface RadarPageSwipeEvent {
    data object Begin : RadarPageSwipeEvent
    data class Drag(val pointerDeltaPixels: Float) : RadarPageSwipeEvent
    data class End(val destinationDelta: Int?) : RadarPageSwipeEvent
    data object Cancel : RadarPageSwipeEvent
}

internal object SatellitePreparationLabelPolicy {
    /** Active preparation belongs exclusively to the bottom-right progress stack. */
    fun activeLabel(layer: RadarMapLayer, status: SatellitePreparationStatus): String? = when (status) {
        is SatellitePreparationStatus.Preparing -> if (status.total > 0 && status.ready >= status.total) {
            WeatherDataStatusPolicy.preparing(layer.weatherDataKind())
        } else WeatherDataStatusPolicy.loading(layer.weatherDataKind(), status.ready, status.total)
        SatellitePreparationStatus.Rendering ->
            WeatherDataStatusPolicy.preparing(layer.weatherDataKind())
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
        information: RadarRefreshOverlay? = null,
        rendering: RadarRefreshOverlay? = null,
    ): List<RadarRefreshOverlay> {
        val operational = buildList {
            location?.let(::add)
            radar?.let(::add)
            rendering?.let(::add)
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
        // Loading/unavailable states always win the bounded rail. Informational events only use
        // spare space and their independent timer continues even while they are obscured.
        return if (information == null || operational.size >= maximumEntries) operational
        else operational + information
    }

    fun layerLabel(
        layer: RadarMapLayer,
        ancillary: AncillaryStatus?,
        preparation: SatellitePreparationStatus?,
    ): String? = when {
        preparation is SatellitePreparationStatus.Preparing ||
            preparation == SatellitePreparationStatus.Rendering ->
            SatellitePreparationLabelPolicy.activeLabel(layer, preparation)
        ancillary == AncillaryStatus.Loading || ancillary == AncillaryStatus.Off ->
            WeatherDataStatusPolicy.loading(layer.weatherDataKind())
        preparation is SatellitePreparationStatus.Failed && preparation.hasRenderableFallback -> null
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

internal object RadarManualRefreshPolicy {
    fun shouldRecoverRenderer(
        rendererStatus: RadarRendererStatus,
        mapStyleError: String?,
    ): Boolean = rendererStatus is RadarRendererStatus.Error || !mapStyleError.isNullOrBlank()
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
            val direction = localizedCardinalDirectionLabel(from)
            stringResource(R.string.wind_value, speed.toInt(), direction) to
                stringResource(R.string.radar_wind_description, direction, speed.toInt())
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
    lightningControlState: RadarLightningControlState,
    onLightningControlTap: () -> Unit,
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
                val active = if (layer == RadarMapLayer.LIGHTNING) {
                    lightningControlState != RadarLightningControlState.OFF
                } else layer in enabledMapLayers
                val layerName = localizedLayerName(layer)
                val layerDescription = stringResource(R.string.radar_layer_description, layerName)
                val layerState = if (layer == RadarMapLayer.LIGHTNING &&
                    lightningControlState == RadarLightningControlState.TEMPORARY
                ) stringResource(R.string.radar_lightning_temporary_state)
                else stringResource(if (active) R.string.common_enabled else R.string.common_disabled)
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
                            onValueChange = {
                                if (layer == RadarMapLayer.LIGHTNING) onLightningControlTap()
                                else setMapLayerEnabled(layer, it)
                            },
                        )
                        .semantics {
                            contentDescription = layerDescription
                            stateDescription = layerState
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null,
                        tint = if (active) Accent else inactiveTint,
                        modifier = Modifier.size(24.dp))
                    if (layer == RadarMapLayer.LIGHTNING &&
                        lightningControlState == RadarLightningControlState.TEMPORARY
                    ) {
                        Icon(
                            Icons.Default.Schedule,
                            contentDescription = null,
                            tint = inactiveTint,
                            modifier = Modifier.align(Alignment.BottomEnd)
                                .padding(end = 6.dp, bottom = 5.dp)
                                .size(11.dp),
                        )
                    }
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

private data class RadarEntryStartSignal(
    val generation: Int,
    val animate: Boolean,
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
    requestedProvider: RadarProviderKind,
    mapStyle: RadarMapStyle,
    coverageMaskDarkness: Float,
    saveAndSelect: (SavedPlace) -> Unit,
    locationState: LocationUiState,
    appForeground: Boolean,
    followLive: Boolean,
    followCapability: FollowUiCapability,
    liveFixElapsedRealtimeNanos: Long,
    travelDisplayTarget: TravelDisplayTarget?,
    onFollowLiveChange: (Boolean) -> Boolean,
    onTravelMapGesture: () -> Unit,
    activateTravelMode: () -> Boolean,
    currentRecenterTick: Int,
    useCurrentLocation: () -> Unit,
    recenterToCurrentLocation: () -> Unit,
    recenterToSelectedPlace: () -> Unit,
    cameraMemory: RadarCameraMemory,
    selectPlace: (String) -> Unit,
    enabledMapLayers: Set<RadarMapLayer>,
    windArrowScale: Float,
    setMapLayerEnabled: (RadarMapLayer, Boolean) -> Unit,
    lightningControlState: RadarLightningControlState = RadarLightningControlState.OFF,
    onLightningControlTap: () -> Unit = {},
    temporaryLightningNoticePending: Boolean = false,
    onTemporaryLightningNoticeConsumed: () -> Unit = {},
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
    onPageSwipeBoundsChanged: (Rect) -> Unit = {},
    screenActive: Boolean = true,
    entryTransition: EntryTransitionState = EntryTransitionPolicy.initial(active = true),
    onEntrySettled: (Int) -> Unit = {},
    featureTourScenario: FeatureTourScenario? = null,
    onFeatureTourTarget: (FeatureTourTargetBounds) -> Unit = {},
) {
    val context = LocalContext.current
    val platformAnimatorScale = remember(context) {
        runCatching {
            android.provider.Settings.Global.getFloat(
                context.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            )
        }.getOrDefault(1f).coerceIn(0f, 10f)
    }
    val animationsEnabled = platformAnimatorScale > 0f
    val entryFocusProgress = remember(entryTransition.generation) {
        Animatable(if (entryTransition.phase == EntryTransitionPhase.SETTLED) 1f else 0f)
    }
    val markerFocusProgress = remember { Animatable(1f) }
    val markerFocusScope = rememberCoroutineScope()
    var markerFocusToken by remember { mutableIntStateOf(0) }
    var markerFocusRequest by remember { mutableStateOf<RadarMarkerFocusRequest?>(null) }
    var nativeEntryStart by remember(entryTransition.generation) {
        mutableStateOf<RadarEntryStartSignal?>(null)
    }
    val entryFocusEnabled = RadarEntryFocusActivationPolicy.shouldPrepare(
        entryPending = entryTransition.phase != EntryTransitionPhase.SETTLED,
        entryGeneration = entryTransition.generation,
        hasResolvedPlace = place != null,
        waitingForInitialCurrentFix = place?.isCurrentLocation == true &&
            locationState is LocationUiState.Locating,
    )
    LaunchedEffect(
        entryTransition.generation,
        entryTransition.phase,
        nativeEntryStart,
        animationsEnabled,
    ) {
        when (entryTransition.phase) {
            EntryTransitionPhase.PREPARED -> entryFocusProgress.snapTo(0f)
            EntryTransitionPhase.PLAY_REQUESTED -> {
                val start = nativeEntryStart?.takeIf { it.generation == entryTransition.generation }
                    ?: return@LaunchedEffect
                if (start.animate && animationsEnabled) {
                    entryFocusProgress.animateTo(
                        1f,
                        tween(RadarEntryFocusPolicy.DURATION_MILLIS, easing = FastOutSlowInEasing),
                    )
                } else entryFocusProgress.snapTo(1f)
                onEntrySettled(entryTransition.generation)
            }
            EntryTransitionPhase.SETTLED -> entryFocusProgress.snapTo(1f)
        }
    }
    LaunchedEffect(place?.id, markerFocusRequest?.token, animationsEnabled) {
        val request = markerFocusRequest ?: return@LaunchedEffect
        when (RadarMarkerFocusPolicy.resolve(request, place?.id)) {
            RadarMarkerFocusAction.WAIT -> Unit
            RadarMarkerFocusAction.CANCEL -> {
                markerFocusProgress.snapTo(1f)
                if (markerFocusRequest?.token == request.token) markerFocusRequest = null
            }
            RadarMarkerFocusAction.ANIMATE -> {
                if (animationsEnabled) {
                    markerFocusProgress.snapTo(0f)
                    markerFocusProgress.animateTo(
                        1f,
                        tween(RadarEntryFocusPolicy.DURATION_MILLIS, easing = FastOutSlowInEasing),
                    )
                } else markerFocusProgress.snapTo(1f)
                if (markerFocusRequest?.token == request.token) markerFocusRequest = null
            }
        }
    }
    val loader = remember { RadarProviderCoordinator(RadarSettingsRepository(context)) }
    val windEnabled = RadarMapLayer.WIND in enabledMapLayers
    var session by remember { mutableStateOf<RadarSession?>(null) }
    val replaceOwnedRadarSession: (RadarSession?, String) -> Unit = { replacement, reason ->
        val previous = session
        if (previous !== replacement) {
            session = replacement
            previous?.release("screen-$reason")
        }
    }
    val latestOwnedRadarSession by rememberUpdatedState(session)
    DisposableEffect(Unit) {
        onDispose {
            latestOwnedRadarSession?.release("screen-dispose")
        }
    }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0 to 0) }
    var radarPreparing by remember { mutableStateOf(false) }
    val radarRequestGate = remember { OverlayRequestGeneration() }
    val lightningRequestGate = remember { OverlayRequestGeneration() }
    val cloudsRequestGate = remember { OverlayRequestGeneration() }
    var reload by remember { mutableIntStateOf(0) }
    var rendererRecoveryGeneration by remember { mutableIntStateOf(0) }
    var lightningRefresh by remember { mutableIntStateOf(0) }
    var cloudsRefresh by remember { mutableIntStateOf(0) }
    var completedRadarReload by remember { mutableIntStateOf(0) }
    var installedRadarRefresh by remember { mutableIntStateOf(Int.MIN_VALUE) }
    var installedRadarIdentity by remember { mutableStateOf<RadarRainLoadIdentity?>(null) }
    var installedRadarAnchor by remember { mutableStateOf<SavedPlace?>(null) }
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
    var pendingTravelActivation by remember { mutableStateOf(false) }
    var locationRequestPending by remember { mutableStateOf(false) }
    var locationOperationGeneration by remember { mutableIntStateOf(0) }
    var permissionOperationGeneration by remember { mutableStateOf<Int?>(null) }
    var travelNoticeActivationToken by remember { mutableIntStateOf(0) }
    var travelTimelineActivationToken by remember { mutableIntStateOf(0) }
    var travelNoticeVisible by remember { mutableStateOf(false) }
    LaunchedEffect(travelNoticeActivationToken) {
        val token = travelNoticeActivationToken
        if (token <= 0) return@LaunchedEffect
        travelNoticeVisible = true
        delay(TravelActivationNoticePolicy.DURATION_MILLIS)
        if (travelNoticeActivationToken == token) travelNoticeVisible = false
    }
    LaunchedEffect(followLive) {
        if (!followLive) travelNoticeVisible = false
    }
    LaunchedEffect(screenActive) {
        if (screenActive && followLive) travelTimelineActivationToken++
    }
    val radarView = LocalView.current
    val keepScreenOn = appForeground && selectedPlaceId == CURRENT_LOCATION_ID && followLive
    DisposableEffect(radarView, keepScreenOn) {
        radarView.keepScreenOn = keepScreenOn
        onDispose { if (keepScreenOn) radarView.keepScreenOn = false }
    }
    val permissionDeniedMessage = stringResource(R.string.places_permission_denied)
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val completedGeneration = permissionOperationGeneration
        permissionOperationGeneration = null
        if (completedGeneration == locationOperationGeneration) {
            if (result.values.any { it }) {
                locationMessage = null
                if (pendingTravelActivation) activateTravelMode()
                else if (pendingMapRecenter) recenterToCurrentLocation() else useCurrentLocation()
            } else {
                locationMessage = permissionDeniedMessage
                locationRequestPending = false
            }
            pendingMapRecenter = false
            pendingTravelActivation = false
        }
    }
    val requestCurrentLocation: (Boolean) -> Unit = { recenterMap ->
        locationOperationGeneration++
        locationMessage = null
        locationRequestPending = !(selectedPlaceId == CURRENT_LOCATION_ID &&
            (place != null || liveMapPlace != null))
        pendingMapRecenter = recenterMap
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            if (recenterMap) recenterToCurrentLocation() else useCurrentLocation()
            pendingMapRecenter = false
        } else {
            permissionOperationGeneration = locationOperationGeneration
            permissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            )
        }
    }
    val requestTravelMode: () -> Unit = {
        travelNoticeActivationToken++
        travelTimelineActivationToken++
        travelNoticeVisible = true
        locationMessage = null
        // Travel is navigation-style following: an existing approximate grant is enough
        // for ordinary Current, but must still take the platform's precise-upgrade path.
        val preciseGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (preciseGranted) {
            if (!activateTravelMode()) locationMessage = followCapability.message
        } else {
            locationOperationGeneration++
            pendingTravelActivation = true
            locationRequestPending = true
            permissionOperationGeneration = locationOperationGeneration
            permissionLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ))
        }
    }

    // Live fixes move the marker immediately, but must not cancel a regional download or
    // destroy its GL session for every 250 m location update. Screen visibility is only a gate:
    // re-entering Radar is not itself a rain-data invalidation.
    val requestedRadarIdentity = RadarRainSessionLoadPolicy.identity(
        place, requestedProvider, showLikelySnow,
    )
    // Coordinates re-evaluate retained-footprint compatibility, but are deliberately not part of
    // the acquisition identity. Saved <-> Current and rapid Follow fixes inside that footprint
    // therefore reuse the installed frames without network work.
    val radarAcquisitionLocationKey = RadarLiveSessionPolicy.acquisitionLocationKey(place)
    LaunchedEffect(screenActive, requestedRadarIdentity, radarAcquisitionLocationKey, reload) {
        val requestGeneration = radarRequestGate.begin()
        val requestStartedAt = OverlayAcquisitionDiagnostics.nowMillis()
        val requestTrigger = when {
            reload != installedRadarRefresh -> "refresh"
            installedRadarIdentity != null && requestedRadarIdentity != installedRadarIdentity -> "provider"
            else -> "selection"
        }
        val requestTargetHash = OverlayAcquisitionDiagnostics.targetHash(
            place?.id, place?.latitude?.toBits(), place?.longitude?.toBits(),
            requestedRadarIdentity?.requestedProvider,
        )
        if (!screenActive) return@LaunchedEffect
        val requestReload = reload
        if (session?.isReleased == true) {
            Log.w(
                "RainRadarScreen",
                "Retained radar session was already released; reacquiring immediately",
            )
            replaceOwnedRadarSession(null, "released-retained")
            installedRadarAnchor = null
        }
        val keepVisible = when {
            place != null -> session?.let {
                RadarLiveSessionPolicy.canReuse(
                    it, place, installedRadarAnchor ?: it.place,
                )
            } == true
            // Selecting Travel can temporarily make Current unresolved. Keep the last valid rain
            // footprint visible until the first fix decides whether it is actually compatible.
            else -> selectedPlaceId == CURRENT_LOCATION_ID && session?.isReleased == false
        }
        if (!keepVisible) {
            replaceOwnedRadarSession(null, "incompatible")
            installedRadarAnchor = null
        } else if (place != null) {
            // Adopt an equivalent Saved <-> Current transition without replacing the immutable
            // frame/session owner. Subsequent Current fixes can then move throughout its retained
            // footprint without being compared forever against the old saved coordinate.
            installedRadarAnchor = place
        }
        if (place == null) {
            refreshing = false
            radarPreparing = false
            Log.i("RainRadarScreen", "No resolved place; radar load deferred")
            completedRadarReload = requestReload
            return@LaunchedEffect
        }
        if (!RadarRainSessionLoadPolicy.shouldLoad(
                screenActive = screenActive,
                requested = requestedRadarIdentity,
                installed = installedRadarIdentity,
                refreshGeneration = requestReload,
                installedRefreshGeneration = installedRadarRefresh,
                hasReusableSession = keepVisible,
            )) {
            refreshing = false
            radarPreparing = false
            Log.d("RainRadarScreen", "Reusing compatible fresh radar session")
            return@LaunchedEffect
        }
        refreshing = keepVisible
        error = null
        progress = 0 to 0
        radarPreparing = false
        OverlayAcquisitionDiagnostics.trace(
            "radar", requestedRadarIdentity?.requestedProvider?.name ?: "unknown",
            requestTargetHash, requestGeneration, requestTrigger,
            OverlayAcquisitionPhase.REQUEST, requestStartedAt,
            cacheFallback = keepVisible,
        )
        Log.i("RainRadarScreen", "Starting ${if (place.isCurrentLocation) "current" else "saved"} radar load")
        var candidate: RadarSession? = null
        try {
            val loaded = loader.load(place, onProgress = { completed, total ->
                if (!radarRequestGate.accepts(requestGeneration)) {
                    OverlayAcquisitionDiagnostics.trace(
                        "radar", requestedRadarIdentity?.requestedProvider?.name ?: "unknown",
                        requestTargetHash, requestGeneration, requestTrigger,
                        OverlayAcquisitionPhase.TRANSFER, requestStartedAt, completed, total,
                        terminalResult = "stale_progress", cacheFallback = keepVisible,
                        accepted = false,
                    )
                    return@load
                }
                progress = completed to total
                radarPreparing = total > 0 && completed >= total
                OverlayAcquisitionDiagnostics.trace(
                    "radar", requestedRadarIdentity?.requestedProvider?.name ?: "unknown",
                    requestTargetHash, requestGeneration, requestTrigger,
                    if (radarPreparing) OverlayAcquisitionPhase.PREPARING
                    else OverlayAcquisitionPhase.TRANSFER,
                    requestStartedAt, completed, total, cacheFallback = keepVisible,
                )
            }).also {
                Log.i("RainRadarScreen", "Radar session ready provider=${it.providerSelection.active} frames=${it.timelineFrames.size}")
            }
            candidate = loaded
            currentCoroutineContext().ensureActive()
            if (!radarRequestGate.accepts(requestGeneration) || requestReload != reload) {
                OverlayAcquisitionDiagnostics.trace(
                    "radar", loaded.providerSelection.active.name, requestTargetHash,
                    requestGeneration, requestTrigger, OverlayAcquisitionPhase.READY,
                    requestStartedAt, terminalResult = "stale_success",
                    cacheFallback = keepVisible, accepted = false,
                )
                loaded.release()
                candidate = null
                return@LaunchedEffect
            }
            onProviderSelection(place, loaded.providerSelection)
            replaceOwnedRadarSession(loaded, "replacement")
            installedRadarIdentity = requestedRadarIdentity
            installedRadarRefresh = requestReload
            installedRadarAnchor = place
            candidate = null
            OverlayAcquisitionDiagnostics.trace(
                "radar", loaded.providerSelection.active.name, requestTargetHash,
                requestGeneration, requestTrigger, OverlayAcquisitionPhase.READY,
                requestStartedAt, terminalResult = "ready", cacheFallback = keepVisible,
            )
        } catch (cancelled: CancellationException) {
            candidate?.release()
            OverlayAcquisitionDiagnostics.trace(
                "radar", requestedRadarIdentity?.requestedProvider?.name ?: "unknown",
                requestTargetHash, requestGeneration, requestTrigger,
                OverlayAcquisitionPhase.CANCELLED, requestStartedAt,
                terminalResult = "replaced", cacheFallback = keepVisible,
                accepted = radarRequestGate.accepts(requestGeneration),
            )
            throw cancelled
        } catch (failure: Exception) {
            candidate?.release()
            Log.e("RainRadarScreen", "Radar session load failed", failure)
            val accepted = radarRequestGate.accepts(requestGeneration)
            if (accepted) {
                error = failure.message ?: "Radar session could not be loaded"
                if (!keepVisible) replaceOwnedRadarSession(null, "load-failed")
            }
            OverlayAcquisitionDiagnostics.trace(
                "radar", requestedRadarIdentity?.requestedProvider?.name ?: "unknown",
                requestTargetHash, requestGeneration, requestTrigger,
                OverlayAcquisitionPhase.FAILED, requestStartedAt,
                terminalResult = failure.javaClass.simpleName, cacheFallback = keepVisible,
                accepted = accepted,
            )
        } finally {
            if (radarRequestGate.accepts(requestGeneration)) {
                completedRadarReload = requestReload
                refreshing = false
                radarPreparing = false
            }
        }
    }
    LaunchedEffect(selectedPlaceId, place, liveMapPlace, locationState, screenActive) {
        if (!screenActive) {
            locationOperationGeneration++
            permissionOperationGeneration = null
            locationRequestPending = false
            pendingMapRecenter = false
            pendingTravelActivation = false
            return@LaunchedEffect
        }
        when (locationState) {
            is LocationUiState.Active -> {
                if (place != null || liveMapPlace != null) locationRequestPending = false
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
            locationOperationGeneration++
            permissionOperationGeneration = null
            locationRequestPending = false
            locationMessage = null
            pendingMapRecenter = false
            pendingTravelActivation = false
        }
    }
    val regionLatitude = place?.latitude?.times(10)?.toInt()
    val regionLongitude = place?.longitude?.times(10)?.toInt()
    val lightningEnabled = RadarMapLayer.LIGHTNING in enabledMapLayers
    LaunchedEffect(lightningEnabled, place?.id, regionLatitude, regionLongitude, lightningRefresh) {
        val requestGeneration = lightningRequestGate.begin()
        val requestStartedAt = OverlayAcquisitionDiagnostics.nowMillis()
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
        val targetHash = OverlayAcquisitionDiagnostics.targetHash(
            resolvedPlace.id, resolvedPlace.latitude.toBits(), resolvedPlace.longitude.toBits(),
            RadarMapLayer.LIGHTNING,
        )
        val retained = (lightningStatus as? AncillaryStatus.Satellite)?.takeIf {
            CloudsSwapPolicy.canRetainDuringReplacement(
                it.metadata, lightningVerifiedPlaceKey, resolvedPlace,
            )
        }
        val force = lightningRefresh != previousLightningRefresh
        previousLightningRefresh = lightningRefresh
        lightningReplacementPhase = WeatherReplacementPhase.LOADING
        if (retained == null) lightningStatus = AncillaryStatus.Loading
        OverlayAcquisitionDiagnostics.trace(
            "lightning", "EUMETSAT", targetHash, requestGeneration,
            if (force) "refresh" else "selection", OverlayAcquisitionPhase.REQUEST,
            requestStartedAt, cacheFallback = retained != null,
        )
        try {
            val loaded = AncillaryStatus.Satellite(EumetViewRepository.metadata(
                RadarMapLayer.LIGHTNING, resolvedPlace, force,
            ))
            currentCoroutineContext().ensureActive()
            if (!lightningRequestGate.accepts(requestGeneration)) {
                OverlayAcquisitionDiagnostics.trace(
                    "lightning", "EUMETSAT", targetHash, requestGeneration,
                    if (force) "refresh" else "selection", OverlayAcquisitionPhase.READY,
                    requestStartedAt, terminalResult = "stale_success",
                    cacheFallback = retained != null, accepted = false,
                )
                return@LaunchedEffect
            }
            lightningStatus = loaded
            lightningVerifiedPlaceKey = CloudsSwapPolicy.placeKey(resolvedPlace)
            lightningReplacementPhase = WeatherReplacementPhase.IDLE
            OverlayAcquisitionDiagnostics.trace(
                "lightning", "EUMETSAT", targetHash, requestGeneration,
                if (force) "refresh" else "selection", OverlayAcquisitionPhase.READY,
                requestStartedAt, terminalResult = "catalog_ready",
                cacheFallback = retained != null,
            )
        } catch (cancelled: CancellationException) {
            OverlayAcquisitionDiagnostics.trace(
                "lightning", "EUMETSAT", targetHash, requestGeneration,
                if (force) "refresh" else "selection", OverlayAcquisitionPhase.CANCELLED,
                requestStartedAt, terminalResult = "replaced", cacheFallback = retained != null,
                accepted = lightningRequestGate.accepts(requestGeneration),
            )
            throw cancelled
        }
        catch (failure: Exception) {
            Log.w("RainRadarLayers", "LIGHTNING layer unavailable", failure)
            val accepted = lightningRequestGate.accepts(requestGeneration)
            if (accepted) {
                lightningStatus = retained ?: AncillaryStatus.Unavailable("Lightning unavailable")
                lightningReplacementPhase = if (retained == null) {
                    WeatherReplacementPhase.UNAVAILABLE
                } else WeatherReplacementPhase.IDLE
            }
            OverlayAcquisitionDiagnostics.trace(
                "lightning", "EUMETSAT", targetHash, requestGeneration,
                if (force) "refresh" else "selection", OverlayAcquisitionPhase.FAILED,
                requestStartedAt, terminalResult = failure.javaClass.simpleName,
                cacheFallback = retained != null, accepted = accepted,
            )
        } finally {
            if (lightningRequestGate.accepts(requestGeneration)) {
                completedLightningRefresh = requestRefresh
            }
        }
    }
    val cloudsEnabled = RadarMapLayer.FOG in enabledMapLayers
    val preferredCloudProduct = place?.let {
        CloudProductSelectionPolicy.preferred(currentWeather, it, layerNow)
    }
    LaunchedEffect(
        cloudsEnabled, place?.id, regionLatitude, regionLongitude, cloudsRefresh,
        preferredCloudProduct,
    ) {
        val requestGeneration = cloudsRequestGate.begin()
        val requestStartedAt = OverlayAcquisitionDiagnostics.nowMillis()
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
        val targetHash = OverlayAcquisitionDiagnostics.targetHash(
            resolvedPlace.id, resolvedPlace.latitude.toBits(), resolvedPlace.longitude.toBits(),
            preferredCloudProduct,
        )
        val retained = (cloudsStatus as? AncillaryStatus.Satellite)?.takeIf {
            CloudsSwapPolicy.canRetainDuringReplacement(
                it.metadata, cloudsVerifiedPlaceKey, resolvedPlace,
            )
        }
        val force = cloudsRefresh != previousCloudsRefresh
        previousCloudsRefresh = cloudsRefresh
        cloudsReplacementPhase = WeatherReplacementPhase.LOADING
        if (retained == null) cloudsStatus = AncillaryStatus.Loading
        OverlayAcquisitionDiagnostics.trace(
            "clouds", "EUMETSAT", targetHash, requestGeneration,
            if (force) "refresh" else "selection", OverlayAcquisitionPhase.REQUEST,
            requestStartedAt, cacheFallback = retained != null,
        )
        try {
            val products = EumetViewRepository.cloudProducts(
                requireNotNull(preferredCloudProduct), resolvedPlace, force,
            )
            val primary = products.firstOrNull { it.product == preferredCloudProduct }
                ?: products.first()
            currentCoroutineContext().ensureActive()
            if (!cloudsRequestGate.accepts(requestGeneration)) {
                OverlayAcquisitionDiagnostics.trace(
                    "clouds", "EUMETSAT", targetHash, requestGeneration,
                    if (force) "refresh" else "selection", OverlayAcquisitionPhase.READY,
                    requestStartedAt, terminalResult = "stale_success",
                    cacheFallback = retained != null, accepted = false,
                )
                return@LaunchedEffect
            }
            cloudsStatus = AncillaryStatus.Satellite(primary, products)
            cloudsVerifiedPlaceKey = CloudsSwapPolicy.placeKey(resolvedPlace)
            cloudsReplacementPhase = WeatherReplacementPhase.IDLE
            OverlayAcquisitionDiagnostics.trace(
                "clouds", "EUMETSAT", targetHash, requestGeneration,
                if (force) "refresh" else "selection", OverlayAcquisitionPhase.READY,
                requestStartedAt, terminalResult = "catalog_ready",
                cacheFallback = retained != null,
            )
        } catch (cancelled: CancellationException) {
            OverlayAcquisitionDiagnostics.trace(
                "clouds", "EUMETSAT", targetHash, requestGeneration,
                if (force) "refresh" else "selection", OverlayAcquisitionPhase.CANCELLED,
                requestStartedAt, terminalResult = "replaced", cacheFallback = retained != null,
                accepted = cloudsRequestGate.accepts(requestGeneration),
            )
            throw cancelled
        }
        catch (failure: Exception) {
            Log.w("RainRadarLayers", "Clouds layer unavailable", failure)
            val accepted = cloudsRequestGate.accepts(requestGeneration)
            if (accepted) {
                cloudsStatus = retained ?: AncillaryStatus.Unavailable("Clouds unavailable")
                cloudsReplacementPhase = if (retained == null) {
                    WeatherReplacementPhase.UNAVAILABLE
                } else WeatherReplacementPhase.IDLE
            }
            OverlayAcquisitionDiagnostics.trace(
                "clouds", "EUMETSAT", targetHash, requestGeneration,
                if (force) "refresh" else "selection", OverlayAcquisitionPhase.FAILED,
                requestStartedAt, terminalResult = failure.javaClass.simpleName,
                cacheFallback = retained != null, accepted = accepted,
            )
        } finally {
            if (cloudsRequestGate.accepts(requestGeneration)) {
                completedCloudsRefresh = requestRefresh
            }
        }
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
            refreshCoordinator.pause(Instant.now().epochSecond)
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
            refreshCoordinator.pause(Instant.now().epochSecond)
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
    val displayedMapPlace = place ?: session?.place?.takeIf { currentSelected }
        ?: featureTourScenario?.fallbackPlace
    val livePresentationPlace = if (followLive && liveMapPlace != null && travelDisplayTarget != null) {
        liveMapPlace.copy(
            latitude = travelDisplayTarget.latitude,
            longitude = travelDisplayTarget.longitude,
        )
    } else liveMapPlace
    LaunchedEffect(followLive, travelDisplayTarget) {
        val target = travelDisplayTarget ?: return@LaunchedEffect
        if (!followLive) return@LaunchedEffect
        LocationCadenceDiagnostics.record(
            "ui",
            target.source,
            target.displayElapsedRealtimeNanos,
            if (target.projected) "projected-${target.motionSource.name.lowercase()}"
            else "measured-${target.motionSource.name.lowercase()}",
        )
    }
    val sessionReusable = session != null && displayedMapPlace != null && when {
        place != null -> RadarLiveSessionPolicy.canReuse(
            session!!, place, installedRadarAnchor ?: session!!.place,
        )
        else -> currentSelected
    }
    val showInteractiveMap = featureTourScenario != null || RadarBaseMapPresentationPolicy.showInteractiveMap(
        hasResolvedPlace = place != null,
        hasRetainedCurrentCoordinates = place == null && currentSelected && displayedMapPlace != null,
    )
    LaunchedEffect(
        showInteractiveMap,
        currentSelected,
        place?.id,
        displayedMapPlace?.id,
        followLive,
        travelNoticeVisible,
        entryTransition.generation,
        entryTransition.phase,
    ) {
        Log.i(
            "RainRadarRender",
            "event=radar_screen_state interactive=$showInteractiveMap current=$currentSelected " +
                "resolved=${place != null} retained=${place == null && displayedMapPlace != null} " +
                "follow=$followLive notice=$travelNoticeVisible entry=${entryTransition.generation}:" +
                entryTransition.phase.name,
        )
    }
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
    LaunchedEffect(temporaryLightningNoticePending) {
        if (!temporaryLightningNoticePending) return@LaunchedEffect
        delay(2_000)
        onTemporaryLightningNoticeConsumed()
    }
    // Pass a stable State holder so expiry only invalidates the bottom-right notice rail. A
    // transient notice must not restart the native MapLibre/TextureView composition.
    val temporaryLightningNoticeState = rememberUpdatedState(temporaryLightningNoticePending)
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
            radarPreparing = false
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
    var radarMapBounds by remember { mutableStateOf(Rect.Zero) }
    LaunchedEffect(radarMapBounds, screenActive) {
        // The stationary overlay is owned by the app root, so publish root coordinates. Keeping
        // these as Radar-local coordinates displaced the hit strips from the visible map whenever
        // Scaffold/system-bar padding was present.
        onPageSwipeBoundsChanged(if (screenActive) radarMapBounds else Rect.Zero)
    }
    BoxWithConstraints(
        Modifier.fillMaxSize(),
    ) {
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
                        contentDescription = if (canCenterSelected) stringResource(
                            R.string.radar_center_selected,
                            place!!.name,
                        ) else stringResource(R.string.radar_center_unavailable))
                }
                Spacer(Modifier.width(NowHeaderLayoutPolicy.gapDp.dp))
                Text(place?.name ?: stringResource(R.string.current_location),
                    fontSize = NowHeaderLayoutPolicy.titleFontSizeSp(compactTitle).sp,
                    lineHeight = NowHeaderLayoutPolicy.titleLineHeightSp(compactTitle).sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = maxTitleWidth)
                        .graphicsLayer {
                            val scale = RadarEntryFocusPolicy.titleScale(entryFocusProgress.value)
                            scaleX = scale
                            scaleY = scale
                        }
                        .semantics { heading() })
                Spacer(Modifier.width(NowHeaderLayoutPolicy.gapDp.dp))
                PlaceSwitcher(
                    places,
                    locationState,
                    selectPlace,
                    useCurrentLocation = { requestCurrentLocation(false) },
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        when {
            showInteractiveMap -> RadarPlayer(
                session = session?.takeIf { sessionReusable },
                mapPlace = requireNotNull(displayedMapPlace),
                markerPlace = RadarLiveMapPolicy.marker(displayedMapPlace, livePresentationPlace),
                hasFreshLiveFix = RadarLiveMapPolicy.canFollow(selectedPlaceId, liveMapPlace),
                followLive = followLive,
                travelTimelineActivationToken = travelTimelineActivationToken,
                followCapability = followCapability,
                liveFixElapsedRealtimeNanos = travelDisplayTarget
                    ?.takeIf { followLive }
                    ?.displayElapsedRealtimeNanos
                    ?: liveFixElapsedRealtimeNanos,
                liveTargetProjected = followLive && travelDisplayTarget?.projected == true,
                onFollowLiveChange = { enabled ->
                    if (enabled) requestTravelMode() else onFollowLiveChange(false)
                },
                onTravelMapGesture = onTravelMapGesture,
                selectedPlaceId = selectedPlaceId,
                chartTimeRequest = chartTimeRequest,
                onChartTimeConsumed = onChartTimeConsumed,
                playbackSpeed = playbackSpeed,
                mapStyle = mapStyle,
                coverageMaskDarkness = coverageMaskDarkness,
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
                travelNoticeActivationToken = travelNoticeActivationToken,
                temporaryLightningNoticePending = temporaryLightningNoticeState,
                lightningControlState = lightningControlState,
                onLightningControlTap = onLightningControlTap,
                currentWeather = currentWeather,
                onWindViewportChanged = { windViewport = it },
                onRefresh = performManualRefresh,
                onRendererRecovery = { rendererRecoveryGeneration++ },
                onLayerError = { layer, message ->
                    when (layer) {
                        RadarMapLayer.LIGHTNING -> {
                            if (lightningStatus !is AncillaryStatus.Satellite) {
                                lightningReplacementPhase = WeatherReplacementPhase.UNAVAILABLE
                                lightningStatus = AncillaryStatus.Unavailable(message)
                            } else {
                                lightningReplacementPhase = WeatherReplacementPhase.IDLE
                            }
                        }
                        RadarMapLayer.FOG -> {
                            if (cloudsStatus !is AncillaryStatus.Satellite) {
                                cloudsReplacementPhase = WeatherReplacementPhase.UNAVAILABLE
                                cloudsStatus = AncillaryStatus.Unavailable(message)
                            } else {
                                cloudsReplacementPhase = WeatherReplacementPhase.IDLE
                            }
                        }
                        else -> Unit
                    }
                },
                refreshing = refreshing,
                refreshProgress = progress,
                refreshPreparing = radarPreparing,
                refreshError = error,
                rendererRecoveryGeneration = rendererRecoveryGeneration,
                externalNotice = providerMapNotice,
                entryTransition = entryTransition,
                entryFocusEnabled = entryFocusEnabled,
                entryFocusDurationMillis = RadarEntryFocusPolicy.mapDurationMillis(platformAnimatorScale),
                onEntryAnimationStart = { generation, animate ->
                    if (generation == entryTransition.generation) {
                        nativeEntryStart = RadarEntryStartSignal(generation, animate)
                    }
                },
                markerScale = maxOf(
                    RadarEntryFocusPolicy.markerScale(entryFocusProgress.value),
                    RadarEntryFocusPolicy.markerScale(markerFocusProgress.value),
                ),
                onMapBoundsChanged = { radarMapBounds = it },
                featureTourScenario = featureTourScenario,
                onFeatureTourTarget = onFeatureTourTarget,
            )
            else -> RadarLoadingShell(
                mapStyle = mapStyle,
                progress = progress,
                preparing = radarPreparing,
                locationState = locationState,
                currentSelected = currentSelected,
                onCurrentLocation = { requestCurrentLocation(true) },
                onRefresh = performManualRefresh,
                notice = providerMapNotice,
                locationStatus = locationOperationalStatus,
                radarLoading = place != null && error == null,
                travelMode = followLive,
                travelActivationStatus = when {
                    temporaryLightningNoticePending -> {
                        val label = stringResource(R.string.radar_lightning_temporary_notice)
                        RadarRefreshOverlay(label, label)
                    }
                    travelNoticeVisible -> {
                        val label = stringResource(R.string.radar_travel_mode)
                        RadarRefreshOverlay(label, label)
                    }
                    else -> null
                },
                onTravelMode = requestTravelMode,
                onMapBoundsChanged = { radarMapBounds = it },
            )
        }
    }
    RadarPageGestureExclusions(radarMapBounds, screenActive)
    }

    longPressed?.let { point ->
        val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
        val placeNameRepository = remember { PlaceNameSuggestionRepository() }
        val suggestedName = remember(point) {
            PlacePickerNamePolicy.coordinateFallback(point.latitude, point.longitude)
        }
        var name by remember(point) {
            mutableStateOf(suggestedName)
        }
        var nameEdited by remember(point) { mutableStateOf(false) }
        var nameRequestToken by remember(point) { mutableIntStateOf(1) }
        var resolvingName by remember(point) { mutableStateOf(true) }
        var duplicateErrorToken by remember(point) { mutableIntStateOf(0) }
        LaunchedEffect(point, locale.language, nameRequestToken) {
            val requestToken = nameRequestToken
            val resolved = placeNameRepository.suggestedName(
                point.latitude, point.longitude, locale.language,
            )
            if (PlacePickerNamePolicy.shouldApplyResult(
                    requestToken, nameRequestToken, nameEdited,
                )) name = resolved
            if (requestToken == nameRequestToken) resolvingName = false
        }
        LaunchedEffect(point, duplicateErrorToken) {
            if (duplicateErrorToken == 0) return@LaunchedEffect
            delay(PlaceDuplicateFeedbackPolicy.DURATION_MILLIS)
            duplicateErrorToken = 0
        }
        AlertDialog(
            onDismissRequest = { longPressed = null },
            title = { Text(stringResource(R.string.radar_save_place)) },
            text = {
                Column {
                    if (resolvingName) Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            stringResource(R.string.picker_resolving_name),
                            color = Secondary,
                            modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
                        )
                    }
                    PlaceNameEditor(
                        value = name,
                        onValueChange = {
                            name = it
                            nameEdited = true
                            duplicateErrorToken = 0
                        },
                        duplicate = duplicateErrorToken != 0,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = PlaceCollectionRules.validName(name),
                    onClick = {
                        val incoming = SavedPlace(name.trim(), point.latitude, point.longitude)
                        if (places.places.any { PlaceCoordinatePolicy.nearDuplicate(it, incoming) }) {
                            duplicateErrorToken++
                        }
                        else {
                            val nextToken = markerFocusToken + 1
                            markerFocusToken = nextToken
                            markerFocusRequest = RadarMarkerFocusRequest(
                                token = nextToken,
                                previousPlaceId = place?.id,
                                targetPlaceId = incoming.id,
                            )
                            markerFocusScope.launch {
                                markerFocusProgress.snapTo(if (animationsEnabled) 0f else 1f)
                            }
                            saveAndSelect(incoming)
                            longPressed = null
                        }
                    },
                ) { Text(stringResource(R.string.radar_save_select)) }
            },
            dismissButton = {
                TextButton(onClick = { longPressed = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun ColumnScope.RadarLoadingShell(
    mapStyle: RadarMapStyle,
    progress: Pair<Int, Int>,
    preparing: Boolean,
    locationState: LocationUiState,
    currentSelected: Boolean,
    onCurrentLocation: () -> Unit,
    onRefresh: () -> Unit,
    notice: RadarMapNotice?,
    locationStatus: RadarRefreshOverlay?,
    radarLoading: Boolean,
    travelMode: Boolean,
    travelActivationStatus: RadarRefreshOverlay?,
    onTravelMode: () -> Unit,
    onMapBoundsChanged: (Rect) -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    val status = RadarRefreshOverlayPolicy.initialLoading(
        progress.first, progress.second, preparing,
    )
    val darkMap = mapStyle.darkControls
    val useLocationDescription = stringResource(R.string.radar_use_location)
    val locationLoadingDescription = localizedWeatherStatus(
        WeatherDataStatusPolicy.loading(WeatherDataKind.LOCATION),
    )
    Card(
        colors = CardDefaults.cardColors(
            containerColor = Color(RadarMapAppearance.loadingBackgroundArgb(mapStyle)),
        ),
        shape = shape,
        modifier = Modifier.fillMaxWidth().weight(1f)
            .border(1.dp, LocalRainAlarmPalette.current.border, shape)
            .onGloballyPositioned { onMapBoundsChanged(it.boundsInRoot()) },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val mapWidthDp = maxWidth.value.toInt()
            val operationalEntries = buildList {
                locationStatus?.let(::add)
                status.takeIf { radarLoading }?.let(::add)
                if (size < RadarPreparationStackPolicy.maximumEntries) {
                    travelActivationStatus?.let(::add)
                }
            }
            Row(
                Modifier.align(Alignment.TopEnd).padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onTravelMode,
                    modifier = Modifier.size(RadarTopControlsPolicy.controlSizeDp.dp)) {
                    Icon(
                        Icons.Default.Navigation,
                        contentDescription = stringResource(
                            if (travelMode) R.string.radar_follow_stop else R.string.radar_follow_start,
                        ),
                        tint = if (travelMode) Accent.copy(alpha = 0.85f)
                            else if (darkMap) Color.White else Color.Black,
                    )
                }
                IconButton(onClick = onRefresh) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.radar_refresh),
                        tint = if (darkMap) Color.White else Color.Black,
                    )
                }
                IconButton(
                    onClick = onCurrentLocation,
                    enabled = locationState !is LocationUiState.Locating,
                    modifier = Modifier.size(RadarTopControlsPolicy.controlSizeDp.dp)
                        .clearAndSetSemantics {
                            contentDescription = if (locationState is LocationUiState.Locating && currentSelected)
                                locationLoadingDescription
                            else useLocationDescription
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
                modifier = Modifier.align(Alignment.BottomEnd).padding(
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
    operationalEntries: List<RadarRefreshOverlay>,
    mapWidthDp: Int,
    travelNoticeActivationToken: Int = 0,
    temporaryLightningNoticePending: State<Boolean>? = null,
    automaticCurrentTimeUnavailable: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var travelNoticeVisible by remember { mutableStateOf(false) }
    LaunchedEffect(travelNoticeActivationToken) {
        if (travelNoticeActivationToken <= 0) return@LaunchedEffect
        travelNoticeVisible = true
        delay(TravelActivationNoticePolicy.DURATION_MILLIS)
        travelNoticeVisible = false
    }
    val information = when {
        temporaryLightningNoticePending?.value == true -> {
            val label = stringResource(R.string.radar_lightning_temporary_notice)
            RadarRefreshOverlay(label, label)
        }
        travelNoticeVisible -> {
            val label = stringResource(R.string.radar_travel_mode)
            RadarRefreshOverlay(label, label)
        }
        automaticCurrentTimeUnavailable -> {
            val label = stringResource(R.string.radar_current_time_unavailable)
            RadarRefreshOverlay(label, label)
        }
        else -> null
    }
    val entries = if (information == null ||
        operationalEntries.size >= RadarPreparationStackPolicy.maximumEntries
    ) operationalEntries else operationalEntries + information
    if (entries.isEmpty()) return
    Column(
        modifier = modifier.widthIn(max = RadarPreparationStackPolicy.maxWidthDp(mapWidthDp).dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(RadarPreparationStackPolicy.rowSpacingDp.dp),
    ) {
        entries.forEach { status ->
            val localizedLabel = localizedWeatherStatus(status.label)
            val localizedAccessibilityLabel = localizedWeatherStatus(status.accessibilityLabel)
            Text(
                localizedLabel,
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
                    .semantics { contentDescription = localizedAccessibilityLabel },
            )
        }
    }
}

private data class RadarNativeMapInputs(
    val session: RadarSession?,
    val bracket: RadarTimelineBracket?,
    val mapPlace: SavedPlace,
    val markerPlace: SavedPlace,
    val followLive: Boolean,
    val liveFixElapsedRealtimeNanos: Long,
    val liveTargetProjected: Boolean,
    val onManualCameraGesture: () -> Unit,
    val onLongPress: (GeoPoint) -> Unit,
    val recenterSignal: Int,
    val cameraMemory: RadarCameraMemory,
    val isPlaying: Boolean,
    val radarPresentationVisible: Boolean,
    val onRendererStatus: (RadarRendererStatus) -> Unit,
    val mapStyle: RadarMapStyle,
    val coverageMaskDarkness: Float,
    val windGrid: WindGrid?,
    val windArrowScale: Float,
    val windRenderToken: WindGridRenderToken?,
    val onWindRenderObservation: (WindGridRenderToken, Int) -> Unit,
    val onWindRendererReset: () -> Unit,
    val satelliteCatalogs: List<EumetLayerMetadata>,
    val enabledSatelliteLayers: Set<RadarMapLayer>,
    val satelliteDisplayEpochSeconds: Long,
    val satelliteTimelineStartEpochSeconds: Long,
    val satelliteTimelineEndEpochSeconds: Long,
    val satelliteWeather: CurrentWeather?,
    val onSatellitePreparation: (RadarMapLayer, SatellitePreparationStatus?) -> Unit,
    val onLayerError: (RadarMapLayer, String) -> Unit,
    val onMapStyleError: (String?) -> Unit,
    val rendererRecoveryGeneration: Int,
    val onMapPreparing: (Boolean) -> Unit,
    val onWindViewportChanged: (WindViewport) -> Unit,
    val onRadarTierChanged: (RadarResolutionTier) -> Unit,
    val entryTransition: EntryTransitionState,
    val entryFocusEnabled: Boolean,
    val entryFocusDurationMillis: Int,
    val onEntryAnimationStart: (Int, Boolean) -> Unit,
    val markerScale: Float,
)

@Composable
private fun RadarNativeMap(inputs: RadarNativeMapInputs) {
    RadarImageMap(
        inputs.session,
        inputs.bracket,
        inputs.mapPlace,
        markerPlace = inputs.markerPlace,
        followLive = inputs.followLive,
        liveFixElapsedRealtimeNanos = inputs.liveFixElapsedRealtimeNanos,
        liveTargetProjected = inputs.liveTargetProjected,
        onManualCameraGesture = inputs.onManualCameraGesture,
        onLongPress = inputs.onLongPress,
        recenterSignal = inputs.recenterSignal,
        cameraMemory = inputs.cameraMemory,
        isPlaying = inputs.isPlaying,
        radarPresentationVisible = inputs.radarPresentationVisible,
        onRendererStatus = inputs.onRendererStatus,
        mapStyle = inputs.mapStyle,
        coverageMaskDarkness = inputs.coverageMaskDarkness,
        windGrid = inputs.windGrid,
        windArrowScale = inputs.windArrowScale,
        windRenderToken = inputs.windRenderToken,
        onWindRenderObservation = inputs.onWindRenderObservation,
        onWindRendererReset = inputs.onWindRendererReset,
        satelliteCatalogs = inputs.satelliteCatalogs,
        enabledSatelliteLayers = inputs.enabledSatelliteLayers,
        satelliteDisplayEpochSeconds = inputs.satelliteDisplayEpochSeconds,
        satelliteTimelineStartEpochSeconds = inputs.satelliteTimelineStartEpochSeconds,
        satelliteTimelineEndEpochSeconds = inputs.satelliteTimelineEndEpochSeconds,
        satelliteWeather = inputs.satelliteWeather,
        onSatellitePreparation = inputs.onSatellitePreparation,
        onLayerError = inputs.onLayerError,
        onMapStyleError = inputs.onMapStyleError,
        rendererRecoveryGeneration = inputs.rendererRecoveryGeneration,
        onMapPreparing = inputs.onMapPreparing,
        onWindViewportChanged = inputs.onWindViewportChanged,
        onRadarTierChanged = inputs.onRadarTierChanged,
        entryTransition = inputs.entryTransition,
        entryFocusEnabled = inputs.entryFocusEnabled,
        entryFocusDurationMillis = inputs.entryFocusDurationMillis,
        onEntryAnimationStart = inputs.onEntryAnimationStart,
        markerScale = inputs.markerScale,
    )
}

@Composable
private fun ColumnScope.RadarPlayer(
    session: RadarSession?,
    mapPlace: SavedPlace,
    markerPlace: SavedPlace,
    hasFreshLiveFix: Boolean,
    followLive: Boolean,
    travelTimelineActivationToken: Int,
    followCapability: FollowUiCapability,
    liveFixElapsedRealtimeNanos: Long,
    liveTargetProjected: Boolean,
    onFollowLiveChange: (Boolean) -> Unit,
    onTravelMapGesture: () -> Unit,
    selectedPlaceId: String,
    chartTimeRequest: RadarChartTimeRequest?,
    onChartTimeConsumed: (Int) -> Unit,
    playbackSpeed: RadarPlaybackSpeed,
    mapStyle: RadarMapStyle,
    coverageMaskDarkness: Float,
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
    lightningControlState: RadarLightningControlState,
    onLightningControlTap: () -> Unit,
    ancillaryStatuses: Map<RadarMapLayer, AncillaryStatus>,
    weatherStatuses: Map<RadarMapLayer, AncillaryStatus>,
    locationStatus: RadarRefreshOverlay?,
    travelNoticeActivationToken: Int,
    temporaryLightningNoticePending: State<Boolean>,
    currentWeather: CurrentWeather?,
    onWindViewportChanged: (WindViewport) -> Unit,
    onRefresh: () -> Unit,
    onRendererRecovery: () -> Unit,
    onLayerError: (RadarMapLayer, String) -> Unit,
    refreshing: Boolean,
    refreshProgress: Pair<Int, Int>,
    refreshPreparing: Boolean,
    refreshError: String?,
    rendererRecoveryGeneration: Int,
    externalNotice: RadarMapNotice?,
    entryTransition: EntryTransitionState,
    entryFocusEnabled: Boolean,
    entryFocusDurationMillis: Int,
    onEntryAnimationStart: (Int, Boolean) -> Unit,
    markerScale: Float,
    onMapBoundsChanged: (Rect) -> Unit,
    featureTourScenario: FeatureTourScenario? = null,
    onFeatureTourTarget: (FeatureTourTargetBounds) -> Unit = {},
) {
    val playerCompositionIdentity = remember { Any() }
    DisposableEffect(playerCompositionIdentity) {
        Log.i(
            "RainRadarRender",
            "event=radar_player_compose identity=${System.identityHashCode(playerCompositionIdentity)}",
        )
        onDispose {
            Log.i(
                "RainRadarRender",
                "event=radar_player_dispose identity=${System.identityHashCode(playerCompositionIdentity)}",
            )
        }
    }
    val context = LocalContext.current
    val darkMap = mapStyle.darkControls
    val secondaryColor = Secondary
    val timelineDescription = stringResource(R.string.radar_timeline)
    val travelAutoAccessibility = stringResource(R.string.radar_travel_auto_accessibility)
    val sessionTimes = remember(session) { session?.timelineFrames?.map { it.time }.orEmpty() }
    val sessionForecastFlags = remember(session) {
        session?.timelineFrames?.map { it.forecast }.orEmpty()
    }
    val times = featureTourScenario?.radarFrames?.map { it.epochSeconds } ?: sessionTimes
    val forecastFlags = featureTourScenario?.radarFrames?.mapIndexed { index, _ -> index >= 2 }
        ?: sessionForecastFlags
    val hasRadarSession = times.isNotEmpty() && (session != null || featureTourScenario != null)
    val hasRealRadarSession = session != null && sessionTimes.isNotEmpty()
    val loadingEpochSeconds = remember(mapPlace.id) {
        Math.floorDiv(Instant.now().epochSecond, 60L) * 60L
    }
    val timelineStartEpochSeconds = times.firstOrNull() ?: loadingEpochSeconds
    val latestObservationIndex = forecastFlags.indexOfLast { !it }.coerceAtLeast(0)
    val latestOffset = if (hasRadarSession) {
        (times[latestObservationIndex] - times.first()).toFloat()
    } else 0f
    val providerForecast = forecastFlags.any { it }
    val sessionProviderForecast = sessionForecastFlags.any { it }
    var displayedRadarTier by remember(session) {
        mutableStateOf(if (session?.regional != null || session?.legacyArchive != null) {
            RadarResolutionTier.REGIONAL
        } else RadarResolutionTier.DETAIL)
    }
    val preferredOpenTier = displayedRadarTier.takeIf { session?.tier(it) != null }
        ?: if (session?.detail != null) RadarResolutionTier.DETAIL else RadarResolutionTier.REGIONAL
    val estimatedForecast = session != null && !sessionProviderForecast &&
        session.velocity(preferredOpenTier)?.futureField != null
    val realForecastAvailable = sessionProviderForecast || estimatedForecast
    val forecastAvailable = if (featureTourScenario != null) providerForecast else realForecastAvailable
    val endOffset = if (!hasRadarSession) {
        RadarTimeline.FORECAST_HORIZON_SECONDS.toFloat()
    } else if (providerForecast) {
        (times.last() - times.first()).toFloat()
    } else {
        (times.last() - times.first() + if (estimatedForecast) RadarTimeline.FORECAST_HORIZON_SECONDS else 0L).toFloat()
    }
    val realLatestObservationIndex = sessionForecastFlags.indexOfLast { !it }.coerceAtLeast(0)
    val realLatestOffset = if (hasRealRadarSession) {
        (sessionTimes[realLatestObservationIndex] - sessionTimes.first()).toFloat()
    } else 0f
    val realEndOffset = if (!hasRealRadarSession) {
        RadarTimeline.FORECAST_HORIZON_SECONDS.toFloat()
    } else if (sessionProviderForecast) {
        (sessionTimes.last() - sessionTimes.first()).toFloat()
    } else {
        (sessionTimes.last() - sessionTimes.first() +
            if (estimatedForecast) RadarTimeline.FORECAST_HORIZON_SECONDS else 0L).toFloat()
    }
    val initialCursor = remember(session) {
        if (!hasRealRadarSession) 0f else RadarEntryClock.initialCursor(
            sessionTimes.first(), sessionTimes[realLatestObservationIndex],
            sessionTimes.first() + realEndOffset.toLong(),
            realForecastAvailable, Instant.now().epochSecond,
        )
    }
    val chartDecision = if (!hasRealRadarSession) null else chartTimeRequest?.let {
        RadarChartTimeLink.decide(it, selectedPlaceId, requireNotNull(session).place.id, sessionTimes.first(),
            sessionTimes.first() + realEndOffset.toDouble())
    }
    var cursor by remember(session) { mutableFloatStateOf(
        (chartDecision as? RadarChartTimeDecision.Apply)?.cursorSeconds ?: initialCursor,
    ) }
    val playbackRefreshIdentity = RadarPlaybackRefreshPolicy.identity(
        session,
        session?.providerSelection?.requested,
    )
    var playing by remember(playbackRefreshIdentity) { mutableStateOf(false) }
    var travelTimeAutomatic by remember { mutableStateOf(false) }
    var travelWallClockEpochSeconds by remember { mutableStateOf(Instant.now().epochSecond) }
    var tourCursor by remember(featureTourScenario) { mutableFloatStateOf(0f) }
    var tourPlaying by remember(featureTourScenario) { mutableStateOf(false) }
    var chartTimeMessage by remember(session) { mutableStateOf<String?>(null) }
    val chartTimeUnavailableMessage = stringResource(R.string.radar_chart_time_unavailable)
    LaunchedEffect(session, chartTimeRequest?.token, chartDecision) {
        val request = chartTimeRequest ?: return@LaunchedEffect
        when (val decision = chartDecision) {
            is RadarChartTimeDecision.Apply -> {
                travelTimeAutomatic = false
                cursor = decision.cursorSeconds
                playing = false
                chartTimeMessage = null
            }
            RadarChartTimeDecision.Unavailable -> {
                chartTimeMessage = chartTimeUnavailableMessage
                onChartTimeConsumed(request.token)
            }
            else -> Unit
        }
    }
    LaunchedEffect(followLive, travelTimelineActivationToken) {
        if (followLive) {
            travelTimeAutomatic = true
            playing = false
            travelWallClockEpochSeconds = Instant.now().epochSecond
        } else {
            travelTimeAutomatic = false
        }
    }
    LaunchedEffect(travelTimeAutomatic) {
        while (travelTimeAutomatic) {
            travelWallClockEpochSeconds = Instant.now().epochSecond
            delay(1_000L)
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
    var mapPreparing by remember(mapStyle, rendererRecoveryGeneration) { mutableStateOf(true) }
    var satellitePreparation by remember {
        mutableStateOf<Map<RadarMapLayer, SatellitePreparationStatus>>(emptyMap())
    }
    val travelTimelineFrame = if (travelTimeAutomatic && hasRealRadarSession) {
        RadarTravelTimelinePolicy.frame(
            nowEpochSeconds = travelWallClockEpochSeconds,
            dataStartEpochSeconds = sessionTimes.first(),
            latestObservationEpochSeconds = sessionTimes[realLatestObservationIndex],
            dataEndEpochSeconds = sessionTimes.first() + realEndOffset.toLong(),
            forecastAvailable = realForecastAvailable,
        )
    } else null
    val activeCursor = if (featureTourScenario != null) tourCursor
        else travelTimelineFrame?.dataCursorSeconds ?: cursor
    val displayPlaying = if (featureTourScenario != null) tourPlaying else playing
    val safeCursor = activeCursor.takeIf { it.isFinite() }?.coerceIn(0f, endOffset)
        ?: if (featureTourScenario != null) 0f else initialCursor
    val bracket = if (!hasRadarSession) null else if (providerForecast) {
        RadarTimeline.bracket(times, forecastFlags, times.first() + safeCursor.toDouble())
    } else {
        RadarTimeline.bracket(times, times.first() + safeCursor.toDouble())
    }
    val realCursor = travelTimelineFrame?.dataCursorSeconds ?: cursor
    val realSafeCursor = realCursor.takeIf { it.isFinite() }?.coerceIn(0f, realEndOffset) ?: initialCursor
    val realBracket = if (!hasRealRadarSession) null else if (sessionProviderForecast) {
        RadarTimeline.bracket(
            sessionTimes,
            sessionForecastFlags,
            sessionTimes.first() + realSafeCursor.toDouble(),
        )
    } else {
        RadarTimeline.bracket(sessionTimes, sessionTimes.first() + realSafeCursor.toDouble())
    }

    LaunchedEffect(displayPlaying, session, featureTourScenario, playbackSpeed) {
        if (!displayPlaying || !hasRadarSession) return@LaunchedEffect
        var lastNanos = withFrameNanos { it }
        while (if (featureTourScenario != null) tourPlaying else playing) {
            val nanos = withFrameNanos { it }
            val elapsedSeconds = (nanos - lastNanos) / 1_000_000_000.0
            lastNanos = nanos
            val stopAt = if (forecastAvailable) endOffset else latestOffset
            val rawCursor = if (featureTourScenario != null) tourCursor else cursor
            val currentCursor = rawCursor.takeIf { it.isFinite() }?.coerceIn(0f, stopAt) ?: latestOffset
            val advanced = RadarPlaybackClock.advance(
                currentCursor, elapsedSeconds, stopAt, playbackSpeed.multiplier,
            )
            if (featureTourScenario != null) tourCursor = advanced else cursor = advanced
        }
    }
    val travelClock = if (travelTimeAutomatic) RadarTravelClockPolicy.snapshotAt(
        epochSeconds = travelWallClockEpochSeconds,
        zoneId = ZoneId.systemDefault(),
        use24Hour = DateFormat.is24HourFormat(context),
        locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0],
    ) else null
    val label = if (featureTourScenario != null) {
        val time = Instant.ofEpochSecond((times.first() + safeCursor.toLong()))
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"))
        "${featureTourScenario.forecast.sourceLabel} · $time"
    } else if (travelClock != null) {
        stringResource(
            if (travelTimelineFrame?.wallClockCovered == true) R.string.radar_forecast_time
            else R.string.radar_forecast_unavailable_time,
            travelClock.localMinuteText,
        )
    } else bracket?.let {
        timelineLabel(times, it, safeCursor, latestOffset, forecastAvailable)
    } ?: stringResource(R.string.radar_loading)
    val refreshOverlay = if (!hasRadarSession && refreshError.isNullOrBlank()) {
        RadarRefreshOverlayPolicy.initialLoading(
            refreshProgress.first, refreshProgress.second, refreshPreparing,
        )
    } else RadarRefreshOverlayPolicy.status(
        hasSession = hasRadarSession, refreshing = refreshing,
        completed = refreshProgress.first, total = refreshProgress.second, error = refreshError,
        preparing = refreshPreparing,
    )
    val mapRenderOverlay = if (mapPreparing) {
        val preparing = WeatherDataStatusPolicy.preparing(WeatherDataKind.RADAR)
        RadarRefreshOverlay(preparing, preparing)
    } else null
    val preparationEntries = RadarPreparationStackPolicy.entries(
        refreshOverlay, enabledMapLayers, weatherStatuses, satellitePreparation, locationStatus,
        information = null,
        rendering = mapRenderOverlay,
    )
    val automaticCurrentTimeUnavailable = travelTimeAutomatic &&
        travelTimelineFrame?.wallClockCovered == false
    val mapNotice = RadarMapNoticePolicy.select(
        (rendererStatus as? RadarRendererStatus.Error)?.let {
            RadarMapNotice(
                RadarMapNoticeKind.RENDERER_FAILURE,
                stringResource(R.string.radar_renderer_unavailable),
            )
        },
        mapStyleError?.let {
            RadarMapNotice(RadarMapNoticeKind.MAP_STYLE, stringResource(R.string.radar_map_unavailable))
        },
        externalNotice,
        compatibilityNotice?.let {
            RadarMapNotice(
                RadarMapNoticeKind.RENDERER_COMPATIBILITY,
                stringResource(R.string.radar_renderer_recovered),
            )
        },
        chartTimeMessage?.let { RadarMapNotice(RadarMapNoticeKind.CHART_TIME, it) },
    )
    val refreshMapAndData = {
        if (RadarManualRefreshPolicy.shouldRecoverRenderer(rendererStatus, mapStyleError)) {
            onRendererRecovery()
        }
        onRefresh()
    }
    val currentNativeMapInputs = rememberUpdatedState(RadarNativeMapInputs(
        session = session,
        bracket = realBracket,
        mapPlace = mapPlace,
        markerPlace = markerPlace,
        followLive = RadarLiveMapPolicy.shouldCenterOnFix(
            selectedPlaceId, if (hasFreshLiveFix) markerPlace else null, followLive,
        ),
        liveFixElapsedRealtimeNanos = liveFixElapsedRealtimeNanos,
        liveTargetProjected = liveTargetProjected,
        onManualCameraGesture = { if (followLive) onTravelMapGesture() },
        onLongPress = onLongPress,
        recenterSignal = currentRecenterTick,
        cameraMemory = cameraMemory,
        isPlaying = playing,
        radarPresentationVisible = featureTourScenario == null,
        onRendererStatus = { rendererStatus = it },
        mapStyle = mapStyle,
        coverageMaskDarkness = coverageMaskDarkness,
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
        rendererRecoveryGeneration = rendererRecoveryGeneration,
        onMapPreparing = { mapPreparing = it },
        onWindViewportChanged = onWindViewportChanged,
        onRadarTierChanged = { displayedRadarTier = it },
        entryTransition = entryTransition,
        entryFocusEnabled = entryFocusEnabled,
        entryFocusDurationMillis = entryFocusDurationMillis,
        onEntryAnimationStart = onEntryAnimationStart,
        markerScale = markerScale,
    ))
    Box(
        modifier = Modifier.fillMaxWidth().weight(1f)
            .onGloballyPositioned { onMapBoundsChanged(it.boundsInRoot()) }
            .clip(RoundedCornerShape(22.dp))
            .background(Surface),
    ) {
            // Keep the stateful native renderer outside the overlay's BoxWithConstraints
            // subcomposition. Travel inserts and removes short-lived overlay/status content; a
            // new constraint-content lambda must not be allowed to discard a healthy MapView.
            key("radar-native-map") { RadarNativeMap(currentNativeMapInputs.value) }
            BoxWithConstraints(Modifier.fillMaxSize()) {
            val mapWidthDp = maxWidth.value.toInt()
            featureTourScenario?.let { scenario ->
                FeatureTourRadarOverlay(
                    scenario = scenario,
                    epochSeconds = times.first() + safeCursor.toDouble(),
                    darkMap = darkMap,
                    modifier = Modifier.fillMaxSize(),
                )
            }
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
                        mapWidthDp, true).dp)
                    .background(LocalRainAlarmPalette.current.mapLabelSurface, RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp),
            )
            Row(Modifier.align(Alignment.TopEnd).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onFollowLiveChange(true) },
                    modifier = Modifier.size(RadarTopControlsPolicy.controlSizeDp.dp)
                        .featureTourTarget(
                            FeatureTourTarget.RADAR_TRAVEL,
                            enabled = featureTourScenario != null,
                            cornerRadiusDp = 24f,
                            paddingDp = 2f,
                            onBounds = onFeatureTourTarget,
                        )) {
                    Icon(Icons.Default.Navigation,
                        contentDescription = stringResource(
                            R.string.radar_follow_start,
                        ),
                        tint = if (followLive) Accent.copy(alpha = 0.85f)
                            else if (darkMap) Color.White else Color.Black)
                }
                IconButton(onClick = refreshMapAndData) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.radar_refresh),
                        tint = if (darkMap) Color.White else Color.Black)
                }
                IconButton(onClick = onCurrentLocation,
                    enabled = locationState !is LocationUiState.Locating) {
                    if (locationState is LocationUiState.Locating) CircularProgressIndicator(Modifier.size(22.dp),
                        color = if (darkMap) Color.White else Color.Black, strokeWidth = 2.dp)
                    else Icon(Icons.Default.MyLocation, contentDescription = stringResource(R.string.radar_use_location),
                        tint = if (darkMap) Color.White else Color.Black)
                }
            }
            RadarLayerSegments(
                enabledMapLayers,
                darkMap,
                setMapLayerEnabled,
                lightningControlState,
                onLightningControlTap,
                Modifier.align(Alignment.TopEnd).padding(
                    top = RadarTopControlsPolicy.segmentTopDp.dp,
                    end = RadarTopControlsPolicy.controlsEndDp.dp,
                ).featureTourTarget(
                    FeatureTourTarget.RADAR_LAYERS,
                    enabled = featureTourScenario != null,
                    cornerRadiusDp = 12f,
                    paddingDp = 3f,
                    onBounds = onFeatureTourTarget,
                ),
            )
            RadarLayerStatuses(
                enabledMapLayers, currentWeather, mapWidthDp,
                Modifier.align(Alignment.TopEnd).padding(
                    top = RadarTopControlsPolicy.statusTopDp.dp,
                    end = RadarTopControlsPolicy.statusEndDp.dp))
            RadarOperationalStatusStack(
                preparationEntries,
                mapWidthDp,
                travelNoticeActivationToken = travelNoticeActivationToken,
                temporaryLightningNoticePending = temporaryLightningNoticePending,
                automaticCurrentTimeUnavailable = automaticCurrentTimeUnavailable,
                Modifier.align(Alignment.BottomEnd).padding(
                    end = RadarPreparationStackPolicy.endInsetDp.dp,
                    bottom = RadarPreparationStackPolicy.bottomInsetDp.dp,
                ),
            )
            }
    }
    Column(
        Modifier.fillMaxWidth().featureTourTarget(
            FeatureTourTarget.RADAR_TIMELINE,
            enabled = featureTourScenario != null,
            cornerRadiusDp = 12f,
            paddingDp = 3f,
            onBounds = onFeatureTourTarget,
        ),
    ) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = {
                if (featureTourScenario == null) {
                    TravelModeDiagnostics.record(
                        followLive, followLive, RadarTravelTransitionReason.TIMELINE_MANUAL,
                    )
                    travelTimeAutomatic = false
                    if (!playing) chartTimeRequest?.let { onChartTimeConsumed(it.token) }
                    playing = !playing
                } else {
                    tourPlaying = !tourPlaying
                }
            },
            enabled = hasRadarSession,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                if (displayPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = stringResource(
                    if (displayPlaying) R.string.radar_pause else R.string.radar_play,
                ),
            )
        }
        val timelineSliderValue = travelTimelineFrame?.displayCursorSeconds ?: safeCursor
        Slider(
            value = timelineSliderValue,
            onValueChange = {
                chartTimeRequest?.let { request -> onChartTimeConsumed(request.token) }
                if (featureTourScenario != null) {
                    tourCursor = it.takeIf { value -> value.isFinite() }
                        ?.coerceIn(0f, endOffset) ?: safeCursor
                    tourPlaying = false
                } else {
                    TravelModeDiagnostics.record(
                        followLive, followLive, RadarTravelTransitionReason.TIMELINE_MANUAL,
                    )
                    val finite = it.takeIf { value -> value.isFinite() } ?: timelineSliderValue
                    cursor = travelTimelineFrame?.let { frame ->
                        RadarTravelTimelinePolicy.dataCursorForManualSelection(
                            finite,
                            frame,
                            times.first(),
                            times.first() + endOffset.toLong(),
                        )
                    } ?: finite.coerceIn(0f, endOffset)
                    travelTimeAutomatic = false
                    playing = false
                }
            },
            valueRange = 0f..endOffset,
            enabled = hasRadarSession,
            modifier = Modifier.weight(1f).semantics {
                contentDescription = timelineDescription
                stateDescription = label
            },
        )
        if (travelTimeAutomatic) {
            Text(
                text = stringResource(R.string.radar_travel_auto_badge),
                color = Accent,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier
                    .background(Accent.copy(alpha = 0.14f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 6.dp, vertical = 3.dp)
                    .semantics {
                        contentDescription = travelAutoAccessibility
                    },
            )
        }
    }
    if (hasRadarSession && endOffset > 0f) {
        val configuration = androidx.compose.ui.platform.LocalConfiguration.current
        val use24Hour = DateFormat.is24HourFormat(context)
        val locale = configuration.locales[0]
        val tickStart = travelTimelineFrame?.displayStartEpochSeconds ?: times.first()
        val tickEnd = travelTimelineFrame?.displayEndEpochSeconds ?:
            (times.first() + endOffset.toLong())
        val ticks = remember(
            session,
            featureTourScenario,
            endOffset,
            use24Hour,
            locale,
            tickStart,
            tickEnd,
        ) {
            RadarTimelineTicks.between(
                tickStart,
                tickEnd,
                ZoneId.systemDefault(),
                use24Hour,
                locale,
            )
        }
        // Match the 48dp play target, 6dp row gap and Slider's 10dp thumb inset.
        RadarTimelineTickRow(
            ticks = ticks,
            color = secondaryColor,
            modifier = Modifier.fillMaxWidth().height(21.dp).padding(
                start = 54.dp,
                end = if (travelTimeAutomatic) 44.dp else 0.dp,
            ),
        )
    } else Spacer(Modifier.fillMaxWidth().height(21.dp))
    }
}

@Composable
private fun FeatureTourRadarOverlay(
    scenario: FeatureTourScenario,
    epochSeconds: Double,
    darkMap: Boolean,
    modifier: Modifier = Modifier,
) {
    val progress = scenario.radarMotionProgress(epochSeconds)
    val precipitation = remember(darkMap) { featureTourRadarBitmap(darkMap) }
    Canvas(modifier) {
        val offset = FeatureTourRadarField.motionOffset(progress)
        val padding = FeatureTourRadarField.FIELD_PADDING
        val fieldScale = 1f + padding * 2f
        drawImage(
            image = precipitation,
            dstOffset = androidx.compose.ui.unit.IntOffset(
                (size.width * (offset.xFraction - padding)).toInt(),
                (size.height * (offset.yFraction - padding)).toInt(),
            ),
            dstSize = androidx.compose.ui.unit.IntSize(
                (size.width * fieldScale).toInt().coerceAtLeast(1),
                (size.height * fieldScale).toInt().coerceAtLeast(1),
            ),
            filterQuality = FilterQuality.Medium,
        )
    }
}

private fun featureTourRadarBitmap(darkMap: Boolean): ImageBitmap {
    val intensities = FeatureTourRadarField.generate()
    val pixels = IntArray(intensities.size) { index ->
        featureTourRadarColor(intensities[index], darkMap)
    }
    return Bitmap.createBitmap(
        pixels,
        FeatureTourRadarField.WIDTH,
        FeatureTourRadarField.HEIGHT,
        Bitmap.Config.ARGB_8888,
    ).asImageBitmap()
}

private fun featureTourRadarColor(intensity: Float, darkMap: Boolean): Int {
    if (intensity <= 0.018f) return 0
    val safe = intensity.coerceIn(0f, 1f)
    val edge = ((safe - 0.018f) / 0.21f).coerceIn(0f, 1f)
    val core = ((safe - 0.28f) / 0.50f).coerceIn(0f, 1f)
    val medium = ((safe - 0.73f) / 0.27f).coerceIn(0f, 1f)
    val firstBlend = core * (1f - medium)
    val red = (77f + (26f - 77f) * firstBlend + (116f - 77f) * medium)
        .toInt().coerceIn(0, 255)
    val green = (217f + (159f - 217f) * firstBlend + (82f - 217f) * medium)
        .toInt().coerceIn(0, 255)
    val blue = (244f + (235f - 244f) * firstBlend + (214f - 244f) * medium)
        .toInt().coerceIn(0, 255)
    // The spotlight scrim intentionally dims the map. Keep the synthetic radar sufficiently
    // luminous to read through it, while retaining feathered translucent edges.
    val maxAlpha = if (darkMap) 224f else 236f
    val alpha = ((26f + edge * 92f + safe * maxAlpha) * (1f - medium * 0.10f))
        .toInt().coerceIn(0, 244)
    return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
}

@Composable
private fun RadarTimelineTickRow(
    ticks: List<com.rainalarm.app.domain.RadarTimeTick>,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    Layout(
        modifier = modifier,
        content = {
            ticks.forEach {
                Spacer(Modifier.width(1.dp).height(4.dp).background(color))
            }
            ticks.forEach { tick ->
                Text(
                    tick.label,
                    color = color,
                    fontSize = 11.sp,
                    lineHeight = 13.sp,
                    fontWeight = FontWeight.Normal,
                    maxLines = 1,
                    softWrap = false,
                    textAlign = TextAlign.Center,
                )
            }
        },
    ) { measurables, constraints ->
        val count = ticks.size
        val height = constraints.maxHeight.coerceAtLeast(1)
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val tickPlaceables = measurables.take(count).map { it.measure(loose) }
        val labelPlaceables = measurables.drop(count).map { it.measure(loose) }
        val placements = RadarTimelineLabelLayout.arrange(
            fractions = ticks.map { it.fraction },
            labelWidthsPx = labelPlaceables.map { it.width },
            widthPx = constraints.maxWidth,
            innerInsetPx = with(density) { 10.dp.toPx() },
            gapPx = with(density) { 5.dp.toPx() },
        )
        layout(constraints.maxWidth, height) {
            placements.forEachIndexed { index, placement ->
                val tick = tickPlaceables[index]
                tick.placeRelative((placement.anchorPx - tick.width / 2f).toInt(), 0)
                if (placement.visible) {
                    val label = labelPlaceables[index]
                    label.placeRelative(
                        (placement.anchorPx - label.width / 2f).toInt(),
                        with(density) { 4.dp.roundToPx() },
                    )
                }
            }
        }
    }
}

/**
 * Installed on the stationary app-page ancestor (not the horizontally moving Radar pager page),
 * so a pointer that starts in a gutter remains owned after the page follows it across the screen.
 * It does not consume a tap or map gesture until the established policy claims a clearly
 * horizontal single-pointer drag.
 */
internal fun Modifier.radarPageSwipeInput(
    edge: RadarPageEdge,
    density: Density,
    onPageSwipe: (RadarPageSwipeEvent) -> Unit,
): Modifier = pointerInput(edge, density.density, onPageSwipe) {
    awaitEachGesture {
        val down = awaitFirstDown(
            requireUnconsumed = false,
            pass = PointerEventPass.Initial,
        )
        var accumulatedX = 0f
        var accumulatedY = 0f
        var multiTouch = false
        var claimed = false
        var finished = false
        var lastUptimeMillis = down.uptimeMillis
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.count { it.pressed } > 1) {
                    multiTouch = true
                    if (claimed) {
                        finished = true
                        onPageSwipe(RadarPageSwipeEvent.Cancel)
                    }
                    break
                }
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                val amount = change.positionChange()
                accumulatedX += amount.x
                accumulatedY += amount.y
                lastUptimeMillis = change.uptimeMillis
                if (!claimed && RadarPageSwipePolicy.canClaim(
                        edge,
                        with(density) { accumulatedX.toDp().value },
                        with(density) { accumulatedY.toDp().value },
                        multiTouch,
                    )
                ) {
                    claimed = true
                    onPageSwipe(RadarPageSwipeEvent.Begin)
                    onPageSwipe(RadarPageSwipeEvent.Drag(accumulatedX))
                } else if (claimed) {
                    onPageSwipe(RadarPageSwipeEvent.Drag(amount.x))
                }
                if (claimed) change.consume()
                if (!change.pressed) break
            }
            if (claimed) {
                val delta = RadarPageSwipePolicy.destinationDelta(
                    edge,
                    with(density) { accumulatedX.toDp().value },
                    with(density) { accumulatedY.toDp().value },
                    multiTouch = multiTouch,
                    durationMillis = (lastUptimeMillis - down.uptimeMillis).coerceAtLeast(1L),
                )
                finished = true
                onPageSwipe(RadarPageSwipeEvent.End(delta))
            }
        } finally {
            if (claimed && !finished) onPageSwipe(RadarPageSwipeEvent.Cancel)
        }
    }
}

/**
 * Stationary DOWN-only gutter targets. The target composable remains fixed while the pager content
 * follows the finger, so a claimed pointer keeps its original hit path all the way to UP/CANCEL.
 * The centre map is never covered, and the vertical geometry leaves every interactive map control
 * plus the bottom-left attribution/info target outside these siblings.
 */
@Composable
internal fun RadarPageSwipeOverlay(
    mapBounds: Rect,
    enabled: Boolean,
    onPageSwipe: (RadarPageSwipeEvent) -> Unit,
) {
    if (!enabled || mapBounds == Rect.Zero) return
    val density = LocalDensity.current
    val mapTopDp = with(density) { mapBounds.top.toDp() }
    val mapHeightDp = with(density) { mapBounds.height.toDp() }
    val leftHeight = mapHeightDp - RadarPageSwipeLayoutPolicy.leftInfoCutoutHeightDp.dp
    val rightHeight = mapHeightDp - RadarPageSwipeLayoutPolicy.rightTopInsetDp.dp
    if (leftHeight <= 0.dp || rightHeight <= 0.dp) return
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.offset(y = mapTopDp)
                .width(RadarPageSwipeLayoutPolicy.widthDp.dp)
                .height(leftHeight)
                .radarPageSwipeInput(RadarPageEdge.PREVIOUS, density, onPageSwipe),
        )
        Box(
            Modifier.align(Alignment.TopEnd)
                .offset(y = mapTopDp + RadarPageSwipeLayoutPolicy.rightTopInsetDp.dp)
                .width(RadarPageSwipeLayoutPolicy.widthDp.dp)
                .height(rightHeight)
                .radarPageSwipeInput(RadarPageEdge.NEXT, density, onPageSwipe),
        )
    }
}

/**
 * Android accepts only 200 dp of protected edge per side; hit regions remain taller than these.
 *
 * Compose owns and periodically rewrites the AndroidComposeView exclusion list. Register these two
 * rectangles on the decor root instead so retained-pager transitions cannot silently clear them.
 * They are installed only while Radar is the active destination and are removed on every exit.
 */
@Composable
private fun RadarPageGestureExclusions(mapBounds: Rect, enabled: Boolean) {
    val composeView = LocalView.current
    val rootView = composeView.rootView
    val density = LocalDensity.current
    val width = with(density) { RadarPageSwipeLayoutPolicy.widthDp.dp.roundToPx() }
    val height = with(density) { RadarPageSwipeLayoutPolicy.exclusionHeightDp.dp.roundToPx() }
    val composeLocation = remember(composeView, mapBounds) { IntArray(2) }.also {
        composeView.getLocationInWindow(it)
    }
    val leftTop = composeLocation[1] + mapBounds.top.roundToInt() + with(density) {
        RadarPageSwipeLayoutPolicy.leftExclusionTopDp.dp.roundToPx()
    }
    val rightTop = composeLocation[1] + mapBounds.top.roundToInt() + with(density) {
        RadarPageSwipeLayoutPolicy.rightExclusionTopDp.dp.roundToPx()
    }
    val active = enabled && mapBounds != Rect.Zero
    DisposableEffect(rootView, active, width, height, leftTop, rightTop) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && active) {
            rootView.systemGestureExclusionRects = listOf(
                AndroidRect(0, leftTop, width, leftTop + height),
                AndroidRect(rootView.width - width, rightTop, rootView.width, rightTop + height),
            )
        }
        onDispose {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                rootView.systemGestureExclusionRects = emptyList()
            }
        }
    }
}

@Composable
private fun timelineLabel(
    times: List<Long>,
    bracket: RadarTimelineBracket,
    cursor: Float,
    latestOffset: Float,
    forecastAvailable: Boolean,
): String {
    val instant = Instant.ofEpochSecond((times.first() + cursor).toLong())
    val context = LocalContext.current
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val locale = configuration.locales[0]
    val time = DateTimeFormatter.ofPattern(
        if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a",
        locale,
    ).format(instant.atZone(ZoneId.systemDefault()))
    return when {
        cursor == latestOffset -> stringResource(R.string.radar_latest_time, time)
        bracket.isForecast && forecastAvailable ->
            if (
                cursor <= (times.last() - times.first()).toFloat() &&
                latestOffset < (times.last() - times.first()).toFloat()
            ) {
                stringResource(R.string.radar_forecast_time, time)
            } else {
                stringResource(R.string.radar_estimate_time, time)
            }
        bracket.isForecast ->
            stringResource(R.string.radar_forecast_unavailable_time, time)
        else -> stringResource(R.string.radar_observed_time, time)
    }
}

@Composable
private fun localizedLayerName(layer: RadarMapLayer): String = stringResource(when (layer) {
    RadarMapLayer.WIND -> R.string.layer_wind
    RadarMapLayer.LIGHTNING -> R.string.layer_lightning
    RadarMapLayer.FOG -> R.string.layer_clouds
    RadarMapLayer.OFF -> R.string.common_disabled
})
