package com.rainalarm.app.data

import com.rainalarm.app.domain.RadarResolutionTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.Json

class PlaceAndGeocoderTest {
    @Test fun freshStoreSelectsVirtualCurrentWithoutPersistingAFix() {
        assertTrue(PlaceCollectionRules.isFreshStore(false, false, false))
        assertFalse(PlaceCollectionRules.isFreshStore(true, false, false))
        assertFalse(PlaceCollectionRules.isFreshStore(false, true, false))
        assertFalse(PlaceCollectionRules.isFreshStore(false, false, true))
        val fresh = PlaceCollectionRules.freshInstall()
        assertEquals(CURRENT_LOCATION_ID, fresh.selectedId)
        assertEquals(CURRENT_LOCATION_ID, PlaceCollectionRules.resolveDefaultId(fresh, CURRENT_LOCATION_ID))
        assertFalse(fresh.places.any { it.isCurrentLocation })
        assertEquals(DEFAULT_PLACE, fresh.places.single())
        val savedDefault = PlaceCollectionRules.select(fresh, DEFAULT_PLACE.id)
        assertEquals(DEFAULT_PLACE.id, savedDefault.selectedId)
    }
    @Test
    fun legacySelectionMigratesWithoutLoss() {
        val migrated = PlaceCollectionRules.migrateLegacy(
            name = "Old selection",
            latitude = 53.48,
            longitude = -2.24,
            isCurrent = true,
        )
        assertEquals(1, migrated.places.size)
        assertEquals(DEFAULT_PLACE, migrated.places.single())
        assertEquals("Current location", migrated.selected.name)
        assertEquals(CURRENT_LOCATION_ID, migrated.selected.id)
        assertTrue(migrated.selected.isCurrentLocation)
        assertEquals(0.0, migrated.selected.latitude, 0.0)
    }

    @Test
    fun selectionDeletionAndLastPlaceFallbackAreSafe() {
        val york = SavedPlace("York", 53.96, -1.08)
        var collection = PlaceCollectionRules.upsert(PlaceCollection(), york)
        assertEquals(york.id, collection.selectedId)
        collection = PlaceCollectionRules.select(collection, DEFAULT_PLACE.id)
        assertEquals(DEFAULT_PLACE.id, collection.selectedId)
        collection = PlaceCollectionRules.delete(collection, DEFAULT_PLACE.id)
        assertEquals(york.id, collection.selectedId)
        collection = PlaceCollectionRules.delete(collection, york.id)
        assertEquals(CURRENT_LOCATION_ID, collection.selectedId)
        assertTrue(collection.places.isEmpty())
        assertEquals(CURRENT_LOCATION_SELECTION, collection.selected)
        assertEquals(CURRENT_LOCATION_ID, PlaceCollectionRules.resolveDefaultId(collection, york.id))
        assertEquals(collection, PlaceCollectionRules.normalize(collection))
    }

    @Test
    fun deletingExactRowsPreservesOtherOrderAndPinnedSelection() {
        val york = SavedPlace("York", 53.96, -1.08)
        val bath = SavedPlace("Bath", 51.38, -2.36)
        val exeter = SavedPlace("Exeter", 50.72, -3.53)
        var collection = PlaceCollectionRules.upsert(PlaceCollection(), york)
        collection = PlaceCollectionRules.upsert(collection, bath, select = false)
        collection = PlaceCollectionRules.upsert(collection, exeter, select = false)
        collection = PlaceCollectionRules.setPinned(collection, bath.id, true)
        collection = PlaceCollectionRules.reorder(collection,
            listOf(exeter.id, york.id, DEFAULT_PLACE.id, bath.id))
        collection = PlaceCollectionRules.delete(collection, exeter.id)
        assertEquals(listOf(york.id, DEFAULT_PLACE.id, bath.id), collection.places.map { it.id })
        assertEquals(york.id, collection.selectedId)
        collection = PlaceCollectionRules.delete(collection, DEFAULT_PLACE.id)
        assertEquals(listOf(york.id, bath.id), collection.places.map { it.id })
        collection = PlaceCollectionRules.delete(collection, york.id)
        assertEquals(bath.id, collection.selectedId) // pinned saved place wins
        assertEquals(listOf(bath.id), collection.places.map { it.id })
        collection = PlaceCollectionRules.delete(collection, bath.id)
        assertTrue(collection.places.isEmpty())
        assertEquals(CURRENT_LOCATION_ID, collection.selectedId)
    }

