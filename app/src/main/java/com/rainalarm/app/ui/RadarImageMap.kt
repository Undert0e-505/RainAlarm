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
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.view.isVisible
import com.rainalarm.app.BuildConfig
import com.rainalarm.app.data.RadarSession
import com.rainalarm.app.data.RadarCoverageRaster
import com.rainalarm.app.data.RadarProviderKind
import com.rainalarm.app.data.CoverageMaskDarknessPreference
import com.rainalarm.app.data.RegionalRadarArea
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
import com.rainalarm.app.domain.GeoQuad
import com.rainalarm.app.domain.MeteoNominalCoverage
import com.rainalarm.app.domain.MeteoNominalCoveragePolygon
import com.rainalarm.app.domain.NorthUpRadarGeoreference
import com.rainalarm.app.domain.RadarMotionPolicy
import com.rainalarm.app.domain.RadarOverlayFramePlan
import com.rainalarm.app.domain.RadarOverlayPlanner
import com.rainalarm.app.domain.RadarResolutionTier
import com.rainalarm.app.domain.RadarResourceTeardown
import com.rainalarm.app.domain.RadarCameraMemory
import com.rainalarm.app.domain.RadarCameraIntentOwner
import com.rainalarm.app.domain.RadarCameraTarget
import com.rainalarm.app.domain.RadarEntryFocusCompletionPolicy
import com.rainalarm.app.domain.WebMercator
import com.rainalarm.app.domain.RadarTimelineBracket
import com.rainalarm.app.data.LocationCadenceDiagnostics
import com.rainalarm.app.data.TravelModeDiagnostics
import com.rainalarm.app.domain.RadarEntryFocusPolicy
import com.rainalarm.app.domain.EntryTransitionPhase
import com.rainalarm.app.domain.EntryTransitionState
import com.rainalarm.app.domain.RadarEntryFrameHandshake
import com.rainalarm.app.domain.RadarEntryFramePolicy
import com.rainalarm.app.domain.RadarNativePresentationGate
import com.rainalarm.app.domain.RadarMapGestureOwnership
import com.rainalarm.app.domain.RadarMapTouchPolicy
import com.rainalarm.app.domain.RadarTravelTransitionReason
import com.rainalarm.app.domain.RainAlarmPalette
import org.maplibre.android.MapLibre
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngQuad
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.style.sources.ImageSource
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.maps.Style
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
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

    /**
     * MapLibre captures this renderer colour when MapView is constructed and cannot update it
     * when an asynchronously restored appearance changes. Texture mode lets the mutable,
     * style-aware MapView/container background show through instead.
     */
    fun rendererForegroundArgb(): Int = 0x00000000
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

internal data class RadarCoverageMask(
    val outerRing: List<GeoPoint>,
    val coverage: List<MeteoNominalCoveragePolygon>,
    val colorArgb: Int,
    val opacity: Float,
)

/** A geographic unknown-coverage mask used only for evidence-backed nominal coverage. */
internal object RadarCoverageMaskPolicy {
    // One normalized user setting drives both vector Meteo envelopes and raster OPERA/
    // RainViewer unknown fields. Maximums stay translucent so labels and map context survive.
    fun palette(
        style: RadarMapStyle,
        darkness: Float = CoverageMaskDarknessPreference.DEFAULT,
    ): Pair<Int, Float> {
        val strength = CoverageMaskDarknessPreference.decode(darkness)
        val (color, maximumOpacity) = when (style) {
            RadarMapStyle.DARK -> 0xFF000000.toInt() to 0.90f
            RadarMapStyle.SLATE -> 0xFF101827.toInt() to 0.84f
            RadarMapStyle.LIGHT -> 0xFF24313A.toInt() to 0.80f
        }
        return color to maximumOpacity * strength
    }

    fun mask(
        provider: RadarProviderKind,
        area: RegionalRadarArea?,
        style: RadarMapStyle,
        darkness: Float = CoverageMaskDarknessPreference.DEFAULT,
    ): RadarCoverageMask? {
        if (provider != RadarProviderKind.METEOGROUP_REGIONAL || area == null) return null
        // Regional feed rectangles are image geometry, not evidence of observation reach. Only
        // products with separately versioned nominal network envelopes receive a vector mask.
        val coverage = MeteoNominalCoverage.forAreaId(area.id) ?: return null
        val outer = listOf(
            GeoPoint(-WebMercator.MAX_LATITUDE, -180.0),
            GeoPoint(-WebMercator.MAX_LATITUDE, 180.0),
            GeoPoint(WebMercator.MAX_LATITUDE, 180.0),
            GeoPoint(WebMercator.MAX_LATITUDE, -180.0),
            GeoPoint(-WebMercator.MAX_LATITUDE, -180.0),
        )
        val (color, opacity) = palette(style, darkness)
        return RadarCoverageMask(outer, coverage, color, opacity)
    }

}

/**
 * Splits the world outside a dynamic provider raster into small geographic image bands. The
 * central alpha mask and every outside band consequently use the same RasterLayer compositor,
 * opacity, resampling and layer order; mixing a raster centre with a vector world fill produced a
 * visible rectangular shade boundary at low zoom. Each outside bitmap is only 2x2 pixels.
 */
internal object RadarCoverageRasterBandPolicy {
    private const val EPSILON = 1e-7
    private val longitudeCuts = listOf(-180.0, -90.0, 0.0, 90.0, 180.0)

    fun outsideBands(bounds: GeoQuad): List<GeoQuad>? {
        val west = bounds.topLeft.longitude
        val east = bounds.topRight.longitude
        val north = bounds.topLeft.latitude
        val south = bounds.bottomLeft.latitude
        val axisAligned = kotlin.math.abs(bounds.topRight.latitude - north) < EPSILON &&
            kotlin.math.abs(bounds.bottomRight.latitude - south) < EPSILON &&
            kotlin.math.abs(bounds.bottomLeft.longitude - west) < EPSILON &&
            kotlin.math.abs(bounds.bottomRight.longitude - east) < EPSILON
        // Dateline-crossing or non-axis-aligned imagery needs a different split. Failing open is
        // safer than darkening the wrong hemisphere.
        if (!axisAligned || west !in -180.0..180.0 || east !in -180.0..180.0 ||
            north !in -WebMercator.MAX_LATITUDE..WebMercator.MAX_LATITUDE ||
            south !in -WebMercator.MAX_LATITUDE..WebMercator.MAX_LATITUDE ||
            west >= east || south >= north) {
            return null
        }

        return buildList {
            longitudeSlices(-180.0, 180.0).forEach { (left, right) ->
                rectangle(WebMercator.MAX_LATITUDE, north, left, right)?.let(::add)
                rectangle(south, -WebMercator.MAX_LATITUDE, left, right)?.let(::add)
            }
            longitudeSlices(-180.0, west).forEach { (left, right) ->
                rectangle(north, south, left, right)?.let(::add)
            }
            longitudeSlices(east, 180.0).forEach { (left, right) ->
                rectangle(north, south, left, right)?.let(::add)
            }
        }
    }

    fun sourceId(index: Int): String = "rain-alarm-provider-coverage-outside-$index-source"
    fun layerId(index: Int): String = "rain-alarm-provider-coverage-outside-$index-layer"

    private fun longitudeSlices(west: Double, east: Double): List<Pair<Double, Double>> {
        if (east - west <= EPSILON) return emptyList()
        val points = buildList {
            add(west)
            longitudeCuts.filterTo(this) { it > west + EPSILON && it < east - EPSILON }
            add(east)
        }
        return points.zipWithNext()
    }

    private fun rectangle(north: Double, south: Double, west: Double, east: Double): GeoQuad? {
        if (north - south <= EPSILON || east - west <= EPSILON) return null
        return GeoQuad(
            GeoPoint(north, west),
            GeoPoint(north, east),
            GeoPoint(south, east),
            GeoPoint(south, west),
        )
    }
}

private class RadarCoverageMaskController {
    private val vectorSourceId = "rain-alarm-regional-coverage-mask-source"
    private val vectorLayerId = "rain-alarm-regional-coverage-mask-layer"
    private val rasterSourceId = "rain-alarm-provider-coverage-mask-source"
    private val rasterLayerId = "rain-alarm-provider-coverage-mask-layer"
    private var currentStyle: Style? = null
    private val dynamicRasterResources = mutableListOf<DynamicRasterResource>()
    private var currentKey: String? = null

    fun reconcile(
        style: Style,
        session: RadarSession?,
        mapStyle: RadarMapStyle,
        darkness: Float,
    ) {
        val safeDarkness = CoverageMaskDarknessPreference.decode(darkness)
        val nextKey = session?.let {
            "${System.identityHashCode(it)}:${it.providerSelection.active}:$mapStyle:$safeDarkness"
        }
        if (currentStyle === style && currentKey == nextKey) return
        currentStyle?.let(::remove)
        currentStyle = style
        currentKey = nextKey
        val selection = session?.providerSelection ?: return
        session.mapCoverage?.let { raster ->
            addRaster(style, raster, mapStyle, safeDarkness)
            return
        }
        val mask = RadarCoverageMaskPolicy.mask(
            selection.active, session.region, mapStyle, safeDarkness,
        ) ?: return
        addVector(style, mask)
    }

    private fun addVector(style: Style, mask: RadarCoverageMask) {
        fun List<GeoPoint>.points() = map { Point.fromLngLat(it.longitude, it.latitude) }
        fun List<GeoPoint>.signedArea(): Double = zipWithNext().sumOf { (first, second) ->
            first.longitude * second.latitude - second.longitude * first.latitude
        } / 2.0
        fun List<GeoPoint>.clockwise(): List<GeoPoint> =
            if (signedArea() <= 0.0) this else reversed()
        fun List<GeoPoint>.counterClockwise(): List<GeoPoint> =
            if (signedArea() >= 0.0) this else reversed()
        val worldRings = buildList {
            add(mask.outerRing.counterClockwise().points())
            // Each disjoint nominal component is a hole in the world-sized unknown scrim.
            mask.coverage.forEach { add(it.exterior.clockwise().points()) }
        }
        val features = buildList {
            add(Feature.fromGeometry(Polygon.fromLngLats(worldRings)))
            // A hole inside a known component is unknown again, so render it as its own fill.
            mask.coverage.forEach { polygon ->
                polygon.holes.forEach { hole ->
                    add(Feature.fromGeometry(Polygon.fromLngLats(listOf(
                        hole.counterClockwise().points(),
                    ))))
                }
            }
        }
        style.addSource(GeoJsonSource(
            vectorSourceId,
            FeatureCollection.fromFeatures(features),
        ))
        val layer = FillLayer(vectorLayerId, vectorSourceId).withProperties(
            PropertyFactory.fillColor(mask.colorArgb),
            PropertyFactory.fillOpacity(mask.opacity),
        )
        val firstWeatherLayer = style.layers.firstOrNull {
            it.id.startsWith("rain-alarm-satellite-")
        }
        if (firstWeatherLayer != null) style.addLayerBelow(layer, firstWeatherLayer.id)
        else style.addLayer(layer)
    }

    fun clear() {
        currentStyle?.let(::remove)
        currentStyle = null
        currentKey = null
    }

    private fun addRaster(
        style: Style,
        raster: RadarCoverageRaster,
        mapStyle: RadarMapStyle,
        darkness: Float,
    ) {
        if (raster.bitmap.isRecycled || raster.bitmap.width <= 0 || raster.bitmap.height <= 0) return
        val (color, opacity) = RadarCoverageMaskPolicy.palette(mapStyle, darkness)
        val tinted = tintUnknownRaster(raster.bitmap, color)
        try {
            addRasterResource(style, rasterSourceId, rasterLayerId, raster.bounds, tinted, opacity)
            RadarCoverageRasterBandPolicy.outsideBands(raster.bounds).orEmpty()
                .forEachIndexed { index, bounds ->
                    val solid = Bitmap.createBitmap(
                        intArrayOf(color, color, color, color),
                        2,
                        2,
                        Bitmap.Config.ARGB_8888,
                    )
                    addRasterResource(
                        style,
                        RadarCoverageRasterBandPolicy.sourceId(index),
                        RadarCoverageRasterBandPolicy.layerId(index),
                        bounds,
                        solid,
                        opacity,
                    )
                }
        } catch (failure: Exception) {
            if (dynamicRasterResources.none { it.bitmap === tinted } && !tinted.isRecycled) {
                tinted.recycle()
            }
            Log.w("RainRadarCoverage", "Provider coverage mask could not be rendered", failure)
            remove(style)
        }
    }

