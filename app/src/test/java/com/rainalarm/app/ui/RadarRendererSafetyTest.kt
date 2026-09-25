package com.rainalarm.app.ui

import com.rainalarm.app.data.RegionalRadarAreas
import com.rainalarm.app.domain.GeoPoint
import com.rainalarm.app.domain.GeoQuad
import com.rainalarm.app.domain.RainAlarmPalette
import com.rainalarm.app.domain.RadarLinearSampling
import com.rainalarm.app.domain.RadarVelocityField
import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadarRendererSafetyTest {
    @Test
    fun `logical OPERA region never selects legacy Meteo raster behavior`() {
        val uk = RegionalRadarAreas.all.single { it.id == "uk" }
        val opera = RadarRasterRenderPolicy.resolve(uk, legacyArchivePresent = false)
        val bounds = GeoQuad(
            GeoPoint(61.0, -12.0), GeoPoint(61.0, 6.0),
            GeoPoint(48.0, -12.0), GeoPoint(48.0, 6.0),
        )
        val velocity = RadarVelocityField(
            width = 1,
            height = 1,
            channels = byteArrayOf(128.toByte(), 128.toByte()),
            maxDisplacementPixels = 72f,
            sourceIntervalSeconds = 300,
            confidence = 0.8,
        )

        assertFalse(opera.legacyNativeRaster)
        assertEquals(1f, opera.coloredSource, 0f)
        assertNull(opera.legacyTextureLayout())
        assertEquals(72f, opera.velocityScale(velocity), 0f)
        assertEquals(512 to 384, opera.sourceDimensions(512 to 384))
        val mesh = opera.mesh(bounds)
        assertEquals(2, mesh.columns)
        assertEquals(bounds.topLeft.latitude, mesh.vertices.first().latitude, 0.0)
        assertEquals(bounds.topLeft.longitude, mesh.vertices.first().longitude, 0.0)
    }

    @Test
    fun `only an actual legacy archive selects native Meteo raster behavior`() {
        val uk = RegionalRadarAreas.all.single { it.id == "uk" }
        val meteo = RadarRasterRenderPolicy.resolve(uk, legacyArchivePresent = true)

        assertTrue(meteo.legacyNativeRaster)
        assertEquals(0f, meteo.coloredSource, 0f)
        assertEquals(uk.velocityScale, meteo.velocityScale(null), 0f)
        assertEquals(uk.rasterWidth to uk.rasterHeight, meteo.sourceDimensions(null))
        assertEquals(1024, requireNotNull(meteo.legacyTextureLayout()).backingWidth)
        val mesh = meteo.mesh(null)
        assertTrue(mesh.projectionAccurate)
        assertTrue(mesh.columns > 2)
    }

    @Test
    fun `pair cache never exceeds three pairs or six textures and evicts before allocation`() {
        val policy = LegacyPairResidency(3)
        val allocated = linkedSetOf<Int>()
        var peakTextures = 0
        for (frame in 0 until 12) {
            val evicted = policy.beforeAccess(frame)
            evicted?.let { allocated.remove(it) }
            assertTrue("eviction must happen before allocation", allocated.size < 3)
            allocated.add(frame)
            peakTextures = maxOf(peakTextures, allocated.size * 2)
            assertTrue(policy.snapshot().size <= 3)
        }
        assertEquals(6, peakTextures)
    }

    @Test
    fun `compatibility cache is limited to current and adjacent pair`() {
        val policy = LegacyPairResidency(2)
        assertNull(policy.beforeAccess(4))
        assertNull(policy.beforeAccess(5))
        assertEquals(4, policy.beforeAccess(6))
        assertEquals(setOf(5, 6), policy.snapshot())
    }

    @Test
    fun `first draw requests only current pair and interpolation requests at most two`() {
        assertEquals(listOf(7), LegacyFrameRequestPlan.forDraw(7, 7))
        assertEquals(listOf(7, 8), LegacyFrameRequestPlan.forDraw(7, 8))
    }

    @Test
    fun `npot regional layout uses exact power of two center mapping`() {
        val layout = LegacyTextureLayout.plan(583, 767, forcePowerOfTwo = true)
        assertEquals(1024, layout.backingWidth)
        assertEquals(1024, layout.backingHeight)
        assertEquals(0.5f / 1024f, layout.offsetX, 0f)
        assertEquals((583f - 0.5f) / 1024f, layout.offsetX + layout.scaleX, 0.000001f)
        assertEquals((767f - 0.5f) / 1024f, layout.offsetY + layout.scaleY, 0.000001f)
        val direct = LegacyTextureLayout.plan(512, 256, forcePowerOfTwo = false)
        assertEquals(512, direct.backingWidth)
        assertEquals(256, direct.backingHeight)
    }

    @Test
    fun `compact row conversion writes one and two channel contracts`() {
        val pixels = intArrayOf(0x00112233, 0x00A0B0C0)
        val radar = ByteBuffer.allocate(2)
        LegacyTextureRowCodec.encode(pixels, velocity = false, radar)
        assertEquals(listOf(0x11, 0xA0), List(radar.remaining()) { radar.get().toInt() and 0xff })
        val velocity = ByteBuffer.allocate(4)
        LegacyTextureRowCodec.encode(pixels, velocity = true, velocity)
        assertEquals(listOf(0x11, 0x22, 0xA0, 0xB0), List(velocity.remaining()) { velocity.get().toInt() and 0xff })
        val strip = ByteBuffer.allocate(4)
        LegacyTextureRowCodec.encode(intArrayOf(0x00112233, 0x00A0B0C0, 0), velocity = true, strip, pixelCount = 2)
        assertEquals(4, strip.remaining())
        assertEquals(listOf(0x11, 0x22, 0xA0, 0xB0), List(strip.remaining()) { strip.get().toInt() and 0xff })
    }

    @Test
    fun `non aligned regional width uploads in bounded tightly packed strips`() {
        assertEquals(1, LegacyTextureStripPlan.UNPACK_ALIGNMENT)
        val rows = (0 until 767 step LegacyTextureStripPlan.ROWS)
            .map { LegacyTextureStripPlan.rowsAt(it, 767) }
        assertEquals(767, rows.sum())
        assertTrue(rows.all { it in 1..16 })
        assertEquals(15, rows.last())
        assertTrue(583 * LegacyTextureStripPlan.ROWS * 2 < 20 * 1024)
    }

    @Test
    fun `regional upload preserves all eight intensity bits without spatial preprocessing`() {
        val intensities = intArrayOf(0, 1, 7, 8, 9, 62, 63, 64, 83, 93, 123, 153, 255)
        val pixels = intensities.map { (it shl 16) or 0x001122 }.toIntArray()
        val row = ByteBuffer.allocate(pixels.size)
        LegacyTextureRowCodec.encode(pixels, velocity = false, row)
        assertEquals(intensities.toList(), List(row.remaining()) { row.get().toInt() and 0xff })
        assertEquals(0, RainAlarmPalette.alphaAt(52f / 255f))
        assertTrue(RainAlarmPalette.alphaAt(63f / 255f) > 0)
    }

    @Test
    fun `regional footprint keeps grayscale rain row and established wet boundary`() {
        val shader = RadarShaderSources.fragment
        assertTrue(shader.contains("vec3(sample.r, sample.g * coloredSource"))
        assertTrue(shader.contains("mix(1.0, sample.b, coloredSource)"))
        assertTrue(shader.contains("paletteUv(sample.x, sample.y)"))
        assertEquals(0, RainAlarmPalette.alphaAt(52f / 255f))
        assertTrue(RainAlarmPalette.alphaAt(62f / 255f) < RainAlarmPalette.alphaAt(63f / 255f))
        assertEquals(63, RainAlarmPalette.FIRST_VISIBLE_RAW)
        assertEquals(1, RadarLinearSampling.GPU_TEXTURE_TAPS)
        val renderer = listOf(
            File("src/main/java/com/rainalarm/app/ui/LegacyRadarGlOverlayView.kt"),
            File("app/src/main/java/com/rainalarm/app/ui/LegacyRadarGlOverlayView.kt"),
        ).first(File::isFile).readText()
        assertTrue(renderer.contains("GLES20.glUniform1f(uniform(\"layerAlpha\"), 0.90f)"))
        assertTrue(renderer.contains(
            "GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)",
        ))
        assertTrue(renderer.contains(
            "GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)",
        ))
    }

    @Test
    fun `cross process interruption selects compatibility and clears after success`() {
        class FakeStore(var value: String? = null) : RadarRendererStageStore {
            override fun read(): String? = value
            override fun write(value: String?) { this.value = value }
        }
        RadarRendererStage.entries.forEach { stage ->
            val store = FakeStore()
            RadarRendererStageGuard(store, processToken = "old", ownerToken = "old-owner").mark(stage)
            val recovered = RadarRendererStageGuard(store, processToken = "new", ownerToken = "new-owner")
            assertTrue(recovered.compatibilityMode)
            assertEquals(stage, recovered.interruptedStage)
            recovered.mark(RadarRendererStage.MESH_READY)
            recovered.clear()
            assertNull(store.value)
        }
        assertFalse(RadarRendererStageGuard(
            FakeStore(), processToken = "process", ownerToken = "owner",
        ).compatibilityMode)
    }

    @Test
    fun `same process session handoff is expected and owners cannot clear each other`() {
        class FakeStore(var value: String? = null) : RadarRendererStageStore {
            override fun read(): String? = value
            override fun write(value: String?) { this.value = value }
        }
        val store = FakeStore()
        val outgoing = RadarRendererStageGuard(store, processToken = "same", ownerToken = "outgoing")
        outgoing.mark(RadarRendererStage.VELOCITY_UPLOAD)

        val incoming = RadarRendererStageGuard(store, processToken = "same", ownerToken = "incoming")
        assertFalse(incoming.compatibilityMode)
        assertNull(incoming.interruptedStage)
        incoming.mark(RadarRendererStage.MESH_READY)
        outgoing.finishExpected()
        assertEquals(
            RadarRendererStageRecord("same", "incoming", RadarRendererStage.MESH_READY).encode(),
            store.value,
        )
        incoming.finishExpected()
        assertNull(store.value)
    }

    @Test
    fun `legacy ambiguous breadcrumb is discarded without exposing internal stage wording`() {
        class FakeStore(var value: String? = RadarRendererStage.VELOCITY_UPLOAD.name) : RadarRendererStageStore {
            override fun read(): String? = value
            override fun write(value: String?) { this.value = value }
        }
        val store = FakeStore()
        val guard = RadarRendererStageGuard(store, processToken = "process", ownerToken = "owner")
        assertFalse(guard.compatibilityMode)
        assertNull(store.value)

        val status = RadarRendererRecoveryPolicy.status(RadarRendererStage.VELOCITY_UPLOAD)
        assertTrue(status is RadarRendererStatus.Compatibility)
        val message = (status as RadarRendererStatus.Compatibility).message
        assertFalse(message.contains("velocity", ignoreCase = true))
        assertFalse(message.contains("upload", ignoreCase = true))
    }

    @Test
    fun `upload and swap failures expose static legacy fallback`() {
        listOf(
            RadarRendererStage.RADAR_UPLOAD,
            RadarRendererStage.VELOCITY_UPLOAD,
            RadarRendererStage.SWAP,
        ).forEach { stage ->
            val status = RadarRendererStatus.Error("failed at ${stage.name}")
            assertTrue(RadarStaticFallbackPolicy.shouldShow(true, status))
            assertFalse(RadarStaticFallbackPolicy.shouldShow(false, status))
        }
    }
}