    @Test
    fun emptySavedListSurvivesSerializationSelectionAndStartupFallback() {
        val york = SavedPlace("York", 53.96, -1.08)
        var collection = PlaceCollectionRules.upsert(PlaceCollection(), york)
        collection = PlaceCollectionRules.delete(collection, DEFAULT_PLACE.id)
        collection = PlaceCollectionRules.delete(collection, york.id)
        val json = Json { encodeDefaults = true }
        val restored = PlaceCollectionRules.normalize(json.decodeFromString<PlaceCollection>(
            json.encodeToString(collection)))
        assertTrue(restored.places.isEmpty())
        assertEquals(CURRENT_LOCATION_ID, restored.selectedId)
        assertEquals(CURRENT_LOCATION_ID, PlaceCollectionRules.resolveDefaultId(restored, york.id))
        assertEquals(CURRENT_LOCATION_ID, PlaceCollectionRules.resolveDefaultId(restored, null))
        assertEquals(restored, PlaceCollectionRules.select(restored, CURRENT_LOCATION_ID))
        assertEquals(restored, PlaceCollectionRules.delete(restored, CURRENT_LOCATION_ID))
        assertEquals(restored, PlaceCollectionRules.delete(restored, "missing"))
        assertEquals(listOf(york), PlaceCollectionRules.upsert(restored, york).places)
    }

    @Test
    fun currentLocationUpdateDoesNotCreateDuplicates() {
        val first = SavedPlace("Current location", 51.0, -1.0, isCurrentLocation = true)
        val second = SavedPlace("Current location", 52.0, -2.0, isCurrentLocation = true)
        var collection = PlaceCollectionRules.upsert(PlaceCollection(), first)
        collection = PlaceCollectionRules.upsert(collection, second)
        assertEquals(0, collection.places.count { it.isCurrentLocation })
        assertEquals(0.0, collection.selected.latitude, 0.0)
        assertEquals(CURRENT_LOCATION_ID, collection.selected.id)
        assertTrue(forecastSelectionKey(first) != forecastSelectionKey(second))
    }

    @Test
    fun `startup default and active selection remain independent with deletion fallback`() {
        val york = SavedPlace("York", 53.96, -1.08)
        var collection = PlaceCollectionRules.upsert(PlaceCollection(), york)
        assertEquals(york.id, PlaceCollectionRules.resolveDefaultId(collection, york.id))
        collection = PlaceCollectionRules.select(collection, CURRENT_LOCATION_ID)
        assertEquals(CURRENT_LOCATION_ID, collection.selectedId)
        assertEquals(york.id, PlaceCollectionRules.resolveDefaultId(collection, york.id))
        collection = PlaceCollectionRules.delete(collection, york.id)
        assertEquals(DEFAULT_PLACE.id, PlaceCollectionRules.resolveDefaultId(collection, york.id))
        assertEquals(CURRENT_LOCATION_ID, PlaceCollectionRules.resolveDefaultId(collection, CURRENT_LOCATION_ID))
        assertEquals(DEFAULT_PLACE.id, PlaceCollectionRules.resolveDefaultId(collection, null))
    }

    @Test
    fun `foreground fix policy rejects stale positions and refreshes after material travel`() {
        val now = 1_000_000L
        assertTrue(LiveLocationPolicy.isFresh(now - 60_000, now))
        assertTrue(LiveLocationPolicy.isImmediatelyUsable(now - 20_000, now))
        assertFalse(LiveLocationPolicy.isImmediatelyUsable(now - 60_000, now))
        assertFalse(LiveLocationPolicy.isFresh(now - 600_000, now))
        assertFalse(LiveLocationPolicy.materiallyMoved(53.48, -2.24, 53.4801, -2.2401))
        assertTrue(LiveLocationPolicy.materiallyMoved(53.48, -2.24, 53.49, -2.24))
        assertEquals(0f, LiveLocationPolicy.UPDATE_DISTANCE_METRES)
        assertFalse(LiveLocationPolicy.needsForegroundRefresh(now - 60_000, now))
        assertTrue(LiveLocationPolicy.needsForegroundRefresh(now - 121_000, now))
        assertTrue(LiveLocationPolicy.needsForegroundRefresh(0, now))
        assertEquals(listOf("network", "gps"), LiveLocationPolicy.allowedProviders(
            listOf("network", "gps"), fineGranted = true))
        assertEquals(listOf("network"), LiveLocationPolicy.allowedProviders(
            listOf("network", "gps"), fineGranted = false))
        assertTrue(CurrentLocationSelectionPolicy.needsFixRetry(CURRENT_LOCATION_ID, false))
        assertFalse(CurrentLocationSelectionPolicy.needsFixRetry(CURRENT_LOCATION_ID, true))
        assertFalse(CurrentLocationSelectionPolicy.needsFixRetry(DEFAULT_PLACE.id, false))
    }

