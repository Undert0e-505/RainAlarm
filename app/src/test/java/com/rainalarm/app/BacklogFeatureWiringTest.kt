package com.rainalarm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class BacklogFeatureWiringTest {
    private val root: Path by lazy {
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isDirectory(it.resolve("app/src/main/java")) }
    }

    private fun source(relative: String): String = Files.readString(root.resolve(relative))

    @Test fun `phone destinations have one retained non-wrapping pager with accessible actions`() {
        assertEquals(
            listOf(Destination.NOW, Destination.RADAR, Destination.PLACES, Destination.SETTINGS),
            Destination.entries,
        )
        val main = source("app/src/main/java/com/rainalarm/app/MainActivity.kt")
        assertTrue(main.contains("HorizontalPager("))
        assertTrue(main.contains("beyondViewportPageCount = Destination.entries.lastIndex"))
        assertTrue(main.contains("destination != Destination.RADAR"))
        assertTrue(main.contains("customActions = navigationAccessibilityActions"))
        assertTrue(main.contains("getOrNull(destination.ordinal - 1)"))
        assertTrue(main.contains("getOrNull(destination.ordinal + 1)"))
    }

    @Test fun `Places owns startup selection and shared coordinate save flows`() {
        val main = source("app/src/main/java/com/rainalarm/app/MainActivity.kt")
        val places = source("app/src/main/java/com/rainalarm/app/ui/PlacesScreen.kt")
        val picker = source("app/src/main/java/com/rainalarm/app/ui/PlaceMapPicker.kt")
        val settings = source("app/src/main/java/com/rainalarm/app/ui/SettingsScreen.kt")
        val switcher = source("app/src/main/java/com/rainalarm/app/ui/PlaceSwitcher.kt")
        assertTrue(main.contains("setDefaultStartupId = viewModel::setDefaultStartupPlace"))
        assertFalse(main.contains("placesReturnDestination"))
        assertTrue(places.contains("R.string.selected_now"))
        assertTrue(places.contains("R.string.opens_on_startup"))
        assertTrue(places.contains("R.string.places_save_current"))
        assertTrue(places.contains("CurrentLocationSelectionButton("))
        assertTrue(places.contains("liveSnapshotAlreadySaved"))
        assertTrue(places.contains("LiveLocationMatchPolicy.matchingPlace("))
        assertTrue(places.contains("R.string.places_current_saved_status"))
        assertTrue(places.contains("liveCoordinateMatch = currentSelected"))
        assertTrue(places.contains("trailingIcon ="))
        assertTrue(places.contains("ImeAction.Search"))
        assertTrue(places.contains("dismissSearchInput()"))
        assertTrue(places.contains("if (!screenActive) dismissSearchInput()"))
        assertTrue(places.contains("PlacesTopControlGeometry.heightDp.dp"))
        assertTrue(places.contains("PlacesTopControlGeometry.outlineWidthDp.dp"))
        assertTrue(places.contains("PlaceDuplicateFeedbackPolicy.DURATION_MILLIS"))
        assertTrue(picker.contains("PlaceDuplicateFeedbackPolicy.DURATION_MILLIS"))
        assertFalse(places.contains("message = resources.getString(R.string.places_duplicate)"))
        assertTrue(main.contains("if (!preserveFix) {\n            clearLiveLocation()\n            _locationState.value = LocationUiState.Locating"))
        assertTrue(places.contains("Icons.Filled.PushPin else Icons.Outlined.PushPin"))
        assertTrue(places.contains("Icons.Default.Edit"))
        assertTrue(places.contains("Icons.Default.Delete"))
        assertFalse(places.contains("Snackbar"))
        assertTrue(switcher.contains("DropdownMenu("))
        assertTrue(switcher.contains("useCurrentLocation()"))
        assertTrue(picker.contains("Canvas(Modifier.align(Alignment.Center)"))
        assertTrue(picker.contains(".attributionEnabled(true)"))
        assertTrue(picker.contains("PlaceNameSuggestionRepository()"))
        assertTrue(picker.contains("PlacePickerNamePolicy.shouldApplyResult"))
        assertTrue(picker.contains("nameRequestToken++"))
        val radar = source("app/src/main/java/com/rainalarm/app/ui/RadarScreen.kt")
        assertTrue(radar.contains("PlaceNameSuggestionRepository()"))
        assertTrue(radar.contains("PlacePickerNamePolicy.shouldApplyResult"))
        assertTrue(radar.contains("PlacePickerNamePolicy.coordinateFallback"))
        assertFalse(settings.contains("defaultStartupId"))
    }

    @Test fun `automatic appearance and About stay local and preserve visible version and source`() {
        val main = source("app/src/main/java/com/rainalarm/app/MainActivity.kt")
        val settings = source("app/src/main/java/com/rainalarm/app/ui/SettingsScreen.kt")
        assertTrue(main.contains("AutomaticAppearancePolicy.resolve"))
        assertTrue(main.contains("retainedSolarWeather"))
        assertFalse(main.substringAfter("val appearanceWeather").substringBefore("val resolved")
            .contains("repository"))
        assertTrue(settings.contains("R.string.settings_version"))
        assertTrue(settings.contains("https://github.com/Undert0e-505/RainAlarm"))
        assertTrue(settings.indexOf("R.string.settings_version") <
            settings.indexOf("var aboutExpanded"))
        assertTrue(settings.contains("stateDescription = resources.getString("))
    }

    @Test fun `Travel focus and Radar edge gesture wiring use retained live screen state`() {
        val radar = source("app/src/main/java/com/rainalarm/app/ui/RadarScreen.kt")
        val map = source("app/src/main/java/com/rainalarm/app/ui/RadarImageMap.kt")
        assertTrue(radar.contains("entryFocusGeneration"))
        assertTrue(radar.contains("RadarPageGestureExclusions(radarMapBounds, screenActive)"))
        assertTrue(radar.contains("RadarPageSwipePolicy.destinationDelta"))
        assertTrue(radar.contains("RadarPageSwipeEvent.Begin"))
        assertTrue(radar.contains("RadarPageSwipeEvent.Drag(accumulatedX)"))
        assertTrue(radar.contains("RadarPageSwipeEvent.End(delta)"))
        assertTrue(radar.contains("RadarPageSwipeEvent.Cancel"))
        assertTrue(radar.contains("R.string.radar_travel_mode"))
        assertTrue(radar.contains("activateTravelMode"))
        assertTrue(map.contains("RadarEntryFocusPolicy.startZoom"))
        assertTrue(map.contains("entryFocusGeneration"))
    }

    @Test fun `manifest exposes static per-app locales and pseudo locales remain debug only`() {
        val manifest = source("app/src/main/AndroidManifest.xml")
        val build = source("app/build.gradle.kts")
        val main = source("app/src/main/java/com/rainalarm/app/MainActivity.kt")
        assertTrue(manifest.contains("android:localeConfig='@xml/locales_config'"))
        assertTrue(manifest.contains("androidx.appcompat.app.AppLocalesMetadataHolderService"))
        assertTrue(main.contains("class MainActivity : AppCompatActivity()"))
        assertTrue(main.contains("AppCompatDelegate.setApplicationLocales("))
        assertTrue(main.contains("languageChoiceCompleted != true"))
        assertTrue(main.contains("var localeApplyInFlight by remember {"))
        assertFalse(main.contains("var localeApplyInFlight by rememberSaveable"))
        assertTrue(main.indexOf("languageChoiceCompleted != true") <
            main.indexOf("locationPermissionLauncher.launch"))
        assertTrue(build.contains("isPseudoLocalesEnabled = true"))
        assertTrue(build.contains("debug {"))
    }

    @Test fun `Settings language control stays immediately above version and source`() {
        val settings = source("app/src/main/java/com/rainalarm/app/ui/SettingsScreen.kt")
        val language = settings.indexOf("R.string.settings_language")
        val version = settings.indexOf("R.string.settings_version")
        val about = settings.indexOf("var aboutExpanded")
        assertTrue(language >= 0)
        assertTrue(language < version)
        assertTrue(version < about)
        assertTrue(settings.contains("openLanguageSelector"))
    }
}
