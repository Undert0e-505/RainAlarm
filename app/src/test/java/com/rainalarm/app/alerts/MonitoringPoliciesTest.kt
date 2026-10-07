package com.rainalarm.app.alerts

import java.io.File
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitoringPoliciesTest {
    @Test fun `Travel delivery bridge defaults to normal mode and tracks the authoritative flow`() {
        assertFalse(TravelNotificationDeliveryState.isActive)
        try {
            TravelNotificationDeliveryState.update(true)
            assertTrue(TravelNotificationDeliveryState.isActive)
            TravelNotificationDeliveryState.update(false)
            assertFalse(TravelNotificationDeliveryState.isActive)
        } finally {
            TravelNotificationDeliveryState.update(false)
        }
    }

    @Test fun `normal mode leaves app delivery unchanged`() {
        val resolution = MonitoringDeliveryResolution(
            deliverRainEvent = "rain:1",
            deliverLightningEvent = "lightning:1",
        )
        assertEquals(
            resolution,
            TravelNotificationDeliveryPolicy.apply(
                NotificationSourceClass.APP,
                travelActive = false,
                resolution = resolution,
            ),
        )
    }

    @Test fun `Travel silently consumes app rain lightning and combined candidates`() {
        listOf(
            MonitoringDeliveryResolution(deliverRainEvent = "rain:1") to
                MonitoringDeliveryResolution(consumeRainEvent = "rain:1"),
            MonitoringDeliveryResolution(deliverLightningEvent = "lightning:1") to
                MonitoringDeliveryResolution(consumeLightningEvent = "lightning:1"),
            MonitoringDeliveryResolution(
                deliverRainEvent = "rain:1",
                deliverLightningEvent = "lightning:1",
            ) to MonitoringDeliveryResolution(
                consumeRainEvent = "rain:1",
                consumeLightningEvent = "lightning:1",
            ),
        ).forEach { (candidate, expected) ->
            val suppressed = TravelNotificationDeliveryPolicy.apply(
                NotificationSourceClass.APP,
                travelActive = true,
                resolution = candidate,
            )
            assertEquals(expected, suppressed)
            assertEquals(CombinedAlertPolicy.Kind.NONE, suppressed.kind)
        }
    }

    @Test fun `Travel leaves widget delivery and claims independently eligible`() {
        val widget = MonitoringDeliveryResolution(
            deliverRainEvent = "rain:1",
            deliverLightningEvent = "lightning:1",
        )
        val app = TravelNotificationDeliveryPolicy.apply(
            NotificationSourceClass.APP,
            travelActive = true,
            resolution = widget,
        )
        val unchangedWidget = TravelNotificationDeliveryPolicy.apply(
            NotificationSourceClass.WIDGET,
            travelActive = true,
            resolution = widget,
        )

        assertEquals(CombinedAlertPolicy.Kind.NONE, app.kind)
        assertEquals(CombinedAlertPolicy.Kind.COMBINED, unchangedWidget.kind)
        assertEquals(widget, unchangedWidget)
    }

    @Test fun `Travel consumption prevents catch-up but a new episode remains eligible`() {
        val suppressed = TravelNotificationDeliveryPolicy.apply(
            NotificationSourceClass.APP,
            travelActive = true,
            resolution = MonitoringDeliveryResolution(
                deliverRainEvent = "rain:1",
                deliverLightningEvent = "lightning:1",
            ),
        )
        val consumed = SubscriptionDeliveryState(
            consumedRainEvent = suppressed.consumeRainEvent,
            consumedLightningEvent = suppressed.consumeLightningEvent,
        )
        val sameEpisode = SubscriptionDeliveryPolicy.decide(
            listOf(DeliveryCandidate(
                subscriptionId = "app",
                state = consumed,
                rainEvent = "rain:1",
                lightningEvent = "lightning:1",
            )),
            Instant.parse("2026-10-07T12:00:00Z"),
            ZoneId.of("UTC"),
        )
        val rearmedEpisodes = SubscriptionDeliveryPolicy.decide(
            listOf(DeliveryCandidate(
                subscriptionId = "app",
                state = consumed,
                rainEvent = "rain:2",
                lightningEvent = "lightning:2",
            )),
            Instant.parse("2026-10-07T12:10:00Z"),
            ZoneId.of("UTC"),
        )

        assertFalse(sameEpisode.shouldNotify)
        assertTrue(rearmedEpisodes.shouldNotify)
    }

    @Test fun `standalone lightning is compact while rain-bearing alerts retain detail`() {
        assertFalse(MonitorNotificationPresentationPolicy.usesExpandedStyle(
            CombinedAlertPolicy.Kind.LIGHTNING,
        ))
        assertTrue(MonitorNotificationPresentationPolicy.usesExpandedStyle(
            CombinedAlertPolicy.Kind.RAIN,
        ))
        assertTrue(MonitorNotificationPresentationPolicy.usesExpandedStyle(
            CombinedAlertPolicy.Kind.COMBINED,
        ))
    }

    @Test fun `every localized standalone lightning body is only the radius sentence`() {
        val resources = listOf(File("src/main/res"), File("app/src/main/res"))
            .first(File::isDirectory)
        val localized = resources.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith("values") }
            .map { File(it, "strings.xml") }
            .filter(File::isFile)
            .filter { it.readText().contains("notification_lightning_detail") }
        assertEquals(6, localized.size)
        localized.forEach { strings ->
            val localeDirectory = strings.parentFile?.name ?: strings.path
            val xml = strings.readText()
            assertFalse(xml.contains("notification_lightning_summary"))
            val body = requireNotNull(Regex(
                """<string name="notification_lightning_detail">([^<]+)</string>""",
            ).find(xml)).groupValues[1]
            assertTrue("$localeDirectory: $body", body.contains("%1\$d km"))
            assertEquals("$localeDirectory: $body", 1, body.count { it == '.' })
        }
    }

    @Test fun `notification evidence is not consumed while delivery permission is unavailable`() {
        assertTrue(AlertDecisionEligibilityPolicy.mayAdvance(true, true))
        assertFalse(AlertDecisionEligibilityPolicy.mayAdvance(true, false))
        assertFalse(AlertDecisionEligibilityPolicy.mayAdvance(false, true))
    }

    @Test fun `quiet hours use inclusive start exclusive end for same-day and overnight`() {
        val day = QuietHours(true, 9 * 60, 17 * 60)
        assertTrue(QuietHoursPolicy.contains(day, 9 * 60))
        assertTrue(QuietHoursPolicy.contains(day, 16 * 60 + 59))
        assertFalse(QuietHoursPolicy.contains(day, 17 * 60))
        val overnight = QuietHours(true, 22 * 60, 7 * 60)
        assertTrue(QuietHoursPolicy.contains(overnight, 22 * 60))
        assertTrue(QuietHoursPolicy.contains(overnight, 6 * 60 + 59))
        assertFalse(QuietHoursPolicy.contains(overnight, 7 * 60))
        assertFalse(QuietHours(true, 60, 60).valid)
    }

    @Test fun `quiet hours follow current zone and Android local time across DST`() {
        val quiet = QuietHours(true, 120, 180)
        val instant = Instant.parse("2026-03-29T01:30:00Z")
        assertTrue(QuietHoursPolicy.isQuiet(quiet, instant, ZoneId.of("Europe/London")))
        assertFalse(QuietHoursPolicy.isQuiet(quiet, instant, ZoneId.of("UTC")))
        assertFalse(QuietHoursPolicy.isQuiet(quiet, instant, ZoneId.of("Europe/Athens")))
    }

    @Test fun `event consumed during quiet hours is never replayed later`() {
        val quiet = DeliveryCandidate("widget:1", QuietHours(true, 22 * 60, 7 * 60))
        val during = SubscriptionDeliveryPolicy.decide(
            listOf(quiet),
            "rain:1",
            null,
            Instant.parse("2026-01-01T23:00:00Z"),
            ZoneId.of("UTC"),
        )
        assertFalse(during.shouldNotify)
        val after = SubscriptionDeliveryPolicy.decide(
            listOf(quiet.copy(state = requireNotNull(during.updated[quiet.subscriptionId]))),
            "rain:1",
            null,
            Instant.parse("2026-01-02T08:00:00Z"),
            ZoneId.of("UTC"),
        )
        assertFalse(after.shouldNotify)
    }

    @Test fun `shared target emits once when one widget is outside its own quiet window`() {
        val candidates = listOf(
            DeliveryCandidate("widget:1", QuietHours(true, 22 * 60, 7 * 60)),
            DeliveryCandidate("widget:2", QuietHours(false)),
        )
        val decision = SubscriptionDeliveryPolicy.decide(
            candidates,
            "rain:2",
            "lightning:2",
            Instant.parse("2026-01-01T23:00:00Z"),
            ZoneId.of("UTC"),
        )
        assertTrue(decision.shouldNotify)
        assertEquals(2, decision.updated.size)
        assertEquals(CombinedAlertPolicy.Kind.COMBINED, CombinedAlertPolicy.kind(true, true))
    }

    @Test fun `rain evidence arms only from clear and delivery eligibility survives until consumed`() {
        val clear = RainEpisodePolicy.reduce(RainEpisodeState(), RadarAlertEvaluation.Clear)
        assertTrue(clear.state.armed)
        val approaching = RainEpisodePolicy.reduce(
            clear.state,
            RadarAlertEvaluation.Approaching(5, 10, 123L),
        )
        assertEquals(123L, approaching.newEventIdentity)
        assertTrue(approaching.state.armed)
        val wet = RainEpisodePolicy.reduce(clear.state, RadarAlertEvaluation.WetNow(123L))
        assertTrue(wet.state.armed)
        assertEquals(123L, wet.newEventIdentity)
        assertEquals(wet.state, RainEpisodePolicy.reduce(wet.state, RadarAlertEvaluation.Unknown).state)
    }
}