    @Test
    fun `live fix stays in memory only while foreground selected and fresh`() {
        val fixTime = 1_000_000L
        val current = SavedPlace("Manchester", 53.48, -2.24, isCurrentLocation = true)
        ForegroundLocationSnapshot.setForeground(true)
        try {
            ForegroundLocationSnapshot.update(current, fixTime)
            assertEquals(current, ForegroundLocationSnapshot.freshPlace(fixTime + 60_000))
            assertEquals(null, ForegroundLocationSnapshot.freshPlace(fixTime + 600_000))
            ForegroundLocationSnapshot.clear()
            assertEquals(null, ForegroundLocationSnapshot.freshPlace(fixTime + 60_000))
            ForegroundLocationSnapshot.update(current, fixTime)
            ForegroundLocationSnapshot.setForeground(false)
            assertEquals(null, ForegroundLocationSnapshot.freshPlace(fixTime + 60_000))
        } finally {
            ForegroundLocationSnapshot.setForeground(false)
        }
    }

    @Test
    fun newPinPromotesOnceButDragOrderSurvivesNormalizationAndSelection() {
        val plain = SavedPlace("Plain", 50.0, 0.0)
        val pinned = SavedPlace("Pinned", 55.0, 0.0)
        var collection = PlaceCollectionRules.upsert(PlaceCollection(), plain)
        collection = PlaceCollectionRules.upsert(collection, pinned, select = false)
        collection = PlaceCollectionRules.setPinned(collection, pinned.id, true)
        assertEquals(pinned.id, collection.places.first().id)
        assertEquals(plain.id, collection.selectedId)
        val dragged = listOf(plain.id, DEFAULT_PLACE.id, pinned.id)
        collection = PlaceCollectionRules.reorder(collection, dragged)
        assertEquals(dragged, PlaceCollectionRules.normalize(collection).places.map { it.id })
        assertEquals(dragged, PlaceCollectionRules.select(collection, DEFAULT_PLACE.id).places.map { it.id })
        assertEquals(dragged, PlaceCollectionRules.rename(collection, pinned.id, "Pinned again").places.map { it.id })
        assertEquals(dragged, PlaceCollectionRules.setPinned(collection, pinned.id, false).places.map { it.id })
    }

    @Test
    fun renameAndReorderPreserveIdentityCoordinatesSelectionAndStartupDefault() {
        val york = SavedPlace("York", 53.96, -1.08)
        val bath = SavedPlace("Bath", 51.38, -2.36)
        var collection = PlaceCollectionRules.upsert(PlaceCollection(), york)
        collection = PlaceCollectionRules.upsert(collection, bath, select = false)
        val defaultId = bath.id
        val originalKey = forecastSelectionKey(york)
        collection = PlaceCollectionRules.rename(collection, york.id, "  York centre  ")
        val renamed = collection.places.single { it.id == york.id }
        assertEquals("York centre", renamed.name)
        assertEquals(york.latitude, renamed.latitude, 0.0)
        assertEquals(york.longitude, renamed.longitude, 0.0)
        assertEquals(originalKey, forecastSelectionKey(renamed))
        assertEquals(york.id, collection.selectedId)
        assertEquals(defaultId, PlaceCollectionRules.resolveDefaultId(collection, defaultId))
        val order = listOf(bath.id, york.id, DEFAULT_PLACE.id)
        collection = PlaceCollectionRules.reorder(collection, order)
        assertEquals(order, collection.places.map { it.id })
        assertEquals(york.id, collection.selectedId)
        assertEquals(defaultId, PlaceCollectionRules.resolveDefaultId(collection, defaultId))
        // DataStore decodes through normalize on every read; that may not undo a drag.
        assertEquals(order, PlaceCollectionRules.normalize(collection).places.map { it.id })
    }

