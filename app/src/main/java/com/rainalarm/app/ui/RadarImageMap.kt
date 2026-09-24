@file:android.annotation.SuppressLint("LogNotTimber") // Local tile diagnostics are needed on devices without telemetry.
package com.rainalarm.app.ui

import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.rainalarm.app.data.RadarSession
import com.rainalarm.app.data.SavedPlace
import com.rainalarm.app.data.WindGrid
import com.rainalarm.app.data.WindViewport
import com.rainalarm.app.data.EumetLayerMetadata
import com.rainalarm.app.data.CurrentWeather
import com.rainalarm.app.data.SatelliteCacheContext
import com.rainalarm.app.data.SatelliteCacheIdentity
import com.rainalarm.app.data.SatelliteCachedFrame
import com.rainalarm.app.data.SatelliteFrameAssetPolicy
import com.rainalarm.app.data.SatelliteFrameStoreProvider
import com.rainalarm.app.data.SatelliteFrameWindowPolicy
import com.rainalarm.app.data.SatelliteFrameSelectionPolicy
import com.rainalarm.app.data.SatelliteHandoffPolicy
import com.rainalarm.app.data.SatelliteRegionalImagePolicy
import com.rainalarm.app.data.SatelliteRenderIdentity
import com.rainalarm.app.data.SatelliteRegionPolicy
import com.rainalarm.app.data.RadarMapLayer
import com.rainalarm.app.data.RadarMapStyle
import com.rainalarm.app.domain.GeoPoint
import com.rainalarm.app.domain.NorthUpRadarGeoreference
import com.rainalarm.app.domain.RadarMotionPolicy
import com.rainalarm.app.domain.RadarOverlayFramePlan
import com.rainalarm.app.domain.RadarOverlayPlanner
import com.rainalarm.app.domain.RadarResolutionTier
import com.rainalarm.app.domain.RadarResourceTeardown
import com.rainalarm.app.domain.RadarCameraMemory
import com.rainalarm.app.domain.RadarCameraTarget
import com.rainalarm.app.domain.RadarTimelineBracket
import com.rainalarm.app.domain.RainAlarmPalette
import org.maplibre.android.MapLibre
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngQuad
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.style.sources.ImageSource
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.maps.Style
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.io.File
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val OPEN_FREE_MAP_DARK_STYLE = "https://tiles.openfreemap.org/styles/dark"
private const val OPEN_FREE_MAP_LIGHT_STYLE = "https://tiles.openfreemap.org/styles/liberty"
private const val OPEN_FREE_MAP_SLATE_STYLE = "https://tiles.openfreemap.org/styles/fiord"

/**
 * MapLibre exposes one supported ambient database shared by base-map and raster resources.
 * Satellite frames use their own verified 128 MiB cache. MapLibre's ambient database is kept at
 * a defensible 64 MiB for OpenFreeMap and ordinary map navigation resources.
 */
internal object SatelliteAmbientCache {
    const val satelliteAllowanceBytes = 128L * 1024L * 1024L
    const val baseMapAllowanceBytes = 64L * 1024L * 1024L
    const val sharedBudgetBytes = baseMapAllowanceBytes
    private val configured = AtomicBoolean(false)

    fun configure(context: android.content.Context) {
        if (!configured.compareAndSet(false, true)) return
        MapLibre.getInstance(context.applicationContext)
        OfflineManager.getInstance(context.applicationContext).setMaximumAmbientCacheSize(
            sharedBudgetBytes,
            object : OfflineManager.FileSourceCallback {
                override fun onSuccess() = Unit
                override fun onError(message: String) {
                    configured.set(false)
                    Log.w("RainRadarCache", "Could not configure ambient cache: $message")
                }
            },
        )
    }
}

internal object RadarMapAppearance {
    fun styleUrl(style: RadarMapStyle): String = when (style) {
        RadarMapStyle.DARK -> OPEN_FREE_MAP_DARK_STYLE
        RadarMapStyle.LIGHT -> OPEN_FREE_MAP_LIGHT_STYLE
        RadarMapStyle.SLATE -> OPEN_FREE_MAP_SLATE_STYLE
    }

    fun loadingBackgroundArgb(style: RadarMapStyle): Int = when (style) {
        RadarMapStyle.DARK -> 0xFF111C24.toInt()
        RadarMapStyle.LIGHT -> 0xFFF3F5F4.toInt()
        RadarMapStyle.SLATE -> 0xFF45516E.toInt()
    }
}

internal enum class RadarMapLabelCategory { PLACE, ROAD, WATER, ROAD_REFERENCE }

internal data class RadarMapLabelPalette(
    val placeTextArgb: Int,
    val roadTextArgb: Int,
    val waterTextArgb: Int,
    val roadReferenceTextArgb: Int,
    val haloArgb: Int,
    val haloWidth: Float,
    val haloBlur: Float,
) {
    fun textArgb(category: RadarMapLabelCategory): Int = when (category) {
        RadarMapLabelCategory.PLACE -> placeTextArgb
        RadarMapLabelCategory.ROAD -> roadTextArgb
        RadarMapLabelCategory.WATER -> waterTextArgb
        RadarMapLabelCategory.ROAD_REFERENCE -> roadReferenceTextArgb
    }
}

/** Text-only contrast lift; layout, typography, icons and non-label paint remain style-owned. */
internal object RadarMapLabelContrastPolicy {
    private val dark = RadarMapLabelPalette(
        placeTextArgb = 0xFFE2E6ED.toInt(),
        roadTextArgb = 0xFFAEB6C2.toInt(),
        waterTextArgb = 0xFF78BFE3.toInt(),
        roadReferenceTextArgb = 0xFFC5CFDC.toInt(),
        haloArgb = 0xFF080B12.toInt(),
        haloWidth = 1.1f,
        haloBlur = 0.25f,
    )
    private val slate = RadarMapLabelPalette(
        placeTextArgb = 0xFFF2F4F8.toInt(),
        roadTextArgb = 0xFFC4CBD7.toInt(),
        waterTextArgb = 0xFF9FD8F0.toInt(),
        roadReferenceTextArgb = 0xFFD6DFEB.toInt(),
        haloArgb = 0xFF273149.toInt(),
        haloWidth = 1.2f,
        haloBlur = 0.3f,
    )

    fun paletteFor(style: RadarMapStyle): RadarMapLabelPalette? = when (style) {
        RadarMapStyle.DARK -> dark
        RadarMapStyle.SLATE -> slate
        RadarMapStyle.LIGHT -> null
    }

    fun category(layerId: String, sourceLayer: String?): RadarMapLabelCategory? = when (sourceLayer) {
        "place" -> RadarMapLabelCategory.PLACE
        "water_name" -> RadarMapLabelCategory.WATER
        "transportation_name" -> if (layerId.contains("ref", ignoreCase = true) ||
            layerId.contains("motorway", ignoreCase = true)) {
            RadarMapLabelCategory.ROAD_REFERENCE
        } else RadarMapLabelCategory.ROAD
        else -> null
    }
}

private fun applyMapLabelContrast(style: Style, mapStyle: RadarMapStyle) {
    val palette = RadarMapLabelContrastPolicy.paletteFor(mapStyle) ?: return
    style.layers.filterIsInstance<SymbolLayer>().forEach { layer ->
        val category = RadarMapLabelContrastPolicy.category(layer.id, layer.sourceLayer) ?: return@forEach
        if (layer.textField.isNull) return@forEach // Never recolour an icon-only symbol layer.
        try {
            layer.setProperties(
                PropertyFactory.textColor(palette.textArgb(category)),
                PropertyFactory.textHaloColor(palette.haloArgb),
                PropertyFactory.textHaloWidth(palette.haloWidth),
                PropertyFactory.textHaloBlur(palette.haloBlur),
            )
        } catch (failure: Exception) {
            Log.w("RainRadarMap", "Could not lift label contrast for ${layer.id}", failure)
        }
    }
}
private const val RADAR_OPACITY = 0.90
internal object SatelliteLayerRenderPolicy {
    /** Clouds are added first so Lightning remains legible above them. */
    val renderOrder: List<RadarMapLayer> = listOf(RadarMapLayer.FOG, RadarMapLayer.LIGHTNING)

