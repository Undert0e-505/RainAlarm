package com.rainalarm.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.util.Log
import com.rainalarm.app.data.RadarSession
import com.rainalarm.app.data.RegionalRadarArea
import com.rainalarm.app.data.SavedPlace
import com.rainalarm.app.data.consumeAndRelease
import com.rainalarm.app.domain.RadarProjectionMesh
import com.rainalarm.app.domain.RadarTimelineBracket
import com.rainalarm.app.domain.RegionalProjectionMesh
import com.rainalarm.app.domain.BoundsProjectionMesh
import com.rainalarm.app.domain.RadarResolutionTier
import com.rainalarm.app.domain.RadarOverlayPlanner
import com.rainalarm.app.domain.RadarVelocityField
import com.rainalarm.app.domain.RainAlarmPalette
import com.rainalarm.app.domain.SnowAlarmPalette
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Separates a logical regional identity from the raster's actual encoding and geometry.
 * OPERA carries a region for fallback/attribution, but its bitmaps are already resampled onto
 * their declared Web Mercator tier bounds. Only a real legacy archive is a native Meteo raster.
 */
internal class RadarRasterRenderPolicy private constructor(
    val logicalRegion: RegionalRadarArea?,
    val legacyNativeRaster: Boolean,
) {
    init {
        require(!legacyNativeRaster || logicalRegion != null) {
            "A native regional raster requires its projection metadata"
        }
    }

    val coloredSource: Float get() = if (legacyNativeRaster) 0f else 1f

    fun mesh(tierBounds: com.rainalarm.app.domain.GeoQuad?): RadarProjectionMesh =
        if (legacyNativeRaster) {
            RegionalProjectionMesh.build(requireNotNull(logicalRegion))
        } else {
            BoundsProjectionMesh.build(requireNotNull(tierBounds))
        }

    fun legacyTextureLayout(): LegacyTextureLayout? = if (legacyNativeRaster) {
        requireNotNull(logicalRegion).let {
            LegacyTextureLayout.plan(it.rasterWidth, it.rasterHeight, forcePowerOfTwo = true)
        }
    } else null

    fun velocityScale(denseVelocity: RadarVelocityField?): Float = if (legacyNativeRaster) {
        requireNotNull(logicalRegion).velocityScale
    } else {
        denseVelocity?.maxDisplacementPixels ?: 0f
    }

    fun sourceDimensions(bitmapDimensions: Pair<Int, Int>?): Pair<Int, Int> =
        if (legacyNativeRaster) {
            requireNotNull(logicalRegion).rasterWidth to logicalRegion.rasterHeight
        } else {
            requireNotNull(bitmapDimensions).also { (width, height) ->
                require(width > 0 && height > 0) {
                    "A bounds-projected raster draw requires a decoded bitmap"
                }
            }
        }

    companion object {
        fun forSession(session: RadarSession): RadarRasterRenderPolicy = resolve(
            logicalRegion = session.region,
            legacyArchivePresent = session.legacyArchive != null,
        )

        internal fun resolve(
            logicalRegion: RegionalRadarArea?,
            legacyArchivePresent: Boolean,
        ): RadarRasterRenderPolicy = RadarRasterRenderPolicy(logicalRegion, legacyArchivePresent)
    }
}

/**
 * Clean-room GLES renderer for the regional feed. It implements the recovered symmetric local
 * velocity warp without loading or linking the retired application's native library.
 *
 * Velocity JPEG R/G are uploaded as GLES LUMINANCE_ALPHA. This makes shader `.xw` reproduce the
 * original engine's two-channel sampling. JPEG compression means neutral motion is near 128,
 * rather than guaranteed to be exactly 128.
 */
