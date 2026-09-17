package com.rainalarm.app.ui

import com.rainalarm.app.domain.RainAlarmPalette
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadarRendererSafetyTest {
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
    fun `interrupted renderer stage selects compatibility and clears after success`() {
        class FakeStore(var value: String? = null) : RadarRendererStageStore {
            override fun read(): String? = value
            override fun write(value: String?) { this.value = value }
        }
        RadarRendererStage.entries.forEach { stage ->
            val store = FakeStore(stage.name)
            val guard = RadarRendererStageGuard(store)
            assertTrue(guard.compatibilityMode)
            assertEquals(stage, guard.interruptedStage)
            guard.clear()
            assertNull(store.value)
        }
        assertFalse(RadarRendererStageGuard(FakeStore()).compatibilityMode)
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