    fun sourcePrefix(choice: RadarMapLayer): String = when (choice) {
        RadarMapLayer.FOG -> "rain-alarm-satellite-clouds"
        RadarMapLayer.LIGHTNING -> "rain-alarm-satellite-lightning"
        else -> error("Not a satellite layer")
    }

    fun sourceId(choice: RadarMapLayer, generation: Long = 0L): String =
        "${sourcePrefix(choice)}-$generation-source"

    fun layerId(choice: RadarMapLayer, generation: Long = 0L): String =
        "${sourcePrefix(choice)}-$generation-layer"

    fun opacity(choice: RadarMapLayer): Float = if (choice == RadarMapLayer.FOG) 0.38f else 0.7f

    fun ordered(metadata: Collection<EumetLayerMetadata>): List<EumetLayerMetadata> = renderOrder
        .mapNotNull { choice -> metadata.firstOrNull { it.choice == choice } }
}

internal enum class SatellitePendingAction { PROMOTE, DISCARD }

/** Pure decision for a loaded pending source against the latest conflated cursor target. */
internal object SatelliteProgressiveLoadPolicy {
    fun whenReady(
        active: EumetLayerMetadata?,
        pending: EumetLayerMetadata,
        desired: EumetLayerMetadata?,
        isPlaying: Boolean,
    ): SatellitePendingAction {
        if (desired?.frameIdentity == pending.frameIdentity) return SatellitePendingAction.PROMOTE
        if (!isPlaying || active == null || desired == null) return SatellitePendingAction.DISCARD
        // Playback can display a useful intermediate only while moving forward within the
        // target product. A loop wrap/backward seek or day/night product switch cannot promote
        // a stale frame over the newly requested cursor position.
        val forwardWindow = desired.validEpochSeconds > active.validEpochSeconds
        val intermediate = pending.validEpochSeconds > active.validEpochSeconds &&
            pending.validEpochSeconds <= desired.validEpochSeconds
        return if (forwardWindow && intermediate && pending.product == desired.product) {
            SatellitePendingAction.PROMOTE
        } else {
            SatellitePendingAction.DISCARD
        }
    }
}

private data class SatelliteBufferSlot(
    val metadata: EumetLayerMetadata,
    val generation: Long,
    val sourceId: String,
    val layerId: String,
    val cacheIdentity: String,
    val renderIdentity: String,
    val bitmap: Bitmap,
    val addedAtRenderSequence: Long,
)

internal data class SatelliteOverlayRequest(
    val desired: EumetLayerMetadata?,
    val frames: List<SatelliteCachedFrame>,
    val preparationGeneration: Int,
)

private data class SatellitePreparedPlan(
    val planKey: String,
    val cacheContext: SatelliteCacheContext,
    val frames: List<SatelliteCachedFrame>,
    val generation: Int,
)

internal object SatellitePreparedFramePolicy {
    /** Select only from the complete verified set, holding the latest observation in forecast time. */
    fun desired(
        frames: List<SatelliteCachedFrame>,
        requested: EumetLayerMetadata?,
    ): EumetLayerMetadata? {
        if (requested == null || frames.isEmpty()) return null
        return frames.firstOrNull { it.metadata.frameIdentity == requested.frameIdentity }?.metadata
            ?: frames.asSequence().map(SatelliteCachedFrame::metadata)
                .filter { it.product == requested.product &&
                    it.validEpochSeconds <= requested.validEpochSeconds }
                .maxByOrNull(EumetLayerMetadata::validEpochSeconds)
            ?: frames.asSequence().map(SatelliteCachedFrame::metadata)
                .filter { it.validEpochSeconds <= requested.validEpochSeconds }
                .maxByOrNull(EumetLayerMetadata::validEpochSeconds)
    }
}

sealed interface SatellitePreparationStatus {
    data class Preparing(val ready: Int, val total: Int) : SatellitePreparationStatus
    data object Rendering : SatellitePreparationStatus
    data object Ready : SatellitePreparationStatus
    data class Failed(val message: String) : SatellitePreparationStatus
}

private data class SatelliteChoiceBuffer(
    var nextGeneration: Long = 0L,
    var desired: EumetLayerMetadata? = null,
    var cacheContext: SatelliteCacheContext? = null,
    var assets: Map<String, SatelliteCachedFrame> = emptyMap(),
    var planKey: String? = null,
    var requestPresent: Boolean = false,
    var isPlaying: Boolean = false,
    var active: SatelliteBufferSlot? = null,
    var pending: SatelliteBufferSlot? = null,
    var retiring: SatelliteBufferSlot? = null,
    var revealedGeneration: Long? = null,
    var revealedAtRenderSequence: Long? = null,
    var decoding: EumetLayerMetadata? = null,
    var decodeJob: Job? = null,
)

/**
 * Verified compressed frames come from the app-owned cache. Only the desired frame is decoded off
 * the UI thread. MapLibre mutation stays on Main, with active/pending/retiring overlap bounded.
 */