@SuppressLint("LogNotTimber", "ViewConstructor") // Programmatic-only view; platform logs aid adb diagnosis.
internal class RadarGlOverlayView(
    context: Context,
    private val reportStatus: (RadarRendererStatus) -> Unit = {},
) : TextureView(context),
    RadarOverlayController,
    TextureView.SurfaceTextureListener {
    override val overlayView: View get() = this

    private val renderThread = HandlerThread("rain-radar-gl").apply { start() }
    private val renderHandler = Handler(renderThread.looper)
    private val stateLock = Any()
    private val lifecycleGate = RadarRendererLifecycle()
    private var session: RadarSession? = null
    private lateinit var geoMesh: RadarProjectionMesh
    private var activeTier = RadarResolutionTier.REGIONAL
    private var map: MapLibreMap? = null
    private var bracket = RadarTimelineBracket(0, 0, 0.0, false, 0.0)
    private var screenVertices: FloatArray? = null
    private var markerNdc: FloatArray? = null
    private var markerPlace: SavedPlace? = null
    private var disposed = false
    private var renderer: Renderer? = null
    private val renderQueued = AtomicBoolean(false)
    private val renderDirty = AtomicBoolean(false)
    @Volatile private var availableSurface: SurfaceTexture? = null
    @Volatile private var surfaceWidth = 0
    @Volatile private var surfaceHeight = 0
    private val stageGuard = RadarRendererStageGuard(SharedPreferencesRadarRendererStageStore(context))
    private val recoveredStage = stageGuard.interruptedStage
    private var rendererFailed = false

    companion object {
        private const val TAG = "RainRadarRenderer"
    }

    init {
        isOpaque = false
        surfaceTextureListener = this
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        translationZ = resources.displayMetrics.density
    }

    override fun bindSession(ownedSession: RadarSession) {
        check(session == null)
        session = ownedSession
        markerPlace = ownedSession.place
        activeTier = if (ownedSession.regional != null) RadarResolutionTier.REGIONAL else RadarResolutionTier.DETAIL
        geoMesh = meshFor(activeTier)
        if (ownedSession.legacyArchive != null) stageGuard.mark(RadarRendererStage.MESH_READY)
        val now = ownedSession.timelineFrames.indexOfLast { !it.forecast }.coerceAtLeast(0)
        bracket = RadarTimelineBracket(now, now, 0.0, false, 0.0)
        Log.i(TAG, "Session bound provider=${ownedSession.providerSelection.active} frames=${ownedSession.timelineFrames.size}")
        lifecycleGate.bind()
        report(RadarRendererStatus.Loading)
        renderHandler.post(::initializeIfReady)
    }

    override fun attachMap(ready: MapLibreMap) {
        if (disposed) return
        map = ready
        onCameraMoved()
    }

    override fun setState(next: RadarTimelineBracket, playing: Boolean, mapPlace: SavedPlace) {
        if (disposed) return
        val moved = synchronized(stateLock) {
            bracket = next
            val previous = markerPlace
            markerPlace = mapPlace
            previous?.latitude != mapPlace.latitude || previous.longitude != mapPlace.longitude
        }
        if (moved) updateMarkerProjection(mapPlace)
        requestRender()
    }

    override fun setMarker(mapPlace: SavedPlace) {
        if (disposed) return
        val moved = synchronized(stateLock) {
            val previous = markerPlace
            markerPlace = mapPlace
            previous?.latitude != mapPlace.latitude || previous.longitude != mapPlace.longitude
        }
        if (moved) updateMarkerProjection(mapPlace)
    }

    private fun updateMarkerProjection(mapPlace: SavedPlace) {
        if (width <= 0 || height <= 0) return
        val ready = map ?: return
        val point = ready.projection.toScreenLocation(LatLng(mapPlace.latitude, mapPlace.longitude))
        synchronized(stateLock) {
            markerNdc = floatArrayOf(point.x / width * 2f - 1f, 1f - point.y / height * 2f)
        }
        requestRender()
    }

    override fun onCameraMoved() {
        if (disposed || width <= 0 || height <= 0) return
        val ready = map ?: return
        val selectedTier = RadarOverlayPlanner.activeTier(
            ready.cameraPosition.zoom,
            session?.regional != null || session?.legacyArchive != null,
            session?.detail != null,
        )
        val selectedMesh = if (selectedTier != activeTier) meshFor(selectedTier) else geoMesh
        val updated = FloatArray(selectedMesh.vertices.size * 4)
        selectedMesh.vertices.forEachIndexed { index, vertex ->
            val point = ready.projection.toScreenLocation(LatLng(vertex.latitude, vertex.longitude))
            updated[index * 4] = point.x / width * 2f - 1f
            updated[index * 4 + 1] = 1f - point.y / height * 2f
            updated[index * 4 + 2] = vertex.u
            updated[index * 4 + 3] = vertex.v
        }
        val selected = synchronized(stateLock) { markerPlace }
        val marker = selected?.let {
            val point = ready.projection.toScreenLocation(LatLng(it.latitude, it.longitude))
            floatArrayOf(point.x / width * 2f - 1f, 1f - point.y / height * 2f)
        }
        synchronized(stateLock) {
            // Tier and its projected vertices are one render state. Publishing either first can
            // briefly draw z7 pixels on z5 bounds (or vice versa) while a gesture crosses 6.35.
            activeTier = selectedTier
            geoMesh = selectedMesh
            screenVertices = updated
            markerNdc = marker
        }
        requestRender()
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        lifecycleGate.dispose()
        val done = CountDownLatch(1)
        renderHandler.post {
            renderer?.close()
            renderer = null
            done.countDown()
        }
        done.await(750, TimeUnit.MILLISECONDS)
        if (!rendererFailed && session?.legacyArchive != null) stageGuard.clear()
        renderThread.quitSafely()
        map = null
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (disposed) return
        availableSurface = surface
        surfaceWidth = width
        surfaceHeight = height
        surface.setDefaultBufferSize(width, height)
        Log.i(TAG, "Texture surface available ${width}x$height")
        lifecycleGate.surfaceAvailable()
        report(RadarRendererStatus.Loading)
        renderHandler.post {
            initializeIfReady()
            drawOnRenderThread()
        }
        post(::onCameraMoved)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        renderHandler.post { renderer?.resize(width, height) }
        surfaceWidth = width
        surfaceHeight = height
        post(::onCameraMoved)
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        if (disposed || !renderThread.isAlive) return true
        availableSurface = null
        surfaceWidth = 0
        surfaceHeight = 0
        lifecycleGate.surfaceDestroyed()
        val done = CountDownLatch(1)
        renderHandler.post {
            renderer?.close()
            renderer = null
            done.countDown()
        }
        done.await(750, TimeUnit.MILLISECONDS)
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    private fun requestRender() {
        if (disposed) return
        renderDirty.set(true)
        if (!renderQueued.compareAndSet(false, true)) return
        renderHandler.post {
            do {
                renderDirty.set(false)
                drawOnRenderThread()
            } while (renderDirty.get() && !disposed)
            renderQueued.set(false)
            if (renderDirty.get() && !disposed) requestRender()
        }
    }

    private fun drawOnRenderThread() {
        if (disposed) return
        val snapshot = synchronized(stateLock) {
            RenderSnapshot(screenVertices?.copyOf(), markerNdc?.copyOf(), bracket, activeTier)
        }
        if (snapshot.vertices != null) runCatching {
            val drew = renderer?.draw(
                requireNotNull(snapshot.vertices), snapshot.marker, snapshot.bracket, snapshot.tier,
            )
                ?: false
            if (drew) {
                lifecycleGate.drawSucceeded()
                if (session?.legacyArchive != null) stageGuard.clear()
                val status = if (session?.legacyArchive != null) {
                    recoveredStage?.let {
                        RadarRendererStatus.Compatibility(
                            "Safe radar mode enabled after an interrupted ${it.name.lowercase().replace('_', ' ')} stage.",
                        )
                    } ?: RadarRendererStatus.Ready
                } else RadarRendererStatus.Ready
                report(status)
            }
        }.onFailure(::fail)
    }

    private data class RenderSnapshot(
        val vertices: FloatArray?,
        val marker: FloatArray?,
        val bracket: RadarTimelineBracket,
        val tier: RadarResolutionTier,
    )

    private fun meshFor(tier: RadarResolutionTier): RadarProjectionMesh {
        val bound = requireNotNull(session)
        return RadarRasterRenderPolicy.forSession(bound).mesh(bound.tier(tier)?.bounds)
    }

    private fun initializeIfReady() {
        if (disposed || renderer != null) return
        val surface = availableSurface ?: return
        val bound = session ?: return
        runCatching {
            renderer = Renderer(
                surface,
                surfaceWidth,
                surfaceHeight,
                bound,
                geoMesh.indices,
                stageGuard,
                recoveredStage != null,
            )
            lifecycleGate.initialized()
            Log.i(TAG, "EGL, shader, mesh and textures ready; scheduling first frame")
            requestRender()
        }.onFailure(::fail)
    }

    private fun fail(failure: Throwable) {
        Log.e(TAG, "Radar renderer failure", failure)
        rendererFailed = true
        runCatching { renderer?.close() }
            .onFailure { Log.e(TAG, "Radar renderer cleanup failed", it) }
        renderer = null
        val message = failure.message?.take(160) ?: failure.javaClass.simpleName
        lifecycleGate.failed(message)
        val fallback = if (session?.legacyArchive != null) {
            " A static current frame is shown without motion interpolation."
        } else ""
        report(RadarRendererStatus.Error("Radar display failed: $message$fallback"))
    }

    private fun report(status: RadarRendererStatus) {
        post { if (!disposed) reportStatus(status) }
    }

    private class Renderer(
        surfaceTexture: SurfaceTexture,
        private var width: Int,
        private var height: Int,
        private val session: RadarSession,
        indices: ShortArray,
        private val stageGuard: RadarRendererStageGuard,
        compatibilityMode: Boolean,
    ) : AutoCloseable {
        private val windowSurface = Surface(surfaceTexture)
        private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var context: EGLContext = EGL14.EGL_NO_CONTEXT
        private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
        private val legacyArchive = session.legacyArchive
        private val rasterPolicy = RadarRasterRenderPolicy.forSession(session)
        private val textures = IdentityHashMap<Bitmap, Int>()
        private val velocityTextures = IdentityHashMap<RadarVelocityField, Int>()
        private data class LegacyPairTextures(val radar: Int, val velocity: Int)
        private val legacyPairs = HashMap<Int, LegacyPairTextures>()
        private val legacyResidency = LegacyPairResidency(if (compatibilityMode) 2 else MAX_RESIDENT_PAIRS)
        private val legacyLayout = rasterPolicy.legacyTextureLayout()
        private var lutTexture = 0
        private var neutralVelocityTexture = 0
        private var program = 0
        private var maxTextureSize = 0
        private var windowSurfaceReleased = false
        private val indexBuffer: ShortBuffer = ByteBuffer.allocateDirect(indices.size * 2)
            .order(ByteOrder.nativeOrder()).asShortBuffer().apply { put(indices); position(0) }
        private val indexCount = indices.size
        private var vertexBuffer: FloatBuffer? = null
        private val markerBuffer: FloatBuffer = ByteBuffer.allocateDirect(16)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        private val deleteTextureId = IntArray(1)

        init {
            try {
                if (legacyArchive != null) stageGuard.mark(RadarRendererStage.EGL_CREATE)
                createEgl(surfaceTexture)
                if (legacyArchive != null) stageGuard.mark(RadarRendererStage.PROGRAM_LINK)
                val preferredPrecision = queryShaderPrecision()
                program = try {
                    createRadarProgram(preferredPrecision)
                } catch (failure: RadarShaderCompileException) {
                    if (preferredPrecision != RadarShaderPrecision.HIGH) throw failure
                    Log.w(TAG, "High-precision radar shader rejected; using GLES2 mediump fallback", failure)
                    createRadarProgram(RadarShaderPrecision.MEDIUM)
                }
                listOfNotNull(session.regional, session.detail).flatMap { it.frames }.forEach { frame ->
                    textures[frame.bitmap] = uploadBitmap(frame.bitmap)
                    frame.velocityBitmap?.let { textures[it] = uploadVelocity(it) }
                }
                session.denseVelocity.values.flatMap { it.pairFields.filterNotNull() + listOfNotNull(it.futureField) }
                    .forEach { velocityTextures[it] = uploadVelocityField(it) }
                lutTexture = uploadLut()
                neutralVelocityTexture = uploadNeutralVelocity()
                checkGl("uploading radar textures")
                Log.i(TAG, "Uploaded ${textures.size} raster and ${velocityTextures.size} dense velocity textures")
            } catch (failure: Throwable) {
                close()
                throw failure
            }
        }

        fun resize(width: Int, height: Int) {
            this.width = width
            this.height = height
        }

        fun draw(
            vertices: FloatArray,
            marker: FloatArray?,
            bracket: RadarTimelineBracket,
            tier: RadarResolutionTier,
        ): Boolean {
            if (surface == EGL14.EGL_NO_SURFACE || width <= 0 || height <= 0) return false
            check(EGL14.eglMakeCurrent(display, surface, surface, context)) { eglFailure("eglMakeCurrent") }
            GLES20.glViewport(0, 0, width, height)
            GLES20.glClearColor(0f, 0f, 0f, 0f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glUseProgram(program)

            val archive = legacyArchive
            val tierFrames = session.tier(tier)
            if (archive == null && tierFrames == null) return false
            val firstIndex = bracket.firstIndex.coerceIn(0, (archive?.frames?.lastIndex ?: tierFrames!!.frames.lastIndex))
            val secondIndex = bracket.secondIndex.coerceIn(0, (archive?.frames?.lastIndex ?: tierFrames!!.frames.lastIndex))
            val first = tierFrames?.frames?.get(firstIndex)
            val second = tierFrames?.frames?.get(secondIndex)
            val requestedPairs = archive?.let {
                LegacyFrameRequestPlan.forDraw(firstIndex, secondIndex).associateWith(::ensureLegacyPair)
            }.orEmpty()
            val firstPair = requestedPairs[firstIndex]
            val secondPair = requestedPairs[secondIndex]
            bindTexture(
                0,
                firstPair?.radar
                    ?: textures[requireNotNull(first).bitmap]
                    ?: return false,
                "texRadarFrom",
            )
            bindTexture(
                1,
                secondPair?.radar
                    ?: textures[requireNotNull(second).bitmap]
                    ?: return false,
                "texRadarTo",
            )
            val legacyVelocity = first?.velocityBitmap ?: second?.velocityBitmap
            val denseSet = session.velocity(tier)
            val denseVelocity = if (bracket.isForecast) {
                denseSet?.futureField
            } else {
                denseSet?.pairFields?.getOrNull(bracket.firstIndex)
            }
            bindTexture(
                2,
                firstPair?.velocity
                    ?: legacyVelocity?.let(textures::get)
                    ?: denseVelocity?.let(velocityTextures::get)
                    ?: neutralVelocityTexture,
                "texVelocity",
            )
            bindTexture(3, lutTexture, "texColorLut")

            GLES20.glUniform1f(uniform("time"), bracket.fraction.toFloat().coerceIn(0f, 1f))
            GLES20.glUniform1f(
                uniform("velocityScale"),
                rasterPolicy.velocityScale(denseVelocity),
            )
            val (sourceWidth, sourceHeight) = rasterPolicy.sourceDimensions(
                first?.bitmap?.let { it.width to it.height },
            )
            GLES20.glUniform2f(
                uniform("pixelSize"),
                1f / sourceWidth,
                1f / sourceHeight,
            )
            // The retired texture surface used 90% overall opacity; keep alpha
            // in the LUT and apply the surface-level factor only once here.
            GLES20.glUniform1f(uniform("layerAlpha"), 0.90f)
            GLES20.glUniform1f(uniform("markerMode"), 0f)
            GLES20.glUniform1f(uniform("coloredSource"), rasterPolicy.coloredSource)
            val textureLayout = legacyLayout
            GLES20.glUniform2f(
                uniform("textureOffset"),
                textureLayout?.offsetX ?: 0f,
                textureLayout?.offsetY ?: 0f,
            )
            GLES20.glUniform2f(
                uniform("textureScale"),
                textureLayout?.scaleX ?: 1f,
                textureLayout?.scaleY ?: 1f,
            )
            val futureFactor = if (bracket.isForecast && denseVelocity != null) {
                bracket.forecastMinutes * 60.0 / denseVelocity.sourceIntervalSeconds
            } else 0.0
            GLES20.glUniform1f(uniform("futureFactor"), futureFactor.toFloat())

            val vertexBuffer = this.vertexBuffer?.takeIf { it.capacity() >= vertices.size }
                ?: ByteBuffer.allocateDirect(vertices.size * 4).order(ByteOrder.nativeOrder())
                    .asFloatBuffer().also { vertexBuffer = it }
            vertexBuffer.clear()
            vertexBuffer.put(vertices).position(0)
            val position = GLES20.glGetAttribLocation(program, "position")
            val uv = GLES20.glGetAttribLocation(program, "textureCoordinate")
            check(position >= 0 && uv >= 0) { "Radar shader attributes are unavailable" }
            vertexBuffer.position(0)
            GLES20.glEnableVertexAttribArray(position)
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
            vertexBuffer.position(2)
            GLES20.glEnableVertexAttribArray(uv)
            GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
            indexBuffer.position(0)
            if (archive != null) stageGuard.mark(RadarRendererStage.FIRST_DRAW)
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, indexBuffer)
            marker?.let { location ->
                markerBuffer.clear()
                markerBuffer.put(location[0]); markerBuffer.put(location[1]); markerBuffer.put(0f); markerBuffer.put(0f)
                markerBuffer.position(0)
                GLES20.glUniform1f(uniform("markerMode"), 1f)
                markerBuffer.position(0)
                GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, markerBuffer)
                markerBuffer.position(2)
                GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 16, markerBuffer)
                GLES20.glDrawArrays(GLES20.GL_POINTS, 0, 1)
            }
            GLES20.glDisableVertexAttribArray(position)
            GLES20.glDisableVertexAttribArray(uv)
            checkGl("drawing radar frame")
            if (archive != null) stageGuard.mark(RadarRendererStage.SWAP)
            check(EGL14.eglSwapBuffers(display, surface)) { eglFailure("eglSwapBuffers") }
            return true
        }

        override fun close() {
            if (display != EGL14.EGL_NO_DISPLAY) {
                if (surface != EGL14.EGL_NO_SURFACE && context != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglMakeCurrent(display, surface, surface, context)
                    textures.values.forEach(::safeDeleteTexture)
                    velocityTextures.values.forEach(::safeDeleteTexture)
                    safeDeleteTexture(lutTexture)
                    safeDeleteTexture(neutralVelocityTexture)
                    legacyPairs.values.forEach { pair ->
                        safeDeleteTexture(pair.radar)
                        if (pair.velocity != neutralVelocityTexture) safeDeleteTexture(pair.velocity)
                    }
                    if (program != 0) GLES20.glDeleteProgram(program)
                }
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
                surface = EGL14.EGL_NO_SURFACE
                context = EGL14.EGL_NO_CONTEXT
                display = EGL14.EGL_NO_DISPLAY
            }
            if (!windowSurfaceReleased) {
                windowSurface.release()
                windowSurfaceReleased = true
            }
        }

        private fun createEgl(surfaceTexture: SurfaceTexture) {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY)
            check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0))
            val attributes = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0)
            context = EGL14.eglCreateContext(
                display,
                requireNotNull(configs[0]),
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
                0,
            )
            check(context != EGL14.EGL_NO_CONTEXT)
            surface = EGL14.eglCreateWindowSurface(
                display,
                requireNotNull(configs[0]),
                windowSurface,
                intArrayOf(EGL14.EGL_NONE),
                0,
            )
            check(surface != EGL14.EGL_NO_SURFACE)
            check(EGL14.eglMakeCurrent(display, surface, surface, context))
            val textureSize = IntArray(1)
            val textureUnits = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, textureSize, 0)
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_IMAGE_UNITS, textureUnits, 0)
            maxTextureSize = textureSize[0]
            check(textureUnits[0] >= 4) { "Radar requires four fragment texture units" }
            legacyLayout?.let { layout ->
                check(layout.backingWidth <= maxTextureSize && layout.backingHeight <= maxTextureSize) {
                    "Regional texture ${layout.backingWidth}x${layout.backingHeight} exceeds GL_MAX_TEXTURE_SIZE"
                }
            }
            Log.i(
                TAG,
                "GL vendor=${GLES20.glGetString(GLES20.GL_VENDOR)} renderer=${GLES20.glGetString(GLES20.GL_RENDERER)} " +
                    "version=${GLES20.glGetString(GLES20.GL_VERSION)} maxTexture=$maxTextureSize " +
                    "units=${textureUnits[0]} legacyLayout=$legacyLayout",
            )
            checkGl("querying GL capabilities")
            Log.i(TAG, "EGL ${width}x$height context created")
        }

        private fun uploadBitmap(bitmap: Bitmap): Int {
            val texture = newTexture()
            return try {
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
                checkGl("uploading radar texture")
                texture
            } catch (failure: Throwable) {
                GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
                throw failure
            }
        }

        private fun uploadVelocity(bitmap: Bitmap): Int {
            val texture = newTexture()
            return try {
                GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE_ALPHA,
                    bitmap.width, bitmap.height, 0, GLES20.GL_LUMINANCE_ALPHA,
                    GLES20.GL_UNSIGNED_BYTE, null,
                )
                checkGl("allocating velocity texture")
                // Convert one row at a time: peak conversion storage is O(width), not O(width*height).
                val pixels = IntArray(bitmap.width)
                val channels = ByteBuffer.allocateDirect(bitmap.width * 2)
                for (row in 0 until bitmap.height) {
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, row, bitmap.width, 1)
                    channels.clear()
                    pixels.forEach { color ->
                        channels.put(((color shr 16) and 0xff).toByte())
                        channels.put(((color shr 8) and 0xff).toByte())
                    }
                    channels.position(0)
                    GLES20.glTexSubImage2D(
                        GLES20.GL_TEXTURE_2D, 0, 0, row, bitmap.width, 1,
                        GLES20.GL_LUMINANCE_ALPHA, GLES20.GL_UNSIGNED_BYTE, channels,
                    )
                    checkGl("uploading velocity texture row")
                }
                texture
            } catch (failure: Throwable) {
                GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
                throw failure
            }
        }

        private fun ensureLegacyPair(index: Int): LegacyPairTextures {
            val archive = requireNotNull(legacyArchive)
            val frame = archive.frames[index]
            legacyPairs[frame.id]?.let { pair ->
                legacyResidency.beforeAccess(frame.id)
                return pair
            }
            legacyResidency.beforeAccess(frame.id)?.let { evictedId ->
                legacyPairs.remove(evictedId)?.let { evicted ->
                    // Delete before replacement allocation so the hard pair budget is never exceeded.
                    deleteTexture(evicted.radar)
                    if (evicted.velocity != neutralVelocityTexture) deleteTexture(evicted.velocity)
                }
            }
            var radarTexture = 0
            var velocityTexture = neutralVelocityTexture
            try {
                stageGuard.mark(RadarRendererStage.RADAR_UPLOAD)
                val radarBitmap = decodeLegacyBitmap(frame.radarJpeg)
                radarTexture = consumeAndRelease(radarBitmap, Bitmap::recycle, ::uploadLegacyRadar)
                check(radarBitmap.isRecycled) { "Uploaded regional radar bitmap was not released" }
                frame.velocityJpeg?.let { bytes ->
                    stageGuard.mark(RadarRendererStage.VELOCITY_UPLOAD)
                    val velocityBitmap = decodeLegacyBitmap(bytes)
                    velocityTexture = consumeAndRelease(velocityBitmap, Bitmap::recycle, ::uploadLegacyVelocity)
                    check(velocityBitmap.isRecycled) { "Uploaded regional velocity bitmap was not released" }
                }
                return LegacyPairTextures(radarTexture, velocityTexture).also { legacyPairs[frame.id] = it }
            } catch (failure: Throwable) {
                deleteTexture(radarTexture)
                if (velocityTexture != neutralVelocityTexture) deleteTexture(velocityTexture)
                legacyResidency.remove(frame.id)
                throw failure
            }
        }

        private fun uploadLegacyRadar(bitmap: Bitmap): Int = uploadLegacyRows(bitmap, velocity = false)

        private fun uploadLegacyVelocity(bitmap: Bitmap): Int = uploadLegacyRows(bitmap, velocity = true)

        private fun uploadLegacyRows(bitmap: Bitmap, velocity: Boolean): Int {
            val layout = requireNotNull(legacyLayout)
            require(layout.backingWidth <= maxTextureSize && layout.backingHeight <= maxTextureSize) {
                "Regional texture exceeds GL_MAX_TEXTURE_SIZE"
            }
            val format = if (velocity) GLES20.GL_LUMINANCE_ALPHA else GLES20.GL_LUMINANCE
            val bytesPerPixel = if (velocity) 2 else 1
            val texture = newTexture()
            return try {
                // Regional widths (e.g. 583) are not four-byte aligned. Multi-row strips
                // are tightly packed, so GLES must not infer padded row strides.
                GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, LegacyTextureStripPlan.UNPACK_ALIGNMENT)
                checkGl("configuring compact regional row alignment")
                GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D,
                    0,
                    format,
                    layout.backingWidth,
                    layout.backingHeight,
                    0,
                    format,
                    GLES20.GL_UNSIGNED_BYTE,
                    null,
                )
                checkGl("allocating compact regional texture")
                // A small strip avoids hundreds of synchronous JNI/driver uploads at every
                // five-minute frame boundary, without allocating a raster-sized buffer.
                val stripRows = LegacyTextureStripPlan.ROWS
                val pixels = IntArray(bitmap.width * stripRows)
                val channels = ByteBuffer.allocateDirect(bitmap.width * stripRows * bytesPerPixel)
                for (row in 0 until bitmap.height step stripRows) {
                    val rows = LegacyTextureStripPlan.rowsAt(row, bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, row, bitmap.width, rows)
                    LegacyTextureRowCodec.encode(pixels, velocity, channels, bitmap.width * rows)
                    GLES20.glTexSubImage2D(
                        GLES20.GL_TEXTURE_2D,
                        0,
                        0,
                        row,
                        bitmap.width,
                        rows,
                        format,
                        GLES20.GL_UNSIGNED_BYTE,
                        channels,
                    )
                    checkGl("uploading compact regional texture strip")
                }
                texture
            } catch (failure: Throwable) {
                deleteTexture(texture)
                throw failure
            }
        }

        private fun decodeLegacyBitmap(bytes: ByteArray): Bitmap {
            val region = requireNotNull(session.region)
            val bitmap = try {
                android.graphics.BitmapFactory.decodeByteArray(
                    bytes,
                    0,
                    bytes.size,
                    android.graphics.BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    },
                )
            } catch (oom: OutOfMemoryError) {
                throw RadarGlAllocationException("Regional JPEG decode ran out of memory", oom)
            } ?: throw RadarGlAllocationException("Regional JPEG decode failed")
            if (bitmap.width != region.rasterWidth || bitmap.height != region.rasterHeight) {
                bitmap.recycle()
                throw RadarGlAllocationException("Regional JPEG dimensions changed")
            }
            return bitmap
        }

        private fun deleteTexture(texture: Int) {
            if (texture == 0) return
            deleteTextureId[0] = texture
            GLES20.glDeleteTextures(1, deleteTextureId, 0)
            if (legacyArchive != null) checkGl("deleting regional texture")
        }

        private fun safeDeleteTexture(texture: Int) {
            runCatching { deleteTexture(texture) }
                .onFailure { Log.e(TAG, "GL texture cleanup failed", it) }
        }

        private fun uploadVelocityField(field: RadarVelocityField): Int {
            val channels = ByteBuffer.allocateDirect(field.channels.size).apply {
                put(field.channels)
                position(0)
            }
            val texture = newTexture()
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE_ALPHA,
                field.width, field.height, 0, GLES20.GL_LUMINANCE_ALPHA,
                GLES20.GL_UNSIGNED_BYTE, channels,
            )
            return texture
        }

        private fun uploadNeutralVelocity(): Int {
            val texture = newTexture()
            val pixel = ByteBuffer.allocateDirect(2).put(0, 128.toByte()).put(1, 128.toByte())
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE_ALPHA,
                1, 1, 0, GLES20.GL_LUMINANCE_ALPHA, GLES20.GL_UNSIGNED_BYTE, pixel,
            )
            checkGl("uploading neutral velocity texture")
            return texture
        }

        private fun uploadLut(): Int {
            val bytes = ByteBuffer.allocateDirect(256 * 2 * 4)
            repeat(2) { type ->
                repeat(256) { intensity ->
                    val color = if (type == 0) RainAlarmPalette.colorAt(intensity / 255f)
                        else SnowAlarmPalette.colorAt(intensity / 255f)
                    bytes.put(((color shr 16) and 0xff).toByte())
                    bytes.put(((color shr 8) and 0xff).toByte())
                    bytes.put((color and 0xff).toByte())
                    bytes.put(((color ushr 24) and 0xff).toByte())
                }
            }
            bytes.position(0)
            val texture = newTexture()
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                256, 2, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bytes,
            )
            checkGl("uploading radar color LUT")
            return texture
        }

        private fun newTexture(): Int {
            val id = IntArray(1)
            GLES20.glGenTextures(1, id, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            checkGl("creating radar texture")
            return id[0]
        }

        private fun bindTexture(unit: Int, texture: Int, name: String) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES20.glUniform1i(uniform(name), unit)
        }

        private fun uniform(name: String): Int = GLES20.glGetUniformLocation(program, name)

        private fun checkGl(operation: String) {
            val error = GLES20.glGetError()
            if (error == GLES20.GL_OUT_OF_MEMORY) {
                throw RadarGlAllocationException("$operation ran out of graphics memory")
            }
            check(error == GLES20.GL_NO_ERROR) { "$operation failed with GL error 0x${error.toString(16)}" }
        }

        private fun eglFailure(operation: String): String =
            "$operation failed with EGL error 0x${EGL14.eglGetError().toString(16)}"

        private fun createProgram(vertex: String, fragment: String): Int {
            fun compile(type: Int, source: String): Int {
                val shader = GLES20.glCreateShader(type)
                GLES20.glShaderSource(shader, source)
                GLES20.glCompileShader(shader)
                val status = IntArray(1)
                GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
                if (status[0] == 0) {
                    val kind = if (type == GLES20.GL_VERTEX_SHADER) "vertex" else "fragment"
                    val log = GLES20.glGetShaderInfoLog(shader)
                    GLES20.glDeleteShader(shader)
                    throw RadarShaderCompileException("Radar $kind shader failed: $log")
                }
                return shader
            }
            val vertexShader = compile(GLES20.GL_VERTEX_SHADER, vertex)
            val fragmentShader = try {
                compile(GLES20.GL_FRAGMENT_SHADER, fragment)
            } catch (failure: Throwable) {
                GLES20.glDeleteShader(vertexShader)
                throw failure
            }
            val result = GLES20.glCreateProgram()
            GLES20.glAttachShader(result, vertexShader)
            GLES20.glAttachShader(result, fragmentShader)
            GLES20.glLinkProgram(result)
            val status = IntArray(1)
            GLES20.glGetProgramiv(result, GLES20.GL_LINK_STATUS, status, 0)
            GLES20.glDeleteShader(vertexShader)
            GLES20.glDeleteShader(fragmentShader)
            if (status[0] == 0) {
                val log = GLES20.glGetProgramInfoLog(result)
                GLES20.glDeleteProgram(result)
                throw RadarShaderCompileException("Radar shader link failed: $log")
            }
            return result
        }

        private fun queryShaderPrecision(): RadarShaderPrecision {
            val range = IntArray(2)
            val bits = IntArray(1)
            GLES20.glGetShaderPrecisionFormat(GLES20.GL_FRAGMENT_SHADER, GLES20.GL_HIGH_FLOAT, range, 0, bits, 0)
            val error = GLES20.glGetError()
            if (error == GLES20.GL_OUT_OF_MEMORY) {
                throw RadarGlAllocationException("Querying shader precision ran out of graphics memory")
            }
            val selected = RadarShaderPrecisionPolicy.select(if (error == GLES20.GL_NO_ERROR) bits[0] else 0)
            Log.i(TAG, "Fragment highp range=${range.toList()} bits=${bits[0]} error=0x${error.toString(16)} selected=$selected")
            return selected
        }

        private fun createRadarProgram(precision: RadarShaderPrecision): Int {
            val vertex = RadarShaderSources.vertexFor(precision)
            val fragment = RadarShaderSources.fragmentFor(precision)
            RadarShaderContract.validateGles2(vertex, fragment).getOrThrow()
            return createProgram(vertex, fragment)
        }

        private class RadarShaderCompileException(message: String) : IllegalStateException(message)

        private class RadarGlAllocationException(message: String, cause: Throwable? = null) :
            IllegalStateException(message, cause)

        private companion object {
            const val MAX_RESIDENT_PAIRS = 3
        }

    }
}
