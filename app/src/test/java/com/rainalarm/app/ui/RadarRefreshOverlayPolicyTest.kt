package com.rainalarm.app.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadarRefreshOverlayPolicyTest {
    private fun source(): String = listOf(
        File("src/main/java/com/rainalarm/app/ui/RadarScreen.kt"),
        File("app/src/main/java/com/rainalarm/app/ui/RadarScreen.kt"),
    ).first(File::isFile).readText()

    @Test fun onlyReusableSessionShowsProgressOrFailure() {
        assertNull(RadarRefreshOverlayPolicy.status(false, true, 1, 49, null))
        assertNull(RadarRefreshOverlayPolicy.status(false, false, 0, 0, "offline"))
        assertNull(RadarRefreshOverlayPolicy.status(true, false, 0, 0, null))
        assertEquals("Refreshing radar · 1/49",
            RadarRefreshOverlayPolicy.status(true, true, 1, 49, null)?.label)
        assertEquals("Refreshing radar…",
            RadarRefreshOverlayPolicy.status(true, true, 0, 0, null)?.label)
        val failure = requireNotNull(RadarRefreshOverlayPolicy.status(true, false, 0, 0, "offline"))
        assertEquals("Radar refresh failed · retry", failure.label)
        assertTrue(failure.accessibilityLabel.contains("offline"))
        assertTrue(failure.accessibilityLabel.contains("Refresh radar and map layer"))
    }

    @Test fun initialLoadHasCompactUnknownAndKnownProgressLabels() {
        val unknown = RadarRefreshOverlayPolicy.initialLoading(0, 0)
        assertEquals("Loading radar…", unknown.label)
        assertEquals("Loading radar", unknown.accessibilityLabel)
        val known = RadarRefreshOverlayPolicy.initialLoading(3, 49)
        assertEquals("Loading radar 3/49", known.label)
        assertEquals("Loading radar", known.accessibilityLabel)
        assertEquals("Loading radar 49/49",
            RadarRefreshOverlayPolicy.initialLoading(80, 49).label)
    }

    @Test fun feedbackIsOverlayInsideMapNotAConditionalColumnRow() {
        val screen = source()
        assertTrue(screen.contains("refreshOverlay?.let { status ->"))
        assertTrue(screen.contains("modifier = Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 32.dp)"))
        assertTrue(screen.contains("RadarLayerStatuses(enabledMapLayers, ancillaryStatuses, currentWeather, mapWidthDp"))
        assertTrue(screen.contains("Modifier.align(Alignment.TopEnd).padding("))
        assertTrue(!screen.contains("layerDescription"))
        assertTrue(screen.contains(".semantics { contentDescription = status.accessibilityLabel }"))
        assertTrue(!screen.contains("if (session != null && (refreshing || error != null))"))
        assertTrue(screen.contains("session != null && place != null && RadarLiveSessionPolicy.canReuse"))
        assertTrue(screen.contains("else -> RadarLoadingShell(mapStyle, progress)"))
        assertTrue(screen.contains("Color(RadarMapAppearance.loadingBackgroundArgb(mapStyle))"))
        assertTrue(screen.contains("Modifier.align(Alignment.BottomEnd).padding(8.dp)"))
        assertTrue(screen.contains("Reserve the player controls/ticks footprint"))
        assertTrue(!screen.contains("else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center)"))
    }
}
