package com.rainalarm.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EntryTransitionTest {
    @Test fun `retained destinations begin prepared and play their prepared generation once`() {
        val prepared = EntryTransitionPolicy.initial(active = false)
        assertEquals(EntryTransitionPhase.PREPARED, prepared.phase)

        val playing = EntryTransitionPolicy.requestPlay(prepared)
        assertEquals(prepared.generation, playing.generation)
        assertEquals(EntryTransitionPhase.PLAY_REQUESTED, playing.phase)
        assertSame(playing, EntryTransitionPolicy.requestPlay(playing))

        val settled = EntryTransitionPolicy.settle(playing, playing.generation)
        assertEquals(EntryTransitionPhase.SETTLED, settled.phase)
    }

    @Test fun `offscreen preparation precedes tap or swipe playback without resetting incoming page`() {
        val firstVisit = EntryTransitionPolicy.settle(
            EntryTransitionPolicy.initial(active = true),
            generation = 1,
        )
        val offscreen = EntryTransitionPolicy.prepare(firstVisit)
        assertEquals(EntryTransitionPhase.PREPARED, offscreen.phase)
        assertEquals(firstVisit.generation + 1, offscreen.generation)

        val tapOrSwipe = EntryTransitionPolicy.requestPlay(offscreen)
        assertEquals(offscreen.generation, tapOrSwipe.generation)
        assertEquals(EntryTransitionPhase.PLAY_REQUESTED, tapOrSwipe.phase)
    }

    @Test fun `stale completion cannot settle a newer rapid re-entry`() {
        val first = EntryTransitionPolicy.initial(active = true)
        val rearmed = EntryTransitionPolicy.prepare(first)
        val second = EntryTransitionPolicy.requestPlay(rearmed)

        assertSame(second, EntryTransitionPolicy.settle(second, first.generation))
        assertEquals(
            EntryTransitionPhase.SETTLED,
            EntryTransitionPolicy.settle(second, second.generation).phase,
        )
    }

    @Test fun `native frame handshake accepts a matching retained-style frame after prepare`() {
        val handshake = RadarEntryFrameHandshake()
        handshake.prepare(8)
        assertNull(handshake.frameRendered(fully = true, cameraMatchesStart = true))
        handshake.frameStarted()
        assertNull(handshake.frameRendered(fully = false, cameraMatchesStart = true))
        handshake.frameStarted()
        assertEquals(8, handshake.frameRendered(
            fully = false,
            cameraMatchesStart = true,
            retainedStyleWasCoherent = true,
        ))
    }

    @Test fun `new style still requires a complete matching frame`() {
        val handshake = RadarEntryFrameHandshake()
        handshake.prepare(8)
        handshake.frameStarted()
        assertNull(handshake.frameRendered(fully = false, cameraMatchesStart = true))
        handshake.frameStarted()
        assertNull(handshake.frameRendered(fully = true, cameraMatchesStart = false))
        handshake.frameStarted()
        assertEquals(8, handshake.frameRendered(fully = true, cameraMatchesStart = true))
        handshake.frameStarted()
        assertNull(handshake.frameRendered(fully = true, cameraMatchesStart = true))
    }

    @Test fun `slow retained frame can animate after the former readiness window`() {
        val handshake = RadarEntryFrameHandshake()
        val gate = RadarNativePresentationGate()
        gate.prepare(12)

        // No wall-clock deadline participates in readiness. Even if a slow native renderer takes
        // longer than the former 750 ms window, its first real matching frame remains eligible.
        handshake.prepare(12)
        repeat(4) {
            handshake.frameStarted()
            assertNull(handshake.frameRendered(
                fully = false,
                cameraMatchesStart = false,
                retainedStyleWasCoherent = true,
            ))
        }
        handshake.frameStarted()
        assertEquals(12, handshake.frameRendered(
            fully = false,
            cameraMatchesStart = true,
            retainedStyleWasCoherent = true,
        ))
        assertTrue(gate.reveal(12))
        assertFalse(gate.isHidden)
    }

    @Test fun `native presentation gate rejects stale reveal and never re-hides revealed generation`() {
        val gate = RadarNativePresentationGate()
        assertTrue(gate.prepare(3))
        assertTrue(gate.isHiddenFor(3))
        assertTrue(gate.prepare(4))
        assertFalse(gate.reveal(3))
        assertTrue(gate.isHiddenFor(4))
        assertTrue(gate.reveal(4))
        assertFalse(gate.isHidden)
        assertFalse(gate.prepare(4))
    }

    @Test fun `superseded native frame generation cannot acknowledge the newer entry`() {
        val handshake = RadarEntryFrameHandshake()
        handshake.prepare(3)
        handshake.frameStarted()
        handshake.prepare(4)
        assertNull(handshake.frameRendered(fully = true, cameraMatchesStart = true))
        handshake.frameStarted()
        assertEquals(4, handshake.frameRendered(fully = true, cameraMatchesStart = true))
        handshake.cancel(4)
        handshake.frameStarted()
        assertNull(handshake.frameRendered(fully = true, cameraMatchesStart = true))
    }

    @Test fun `native frame camera match keeps panned center and start zoom authoritative`() {
        val pannedTarget = RadarCameraTarget(52.1432, -1.7211, 11.75)
        val start = pannedTarget.copy(zoom = RadarEntryFocusPolicy.startZoom(pannedTarget.zoom))
        assertTrue(RadarEntryFramePolicy.cameraMatchesStart(start, start))
        assertFalse(RadarEntryFramePolicy.cameraMatchesStart(pannedTarget, start))
        assertFalse(RadarEntryFramePolicy.cameraMatchesStart(
            start.copy(latitude = start.latitude + 0.001),
            start,
        ))
    }

    @Test fun `navigation and screens wire prepare before reveal and reduced motion fail open`() {
        val main = source("app/src/main/java/com/rainalarm/app/MainActivity.kt")
        val now = source("app/src/main/java/com/rainalarm/app/ui/NowScreen.kt")
        val radar = source("app/src/main/java/com/rainalarm/app/ui/RadarScreen.kt")
        val map = source("app/src/main/java/com/rainalarm/app/ui/RadarImageMap.kt")

        assertTrue(main.contains("prepareEntry(animatedPage)"))
        assertTrue(main.contains("requestEntryPlayback(animatedPage)"))
        assertTrue(main.contains("withFrameNanos { }"))
        assertTrue(now.contains("EntryTransitionPhase.PREPARED -> entryProgress.snapTo(0f)"))
        assertTrue(radar.contains("nativeEntryStart?.takeIf"))
        assertTrue(map.contains("RadarEntryFramePolicy.PLAY_READINESS_FAILURE_TIMEOUT_MILLIS"))
        assertTrue(map.contains("currentEntryAnimationStart(generation, false)"))
        assertTrue(map.contains("withFrameNanos { }"))
        assertTrue(map.contains("activeRadarSlot?.setVisible(false)"))
        assertTrue(map.contains("translationZ = density * 2f"))
    }

    private fun source(path: String): String = sequenceOf(File(path), File("../$path"))
        .first(File::isFile).readText()
}