private class SatelliteLayerBuffers(
    private val onLayerError: (RadarMapLayer, String) -> Unit,
    private val onFrameInvalidated: (RadarMapLayer, SatelliteCachedFrame) -> Unit,
    private val onFrameRendered: (RadarMapLayer) -> Unit,
) {
    private val choices = SatelliteLayerRenderPolicy.renderOrder.associateWith {
        SatelliteChoiceBuffer()
    }.toMutableMap()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var currentStyle: Style? = null
    private var renderSequence = 0L

    fun onStyleLoaded(
        style: Style,
        requests: Map<RadarMapLayer, SatelliteOverlayRequest>,
        enabled: Set<RadarMapLayer>,
        isPlaying: Boolean,
        cacheContext: SatelliteCacheContext?,
    ) {
        currentStyle = style
        renderSequence = 0L
        choices.values.forEach { state ->
            state.decodeJob?.cancel()
            allSlots(state).forEach { it.bitmap.recycle() }
            state.nextGeneration = 0L
            state.desired = null
            state.cacheContext = null
            state.assets = emptyMap()
            state.planKey = null
            state.requestPresent = false
            state.isPlaying = false
            state.active = null
            state.pending = null
            state.retiring = null
            state.revealedGeneration = null
            state.revealedAtRenderSequence = null
            state.decoding = null
            state.decodeJob = null
        }
        reconcile(style, requests, enabled, isPlaying, cacheContext)
    }

    fun reconcile(
        style: Style,
        requests: Map<RadarMapLayer, SatelliteOverlayRequest>,
        enabled: Set<RadarMapLayer>,
        isPlaying: Boolean,
        cacheContext: SatelliteCacheContext?,
    ) {
        if (currentStyle !== style) {
            onStyleLoaded(style, requests, enabled, isPlaying, cacheContext)
            return
        }
        SatelliteLayerRenderPolicy.renderOrder.forEach { choice ->
            reconcileChoice(style, choice, requests[choice], choice in enabled, isPlaying, cacheContext)
        }
    }

    /** A Bitmap ImageSource needs one complete frame after add before reveal, then another to retire. */
    fun onFullyRendered() {
        val style = currentStyle ?: return
        renderSequence++
        SatelliteLayerRenderPolicy.renderOrder.forEach { choice ->
            val state = choices.getValue(choice)
            try {
                if (SatelliteHandoffPolicy.mayRetire(
                        state.revealedGeneration, state.active?.generation,
                        state.revealedAtRenderSequence, renderSequence, fullyRendered = true,
                    )) {
                    state.retiring?.let { remove(style, it) }
                    state.retiring = null
                    onFrameRendered(choice)
                    state.revealedGeneration = null
                    state.revealedAtRenderSequence = null
                }
                val pending = state.pending?.takeIf {
                    renderSequence > it.addedAtRenderSequence
                }
                if (pending != null && state.retiring == null) {
                    val action = SatelliteProgressiveLoadPolicy.whenReady(
                        state.active?.metadata, pending.metadata, state.desired, state.isPlaying,
                    )
                    when (action) {
                        SatellitePendingAction.PROMOTE -> {
                            val incoming = requireNotNull(style.getLayer(pending.layerId))
                            incoming.setProperties(PropertyFactory.rasterOpacity(
                                SatelliteLayerRenderPolicy.opacity(choice),
                            ))
                            val outgoing = state.active
                            state.retiring = outgoing
                            state.active = pending
                            state.revealedGeneration = pending.generation
                            state.revealedAtRenderSequence = renderSequence
                        }
                        SatellitePendingAction.DISCARD -> remove(style, pending)
                    }
                    state.pending = null
                }
                startLatestIfNeeded(style, choice, state)
            } catch (failure: Exception) {
                Log.e("RainRadarLayers", "${choice.label} raster swap failed", failure)
                state.pending?.let { remove(style, it) }
                state.pending = null
                if (state.active == null) onLayerError(choice, "${choice.label} layer unavailable")
                startLatestIfNeeded(style, choice, state)
            }
        }
    }

    private fun reconcileChoice(
        style: Style,
        choice: RadarMapLayer,
        request: SatelliteOverlayRequest?,
        enabled: Boolean,
        isPlaying: Boolean,
        cacheContext: SatelliteCacheContext?,
    ) {
        val state = choices.getValue(choice)
        if (!enabled) {
            clearChoice(style, choice, state)
            return
        }
        state.isPlaying = isPlaying
        if (cacheContext == null) {
            state.requestPresent = false
            return
        }
        if (state.cacheContext != null && state.cacheContext != cacheContext) {
            clearChoice(style, choice, state)
            state.isPlaying = isPlaying
        }
        state.cacheContext = cacheContext
        if (request == null) {
            state.requestPresent = false
            return
        }
        state.requestPresent = true
        state.desired = request.desired
        val nextPlanKey = request.frames.joinToString(
            prefix = "${request.preparationGeneration}|", separator = ";",
        ) { it.request.diskKey }
        if (state.planKey != nextPlanKey) {
            state.decodeJob?.cancel()
            state.decodeJob = null
            state.decoding = null
            state.pending?.let { remove(style, it) }
            state.pending = null
            state.planKey = nextPlanKey
        }
        state.assets = request.frames.associateBy { it.metadata.frameIdentity }
        startLatestIfNeeded(style, choice, state)
    }

    private fun startLatestIfNeeded(
        style: Style,
        choice: RadarMapLayer,
        state: SatelliteChoiceBuffer,
    ) {
        val metadata = state.desired
        if (metadata == null) {
            state.decodeJob?.cancel()
            state.decodeJob = null
            state.decoding = null
            state.pending?.let { remove(style, it) }
            state.pending = null
            state.active?.let { remove(style, it) }
            state.active = null
            state.retiring?.let { remove(style, it) }
            state.retiring = null
            return
        }
        val context = state.cacheContext ?: return
        val desiredRenderIdentity = SatelliteRenderIdentity.frame(metadata, context)
        if (state.active?.renderIdentity == desiredRenderIdentity ||
            state.pending?.renderIdentity == desiredRenderIdentity ||
            state.decoding?.frameIdentity == metadata.frameIdentity) return
        if (state.pending != null || state.retiring != null || state.revealedGeneration != null) return
        val asset = state.assets[metadata.frameIdentity] ?: return
        val generation = ++state.nextGeneration
        val planKey = state.planKey
        state.decoding = metadata
        state.decodeJob = scope.launch {
            val bitmap = try {
                withContext(Dispatchers.IO) {
                    val decoded = BitmapFactory.decodeFile(asset.file.absolutePath)
                        ?: error("Satellite PNG could not be decoded")
                    try {
                        currentCoroutineContext().ensureActive()
                        require(decoded.width == asset.request.width &&
                            decoded.height == asset.request.height) {
                            "Satellite bitmap dimensions changed"
                        }
                        decoded
                    } catch (failure: Throwable) {
                        if (!decoded.isRecycled) decoded.recycle()
                        throw failure
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                Log.e("RainRadarLayers", "${choice.label} bitmap decode failed", failure)
                state.assets = state.assets - metadata.frameIdentity
                onFrameInvalidated(choice, asset)
                onLayerError(choice, "${choice.label} frame could not be decoded · refresh")
                null
            }
            state.decodeJob = null
            state.decoding = null
            if (bitmap == null) return@launch
            try {
                currentCoroutineContext().ensureActive()
            } catch (cancelled: CancellationException) {
                if (!bitmap.isRecycled) bitmap.recycle()
                throw cancelled
            }
            val currentDesired = state.desired
            val action = SatelliteProgressiveLoadPolicy.whenReady(
                state.active?.metadata, metadata, currentDesired, state.isPlaying,
            )
            if (currentStyle !== style || state.planKey != planKey ||
                action == SatellitePendingAction.DISCARD) {
                bitmap.recycle()
                startLatestIfNeeded(style, choice, state)
                return@launch
            }
            val slot = add(
                style, metadata, context, generation, PRELOAD_OPACITY, bitmap,
            )
            if (slot == null) {
                if (!bitmap.isRecycled) bitmap.recycle()
                onLayerError(choice, "${choice.label} layer unavailable · refresh")
            } else state.pending = slot
        }
    }

    private fun add(
        style: Style,
        metadata: EumetLayerMetadata,
        context: SatelliteCacheContext,
        generation: Long,
        opacity: Float,
        bitmap: Bitmap,
    ): SatelliteBufferSlot? {
        val sourceId = SatelliteLayerRenderPolicy.sourceId(metadata.choice, generation)
        val layerId = SatelliteLayerRenderPolicy.layerId(metadata.choice, generation)
        val slot = SatelliteBufferSlot(
            metadata, generation, sourceId, layerId,
            SatelliteCacheIdentity.frame(metadata, context),
            SatelliteRenderIdentity.frame(metadata, context),
            bitmap,
            renderSequence,
        )
        try {
            val request = SatelliteRegionalImagePolicy.request(metadata)
            val bounds = request.bounds
            val quad = LatLngQuad(
                LatLng(bounds.north, bounds.west),
                LatLng(bounds.north, bounds.east),
                LatLng(bounds.south, bounds.east),
                LatLng(bounds.south, bounds.west),
            )
            style.addSource(ImageSource(sourceId, quad, bitmap))
            val layer = RasterLayer(layerId, sourceId).withProperties(
                PropertyFactory.rasterOpacity(opacity),
                PropertyFactory.rasterFadeDuration(0f),
            )
            if (metadata.choice == RadarMapLayer.FOG) {
                val lightningLayer = choices[RadarMapLayer.LIGHTNING]?.let(::allSlots)
                    ?.firstOrNull { style.getLayer(it.layerId) != null }?.layerId
                if (lightningLayer != null) style.addLayerBelow(layer, lightningLayer)
                else style.addLayer(layer)
            } else style.addLayer(layer)
            return slot
        } catch (failure: Exception) {
            Log.e("RainRadarLayers", "${metadata.choice.label} regional image failed", failure)
            remove(style, slot)
            return null
        }
    }

    private fun remove(style: Style, slot: SatelliteBufferSlot) {
        if (style.getLayer(slot.layerId) != null) style.removeLayer(slot.layerId)
        if (style.getSource(slot.sourceId) != null) style.removeSource(slot.sourceId)
        if (!slot.bitmap.isRecycled) slot.bitmap.recycle()
    }

    private fun allSlots(state: SatelliteChoiceBuffer): List<SatelliteBufferSlot> =
        listOfNotNull(state.active, state.pending, state.retiring)

    fun clear() {
        val style = currentStyle
        if (style != null) choices.forEach { (choice, state) -> clearChoice(style, choice, state) }
        else choices.values.forEach { state ->
            state.decodeJob?.cancel()
            allSlots(state).forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
        }
        scope.cancel()
        currentStyle = null
    }

    private fun clearChoice(
        style: Style,
        choice: RadarMapLayer,
        state: SatelliteChoiceBuffer,
    ) {
        allSlots(state).distinctBy(SatelliteBufferSlot::sourceId).forEach { remove(style, it) }
        state.decodeJob?.cancel()
        state.decodeJob = null
        state.decoding = null
        state.active = null
        state.pending = null
        state.retiring = null
        state.revealedGeneration = null
        state.revealedAtRenderSequence = null
        state.desired = null
        state.cacheContext = null
        state.assets = emptyMap()
        state.planKey = null
        state.requestPresent = false
    }

    companion object {
        const val PRELOAD_OPACITY = 0.001f
    }
}

/** Twenty-five distinct requested map positions; the model may reuse a coarse weather cell. */
internal object WindArrowGeometry {
    const val lengthPx = 44f
    const val headBackPx = 16f
    const val headHalfWidthPx = 12f
    const val cullMarginPx = 60f
    fun length(scale: Float): Float = lengthPx * scale
    fun headBack(scale: Float): Float = headBackPx * scale
    fun headHalfWidth(scale: Float): Float = headHalfWidthPx * scale
    fun cullMargin(scale: Float): Float = cullMarginPx * scale
}

private class WindFieldView(context: android.content.Context) : View(context) {
    private val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAAB7EEFF.toInt(); strokeWidth = 3.5f; style = Paint.Style.STROKE
    }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x990B1220.toInt(); strokeWidth = 7.5f; style = Paint.Style.STROKE
    }
    private var map: MapLibreMap? = null
    private var grid: WindGrid? = null
    private var arrowScale = 1f
    private var locations: List<LatLng> = emptyList()
    init { isClickable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    fun bind(ready: MapLibreMap) { map = ready; invalidate() }
    fun update(value: WindGrid?, scale: Float) { if (grid !== value || arrowScale != scale) {
        grid = value
        arrowScale = scale
        arrow.strokeWidth = 3.5f * scale
        shadow.strokeWidth = 7.5f * scale
        locations = value?.renderCoordinates()?.map { LatLng(it.first, it.second) }.orEmpty()
        invalidate()
    } }
    fun cameraMoved() = invalidate()
    override fun onDraw(canvas: Canvas) {
        val ready = map ?: return
        val points = grid?.points ?: return
        for ((index, sample) in points.withIndex()) {
            sample.windSpeedKmh ?: continue
            val from = sample.windFromDegrees ?: continue
            val screen = ready.projection.toScreenLocation(locations[index])
            val margin = WindArrowGeometry.cullMargin(arrowScale)
            if (screen.x < -margin || screen.x > width + margin ||
                screen.y < -margin || screen.y > height + margin) continue
            val radians = Math.toRadians((from + 180.0) % 360.0)
            val dx = sin(radians).toFloat()
            val dy = -cos(radians).toFloat()
            val length = WindArrowGeometry.length(arrowScale)
            val x0 = screen.x - dx * length / 2
            val y0 = screen.y - dy * length / 2
            val x1 = screen.x + dx * length / 2
            val y1 = screen.y + dy * length / 2
            drawArrow(canvas, x0, y0, x1, y1, dx, dy, shadow)
            drawArrow(canvas, x0, y0, x1, y1, dx, dy, arrow)
        }
    }
    private fun drawArrow(canvas: Canvas, x0: Float, y0: Float, x1: Float, y1: Float,
        dx: Float, dy: Float, paint: Paint) {
        canvas.drawLine(x0, y0, x1, y1, paint)
        val back = WindArrowGeometry.headBack(arrowScale)
        val halfWidth = WindArrowGeometry.headHalfWidth(arrowScale)
        canvas.drawLine(x1, y1, x1 - dx * back - dy * halfWidth,
            y1 - dy * back + dx * halfWidth, paint)
        canvas.drawLine(x1, y1, x1 - dx * back + dy * halfWidth,
            y1 - dy * back - dx * halfWidth, paint)
    }
}

private data class OverlayBounds(
    val topLeft: LatLng,
    val bottomRight: LatLng,
)

@android.annotation.SuppressLint("ViewConstructor", "LogNotTimber")
private class LegacyStaticFallbackOverlayView(
    context: android.content.Context,
    private val session: RadarSession,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { alpha = 230 }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4FC3F7.toInt() }
    private val decodeThread = HandlerThread("rain-radar-static-fallback").apply { start() }
    private val decodeHandler = Handler(decodeThread.looper)
    private var bitmap: android.graphics.Bitmap? = null
    private var map: MapLibreMap? = null
    private var marker = session.place
    private var decodeStarted = false
    private var disposed = false

    init {
        visibility = GONE
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun attachMap(ready: MapLibreMap) {
        map = ready
        invalidate()
    }

    fun setPlace(place: SavedPlace) {
        marker = place
        invalidate()
    }

    fun onCameraMoved() {
        invalidate()
    }

    fun show() {
        if (disposed) return
        visibility = VISIBLE
        if (decodeStarted) return
        decodeStarted = true
        decodeHandler.post {
            val archive = session.legacyArchive ?: return@post
            val index = archive.frames.indexOfLast { !it.frame.forecast }.coerceAtLeast(0)
            val decoded = runCatching {
                val source = android.graphics.BitmapFactory.decodeByteArray(
                    archive.frames[index].radarJpeg,
                    0,
                    archive.frames[index].radarJpeg.size,
                    android.graphics.BitmapFactory.Options().apply {
                        inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                        // Emergency CPU display only: half-size keeps recovery bounded on
                        // devices whose GL surface/allocation has already failed.
                        inSampleSize = 2
                    },
                ) ?: error("Static radar decode failed")
                try {
                    colorizeStaticRadar(source)
                } finally {
                    source.recycle()
                }
            }.onFailure { Log.e("RainRadarRenderer", "Static radar fallback failed", it) }.getOrNull()
            post {
                if (disposed) decoded?.recycle() else {
                    bitmap = decoded
                    invalidate()
                }
            }
        }
    }

    @android.annotation.SuppressLint("UseKtx")
    private fun colorizeStaticRadar(source: android.graphics.Bitmap): android.graphics.Bitmap {
        val width = source.width
        val height = source.height
        val output = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        val sourceRow = IntArray(width)
        val colored = IntArray(width)
        for (y in 0 until height) {
            source.getPixels(sourceRow, 0, width, 0, y, width, 1)
            for (x in 0 until width) {
                colored[x] = RainAlarmPalette.colorAt(((sourceRow[x] ushr 16) and 0xff) / 255f)
            }
            output.setPixels(colored, 0, width, 0, y, width, 1)
        }
        return output
    }

    fun hide() {
        if (!disposed) visibility = GONE
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        bitmap?.takeUnless { it.isRecycled }?.recycle()
        bitmap = null
        map = null
        decodeThread.quitSafely()
    }

    // MapLibre's Projection API returns screen points and requires LatLng values. This
    // fallback draws only after a GL failure, rather than on the normal animation path.
    @android.annotation.SuppressLint("DrawAllocation")
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val image = bitmap?.takeUnless { it.isRecycled } ?: return
        val ready = map ?: return
        val bounds = session.region?.bounds ?: return
        val topLeft = ready.projection.toScreenLocation(LatLng(bounds.topLeft.latitude, bounds.topLeft.longitude))
        val bottomRight = ready.projection.toScreenLocation(
            LatLng(bounds.bottomRight.latitude, bounds.bottomRight.longitude),
        )
        val destination = RectF(
            min(topLeft.x, bottomRight.x),
            min(topLeft.y, bottomRight.y),
            max(topLeft.x, bottomRight.x),
            max(topLeft.y, bottomRight.y),
        )
        canvas.drawBitmap(image, null, destination, paint)
        val point = ready.projection.toScreenLocation(LatLng(marker.latitude, marker.longitude))
        canvas.drawCircle(point.x, point.y, 7f * resources.displayMetrics.density, markerPaint)
    }
}

