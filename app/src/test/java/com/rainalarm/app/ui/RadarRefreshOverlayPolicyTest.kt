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
        val unavailable = requireNotNull(
            RadarRefreshOverlayPolicy.status(false, false, 0, 0, "offline"),
        )
        assertEquals("Radar unavailable", unavailable.label)
        assertEquals("Radar unavailable", unavailable.accessibilityLabel)
        assertNull(RadarRefreshOverlayPolicy.status(true, false, 0, 0, null))
        assertEquals("Radar loading 1/49",
            RadarRefreshOverlayPolicy.status(true, true, 1, 49, null)?.label)
        assertEquals("Radar loading",
            RadarRefreshOverlayPolicy.status(true, true, 0, 0, null)?.label)
        val failure = requireNotNull(RadarRefreshOverlayPolicy.status(true, false, 0, 0, "offline"))
        assertEquals("Radar unavailable", failure.label)
        assertEquals("Radar unavailable", failure.accessibilityLabel)
    }

    @Test fun initialLoadHasCompactUnknownAndKnownProgressLabels() {
        val unknown = RadarRefreshOverlayPolicy.initialLoading(0, 0)
        assertEquals("Radar loading", unknown.label)
        assertEquals("Radar loading", unknown.accessibilityLabel)
        val known = RadarRefreshOverlayPolicy.initialLoading(3, 49)
        assertEquals("Radar loading 3/49", known.label)
        assertEquals("Radar loading", known.accessibilityLabel)
        assertEquals("Radar loading 49/49",
            RadarRefreshOverlayPolicy.initialLoading(80, 49).label)
    }

    @Test fun feedbackIsOverlayInsideMapNotAConditionalColumnRow() {
        val screen = source()
        assertTrue(screen.contains("RadarPreparationStackPolicy.entries("))
        assertTrue(screen.contains("Modifier.align(Alignment.BottomEnd)"))
        assertTrue(screen.contains("RadarPreparationStackPolicy.bottomInsetDp.dp"))
        assertTrue(screen.contains("entries.forEach { status ->"))
        assertTrue(screen.contains("Modifier.align(Alignment.TopEnd).padding("))
        assertTrue(!screen.contains("layerDescription"))
        assertTrue(screen.contains(".semantics { contentDescription = status.accessibilityLabel }"))
        assertTrue(!screen.contains("if (session != null && (refreshing || error != null))"))
        assertTrue(screen.contains("val sessionReusable = session != null && displayedMapPlace != null"))
        assertTrue(screen.contains("showInteractiveMap -> RadarPlayer("))
        assertTrue(screen.contains("session = session?.takeIf { sessionReusable }"))
        assertTrue(screen.contains("else -> RadarLoadingShell("))
        assertTrue(screen.contains("Color(RadarMapAppearance.loadingBackgroundArgb(mapStyle))"))
        assertTrue(screen.contains("Modifier.align(Alignment.BottomEnd).padding("))
        assertTrue(screen.contains("IconButton(onClick = onRefresh)"))
        assertTrue(screen.contains("Reserve the player controls/ticks footprint"))
        assertTrue(screen.contains("RadarRefreshOverlayPolicy.initialLoading(refreshProgress.first"))
        assertTrue(!screen.contains("else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center)"))
    }
}
