package com.rainalarm.app.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRadarMotionWiringTest {
    private fun source(path: String) = listOf(File(path), File("app/$path"))
        .first(File::isFile).readText().replace("\r\n", "\n")

    @Test fun `production build packages opencv without trial or settings plumbing`() {
        val build = listOf(File("app/build.gradle.kts"), File("build.gradle.kts"))
            .first { it.isFile && it.readText().contains("libs.plugins.android.application") }
            .readText().replace("\r\n", "\n")
        val settings = source("src/main/java/com/rainalarm/app/ui/SettingsScreen.kt")
        val main = source("src/main/java/com/rainalarm/app/MainActivity.kt")
        val repository = source("src/main/java/com/rainalarm/app/data/RadarProviders.kt")
        val strings = source("src/main/res/values/strings.xml")
        assertTrue(build.contains("implementation(libs.opencv)"))
        assertFalse(build.contains("OPEN_RADAR_MOTION_TRIAL"))
        assertFalse(build.contains("rainAlarmOpenRadarMotionTrial"))
        assertFalse(settings.contains("OpenRadarMotion"))
        assertFalse(main.contains("openRadarMotion"))
        assertFalse(repository.contains("open_radar_motion_method"))
        assertFalse(strings.contains("settings_open_radar_motion_trial"))
        assertFalse(strings.contains("settings_motion_native_lk"))
    }

    @Test fun `open providers always use native selector while meteo remains untouched`() {
        val coordinator = source("src/main/java/com/rainalarm/app/data/RadarProviders.kt")
        val opera = source("src/main/java/com/rainalarm/app/data/OperaRadarRepository.kt")
        val rainViewer = source("src/main/java/com/rainalarm/app/data/RainViewerRepository.kt")
        val selector = source("src/main/java/com/rainalarm/app/domain/OpenRadarMotion.kt")
        assertTrue(coordinator.contains("regional.load(place, maxFrames, mode, onProgress)"))
        assertTrue(opera.contains("legacyPairFields = fields"))
        assertTrue(opera.contains("trackerFactory = { OpenCvSparseRadarTracker() }"))
        assertTrue(rainViewer.contains("legacyPairFields = fields"))
        assertTrue(rainViewer.contains("trackerFactory = { OpenCvSparseRadarTracker() }"))
        assertTrue(selector.contains("val estimator = NativeOpenRadarMotionEstimator(trackerFactory())"))
        assertTrue(selector.contains("futureField = result.field ?: legacy"))
        assertFalse(coordinator.contains("openRadarMotionMethod"))
        assertFalse(opera.contains("method = openRadarMotionMethod"))
        assertFalse(rainViewer.contains("method = openRadarMotionMethod"))
    }

    @Test fun `native analysis history is bounded to three production observations`() {
        val opera = source("src/main/java/com/rainalarm/app/data/OperaRadarRepository.kt")
        val rainViewer = source("src/main/java/com/rainalarm/app/data/RainViewerRepository.kt")
        assertTrue(opera.contains("NATIVE_MOTION_HISTORY_FRAMES = 3"))
        assertTrue(rainViewer.contains("NATIVE_MOTION_HISTORY_FRAMES = 3"))
        assertFalse(opera.contains("publishAgreementWhenReady"))
    }
}