    @Test
    fun invalidRenameAndStaleReorderAreNoOps() {
        val collection = PlaceCollectionRules.upsert(PlaceCollection(), SavedPlace("York", 53.96, -1.08))
        val york = collection.selected
        for (input in listOf("", "  ", "bad\nname", "x".repeat(61))) {
            assertFalse(PlaceCollectionRules.validName(input))
            assertEquals(collection, PlaceCollectionRules.rename(collection, york.id, input))
        }
        assertEquals(collection, PlaceCollectionRules.rename(collection, CURRENT_LOCATION_ID, "Home"))
        assertEquals(collection, PlaceCollectionRules.rename(collection, "missing", "Home"))
        assertEquals(collection, PlaceCollectionRules.reorder(collection, listOf(york.id)))
        assertEquals(collection, PlaceCollectionRules.reorder(collection, listOf(york.id, york.id)))
        assertEquals(collection, PlaceCollectionRules.reorder(collection, listOf(CURRENT_LOCATION_ID,
            DEFAULT_PLACE.id, york.id)))
    }

    @Test
    fun geocoderUrlEncodesUnicodeSpacesAndLanguage() {
        val url = buildGeocodingUrl("  St Albans  ", "en GB")
        assertTrue(url.startsWith("https://geocoding-api.open-meteo.com/v1/search?"))
        assertTrue(url.contains("name=St+Albans"))
        assertTrue(url.contains("language=en+GB"))
        assertThrows(IllegalArgumentException::class.java) {
            buildGeocodingUrl("x", "en")
        }
        assertThrows(IllegalArgumentException::class.java) {
            buildGeocodingUrl("London", "en", "http://example.invalid/search")
        }
    }

    @Test
    fun geocoderParsingHandlesResultsEmptyAndMalformedPayload() {
        val results = GeocodingMapper.parse(
            """
            {"results":[
              {"name":"Bristol","latitude":51.45,"longitude":-2.59,
               "admin1":"England","country":"United Kingdom"},
              {"name":"Invalid","latitude":99.0,"longitude":0.0}
            ]}
            """.trimIndent(),
        )
        assertEquals(1, results.size)
        assertEquals("Bristol", results.first().name)
        assertEquals("England, United Kingdom", results.first().detail)
        assertTrue(GeocodingMapper.parse("{}").isEmpty())
        assertThrows(Exception::class.java) { GeocodingMapper.parse("{broken") }
    }

    @Test
    fun coordinateRadarUrlSupportsRegionalAndDetailImagesAndValidatesInputs() {
        val frame = RainViewerFrame(1_789_378_200L, "/v2/radar/1789378200")
        val detailUrl = buildCoordinateRadarUrl(
            "https://tilecache.rainviewer.com/",
            frame,
            SavedPlace("Here", 51.5074, -0.1278),
        )
        assertEquals(
            "https://tilecache.rainviewer.com/v2/radar/1789378200/512/7/" +
                "51.5074/-0.1278/2/1_1.png",
            detailUrl,
        )
        val regionalUrl = buildCoordinateRadarUrl(
            "https://tilecache.rainviewer.com",
            frame,
            SavedPlace("Here", 51.5074, -0.1278),
            zoom = 5,
            imageSize = 512,
        )
        assertTrue(regionalUrl.contains("/512/5/51.5074/-0.1278/"))
        assertFalse(detailUrl.contains("{z}"))
        assertThrows(IllegalArgumentException::class.java) {
            buildCoordinateRadarUrl("http://example.invalid", frame, DEFAULT_PLACE)
        }
        assertThrows(IllegalArgumentException::class.java) {
            buildCoordinateRadarUrl(
                "https://tilecache.rainviewer.com",
                frame.copy(path = "../bad"),
                DEFAULT_PLACE,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            buildCoordinateRadarUrl(
                "https://tilecache.rainviewer.com",
                frame,
                DEFAULT_PLACE,
                zoom = 8,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            buildCoordinateRadarUrl(
                "https://tilecache.rainviewer.com",
                frame,
                DEFAULT_PLACE,
                imageSize = 1024,
            )
        }
    }

    @Test
    fun radarRequestPlansTwoTiersAndAlertsUseFiveFramesPerTier() {
        val frames = (1L..13L).map { RainViewerFrame(it, "/v2/radar/$it") }
        val screen = radarRequestPlan(frames, RadarLoadMode.SCREEN_TWO_TIER)
        assertEquals(26, screen.size)
        assertEquals(13, screen.count { it.tier == RadarResolutionTier.REGIONAL })
        assertEquals(13, screen.count { it.tier == RadarResolutionTier.DETAIL })
        val alert = radarRequestPlan(frames.takeLast(5), RadarLoadMode.ALERT_ANALYSIS)
        assertEquals(10, alert.size)
        assertEquals(5, alert.count { it.tier == RadarResolutionTier.REGIONAL })
        assertEquals(5, alert.count { it.tier == RadarResolutionTier.DETAIL })
    }
}
