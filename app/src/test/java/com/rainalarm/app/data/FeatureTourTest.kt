package com.rainalarm.app.data

import com.rainalarm.app.domain.RainMinuteSeriesAnalyzer
import com.rainalarm.app.ui.FeatureTourPlacementPolicy
import com.rainalarm.app.ui.FeatureTourRect
import com.rainalarm.app.ui.FeatureTourTarget
import com.rainalarm.app.ui.FeatureTourTargetBounds
import com.rainalarm.app.domain.FeatureTourRadarField
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureTourTest {
    private val root: Path by lazy {
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isDirectory(it.resolve("app/src/main/java")) }
    }

    private fun source(relative: String): String = Files.readString(root.resolve(relative))

    @Test fun `fresh install is eligible while upgrades default completed`() {
        assertEquals(
            FeatureTourLaunchState.NOW_PENDING,
            FeatureTourPersistencePolicy.initialState(true, null, null),
        )
        assertEquals(
            FeatureTourLaunchState.COMPLETE,
            FeatureTourPersistencePolicy.initialState(false, null, null),
        )
        assertEquals(
            FeatureTourLaunchState.RADAR_PENDING,
            FeatureTourPersistencePolicy.initialState(false, true, false),
        )
        assertEquals("feature_tour_v1_now", FeatureTourPreferences.stageKey(1, "now"))
        assertEquals("feature_tour_v1_radar", FeatureTourPreferences.stageKey(1, "radar"))
    }

    @Test fun `Now starts only after onboarding destination and measured target are ready`() {
        assertNull(FeatureTourPolicy.initial(FeatureTourLaunchState.NOW_PENDING, false, true, true))
        assertNull(FeatureTourPolicy.initial(FeatureTourLaunchState.NOW_PENDING, true, false, true))
        assertNull(FeatureTourPolicy.initial(FeatureTourLaunchState.NOW_PENDING, true, true, false))
        assertEquals(
            FeatureTourProgress(FeatureTourStage.NOW, 0),
            FeatureTourPolicy.initial(FeatureTourLaunchState.NOW_PENDING, true, true, true),
        )
        assertNull(FeatureTourPolicy.initial(FeatureTourLaunchState.COMPLETE, true, true, true))
    }

    @Test fun `tour advances four Now steps then three Radar steps and skip completes globally`() {
        var progress = FeatureTourProgress(FeatureTourStage.NOW, 0)
        repeat(3) { progress = requireNotNull(FeatureTourPolicy.reduce(progress, FeatureTourAction.Advance)) }
        assertEquals(FeatureTourProgress(FeatureTourStage.NOW, 3), progress)
        progress = requireNotNull(FeatureTourPolicy.reduce(progress, FeatureTourAction.Advance))
        assertEquals(FeatureTourProgress(FeatureTourStage.RADAR, 0), progress)
        repeat(2) { progress = requireNotNull(FeatureTourPolicy.reduce(progress, FeatureTourAction.Advance)) }
        assertTrue(FeatureTourPolicy.completes(progress, FeatureTourAction.Advance))
        assertNull(FeatureTourPolicy.reduce(progress, FeatureTourAction.Advance))
        assertTrue(
            FeatureTourPolicy.completes(
                FeatureTourProgress(FeatureTourStage.NOW, 0),
                FeatureTourAction.Skip,
            ),
        )
        assertNull(
            FeatureTourPolicy.reduce(
                FeatureTourProgress(FeatureTourStage.RADAR, 1),
                FeatureTourAction.Skip,
            ),
        )
    }

    @Test fun `force stop resumes pending Radar stage from its first step`() {
        assertEquals(
            FeatureTourProgress(FeatureTourStage.RADAR, 0),
            FeatureTourPolicy.resumeRadar(FeatureTourLaunchState.RADAR_PENDING, true),
        )
        assertNull(FeatureTourPolicy.resumeRadar(FeatureTourLaunchState.RADAR_PENDING, false))
        assertNull(FeatureTourPolicy.resumeRadar(FeatureTourLaunchState.COMPLETE, true))
    }

    @Test fun `example scenario is deterministic coherent and falls back without saving`() {
        val now = 1_800_001_234L
        val first = FeatureTourScenario.create(now, null, "Example", "Rain in 11 min", "Then easing")
        val second = FeatureTourScenario.create(now, null, "Example", "Rain in 11 min", "Then easing")
        assertEquals(first, second)
        assertEquals("feature-tour-fallback", first.fallbackPlace.id)
        assertEquals(DEFAULT_PLACE.latitude, first.fallbackPlace.latitude, 0.0)
        assertEquals(61, first.forecast.nowcastSeries?.points?.size)
        val series = requireNotNull(first.forecast.nowcastSeries)
        val analysis = requireNotNull(RainMinuteSeriesAnalyzer.analyze(series))
        assertFalse(analysis.rainingNow)
        assertEquals(FeatureTourScenario.RAIN_ARRIVAL_MINUTE, analysis.arrivalMinute)
        assertTrue(analysis.maximum >= 0.64f)
        assertEquals(FeatureTourScenario.SOURCE_BEARING_DEGREES, series.sourceBearingDegrees!!, 0.0)
        assertEquals(FeatureTourScenario.RADAR_FRAME_COUNT, first.radarFrames.size)
        assertTrue(first.radarFrames.zipWithNext().all { (a, b) -> b.epochSeconds > a.epochSeconds })
        assertTrue(first.radarFrames.zipWithNext().all { (a, b) -> b.motionProgress > a.motionProgress })
        assertEquals(0f, first.radarMotionProgress(first.radarFrames.first().epochSeconds.toDouble()), 0f)
        assertEquals(1f, first.radarMotionProgress(first.radarFrames.last().epochSeconds.toDouble()), 0f)
    }

    @Test fun `example scenario retains the real selected coordinate without mutating it`() {
        val place = SavedPlace("Utrecht", 52.0907, 5.1214)
        val scenario = FeatureTourScenario.create(
            1_800_001_234L,
            place,
            "Example",
            "Rain in 11 min",
            "Then easing",
        )
        assertEquals(place, scenario.fallbackPlace)
        assertEquals("Example", scenario.forecast.sourceLabel)
        assertTrue(scenario.forecast.isDemo)
    }

    @Test fun `feature tour radar field is deterministic bounded and organically distributed`() {
        val first = FeatureTourRadarField.generate()
        val second = FeatureTourRadarField.generate()
        assertTrue(first.contentEquals(second))
        assertTrue(first.all { it in 0f..1f })
        val visible = first.count { it > 0.08f }
        val cores = first.count { it > 0.73f }
        assertTrue(visible > first.size / 16)
        assertTrue(visible < first.size / 2)
        assertTrue(cores > 0)
        assertTrue(cores < visible / 5)
    }

    @Test fun `feature tour radar field translates coherently northeast`() {
        val start = FeatureTourRadarField.motionOffset(0f)
        val finish = FeatureTourRadarField.motionOffset(1f)
        assertTrue(finish.xFraction > start.xFraction)
        assertTrue(finish.yFraction < start.yFraction)

        val sourceX = 0.53f
        val sourceY = 0.47f
        val progress = 0.42f
        val offset = FeatureTourRadarField.motionOffset(progress)
        assertEquals(
            FeatureTourRadarField.intensityAt(sourceX, sourceY),
            FeatureTourRadarField.intensityAtViewport(
                sourceX + offset.xFraction,
                sourceY + offset.yFraction,
                progress,
            ),
            0.00001f,
        )
    }

    @Test fun `target readiness requires real on-screen measured bounds`() {
        val valid = FeatureTourTargetBounds(
            FeatureTourTarget.NOW_COMPASS,
            FeatureTourRect(20f, 80f, 340f, 420f),
            cornerRadiusPx = 28f,
            paddingPx = 4f,
        )
        assertTrue(valid.ready(360f, 760f))
        assertFalse(valid.copy(bounds = FeatureTourRect(0f, 0f, 0f, 0f)).ready(360f, 760f))
        assertFalse(valid.copy(bounds = FeatureTourRect(500f, 80f, 600f, 420f)).ready(360f, 760f))
        assertEquals(FeatureTourRect(16f, 76f, 344f, 424f), valid.cutout)
    }

    @Test fun `card placement stays adjacent and clamped within safe drawing bounds`() {
        val below = FeatureTourPlacementPolicy.place(
            rootWidthPx = 360, rootHeightPx = 800,
            target = FeatureTourRect(20f, 80f, 340f, 240f),
            cardWidthPx = 320, cardHeightPx = 180,
            safeTopPx = 24, safeBottomPx = 48, safeLeftPx = 0, safeRightPx = 0,
            marginPx = 16, gapPx = 12,
        )
        assertTrue(below.belowTarget)
        assertEquals(252, below.yPx)
        assertTrue(below.xPx >= 16)

        val above = FeatureTourPlacementPolicy.place(
            rootWidthPx = 360, rootHeightPx = 800,
            target = FeatureTourRect(20f, 610f, 340f, 760f),
            cardWidthPx = 320, cardHeightPx = 220,
            safeTopPx = 24, safeBottomPx = 48, safeLeftPx = 0, safeRightPx = 0,
            marginPx = 16, gapPx = 12,
        )
        assertFalse(above.belowTarget)
        assertTrue(above.yPx >= 24)
        assertTrue(above.yPx + 220 <= 610)

        val sideInset = FeatureTourPlacementPolicy.place(
            rootWidthPx = 500, rootHeightPx = 800,
            target = FeatureTourRect(0f, 300f, 80f, 380f),
            cardWidthPx = 320, cardHeightPx = 180,
            safeTopPx = 24, safeBottomPx = 48, safeLeftPx = 30, safeRightPx = 20,
            marginPx = 16, gapPx = 12,
        )
        assertTrue(sideInset.xPx >= 46)
        assertTrue(sideInset.xPx + 320 <= 464)

        val nearBottomTarget = FeatureTourPlacementPolicy.place(
            rootWidthPx = 1080, rootHeightPx = 2400,
            target = FeatureTourRect(36f, 1580f, 1044f, 2112f),
            cardWidthPx = 960,
            cardHeightPx = FeatureTourPlacementPolicy.INITIAL_CARD_HEIGHT_DP * 3,
            safeTopPx = 72, safeBottomPx = 63, safeLeftPx = 0, safeRightPx = 0,
            marginPx = 48, gapPx = 36,
        )
        assertFalse("a first-pass card must not be trapped in the short strip below the graph", nearBottomTarget.belowTarget)
        assertTrue(nearBottomTarget.yPx + FeatureTourPlacementPolicy.INITIAL_CARD_HEIGHT_DP * 3 <= 1544)
    }

    @Test fun `tour uses measured targets no delay connectors or production data mutation`() {
        val overlay = source("app/src/main/java/com/rainalarm/app/ui/FeatureSpotlight.kt")
        val main = source("app/src/main/java/com/rainalarm/app/MainActivity.kt")
        val scenario = source("app/src/main/java/com/rainalarm/app/data/FeatureTourScenario.kt")
        assertTrue(overlay.contains("boundsInRoot()"))
        assertTrue(overlay.contains("BlendMode.Clear"))
        assertFalse(overlay.contains("Connector"))
        assertFalse(overlay.contains("drawLine"))
        assertFalse(main.substringAfter("FeatureTourPolicy.initial(").substringBefore(") ?: return")
            .contains("delay("))
        listOf("Repository", "SharedPreferences", "DataStore", "WorkManager", "Notification")
            .forEach { forbidden -> assertFalse("scenario contains $forbidden", scenario.contains(forbidden)) }
        assertTrue(main.contains("completeFeatureTourNowStage()"))
        assertTrue(main.contains("pagerState.settledPage == expectedDestination.ordinal"))
        assertTrue(main.contains("featureTourScenario = null"))
        assertTrue(main.contains("NowEntryRefreshPolicy.entersNow(previous, next) && featureTourStageName == null"))
        val preferences = source("app/src/main/java/com/rainalarm/app/data/FeatureTour.kt")
        assertTrue(preferences.contains(".commit()"))
        assertFalse(preferences.contains(".apply()"))
    }

    @Test fun `all seven measured targets and replay entry are wired`() {
        val main = source("app/src/main/java/com/rainalarm/app/MainActivity.kt")
        val now = source("app/src/main/java/com/rainalarm/app/ui/NowScreen.kt")
        val radar = source("app/src/main/java/com/rainalarm/app/ui/RadarScreen.kt")
        val settings = source("app/src/main/java/com/rainalarm/app/ui/SettingsScreen.kt")
        assertTrue(main.contains("FeatureTourTarget.NAV_RADAR"))
        listOf("NOW_PLACE", "NOW_COMPASS", "NOW_GRAPH").forEach {
            assertTrue("missing $it", now.contains("FeatureTourTarget.$it"))
        }
        listOf("RADAR_TIMELINE", "RADAR_LAYERS", "RADAR_TRAVEL").forEach {
            assertTrue("missing $it", radar.contains("FeatureTourTarget.$it"))
        }
        assertTrue(settings.contains("R.string.settings_show_app_tour"))
        assertTrue(main.contains("viewModel.replayFeatureTour()"))
        assertTrue(main.contains("featureTourPreferences.resetForReplay()"))
        assertTrue(main.contains("userScrollEnabled = !wide && destination != Destination.RADAR &&"))
        assertTrue(main.contains("featureTourProgress == null"))
        assertTrue(radar.contains("radarPresentationVisible = featureTourScenario == null"))
        assertFalse(radar.contains("session.takeIf { featureTourScenario == null }"))
        assertTrue(radar.contains("var tourCursor by remember(featureTourScenario)"))
        assertTrue(radar.contains("if (featureTourScenario != null) tourCursor else cursor"))
        val map = source("app/src/main/java/com/rainalarm/app/ui/RadarImageMap.kt")
        assertTrue(map.contains("fun setVisible(visible: Boolean)"))
        assertTrue(map.contains("latestRadarPresentationVisible"))
    }
}
