@file:android.annotation.SuppressLint("LogNotTimber") // Local tile diagnostics are needed on devices without telemetry.
package com.rainalarm.app.ui

import android.graphics.Canvas
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
import com.rainalarm.app.data.RadarMapLayer
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
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.style.sources.TileSet
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.maps.Style
import org.maplibre.android.tile.TileOperation
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val OPEN_FREE_MAP_DARK_STYLE = "https://tiles.openfreemap.org/styles/dark"
private const val OPEN_FREE_MAP_LIGHT_STYLE = "https://tiles.openfreemap.org/styles/liberty"

internal object RadarMapAppearance {
    fun styleUrl(dark: Boolean): String = if (dark) OPEN_FREE_MAP_DARK_STYLE else OPEN_FREE_MAP_LIGHT_STYLE
    fun loadingBackgroundArgb(dark: Boolean): Int = if (dark) 0xFF111C24.toInt() else 0xFFF3F5F4.toInt()
}
private const val RADAR_OPACITY = 0.90
private const val SATELLITE_SOURCE_ID = "rain-alarm-satellite-source"
private const val SATELLITE_LAYER_ID = "rain-alarm-satellite-layer"

private fun applySatelliteLayer(style: Style, satellite: EumetLayerMetadata?) {
    if (style.getLayer(SATELLITE_LAYER_ID) != null) style.removeLayer(SATELLITE_LAYER_ID)
    if (style.getSource(SATELLITE_SOURCE_ID) != null) style.removeSource(SATELLITE_SOURCE_ID)
    if (satellite == null) return
    val tiles = TileSet("2.2.0", satellite.tileUrl()).apply {
        setMinZoom(0f)
        setMaxZoom(8f) // Cap remote tile load; overscale thereafter.
        setBounds(satellite.west.toFloat(), satellite.south.toFloat(),
            satellite.east.toFloat(), satellite.north.toFloat())
        attribution = "© EUMETSAT (CC BY 4.0)"
    }
    style.addSource(RasterSource(SATELLITE_SOURCE_ID, tiles, 256))
    style.addLayer(RasterLayer(SATELLITE_LAYER_ID, SATELLITE_SOURCE_ID)
        .withProperties(PropertyFactory.rasterOpacity(
            if (satellite.choice == RadarMapLayer.FOG) 0.38f else 0.7f)))
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
    darkMap: Boolean = true,
    cameraMemory: RadarCameraMemory,
    windGrid: WindGrid? = null,
    windArrowScale: Float = 1f,
    satelliteLayer: EumetLayerMetadata? = null,
    onLayerError: (String) -> Unit = {},
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
            darkMap,
            cameraMemory,
            windGrid,
            windArrowScale,
            satelliteLayer,
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
    darkMap: Boolean,
    cameraMemory: RadarCameraMemory,
    windGrid: WindGrid?,
    windArrowScale: Float,
    satelliteLayer: EumetLayerMetadata?,
    onLayerError: (String) -> Unit,
    onMapStyleError: (String?) -> Unit,
    onWindViewportChanged: (WindViewport) -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember {
        MapLibre.getInstance(context)
        // Texture mode keeps MapLibre in the normal View hierarchy so our transparent GLES
        // TextureView can reliably composite above it on every Android surface compositor.
        val options = MapLibreMapOptions.createFromAttributes(context, null)
            .textureMode(true)
            .foregroundLoadColor(RadarMapAppearance.loadingBackgroundArgb(darkMap))
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
            setBackgroundColor(RadarMapAppearance.loadingBackgroundArgb(darkMap))
            onCreate(Bundle())
        }
    }
    val currentStatusCallback by rememberUpdatedState(onRendererStatus)
    val currentLayerError by rememberUpdatedState(onLayerError)
    val currentMapStyleError by rememberUpdatedState(onMapStyleError)
    val currentWindViewportCallback by rememberUpdatedState(onWindViewportChanged)
    val latestSatellite by rememberUpdatedState(satelliteLayer)
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
        View(context).apply { setBackgroundColor(RadarMapAppearance.loadingBackgroundArgb(darkMap)) }
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var cameraListener by remember { mutableStateOf<MapLibreMap.OnCameraMoveListener?>(null) }
    var cameraStartListener by remember { mutableStateOf<MapLibreMap.OnCameraMoveStartedListener?>(null) }
    var cameraIdleListener by remember { mutableStateOf<MapLibreMap.OnCameraIdleListener?>(null) }
    var appliedPlace by remember(mapView) { mutableStateOf<SavedPlace?>(null) }
    var lastBracket by remember(session) { mutableStateOf<RadarTimelineBracket?>(null) }
    var lastPlaying by remember(session) { mutableStateOf<Boolean?>(null) }
    var lastMarker by remember(session) { mutableStateOf<SavedPlace?>(null) }
    fun saveCamera(ready: MapLibreMap, place: SavedPlace) {
        val position = ready.cameraPosition
        val center = position.target ?: return
        cameraMemory.capture(place, center.latitude, center.longitude, position.zoom)
    }
    val teardown = remember(session, mapView, overlay, staticFallback) {
        RadarResourceTeardown(
            stopOverlay = {
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

    DisposableEffect(mapView) {
        val listener = MapView.OnTileActionListener { operation, _, _, _, _, _, sourceId ->
            if (operation == TileOperation.Error && sourceId == SATELLITE_SOURCE_ID) {
                Log.e("RainRadarLayers", "Satellite WMS tile failed")
                mapView.post { if (!teardown.isClosed) currentLayerError("Satellite tiles unavailable") }
            }
        }
        mapView.addOnTileActionListener(listener)
        onDispose { mapView.removeOnTileActionListener(listener) }
    }

    val startingListener = remember(mapView, teardown) {
        MapView.OnWillStartRenderingFrameListener {
            if (!teardown.isClosed) mapRevealGate.frameStarted()
        }
    }
    val renderedListener = remember(mapView, teardown) {
        MapView.OnDidFinishRenderingFrameListener { fully, _, _ ->
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
                setBackgroundColor(RadarMapAppearance.loadingBackgroundArgb(darkMap))
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
                            // Re-project both overlay tiers and the selected marker at the settled
                            // camera so a zoom-threshold switch cannot retain gesture-era vertices.
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
            container.setBackgroundColor(RadarMapAppearance.loadingBackgroundArgb(darkMap))
            mapView.setBackgroundColor(RadarMapAppearance.loadingBackgroundArgb(darkMap))
            if (lastBracket != bracket || lastPlaying != isPlaying) {
                overlay.setState(bracket, isPlaying, markerPlace)
                lastBracket = bracket
                lastPlaying = isPlaying
            } else overlay.setMarker(markerPlace)
            if (lastMarker != markerPlace) {
                staticFallback?.setPlace(markerPlace)
                lastMarker = markerPlace
            }
            windView.update(windGrid, windArrowScale)
        },
        modifier = modifier,
    )

    // Only the basemap style changes: MapView, camera, overlay and session retain ownership.
    // Camera was explicitly positioned before style loading, so style defaults cannot reset it.
    androidx.compose.runtime.DisposableEffect(map, darkMap) {
        val ready = map
        if (ready == null || teardown.isClosed) return@DisposableEffect onDispose { }
        var active = true
        val styleGeneration = mapRevealGate.styleRequested()
        mapCover.setBackgroundColor(RadarMapAppearance.loadingBackgroundArgb(darkMap))
        mapCover.visibility = View.VISIBLE
        currentMapStyleError(null)
        try {
            ready.setStyle(RadarMapAppearance.styleUrl(darkMap)) {
                if (active && !teardown.isClosed && mapRevealGate.styleLoaded(styleGeneration)) {
                    try { applySatelliteLayer(it, latestSatellite) }
                    catch (failure: Exception) {
                        Log.e("RainRadarLayers", "Satellite raster style failed", failure)
                        currentLayerError("Satellite layer unavailable")
                    }
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

    LaunchedEffect(mapView, darkMap) {
        delay(25_000)
        if (!teardown.isClosed && mapRevealGate.isCovered && mapRevealGate.markFailed()) {
            Log.w("RainRadarMap", "Base map did not finish rendering within 25 seconds")
            currentMapStyleError("Map imagery still loading · refresh to retry")
        }
    }

    DisposableEffect(map, satelliteLayer) {
        val ready = map
        if (ready == null || teardown.isClosed) return@DisposableEffect onDispose { }
        var active = true
        ready.getStyle { style ->
            if (active && !teardown.isClosed) try { applySatelliteLayer(style, satelliteLayer) }
            catch (failure: Exception) {
                Log.e("RainRadarLayers", "Satellite raster update failed", failure)
                currentLayerError("Satellite layer unavailable")
            }
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
