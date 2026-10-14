package com.rainalarm.app.ui

import com.rainalarm.app.data.LegacyCompressedFrame
import com.rainalarm.app.data.LegacyRadarArchive
import com.rainalarm.app.data.RadarPointSample
import com.rainalarm.app.data.RadarCoverageSemantics
import com.rainalarm.app.data.RadarEffectiveCoverage
import com.rainalarm.app.data.RadarProviderKind
import com.rainalarm.app.data.RadarProviderSelection
import com.rainalarm.app.data.RadarSession
import com.rainalarm.app.data.RadarTierFrames
import com.rainalarm.app.data.RainViewerFrame
import com.rainalarm.app.data.RegionalRadarAreas
import com.rainalarm.app.data.SavedPlace
import com.rainalarm.app.domain.GeoPoint
import com.rainalarm.app.domain.GeoQuad
import com.rainalarm.app.domain.RadarResolutionTier
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadarLiveSessionPolicyTest {
    private fun current(lat: Double, lon: Double) = SavedPlace("Current", lat, lon, isCurrentLocation = true)

    private fun retainedRegionalSession(
        place: SavedPlace,
        activeProvider: RadarProviderKind,
        regionId: String,
    ): RadarSession {
        val frames = listOf(
            LegacyCompressedFrame(
                id = 0,
                frame = RainViewerFrame(1_000, "first", false),
                radarJpeg = byteArrayOf(1),
                velocityJpeg = null,
            ),
            LegacyCompressedFrame(
                id = 1,
                frame = RainViewerFrame(1_300, "second", false),
                radarJpeg = byteArrayOf(2),
                velocityJpeg = null,
            ),
        )
        return RadarSession(
            place = place,
            regional = null,
            detail = null,
            motion = null,
            pairMotions = listOf(null),
            providerSelection = RadarProviderSelection(
                requested = RadarProviderKind.METEOGROUP_REGIONAL,
                active = activeProvider,
            ),
            region = RegionalRadarAreas.all.first { it.id == regionId },
            legacyArchive = LegacyRadarArchive(
                frames = frames,
                pointSamples = frames.map { RadarPointSample(it.frame.time, false, 0f) },
                totalCompressedBytes = 2,
            ),
        )
    }

    private fun retainedOpenSession(
        place: SavedPlace,
        activeProvider: RadarProviderKind,
        detailBounds: GeoQuad,
        regionalBounds: GeoQuad = RegionalRadarAreas.all.first { it.id == "fr" }.bounds,
        regionId: String? = "fr",
        effectiveCoverage: RadarEffectiveCoverage = fullyCovered(regionalBounds),
    ): RadarSession = RadarSession(
        place = place,
        regional = RadarTierFrames(RadarResolutionTier.REGIONAL, regionalBounds, emptyList()),
        detail = RadarTierFrames(RadarResolutionTier.DETAIL, detailBounds, emptyList()),
        motion = null,
        pairMotions = emptyList(),
        providerSelection = RadarProviderSelection(activeProvider, activeProvider),
        region = regionId?.let { id -> RegionalRadarAreas.all.first { it.id == id } },
        effectiveCoverage = effectiveCoverage,
    )

    private fun bounds(north: Double, west: Double, south: Double, east: Double) = GeoQuad(
        GeoPoint(north, west), GeoPoint(north, east),
        GeoPoint(south, east), GeoPoint(south, west),
    )

    private fun fullyCovered(bounds: GeoQuad) = RadarEffectiveCoverage(
        bounds = bounds,
        width = 2,
        height = 2,
        unknownAlpha = ByteArray(4),
        semantics = RadarCoverageSemantics.CURRENT_COMPOSITE,
    )

    @Test fun currentFixesWithinRegionKeepCachedSessionAcrossMaterialMovement() {
        val loaded = current(53.4808, -2.2426)
        assertTrue(RadarLiveSessionPolicy.canReuse(loaded, current(53.486, -2.238), "uk"))
        assertFalse(RadarLiveSessionPolicy.canReuse(loaded, current(52.5200, 13.4050), "uk"))
        assertFalse(RadarLiveSessionPolicy.canReuse(
            current(51.70, 0.50), current(51.70, 0.50), "nl",
        )) // stale pre-fix NL session must reload even without a new fix
        assertTrue(RadarLiveSessionPolicy.canReuse(
            current(51.70, 0.50), current(51.71, 0.51), "uk",
        ))
        assertFalse(RadarLiveSessionPolicy.canReuse(
            current(51.70, 0.50), current(52.37, 4.90), "uk",
        ))
    }

    @Test fun firstResolvedVirtualCurrentFixStartsLoadButLaterMovementDoesNotCancelIt() {
        val initial = current(53.4808, -2.2426)
        assertNull(RadarLiveSessionPolicy.loadIdentity(null))
        assertEquals("current-location", RadarLiveSessionPolicy.loadIdentity(initial))
        assertEquals(RadarLiveSessionPolicy.loadIdentity(initial),
            RadarLiveSessionPolicy.loadIdentity(current(53.486, -2.238)))
    }

    @Test fun fiveHertzTravelFixesUseOneRegionalAcquisitionKey() {
        val fixes = (0 until 25).map { index ->
            current(51.5074 + index * 0.00001, -0.1278 + index * 0.00001)
        }
        assertEquals(1, fixes.map(RadarLiveSessionPolicy::acquisitionLocationKey).distinct().size)
        assertEquals("current:region:uk", RadarLiveSessionPolicy.acquisitionLocationKey(fixes.first()))
        assertEquals(
            "current:region:nl",
            RadarLiveSessionPolicy.acquisitionLocationKey(current(52.3676, 4.9041)),
        )
    }

    @Test fun savedSelectionsAlwaysReevaluateEvenInsideOneCoarseRegion() {
        val yepes = SavedPlace("Yepes", 39.9010, -3.6240)
        val castello = SavedPlace("Castelló", 39.9864, -0.0513)

        assertEquals("fr", RegionalRadarAreas.forPoint(yepes.latitude, yepes.longitude)?.id)
        assertEquals("fr", RegionalRadarAreas.forPoint(castello.latitude, castello.longitude)?.id)
        assertNotEquals(
            RadarLiveSessionPolicy.acquisitionLocationKey(yepes),
            RadarLiveSessionPolicy.acquisitionLocationKey(castello),
        )

        val repeated = yepes.copy()
        val editedSameId = yepes.copy(latitude = 41.3851, longitude = 2.1734)
        assertEquals(
            RadarLiveSessionPolicy.acquisitionLocationKey(yepes),
            RadarLiveSessionPolicy.acquisitionLocationKey(repeated),
        )
        assertNotEquals(
            RadarLiveSessionPolicy.acquisitionLocationKey(yepes),
            RadarLiveSessionPolicy.acquisitionLocationKey(editedSameId),
        )
    }

    @Test fun effectiveSelectionChangesUseCoordinateToleranceThenCurrentUsesCoverage() {
        val loaded = current(53.4808, -2.2426)
        assertTrue(RadarLiveSessionPolicy.canReuse(loaded, current(53.486, -2.238), null))
        assertFalse(RadarLiveSessionPolicy.canReuse(loaded, current(53.54, -2.24), null))
        val saved = SavedPlace("Saved", 53.4808, -2.2426)
        val equivalentCurrent = current(53.4810, -2.2426)
        val materiallyDifferentCurrent = current(53.49, -2.24)
        assertTrue(RadarLiveSessionPolicy.selectionCompatible(saved, equivalentCurrent))
        assertFalse(RadarLiveSessionPolicy.selectionCompatible(saved, materiallyDifferentCurrent))
        // Once Current is adopted as the acquisition anchor, rapid movement within UK remains
        // coverage-compatible rather than being compared with the old Saved coordinate.
        assertTrue(RadarLiveSessionPolicy.selectionCompatible(equivalentCurrent,
            current(54.0, -2.0)))
        assertTrue(RadarLiveSessionPolicy.canReuse(equivalentCurrent,
            current(54.0, -2.0), "uk"))
        assertFalse(RadarLiveSessionPolicy.canReuse(equivalentCurrent,
            current(52.52, 13.405), "uk"))
    }

    @Test fun travelModeAndPlaceIdentityAreNotRainAcquisitionInputs() {
        val saved = SavedPlace("Saved", 51.5074, -0.1278)
        val liveSame = current(51.5075, -0.1278)
        val savedIdentity = RadarRainSessionLoadPolicy.identity(
            saved, RadarProviderKind.METEOGROUP_REGIONAL, false,
        )
        val liveIdentity = RadarRainSessionLoadPolicy.identity(
            liveSame, RadarProviderKind.METEOGROUP_REGIONAL, false,
        )
        assertEquals(savedIdentity, liveIdentity)
        assertTrue(RadarLiveSessionPolicy.selectionCompatible(saved, liveSame))
        assertFalse(RadarRainSessionLoadPolicy.shouldLoad(
            true, liveIdentity, savedIdentity, 3, 3, hasReusableSession = true,
        )) // Saved -> Travel at the same effective coordinate: zero requests.
        assertFalse(RadarRainSessionLoadPolicy.shouldLoad(
            true, savedIdentity, liveIdentity, 3, 3, hasReusableSession = true,
        )) // Travel -> Saved at the same effective coordinate: zero requests.
    }

    @Test fun travelRequestMatrixReloadsOnlyForEffectiveInputOrCoverageChanges() {
        val saved = SavedPlace("Saved", 51.5074, -0.1278)
        val sameLive = current(51.5075, -0.1278)
        val movedLive = current(53.4808, -2.2426)
        val outside = current(52.52, 13.405)
        val identity = requireNotNull(RadarRainSessionLoadPolicy.identity(
            saved, RadarProviderKind.METEOGROUP_REGIONAL, false,
        ))
        fun requestCount(anchor: SavedPlace, selected: SavedPlace, regionId: String): Int {
            val reusable = RadarLiveSessionPolicy.selectionCompatible(anchor, selected) &&
                RadarLiveSessionPolicy.canReuse(anchor, selected, regionId)
            return if (RadarRainSessionLoadPolicy.shouldLoad(
                    true, identity, identity, 8, 8, reusable,
                )) 1 else 0
        }

        assertEquals(0, requestCount(saved, sameLive, "uk"))
        assertEquals(1, requestCount(saved, movedLive, "uk"))
        // After equivalent activation adopts Current, ordinary rapid movement is regional reuse.
        assertEquals(0, requestCount(sameLive, movedLive, "uk"))
        assertEquals(1, requestCount(movedLive, saved, "uk"))
        assertEquals(1, requestCount(movedLive, outside, "uk"))
        assertTrue(RadarRainSessionLoadPolicy.shouldLoad(
            true, identity, identity, 9, 8, hasReusableSession = true,
        )) // manual/publication generation
        val otherProvider = requireNotNull(RadarRainSessionLoadPolicy.identity(
            sameLive, RadarProviderKind.OPEN_RAINVIEWER, false,
        ))
        assertTrue(RadarRainSessionLoadPolicy.shouldLoad(
            true, otherProvider, identity, 8, 8, hasReusableSession = true,
        ))
    }

    @Test fun broadFranceRectangleCannotRetainMeteoBeyondItsNominalCoverage() {
        val paris = SavedPlace("Paris", 48.8566, 2.3522)
        val yepes = SavedPlace("Yepes", 39.9010, -3.6240)
        assertEquals("fr", RegionalRadarAreas.forPoint(
            paris.latitude, paris.longitude,
        )?.id)
        assertEquals("fr", RegionalRadarAreas.forPoint(
            yepes.latitude, yepes.longitude,
        )?.id)
        assertNotEquals(
            RadarLiveSessionPolicy.acquisitionLocationKey(paris),
            RadarLiveSessionPolicy.acquisitionLocationKey(yepes),
        )
        assertTrue(RadarLiveSessionPolicy.providerCanServe(
            RadarProviderKind.METEOGROUP_REGIONAL, paris,
        ))
        assertFalse(RadarLiveSessionPolicy.providerCanServe(
            RadarProviderKind.METEOGROUP_REGIONAL, yepes,
        ))

        val meteo = retainedRegionalSession(
            paris, RadarProviderKind.METEOGROUP_REGIONAL, "fr",
        )
        assertTrue(RadarLiveSessionPolicy.canReuse(meteo, paris))
        assertFalse(RadarLiveSessionPolicy.canReuse(meteo, yepes))
        meteo.release("test")

        // A legacy open-provider session with no effective mask is deliberately not authoritative.
        val fixedOpera = retainedRegionalSession(paris, RadarProviderKind.EUMETNET_OPERA, "fr")
        assertFalse(RadarLiveSessionPolicy.canReuse(fixedOpera, yepes))
        fixedOpera.release("test")
    }

    @Test fun actualShadowRejectsReuseEvenInsideCoarseRegionAndInstalledCrops() {
        val oPino = SavedPlace("O Pino", 42.904, -8.363)
        val rasterBounds = bounds(44.0, -10.0, 41.0, -6.0)
        val alpha = ByteArray(9)
        alpha[4] = 0xff.toByte()
        val shadowed = RadarEffectiveCoverage(
            rasterBounds, 3, 3, alpha, RadarCoverageSemantics.CURRENT_COMPOSITE,
        )
        val session = retainedOpenSession(
            oPino,
            RadarProviderKind.EUMETNET_OPERA,
            detailBounds = rasterBounds,
            regionalBounds = rasterBounds,
            effectiveCoverage = shadowed,
        )

        assertEquals("fr", RegionalRadarAreas.forPoint(oPino.latitude, oPino.longitude)?.id)
        assertFalse(RadarLiveSessionPolicy.canReuse(session, oPino))
        session.release("test")
    }

    @Test fun currentTravelKeyChangesOnceAtEffectiveCoverageEdgeNotAtBroadCropEdge() {
        val covered = current(42.8, -8.8)
        val shadowed = current(42.8, -7.2)
        val rasterBounds = bounds(44.0, -10.0, 41.0, -6.0)
        val alpha = byteArrayOf(0, 0xff.toByte())
        val effective = RadarEffectiveCoverage(
            rasterBounds, 2, 1, alpha, RadarCoverageSemantics.CURRENT_COMPOSITE,
        )
        val session = retainedOpenSession(
            covered,
            RadarProviderKind.EUMETNET_OPERA,
            detailBounds = rasterBounds,
            regionalBounds = rasterBounds,
            effectiveCoverage = effective,
        )

        val retained = requireNotNull(RadarLiveSessionPolicy.acquisitionLocationKey(covered, session))
        assertTrue(retained.startsWith("current:retained:"))
        assertEquals(retained, RadarLiveSessionPolicy.acquisitionLocationKey(
            current(42.7, -8.7), session,
        ))
        assertFalse(RadarLiveSessionPolicy.canReuse(session, shadowed))
        assertEquals(
            "current:region:fr",
            RadarLiveSessionPolicy.acquisitionLocationKey(shadowed, session),
        )
        session.release("test")
    }

    @Test fun openProviderReuseUsesInstalledDetailCropBeforeCoarseRegion() {
        val yepes = SavedPlace("Yepes", 39.9010, -3.6240)
        val nearby = SavedPlace("Nearby", 40.1, -3.4)
        val castello = SavedPlace("Castelló", 39.9864, -0.0513)
        val detail = bounds(41.0, -4.8, 39.0, -2.4)

        listOf(RadarProviderKind.EUMETNET_OPERA, RadarProviderKind.OPEN_RAINVIEWER).forEach { provider ->
            val session = retainedOpenSession(yepes, provider, detail)
            assertTrue("$provider nearby", RadarLiveSessionPolicy.canReuse(session, nearby))
            assertFalse("$provider distant", RadarLiveSessionPolicy.canReuse(session, castello))
            session.release("test")
        }
    }

    @Test fun currentTravelUsesBroadRetainedCropWithoutMakingSavedSelectionAuthoritative() {
        val yepes = SavedPlace("Yepes", 39.9010, -3.6240)
        val castelloCurrent = current(39.9864, -0.0513)
        val castelloSaved = SavedPlace("Castelló", 39.9864, -0.0513)
        val session = retainedOpenSession(
            yepes,
            RadarProviderKind.EUMETNET_OPERA,
            detailBounds = bounds(41.0, -4.8, 39.0, -2.4),
        )

        assertTrue(RadarLiveSessionPolicy.canReuse(session, castelloCurrent))
        assertFalse(RadarLiveSessionPolicy.canReuse(session, castelloSaved))
        session.release("test")
    }

    @Test fun unboundedCurrentChangesKeyOnlyWhenItLeavesTheInstalledRegionalCrop() {
        val initial = current(35.0, 25.0)
        val nearby = current(35.5, 25.5)
        val distant = current(10.0, 80.0)
        val regional = bounds(45.0, 15.0, 25.0, 35.0)
        val session = retainedOpenSession(
            initial,
            RadarProviderKind.OPEN_RAINVIEWER,
            detailBounds = bounds(36.0, 24.0, 34.0, 26.0),
            regionalBounds = regional,
            regionId = null,
        )

        assertEquals("current:unbounded", RadarLiveSessionPolicy.acquisitionLocationKey(initial))
        val installedKey = RadarLiveSessionPolicy.acquisitionLocationKey(initial, session)
        assertTrue("initial reusable=${RadarLiveSessionPolicy.canReuse(session, initial)} key=$installedKey",
            requireNotNull(installedKey).startsWith("current:retained:"))
        assertEquals(installedKey, RadarLiveSessionPolicy.acquisitionLocationKey(nearby, session))
        assertEquals(
            "current:unbounded",
            RadarLiveSessionPolicy.acquisitionLocationKey(distant, session),
        )
        assertFalse(RadarLiveSessionPolicy.canReuse(session, distant))
        session.release("test")
    }

    @Test fun currentEnteringNamedRegionStillHonoursAnOlderCentredCropBoundary() {
        val outside = current(39.70, -10.0)
        val insideFranceButRetained = current(40.0, -9.0)
        val insideFranceOutsideRetained = current(40.0, 0.0)
        val session = retainedOpenSession(
            outside,
            RadarProviderKind.OPEN_RAINVIEWER,
            detailBounds = bounds(40.5, -10.5, 39.0, -8.5),
            regionalBounds = bounds(43.0, -13.0, 37.0, -7.0),
            regionId = null,
        )

        val retainedKey = RadarLiveSessionPolicy.acquisitionLocationKey(outside, session)
        assertTrue("outside reusable=${RadarLiveSessionPolicy.canReuse(session, outside)} key=$retainedKey",
            requireNotNull(retainedKey).startsWith("current:retained:"))
        assertEquals(
            retainedKey,
            RadarLiveSessionPolicy.acquisitionLocationKey(insideFranceButRetained, session),
        )
        assertEquals(
            "current:region:fr",
            RadarLiveSessionPolicy.acquisitionLocationKey(insideFranceOutsideRetained, session),
        )
        session.release("test")
    }

    @Test fun sessionReuseAndLoadDecisionCoverProviderPlaceAndRefreshTransitions() {
        val yepes = SavedPlace("Yepes", 39.9010, -3.6240)
        val nearby = SavedPlace("Nearby", 40.1, -3.4)
        val castello = SavedPlace("Castelló", 39.9864, -0.0513)
        val berlin = SavedPlace("Berlin", 52.52, 13.405)
        val detail = bounds(41.0, -4.8, 39.0, -2.4)
        val session = retainedOpenSession(yepes, RadarProviderKind.EUMETNET_OPERA, detail)
        val opera = requireNotNull(RadarRainSessionLoadPolicy.identity(
            yepes, RadarProviderKind.EUMETNET_OPERA, false,
        ))
        val rainViewer = requireNotNull(RadarRainSessionLoadPolicy.identity(
            yepes, RadarProviderKind.OPEN_RAINVIEWER, false,
        ))

        // Initial/cold acquisition.
        assertTrue(RadarRainSessionLoadPolicy.shouldLoad(
            true, opera, null, 0, Int.MIN_VALUE, hasReusableSession = false,
        ))
        // Repeated selection and a nearby selection inside the installed detail crop reuse.
        assertFalse(RadarRainSessionLoadPolicy.shouldLoad(
            true, opera, opera, 2, 2,
            hasReusableSession = RadarLiveSessionPolicy.canReuse(session, yepes),
        ))
        assertFalse(RadarRainSessionLoadPolicy.shouldLoad(
            true, opera, opera, 2, 2,
            hasReusableSession = RadarLiveSessionPolicy.canReuse(session, nearby),
        ))
        // Distant same-region, cross-region, provider preference and manual refresh reload.
        assertTrue(RadarRainSessionLoadPolicy.shouldLoad(
            true, opera, opera, 2, 2,
            hasReusableSession = RadarLiveSessionPolicy.canReuse(session, castello),
        ))
        assertTrue(RadarRainSessionLoadPolicy.shouldLoad(
            true, opera, opera, 2, 2,
            hasReusableSession = RadarLiveSessionPolicy.canReuse(session, berlin),
        ))
        assertTrue(RadarRainSessionLoadPolicy.shouldLoad(
            true, rainViewer, opera, 2, 2, hasReusableSession = true,
        ))
        assertTrue(RadarRainSessionLoadPolicy.shouldLoad(
            true, opera, opera, 3, 2, hasReusableSession = true,
        ))
        // A retained fallback session is safe only while its real crop covers the selection.
        val fallback = retainedOpenSession(yepes, RadarProviderKind.OPEN_RAINVIEWER, detail)
        assertTrue(RadarLiveSessionPolicy.canReuse(fallback, nearby))
        assertFalse(RadarLiveSessionPolicy.canReuse(fallback, castello))
        fallback.release("test")
        session.release("test")
    }

    @Test fun regionalToFallbackAndBackChangesLogicalLoadIdentity() {
        val paris = SavedPlace("Paris", 48.8566, 2.3522)
        val yepes = SavedPlace("Yepes", 39.9010, -3.6240)
        val parisIdentity = requireNotNull(RadarRainSessionLoadPolicy.identity(
            paris, RadarProviderKind.METEOGROUP_REGIONAL, false,
        ))
        val yepesIdentity = requireNotNull(RadarRainSessionLoadPolicy.identity(
            yepes, RadarProviderKind.METEOGROUP_REGIONAL, false,
        ))
        assertEquals(RadarProviderKind.METEOGROUP_REGIONAL, parisIdentity.firstEligibleProvider)
        assertEquals(RadarProviderKind.EUMETNET_OPERA, yepesIdentity.firstEligibleProvider)
        assertNotEquals(parisIdentity, yepesIdentity)
        assertTrue(RadarRainSessionLoadPolicy.shouldLoad(
            true, yepesIdentity, parisIdentity, 6, 6, hasReusableSession = true,
        ))
        assertTrue(RadarRainSessionLoadPolicy.shouldLoad(
            true, parisIdentity, yepesIdentity, 6, 6, hasReusableSession = true,
        ))
    }

    @Test fun fallbackTransitionRejectsAnOlderRegionalCompletion() {
        val gate = OverlayRequestGeneration()
        val regionalGeneration = gate.begin()
        val fallbackGeneration = gate.begin()

        assertFalse(gate.accepts(regionalGeneration))
        assertTrue(gate.accepts(fallbackGeneration))
    }

    @Test fun screenReentryReusesFreshRainButRealInvalidationsLoad() {
        val place = SavedPlace("London", 51.5074, -0.1278)
        val meteo = requireNotNull(RadarRainSessionLoadPolicy.identity(
            place, RadarProviderKind.METEOGROUP_REGIONAL, showLikelySnow = false,
        ))
        assertFalse(RadarRainSessionLoadPolicy.shouldLoad(
            true, meteo, meteo, 4, 4, hasReusableSession = true,
        )) // repeated Now <-> Radar entry
        assertFalse(RadarRainSessionLoadPolicy.shouldLoad(
            false, meteo, null, 0, Int.MIN_VALUE, hasReusableSession = false,
        ))
        assertTrue(RadarRainSessionLoadPolicy.shouldLoad(
            true, meteo, null, 0, Int.MIN_VALUE, hasReusableSession = false,
        )) // cold / failed / incomplete session
        assertTrue(RadarRainSessionLoadPolicy.shouldLoad(
            true, meteo, meteo, 5, 4, hasReusableSession = true,
        )) // manual refresh or publication-aware Travel refresh

        val opera = RadarRainSessionLoadPolicy.identity(
            place, RadarProviderKind.EUMETNET_OPERA, showLikelySnow = false,
        )
        assertTrue(RadarRainSessionLoadPolicy.shouldLoad(
            true, opera, meteo, 4, 4, hasReusableSession = true,
        )) // provider change
        val elsewhere = RadarRainSessionLoadPolicy.identity(
            SavedPlace("Paris", 48.8566, 2.3522),
            RadarProviderKind.METEOGROUP_REGIONAL,
            showLikelySnow = false,
        )
        assertTrue(RadarRainSessionLoadPolicy.shouldLoad(
            true, elsewhere, meteo, 4, 4, hasReusableSession = false,
        )) // place / coverage change
    }

    @Test fun snowSettingRemainsAnExplicitRainConfigurationInvalidation() {
        val place = SavedPlace("London", 51.5074, -0.1278)
        assertFalse(
            RadarRainSessionLoadPolicy.identity(
                place, RadarProviderKind.METEOGROUP_REGIONAL, false,
            ) ==
            RadarRainSessionLoadPolicy.identity(
                place, RadarProviderKind.METEOGROUP_REGIONAL, true,
            ),
        )
        assertFalse(requireNotNull(RadarRainSessionLoadPolicy.identity(
            place, RadarProviderKind.OPEN_RAINVIEWER, false,
        )).showLikelySnow)
        assertTrue(requireNotNull(RadarRainSessionLoadPolicy.identity(
            place, RadarProviderKind.OPEN_RAINVIEWER, true,
        )).showLikelySnow)
    }
}
