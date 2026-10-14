package com.rainalarm.app.data

import com.rainalarm.app.domain.GeoPoint
import com.rainalarm.app.domain.GeoQuad
import com.rainalarm.app.domain.IntensityGrid
import com.rainalarm.app.domain.RadarResolutionTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadarEffectiveCoverageTest {
    private val bounds = GeoQuad(
        GeoPoint(44.0, -10.0), GeoPoint(44.0, -6.0),
        GeoPoint(41.0, -6.0), GeoPoint(41.0, -10.0),
    )

    @Test fun exactRendererAlphaDeterminesCoverageIncludingIrregularHoleAndOutside() {
        val alpha = ByteArray(9)
        alpha[4] = 0xff.toByte()
        val coverage = RadarEffectiveCoverage(
            bounds, 3, 3, alpha, RadarCoverageSemantics.CURRENT_COMPOSITE,
        )

        assertTrue(coverage.covers(GeoPoint(43.6, -9.5)))
        assertFalse(coverage.covers(GeoPoint(42.5, -8.0)))
        assertTrue(coverage.covers(GeoPoint(41.4, -6.5)))
        assertFalse(coverage.covers(GeoPoint(45.0, -8.0)))
    }

    @Test fun partiallyShadowedPixelIsNotAcceptedButRoundedTransparentPixelIs() {
        val field = RadarEffectiveCoverage.fromCoveredWeights(
            bounds,
            IntensityGrid(3, 1, floatArrayOf(1f, 0.999f, 0.99f)),
            RadarCoverageSemantics.CURRENT_COMPOSITE,
        )

        assertTrue(field.covers(GeoPoint(42.5, -9.3)))
        // 0.999 coverage rounds to alpha zero, exactly matching the rendered bitmap.
        assertTrue(field.covers(GeoPoint(42.5, -8.0)))
        assertFalse(field.covers(GeoPoint(42.5, -6.7)))
    }

    @Test fun candidatePolicyAcceptsOnlyProvenCoverage() {
        assertTrue(RadarCandidateCoveragePolicy.accepts(RadarProviderCoverageState.COVERED))
        listOf(
            RadarProviderCoverageState.SUPPORTED,
            RadarProviderCoverageState.UNCOVERED,
            RadarProviderCoverageState.OUTSIDE_DOMAIN,
        ).forEach { assertFalse(it.name, RadarCandidateCoveragePolicy.accepts(it)) }
    }

    @Test fun fallbackChoosesNextProvenCandidateAndAllUncoveredHasNoAuthority() {
        val fallbackSequence = listOf(
            RadarProviderKind.EUMETNET_OPERA to RadarProviderCoverageState.UNCOVERED,
            RadarProviderKind.OPEN_RAINVIEWER to RadarProviderCoverageState.COVERED,
        )
        assertEquals(
            RadarProviderKind.OPEN_RAINVIEWER,
            fallbackSequence.firstOrNull { RadarCandidateCoveragePolicy.accepts(it.second) }?.first,
        )
        assertEquals(
            null,
            fallbackSequence.map { it.first to RadarProviderCoverageState.UNCOVERED }
                .firstOrNull { RadarCandidateCoveragePolicy.accepts(it.second) },
        )
    }

    @Test fun sessionStateUsesEffectiveFieldRatherThanBroadProviderDomain() {
        val oPino = SavedPlace("O Pino", 42.904, -8.363)
        val alpha = ByteArray(9).also { it[4] = 0xff.toByte() }
        val effective = RadarEffectiveCoverage(
            bounds, 3, 3, alpha, RadarCoverageSemantics.CURRENT_COMPOSITE,
        )
        val tier = RadarTierFrames(RadarResolutionTier.DETAIL, bounds, emptyList())
        val session = RadarSession(
            place = oPino,
            regional = tier.copy(tier = RadarResolutionTier.REGIONAL),
            detail = tier,
            motion = null,
            pairMotions = emptyList(),
            providerSelection = RadarProviderSelection(
                RadarProviderKind.EUMETNET_OPERA,
                RadarProviderKind.EUMETNET_OPERA,
            ),
            region = RegionalRadarAreas.all.first { it.id == "fr" },
            effectiveCoverage = effective,
        )
        try {
            assertEquals(
                RadarProviderCoverageState.UNCOVERED,
                RadarSessionCoverage.state(session, GeoPoint(oPino.latitude, oPino.longitude)),
            )
            assertEquals(
                RadarProviderCoverageState.COVERED,
                RadarSessionCoverage.state(session, GeoPoint(43.6, -9.5)),
            )
        } finally {
            session.release("test")
        }
    }

    @Test fun quantisationPreservesFullyLitAndShadowedBytes() {
        val field = RadarEffectiveCoverage.fromCoveredWeights(
            bounds,
            IntensityGrid(2, 1, floatArrayOf(1f, 0f)),
            RadarCoverageSemantics.PROVIDER_PUBLISHED,
        )
        assertEquals(0, field.unknownAlpha[0].toInt() and 0xff)
        assertEquals(255, field.unknownAlpha[1].toInt() and 0xff)
    }
}