    private fun addRasterResource(
        style: Style,
        sourceId: String,
        layerId: String,
        bounds: GeoQuad,
        bitmap: Bitmap,
        opacity: Float,
    ) {
        val resource = DynamicRasterResource(sourceId, layerId, bitmap)
        // Track before the first style mutation so partial additions are cleaned up on failure.
        dynamicRasterResources += resource
        style.addSource(ImageSource(sourceId, bounds.toLatLngQuad(), bitmap))
        val layer = RasterLayer(layerId, sourceId).withProperties(
            PropertyFactory.rasterOpacity(opacity),
            PropertyFactory.rasterFadeDuration(0f),
            PropertyFactory.rasterResampling("nearest"),
        )
        val firstWeatherLayer = style.layers.firstOrNull {
            it.id.startsWith("rain-alarm-satellite-")
        }
        if (firstWeatherLayer != null) style.addLayerBelow(layer, firstWeatherLayer.id)
        else style.addLayer(layer)
    }

    private fun GeoQuad.toLatLngQuad() = LatLngQuad(
        LatLng(topLeft.latitude, topLeft.longitude),
        LatLng(topRight.latitude, topRight.longitude),
        LatLng(bottomRight.latitude, bottomRight.longitude),
        LatLng(bottomLeft.latitude, bottomLeft.longitude),
    )

    private fun tintUnknownRaster(source: Bitmap, color: Int): Bitmap {
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        val rgb = color and 0x00ffffff
        pixels.indices.forEach { index -> pixels[index] = (pixels[index] and -0x1000000) or rgb }
        return Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        }
    }

    private fun remove(style: Style) {
        dynamicRasterResources.forEach { runCatching { style.removeLayer(it.layerId) } }
        dynamicRasterResources.forEach { runCatching { style.removeSource(it.sourceId) } }
        dynamicRasterResources.map { it.bitmap }.distinctBy { System.identityHashCode(it) }.forEach {
            if (!it.isRecycled) it.recycle()
        }
        dynamicRasterResources.clear()
        runCatching { style.removeLayer(vectorLayerId) }
        runCatching { style.removeSource(vectorSourceId) }
    }

    private data class DynamicRasterResource(
        val sourceId: String,
        val layerId: String,
        val bitmap: Bitmap,
    )
}

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
    val planKey: String?,
    val bitmap: Bitmap,
    val addedAtRenderSequence: Long,
)

internal data class SatelliteOverlayRequest(
    val desired: EumetLayerMetadata?,
    val frames: List<SatelliteCachedFrame>,
    val preparationGeneration: Int,
    val publicationPlanKey: String,
)

private data class SatellitePreparedPlan(
    val planKey: String,
    val cacheContext: SatelliteCacheContext,
    val frames: List<SatelliteCachedFrame>,
    val generation: Int,
    val requestGeneration: Long,
    val startedAtMillis: Long,
    val targetHash: String,
    val trigger: String,
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
    data class Failed(
        val message: String,
        val hasRenderableFallback: Boolean = false,
    ) : SatellitePreparationStatus
}

private data class SatelliteChoiceBuffer(
    var nextGeneration: Long = 0L,
    var desired: EumetLayerMetadata? = null,
    var cacheContext: SatelliteCacheContext? = null,
    var assets: Map<String, SatelliteCachedFrame> = emptyMap(),
    var planKey: String? = null,
    var publicationPlanKey: String? = null,
    var acknowledgedPlanKey: String? = null,
    var requestPresent: Boolean = false,
    var isPlaying: Boolean = false,
    var active: SatelliteBufferSlot? = null,
    var pending: SatelliteBufferSlot? = null,
    var retiring: SatelliteBufferSlot? = null,
    var revealedGeneration: Long? = null,
    var revealedAtRenderSequence: Long? = null,
    var decoding: EumetLayerMetadata? = null,
    var decodingGeneration: Long? = null,
    var decodeJob: Job? = null,
    var pendingPromotionJob: Job? = null,
    var revealConfirmationJob: Job? = null,
)

internal object SatelliteRenderConfirmationPolicy {
    /** MapLibre can omit a second fully-rendered callback once an idle image layer is revealed. */
    const val fallbackMillis = 500L

    fun matches(
        expectedGeneration: Long?,
        activeGeneration: Long?,
        expectedPlanKey: String?,
        activePlanKey: String?,
    ): Boolean = expectedGeneration != null && expectedGeneration == activeGeneration &&
        expectedPlanKey != null && expectedPlanKey == activePlanKey
}

/**
 * Verified compressed frames come from the app-owned cache. Only the desired frame is decoded off
 * the UI thread. MapLibre mutation stays on Main, with active/pending/retiring overlap bounded.
 */
