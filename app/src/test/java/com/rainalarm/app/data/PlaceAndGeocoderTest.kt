package com.rainalarm.app.data

import com.rainalarm.app.domain.RadarResolutionTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
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
        assertTrue(LiveLocationPolicy.isImmediatelyUsable(now - 4_000, now))
        assertFalse(LiveLocationPolicy.isImmediatelyUsable(now - 20_000, now))
        assertFalse(LiveLocationPolicy.isImmediatelyUsable(now - 60_000, now))
        assertFalse(LiveLocationPolicy.isFresh(now - 600_000, now))
        assertFalse(LiveLocationPolicy.materiallyMoved(53.48, -2.24, 53.4801, -2.2401))
        assertTrue(LiveLocationPolicy.materiallyMoved(53.48, -2.24, 53.49, -2.24))
        assertEquals(0f, LiveLocationPolicy.UPDATE_DISTANCE_METRES)
        assertFalse(LiveLocationPolicy.needsForegroundRefresh(now - 60_000, now))
        assertTrue(LiveLocationPolicy.needsForegroundRefresh(now - 121_000, now))
        assertTrue(LiveLocationPolicy.needsForegroundRefresh(0, now))
        assertEquals(listOf("gps"), LiveLocationPolicy.allowedProviders(
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
    fun `active live fix becomes an ordinary saved snapshot only by explicit conversion`() {
        val live = SavedPlace("Danbury", 51.720457, 0.561948, isCurrentLocation = true)
        val snapshot = requireNotNull(LiveLocationSavePolicy.snapshot(live))
        assertEquals(live.name, snapshot.name)
        assertEquals(live.latitude, snapshot.latitude, 0.0)
        assertEquals(live.longitude, snapshot.longitude, 0.0)
        assertFalse(snapshot.isCurrentLocation)
        assertFalse(snapshot.id == CURRENT_LOCATION_ID)
        assertEquals(stablePlaceId(live.latitude, live.longitude), snapshot.id)
        assertEquals(null, LiveLocationSavePolicy.snapshot(DEFAULT_PLACE))
        assertEquals(null, LiveLocationSavePolicy.snapshot(null))
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
    fun `full UK postcode policy normalizes standard variants and builds an HTTPS path`() {
        val variants = mapOf(
            "m1 1ae" to "M1 1AE",
            "M601NW" to "M60 1NW",
            "cr2 6xh" to "CR2 6XH",
            "DN551PT" to "DN55 1PT",
            "w1a 1hq" to "W1A 1HQ",
            "EC1A1BB" to "EC1A 1BB",
            "gir0aa" to "GIR 0AA",
        )
        variants.forEach { (input, expected) -> assertEquals(expected, UkPostcodePolicy.normalize(input)) }
        assertEquals(null, UkPostcodePolicy.normalize("London"))
        assertEquals(null, UkPostcodePolicy.normalize("CM3"))
        assertEquals(null, UkPostcodePolicy.normalize("CM3 4CI"))
        assertEquals("https://api.postcodes.io/postcodes/CM3%204DS",
            buildPostcodeLookupUrl(" cm34ds "))
        assertThrows(IllegalArgumentException::class.java) {
            buildPostcodeLookupUrl("CM3 4DS", "http://example.invalid/postcodes")
        }
        assertThrows(IllegalArgumentException::class.java) {
            buildPostcodeLookupUrl("not a postcode")
        }
    }

    @Test
    fun `postcode mapper keeps canonical postcode coordinates and distinct locality detail`() {
        val result = PostcodesIoMapper.parse(
            """
            {"status":200,"result":{"postcode":"CM3 4DS","latitude":51.720457,
              "longitude":0.561948,"parish":"Danbury","admin_district":"Chelmsford",
              "admin_county":"Essex","country":"England"}}
            """.trimIndent(),
        ).single()
        assertEquals("CM3 4DS", result.name)
        assertEquals("Danbury, Chelmsford, Essex, England", result.detail)
        assertEquals(51.720457, result.latitude, 0.0000001)
        assertEquals(0.561948, result.longitude, 0.0000001)
        assertTrue(PostcodesIoMapper.parse(
            """{"status":200,"result":{"postcode":"CM3 4DS","latitude":null,"longitude":0.5}}""",
        ).isEmpty())
        assertTrue(PostcodesIoMapper.parse(
            """{"status":200,"result":{"postcode":"CM3 4DS","latitude":99.0,"longitude":0.5}}""",
        ).isEmpty())
        assertTrue(PostcodesIoMapper.parse(
            """{"status":200,"result":{"postcode":"invalid","latitude":51.0,"longitude":0.5}}""",
        ).isEmpty())
        assertTrue(PostcodesIoMapper.parse("""{"status":404,"result":null}""").isEmpty())
        assertThrows(Exception::class.java) { PostcodesIoMapper.parse("{broken") }
    }

    @Test
    fun `postcode transport distinguishes unknown postcode from service failure`() = runBlocking {
        val notFound = PostcodesIoGeocoder(httpClient = GeocodingHttpClient { _, connect, read ->
            assertEquals(8_000, connect)
            assertEquals(10_000, read)
            GeocodingHttpResponse(404, "")
        })
        assertTrue(notFound.search("CM3 4ZZ", "en").isEmpty())
        val unavailable = PostcodesIoGeocoder(httpClient = GeocodingHttpClient { _, _, _ ->
            GeocodingHttpResponse(503, "service unavailable")
        })
        val failure = runCatching { unavailable.search("CM3 4DS", "en") }.exceptionOrNull()
        assertTrue(failure?.message?.contains("503") == true)
    }

    @Test
    fun `place geocoder routes only complete postcode shapes away from Open Meteo`() = runBlocking {
        val townQueries = mutableListOf<String>()
        val postcodeQueries = mutableListOf<String>()
        val namedQueries = mutableListOf<String>()
        val town = object : GeocodingEndpoint {
            override suspend fun search(query: String, language: String): List<PlaceSearchResult> {
                townQueries += query
                return listOf(PlaceSearchResult("London", "England", 51.5, -0.1))
            }
        }
        val postcode = object : GeocodingEndpoint {
            override suspend fun search(query: String, language: String): List<PlaceSearchResult> {
                postcodeQueries += query
                return listOf(PlaceSearchResult("CM3 4DS", "Danbury", 51.72, 0.56))
            }
        }
        val named = object : GeocodingEndpoint {
            override suspend fun search(query: String, language: String): List<PlaceSearchResult> {
                namedQueries += query
                return emptyList()
            }
        }
        val geocoder = PlaceGeocoder(town, postcode, named)
        assertEquals("London", geocoder.search("London", "en").single().name)
        assertEquals("CM3 4DS", geocoder.search("cm34ds", "en").single().name)
        assertEquals(listOf("London"), townQueries)
        assertEquals(listOf("cm34ds"), postcodeQueries)
        assertEquals(listOf("London"), namedQueries)
        assertTrue(buildGeocodingUrl("London", "en")
            .startsWith("https://geocoding-api.open-meteo.com/v1/search?"))
    }

    @Test
    fun `Photon URL and parser return named OSM features including Ragley Hall`() {
        assertEquals(
            "https://photon.komoot.io/api?q=Ragley+Estate+Warwickshire&limit=6&lang=en",
            buildPhotonGeocodingUrl("Ragley Estate Warwickshire", "en"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            buildPhotonGeocodingUrl("Ragley", "en", "http://example.invalid/api")
        }
        val results = PhotonGeocodingMapper.parse(
            """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","properties":{"osm_type":"W","osm_id":123,
                "name":"Ragley Hall","county":"Warwickshire","state":"England",
                "country":"United Kingdom"},
               "geometry":{"coordinates":[-1.8961030,52.1980316],"type":"Point"}},
              {"type":"Feature","properties":{"name":"Ragley Park","county":"Warwickshire"},
               "geometry":{"coordinates":[-1.9001,52.2001],"type":"Point"}},
              {"type":"Feature","properties":{},
               "geometry":{"coordinates":[-1.0,52.0],"type":"Point"}}
            ]}
            """.trimIndent(),
        )
        assertEquals(listOf("Ragley Hall", "Ragley Park"), results.map { it.name })
        assertEquals("Warwickshire, England, United Kingdom", results.first().detail)
        assertEquals(52.1980316, results.first().latitude, 0.0000001)
        assertEquals(-1.8961030, results.first().longitude, 0.0000001)
    }

    @Test
    fun `Wikimedia URL normalizes possessives and parser keeps only valid geocoded pages`() {
        val expected = "https://en.wikipedia.org/w/api.php?action=query&format=json&formatversion=2" +
            "&generator=search&gsrsearch=ragley+estate&gsrlimit=5&gsrnamespace=0" +
            "&prop=coordinates%7Cdescription&coprimary=all&redirects=1"
        assertEquals(expected, buildWikimediaGeocodingUrl("ragley estate's"))
        assertEquals(expected, buildWikimediaGeocodingUrl("ragley estate’s"))
        assertEquals("ragley estate", PlaceSearchText.normalize(" Ragley Estate’s "))
        assertThrows(IllegalArgumentException::class.java) {
            buildWikimediaGeocodingUrl("Ragley", "http://example.invalid/w/api.php")
        }

        val results = WikimediaGeocodingMapper.parse(
            """
            {"query":{"pages":[
              {"pageid":123,"title":"Ragley Hall",
               "description":"Grade I listed historic house in Warwickshire, England",
               "coordinates":[{"lat":52.1980316,"lon":-1.8961030,"primary":""}]},
              {"pageid":124,"title":"No coordinates"},
              {"pageid":125,"title":"Invalid","coordinates":[{"lat":120.0,"lon":0.0}]}
            ]}}
            """.trimIndent(),
        )
        assertEquals(listOf("Ragley Hall"), results.map { it.name })
        assertEquals("Grade I listed historic house in Warwickshire, England", results.single().detail)
        assertEquals(52.1980316, results.single().latitude, 0.0000001)
        assertEquals(-1.8961030, results.single().longitude, 0.0000001)
        assertTrue(WikimediaGeocodingMapper.parse("{}").isEmpty())
    }

    @Test
    fun `weak possessive search invokes notable fallback and ranks its leading coordinate first`() = runBlocking {
        val empty = object : GeocodingEndpoint {
            override suspend fun search(query: String, language: String) = emptyList<PlaceSearchResult>()
        }
        val photon = object : GeocodingEndpoint {
            override suspend fun search(query: String, language: String) = listOf(
                PlaceSearchResult("Ragley Walk", "London, England", 51.51, -0.12),
            )
        }
        val notableQueries = mutableListOf<String>()
        val notable = object : GeocodingEndpoint {
            override suspend fun search(query: String, language: String): List<PlaceSearchResult> {
                notableQueries += query
                return listOf(PlaceSearchResult(
                    "Ragley Hall",
                    "Grade I listed historic house in Warwickshire, England",
                    52.1980316,
                    -1.8961030,
                ))
            }
        }
        val geocoder = PlaceGeocoder(empty, empty, photon, notable)
        assertEquals("Ragley Hall", geocoder.search("ragley estate's", "en").first().name)
        assertEquals("Ragley Hall", geocoder.search("ragley estate’s", "en").first().name)
        assertEquals(listOf("ragley estate's", "ragley estate’s"), notableQueries)
        assertFalse(PlaceSearchMerger.hasStrongMatch(
            "ragley estate's",
            listOf(PlaceSearchResult("Ragley Walk", "London", 51.51, -0.12)),
        ))
        assertTrue(PlaceSearchMerger.hasStrongMatch(
            "ragley estate’s",
            listOf(PlaceSearchResult("Ragley Estate", "Warwickshire", 52.2, -1.9)),
        ))
    }

    @Test
    fun `strong primary skips Wikimedia while fallback failure preserves primary results`() = runBlocking {
        var notableCalls = 0
        val notableFailure = object : GeocodingEndpoint {
            override suspend fun search(query: String, language: String): List<PlaceSearchResult> {
                notableCalls += 1
                error("Wikimedia unavailable")
            }
        }
        val empty = object : GeocodingEndpoint {
            override suspend fun search(query: String, language: String) = emptyList<PlaceSearchResult>()
        }
        val strongTown = object : GeocodingEndpoint {
            override suspend fun search(query: String, language: String) = listOf(
                PlaceSearchResult("London", "England", 51.5, -0.1),
            )
        }
        assertEquals(
            "London",
            PlaceGeocoder(strongTown, empty, empty, notableFailure).search("London", "en").single().name,
        )
        assertEquals(0, notableCalls)

        val weakPhoton = object : GeocodingEndpoint {
            override suspend fun search(query: String, language: String) = listOf(
                PlaceSearchResult("Ragley Walk", "London", 51.51, -0.12),
            )
        }
        assertEquals(
            "Ragley Walk",
            PlaceGeocoder(empty, empty, weakPhoton, notableFailure)
                .search("ragley estate's", "en").single().name,
        )
        assertEquals(1, notableCalls)
    }

    @Test
    fun `Wikimedia bounds requests and shares possessive-normalized cache entries`() = runBlocking {
        var requests = 0
        val geocoder = WikimediaGeocoder(httpClient = GeocodingHttpClient { url, connect, read ->
            requests += 1
            assertTrue(url.contains("gsrsearch=ragley+estate"))
            assertEquals(4_000, connect)
            assertEquals(6_000, read)
            GeocodingHttpResponse(
                200,
                """{"query":{"pages":[{"title":"Ragley Hall","coordinates":[{"lat":52.1980316,"lon":-1.896103}]}]}}""",
            )
        })
        val straight = async { geocoder.search("ragley estate's", "en") }
        val curly = async { geocoder.search("ragley estate’s", "cy") }
        assertEquals("Ragley Hall", straight.await().single().name)
        assertEquals("Ragley Hall", curly.await().single().name)
        assertEquals(1, requests)
    }

    @Test
    fun `named-place and town results merge while either endpoint may fail`() = runBlocking {
        val town = object : GeocodingEndpoint {
            override suspend fun search(query: String, language: String) =
                listOf(PlaceSearchResult("Alcester", "Warwickshire", 52.216, -1.87))
        }
        val named = object : GeocodingEndpoint {
            override suspend fun search(query: String, language: String) = listOf(
                PlaceSearchResult("Ragley Hall", "Warwickshire, England", 52.1980316, -1.8961030),
                PlaceSearchResult("Ragley Park", "Warwickshire, England", 52.2001, -1.9001),
            )
        }
        val postcode = object : GeocodingEndpoint {
            override suspend fun search(query: String, language: String) = emptyList<PlaceSearchResult>()
        }
        val notable = object : GeocodingEndpoint {
            override suspend fun search(query: String, language: String) = emptyList<PlaceSearchResult>()
        }
        val merged = PlaceGeocoder(town, postcode, named, notable)
            .search("Ragley Estate Warwickshire", "en")
        assertEquals("Ragley Hall", merged.first().name)
        assertTrue(merged.any { it.name == "Ragley Park" })
        assertTrue(merged.any { it.name == "Alcester" })

        val failed = object : GeocodingEndpoint {
            override suspend fun search(query: String, language: String): List<PlaceSearchResult> =
                error("endpoint unavailable")
        }
        assertEquals(
            listOf("Ragley Hall", "Ragley Park"),
            PlaceGeocoder(failed, postcode, named, notable)
                .search("Ragley Estate Warwickshire", "en").map { it.name },
        )
        assertEquals(
            listOf("Alcester"),
            PlaceGeocoder(town, postcode, failed, notable)
                .search("Ragley Estate Warwickshire", "en").map { it.name },
        )
        assertTrue(runCatching {
            PlaceGeocoder(failed, postcode, failed, failed).search("Ragley", "en")
        }.isFailure)
    }

    @Test
    fun `Photon bounds requests and shares cached identical searches`() = runBlocking {
        var requests = 0
        val geocoder = PhotonGeocoder(httpClient = GeocodingHttpClient { url, connect, read ->
            requests += 1
            assertTrue(url.contains("limit=6"))
            assertEquals(5_000, connect)
            assertEquals(7_000, read)
            GeocodingHttpResponse(
                200,
                """{"features":[{"properties":{"name":"Ragley Hall"},"geometry":{"coordinates":[-1.896103,52.1980316]}}]}""",
            )
        })
        (1..3).map { async { geocoder.search("Ragley Estate", "en") } }.awaitAll()
            .forEach { assertEquals("Ragley Hall", it.single().name) }
        assertEquals("Ragley Hall", geocoder.search(" ragley estate ", "EN").single().name)
        assertEquals(1, requests)
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
                "51.5074/-0.1278/2/1_0.png",
            detailUrl,
        )
        assertEquals(
            "https://tile-cache.rainviewer.com/v2/radar/1789378200/512/7/51.5074/-0.1278/2/1_1.png",
            buildCoordinateRadarUrl(
                "https://tile-cache.rainviewer.com", frame, DEFAULT_PLACE,
                zoom = 7, imageSize = 512, showLikelySnow = true,
            ),
        )
        val regionalUrl = buildCoordinateRadarUrl(
            "https://tilecache.rainviewer.com",
            frame,
            SavedPlace("Here", 51.5074, -0.1278),
            zoom = 5,
            imageSize = 512,
        )
        assertTrue(regionalUrl.contains("/512/5/51.5074/-0.1278/"))
        assertEquals(
            "https://tilecache.rainviewer.com/v2/coverage/0/512/7/51.5074/-0.1278/0/0_0.png",
            buildCoordinateCoverageUrl(
                "https://tilecache.rainviewer.com/",
                SavedPlace("Here", 51.5074, -0.1278),
            ),
        )
        assertFalse(detailUrl.contains("{z}"))
        assertThrows(IllegalArgumentException::class.java) {
            buildCoordinateRadarUrl("http://example.invalid", frame, DEFAULT_PLACE)
        }
        assertThrows(IllegalArgumentException::class.java) {
            buildCoordinateCoverageUrl("http://example.invalid", DEFAULT_PLACE)
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
