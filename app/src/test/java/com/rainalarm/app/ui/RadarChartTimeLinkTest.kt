package com.rainalarm.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadarChartTimeLinkTest {
    @Test fun continuousPlotTapUsesActualPartialHorizonAndInset() {
        val start = 1_700_000_000L
        val end = 54
        val width = 300f
        val inset = 24f
        for (minute in listOf(0f, 0.5f, 13.25f, 27f, 54f)) {
            val x = NowChartLayout.plotX(minute, width, inset, end)
            assertEquals(minute, NowChartLayout.minuteAtX(x, width, inset, end)!!, 0.0001f)
            assertEquals(start + minute * 60.0,
                NowChartLayout.epochAtX(start, x, width, inset, end)!!, 0.01)
        }
        assertEquals(0f, NowChartLayout.minuteAtX(-100f, width, inset, end)!!, 0f)
        assertEquals(54f, NowChartLayout.minuteAtX(999f, width, inset, end)!!, 0f)
        assertEquals(0f, NowChartLayout.minuteAtX(150f, width, inset, 0)!!, 0f)
        assertNull(NowChartLayout.minuteAtX(Float.NaN, width, inset, end))
        assertNull(NowChartLayout.minuteAtX(20f, 0f, inset, end))
    }

    @Test fun requestWaitsThenAppliesOnlyToMatchingPlaceAndCoverage() {
        val request = RadarChartTimeRequest(7, "current-location", 2_050.5)
        assertEquals(RadarChartTimeDecision.Waiting,
            RadarChartTimeLink.decide(request, "current-location", null, null, null))
        // Harmless live fix drift changes coordinates, not the virtual Current ID.
        assertEquals(RadarChartTimeDecision.Apply(1_050.5f),
            RadarChartTimeLink.decide(request, "current-location", "current-location", 1_000, 4_000.0))
        assertEquals(RadarChartTimeDecision.Unavailable,
            RadarChartTimeLink.decide(request, "saved-place", "saved-place", 1_000, 4_000.0))
        assertEquals(RadarChartTimeDecision.Unavailable,
            RadarChartTimeLink.decide(request, "current-location", "other-place", 1_000, 4_000.0))
        assertEquals(RadarChartTimeDecision.Unavailable,
            RadarChartTimeLink.decide(request, "current-location", "current-location", 2_100, 4_000.0))
        assertEquals(RadarChartTimeDecision.Unavailable,
            RadarChartTimeLink.decide(request, "current-location", "current-location", 1_000, 2_000.0))
        assertTrue(RadarChartTimeLink.decide(request.copy(epochSeconds = Double.NaN),
            "current-location", "current-location", 1_000, 4_000.0) is RadarChartTimeDecision.Unavailable)
        assertTrue(!RadarChartTimeLink.shouldDiscard(request, "current-location", false))
        assertTrue(RadarChartTimeLink.shouldDiscard(request, "saved-place", false))
        assertTrue(RadarChartTimeLink.shouldDiscard(request, "current-location", true))
        assertTrue(!RadarChartTimeLink.shouldDiscard(null, "saved-place", true))
    }
}
