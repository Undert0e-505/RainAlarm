package com.rainalarm.app.data

import com.rainalarm.app.domain.GeoPoint
import com.rainalarm.app.domain.MeteoNominalCoverage
import java.io.File
import java.io.IOException
import java.time.Instant
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LflLightningFeedTest {
    private val observedFrom = Instant.parse("2026-10-02T10:00:00Z").epochSecond
    private val observedThrough = Instant.parse("2026-10-02T11:30:00Z").epochSecond
    private val freshNow = Instant.parse("2026-10-02T11:31:00Z").epochSecond

    @Test fun lightningVisualPreferenceDefaultsUnsetAndUnknownValuesToLflAndRoundTrips() {
        assertEquals(
            LightningVisualProviderKind.EUMETSAT_INDIVIDUAL_FLASHES,
            LightningVisualProviderPreference.decode(null),
        )
        assertEquals(
            LightningVisualProviderKind.EUMETSAT_INDIVIDUAL_FLASHES,
            LightningVisualProviderPreference.decode("removed-provider"),
        )
        LightningVisualProviderKind.entries.forEach { provider ->
            assertEquals(
                provider,
                LightningVisualProviderPreference.decode(
                    LightningVisualProviderPreference.encode(provider),
                ),
            )
        }
    }

    @Test fun regionMappingUsesTheExistingPlaceOwnedRadarSelection() {
        listOf(
            Triple(51.70, 0.50, "uk"),
            Triple(48.86, 2.35, "fr"),
            Triple(52.37, 4.90, "nl"),
            Triple(50.11, 8.68, "de"),
            Triple(46.95, 7.45, "ch"),
        ).forEach { (latitude, longitude, expected) ->
            val place = SavedPlace(
                name = expected, latitude = latitude, longitude = longitude, id = expected,
            )
            assertEquals(expected, LflRegionPolicy.regionFor(place)?.id)
            assertTrue(LflRegionPolicy.endpoint(expected).endsWith("/v1/regions/$expected"))
        }
        assertEquals(null, LflRegionPolicy.regionFor(SavedPlace(
            name = "Tokyo", latitude = 35.0, longitude = 139.0, id = "jp",
        )))
    }

    @Test fun meteoLflSourceUsesActiveFranceNominalCoverageNotFeedRectangleOrFeedId() {
        val insideFrance = LflFlashPoint(
            "000000000000000000000101", observedThrough * 1_000L - 1L, 48.8566, 2.3522,
        )
        // Inside the broad France feed rectangle, but outside MeteoGroup's nominal France reach.
        val outsideInSpain = LflFlashPoint(
            "000000000000000000000102", observedThrough * 1_000L - 1L, 40.0, -7.0,
        )
        assertEquals(true, MeteoNominalCoverage.covers("fr", GeoPoint(
            insideFrance.latitude, insideFrance.longitude,
        )))
        assertEquals(false, MeteoNominalCoverage.covers("fr", GeoPoint(
            outsideInSpain.latitude, outsideInSpain.longitude,
        )))

        // Deliberately retain the helper's UK feed id: the active radar area must be authoritative.
        val source = LflLightningPresentationPolicy.source(
            feed(listOf(insideFrance, outsideInSpain)),
            LflLightningCoverageContext(RadarProviderKind.METEOGROUP_REGIONAL, "fr"),
        )
        assertEquals(listOf(insideFrance.id), source.flashes.map { it.point.id })
    }

    @Test fun nonMeteoAndUnknownCoverageRemainFailOpen() {
        val outsideFrance = LflFlashPoint(
            "000000000000000000000103", observedThrough * 1_000L - 1L, 40.0, -7.0,
        )
        val sourceFeed = feed(listOf(outsideFrance)).copy(regionId = "fr")
        listOf(RadarProviderKind.EUMETNET_OPERA, RadarProviderKind.OPEN_RAINVIEWER).forEach {
            val source = LflLightningPresentationPolicy.source(
                sourceFeed, LflLightningCoverageContext(it, "fr"),
            )
            assertEquals(listOf(outsideFrance.id), source.flashes.map { flash -> flash.point.id })
        }
        listOf(null, "unsupported").forEach { areaId ->
            val source = LflLightningPresentationPolicy.source(
                sourceFeed,
                LflLightningCoverageContext(RadarProviderKind.METEOGROUP_REGIONAL, areaId),
            )
            assertEquals(listOf(outsideFrance.id), source.flashes.map { it.point.id })
        }
    }

    @Test fun providerAndActiveAreaChangesInvalidateStableLflSourceIndependentOfMaskDarkness() {
        val sourceFeed = feed(listOf(point(
            "000000000000000000000104", observedThrough * 1_000L - 1L,
        ))).copy(regionId = "fr")
        val meteoFrance = LflLightningPresentationPolicy.source(
            sourceFeed,
            LflLightningCoverageContext(RadarProviderKind.METEOGROUP_REGIONAL, "fr"),
        )
        val meteoGermany = LflLightningPresentationPolicy.source(
            sourceFeed,
            LflLightningCoverageContext(RadarProviderKind.METEOGROUP_REGIONAL, "de"),
        )
        val operaFrance = LflLightningPresentationPolicy.source(
            sourceFeed,
            LflLightningCoverageContext(RadarProviderKind.EUMETNET_OPERA, "fr"),
        )
        val rainViewerFrance = LflLightningPresentationPolicy.source(
            sourceFeed,
            LflLightningCoverageContext(RadarProviderKind.OPEN_RAINVIEWER, "fr"),
        )
        assertTrue(setOf(
            meteoFrance.identity,
            meteoGermany.identity,
            operaFrance.identity,
            rainViewerFrance.identity,
        ).size == 4)
        assertTrue(meteoFrance.coverageIdentity.contains(MeteoNominalCoverage.GEOMETRY_VERSION))

        // Visibility is source-data policy and has no dependency on the optional visual scrim.
        val policySource = source("data/LflLightningFeed.kt")
        assertFalse(policySource.contains("CoverageMaskDarknessPreference"))
        assertFalse(policySource.contains("coverageMaskDarkness"))
    }

    @Test fun parserAcceptsExactSchemaValidEmptyAndDeduplicatesStableIdentities() {
        val empty = LflFeedParser.parse(fixture(), "uk")
        assertEquals(LflFeedStatus.VALID_EMPTY, empty.status)
        assertTrue(empty.points.isEmpty())
        assertTrue(empty.isFreshAt(freshNow))

        val duplicate = tuple("00112233445566778899aabb", observedThrough * 1_000L - 1L)
        val populated = LflFeedParser.parse(fixture(
            pointsByFrame = mapOf(17 to listOf(duplicate, duplicate)),
        ), "uk")
        assertEquals(LflFeedStatus.VALID, populated.status)
        assertEquals(1, populated.points.size)
        assertEquals("00112233445566778899aabb", populated.points.single().id)
    }

    @Test fun parserRejectsSchemaRegionBoundsOrderingAndConflictingIdentityViolations() {
        rejects(fixture().decodeToString().replaceFirst(
            "{\"schemaVersion\"", "{\"unexpected\":1,\"schemaVersion\"",
        ))
        rejects(fixture().decodeToString().replace("\"intervalMinutes\":5", "\"intervalMinutes\":4"))
        rejects(fixture(), expectedRegion = "fr")
        rejects(fixture().decodeToString().replaceFirst(
            Regex("\\\"north\\\":-?[0-9.]+"), "\"north\":0.0",
        ))

        val first = tuple("00112233445566778899aabb", observedThrough * 1_000L - 1L)
        val second = tuple("00112233445566778899aabb", observedThrough * 1_000L - 2L)
        rejects(fixture(pointsByFrame = mapOf(17 to listOf(first, second))))
        val changedLocation = tuple(
            "00112233445566778899aabb", observedThrough * 1_000L - 1L, 51.8, 0.5,
        )
        rejects(fixture(pointsByFrame = mapOf(17 to listOf(first, changedLocation))))
    }

    @Test fun repositoryDistinguishesValidEmptyPartialStaleAndTransientUnavailable() = runBlocking {
        val emptyTransport = QueueTransport(mutableListOf(response(fixture(), "empty-etag")))
        val emptyRepository = LflLightningRepository(emptyTransport)
        val emptyFeed = emptyRepository.feed("uk", freshNow)
        assertEquals(LflFeedStatus.VALID_EMPTY, emptyFeed.status)
        val emptyPresentation = LflLightningPresentationPolicy.at(emptyFeed, observedThrough)
        assertTrue(emptyPresentation.covered)
        assertTrue(emptyPresentation.source.flashes.isEmpty())
        assertTrue(LflLightningPresentationPolicy.renderedFlashes(emptyPresentation).isEmpty())
        assertEquals(1, emptyTransport.requests.size)
        // A second normal acquisition in the same minute reuses the verified response.
        assertEquals(LflFeedStatus.VALID_EMPTY, emptyRepository.feed("uk", freshNow + 30).status)
        assertEquals(1, emptyTransport.requests.size)

        val partial = LflLightningRepository(QueueTransport(mutableListOf(response(
            fixture(topStatus = "partial", lastFrameStatus = "unknown"),
        ))))
        expectsIOException { partial.feed("uk", freshNow) }

        val stale = LflLightningRepository(QueueTransport(mutableListOf(response(
            fixture(topStatus = "stale"),
        ))))
        expectsIOException { stale.feed("uk", freshNow) }

        val missingHeaders = LflLightningRepository(QueueTransport(mutableListOf(
            LflTransportResponse(
                200, "application/json", "gzip", null, null, null, null, fixture(),
            ),
        )))
        expectsFailure { missingHeaders.feed("uk", freshNow) }

        val fallbackTransport = QueueTransport(mutableListOf(
            response(fixture(), "generation-one"),
            LflTransportResponse(503, "text/plain", null, null, null, null, null, byteArrayOf()),
        ))
        val fallback = LflLightningRepository(fallbackTransport)
        val first = fallback.feed("uk", freshNow)
        assertEquals(first, fallback.feed("uk", freshNow + 61, force = true))
        assertEquals("generation-one", fallbackTransport.requests.last().etag)
    }

    @Test fun repositoryUsesConditionalRequestsAndNeverReplacesNewerGoodData() = runBlocking {
        val newer = fixture(generationSuffix = "1111111111111111")
        val older = fixture(
            observedFromSeconds = observedFrom - 300,
            generationTimeSeconds = observedThrough - 300,
            generationSuffix = "0000000000000000",
        )
        val transport = QueueTransport(mutableListOf(
            response(newer, "newer-etag"),
            response(older, "older-etag"),
            LflTransportResponse(304, null, null, "newer-etag", null, null, null, byteArrayOf()),
        ))
        val repository = LflLightningRepository(transport)
        val first = repository.feed("uk", freshNow)
        assertEquals(first, repository.feed("uk", freshNow + 1, force = true))
        assertEquals("newer-etag", transport.requests[1].etag)
        assertEquals(first, repository.feed("uk", freshNow + 2, force = true))
    }

    @Test fun utcBirthBinsContinuousAgeHistoricalScrubbingAndPostCompletionFadeAreExact() {
        val at112730 = Instant.parse("2026-10-02T11:27:30Z").toEpochMilli()
        val feed = feed(listOf(
            point("000000000000000000000001", at112730),
            point("000000000000000000000002", at112730 + 1),
            point("000000000000000000000003", Instant.parse("2026-10-02T11:10:00Z").toEpochMilli()),
            point("000000000000000000000004", Instant.parse("2026-10-02T11:10:00.001Z").toEpochMilli()),
            point("000000000000000000000005", Instant.parse("2026-10-02T11:19:59Z").toEpochMilli()),
        ))

        val beforeBoundary = LflLightningPresentationPolicy.at(
            feed, Instant.parse("2026-10-02T11:29:59Z").epochSecond,
        )
        assertEquals(Instant.parse("2026-10-02T11:29:59Z").epochSecond.toDouble(),
            beforeBoundary.referenceEpochSeconds, 0.0)
        val beforeFlashes = LflLightningPresentationPolicy.renderedFlashes(beforeBoundary)
        assertTrue(beforeFlashes.any { it.point.id.endsWith("1") && it.ageSeconds == 149.0 })
        assertFalse(beforeFlashes.any { it.point.id.endsWith("2") })

        val atBoundary = LflLightningPresentationPolicy.at(feed, observedThrough)
        val boundaryFlashes = LflLightningPresentationPolicy.renderedFlashes(atBoundary)
        assertTrue(boundaryFlashes.any { it.point.id.endsWith("2") && it.ageSeconds == 0.0 })
        assertFalse(boundaryFlashes.any { it.point.id.endsWith("3") })
        assertTrue(boundaryFlashes.any { it.point.id.endsWith("4") && it.ageSeconds == 1_050.0 })

        val historical = LflLightningPresentationPolicy.at(
            feed, Instant.parse("2026-10-02T11:20:00Z").epochSecond,
        )
        val historicalFlashes = LflLightningPresentationPolicy.renderedFlashes(historical)
        assertTrue(historicalFlashes.any { it.point.id.endsWith("5") })
        assertFalse(historicalFlashes.any { it.point.id.endsWith("1") })

        val nextFutureCohort = LflLightningPresentationPolicy.at(
            feed, Instant.parse("2026-10-02T11:32:30Z").epochSecond,
        )
        val futureFlashes = LflLightningPresentationPolicy.renderedFlashes(nextFutureCohort)
        assertEquals(
            boundaryFlashes.single { it.point.id.endsWith("2") }.ageSeconds + 150.0,
            futureFlashes.single { it.point.id.endsWith("2") }.ageSeconds,
            0.0,
        )
        assertTrue(futureFlashes.all {
            it.point.observedAtEpochMs <= feed.completeThroughEpochSeconds * 1_000L
        })
        val afterLifetime = LflLightningPresentationPolicy.at(
            feed, Instant.parse("2026-10-02T12:30:00Z").epochSecond,
        )
        assertTrue(afterLifetime.covered)
        assertTrue(LflLightningPresentationPolicy.renderedFlashes(afterLifetime).isEmpty())

        val uncovered = LflLightningPresentationPolicy.at(feed, observedFrom + 19 * 60L)
        assertFalse(uncovered.covered)
        assertTrue(LflLightningPresentationPolicy.renderedFlashes(uncovered).isEmpty())
    }

    @Test fun opacityColourEdgeSofteningAndZoomAreContinuousCentralizedAndMonotonic() {
        assertEquals(150L, LflLightningVisualStyle.visualIntervalSeconds)
        assertEquals(1_200L, LflLightningVisualStyle.lifetimeSeconds)
        assertEquals(8, LflLightningVisualStyle.visibleCohorts)
        assertEquals(5f, LflLightningVisualStyle.coreDiameterDp, 0f)
        assertEquals(11f, LflLightningVisualStyle.haloDiameterDp, 0f)
        assertEquals(2.4f, LflLightningVisualStyle.innerGlowDiameterDp, 0f)
        assertEquals(1.1f, LflLightningVisualStyle.pinpointDiameterDp, 0f)
        assertEquals(0.12f, LflLightningVisualStyle.zoomScaleReductionPerLevel, 0f)
        assertEquals(1f, LflLightningVisualStyle.zoomScale(10f), 0.0001f)
        assertEquals(0.76f, LflLightningVisualStyle.zoomScale(8f), 0.0001f)
        assertEquals(0.52f, LflLightningVisualStyle.zoomScale(6f), 0.0001f)
        assertEquals(0.40f, LflLightningVisualStyle.zoomScale(5f), 0.0001f)
        assertEquals(0.25f, LflLightningVisualStyle.zoomScale(-10f), 0.0001f)
        assertEquals(1f, LflLightningVisualStyle.opacity(0.0), 0f)
        assertEquals(0.5f, LflLightningVisualStyle.opacity(600.0), 0f)
        assertEquals(0.125f, LflLightningVisualStyle.opacity(1_050.0), 0f)
        assertEquals(0f, LflLightningVisualStyle.opacity(1_200.0), 0f)
        val expectedColors = listOf(
            0xFFFFE45C.toInt(), 0xFFFFD33F.toInt(), 0xFFFFBD2E.toInt(),
            0xFFFFA21F.toInt(), 0xFFF78016.toInt(), 0xFFE85F18.toInt(),
            0xFFCF3F22.toInt(), 0xFFA9272D.toInt(),
        )
        assertEquals(expectedColors, (0..7).map {
            LflLightningVisualStyle.color(it * 150.0)
        })
        fun luminance(argb: Int): Double =
            0.2126 * (argb ushr 16 and 0xff) + 0.7152 * (argb ushr 8 and 0xff) +
                0.0722 * (argb and 0xff)
        assertTrue(expectedColors.zipWithNext().all { (fresh, older) ->
            luminance(older) < luminance(fresh)
        })
        val expectedBlur = listOf(0.08f, 0.10f, 0.14f, 0.19f, 0.25f, 0.33f, 0.42f, 0.52f)
        assertEquals(expectedBlur, (0..7).map {
            LflLightningVisualStyle.coreBlur(it * 150.0)
        })
        assertTrue(expectedBlur.zipWithNext().all { (fresh, older) -> older > fresh })

        // Every interval midpoint must interpolate rather than wait for the next cohort step.
        (0 until 7).forEach { index ->
            val midpointAge = index * 150.0 + 75.0
            assertEquals(
                midpointArgb(expectedColors[index], expectedColors[index + 1]),
                LflLightningVisualStyle.color(midpointAge),
            )
            assertEquals(
                (expectedBlur[index] + expectedBlur[index + 1]) / 2f,
                LflLightningVisualStyle.coreBlur(midpointAge),
                0.0001f,
            )
            assertEquals(
                1f - midpointAge.toFloat() / 1_200f,
                LflLightningVisualStyle.opacity(midpointAge),
                0.0001f,
            )
        }
        assertEquals(expectedColors.last(), LflLightningVisualStyle.color(1_125.0))
        assertEquals(expectedBlur.last(), LflLightningVisualStyle.coreBlur(1_125.0), 0f)
    }

    @Test fun exactReferenceBirthLifetimeAndStableSourceIdentityArePreserved() {
        val observed = point(
            "000000000000000000000006",
            Instant.parse("2026-10-02T11:27:30.001Z").toEpochMilli(),
        )
        val sourceFeed = feed(listOf(observed))
        val source = LflLightningPresentationPolicy.source(sourceFeed)
        assertEquals(Instant.parse("2026-10-02T11:30:00Z").epochSecond - observedFrom,
            source.flashes.single().birthOffsetSeconds.toLong())

        val justBeforeBirth = Instant.parse("2026-10-02T11:29:59.999Z").toEpochMilli() / 1_000.0
        val atBirth = Instant.parse("2026-10-02T11:30:00Z").epochSecond.toDouble()
        assertTrue(LflLightningPresentationPolicy.renderedFlashes(
            LflLightningPresentationPolicy.frame(source, justBeforeBirth),
        ).isEmpty())
        val newborn = LflLightningPresentationPolicy.renderedFlashes(
            LflLightningPresentationPolicy.frame(source, atBirth),
        ).single()
        assertEquals(0.0, newborn.ageSeconds, 0.0)
        assertEquals(1f, newborn.opacity, 0f)

        val fractionalReference = atBirth + 37.25
        assertEquals(
            fractionalReference,
            LflLightningPresentationPolicy.referenceEpochSeconds(sourceFeed, fractionalReference),
            0.0,
        )
        val laterFrame = LflLightningPresentationPolicy.frame(source, fractionalReference)
        assertSame(source, laterFrame.source)
        assertEquals(source.identity, laterFrame.source.identity)
        assertEquals(37.25, laterFrame.referenceOffsetSeconds -
            (atBirth - source.baselineEpochSeconds), 0.0001)

        val justBeforeExpiry = LflLightningPresentationPolicy.renderedFlashes(
            LflLightningPresentationPolicy.frame(source, atBirth + 1_199.999),
        ).single()
        assertTrue(justBeforeExpiry.opacity > 0f)
        assertTrue(LflLightningPresentationPolicy.renderedFlashes(
            LflLightningPresentationPolicy.frame(source, atBirth + 1_200.0),
        ).isEmpty())
    }

    @Test fun flashCoordinatesRemainAtTheirObservedLocationForEveryAge() {
        val observed = point(
            "000000000000000000000006",
            Instant.parse("2026-10-02T11:27:30Z").toEpochMilli(),
        )
        val sourceFeed = feed(listOf(observed))
        listOf(
            Instant.parse("2026-10-02T11:27:30Z").epochSecond,
            Instant.parse("2026-10-02T11:35:00Z").epochSecond,
            Instant.parse("2026-10-02T11:45:00Z").epochSecond,
        ).forEach { selected ->
            val rendered = LflLightningPresentationPolicy.renderedFlashes(
                LflLightningPresentationPolicy.at(sourceFeed, selected),
            ).single()
            assertEquals(observed, rendered.point)
            assertEquals(51.7, rendered.point.latitude, 0.0)
            assertEquals(0.5, rendered.point.longitude, 0.0)
        }
    }

    @Test fun appAndWidgetNotificationPipelinesRemainIndependentAndAfaBacked() {
        val monitor = source("alerts/WeatherMonitoringCoordinator.kt")
        val radar = source("ui/RadarScreen.kt")
        val map = source("ui/RadarImageMap.kt")
        val settings = source("ui/SettingsScreen.kt")
        val providers = source("data/RadarProviders.kt")
        val main = source("MainActivity.kt")
        val monitoringSources = listOf("alerts", "widget").flatMap { directory ->
            listOf(
                File("src/main/java/com/rainalarm/app/$directory"),
                File("app/src/main/java/com/rainalarm/app/$directory"),
            ).first(File::isDirectory).walkTopDown().filter(File::isFile).map(File::readText).toList()
        }.joinToString("\n")
        assertFalse(monitoringSources.contains("LflLightning"))
        assertFalse(monitoringSources.contains("LightningVisualProviderKind"))
        assertTrue(monitor.contains("LightningObservationRepository"))
        assertTrue(settings.contains("settings_lightning_provider_heading"))
        assertTrue(providers.contains("stringPreferencesKey(\"lightning_visual_provider\")"))
        assertTrue(providers.contains("setLightningVisualProvider"))
        assertTrue(main.contains("selectedLightningProvider = lightningVisualProvider"))
        assertTrue(radar.contains("lightningVisualProvider"))
        assertTrue(radar.contains("key(lightningVisualProvider)"))
        assertTrue(radar.contains("RadarMapLayer.LIGHTNING, lightningVisualProvider"))
        assertTrue(radar.contains("LflLightningRepositoryProvider"))
        assertTrue(radar.contains("EumetViewRepository.metadata("))
        assertTrue(map.contains("lflLightningPresentation"))

        val layer = source("ui/LflLightningMapLayer.kt")
        assertTrue(layer.contains("Expression.zoom()"))
        assertTrue(layer.contains("blurExpression(age)"))
        assertTrue(layer.contains("opacityExpression(age)"))
        assertTrue(layer.contains("colorExpression(age)"))
        assertTrue(layer.contains("BIRTH_OFFSET_PROPERTY"))
        assertTrue(layer.contains("if (sourceChanged)"))
        assertTrue(layer.contains("setGeoJson(featureCollection(presentation))"))
        assertTrue(layer.contains("updateTimeExpressions"))
        assertTrue(layer.contains("Point.fromLngLat(sourceFlash.point.longitude, sourceFlash.point.latitude)"))
        assertTrue(layer.contains("LflLightningMapLayerIds.innerGlow"))
        assertTrue(layer.contains("LflLightningMapLayerIds.pinpoint"))
        assertTrue(radar.contains("timelineStartEpochSeconds.toDouble() + safeCursor.toDouble()"))
        assertFalse(radar.contains("lflReferenceEpochSeconds"))
        assertFalse(radar.contains("atCancellable"))
        assertTrue(map.contains("SideEffect"))
        val coreOrder = layer.indexOf(
            "style.addLayerAbove(core, LflLightningMapLayerIds.halo)",
        )
        val glowOrder = layer.indexOf(
            "style.addLayerAbove(innerGlow, LflLightningMapLayerIds.core)",
        )
        val pinOrder = layer.indexOf(
            "style.addLayerAbove(pinpoint, LflLightningMapLayerIds.innerGlow)",
        )
        assertTrue(coreOrder >= 0 && glowOrder > coreOrder && pinOrder > glowOrder)
    }

    private fun feed(points: List<LflFlashPoint>) = LflLightningFeed(
        "20261002T113000Z-0123456789abcdef", "uk", LflFeedStatus.VALID,
        observedFrom, observedThrough, observedThrough, observedThrough + 720,
        points, "Contains modified EUMETSAT Meteosat Third Generation LI-2-LFL data, 2026",
    )

    private fun point(id: String, epochMs: Long) = LflFlashPoint(id, epochMs, 51.7, 0.5)

    private fun midpointArgb(first: Int, second: Int): Int {
        fun channel(shift: Int): Int {
            val start = first ushr shift and 0xff
            val end = second ushr shift and 0xff
            return ((start + end) / 2.0).roundToInt()
        }
        return (channel(24) shl 24) or (channel(16) shl 16) or
            (channel(8) shl 8) or channel(0)
    }

    private fun tuple(
        id: String,
        epochMs: Long,
        latitude: Double = 51.7,
        longitude: Double = 0.5,
    ) = "[\"$id\",$epochMs,$latitude,$longitude]"

    private fun fixture(
        topStatus: String? = null,
        lastFrameStatus: String? = null,
        pointsByFrame: Map<Int, List<String>> = emptyMap(),
        observedFromSeconds: Long = observedFrom,
        generationTimeSeconds: Long = observedThrough,
        generationSuffix: String = "0123456789abcdef",
    ): ByteArray {
        val area = requireNotNull(RegionalRadarAreas.all.firstOrNull { it.id == "uk" })
        val through = observedFromSeconds + 90L * 60L
        val frames = (0 until 18).map { index ->
            val start = observedFromSeconds + index * 300L
            val end = start + 300L
            val points = pointsByFrame[index].orEmpty()
            val requested = if (index == 17) lastFrameStatus else null
            val status = requested ?: if (points.isEmpty()) "valid-empty" else "valid"
            val complete = status == "valid" || status == "valid-empty"
            """{"start":"${iso(start)}","end":"${iso(end)}","complete":$complete,"status":"$status","count":${points.size},"points":[${points.joinToString(",")}]}"""
        }
        val pointCount = pointsByFrame.values.sumOf(List<String>::size)
        val statuses = frames.indices.map { index ->
            if (index == 17 && lastFrameStatus != null) lastFrameStatus
            else if (pointsByFrame[index].isNullOrEmpty()) {
                "valid-empty"
            } else "valid"
        }
        val completeFrames = statuses.count { it == "valid" || it == "valid-empty" }
        val partialFrames = statuses.count { it == "partial" }
        val unknownFrames = statuses.count { it == "unknown" }
        val emptyFrames = statuses.count { it == "valid-empty" }
        val status = topStatus ?: if (pointCount == 0) "valid-empty" else "valid"
        return """
            {"schemaVersion":1,"generationId":"${Instant.ofEpochSecond(generationTimeSeconds)
                .toString().replace("-", "").replace(":", "").replace("T", "T")
                .substring(0, 15)}Z-$generationSuffix","regionId":"uk",
            "bounds":{"north":${area.north},"west":${area.west},"south":${area.south},"east":${area.east}},
            "intervalMinutes":5,"historyMinutes":90,"generatedAt":"${iso(through + 30)}",
            "observedFrom":"${iso(observedFromSeconds)}","observedThrough":"${iso(through)}",
            "completeThrough":"${iso(through)}","staleAfter":"${iso(through + 720)}","status":"$status",
            "correction":{"applied":false,"mode":"none","version":"raw-centroids-v1"},
            "provenance":{"collectionId":"EO:EUM:DAT:0691","observationType":"total-lightning flash centroids","sourceBatchMinutes":10,"typicalPublicationLatencySeconds":45,"intervalSemantics":"UTC half-open [start,end)","pointFields":["id","observedAtEpochMs","latitude","longitude"]},
            "attribution":"Contains modified EUMETSAT Meteosat Third Generation LI-2-LFL data, 2026",
            "counts":{"points":$pointCount,"frames":18,"completeFrames":$completeFrames,"partialFrames":$partialFrames,"unknownFrames":$unknownFrames,"validEmptyFrames":$emptyFrames},
            "frames":[${frames.joinToString(",")}]}
        """.trimIndent().toByteArray()
    }

    private fun response(bytes: ByteArray, etag: String = "etag"): LflTransportResponse {
        val feed = LflFeedParser.parse(bytes, "uk")
        val status = when (feed.status) {
            LflFeedStatus.VALID -> "valid"
            LflFeedStatus.VALID_EMPTY -> "valid-empty"
            LflFeedStatus.PARTIAL -> "partial"
            LflFeedStatus.STALE -> "stale"
        }
        return LflTransportResponse(
            200, "application/json", "gzip", etag, feed.generationId,
            iso(feed.completeThroughEpochSeconds), status, bytes,
        )
    }

    private fun iso(epochSeconds: Long): String = Instant.ofEpochSecond(epochSeconds).toString()

    private fun rejects(bytes: ByteArray, expectedRegion: String = "uk") =
        rejects(bytes.decodeToString(), expectedRegion)

    private fun rejects(text: String, expectedRegion: String = "uk") {
        try {
            LflFeedParser.parse(text.toByteArray(), expectedRegion)
            fail("Expected payload rejection")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    private suspend fun expectsIOException(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected unavailable feed")
        } catch (_: IOException) {
            // expected
        }
    }

    private suspend fun expectsFailure(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected invalid feed")
        } catch (_: Exception) {
            // expected
        }
    }

    private fun source(relative: String): String = listOf(
        File("src/main/java/com/rainalarm/app/$relative"),
        File("app/src/main/java/com/rainalarm/app/$relative"),
    ).first(File::isFile).readText()

    private class QueueTransport(
        val responses: MutableList<LflTransportResponse>,
    ) : LflFeedTransport {
        val requests = mutableListOf<LflTransportRequest>()
        override suspend fun fetch(request: LflTransportRequest): LflTransportResponse {
            requests += request
            return responses.removeAt(0)
        }
    }
}