internal interface RadarOverlayController {
    val overlayView: View
    fun bindSession(ownedSession: RadarSession)
    fun attachMap(ready: MapLibreMap)
    fun setState(next: RadarTimelineBracket, playing: Boolean, mapPlace: SavedPlace)
    fun setMarker(mapPlace: SavedPlace)
    fun onCameraMoved()
    fun dispose()
}

private class CanvasRadarOverlayView(context: android.content.Context) : View(context), RadarOverlayController {
    override val overlayView: View get() = this
    private val firstPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val secondPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4FC3F7.toInt() }
    private val markerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF0D0D0D.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
    }
    private val firstDestination = RectF()
    private val secondDestination = RectF()
    private lateinit var session: RadarSession
    private var frameTimes = emptyList<Long>()
    private var overlayBounds = emptyMap<RadarResolutionTier, OverlayBounds?>()
    private val markerRadius = 7f * resources.displayMetrics.density
    private var map: MapLibreMap? = null
    private lateinit var marker: SavedPlace
    private lateinit var markerLatLng: LatLng
    private lateinit var bracket: RadarTimelineBracket
    private var plan: RadarOverlayFramePlan? = null
    private var activeTier: RadarResolutionTier? = null
    private var animating = false
    private var frameScheduled = false
    private var disposed = false
    private val choreographer = Choreographer.getInstance()
    private val frameCallback = Choreographer.FrameCallback {
        frameScheduled = false
        if (animating && !disposed) {
            postInvalidateOnAnimation()
            scheduleFrame()
        }
    }

    init {
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun bindSession(ownedSession: RadarSession) {
        check(!::session.isInitialized) { "Radar overlay session is already bound" }
        session = ownedSession
        frameTimes = session.frames.map { it.frame.time }
        overlayBounds = RadarResolutionTier.entries.associateWith { tier ->
            session.tier(tier)?.bounds?.let { bounds ->
                OverlayBounds(
                    LatLng(bounds.topLeft.latitude, bounds.topLeft.longitude),
                    LatLng(bounds.bottomRight.latitude, bounds.bottomRight.longitude),
                )
            }
        }
        marker = session.place
        markerLatLng = LatLng(marker.latitude, marker.longitude)
        bracket = RadarTimelineBracket(
            session.frames.lastIndex,
            session.frames.lastIndex,
            0.0,
            false,
            0.0,
        )
    }

    override fun attachMap(ready: MapLibreMap) {
        if (disposed || !::session.isInitialized) return
        map = ready
        rebuildPlan(force = true)
        postInvalidateOnAnimation()
    }

    override fun setState(next: RadarTimelineBracket, playing: Boolean, mapPlace: SavedPlace) {
        if (disposed || !::session.isInitialized) return
        bracket = next
        if (marker.latitude != mapPlace.latitude || marker.longitude != mapPlace.longitude) {
            marker = mapPlace
            markerLatLng = LatLng(marker.latitude, marker.longitude)
        }
        animating = playing
        rebuildPlan(force = true)
        postInvalidateOnAnimation()
        if (playing) scheduleFrame() else cancelFrame()
    }

    override fun setMarker(mapPlace: SavedPlace) {
        if (disposed || !::session.isInitialized ||
            (marker.latitude == mapPlace.latitude && marker.longitude == mapPlace.longitude)) return
        marker = mapPlace
        markerLatLng = LatLng(mapPlace.latitude, mapPlace.longitude)
        postInvalidateOnAnimation()
    }

    override fun onCameraMoved() {
        if (disposed) return
        rebuildPlan(force = false)
        postInvalidateOnAnimation()
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        animating = false
        cancelFrame()
        map = null
        plan = null
        activeTier = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (disposed || !::session.isInitialized || session.isReleased) return
        val ready = map ?: return
        val framePlan = plan ?: return
        val tierFrames = session.tier(framePlan.tier) ?: return
        val firstBitmap = tierFrames.frames[framePlan.firstIndex].bitmap
        val secondBitmap = tierFrames.frames[framePlan.secondIndex].bitmap
        if (firstBitmap.isRecycled || secondBitmap.isRecycled) return

        val projection = ready.projection
        val bounds = overlayBounds[framePlan.tier] ?: return
        val topLeft = projection.toScreenLocation(bounds.topLeft)
        val bottomRight = projection.toScreenLocation(bounds.bottomRight)
        val zoom = ready.cameraPosition.zoom
        val screenScale = NorthUpRadarGeoreference.screenPixelsPerWorld(zoom)
        setDestination(
            firstDestination,
            topLeft.x,
            topLeft.y,
            bottomRight.x,
            bottomRight.y,
            framePlan.firstDxWorldFraction * screenScale,
            framePlan.firstDyWorldFraction * screenScale,
        )
        setDestination(
            secondDestination,
            topLeft.x,
            topLeft.y,
            bottomRight.x,
            bottomRight.y,
            framePlan.secondDxWorldFraction * screenScale,
            framePlan.secondDyWorldFraction * screenScale,
        )

        firstPaint.alpha = (255.0 * RADAR_OPACITY * framePlan.firstAlpha)
            .roundToInt().coerceIn(0, 255)
        secondPaint.alpha = (255.0 * RADAR_OPACITY * framePlan.secondAlpha)
            .roundToInt().coerceIn(0, 255)
        if (firstPaint.alpha > 0) {
            canvas.drawBitmap(firstBitmap, null, firstDestination, firstPaint)
        }
        if (secondPaint.alpha > 0) {
            canvas.drawBitmap(secondBitmap, null, secondDestination, secondPaint)
        }
        val markerPoint = projection.toScreenLocation(markerLatLng)
        canvas.drawCircle(markerPoint.x, markerPoint.y, markerRadius, markerPaint)
        canvas.drawCircle(markerPoint.x, markerPoint.y, markerRadius, markerStrokePaint)
    }

    private fun rebuildPlan(force: Boolean) {
        val ready = map ?: return
        val tier = RadarOverlayPlanner.activeTier(
            mapZoom = ready.cameraPosition.zoom,
            regionalAvailable = session.regional != null,
            detailAvailable = session.detail != null,
        )
        if (!force && tier == activeTier) return
        activeTier = tier
        val safeBracket = if (bracket.isForecast && !RadarMotionPolicy.usable(session.motion)) {
            bracket.copy(
                firstIndex = session.frames.lastIndex,
                secondIndex = session.frames.lastIndex,
                fraction = 0.0,
                isForecast = false,
                forecastMinutes = 0.0,
            )
        } else {
            bracket
        }
        plan = RadarOverlayPlanner.plan(
            safeBracket,
            tier,
            session.pairMotions,
            frameTimes,
            session.motion,
        )
    }

    private fun scheduleFrame() {
        if (frameScheduled || disposed) return
        frameScheduled = true
        choreographer.postFrameCallback(frameCallback)
    }

    private fun cancelFrame() {
        if (!frameScheduled) return
        choreographer.removeFrameCallback(frameCallback)
        frameScheduled = false
    }

    private fun setDestination(
        destination: RectF,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        dxPixels: Double,
        dyPixels: Double,
    ) {
        destination.set(
            min(left, right) + dxPixels.toFloat(),
            min(top, bottom) + dyPixels.toFloat(),
            max(left, right) + dxPixels.toFloat(),
            max(top, bottom) + dyPixels.toFloat(),
        )
    }
}

