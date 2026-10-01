package com.rainalarm.app.alerts

import java.io.File
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitoringPoliciesTest {
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
