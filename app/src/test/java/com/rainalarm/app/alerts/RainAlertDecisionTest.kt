package com.rainalarm.app.alerts

import com.rainalarm.app.data.DEFAULT_PLACE
import com.rainalarm.app.data.SavedPlace
import com.rainalarm.app.data.CURRENT_LOCATION_ID
import com.rainalarm.app.domain.IntensityGrid
import com.rainalarm.app.domain.MotionEstimate
import com.rainalarm.app.domain.PhysicalRadarMotion
import com.rainalarm.app.domain.RadarResolutionTier
import com.rainalarm.app.domain.RadarPointTimeline
import com.rainalarm.app.domain.RainMinuteSeries
import com.rainalarm.app.domain.RainMinutePoint
import com.rainalarm.app.domain.RainMinuteAvailability
import java.time.ZoneId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RainAlertDecisionTest {
    @Test fun startupAlertsRespectExplicitChoicePermissionAndOneTimePrompt() {
        assertEquals(AlertStartupAction.SCHEDULE, AlertStartupPolicy.decide(null, true, false))
        assertEquals(AlertStartupAction.SCHEDULE, AlertStartupPolicy.decide(true, true, true))
        assertEquals(AlertStartupAction.NONE, AlertStartupPolicy.decide(false, true, false))
        assertEquals(AlertStartupAction.NONE, AlertStartupPolicy.decide(false, false, false))
        assertEquals(AlertStartupAction.REQUEST_PERMISSION, AlertStartupPolicy.decide(null, false, false))
        assertEquals(AlertStartupAction.REQUEST_PERMISSION, AlertStartupPolicy.decide(true, false, false))
        assertEquals(AlertStartupAction.PERMISSION_NEEDED, AlertStartupPolicy.decide(null, false, true))
        assertEquals(AlertStartupAction.PERMISSION_NEEDED, AlertStartupPolicy.decide(true, false, true))
    }
    @Test
    fun providerForecastAlertsOnlyForDryNowThenWetWithinSixtyMinutes() {
        val approaching = RainAlertDecisionEngine.evaluateProviderTimeline(
            RadarPointTimeline(false, listOf(20, 25, 30), 1234L),
        )
        assertEquals(RadarAlertEvaluation.Approaching(20, 30, 1234L), approaching)
        assertEquals(
            RadarAlertEvaluation.WetNow,
            RainAlertDecisionEngine.evaluateProviderTimeline(RadarPointTimeline(true, listOf(5), 1234L)),
        )
        assertEquals(
            RadarAlertEvaluation.Clear,
            RainAlertDecisionEngine.evaluateProviderTimeline(RadarPointTimeline(false, emptyList(), 1234L)),
        )
    }
    @Test
    fun dryNowAndFutureWetQualifies() {
        val evaluation = RainAlertDecisionEngine.evaluateField(
            latest = gridWithWetSquare(3, 10),
            motion = motion(0.4, 0.0, 0.8),
            frameIdentity = 100,
        )
        assertTrue(evaluation is RadarAlertEvaluation.Approaching)
        val approaching = evaluation as RadarAlertEvaluation.Approaching
        assertTrue(approaching.etaStartMinutes in 1..60)
    }

    @Test
    fun wetNowDoesNotNotify() {
        val evaluation = RainAlertDecisionEngine.evaluateField(
            latest = gridWithWetSquare(13, 19),
            motion = motion(0.2, 0.0, 0.8),
            frameIdentity = 100,
        )
        assertTrue(evaluation is RadarAlertEvaluation.WetNow)
        assertFalse(
            RainAlertDecisionEngine.decide(evaluation, AlertMemory(), 10_000).shouldNotify,
        )
    }

    @Test
    fun dryNoFutureAndLowConfidenceDoNotNotify() {
        val dry = IntensityGrid(32, 32, FloatArray(32 * 32))
        val clear = RainAlertDecisionEngine.evaluateField(
            dry,
            motion(0.2, 0.0, 0.8),
            100,
        )
        assertTrue(clear is RadarAlertEvaluation.Clear)
        val unknown = RainAlertDecisionEngine.evaluateField(
            gridWithWetSquare(3, 10),
            motion(0.2, 0.0, 0.2),
            100,
        )
        assertTrue(unknown is RadarAlertEvaluation.Unknown)
        assertFalse(RainAlertDecisionEngine.decide(clear, AlertMemory(), 10_000).shouldNotify)
        assertFalse(RainAlertDecisionEngine.decide(unknown, AlertMemory(), 10_000).shouldNotify)
    }

    @Test
    fun notificationSuppressesThroughInclusiveForecastEndAndGraceThenRepeatsWithoutClear() {
        val event = RadarAlertEvaluation.Approaching(10, 20, 100)
        val first = RainAlertDecisionEngine.decide(event, AlertMemory(), 10_000, 600)
        assertTrue(first.shouldNotify)
        assertTrue(first.nextMemory.lastEventIdentity == 100L)
        // Minute 20 is inclusive: suppression ends at base + 21 min + 10 min grace.
        assertEquals(11_860L, first.nextMemory.suppressedUntilEpochSeconds)
        val duplicate = RainAlertDecisionEngine.decide(event, first.nextMemory, 11_859, 600)
        assertFalse(duplicate.shouldNotify)
        assertEquals(11_860L, duplicate.nextMemory.suppressedUntilEpochSeconds)
        assertTrue(RainAlertDecisionEngine.decide(event, first.nextMemory, 11_860, 600).shouldNotify)
        assertTrue(RainAlertDecisionEngine.decide(event, first.nextMemory, 11_861, 600).shouldNotify)
    }

    @Test fun wetUnknownAndClearDoNotNotifyOrExtendTheAcceptedInterval() {
        val memory = AlertMemory(10_000, 100, 12_000)
        listOf(RadarAlertEvaluation.WetNow, RadarAlertEvaluation.Unknown, RadarAlertEvaluation.Clear)
            .forEach { evaluation ->
                val result = RainAlertDecisionEngine.decide(evaluation, memory, 20_000)
                assertFalse(result.shouldNotify)
                assertEquals(memory, result.nextMemory)
            }
    }

    @Test fun wetThenPartialDryCannotMakeThePlaceOneShot() {
        val event = RadarAlertEvaluation.Approaching(5, 15, 100)
        val sent = RainAlertDecisionEngine.decide(event, AlertMemory(), 10_000)
        val wet = RainAlertDecisionEngine.decide(RadarAlertEvaluation.WetNow, sent.nextMemory, 10_600)
        val partialDry = RainAlertDecisionEngine.decide(RadarAlertEvaluation.Unknown, wet.nextMemory, 11_200)
        assertFalse(partialDry.shouldNotify)
        assertEquals(sent.nextMemory.suppressedUntilEpochSeconds,
            partialDry.nextMemory.suppressedUntilEpochSeconds)
        assertTrue(RainAlertDecisionEngine.decide(
            event, partialDry.nextMemory, sent.nextMemory.suppressedUntilEpochSeconds,
        ).shouldNotify)
    }

    @Test fun expectedStartAnchorsSuppressionAndUnconfirmedHorizonIsConservative() {
        val event = RadarAlertEvaluation.Approaching(
            etaStartMinutes = 5,
            etaEndMinutes = 60,
            frameIdentity = 100,
            expectedStartEpochSeconds = 20_300,
        )
        // Forecast base=20_000; minute 60 is inclusive; plus ten-minute grace.
        assertEquals(24_260L, RainAlertDecisionEngine.suppressionUntil(event, 20_010))
    }

    @Test fun legacyMemoryExpiresInsteadOfRemainingPermanentlyDisarmed() {
        assertEquals(17_200L, RainAlertMemoryMigration.suppressionUntil(null, 10_000))
        assertEquals(12_345L, RainAlertMemoryMigration.suppressionUntil(12_345, 10_000))
        assertEquals(0L, RainAlertMemoryMigration.suppressionUntil(null, 0))
        val migrated = AlertMemory(lastNotifiedEpochSeconds = 10_000,
            suppressedUntilEpochSeconds = 17_200)
        assertFalse(RainAlertDecisionEngine.decide(
            RadarAlertEvaluation.Approaching(5, 10, 200), migrated, 17_199).shouldNotify)
        assertTrue(RainAlertDecisionEngine.decide(
            RadarAlertEvaluation.Approaching(5, 10, 200), migrated, 17_200).shouldNotify)
    }

    @Test fun deletedSavedPlaceClearsOnlyItsMemoryAndNotificationsUseStablePerPlaceIds() {
        val york = SavedPlace("York", 53.96, -1.08)
        assertEquals(setOf(
            "armed_${york.id}", "last_notified_${york.id}", "last_event_${york.id}",
            "suppressed_until_${york.id}",
        ), RainAlertMemoryKeys.forDeletedSavedPlace(york.id))
        assertTrue(RainAlertMemoryKeys.forDeletedSavedPlace(CURRENT_LOCATION_ID).isEmpty())
        val first = RainAlertNotificationIdentity.forPlace(york.id)
        assertEquals(first, RainAlertNotificationIdentity.forPlace(york.id))
        assertTrue(first != RainAlertNotificationIdentity.forPlace("another-place"))
    }

    @Test
    fun selectedSavedAndLivePlacesAreEligibleWithAsyncSelectionGuard() {
        assertTrue(RainAlertDecisionEngine.isEligible(DEFAULT_PLACE))
        assertTrue(
            RainAlertDecisionEngine.isEligible(
                SavedPlace("Current", 51.0, -1.0, isCurrentLocation = true),
            ),
        )
        val saved = SavedPlace("York", 53.96, -1.08)
        val other = SavedPlace("Leeds", 53.8, -1.55)
        assertTrue(RainAlertSelectionGuard.stillSelected(saved.id, saved, saved.id, saved))
        assertFalse(RainAlertSelectionGuard.stillSelected(saved.id, saved, other.id, other))
        val live = SavedPlace("Manchester", 53.48, -2.24, isCurrentLocation = true)
        val moved = SavedPlace("Bolton", 53.58, -2.43, isCurrentLocation = true)
        assertFalse(RainAlertSelectionGuard.stillSelected(CURRENT_LOCATION_ID, live, CURRENT_LOCATION_ID, moved))
        assertFalse(RainAlertSelectionGuard.stillSelected(CURRENT_LOCATION_ID, live, CURRENT_LOCATION_ID, null))
    }

    @Test
    fun `notification confirms duration and peak only when dry end is observed`() {
        val series = RainMinuteSeries(1_000, (0..60).map { minute ->
            val value = if (minute in 10..19) 0.6f else 0f
            RainMinutePoint(minute, value, value, value, minute > 0)
        }, "regional", 1.0, RainMinuteAvailability.AVAILABLE)
        val event = RainAlertDecisionEngine.evaluateMinuteSeries(series) as RadarAlertEvaluation.Approaching
        assertEquals(10, event.confirmedDurationMinutes)
        assertEquals(0.6f, event.confirmedPeakIntensity)
        val text = RainAlertNotificationText.forApproaching("York", event, 1_000, ZoneId.of("UTC"))
        assertTrue(text.title.contains("York"))
        assertTrue(text.summary.contains("10 min"))
        assertTrue(text.summary.contains("00:26"))
        assertTrue(text.detail.contains("about 10 min"))
        assertTrue(text.detail.contains("60%"))

        val continuing = series.copy(points = series.points.map {
            val value = if (it.minute >= 10) 0.6f else 0f
            it.copy(minimum = value, average = value, maximum = value)
        })
        val ongoing = RainAlertDecisionEngine.evaluateMinuteSeries(continuing) as RadarAlertEvaluation.Approaching
        assertEquals(null, ongoing.confirmedDurationMinutes)
        assertEquals(null, ongoing.confirmedPeakIntensity)
        val restrained = RainAlertNotificationText.forApproaching("York", ongoing, 1_000, ZoneId.of("UTC"))
        assertEquals(restrained.summary, restrained.detail)
        val partial = continuing.copy(points = continuing.points.take(36), availability = RainMinuteAvailability.PARTIAL)
        val partialEvent = RainAlertDecisionEngine.evaluateMinuteSeries(partial) as RadarAlertEvaluation.Approaching
        assertEquals(null, partialEvent.confirmedDurationMinutes)
        assertEquals(RainAlertNotificationText.forApproaching("York", partialEvent, 1_000, ZoneId.of("UTC")).summary,
            RainAlertNotificationText.forApproaching("York", partialEvent, 1_000, ZoneId.of("UTC")).detail)
    }

    @Test
    fun `notification clock uses the selected zone across midnight`() {
        val event = RadarAlertEvaluation.Approaching(20, 20, 0,
            expectedStartEpochSeconds = 1_735_689_000L)
        val text = RainAlertNotificationText.forApproaching("Manchester", event, 0, ZoneId.of("Europe/London"))
        assertTrue(text.summary.contains("20 min"))
        assertTrue(text.summary.contains("23:50"))
    }

    @Test
    fun `snow wording follows the arrival minute rather than later snow`() {
        fun series(snowMinutes: Set<Int>) = RainMinuteSeries(1_000, (0..60).map { minute ->
            val value = if (minute in 10..19) 0.6f else 0f
            RainMinutePoint(minute, value, value, value, minute > 0, minute in snowMinutes)
        }, "open", 1.0, RainMinuteAvailability.AVAILABLE,
            intensityEncoding = com.rainalarm.app.domain.RadarIntensityEncoding.OPEN_REFLECTIVITY)

        val snowAtOnset = RainAlertDecisionEngine.evaluateMinuteSeries(series(setOf(10))) as
            RadarAlertEvaluation.Approaching
        assertTrue(snowAtOnset.likelySnow)
        assertEquals("Snow likely approaching York",
            RainAlertNotificationText.forApproaching("York", snowAtOnset, 1_000, ZoneId.of("UTC")).title)

        val rainThenSnow = RainAlertDecisionEngine.evaluateMinuteSeries(series(setOf(15))) as
            RadarAlertEvaluation.Approaching
        assertFalse(rainThenSnow.likelySnow)
        assertEquals("Rain approaching York",
            RainAlertNotificationText.forApproaching("York", rainThenSnow, 1_000, ZoneId.of("UTC")).title)
    }

    private fun gridWithWetSquare(start: Int, endExclusive: Int): IntensityGrid {
        val values = FloatArray(32 * 32)
        for (y in 13 until 20) {
            for (x in start until endExclusive) values[y * 32 + x] = 1f
        }
        return IntensityGrid(32, 32, values)
    }

    private fun motion(dx: Double, dy: Double, confidence: Double): PhysicalRadarMotion =
        PhysicalRadarMotion.fromAnalysisPixels(
            MotionEstimate(dx, dy, confidence, 3),
            RadarResolutionTier.DETAIL,
            analysisWidth = 32,
            analysisHeight = 32,
        )
}
