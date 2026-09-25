package com.rainalarm.app.data

import com.rainalarm.app.domain.GeoPoint
import com.rainalarm.app.ui.RadarProviderSettingsPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadarProviderCapabilityTest {
    private val essex = GeoPoint(51.729, 0.503)
    private val vienna = GeoPoint(48.2082, 16.3738)
    private val sydney = GeoPoint(-33.8688, 151.2093)

    @Test fun globalCoordinatesKeepHardProviderDomainsDistinct() {
        assertEquals(RadarProviderCoverageState.COVERED,
            RadarProviderCapabilityResolver.capability(
                RadarProviderKind.METEOGROUP_REGIONAL, essex).state)
        assertTrue(OperaProductDomain.contains(essex))

        assertEquals(RadarProviderCoverageState.OUTSIDE_DOMAIN,
            RadarProviderCapabilityResolver.capability(
                RadarProviderKind.METEOGROUP_REGIONAL, vienna).state)
        assertEquals(RadarProviderCoverageState.SUPPORTED,
            RadarProviderCapabilityResolver.capability(
                RadarProviderKind.EUMETNET_OPERA, vienna).state)

        assertEquals(RadarProviderCoverageState.OUTSIDE_DOMAIN,
            RadarProviderCapabilityResolver.capability(
                RadarProviderKind.EUMETNET_OPERA, sydney).state)
        assertEquals(RadarProviderCoverageState.SUPPORTED,
            RadarProviderCapabilityResolver.capability(
                RadarProviderKind.OPEN_RAINVIEWER, sydney).state)
    }

    @Test fun preferredProviderRemainsFirstWhenValidAndFallsBackWithoutChangingPreference() {
        assertEquals(
            listOf(RadarProviderKind.METEOGROUP_REGIONAL, RadarProviderKind.EUMETNET_OPERA,
                RadarProviderKind.OPEN_RAINVIEWER),
            RadarProviderCapabilityResolver.providersFor(
                RadarProviderKind.METEOGROUP_REGIONAL, essex),
        )
        assertEquals(
            listOf(RadarProviderKind.EUMETNET_OPERA, RadarProviderKind.OPEN_RAINVIEWER),
            RadarProviderCapabilityResolver.providersFor(
                RadarProviderKind.METEOGROUP_REGIONAL, vienna),
        )
        assertEquals(
            listOf(RadarProviderKind.OPEN_RAINVIEWER),
            RadarProviderCapabilityResolver.providersFor(
                RadarProviderKind.METEOGROUP_REGIONAL, sydney),
        )
        assertEquals(
            listOf(RadarProviderKind.EUMETNET_OPERA, RadarProviderKind.METEOGROUP_REGIONAL,
                RadarProviderKind.OPEN_RAINVIEWER),
            RadarProviderCapabilityResolver.providersFor(
                RadarProviderKind.EUMETNET_OPERA, essex),
        )
        assertEquals(
            listOf(RadarProviderKind.OPEN_RAINVIEWER, RadarProviderKind.METEOGROUP_REGIONAL,
                RadarProviderKind.EUMETNET_OPERA),
            RadarProviderCapabilityResolver.providersFor(
                RadarProviderKind.OPEN_RAINVIEWER, essex),
        )
    }

    @Test fun failedMaskIsUnknownAndPublishedUncoveredCanResolveToNoProvider() {
        val worldwide = RadarProviderCapabilityResolver.capability(
            RadarProviderKind.OPEN_RAINVIEWER, sydney)
        assertEquals(worldwide, RadarProviderCapabilityResolver.refine(worldwide, null))
        assertEquals(RadarProviderCoverageState.COVERED,
            RadarProviderCapabilityResolver.refine(worldwide, true).state)
        assertEquals(RadarProviderCoverageState.UNCOVERED,
            RadarProviderCapabilityResolver.refine(worldwide, false).state)
        assertNull(RadarProviderCapabilityResolver.firstEligible(
            RadarProviderKind.METEOGROUP_REGIONAL, sydney, rainViewerCovered = false))
    }

    @Test fun operaDomainIsIndependentOfConfiguredMeteoRectangles() {
        assertNull(RegionalRadarAreas.forPoint(vienna.latitude, vienna.longitude))
        assertTrue(OperaProductDomain.contains(vienna))
        assertEquals(RadarProviderKind.EUMETNET_OPERA,
            RadarProviderCapabilityResolver.firstEligible(
                RadarProviderKind.EUMETNET_OPERA, vienna))
    }

    @Test fun settingsPinCopyIsConciseAndDescribesFallbackProvider() {
        assertEquals("Pinned default · Outside coverage here",
            RadarProviderSettingsPolicy.detail(
                pinned = true,
                capability = RadarProviderCoverageState.OUTSIDE_DOMAIN,
                inUse = false,
            ))
        assertEquals("Available here · In use here",
            RadarProviderSettingsPolicy.detail(
                pinned = false,
                capability = RadarProviderCoverageState.SUPPORTED,
                inUse = true,
            ))
    }

    @Test fun providerNoticeIdentityDeduplicatesRefreshButChangesWithPlaceOrActiveProvider() {
        val fallback = RadarProviderSelection(
            RadarProviderKind.METEOGROUP_REGIONAL,
            RadarProviderKind.EUMETNET_OPERA,
        )
        val same = RadarProviderNoticePolicy.identity("essex", fallback)
        assertEquals(same, RadarProviderNoticePolicy.identity("essex", fallback))
        assertTrue(same != RadarProviderNoticePolicy.identity("vienna", fallback))
        assertTrue(same != RadarProviderNoticePolicy.identity(
            "essex", fallback.copy(active = RadarProviderKind.OPEN_RAINVIEWER)))
        assertNull(RadarProviderNoticePolicy.identity(
            "essex", fallback.copy(active = RadarProviderKind.METEOGROUP_REGIONAL)))

        val deduplicator = RadarProviderNoticeDeduplicator()
        assertTrue(deduplicator.shouldShow("current-location", fallback))
        assertTrue(!deduplicator.shouldShow("current-location", fallback))
        // A successful return to the preferred source resets a later genuine fallback notice.
        assertTrue(!deduplicator.shouldShow(
            "current-location",
            fallback.copy(active = RadarProviderKind.METEOGROUP_REGIONAL),
        ))
        assertTrue(deduplicator.shouldShow("current-location", fallback))
    }
}