private class MapViewLifecycle(private val mapView: MapView) : DefaultLifecycleObserver {
    private var started = false
    private var resumed = false
    private var destroyed = false

    override fun onStart(owner: LifecycleOwner) {
        if (!destroyed && !started) {
            mapView.onStart()
            started = true
        }
    }

    override fun onResume(owner: LifecycleOwner) {
        if (destroyed || resumed) return
        if (!started) onStart(owner)
        mapView.onResume()
        resumed = true
    }

    override fun onPause(owner: LifecycleOwner) = pause()

    override fun onStop(owner: LifecycleOwner) = stop()

    fun destroy() {
        if (destroyed) return
        pause()
        stop()
        mapView.onDestroy()
        destroyed = true
    }

    private fun pause() {
        if (resumed) {
            mapView.onPause()
            resumed = false
        }
    }

    private fun stop() {
        pause()
        if (started) {
            mapView.onStop()
            started = false
        }
    }
}

@Composable
fun RadarImageMap(
    session: RadarSession,
    bracket: RadarTimelineBracket,
    mapPlace: SavedPlace,
    onLongPress: (GeoPoint) -> Unit,
    modifier: Modifier = Modifier,
    markerPlace: SavedPlace = mapPlace,
    followLive: Boolean = false,
    onManualCameraGesture: () -> Unit = {},
    recenterSignal: Int = 0,
    isPlaying: Boolean = false,
    onRendererStatus: (RadarRendererStatus) -> Unit = {},
    mapStyle: RadarMapStyle = RadarMapStyle.DARK,
    cameraMemory: RadarCameraMemory,
    windGrid: WindGrid? = null,
    windArrowScale: Float = 1f,
    satelliteCatalogs: List<EumetLayerMetadata> = emptyList(),
    enabledSatelliteLayers: Set<RadarMapLayer> = emptySet(),
    satelliteDisplayEpochSeconds: Long,
    satelliteTimelineStartEpochSeconds: Long,
    satelliteTimelineEndEpochSeconds: Long,
    satelliteWeather: CurrentWeather? = null,
    onSatellitePreparation: (RadarMapLayer, SatellitePreparationStatus?) -> Unit = { _, _ -> },
    onLayerError: (RadarMapLayer, String) -> Unit = { _, _ -> },
    onMapStyleError: (String?) -> Unit = {},
    onWindViewportChanged: (WindViewport) -> Unit = {},
) {
    key(session) {
        RadarImageMapInstance(
            session,
            bracket,
            mapPlace,
            onLongPress,
            modifier,
            markerPlace,
            followLive,
            onManualCameraGesture,
            recenterSignal,
            isPlaying,
            onRendererStatus,
            mapStyle,
            cameraMemory,
            windGrid,
            windArrowScale,
            satelliteCatalogs,
            enabledSatelliteLayers,
            satelliteDisplayEpochSeconds,
            satelliteTimelineStartEpochSeconds,
            satelliteTimelineEndEpochSeconds,
            satelliteWeather,
            onSatellitePreparation,
            onLayerError,
            onMapStyleError,
            onWindViewportChanged,
        )
    }
}

