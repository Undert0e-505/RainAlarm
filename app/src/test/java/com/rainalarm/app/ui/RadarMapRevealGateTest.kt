package com.rainalarm.app.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadarMapRevealGateTest {
    @Test fun coverRemainsUntilAFrameAfterTheRequestedStyleLoads() {
        val gate = RadarMapRevealGate()
        assertTrue(gate.isCovered)
        assertFalse(gate.frameRendered()) // MapLibre's pre-style/default frame is not ready.
        gate.styleLoaded()
        assertTrue(gate.frameRendered())
        assertFalse(gate.isCovered)
        assertFalse(gate.frameRendered())
    }

    @Test fun themeChangeCoversAgainUntilItsOwnStyledFrame() {
        val gate = RadarMapRevealGate()
        gate.styleLoaded()
        assertTrue(gate.frameRendered())
        gate.styleRequested()
        assertTrue(gate.isCovered)
        assertFalse(gate.frameRendered())
        gate.styleLoaded()
        assertTrue(gate.frameRendered())
        assertFalse(gate.isCovered)
        assertEquals(0xFF111C24.toInt(), RadarMapAppearance.loadingBackgroundArgb(true))
        assertEquals(0xFFF3F5F4.toInt(), RadarMapAppearance.loadingBackgroundArgb(false))
    }

    @Test fun failureKeepsTheCoverButReportsItAndCanRecover() {
        val gate = RadarMapRevealGate()
        assertTrue(gate.markFailed())
        assertTrue(gate.isCovered)
        assertTrue(gate.hasFailed)
        assertFalse(gate.markFailed())
        gate.styleLoaded()
        assertTrue(gate.frameRendered()) // A late successful style can clear the error.
        assertFalse(gate.hasFailed)
        assertFalse(gate.isCovered)
    }

    @Test fun mapViewCoverAndListenersAreScopedToTheSessionInstance() {
        val source = listOf(
            File("src/main/java/com/rainalarm/app/ui/RadarImageMap.kt"),
            File("app/src/main/java/com/rainalarm/app/ui/RadarImageMap.kt"),
        ).first(File::isFile).readText()
        assertTrue(source.contains("key(session)"))
        assertTrue(source.contains("addView(mapCover"))
        assertTrue(source.indexOf("addView(mapCover") < source.indexOf("mapView.getMapAsync"))
        val mapAttach = requireNotNull(Regex("addView\\(\\s*mapView,").find(source)).range.first
        assertTrue(source.indexOf("mapView.addOnDidFinishRenderingFrameListener(renderedListener)") < mapAttach)
        assertTrue(source.indexOf("mapView.addOnDidFailLoadingMapListener(failedListener)") <
            source.indexOf("mapView.getMapAsync"))
        assertTrue(source.contains("mapRevealGate.styleRequested()"))
        assertTrue(source.contains("mapRevealGate.styleLoaded()"))
        assertTrue(source.contains("MapView.OnDidFinishRenderingFrameListener"))
        assertTrue(source.contains("mapRevealGate.frameRendered()"))
        assertTrue(source.contains("mapView.removeOnDidFinishRenderingFrameListener(renderedListener)"))
        assertTrue(source.contains("mapView.removeOnDidFailLoadingMapListener(failedListener)"))
        assertTrue(source.contains("mapRevealGate.markFailed()"))
    }
}