private class SatelliteLayerBuffers(
    private val onLayerError: (RadarMapLayer, String, Boolean) -> Unit,
    private val onFrameInvalidated: (RadarMapLayer, SatelliteCachedFrame) -> Unit,
    private val onFrameRendered: (RadarMapLayer, String?) -> Unit,
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
            state.publicationPlanKey = null
            state.acknowledgedPlanKey = null
            state.requestPresent = false
            state.isPlaying = false
            state.active = null
            state.pending = null
            state.retiring = null
            state.revealedGeneration = null
            state.revealedAtRenderSequence = null
            state.decoding = null
            state.decodingGeneration = null
            state.decodeJob = null
            state.pendingPromotionJob?.cancel()
            state.pendingPromotionJob = null
            state.revealConfirmationJob?.cancel()
            state.revealConfirmationJob = null
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
                    state.revealConfirmationJob?.cancel()
                    state.revealConfirmationJob = null
                    acknowledgeFrame(choice, state, state.active?.planKey)
                    state.revealedGeneration = null
                    state.revealedAtRenderSequence = null
                }
                val pending = state.pending?.takeIf {
                    renderSequence > it.addedAtRenderSequence
                }
                if (pending != null && state.retiring == null) {
                    resolvePending(style, choice, state, pending)
                }
                startLatestIfNeeded(style, choice, state)
            } catch (failure: Exception) {
                Log.e("RainRadarLayers", "${choice.label} raster swap failed", failure)
                state.pending?.let { remove(style, it) }
                state.pending = null
                if (state.active == null) {
                    onLayerError(choice, "${choice.label} layer unavailable", false)
                }
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
            traceBuffer(choice, "request=absent")
            return
        }
        state.requestPresent = true
        state.desired = request.desired
        val nextPlanKey = request.frames.joinToString(
            prefix = "${request.preparationGeneration}|", separator = ";",
        ) { it.request.diskKey }
        if (state.planKey != nextPlanKey) {
            state.decodeJob?.cancel()
            state.pendingPromotionJob?.cancel()
            state.pendingPromotionJob = null
            state.revealConfirmationJob?.cancel()
            state.revealConfirmationJob = null
            state.decodeJob = null
            state.decoding = null
            state.decodingGeneration = null
            state.pending?.let { remove(style, it) }
            state.pending = null
            state.planKey = nextPlanKey
            traceBuffer(
                choice,
                "plan=changed generation=${request.preparationGeneration} frames=${request.frames.size}",
            )
        }
        state.publicationPlanKey = request.publicationPlanKey
        state.assets = request.frames.associateBy { it.metadata.frameIdentity }
        traceBuffer(
            choice,
            "request=present desired=${request.desired != null} assets=${state.assets.size} " +
                "active=${state.active != null} pending=${state.pending != null}",
        )
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
            state.pendingPromotionJob?.cancel()
            state.pendingPromotionJob = null
            state.revealConfirmationJob?.cancel()
            state.revealConfirmationJob = null
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
        if (state.active?.renderIdentity == desiredRenderIdentity) {
            acknowledgeFrame(choice, state, state.publicationPlanKey)
            return
        }
        if (state.pending?.renderIdentity == desiredRenderIdentity ||
            state.decoding?.frameIdentity == metadata.frameIdentity) return
        if (state.pending != null || state.retiring != null || state.revealedGeneration != null) return
        val asset = state.assets[metadata.frameIdentity] ?: return
        val generation = ++state.nextGeneration
        val requestPlanKey = state.planKey
        val publicationPlanKey = state.publicationPlanKey
        state.decoding = metadata
        state.decodingGeneration = generation
        state.decodeJob = scope.launch {
            traceBuffer(choice, "decode=start generation=$generation")
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
                currentCoroutineContext().ensureActive()
                if (currentStyle !== style || state.planKey != requestPlanKey ||
                    state.decodingGeneration != generation) {
                    return@launch
                }
                Log.e("RainRadarLayers", "${choice.label} bitmap decode failed", failure)
                state.assets = state.assets - metadata.frameIdentity
                onFrameInvalidated(choice, asset)
                onLayerError(
                    choice, "${choice.label} frame could not be decoded · refresh",
                    state.active != null,
                )
                null
            }
            if (currentStyle === style && state.planKey == requestPlanKey &&
                state.decodingGeneration == generation) {
                state.decodeJob = null
                state.decoding = null
                state.decodingGeneration = null
            }
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
            if (currentStyle !== style || state.planKey != requestPlanKey ||
                action == SatellitePendingAction.DISCARD) {
                bitmap.recycle()
                startLatestIfNeeded(style, choice, state)
                return@launch
            }
            val slot = add(
                style, metadata, context, generation, publicationPlanKey, PRELOAD_OPACITY, bitmap,
            )
            if (slot == null) {
                if (!bitmap.isRecycled) bitmap.recycle()
                onLayerError(
                    choice, "${choice.label} layer unavailable · refresh",
                    state.active != null,
                )
            } else {
                state.pending = slot
                traceBuffer(choice, "decode=ready pending=true generation=$generation")
                schedulePendingPromotion(style, choice, state, slot)
            }
        }
    }

    private fun add(
        style: Style,
        metadata: EumetLayerMetadata,
        context: SatelliteCacheContext,
        generation: Long,
        planKey: String?,
        opacity: Float,
        bitmap: Bitmap,
    ): SatelliteBufferSlot? {
        val sourceId = SatelliteLayerRenderPolicy.sourceId(metadata.choice, generation)
        val layerId = SatelliteLayerRenderPolicy.layerId(metadata.choice, generation)
        val slot = SatelliteBufferSlot(
            metadata, generation, sourceId, layerId,
            SatelliteCacheIdentity.frame(metadata, context),
            SatelliteRenderIdentity.frame(metadata, context),
            planKey,
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

    private fun acknowledgeFrame(
        choice: RadarMapLayer,
        state: SatelliteChoiceBuffer,
        publicationPlanKey: String?,
    ) {
        if (publicationPlanKey == null || state.acknowledgedPlanKey == publicationPlanKey) return
        state.acknowledgedPlanKey = publicationPlanKey
        traceBuffer(choice, "plan=renderable")
        onFrameRendered(choice, publicationPlanKey)
    }

    private fun traceBuffer(choice: RadarMapLayer, message: String) {
        if (BuildConfig.DEBUG) Log.d("RainRadarLayers", "satellite layer=${choice.label} $message")
    }

    private fun resolvePending(
        style: Style,
        choice: RadarMapLayer,
        state: SatelliteChoiceBuffer,
        pending: SatelliteBufferSlot,
    ) {
        if (currentStyle !== style || state.pending !== pending) return
        state.pendingPromotionJob?.cancel()
        state.pendingPromotionJob = null
        val action = SatelliteProgressiveLoadPolicy.whenReady(
            state.active?.metadata, pending.metadata, state.desired, state.isPlaying,
        )
        when (action) {
            SatellitePendingAction.PROMOTE -> {
                val incoming = style.getLayer(pending.layerId)
                if (incoming == null) {
                    state.pending = null
                    remove(style, pending)
                    onLayerError(choice, "${choice.label} layer unavailable · refresh", state.active != null)
                    return
                }
                incoming.setProperties(PropertyFactory.rasterOpacity(
                    SatelliteLayerRenderPolicy.opacity(choice),
                ))
                val outgoing = state.active
                state.retiring = outgoing
                state.active = pending
                state.revealedGeneration = pending.generation
                state.revealedAtRenderSequence = renderSequence
                scheduleRevealConfirmation(style, choice, state, pending)
            }
            SatellitePendingAction.DISCARD -> remove(style, pending)
        }
        state.pending = null
    }

    private fun schedulePendingPromotion(
        style: Style,
        choice: RadarMapLayer,
        state: SatelliteChoiceBuffer,
        pending: SatelliteBufferSlot,
    ) {
        state.pendingPromotionJob?.cancel()
        state.pendingPromotionJob = scope.launch {
            delay(SatelliteRenderConfirmationPolicy.fallbackMillis)
            if (currentStyle !== style || state.pending !== pending || state.retiring != null) {
                return@launch
            }
            Log.d(
                "RainRadarLayers",
                "satellite layer=${choice.label} phase=promote confirmation=bounded generation=${pending.generation}",
            )
            state.pendingPromotionJob = null
            resolvePending(style, choice, state, pending)
            startLatestIfNeeded(style, choice, state)
        }
    }

    /**
     * ImageSource/property changes normally produce the next fully-rendered callback. An idle
     * MapLibre renderer can omit that callback even though the revealed layer is visibly composed;
     * use a bounded, generation-checked confirmation so preparation cannot wait forever.
     */
    private fun scheduleRevealConfirmation(
        style: Style,
        choice: RadarMapLayer,
        state: SatelliteChoiceBuffer,
        revealed: SatelliteBufferSlot,
    ) {
        state.revealConfirmationJob?.cancel()
        state.revealConfirmationJob = scope.launch {
            delay(SatelliteRenderConfirmationPolicy.fallbackMillis)
            if (currentStyle !== style || !SatelliteRenderConfirmationPolicy.matches(
                    state.revealedGeneration,
                    state.active?.generation,
                    revealed.planKey,
                    state.active?.planKey,
                )
            ) return@launch
            state.retiring?.let { remove(style, it) }
            state.retiring = null
            state.revealedGeneration = null
            state.revealedAtRenderSequence = null
            state.revealConfirmationJob = null
            Log.d(
                "RainRadarLayers",
                "satellite layer=${choice.label} phase=ready confirmation=bounded generation=${revealed.generation}",
            )
            acknowledgeFrame(choice, state, revealed.planKey)
            startLatestIfNeeded(style, choice, state)
        }
    }

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
        state.pendingPromotionJob?.cancel()
        state.pendingPromotionJob = null
        state.revealConfirmationJob?.cancel()
        state.revealConfirmationJob = null
        state.decodeJob = null
        state.decoding = null
        state.decodingGeneration = null
        state.active = null
        state.pending = null
        state.retiring = null
        state.revealedGeneration = null
        state.revealedAtRenderSequence = null
        state.desired = null
        state.cacheContext = null
        state.assets = emptyMap()
        state.planKey = null
        state.publicationPlanKey = null
        state.acknowledgedPlanKey = null
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
    private var renderToken: WindGridRenderToken? = null
    private var arrowScale = 1f
    private var locations: List<LatLng> = emptyList()
    private var renderEligible = false
    private var observationCallback: (WindGridRenderToken, Int) -> Unit = { _, _ -> }
    private var deliveredObservation: Pair<WindGridRenderToken, Int>? = null
    private var pendingObservation: Pair<WindGridRenderToken, Int>? = null
    init { isClickable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    fun bind(ready: MapLibreMap) {
        map = ready
        resetConfirmation()
    }
    fun setObservationCallback(callback: (WindGridRenderToken, Int) -> Unit) {
        observationCallback = callback
    }
    fun update(value: WindGrid?, token: WindGridRenderToken?, scale: Float) {
        if (grid !== value || renderToken != token || arrowScale != scale) {
        grid = value
        renderToken = token
        arrowScale = scale
        arrow.strokeWidth = 3.5f * scale
        shadow.strokeWidth = 7.5f * scale
        locations = value?.renderCoordinates()?.map { LatLng(it.first, it.second) }.orEmpty()
        deliveredObservation = null
        pendingObservation = null
        invalidate()
    } }
    fun setRenderEligible(eligible: Boolean) {
        if (renderEligible == eligible) return
        renderEligible = eligible
        Log.d("RainRadarWind", "renderer eligible=$eligible")
        resetConfirmation()
    }
    fun resetConfirmation() {
        deliveredObservation = null
        pendingObservation = null
        invalidate()
    }
    fun cameraMoved() = invalidate()
    override fun onDraw(canvas: Canvas) {
        val ready = map
        val points = grid?.points
        val token = renderToken
        if (!renderEligible || ready == null || points == null || token == null ||
            width <= 0 || height <= 0) {
            token?.let { publishObservation(it, 0) }
            return
        }
        var drawnArrowCount = 0
        for ((index, sample) in points.withIndex()) {
            val speed = sample.windSpeedKmh?.takeIf { it.isFinite() } ?: continue
            val from = sample.windFromDegrees?.takeIf { it.isFinite() } ?: continue
            if (speed < 0.0) continue
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
            drawnArrowCount++
        }
        publishObservation(token, drawnArrowCount)
    }
    override fun onDetachedFromWindow() {
        renderToken?.let { publishObservation(it, 0) }
        map = null
        renderEligible = false
        super.onDetachedFromWindow()
    }
    private fun publishObservation(token: WindGridRenderToken, count: Int) {
        val observation = token to count
        if (deliveredObservation == observation || pendingObservation == observation) return
        pendingObservation = observation
        post {
            if (pendingObservation != observation) return@post
            pendingObservation = null
            if (renderToken != token) return@post
            if (deliveredObservation == observation) return@post
            deliveredObservation = observation
            Log.d(
                "RainRadarWind",
                "draw observed generation=${token.generation} viewportKey=${token.viewportKey} " +
                    "arrows=$count",
            )
            observationCallback(token, count)
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
    private val markerOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0D0D0D.toInt() }
    private val decodeThread = HandlerThread("rain-radar-static-fallback").apply { start() }
    private val decodeHandler = Handler(decodeThread.looper)
    private var bitmap: android.graphics.Bitmap? = null
    private var map: MapLibreMap? = null
    private var marker = session.place
    private var markerScale = 1f
    private var markerVisible = true
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

    fun setMarkerScale(scale: Float) {
        markerScale = scale.takeIf(Float::isFinite)?.coerceIn(1f, 1.6f) ?: 1f
        invalidate()
    }

    fun setMarkerVisible(visible: Boolean) {
        if (markerVisible == visible) return
        markerVisible = visible
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
        if (markerVisible) {
            val point = ready.projection.toScreenLocation(LatLng(marker.latitude, marker.longitude))
            canvas.drawCircle(point.x, point.y, RadarMarkerGeometry.outerRadius(markerScale), markerOutlinePaint)
            canvas.drawCircle(point.x, point.y, RadarMarkerGeometry.innerRadius(markerScale), markerPaint)
        }
    }
}

internal interface RadarOverlayController {
    val overlayView: View
    fun bindSession(ownedSession: RadarSession)
    fun attachMap(ready: MapLibreMap)
    fun setState(next: RadarTimelineBracket, playing: Boolean, mapPlace: SavedPlace)
    fun setMarker(mapPlace: SavedPlace)
    fun setMarkerScale(scale: Float) = Unit
    fun setMarkerVisible(visible: Boolean) = Unit
    fun onCameraMoved()
    fun dispose()
}

/** A radar session owns only its overlay views; the MapLibre host outlives session swaps. */
private class RadarSessionOverlaySlot(
    context: android.content.Context,
    val session: RadarSession,
    private val reportStatus: (RadarSessionOverlaySlot, RadarRendererStatus) -> Unit,
) {
    init {
        session.retain("overlay-slot")
        Log.i(
            "RainRadarRender",
            "event=slot_create session=${System.identityHashCode(session.resourceLease)}",
        )
    }

    private val staticFallback = session.legacyArchive?.let {
        LegacyStaticFallbackOverlayView(context, session)
    }
    private val overlay: RadarOverlayController = RadarGlOverlayView(context, ::onStatus).apply {
        bindSession(session)
    }
    private var disposed = false

    val hasStaticFallback: Boolean get() = staticFallback != null

    private fun onStatus(status: RadarRendererStatus) {
        if (disposed) return
        if (RadarStaticFallbackPolicy.shouldShow(session.legacyArchive != null, status)) {
            staticFallback?.show()
        } else {
            staticFallback?.hide()
        }
        reportStatus(this, status)
    }

    fun addTo(container: FrameLayout) {
        if (disposed) return
        staticFallback?.let { fallback ->
            if (fallback.parent == null) container.addView(
                fallback,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        if (overlay.overlayView.parent == null) container.addView(
            overlay.overlayView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        overlay.overlayView.bringToFront()
    }

    fun removeFrom(container: FrameLayout?) {
        container?.removeView(overlay.overlayView)
        staticFallback?.let { container?.removeView(it) }
    }

    fun attachMap(map: MapLibreMap) {
        if (disposed) return
        overlay.attachMap(map)
        staticFallback?.attachMap(map)
    }

    fun setState(bracket: RadarTimelineBracket, playing: Boolean, marker: SavedPlace) {
        if (!disposed) overlay.setState(bracket, playing, marker)
    }

    fun setMarker(marker: SavedPlace) {
        if (disposed) return
        overlay.setMarker(marker)
        staticFallback?.setPlace(marker)
    }

    fun setMarkerScale(scale: Float) {
        if (disposed) return
        overlay.setMarkerScale(scale)
        staticFallback?.setMarkerScale(scale)
    }

    fun setMarkerVisible(visible: Boolean) {
        if (disposed) return
        overlay.setMarkerVisible(visible)
        staticFallback?.setMarkerVisible(visible)
    }

    fun setVisible(visible: Boolean) {
        if (disposed) return
        val visibility = if (visible) View.VISIBLE else View.INVISIBLE
        overlay.overlayView.visibility = visibility
        staticFallback?.visibility = visibility
    }

    fun onCameraMoved() {
        if (disposed) return
        overlay.onCameraMoved()
        staticFallback?.onCameraMoved()
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        overlay.dispose()
        staticFallback?.dispose()
        Log.i(
            "RainRadarRender",
            "event=slot_dispose session=${System.identityHashCode(session.resourceLease)}",
        )
        session.release("overlay-slot")
    }
}

/** Keeps the selected/live marker available while the radar raster is still being acquired. */
private class RadarBaseMarkerView(context: android.content.Context) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4FC3F7.toInt() }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF0D0D0D.toInt()
    }
    private var map: MapLibreMap? = null
    private var marker: SavedPlace? = null
    private var markerScale = 1f
    private var fixedAtCenter = false

    init {
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        // The Travel marker is screen-fixed above the radar TextureView; the opaque loading cover
        // retains a still higher Z so an unfinished native frame is never exposed.
        translationZ = resources.displayMetrics.density * 1.5f
    }

    fun bind(ready: MapLibreMap) {
        map = ready
        invalidate()
    }

    fun update(place: SavedPlace) {
        if (marker?.latitude == place.latitude && marker?.longitude == place.longitude) return
        val needsFirstDraw = marker == null
        marker = place
        if (!fixedAtCenter || needsFirstDraw) invalidate()
    }

    fun onCameraMoved() {
        // The Travel marker is screen-fixed: the map can move at display cadence beneath it
        // without scheduling a redundant marker redraw on every native camera frame.
        if (!fixedAtCenter) invalidate()
    }

    fun setMarkerScale(scale: Float) {
        markerScale = scale.takeIf(Float::isFinite)?.coerceIn(1f, 1.6f) ?: 1f
        invalidate()
    }

    fun setFixedAtCenter(fixed: Boolean) {
        if (fixedAtCenter == fixed) return
        fixedAtCenter = fixed
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val ready = map ?: return
        val place = marker ?: return
        val point = if (fixedAtCenter) null
            else ready.projection.toScreenLocation(LatLng(place.latitude, place.longitude))
        val x = point?.x ?: width / 2f
        val y = point?.y ?: height / 2f
        canvas.drawCircle(x, y, RadarMarkerGeometry.outerRadius(markerScale), outline)
        canvas.drawCircle(x, y, RadarMarkerGeometry.innerRadius(markerScale), fill)
    }
}

private class CanvasRadarOverlayView(context: android.content.Context) : View(context), RadarOverlayController {
    override val overlayView: View get() = this
    private val firstPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val secondPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4FC3F7.toInt() }
    private val markerOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF0D0D0D.toInt()
    }
    private val firstDestination = RectF()
    private val secondDestination = RectF()
    private lateinit var session: RadarSession
    private var frameTimes = emptyList<Long>()
    private var overlayBounds = emptyMap<RadarResolutionTier, OverlayBounds?>()
    private var map: MapLibreMap? = null
    private lateinit var marker: SavedPlace
    private lateinit var markerLatLng: LatLng
    private lateinit var bracket: RadarTimelineBracket
    private var plan: RadarOverlayFramePlan? = null
    private var activeTier: RadarResolutionTier? = null
    private var animating = false
    private var frameScheduled = false
    private var disposed = false
    private var markerScale = 1f
    private var markerVisible = true
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

    override fun setMarkerScale(scale: Float) {
        markerScale = scale.takeIf(Float::isFinite)?.coerceIn(1f, 1.6f) ?: 1f
        postInvalidateOnAnimation()
    }

    override fun setMarkerVisible(visible: Boolean) {
        if (markerVisible == visible) return
        markerVisible = visible
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
        if (markerVisible) {
            val markerPoint = projection.toScreenLocation(markerLatLng)
            canvas.drawCircle(
                markerPoint.x, markerPoint.y, RadarMarkerGeometry.outerRadius(markerScale), markerOutlinePaint,
            )
            canvas.drawCircle(
                markerPoint.x, markerPoint.y, RadarMarkerGeometry.innerRadius(markerScale), markerPaint,
            )
        }
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

private data class RadarNativeEntryPreparation(
    val generation: Int,
    val intentToken: Long,
    val place: SavedPlace,
    val target: RadarCameraTarget,
    val start: RadarCameraTarget,
    val retainedStyleWasCoherent: Boolean,
)

@Composable
internal fun RadarImageMap(
    session: RadarSession?,
    bracket: RadarTimelineBracket?,
    mapPlace: SavedPlace,
    onLongPress: (GeoPoint) -> Unit,
    modifier: Modifier = Modifier,
    markerPlace: SavedPlace = mapPlace,
    followLive: Boolean = false,
    liveFixElapsedRealtimeNanos: Long = 0L,
    liveTargetProjected: Boolean = false,
    onManualCameraGesture: () -> Unit = {},
    recenterSignal: Int = 0,
    isPlaying: Boolean = false,
    radarPresentationVisible: Boolean = true,
    onRendererStatus: (RadarRendererStatus) -> Unit = {},
    mapStyle: RadarMapStyle = RadarMapStyle.DARK,
    coverageMaskDarkness: Float = CoverageMaskDarknessPreference.DEFAULT,
    cameraMemory: RadarCameraMemory,
    windGrid: WindGrid? = null,
    windArrowScale: Float = 1f,
    windRenderToken: WindGridRenderToken? = null,
    onWindRenderObservation: (WindGridRenderToken, Int) -> Unit = { _, _ -> },
    onWindRendererReset: () -> Unit = {},
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
    onRadarTierChanged: (RadarResolutionTier) -> Unit = {},
    entryTransition: EntryTransitionState = EntryTransitionState(0, EntryTransitionPhase.SETTLED),
    entryFocusEnabled: Boolean = true,
    entryFocusDurationMillis: Int = RadarEntryFocusPolicy.DURATION_MILLIS,
    onEntryAnimationStart: (Int, Boolean) -> Unit = { _, _ -> },
    markerScale: Float = 1f,
    rendererRecoveryGeneration: Int = 0,
    onMapPreparing: (Boolean) -> Unit = {},
) {
    val compositionIdentity = remember { Any() }
    DisposableEffect(compositionIdentity) {
        Log.i(
            "RainRadarRender",
            "event=radar_map_compose identity=${System.identityHashCode(compositionIdentity)}",
        )
        onDispose {
            Log.i(
                "RainRadarRender",
                "event=radar_map_dispose identity=${System.identityHashCode(compositionIdentity)}",
            )
        }
    }
    var automaticRendererRecreations by remember(
        rendererRecoveryGeneration,
        mapStyle,
    ) { mutableIntStateOf(0) }
    RadarImageMapInstance(
        session,
        bracket,
        mapPlace,
        onLongPress,
        modifier,
        markerPlace,
        followLive,
        liveFixElapsedRealtimeNanos,
        liveTargetProjected,
        onManualCameraGesture,
        recenterSignal,
        isPlaying,
        radarPresentationVisible,
        onRendererStatus,
        mapStyle,
        coverageMaskDarkness,
        cameraMemory,
        windGrid,
        windArrowScale,
        windRenderToken,
        onWindRenderObservation,
        onWindRendererReset,
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
        onRadarTierChanged,
        entryTransition,
        entryFocusEnabled,
        entryFocusDurationMillis,
        onEntryAnimationStart,
        markerScale,
        rendererRecoveryGeneration,
        automaticRendererRecreations,
        {
            automaticRendererRecreations++
        },
        onMapPreparing,
    )
}

@Composable
private fun RadarImageMapInstance(
    session: RadarSession?,
    bracket: RadarTimelineBracket?,
    mapPlace: SavedPlace,
    onLongPress: (GeoPoint) -> Unit,
    modifier: Modifier,
    markerPlace: SavedPlace,
    followLive: Boolean,
    liveFixElapsedRealtimeNanos: Long,
    liveTargetProjected: Boolean,
    onManualCameraGesture: () -> Unit,
    recenterSignal: Int,
    isPlaying: Boolean,
    radarPresentationVisible: Boolean,
    onRendererStatus: (RadarRendererStatus) -> Unit,
    mapStyle: RadarMapStyle,
    coverageMaskDarkness: Float,
    cameraMemory: RadarCameraMemory,
    windGrid: WindGrid?,
    windArrowScale: Float,
    windRenderToken: WindGridRenderToken?,
    onWindRenderObservation: (WindGridRenderToken, Int) -> Unit,
    onWindRendererReset: () -> Unit,
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
    onRadarTierChanged: (RadarResolutionTier) -> Unit,
    entryTransition: EntryTransitionState,
    entryFocusEnabled: Boolean,
    entryFocusDurationMillis: Int,
    onEntryAnimationStart: (Int, Boolean) -> Unit,
    markerScale: Float,
    rendererRecoveryGeneration: Int,
    automaticRendererRecreations: Int,
    onAutomaticRendererRecreation: () -> Unit,
    onMapPreparing: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val applicationContext = context.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val satelliteFrameStore = remember(applicationContext) {
        SatelliteFrameStoreProvider.get(File(applicationContext.cacheDir, "satellite-frames"))
    }
    val mapView = remember(
        density,
        rendererRecoveryGeneration,
        automaticRendererRecreations,
    ) {
        SatelliteAmbientCache.configure(context)
        // Texture mode keeps MapLibre in the normal View hierarchy so our transparent GLES
        // TextureView can reliably composite above it on every Android surface compositor.
        val options = MapLibreMapOptions.createFromAttributes(context, null)
            .textureMode(true)
            // Do not bake the initial (often default-dark) preference into MapLibre's immutable
            // renderer clear colour. During a remote place focus, unloaded tiles reveal the
            // live style-aware backgrounds below this transparent TextureView.
            .foregroundLoadColor(RadarMapAppearance.rendererForegroundArgb())
            // The style's attribution remains available through MapLibre's compact info
            // control. The optional MapLibre logo and the old duplicate Compose credit are
            // intentionally omitted.
            .logoEnabled(false)
            .attributionEnabled(true)
            .attributionGravity(Gravity.BOTTOM or Gravity.START)
            .attributionMargins(intArrayOf(4, 4, 4, 4).map {
                (it * density).roundToInt()
            }.toIntArray())
        MapView(context, options).apply {
            setBackgroundColor(RadarMapAppearance.loadingBackgroundArgb(mapStyle))
            onCreate(Bundle())
            Log.i(
                "RainRadarRender",
                "event=mapview_create view=${System.identityHashCode(this)} " +
                    "rendererGeneration=$rendererRecoveryGeneration.$automaticRendererRecreations",
            )
        }
    }
    val mapGestureOwnership = remember(mapView) { RadarMapGestureOwnership() }
    val currentStatusCallback by rememberUpdatedState(onRendererStatus)
    val currentLayerError by rememberUpdatedState(onLayerError)
    val currentSatellitePreparation by rememberUpdatedState(onSatellitePreparation)
    val currentMapStyleError by rememberUpdatedState(onMapStyleError)
    val currentMapPreparing by rememberUpdatedState(onMapPreparing)
    val currentAutomaticRendererRecreation by rememberUpdatedState(onAutomaticRendererRecreation)
    val currentWindViewportCallback by rememberUpdatedState(onWindViewportChanged)
    val currentRadarTierCallback by rememberUpdatedState(onRadarTierChanged)
    val latestRadarSession by rememberUpdatedState(session)
    val currentWindRenderObservation by rememberUpdatedState(onWindRenderObservation)
    val currentWindRendererReset by rememberUpdatedState(onWindRendererReset)
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
            val preparationGate = remember { OverlayRequestGeneration() }
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
                val generation = preparationGate.begin()
                val startedAt = OverlayAcquisitionDiagnostics.nowMillis()
                val targetHash = OverlayAcquisitionDiagnostics.targetHash(
                    choice, satelliteCacheContext.identity, metadataPlanKey,
                )
                val trigger = if (recoveryGeneration > 0) "recovery" else "catalog"
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
                    if (preparationGate.accepts(generation)) {
                        val hasFallback = preparedSatellitePlans[choice]
                            ?.takeIf { it.cacheContext == satelliteCacheContext }
                            ?.frames?.isNotEmpty() == true
                        currentSatellitePreparation(choice, SatellitePreparationStatus.Failed(
                            "${choice.label} preparation failed · refresh", hasFallback,
                        ))
                    }
                    OverlayAcquisitionDiagnostics.trace(
                        choice.label.lowercase(), "EUMETSAT", targetHash, generation,
                        trigger, OverlayAcquisitionPhase.FAILED, startedAt,
                        terminalResult = failure.javaClass.simpleName,
                        cacheFallback = preparedSatellitePlans[choice]
                            ?.cacheContext == satelliteCacheContext,
                        accepted = preparationGate.accepts(generation),
                    )
                    return@LaunchedEffect
                }
                OverlayAcquisitionDiagnostics.trace(
                    choice.label.lowercase(), "EUMETSAT", targetHash, generation,
                    trigger, OverlayAcquisitionPhase.TRANSFER, startedAt,
                    completed = 0, total = requests.size,
                    cacheFallback = preparedSatellitePlans[choice]
                        ?.cacheContext == satelliteCacheContext,
                )
                currentSatellitePreparation(
                    choice, SatellitePreparationStatus.Preparing(0, requests.size),
                )
                try {
                    val frames = satelliteFrameStore.prepare(requests) { ready, total ->
                        withContext(Dispatchers.Main.immediate) {
                            if (preparationGate.accepts(generation)) {
                                currentSatellitePreparation(
                                    choice,
                                    if (total > 0 && ready >= total) {
                                        SatellitePreparationStatus.Rendering
                                    } else SatellitePreparationStatus.Preparing(ready, total),
                                )
                            }
                            OverlayAcquisitionDiagnostics.trace(
                                choice.label.lowercase(), "EUMETSAT", targetHash,
                                generation, trigger,
                                if (total > 0 && ready >= total) {
                                    OverlayAcquisitionPhase.PREPARING
                                } else OverlayAcquisitionPhase.TRANSFER,
                                startedAt, ready, total,
                                cacheFallback = preparedSatellitePlans[choice]
                                    ?.cacheContext == satelliteCacheContext,
                                accepted = preparationGate.accepts(generation),
                            )
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    if (!preparationGate.accepts(generation)) {
                        OverlayAcquisitionDiagnostics.trace(
                            choice.label.lowercase(), "EUMETSAT", targetHash,
                            generation, trigger, OverlayAcquisitionPhase.READY,
                            startedAt, terminalResult = "stale_success",
                            cacheFallback = preparedSatellitePlans[choice]
                                ?.cacheContext == satelliteCacheContext,
                            accepted = false,
                        )
                        return@LaunchedEffect
                    }
                    preparedSatellitePlans = preparedSatellitePlans + (
                        choice to SatellitePreparedPlan(
                            metadataPlanKey, satelliteCacheContext, frames, recoveryGeneration,
                            generation, startedAt, targetHash, trigger,
                        )
                    )
                    currentSatellitePreparation(choice, SatellitePreparationStatus.Rendering)
                    OverlayAcquisitionDiagnostics.trace(
                        choice.label.lowercase(), "EUMETSAT", targetHash, generation,
                        trigger, OverlayAcquisitionPhase.RENDERING, startedAt,
                        terminalResult = "prepared", cacheFallback = false,
                    )
                } catch (cancelled: CancellationException) {
                    OverlayAcquisitionDiagnostics.trace(
                        choice.label.lowercase(), "EUMETSAT", targetHash, generation,
                        trigger, OverlayAcquisitionPhase.CANCELLED, startedAt,
                        terminalResult = "replaced",
                        cacheFallback = preparedSatellitePlans[choice]
                            ?.cacheContext == satelliteCacheContext,
                        accepted = preparationGate.accepts(generation),
                    )
                    throw cancelled
                } catch (failure: Throwable) {
                    Log.e("RainRadarLayers", "${choice.label} frame preparation failed", failure)
                    val accepted = preparationGate.accepts(generation)
                    val hasFallback = preparedSatellitePlans[choice]
                        ?.takeIf { it.cacheContext == satelliteCacheContext }
                        ?.frames?.isNotEmpty() == true
                    if (accepted) {
                        currentSatellitePreparation(choice, SatellitePreparationStatus.Failed(
                            "${choice.label} preparation failed · refresh", hasFallback,
                        ))
                        currentLayerError(choice, "${choice.label} imagery could not load · refresh")
                    }
                    OverlayAcquisitionDiagnostics.trace(
                        choice.label.lowercase(), "EUMETSAT", targetHash, generation,
                        trigger, OverlayAcquisitionPhase.FAILED, startedAt,
                        terminalResult = failure.javaClass.simpleName,
                        cacheFallback = hasFallback, accepted = accepted,
                    )
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
                        plan.planKey,
                    ))
                }
            preparedSatellitePlans[RadarMapLayer.LIGHTNING]
                ?.takeIf { it.cacheContext == satelliteCacheContext }?.let { plan ->
                    put(RadarMapLayer.LIGHTNING, SatelliteOverlayRequest(
                        SatellitePreparedFramePolicy.desired(plan.frames, desiredLightningFrame),
                        plan.frames,
                        plan.generation,
                        plan.planKey,
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
    val windView = remember(mapView) { WindFieldView(context) }
    val baseMarkerView = remember(mapView) { RadarBaseMarkerView(context) }
    val coverageMask = remember(mapView) { RadarCoverageMaskController() }
    val mapLifecycle = remember(mapView) { MapViewLifecycle(mapView) }
    val mapRevealGate = remember(mapView) { RadarMapRevealGate() }
    val mapCover = remember(mapView) {
        View(context).apply {
            setBackgroundColor(RadarMapAppearance.loadingBackgroundArgb(mapStyle))
            // Radar uses its own TextureView with positive Z. Child order alone cannot reliably
            // conceal that surface on every compositor, so the native cover must sit above it.
            translationZ = density * 2f
        }
    }
    // Every mutable native handle belongs to exactly one MapView generation. Keeping these state
    // holders across a recovery lets the outgoing teardown see (and dispose) the replacement
    // slot/map, which is indistinguishable from a blank renderer to the parent data state.
    var map by remember(mapView) { mutableStateOf<MapLibreMap?>(null) }
    var mapContainer by remember(mapView) { mutableStateOf<FrameLayout?>(null) }
    var activeRadarSlot by remember(mapView) { mutableStateOf<RadarSessionOverlaySlot?>(null) }
    var pendingRadarSlot by remember(mapView) { mutableStateOf<RadarSessionOverlaySlot?>(null) }
    var cameraListener by remember(mapView) { mutableStateOf<MapLibreMap.OnCameraMoveListener?>(null) }
    var cameraStartListener by remember(mapView) {
        mutableStateOf<MapLibreMap.OnCameraMoveStartedListener?>(null)
    }
    var cameraIdleListener by remember(mapView) {
        mutableStateOf<MapLibreMap.OnCameraIdleListener?>(null)
    }
    var appliedPlace by remember(mapView) { mutableStateOf<SavedPlace?>(null) }
    var lastBracket by remember(mapView) { mutableStateOf<RadarTimelineBracket?>(null) }
    var lastPlaying by remember(mapView) { mutableStateOf<Boolean?>(null) }
    var lastMarker by remember(mapView) { mutableStateOf<SavedPlace?>(null) }
    var lastWindViewportPublishElapsedNanos by remember(mapView) { mutableLongStateOf(0L) }
    val cameraIntentOwner = remember(mapView) { RadarCameraIntentOwner() }
    var activeEntryFocusToken by remember(mapView) { mutableStateOf<Long?>(null) }
    val entryFrameHandshake = remember(mapView) { RadarEntryFrameHandshake() }
    val nativePresentationGate = remember(mapView) { RadarNativePresentationGate() }
    var nativeEntryPreparation by remember(mapView) {
        mutableStateOf<RadarNativeEntryPreparation?>(null)
    }
    var readyEntryGeneration by remember(mapView) { mutableStateOf<Int?>(null) }
    var failedEntryGeneration by remember(mapView) { mutableStateOf<Int?>(null) }
    var cancelledEntryGeneration by remember(mapView) { mutableStateOf<Int?>(null) }
    var finishedEntryGeneration by remember(mapView) { mutableStateOf<Int?>(null) }
    val latestFollowLive by rememberUpdatedState(followLive)
    val latestEntryTransition by rememberUpdatedState(entryTransition)
    val latestEntryFocusEnabled by rememberUpdatedState(entryFocusEnabled)
    val latestEntryFocusDurationMillis by rememberUpdatedState(entryFocusDurationMillis)
    val latestRadarPresentationVisible by rememberUpdatedState(radarPresentationVisible)
    val latestLiveTargetProjected by rememberUpdatedState(liveTargetProjected)
    val currentEntryAnimationStart by rememberUpdatedState(onEntryAnimationStart)
    val travelFrameDiagnostics = remember(mapView) { RadarTravelFrameDiagnosticSampler() }

    val travelCameraFollower = remember(mapView) {
        RadarTravelCameraFollower(Choreographer.getInstance()) { latitude, longitude, frameTimeNanos ->
            val ready = map ?: return@RadarTravelCameraFollower
            ready.cameraPosition = northUpCamera(
                RadarCameraTarget(latitude, longitude, ready.cameraPosition.zoom),
            )
            travelFrameDiagnostics.record(frameTimeNanos)?.let { appliedFrames ->
                LocationCadenceDiagnostics.record(
                    "map-frame",
                    null,
                    frameTimeNanos,
                    (if (latestLiveTargetProjected) "projected-vsync" else "measured-vsync") +
                        " applied=$appliedFrames",
                )
            }
        }
    }
    DisposableEffect(mapView, mapGestureOwnership, travelCameraFollower) {
        mapView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val ownedByMap = RadarMapTouchPolicy.pointerOwnedByMap(event.y, density)
                    if (mapGestureOwnership.pointerStarted(ownedByMap)) {
                        // Claim automated camera ownership away from Travel before MapLibre's
                        // recognisers see movement. Location targets keep flowing into the paused
                        // trajectory, but no vsync camera write can contend with this pointer.
                        travelCameraFollower.pauseForGesture()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (mapGestureOwnership.pointerFinished() && latestFollowLive) {
                        travelCameraFollower.resumeAfterUnclaimedGesture(
                            android.os.SystemClock.elapsedRealtimeNanos(),
                        )
                    }
                }
            }
            false
        }
        onDispose {
            mapGestureOwnership.pointerFinished()
            mapView.setOnTouchListener(null)
        }
    }

    fun reconcileMarkerPresentation() {
        val fixedTravelMarker = latestFollowLive
        activeRadarSlot?.setMarkerVisible(!fixedTravelMarker)
        pendingRadarSlot?.setMarkerVisible(!fixedTravelMarker)
        baseMarkerView.setFixedAtCenter(fixedTravelMarker)
        baseMarkerView.visibility = when {
            nativePresentationGate.isHidden -> View.INVISIBLE
            fixedTravelMarker -> View.VISIBLE
            activeRadarSlot == null || !latestRadarPresentationVisible -> View.VISIBLE
            else -> View.GONE
        }
        windView.bringToFront()
        if (fixedTravelMarker && baseMarkerView.isVisible) {
            baseMarkerView.bringToFront()
        }
        if (mapCover.isVisible) mapCover.bringToFront()
    }

    fun hideNativePresentation(generation: Int) {
        if (!nativePresentationGate.prepare(generation) &&
            !nativePresentationGate.isHiddenFor(generation)) return
        mapCover.visibility = View.VISIBLE
        mapCover.bringToFront()
        activeRadarSlot?.setVisible(false)
        pendingRadarSlot?.setVisible(false)
        baseMarkerView.visibility = View.INVISIBLE
        windView.visibility = View.INVISIBLE
        windView.setRenderEligible(false)
        Log.i(
            "RainRadarRender",
            "event=native_hide entryGeneration=$generation rendererGeneration=" +
                "$rendererRecoveryGeneration.$automaticRendererRecreations",
        )
    }

    fun revealNativePresentation(generation: Int) {
        if (!nativePresentationGate.reveal(generation)) return
        mapCover.visibility = View.GONE
        activeRadarSlot?.setVisible(latestRadarPresentationVisible)
        pendingRadarSlot?.setVisible(latestRadarPresentationVisible)
        windView.visibility = View.VISIBLE
        windView.setRenderEligible(true)
        reconcileMarkerPresentation()
        currentMapStyleError(null)
        Log.i(
            "RainRadarRender",
            "event=native_reveal entryGeneration=$generation rendererGeneration=" +
                "$rendererRecoveryGeneration.$automaticRendererRecreations",
        )
    }
    val satelliteBuffers = remember(mapView) {
        SatelliteLayerBuffers(
            onLayerError = { layer, message, hasFallback ->
                currentSatellitePreparation(
                    layer,
                    SatellitePreparationStatus.Failed(message, hasFallback),
                )
                currentLayerError(layer, message)
            },
            onFrameInvalidated = { layer, frame ->
                satelliteFrameStore.invalidate(frame.request)
                satelliteRecoveryGeneration = satelliteRecoveryGeneration + (
                    layer to ((satelliteRecoveryGeneration[layer] ?: 0) + 1)
                )
            },
            onFrameRendered = { layer, renderedPlanKey ->
                preparedSatellitePlans[layer]?.takeIf {
                    it.planKey == renderedPlanKey
                }?.let { plan ->
                    currentSatellitePreparation(layer, SatellitePreparationStatus.Ready)
                    OverlayAcquisitionDiagnostics.trace(
                        layer.label.lowercase(), "EUMETSAT", plan.targetHash,
                        plan.requestGeneration, plan.trigger, OverlayAcquisitionPhase.READY,
                        plan.startedAtMillis, terminalResult = "renderable",
                    )
                }
            },
        )
    }
    fun saveCamera(ready: MapLibreMap, place: SavedPlace) {
        val position = ready.cameraPosition
        val center = position.target ?: return
        if (activeEntryFocusToken == null) {
            cameraMemory.capture(place, center.latitude, center.longitude, position.zoom)
        }
    }
    val latestBracket by rememberUpdatedState(bracket)
    val latestPlaying by rememberUpdatedState(isPlaying)
    val slotStatusHandler by rememberUpdatedState<(RadarSessionOverlaySlot, RadarRendererStatus) -> Unit> {
            slot, status ->
        val isRelevant = slot === pendingRadarSlot ||
            (pendingRadarSlot == null && slot === activeRadarSlot)
        if (isRelevant) currentStatusCallback(status)
        val promote = slot === pendingRadarSlot && when (status) {
            RadarRendererStatus.Ready, is RadarRendererStatus.Compatibility -> true
            is RadarRendererStatus.Error -> activeRadarSlot == null || slot.hasStaticFallback
            RadarRendererStatus.Loading -> false
        }
        if (promote) mapContainer?.post {
            if (slot !== pendingRadarSlot) return@post
            val outgoing = activeRadarSlot
            activeRadarSlot = slot
            pendingRadarSlot = null
            slot.setVisible(latestRadarPresentationVisible && !nativePresentationGate.isHidden)
            outgoing?.removeFrom(mapContainer)
            outgoing?.dispose()
            map?.style?.let {
                coverageMask.reconcile(it, slot.session, mapStyle, coverageMaskDarkness)
            }
            windView.visibility = if (nativePresentationGate.isHidden) View.INVISIBLE else View.VISIBLE
            reconcileMarkerPresentation()
        }
    }
    // A renderer/style recovery replaces MapView and its teardown disposes the native slot.
    // Re-key the slot with that MapView even when the parent still owns the same data session;
    // otherwise manual refresh reuses a disposed slot and leaves a silent slate map.
    val desiredRadarSlot = remember(mapView, session) {
        session?.let { selected ->
            RadarSessionOverlaySlot(context, selected) { slot, status ->
                slotStatusHandler(slot, status)
            }
        }
    }
    DisposableEffect(desiredRadarSlot) {
        onDispose {
            desiredRadarSlot?.takeUnless {
                it === activeRadarSlot || it === pendingRadarSlot
            }?.dispose()
        }
    }
    val teardown = remember(mapView) {
        RadarResourceTeardown(
            stopOverlay = {
                travelCameraFollower.stop()
                satelliteBuffers.clear()
                coverageMask.clear()
                windView.setRenderEligible(false)
                currentWindRendererReset()
                pendingRadarSlot?.removeFrom(mapContainer)
                activeRadarSlot?.removeFrom(mapContainer)
                pendingRadarSlot?.dispose()
                activeRadarSlot?.takeUnless { it === pendingRadarSlot }?.dispose()
                pendingRadarSlot = null
                activeRadarSlot = null
            },
            detachMap = {
                val ready = map
                val listener = cameraListener
                val idleListener = cameraIdleListener
                if (ready != null && !travelCameraFollower.isRunning) {
                    appliedPlace?.let { saveCamera(ready, it) }
                }
                if (ready != null && listener != null) ready.removeOnCameraMoveListener(listener)
                cameraStartListener?.let { if (ready != null) ready.removeOnCameraMoveStartedListener(it) }
                if (ready != null && idleListener != null) ready.removeOnCameraIdleListener(idleListener)
            },
            destroyMap = mapLifecycle::destroy,
            releaseSession = {},
        )
    }

    DisposableEffect(mapView, lifecycle, teardown) {
        val observer = object : DefaultLifecycleObserver by mapLifecycle {
            override fun onDestroy(owner: LifecycleOwner) = teardown.close()
        }
        lifecycle.addObserver(observer)
        onDispose {
            Log.i(
                "RainRadarRender",
                "event=mapview_dispose view=${System.identityHashCode(mapView)} " +
                    "rendererGeneration=$rendererRecoveryGeneration.$automaticRendererRecreations",
            )
            lifecycle.removeObserver(observer)
            teardown.close()
        }
    }

    val startingListener = remember(mapView, teardown) {
        MapView.OnWillStartRenderingFrameListener {
            if (!teardown.isClosed) {
                mapRevealGate.frameStarted()
                entryFrameHandshake.frameStarted()
            }
        }
    }
    val renderedListener = remember(mapView, teardown) {
        MapView.OnDidFinishRenderingFrameListener { fully, _, _ ->
            if (fully && !teardown.isClosed) satelliteBuffers.onFullyRendered()
            val preparation = nativeEntryPreparation
            val readyMap = map
            val cameraMatchesStart = if (preparation != null && readyMap != null) {
                val position = readyMap.cameraPosition
                val center = position.target
                center != null && RadarEntryFramePolicy.cameraMatchesStart(
                    RadarCameraTarget(center.latitude, center.longitude, position.zoom),
                    preparation.start,
                )
            } else false
            val acknowledgedEntry = if (!teardown.isClosed) {
                entryFrameHandshake.frameRendered(
                    fully = fully,
                    cameraMatchesStart = cameraMatchesStart,
                    retainedStyleWasCoherent = preparation?.retainedStyleWasCoherent == true,
                )
            } else null
            if (acknowledgedEntry != null && preparation?.generation == acknowledgedEntry) {
                readyEntryGeneration = acknowledgedEntry
            }
            val revealCurrentStyle = !teardown.isClosed && mapView.width > 0 && mapView.height > 0 &&
                mapRevealGate.frameRendered(fully)
            if (revealCurrentStyle) currentMapPreparing(false)
            if (revealCurrentStyle || acknowledgedEntry != null) mapView.post {
                val transition = latestEntryTransition
                val entryStillWaiting = transition.phase == EntryTransitionPhase.PLAY_REQUESTED &&
                    nativeEntryPreparation?.generation == transition.generation &&
                    readyEntryGeneration != transition.generation
                val failedOpenFrameReady = acknowledgedEntry != null &&
                    finishedEntryGeneration == acknowledgedEntry && activeEntryFocusToken == null
                if (!teardown.isClosed && failedOpenFrameReady) {
                    revealNativePresentation(acknowledgedEntry)
                    nativeEntryPreparation = null
                } else if (!teardown.isClosed && !nativePresentationGate.isHidden &&
                    !mapRevealGate.isCovered && !entryStillWaiting
                ) {
                    mapCover.visibility = View.GONE
                    windView.setRenderEligible(true)
                    reconcileMarkerPresentation()
                    currentMapStyleError(null)
                }
            }
        }
    }
    val renderedMapListener = remember(mapView, teardown) {
        MapView.OnDidFinishRenderingMapListener { fully ->
            val revealCurrentStyle = !teardown.isClosed && mapView.width > 0 && mapView.height > 0 &&
                mapRevealGate.mapRendered(fully)
            if (revealCurrentStyle) {
                currentMapPreparing(false)
                Log.i(
                    "RainRadarRender",
                    "event=map_ready source=full_map rendererGeneration=" +
                        "$rendererRecoveryGeneration.$automaticRendererRecreations",
                )
                mapView.post {
                    val transition = latestEntryTransition
                    val entryStillWaiting = transition.phase == EntryTransitionPhase.PLAY_REQUESTED &&
                        nativeEntryPreparation?.generation == transition.generation &&
                        readyEntryGeneration != transition.generation
                    if (!teardown.isClosed && !nativePresentationGate.isHidden && !entryStillWaiting) {
                        mapCover.visibility = View.GONE
                        windView.setRenderEligible(true)
                        reconcileMarkerPresentation()
                        currentMapStyleError(null)
                    }
                }
            }
        }
    }
    val failedListener = remember(mapView, teardown) {
        MapView.OnDidFailLoadingMapListener { reason ->
            val showFailure = !teardown.isClosed && mapRevealGate.markFailed()
            nativeEntryPreparation?.let { failedEntryGeneration = it.generation }
            if (showFailure) mapView.post {
                if (!teardown.isClosed && mapRevealGate.hasFailed) {
                    Log.w("RainRadarMap", "Base map failed to load: $reason")
                    currentMapPreparing(false)
                    currentMapStyleError("Map style unavailable · refresh to retry")
                }
            }
        }
    }
    DisposableEffect(
        mapView,
        teardown,
        startingListener,
        renderedListener,
        renderedMapListener,
        failedListener,
    ) {
        onDispose {
            mapView.removeOnWillStartRenderingFrameListener(startingListener)
            mapView.removeOnDidFinishRenderingFrameListener(renderedListener)
            mapView.removeOnDidFinishRenderingMapListener(renderedMapListener)
            mapView.removeOnDidFailLoadingMapListener(failedListener)
        }
    }

    // AndroidView's factory otherwise runs only once, even when the remembered MapView changes.
    // Key the interop node so a watchdog/manual refresh actually attaches the replacement view.
    key(mapView) {
        AndroidView(
            factory = {
            FrameLayout(context).apply {
                mapContainer = this
                setBackgroundColor(RadarMapAppearance.loadingBackgroundArgb(mapStyle))
                // Register synchronously before attaching MapView: a cached style may render
                // before Compose's DisposableEffect runs after this factory returns.
                mapView.addOnWillStartRenderingFrameListener(startingListener)
                mapView.addOnDidFinishRenderingFrameListener(renderedListener)
                mapView.addOnDidFinishRenderingMapListener(renderedMapListener)
                mapView.addOnDidFailLoadingMapListener(failedListener)
                addView(
                    mapView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
                addView(baseMarkerView, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                baseMarkerView.update(latestMarkerPlace)
                baseMarkerView.setMarkerScale(markerScale)
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
                    baseMarkerView.bind(ready)
                    activeRadarSlot?.attachMap(ready)
                    pendingRadarSlot?.attachMap(ready)
                    windView.bind(ready)
                    ready.setMaxZoomPreference(21.0)
                    ready.uiSettings.isRotateGesturesEnabled = false
                    ready.uiSettings.isTiltGesturesEnabled = false
                    ready.uiSettings.isAttributionEnabled = true
                    val mapWidth = mapView.width.takeIf { it > 0 }
                        ?: resources.displayMetrics.widthPixels
                    val initialPlace = latestMapPlace
                    val target = cameraMemory.target(initialPlace, mapWidth, latestRecenterSignal)
                    appliedPlace = initialPlace
                    val pendingEntry = latestEntryFocusEnabled &&
                        latestEntryTransition.generation > 0 &&
                        latestEntryTransition.phase != EntryTransitionPhase.SETTLED &&
                        latestEntryFocusDurationMillis > 0
                    ready.cameraPosition = northUpCamera(
                        if (pendingEntry) {
                            target.copy(zoom = RadarEntryFocusPolicy.startZoom(target.zoom))
                        } else target,
                    )
                    if (!pendingEntry) saveCamera(ready, initialPlace)
                    val listener = MapLibreMap.OnCameraMoveListener {
                        // The Travel loop can publish at display refresh cadence. Persist only
                        // settled/user cameras, not every intermediate centre.
                        if (!travelCameraFollower.isRunning) {
                            appliedPlace?.let { saveCamera(ready, it) }
                        }
                        activeRadarSlot?.onCameraMoved()
                        pendingRadarSlot?.onCameraMoved()
                        baseMarkerView.onCameraMoved()
                        windView.cameraMoved()
                        latestRadarSession?.let { current ->
                            currentRadarTierCallback(RadarOverlayPlanner.activeTier(
                                ready.cameraPosition.zoom,
                                current.regional != null || current.legacyArchive != null,
                                current.detail != null,
                            ))
                        }
                    }
                    cameraListener = listener
                    ready.addOnCameraMoveListener(listener)
                    val startListener = MapLibreMap.OnCameraMoveStartedListener { reason ->
                        val userGesture = mapGestureOwnership.acceptsCameraStart(
                            reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE,
                        )
                        if (userGesture) {
                            // A raw DOWN has already paused Travel's vsync writer. Cancel any
                            // unrelated retained MapLibre transition and make the manual camera
                            // authoritative; pointer UP must not resume this claimed gesture.
                            ready.cancelTransitions()
                            travelCameraFollower.stop()
                            nativeEntryPreparation?.let { preparation ->
                                cancelledEntryGeneration = preparation.generation
                                finishedEntryGeneration = preparation.generation
                                entryFrameHandshake.cancel(preparation.generation)
                                revealNativePresentation(preparation.generation)
                                currentEntryAnimationStart(preparation.generation, false)
                            }
                            cameraIntentOwner.invalidate()
                            activeEntryFocusToken = null
                            nativeEntryPreparation = null
                            currentManualGesture()
                        } else if (!travelCameraFollower.isRunning) {
                            TravelModeDiagnostics.record(
                                latestFollowLive,
                                latestFollowLive,
                                RadarTravelTransitionReason.PROGRAMMATIC_CAMERA,
                            )
                        }
                    }
                    cameraStartListener = startListener
                    ready.addOnCameraMoveStartedListener(startListener)
                    val idle = MapLibreMap.OnCameraIdleListener {
                        if (!teardown.isClosed && mapView.width > 0 && mapView.height > 0) {
                            // MapLibre can publish its final projection after the last move callback.
                            // Re-project the radar, wind and selected marker at the settled camera.
                            // Satellite ImageSources remain fixed to the selected region and do not
                            // participate in viewport-driven reloads.
                            activeRadarSlot?.onCameraMoved()
                            pendingRadarSlot?.onCameraMoved()
                            baseMarkerView.onCameraMoved()
                            windView.cameraMoved()
                            val nowElapsedNanos = android.os.SystemClock.elapsedRealtimeNanos()
                            if (RadarTravelViewportPolicy.shouldPublish(
                                    following = travelCameraFollower.isRunning,
                                    nowElapsedNanos = nowElapsedNanos,
                                    lastPublishedElapsedNanos = lastWindViewportPublishElapsedNanos,
                                )
                            ) {
                                val bounds = ready.projection.visibleRegion.latLngBounds
                                currentWindViewportCallback(WindViewport(
                                    bounds.getLatSouth(), bounds.getLonWest(),
                                    bounds.getLatNorth(), bounds.getLonEast(),
                                ))
                                lastWindViewportPublishElapsedNanos = nowElapsedNanos
                            }
                            latestRadarSession?.let { current ->
                                currentRadarTierCallback(RadarOverlayPlanner.activeTier(
                                    ready.cameraPosition.zoom,
                                    current.regional != null || current.legacyArchive != null,
                                    current.detail != null,
                                ))
                            }
                        }
                    }
                    cameraIdleListener = idle
                    ready.addOnCameraIdleListener(idle)
                    mapView.post { if (!teardown.isClosed) idle.onCameraIdle() }
                    activeRadarSlot?.onCameraMoved()
                    pendingRadarSlot?.onCameraMoved()
                    baseMarkerView.onCameraMoved()
                }
            }
        },
            update = { container ->
            mapContainer = container
            container.setBackgroundColor(RadarMapAppearance.loadingBackgroundArgb(mapStyle))
            mapView.setBackgroundColor(RadarMapAppearance.loadingBackgroundArgb(mapStyle))
            val pendingEntryGeneration = entryTransition.generation.takeIf {
                entryFocusEnabled && it > 0 && entryFocusDurationMillis > 0 &&
                    entryTransition.phase != EntryTransitionPhase.SETTLED &&
                    cancelledEntryGeneration != it && finishedEntryGeneration != it
            }
            pendingEntryGeneration?.let(::hideNativePresentation)
            when {
                desiredRadarSlot == null -> {
                    pendingRadarSlot?.removeFrom(container)
                    activeRadarSlot?.removeFrom(container)
                    pendingRadarSlot?.dispose()
                    activeRadarSlot?.takeUnless { it === pendingRadarSlot }?.dispose()
                    pendingRadarSlot = null
                    activeRadarSlot = null
                    coverageMask.clear()
                    baseMarkerView.visibility = View.VISIBLE
                    currentStatusCallback(RadarRendererStatus.Loading)
                }
                desiredRadarSlot !== activeRadarSlot && desiredRadarSlot !== pendingRadarSlot -> {
                    pendingRadarSlot?.removeFrom(container)
                    pendingRadarSlot?.dispose()
                    pendingRadarSlot = desiredRadarSlot
                    desiredRadarSlot.addTo(container)
                    desiredRadarSlot.setVisible(
                        radarPresentationVisible && !nativePresentationGate.isHidden,
                    )
                    map?.let(desiredRadarSlot::attachMap)
                    latestBracket?.let { desiredRadarSlot.setState(it, latestPlaying, latestMarkerPlace) }
                    desiredRadarSlot.setMarker(latestMarkerPlace)
                    windView.bringToFront()
                    if (mapCover.isVisible) mapCover.bringToFront()
                }
            }
            baseMarkerView.update(markerPlace)
            baseMarkerView.setMarkerScale(markerScale)
            activeRadarSlot?.setVisible(radarPresentationVisible && !nativePresentationGate.isHidden)
            pendingRadarSlot?.setVisible(radarPresentationVisible && !nativePresentationGate.isHidden)
            activeRadarSlot?.setMarkerScale(markerScale)
            pendingRadarSlot?.setMarkerScale(markerScale)
            if (bracket != null && (lastBracket != bracket || lastPlaying != isPlaying)) {
                // A replacement session preloads above the old usable radar. Only the slot that
                // owns the new timeline may consume its frame indices; keep the outgoing slot
                // frozen until the replacement has produced a renderable frame.
                activeRadarSlot?.takeIf { it.session === session }
                    ?.setState(bracket, isPlaying, markerPlace)
                pendingRadarSlot?.takeIf { it.session === session }
                    ?.setState(bracket, isPlaying, markerPlace)
                lastBracket = bracket
                lastPlaying = isPlaying
            } else {
                activeRadarSlot?.setMarker(markerPlace)
                pendingRadarSlot?.setMarker(markerPlace)
            }
            if (lastMarker != markerPlace) {
                activeRadarSlot?.setMarker(markerPlace)
                pendingRadarSlot?.setMarker(markerPlace)
                lastMarker = markerPlace
            }
            windView.setObservationCallback { token, count ->
                currentWindRenderObservation(token, count)
            }
            windView.visibility = if (nativePresentationGate.isHidden) View.INVISIBLE else View.VISIBLE
            windView.update(displayedWindGrid, windRenderToken, windArrowScale)
            reconcileMarkerPresentation()
            // Preference-only changes replace the scrim source/layer in-place. They do not
            // recreate the style, radar session, camera, timeline or ancillary data.
            map?.style?.let {
                coverageMask.reconcile(
                    it, activeRadarSlot?.session, mapStyle, coverageMaskDarkness,
                )
            }
        },
            modifier = modifier,
        )
    }

    // Only the basemap style changes: MapView, camera, overlay and session retain ownership.
    // Camera was explicitly positioned before style loading, so style defaults cannot reset it.
    androidx.compose.runtime.DisposableEffect(map, mapStyle) {
        val ready = map
        if (ready == null || teardown.isClosed) return@DisposableEffect onDispose { }
        var active = true
        val styleGeneration = mapRevealGate.styleRequested()
        currentMapPreparing(true)
        windView.setRenderEligible(false)
        currentWindRendererReset()
        mapCover.setBackgroundColor(RadarMapAppearance.loadingBackgroundArgb(mapStyle))
        mapCover.visibility = View.VISIBLE
        currentMapStyleError(null)
        try {
            ready.setStyle(RadarMapAppearance.styleUrl(mapStyle)) {
                if (active && !teardown.isClosed) {
                    if (RadarMapLabelContrastPolicy.paletteFor(mapStyle) != null) {
                        applyMapLabelContrast(it, mapStyle)
                    }
                    coverageMask.reconcile(
                        it, activeRadarSlot?.session, mapStyle, coverageMaskDarkness,
                    )
                }
                if (active && !teardown.isClosed && mapRevealGate.styleLoaded(styleGeneration)) {
                    Log.i(
                        "RainRadarRender",
                        "event=style_loaded styleGeneration=$styleGeneration rendererGeneration=" +
                            "$rendererRecoveryGeneration.$automaticRendererRecreations",
                    )
                    satelliteBuffers.onStyleLoaded(
                        it, latestSatelliteRequests, latestEnabledSatelliteLayers,
                        latestSatellitePlayback, latestSatelliteCacheContext,
                    )
                    activeRadarSlot?.onCameraMoved()
                    pendingRadarSlot?.onCameraMoved()
                    baseMarkerView.onCameraMoved()
                    ready.triggerRepaint()
                    mapView.post { if (!teardown.isClosed) cameraIdleListener?.onCameraIdle() }
                }
            }
        } catch (failure: Exception) {
            Log.e("RainRadarMap", "Base map style could not start", failure)
            if (mapRevealGate.markFailed()) {
                currentMapPreparing(false)
                currentMapStyleError("Map style unavailable · refresh to retry")
            }
        }
        onDispose { active = false }
    }

    LaunchedEffect(
        mapView,
        mapStyle,
        rendererRecoveryGeneration,
        automaticRendererRecreations,
    ) {
        delay(RadarMapRecoveryPolicy.REPAINT_AFTER_MILLIS)
        if (!teardown.isClosed && mapRevealGate.isCovered) {
            Log.w(
                "RainRadarRender",
                "event=render_watchdog action=repaint rendererGeneration=" +
                    "$rendererRecoveryGeneration.$automaticRendererRecreations",
            )
            map?.triggerRepaint()
        }
        delay(
            RadarMapRecoveryPolicy.RECREATE_AFTER_MILLIS -
                RadarMapRecoveryPolicy.REPAINT_AFTER_MILLIS,
        )
        if (teardown.isClosed || !mapRevealGate.isCovered) return@LaunchedEffect
        if (RadarMapRecoveryPolicy.shouldRecreate(
                isCovered = true,
                automaticRecreations = automaticRendererRecreations,
            )
        ) {
            Log.w(
                "RainRadarRender",
                "event=render_watchdog action=recreate rendererGeneration=" +
                    "$rendererRecoveryGeneration.$automaticRendererRecreations",
            )
            currentAutomaticRendererRecreation()
        } else if (mapRevealGate.markFailed()) {
            Log.w(
                "RainRadarRender",
                "event=render_watchdog action=failed rendererGeneration=" +
                    "$rendererRecoveryGeneration.$automaticRendererRecreations",
            )
            currentMapPreparing(false)
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
        nativeEntryPreparation?.let { preparation ->
            entryFrameHandshake.cancel(preparation.generation)
            if (entryTransition.phase == EntryTransitionPhase.PREPARED &&
                preparation.generation == entryTransition.generation
            ) {
                // A place chosen while Radar is retained off-screen replaces the pending entry
                // target. Keep this generation armed so the new place still receives its normal
                // focus animation when Radar is revealed.
                cancelledEntryGeneration = null
                finishedEntryGeneration = null
            } else {
                cancelledEntryGeneration = preparation.generation
                finishedEntryGeneration = preparation.generation
                revealNativePresentation(preparation.generation)
                currentEntryAnimationStart(preparation.generation, false)
            }
        }
        cameraIntentOwner.invalidate()
        activeEntryFocusToken = null
        nativeEntryPreparation = null
        readyEntryGeneration = null
        failedEntryGeneration = null
        travelCameraFollower.stop()
        ready.cancelTransitions()
        oldPlace?.let { saveCamera(ready, it) }
        val mapWidth = mapView.width.takeIf { it > 0 } ?: mapView.resources.displayMetrics.widthPixels
        val cameraPlace = if (mapPlace.isCurrentLocation) latestMarkerPlace else mapPlace
        val target = cameraMemory.target(cameraPlace, mapWidth, recenterSignal)
        appliedPlace = cameraPlace
        ready.cameraPosition = northUpCamera(target)
        saveCamera(ready, cameraPlace)
    }

    LaunchedEffect(
        map,
        entryTransition.generation,
        entryFocusEnabled,
        entryFocusDurationMillis,
        mapPlace.id,
    ) {
        val ready = map ?: return@LaunchedEffect
        val generation = entryTransition.generation
        if (!entryFocusEnabled || generation <= 0 || teardown.isClosed ||
            entryTransition.phase == EntryTransitionPhase.SETTLED ||
            cancelledEntryGeneration == generation || finishedEntryGeneration == generation
        ) return@LaunchedEffect
        val mapWidth = mapView.width.takeIf { it > 0 } ?: mapView.resources.displayMetrics.widthPixels
        val cameraPlace = if (mapPlace.isCurrentLocation) latestMarkerPlace else mapPlace
        val target = cameraMemory.target(cameraPlace, mapWidth, recenterSignal)
        if (entryFocusDurationMillis <= 0) {
            ready.cancelTransitions()
            ready.cameraPosition = northUpCamera(target)
            appliedPlace = cameraPlace
            activeEntryFocusToken = null
            nativeEntryPreparation = null
            readyEntryGeneration = generation
            finishedEntryGeneration = generation
            saveCamera(ready, cameraPlace)
            currentEntryAnimationStart(generation, false)
            return@LaunchedEffect
        }
        val starting = target.copy(zoom = RadarEntryFocusPolicy.startZoom(target.zoom))
        travelCameraFollower.stop()
        val intentToken = cameraIntentOwner.claim()
        activeEntryFocusToken = intentToken
        cancelledEntryGeneration = null
        finishedEntryGeneration = null
        readyEntryGeneration = null
        failedEntryGeneration = null
        val retainedStyleWasCoherent = !mapRevealGate.isCovered && !mapRevealGate.hasFailed
        nativeEntryPreparation = RadarNativeEntryPreparation(
            generation = generation,
            intentToken = intentToken,
            place = cameraPlace,
            target = target,
            start = starting,
            retainedStyleWasCoherent = retainedStyleWasCoherent,
        )
        hideNativePresentation(generation)
        entryFrameHandshake.prepare(generation)
        appliedPlace = cameraPlace
        ready.cancelTransitions()
        ready.cameraPosition = northUpCamera(starting)
    }

    LaunchedEffect(
        map,
        entryTransition.generation,
        entryTransition.phase,
        entryFocusEnabled,
        entryFocusDurationMillis,
    ) {
        val generation = entryTransition.generation
        if (map != null || entryTransition.phase != EntryTransitionPhase.PLAY_REQUESTED ||
            !entryFocusEnabled || entryFocusDurationMillis <= 0
        ) return@LaunchedEffect
        delay(RadarEntryFramePolicy.PLAY_READINESS_FAILURE_TIMEOUT_MILLIS)
        if (map == null && latestEntryTransition.generation == generation &&
            latestEntryTransition.phase == EntryTransitionPhase.PLAY_REQUESTED
        ) {
            // MapLibre startup itself can fail independently of frame rendering. Do not leave the
            // Compose title/marker presentation gated forever; a later map startup will observe
            // the settled phase and open directly at the authoritative camera target.
            finishedEntryGeneration = generation
            currentEntryAnimationStart(generation, false)
        }
    }

    LaunchedEffect(
        map,
        entryTransition.generation,
        entryTransition.phase,
        entryFocusEnabled,
        entryFocusDurationMillis,
        nativeEntryPreparation,
        readyEntryGeneration,
        failedEntryGeneration,
        cancelledEntryGeneration,
        finishedEntryGeneration,
    ) {
        val ready = map ?: return@LaunchedEffect
        val generation = entryTransition.generation
        if (entryTransition.phase != EntryTransitionPhase.PLAY_REQUESTED ||
            !entryFocusEnabled || generation <= 0 || teardown.isClosed
        ) return@LaunchedEffect

        if (finishedEntryGeneration == generation) return@LaunchedEffect

        if (cancelledEntryGeneration == generation) {
            currentEntryAnimationStart(generation, false)
            return@LaunchedEffect
        }
        if (entryFocusDurationMillis <= 0) return@LaunchedEffect

        val preparation = nativeEntryPreparation?.takeIf { it.generation == generation }
        if (preparation == null || readyEntryGeneration != generation) {
            hideNativePresentation(generation)
            if (failedEntryGeneration != generation) {
                delay(RadarEntryFramePolicy.PLAY_READINESS_FAILURE_TIMEOUT_MILLIS)
            }
            val current = nativeEntryPreparation?.takeIf { it.generation == generation }
            if (current == null) {
                if (latestEntryTransition.generation == generation &&
                    latestEntryTransition.phase == EntryTransitionPhase.PLAY_REQUESTED &&
                    finishedEntryGeneration != generation
                ) {
                    val mapWidth = mapView.width.takeIf { it > 0 }
                        ?: mapView.resources.displayMetrics.widthPixels
                    val cameraPlace = if (mapPlace.isCurrentLocation) latestMarkerPlace else mapPlace
                    val target = cameraMemory.target(cameraPlace, mapWidth, recenterSignal)
                    cameraIntentOwner.invalidate()
                    ready.cancelTransitions()
                    ready.cameraPosition = northUpCamera(target)
                    activeEntryFocusToken = null
                    appliedPlace = cameraPlace
                    finishedEntryGeneration = generation
                    nativeEntryPreparation = RadarNativeEntryPreparation(
                        generation = generation,
                        intentToken = cameraIntentOwner.claim(),
                        place = cameraPlace,
                        target = target,
                        start = target,
                        retainedStyleWasCoherent = !mapRevealGate.isCovered &&
                            !mapRevealGate.hasFailed,
                    )
                    entryFrameHandshake.prepare(generation)
                    saveCamera(ready, cameraPlace)
                    currentEntryAnimationStart(generation, false)
                }
                return@LaunchedEffect
            }
            if (readyEntryGeneration == generation ||
                !cameraIntentOwner.owns(current.intentToken) ||
                latestEntryTransition.generation != generation ||
                latestEntryTransition.phase != EntryTransitionPhase.PLAY_REQUESTED
            ) return@LaunchedEffect

            // A missing native frame or an explicit MapLibre failure must not hold Compose's
            // navigation state forever. Settle at the authoritative target, but keep every native
            // weather child concealed until MapLibre later produces a coherent frame there.
            entryFrameHandshake.cancel(generation)
            cancelledEntryGeneration = generation
            finishedEntryGeneration = generation
            ready.cancelTransitions()
            ready.cameraPosition = northUpCamera(current.target)
            activeEntryFocusToken = null
            nativeEntryPreparation = current.copy(start = current.target)
            entryFrameHandshake.prepare(generation)
            readyEntryGeneration = null
            appliedPlace = current.place
            saveCamera(ready, current.place)
            currentEntryAnimationStart(generation, false)
            return@LaunchedEffect
        }

        if (!cameraIntentOwner.owns(preparation.intentToken)) return@LaunchedEffect
        revealNativePresentation(generation)
        currentEntryAnimationStart(generation, true)
        // Let Compose publish the matching marker/title start before native camera easing begins.
        withFrameNanos { }
        if (!cameraIntentOwner.owns(preparation.intentToken) ||
            latestEntryTransition.generation != generation ||
            latestEntryTransition.phase != EntryTransitionPhase.PLAY_REQUESTED
        ) return@LaunchedEffect

        var completed = false
        try {
            ready.easeCamera(
                CameraUpdateFactory.newCameraPosition(northUpCamera(preparation.target)),
                entryFocusDurationMillis,
            )
            delay(entryFocusDurationMillis + 20L)
            completed = true
        } finally {
            val settledByPresentation = latestEntryTransition.generation == generation &&
                latestEntryTransition.phase == EntryTransitionPhase.SETTLED
            val ownsCamera = cameraIntentOwner.owns(preparation.intentToken) &&
                activeEntryFocusToken == preparation.intentToken && !teardown.isClosed
            if (ownsCamera) {
                // Stop a cancelled native ease before releasing capture suppression. A normal
                // return retains the exact panned target; only Travel may finish on a newer fix.
                ready.cancelTransitions()
                val followTarget = latestMarkerPlace.takeIf {
                    latestFollowLive && mapPlace.isCurrentLocation
                }
                val finalTarget = RadarEntryFocusCompletionPolicy.finalTarget(
                    entryTarget = preparation.target,
                    completed = completed || settledByPresentation,
                    ownsCamera = true,
                    followTarget = followTarget,
                )
                activeEntryFocusToken = null
                nativeEntryPreparation = null
                finishedEntryGeneration = generation
                entryFrameHandshake.cancel(generation)
                if (finalTarget != null) {
                    val finalPlace = followTarget ?: preparation.place
                    ready.cameraPosition = northUpCamera(finalTarget)
                    appliedPlace = finalPlace
                    saveCamera(ready, finalPlace)
                }
            }
        }
    }

    // A live marker changes the snapshot's reference place, not the camera viewport.
    LaunchedEffect(map, markerPlace) {
        val ready = map ?: return@LaunchedEffect
        if (teardown.isClosed || appliedPlace?.id != mapPlace.id) return@LaunchedEffect
        val reference = if (mapPlace.isCurrentLocation) markerPlace else mapPlace
        appliedPlace = reference
        saveCamera(ready, reference)
    }

    LaunchedEffect(
        map, followLive, markerPlace.latitude, markerPlace.longitude, recenterSignal,
        liveFixElapsedRealtimeNanos, liveTargetProjected,
    ) {
        val ready = map ?: return@LaunchedEffect
        if (!followLive || teardown.isClosed) {
            travelCameraFollower.stop()
            reconcileMarkerPresentation()
            return@LaunchedEffect
        }
        reconcileMarkerPresentation()
        // The entry transition owns the camera for one second. Marker projection still updates;
        // its latest accepted fix is applied exactly when that transition completes.
        if (activeEntryFocusToken != null) return@LaunchedEffect
        if (!travelCameraFollower.isRunning) {
            cameraIntentOwner.invalidate()
            val current = ready.cameraPosition.target
            if (current != null) travelCameraFollower.start(
                current.latitude,
                current.longitude,
                android.os.SystemClock.elapsedRealtimeNanos(),
            )
        }
        appliedPlace = markerPlace
        val action = travelCameraFollower.submit(
            markerPlace.latitude,
            markerPlace.longitude,
            liveFixElapsedRealtimeNanos,
        )
        LocationCadenceDiagnostics.record(
            "map-target",
            null,
            liveFixElapsedRealtimeNanos,
            (if (liveTargetProjected) "projected" else "measured") +
                " action=${action.name.lowercase()}",
        )
    }
}

private fun northUpCamera(target: RadarCameraTarget): CameraPosition =
    CameraPosition.Builder()
        .target(LatLng(target.latitude, target.longitude))
        .zoom(target.zoom)
        .bearing(0.0)
        .tilt(0.0)
        .build()