@Composable
private fun RadarImageMapInstance(
    session: RadarSession,
    bracket: RadarTimelineBracket,
    mapPlace: SavedPlace,
    onLongPress: (GeoPoint) -> Unit,
    modifier: Modifier,
    markerPlace: SavedPlace,
    followLive: Boolean,
    onManualCameraGesture: () -> Unit,
    recenterSignal: Int,
    isPlaying: Boolean,
    onRendererStatus: (RadarRendererStatus) -> Unit,
    mapStyle: RadarMapStyle,
    cameraMemory: RadarCameraMemory,
    windGrid: WindGrid?,
    windArrowScale: Float,
    satelliteCatalogs: List<EumetLayerMetadata>,
    enabledSatelliteLayers: Set<RadarMapLayer>,
    satelliteDisplayEpochSeconds: Long,
    satelliteTimelineStartEpochSeconds: Long,
    satelliteTimelineEndEpochSeconds: Long,
    satelliteWeather: CurrentWeather?,
    onSatellitePreparation: (RadarMapLayer, SatellitePreparationStatus?) -> Unit,
    onLayerError: (RadarMapLayer, String) -> Unit,
    onMapStyleError: (String?) -> Unit,
    onWindViewportChanged: (WindViewport) -> Unit,
) {
    val context = LocalContext.current
    val applicationContext = context.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val satelliteFrameStore = remember(applicationContext) {
        SatelliteFrameStoreProvider.get(File(applicationContext.cacheDir, "satellite-frames"))
    }
    val mapView = remember {
        SatelliteAmbientCache.configure(context)
        // Texture mode keeps MapLibre in the normal View hierarchy so our transparent GLES
        // TextureView can reliably composite above it on every Android surface compositor.
        val options = MapLibreMapOptions.createFromAttributes(context, null)
            .textureMode(true)
            .foregroundLoadColor(RadarMapAppearance.loadingBackgroundArgb(mapStyle))
            // The style's attribution remains available through MapLibre's compact info
            // control. The optional MapLibre logo and the old duplicate Compose credit are
            // intentionally omitted.
            .logoEnabled(false)
            .attributionEnabled(true)
            .attributionGravity(Gravity.BOTTOM or Gravity.START)
            .attributionMargins(intArrayOf(4, 4, 4, 4).map {
                (it * context.resources.displayMetrics.density).roundToInt()
            }.toIntArray())
        MapView(context, options).apply {
            setBackgroundColor(RadarMapAppearance.loadingBackgroundArgb(mapStyle))
            onCreate(Bundle())
        }
    }
    val currentStatusCallback by rememberUpdatedState(onRendererStatus)
    val currentLayerError by rememberUpdatedState(onLayerError)
    val currentSatellitePreparation by rememberUpdatedState(onSatellitePreparation)
    val currentMapStyleError by rememberUpdatedState(onMapStyleError)
    val currentWindViewportCallback by rememberUpdatedState(onWindViewportChanged)
    val satellitePlaceKey = "${mapPlace.id}:${(mapPlace.latitude * 10).toInt()}:" +
        "${(mapPlace.longitude * 10).toInt()}"
    val satelliteRegion = remember(satellitePlaceKey) { SatelliteRegionPolicy.select(mapPlace) }
    val regionalCatalogs = remember(satelliteCatalogs, satelliteRegion) {
        satelliteCatalogs.mapNotNull { SatelliteRegionPolicy.clip(it, satelliteRegion) }
    }
    // Satellite resources are fixed to the selected place-owned region. Camera pan/zoom never
    // changes their URL, frame plan, or readiness identity.
    val satelliteCacheContext = remember(satelliteRegion) { SatelliteCacheContext(satelliteRegion) }
    val desiredCloudFrame = remember(
        regionalCatalogs, satelliteDisplayEpochSeconds, satelliteWeather, satellitePlaceKey,
    ) {
        SatelliteFrameSelectionPolicy.clouds(
            regionalCatalogs, satelliteWeather, mapPlace, satelliteDisplayEpochSeconds,
        )
    }
    val desiredLightningFrame = remember(regionalCatalogs, satelliteDisplayEpochSeconds) {
        SatelliteFrameSelectionPolicy.lightning(regionalCatalogs, satelliteDisplayEpochSeconds)
    }
    val satelliteWindow = remember(
        regionalCatalogs, satelliteWeather, satellitePlaceKey,
        satelliteTimelineStartEpochSeconds, satelliteTimelineEndEpochSeconds,
    ) {
        SatelliteFrameWindowPolicy.frames(
            regionalCatalogs, satelliteWeather, mapPlace,
            satelliteTimelineStartEpochSeconds, satelliteTimelineEndEpochSeconds,
        )
    }
    var preparedSatellitePlans by remember {
        mutableStateOf<Map<RadarMapLayer, SatellitePreparedPlan>>(emptyMap())
    }
    var satelliteRecoveryGeneration by remember {
        mutableStateOf<Map<RadarMapLayer, Int>>(emptyMap())
    }
    SatelliteLayerRenderPolicy.renderOrder.forEach { choice ->
        key(choice) {
            val enabled = choice in enabledSatelliteLayers
            val metadataFrames = satelliteWindow[choice].orEmpty()
            val metadataPlanKey = remember(metadataFrames, satelliteCacheContext) {
                if (metadataFrames.isEmpty()) null else
                    com.rainalarm.app.data.SatelliteFullSetPolicy.planKey(
                        metadataFrames, satelliteCacheContext,
                    )
            }
            val recoveryGeneration = satelliteRecoveryGeneration[choice] ?: 0
            LaunchedEffect(
                enabled, satelliteCacheContext.identity, metadataPlanKey, recoveryGeneration,
            ) {
                if (!enabled) {
                    preparedSatellitePlans = preparedSatellitePlans - choice
                    currentSatellitePreparation(choice, null)
                    return@LaunchedEffect
                }
                if (metadataFrames.isEmpty() || metadataPlanKey == null) {
                    // Catalog loading/refresh is not a completed frame set. Keep an existing
                    // same-region plan visible until a replacement can be verified.
                    if (preparedSatellitePlans[choice]?.cacheContext != satelliteCacheContext) {
                        preparedSatellitePlans = preparedSatellitePlans - choice
                    }
                    currentSatellitePreparation(choice, null)
                    return@LaunchedEffect
                }
                val requests = try {
                    metadataFrames.map {
                        SatelliteFrameAssetPolicy.request(it, satelliteCacheContext)
                    }.distinctBy { it.diskKey }
                } catch (failure: Throwable) {
                    if (failure is CancellationException) throw failure
                    Log.e("RainRadarLayers", "${choice.label} regional plan is invalid", failure)
                    currentSatellitePreparation(choice, SatellitePreparationStatus.Failed(
                        "${choice.label} preparation failed · refresh",
                    ))
                    return@LaunchedEffect
                }
                currentSatellitePreparation(
                    choice, SatellitePreparationStatus.Preparing(0, requests.size),
                )
                try {
                    val frames = satelliteFrameStore.prepare(requests) { ready, total ->
                        withContext(Dispatchers.Main.immediate) {
                            currentSatellitePreparation(
                                choice, SatellitePreparationStatus.Preparing(ready, total),
                            )
                        }
                    }
                    preparedSatellitePlans = preparedSatellitePlans + (
                        choice to SatellitePreparedPlan(
                            metadataPlanKey, satelliteCacheContext, frames, recoveryGeneration,
                        )
                    )
                    currentSatellitePreparation(choice, SatellitePreparationStatus.Rendering)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    Log.e("RainRadarLayers", "${choice.label} frame preparation failed", failure)
                    currentSatellitePreparation(choice, SatellitePreparationStatus.Failed(
                        "${choice.label} preparation failed · refresh",
                    ))
                    currentLayerError(choice, "${choice.label} imagery could not load · refresh")
                }
            }
        }
    }
    val satelliteRequests = remember(
        desiredCloudFrame, desiredLightningFrame, preparedSatellitePlans,
        satelliteCacheContext,
    ) {
        buildMap {
            preparedSatellitePlans[RadarMapLayer.FOG]
                ?.takeIf { it.cacheContext == satelliteCacheContext }?.let { plan ->
                    put(RadarMapLayer.FOG, SatelliteOverlayRequest(
                        SatellitePreparedFramePolicy.desired(plan.frames, desiredCloudFrame),
                        plan.frames,
                        plan.generation,
                    ))
                }
            preparedSatellitePlans[RadarMapLayer.LIGHTNING]
                ?.takeIf { it.cacheContext == satelliteCacheContext }?.let { plan ->
                    put(RadarMapLayer.LIGHTNING, SatelliteOverlayRequest(
                        SatellitePreparedFramePolicy.desired(plan.frames, desiredLightningFrame),
                        plan.frames,
                        plan.generation,
                    ))
                }
        }
    }
    val latestSatelliteRequests by rememberUpdatedState(satelliteRequests)
    val latestEnabledSatelliteLayers by rememberUpdatedState(enabledSatelliteLayers)
    val latestSatellitePlayback by rememberUpdatedState(isPlaying)
    val latestSatelliteCacheContext by rememberUpdatedState(satelliteCacheContext)
    val displayedWindGrid = remember(windGrid, satelliteDisplayEpochSeconds) {
        windGrid?.displayedAt(satelliteDisplayEpochSeconds)
    }
    val latestMapPlace by rememberUpdatedState(mapPlace)
    val latestMarkerPlace by rememberUpdatedState(markerPlace)
    val currentManualGesture by rememberUpdatedState(onManualCameraGesture)
    val latestRecenterSignal by rememberUpdatedState(recenterSignal)
    val staticFallback = remember(session) {
        session.legacyArchive?.let { LegacyStaticFallbackOverlayView(context, session) }
    }
    val windView = remember(mapView) { WindFieldView(context) }
    val overlay: RadarOverlayController = remember(session) {
        RadarGlOverlayView(context) { status ->
            if (RadarStaticFallbackPolicy.shouldShow(session.legacyArchive != null, status)) {
                staticFallback?.show()
            } else {
                staticFallback?.hide()
            }
            currentStatusCallback(status)
        }.apply { bindSession(session) }
    }
    val mapLifecycle = remember(mapView) { MapViewLifecycle(mapView) }
    val mapRevealGate = remember(mapView) { RadarMapRevealGate() }
    val mapCover = remember(mapView) {
        View(context).apply { setBackgroundColor(RadarMapAppearance.loadingBackgroundArgb(mapStyle)) }
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var cameraListener by remember { mutableStateOf<MapLibreMap.OnCameraMoveListener?>(null) }
    var cameraStartListener by remember { mutableStateOf<MapLibreMap.OnCameraMoveStartedListener?>(null) }
    var cameraIdleListener by remember { mutableStateOf<MapLibreMap.OnCameraIdleListener?>(null) }
    var appliedPlace by remember(mapView) { mutableStateOf<SavedPlace?>(null) }
    var lastBracket by remember(session) { mutableStateOf<RadarTimelineBracket?>(null) }
    var lastPlaying by remember(session) { mutableStateOf<Boolean?>(null) }
    var lastMarker by remember(session) { mutableStateOf<SavedPlace?>(null) }
    val satelliteBuffers = remember(mapView) {
        SatelliteLayerBuffers(
            onLayerError = { layer, message ->
                currentSatellitePreparation(layer, SatellitePreparationStatus.Failed(message))
                currentLayerError(layer, message)
            },
            onFrameInvalidated = { layer, frame ->
                satelliteFrameStore.invalidate(frame.request)
                satelliteRecoveryGeneration = satelliteRecoveryGeneration + (
                    layer to ((satelliteRecoveryGeneration[layer] ?: 0) + 1)
                )
            },
            onFrameRendered = { layer ->
                currentSatellitePreparation(layer, SatellitePreparationStatus.Ready)
            },
        )
    }
    fun saveCamera(ready: MapLibreMap, place: SavedPlace) {
        val position = ready.cameraPosition
        val center = position.target ?: return
        cameraMemory.capture(place, center.latitude, center.longitude, position.zoom)
    }
    val teardown = remember(session, mapView, overlay, staticFallback) {
        RadarResourceTeardown(
            stopOverlay = {
                satelliteBuffers.clear()
                overlay.dispose()
                staticFallback?.dispose()
            },
            detachMap = {
                val ready = map
                val listener = cameraListener
                val idleListener = cameraIdleListener
                if (ready != null) appliedPlace?.let { saveCamera(ready, it) }
                if (ready != null && listener != null) ready.removeOnCameraMoveListener(listener)
                cameraStartListener?.let { if (ready != null) ready.removeOnCameraMoveStartedListener(it) }
                if (ready != null && idleListener != null) ready.removeOnCameraIdleListener(idleListener)
            },
            destroyMap = mapLifecycle::destroy,
            releaseSession = session::release,
        )
    }

    DisposableEffect(mapView, lifecycle, teardown) {
        val observer = object : DefaultLifecycleObserver by mapLifecycle {
            override fun onDestroy(owner: LifecycleOwner) = teardown.close()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            teardown.close()
        }
    }

    val startingListener = remember(mapView, teardown) {
        MapView.OnWillStartRenderingFrameListener {
            if (!teardown.isClosed) mapRevealGate.frameStarted()
        }
    }
    val renderedListener = remember(mapView, teardown) {
        MapView.OnDidFinishRenderingFrameListener { fully, _, _ ->
            if (fully && !teardown.isClosed) satelliteBuffers.onFullyRendered()
            val revealCurrentStyle = !teardown.isClosed && mapView.width > 0 && mapView.height > 0 &&
                mapRevealGate.frameRendered(fully)
            if (revealCurrentStyle) mapView.post {
                if (!teardown.isClosed && !mapRevealGate.isCovered) {
                    mapCover.visibility = View.GONE
                    currentMapStyleError(null)
                }
            }
        }
    }
    val failedListener = remember(mapView, teardown) {
        MapView.OnDidFailLoadingMapListener { reason ->
            val showFailure = !teardown.isClosed && mapRevealGate.markFailed()
            if (showFailure) mapView.post {
                if (!teardown.isClosed && mapRevealGate.hasFailed) {
                    Log.w("RainRadarMap", "Base map failed to load: $reason")
                    currentMapStyleError("Map style unavailable · refresh to retry")
                }
            }
        }
    }
    DisposableEffect(mapView, teardown, startingListener, renderedListener, failedListener) {
        onDispose {
            mapView.removeOnWillStartRenderingFrameListener(startingListener)
            mapView.removeOnDidFinishRenderingFrameListener(renderedListener)
            mapView.removeOnDidFailLoadingMapListener(failedListener)
        }
    }

    AndroidView(
        factory = {
            FrameLayout(context).apply {
                setBackgroundColor(RadarMapAppearance.loadingBackgroundArgb(mapStyle))
                // Register synchronously before attaching MapView: a cached style may render
                // before Compose's DisposableEffect runs after this factory returns.
                mapView.addOnWillStartRenderingFrameListener(startingListener)
                mapView.addOnDidFinishRenderingFrameListener(renderedListener)
                mapView.addOnDidFailLoadingMapListener(failedListener)
                addView(
                    mapView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
                staticFallback?.let { fallback ->
                    addView(
                        fallback,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT,
                        ),
                    )
                }
                addView(
                    overlay.overlayView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
                overlay.overlayView.bringToFront()
                addView(windView, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                windView.bringToFront()
                // Attach the opaque, map-tone cover before this MapView can display its
                // default texture; reveal only after the requested style renders a full frame.
                addView(mapCover, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                mapCover.bringToFront()
                mapView.getMapAsync { ready ->
                    if (teardown.isClosed) return@getMapAsync
                    map = ready
                    overlay.attachMap(ready)
                    windView.bind(ready)
                    staticFallback?.attachMap(ready)
                    ready.setMaxZoomPreference(21.0)
                    ready.uiSettings.isRotateGesturesEnabled = false
                    ready.uiSettings.isTiltGesturesEnabled = false
                    ready.uiSettings.isAttributionEnabled = true
                    val mapWidth = mapView.width.takeIf { it > 0 }
                        ?: resources.displayMetrics.widthPixels
                    val initialPlace = latestMapPlace
                    val target = cameraMemory.target(initialPlace, mapWidth, latestRecenterSignal)
                    appliedPlace = initialPlace
                    ready.cameraPosition = northUpCamera(target)
                    saveCamera(ready, initialPlace)
                    val listener = MapLibreMap.OnCameraMoveListener {
                        appliedPlace?.let { saveCamera(ready, it) }
                        overlay.onCameraMoved()
                        windView.cameraMoved()
                        staticFallback?.onCameraMoved()
                    }
                    cameraListener = listener
                    ready.addOnCameraMoveListener(listener)
                    val startListener = MapLibreMap.OnCameraMoveStartedListener { reason ->
                        if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE)
                            currentManualGesture()
                    }
                    cameraStartListener = startListener
                    ready.addOnCameraMoveStartedListener(startListener)
                    val idle = MapLibreMap.OnCameraIdleListener {
                        if (!teardown.isClosed && mapView.width > 0 && mapView.height > 0) {
                            // MapLibre can publish its final projection after the last move callback.
                            // Re-project the radar, wind and selected marker at the settled camera.
                            // Satellite ImageSources remain fixed to the selected region and do not
                            // participate in viewport-driven reloads.
                            overlay.onCameraMoved()
                            staticFallback?.onCameraMoved()
                            windView.cameraMoved()
                            val bounds = ready.projection.visibleRegion.latLngBounds
                            currentWindViewportCallback(WindViewport(
                                bounds.getLatSouth(), bounds.getLonWest(), bounds.getLatNorth(), bounds.getLonEast(),
                            ))
                        }
                    }
                    cameraIdleListener = idle
                    ready.addOnCameraIdleListener(idle)
                    mapView.post { if (!teardown.isClosed) idle.onCameraIdle() }
                    overlay.onCameraMoved()
                }
            }
        },
        update = { container ->
            container.setBackgroundColor(RadarMapAppearance.loadingBackgroundArgb(mapStyle))
            mapView.setBackgroundColor(RadarMapAppearance.loadingBackgroundArgb(mapStyle))
            if (lastBracket != bracket || lastPlaying != isPlaying) {
                overlay.setState(bracket, isPlaying, markerPlace)
                lastBracket = bracket
                lastPlaying = isPlaying
            } else overlay.setMarker(markerPlace)
            if (lastMarker != markerPlace) {
                staticFallback?.setPlace(markerPlace)
                lastMarker = markerPlace
            }
            windView.update(displayedWindGrid, windArrowScale)
        },
        modifier = modifier,
    )

    // Only the basemap style changes: MapView, camera, overlay and session retain ownership.
    // Camera was explicitly positioned before style loading, so style defaults cannot reset it.
    androidx.compose.runtime.DisposableEffect(map, mapStyle) {
        val ready = map
        if (ready == null || teardown.isClosed) return@DisposableEffect onDispose { }
        var active = true
        val styleGeneration = mapRevealGate.styleRequested()
        mapCover.setBackgroundColor(RadarMapAppearance.loadingBackgroundArgb(mapStyle))
        mapCover.visibility = View.VISIBLE
        currentMapStyleError(null)
        try {
            ready.setStyle(RadarMapAppearance.styleUrl(mapStyle)) {
                if (active && !teardown.isClosed) {
                    if (RadarMapLabelContrastPolicy.paletteFor(mapStyle) != null) {
                        applyMapLabelContrast(it, mapStyle)
                    }
                }
                if (active && !teardown.isClosed && mapRevealGate.styleLoaded(styleGeneration)) {
                    satelliteBuffers.onStyleLoaded(
                        it, latestSatelliteRequests, latestEnabledSatelliteLayers,
                        latestSatellitePlayback, latestSatelliteCacheContext,
                    )
                    overlay.onCameraMoved()
                    staticFallback?.onCameraMoved()
                    mapView.post { if (!teardown.isClosed) cameraIdleListener?.onCameraIdle() }
                }
            }
        } catch (failure: Exception) {
            Log.e("RainRadarMap", "Base map style could not start", failure)
            if (mapRevealGate.markFailed()) currentMapStyleError("Map style unavailable · refresh to retry")
        }
        onDispose { active = false }
    }

    LaunchedEffect(mapView, mapStyle) {
        delay(25_000)
        if (!teardown.isClosed && mapRevealGate.isCovered && mapRevealGate.markFailed()) {
            Log.w("RainRadarMap", "Base map did not finish rendering within 25 seconds")
            currentMapStyleError("Map imagery still loading · refresh to retry")
        }
    }

    DisposableEffect(
        map, satelliteRequests, enabledSatelliteLayers, isPlaying, satelliteCacheContext,
    ) {
        val ready = map
        if (ready == null || teardown.isClosed) return@DisposableEffect onDispose { }
        var active = true
        ready.getStyle { style ->
            if (active && !teardown.isClosed) satelliteBuffers.reconcile(
                style, satelliteRequests, enabledSatelliteLayers, isPlaying,
                satelliteCacheContext,
            )
        }
        onDispose { active = false }
    }

    DisposableEffect(map, onLongPress) {
        val ready = map
        if (ready == null || teardown.isClosed) return@DisposableEffect onDispose { }
        val listener = MapLibreMap.OnMapLongClickListener { point ->
            onLongPress(GeoPoint(point.latitude, point.longitude))
            true
        }
        ready.addOnMapLongClickListener(listener)
        onDispose { ready.removeOnMapLongClickListener(listener) }
    }

    LaunchedEffect(map, mapPlace.id, recenterSignal) {
        val ready = map ?: return@LaunchedEffect
        if (teardown.isClosed) return@LaunchedEffect
        val oldPlace = appliedPlace
        if (oldPlace?.id == mapPlace.id && !cameraMemory.hasPendingRecenter(recenterSignal)) {
            appliedPlace = mapPlace
            return@LaunchedEffect
        }
        oldPlace?.let { saveCamera(ready, it) }
        val mapWidth = mapView.width.takeIf { it > 0 } ?: mapView.resources.displayMetrics.widthPixels
        val cameraPlace = if (mapPlace.isCurrentLocation) latestMarkerPlace else mapPlace
        val target = cameraMemory.target(cameraPlace, mapWidth, recenterSignal)
        appliedPlace = cameraPlace
        ready.cameraPosition = northUpCamera(target)
        saveCamera(ready, cameraPlace)
    }

    // A live marker changes the snapshot's reference place, not the camera viewport.
    LaunchedEffect(map, markerPlace) {
        val ready = map ?: return@LaunchedEffect
        if (teardown.isClosed || appliedPlace?.id != mapPlace.id) return@LaunchedEffect
        val reference = if (mapPlace.isCurrentLocation) markerPlace else mapPlace
        appliedPlace = reference
        saveCamera(ready, reference)
    }

    LaunchedEffect(map, followLive, markerPlace.latitude, markerPlace.longitude) {
        val ready = map ?: return@LaunchedEffect
        if (!followLive || teardown.isClosed) return@LaunchedEffect
        val target = RadarCameraTarget(markerPlace.latitude, markerPlace.longitude, ready.cameraPosition.zoom)
        appliedPlace = markerPlace
        ready.cameraPosition = northUpCamera(target)
        saveCamera(ready, markerPlace)
    }
}

private fun northUpCamera(target: RadarCameraTarget): CameraPosition =
    CameraPosition.Builder()
        .target(LatLng(target.latitude, target.longitude))
        .zoom(target.zoom)
        .bearing(0.0)
        .tilt(0.0)
        .build()
