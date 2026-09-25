package com.rainalarm.app.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadarMapNoticePolicyTest {
    private fun source(path: String): String = listOf(
        File(path),
        File("app/$path"),
    ).first(File::isFile).readText()

    @Test fun highestPriorityNoticeWinsDeterministically() {
        val chart = RadarMapNotice(RadarMapNoticeKind.CHART_TIME, "chart")
        val compatibility = RadarMapNotice(RadarMapNoticeKind.RENDERER_COMPATIBILITY, "compat")
        val style = RadarMapNotice(RadarMapNoticeKind.MAP_STYLE, "style")
        val renderer = RadarMapNotice(RadarMapNoticeKind.RENDERER_FAILURE, "renderer")
        assertEquals(renderer, RadarMapNoticePolicy.select(
            chart, style, renderer, compatibility,
        ))
        assertEquals(style, RadarMapNoticePolicy.select(chart, style, compatibility))
        assertNull(RadarMapNoticePolicy.select(null, RadarMapNotice(chart.kind, "")))
    }

    @Test fun compatibilityAndChartExpireWhilePersistentFailuresRemain() {
        for (kind in listOf(
            RadarMapNoticeKind.PROVIDER_FALLBACK,
            RadarMapNoticeKind.RENDERER_COMPATIBILITY,
            RadarMapNoticeKind.CHART_TIME,
        )) {
            assertEquals(4_500L, kind.lifetimeMillis)
            assertTrue(RadarMapNoticePolicy.isVisible(kind, 4_499L))
            assertFalse(RadarMapNoticePolicy.isVisible(kind, 4_500L))
        }
        assertTrue(RadarMapNoticePolicy.isVisible(RadarMapNoticeKind.MAP_STYLE, Long.MAX_VALUE))
    }

    @Test fun noticeRailReservesAttributionAndBottomRightStatus() {
        assertEquals(44, RadarMapNoticePolicy.attributionStartReservationDp)
        assertEquals(8, RadarMapNoticePolicy.bottomInsetDp)
        assertEquals(100, RadarMapNoticePolicy.maxWidthDp(360, bottomRightOccupied = true))
        assertEquals(240, RadarMapNoticePolicy.maxWidthDp(360, bottomRightOccupied = false))
        assertEquals(8, RadarMapNoticePolicy.noticeBottomInsetDp(360, 5))
        assertTrue(RadarMapNoticePolicy.noticeBottomInsetDp(240, 5) >
            RadarPreparationStackPolicy.bottomInsetDp)
        assertTrue(RadarMapNoticePolicy.sharesBottomRow(360, 5))
        assertFalse(RadarMapNoticePolicy.sharesBottomRow(240, 5))
        assertEquals(188, RadarMapNoticePolicy.maxWidthDp(
            240, RadarMapNoticePolicy.sharesBottomRow(240, 5),
        ))
    }

    @Test fun unresolvedCurrentUsesShellAndOnlyRetainsMatchingCurrentContent() {
        assertTrue(CurrentLocationPresentationPolicy.retainCurrentSession(true, false, true))
        assertFalse(CurrentLocationPresentationPolicy.retainCurrentSession(true, false, false))
        assertFalse(CurrentLocationPresentationPolicy.retainCurrentSession(false, false, true))
        assertEquals("Location loading", CurrentLocationPresentationPolicy.operationalStatus(
            true, false, true, null, null,
        )?.label)
        assertEquals("Location unavailable", CurrentLocationPresentationPolicy.operationalStatus(
            true, false, false, "Precise location is off", null,
        )?.label)
        assertNull(CurrentLocationPresentationPolicy.operationalStatus(
            false, false, true, null, null,
        ))
        assertTrue(CurrentLocationPresentationPolicy.retainCurrentForecast(
            true, "current-location:51.0:-0.1|provider",
        ))
        assertFalse(CurrentLocationPresentationPolicy.retainCurrentForecast(
            true, "saved:51.0:-0.1|provider",
        ))
        assertTrue(RadarBaseMapPresentationPolicy.showInteractiveMap(true, false))
        assertTrue(RadarBaseMapPresentationPolicy.showInteractiveMap(false, true))
        assertFalse(RadarBaseMapPresentationPolicy.showInteractiveMap(false, false))
    }

    @Test fun radarAndNowFailuresKeepNormalStructuresAndCompactRetryPaths() {
        val radar = source("src/main/java/com/rainalarm/app/ui/RadarScreen.kt")
        val now = source("src/main/java/com/rainalarm/app/ui/NowScreen.kt")
        val main = source("src/main/java/com/rainalarm/app/MainActivity.kt")

        assertFalse(radar.contains("error = \"Current location unavailable"))
        assertFalse(main.contains("ForecastUiState.Error(\"Current location unavailable"))
        assertFalse(radar.contains("Text(\"Radar unavailable\""))
        assertFalse(radar.contains("Radar display unavailable"))
        assertFalse(radar.contains("Text(\"Try again\")"))
        assertFalse(now.contains("NowPlaceholder(\"Radar unavailable\""))
        assertTrue(radar.contains("else -> RadarLoadingShell("))
        assertTrue(radar.contains("showInteractiveMap -> RadarPlayer("))
        assertFalse(radar.contains("radarFailureNotice"))
        assertTrue(radar.contains("RadarPreparationStackPolicy.entries("))
        assertTrue(radar.contains("RadarMapNoticeKind.RENDERER_FAILURE"))
        assertTrue(radar.contains("modifier = Modifier.align(Alignment.BottomStart)"))
        assertTrue(radar.contains("start = RadarMapNoticePolicy.attributionStartReservationDp.dp"))
        assertTrue(radar.contains("IconButton(onClick = onRefresh)"))
        assertTrue(radar.contains("locationState is LocationUiState.Locating"))
        assertTrue(now.contains("WeatherDataStatusPolicy.unavailable(WeatherDataKind.RADAR)"))
        assertTrue(now.contains("WeatherDataStatusPolicy.loading(WeatherDataKind.LOCATION)"))
        assertFalse(radar.contains("RadarMapNoticeKind.LOCATION"))
        assertTrue(radar.contains("locationStatus = locationOperationalStatus"))
        assertTrue(main.contains("CurrentLocationPresentationPolicy.retainCurrentForecast"))
    }

    @Test fun nonFatalNoticesAreNeverCentredOverTheMap() {
        val radar = source("src/main/java/com/rainalarm/app/ui/RadarScreen.kt")
        assertTrue(radar.contains("mapStyleError?.let { RadarMapNotice"))
        assertTrue(radar.contains("chartTimeMessage?.let { RadarMapNotice"))
        assertTrue(radar.contains("compatibilityNotice?.let"))
        assertFalse(radar.contains("modifier = Modifier.align(Alignment.TopCenter).padding(horizontal = 54.dp"))
        assertFalse(radar.contains("modifier = Modifier.align(Alignment.Center)\n                        .background(LocalRainAlarmPalette.current.mapLabelSurface"))
    }
}
