package com.rainalarm.app.alerts

import com.rainalarm.app.data.RadarProviderKind
import com.rainalarm.app.domain.PrecipitationPresentationKind
import com.rainalarm.app.domain.QualitativeIntensity
import com.rainalarm.app.domain.RadarIntensityEncoding
import com.rainalarm.app.domain.RainEventSeverityPolicy
import com.rainalarm.app.domain.RainMinuteAvailability
import com.rainalarm.app.domain.RainMinutePoint
import com.rainalarm.app.domain.RainMinuteSeries
import com.rainalarm.app.domain.RainMinuteSeriesAnalyzer
import com.rainalarm.app.domain.WeatherPresentationPolicy
import com.rainalarm.app.widget.FrozenMonitorTarget
import com.rainalarm.app.widget.RainAlarmWidgetConfig
import com.rainalarm.app.widget.WidgetCompassContentMode
import com.rainalarm.app.widget.WidgetCompassContentPolicy
import com.rainalarm.app.widget.WidgetPrimaryContent
import com.rainalarm.app.widget.WidgetPrimaryPresentation
import com.rainalarm.app.widget.WidgetSavedPlaceTarget
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Executable examples for docs/NOTIFICATION_REQUIREMENTS.md. */
class NotificationRequirementsTest {
    @Test fun `NOTIFY CFG 01 legacy widgets migrate to rain first notification defaults`() {
        val decoded = Json.decodeFromString<RainAlarmWidgetConfig>(
            """{
                "appWidgetId":41,
                "primaryContent":"LIGHTNING",
                "backgroundOpacityPercent":72,
                "savedPlace":{
                    "stableId":"great-baddow",
                    "displayName":"Great Baddow",
                    "latitude":51.717,
                    "longitude":0.506
                }
            }""".trimIndent(),
        )
        assertTrue(decoded.notificationsEnabled)
        assertFalse(decoded.includeLightningNotifications)
        assertEquals(72, decoded.backgroundOpacityPercent)
        assertEquals("Great Baddow", decoded.savedPlace?.displayName)
    }

    @Test fun `NOTIFY VIS 01 compact dry widget shows temperature regardless of legacy mode`() {
        val dry = WeatherPresentationPolicy.from(series = null, temperatureC = null).copy(
            kind = PrecipitationPresentationKind.CLEAR,
            centerText = "—",
        )
        WidgetPrimaryContent.entries.forEach { legacy ->
            val face = WidgetCompassContentPolicy.presentation(
                WidgetCompassContentMode.COMPASS_ONLY,
                legacy,
                dry,
                WidgetPrimaryPresentation("17°", null, "17°", lightningBadge = true),
            )
            assertEquals("legacy=$legacy", "17°", face.centerText)
            assertFalse("legacy=$legacy", face.hasPrecipitation)
        }
    }

    @Test fun `NOTIFY VIS 02 wider dry disc is dash while adjacent temperature remains available`() {
        val dry = WeatherPresentationPolicy.from(series = null, temperatureC = null).copy(
            kind = PrecipitationPresentationKind.CLEAR,
            centerText = "17°",
        )
        val primary = WidgetPrimaryPresentation("17°", null, "17°")
        val face = WidgetCompassContentPolicy.presentation(
            WidgetCompassContentMode.TEXT_BEARING,
            WidgetPrimaryContent.TEMPERATURE,
            dry,
            primary,
        )
        assertEquals("—", face.centerText)
        assertEquals("17°", primary.headline)
        assertFalse(face.hasPrecipitation)
    }

    @Test fun `NOTIFY VIS 03 rain always displaces temperature or lightning legacy content`() {
        val rain = WeatherPresentationPolicy.from(
            com.rainalarm.app.domain.RainMinuteSeries(
                0L,
                (0..60).map { minute ->
                    val value = if (minute <= 10) .7f else 0f
                    com.rainalarm.app.domain.RainMinutePoint(
                        minute, value, value, value, minute > 0,
                    )
                },
                "fixture",
                1.0,
                com.rainalarm.app.domain.RainMinuteAvailability.AVAILABLE,
            ),
            17.0,
        )
        WidgetPrimaryContent.entries.forEach { legacy ->
            WidgetCompassContentMode.entries.forEach { mode ->
                assertEquals(
                    rain,
                    WidgetCompassContentPolicy.presentation(
                        mode,
                        legacy,
                        rain,
                        WidgetPrimaryPresentation("⚡", null, "Lightning nearby", lightningBadge = true),
                    ),
                )
            }
        }
    }

    @Test fun `NOTIFY CFG 02 compact lightning acquisition follows only its own controls`() {
        assertFalse(RainAlarmWidgetConfig(1).requiresLightningAcquisition(110))
        assertTrue(RainAlarmWidgetConfig(
            1,
            notificationsEnabled = true,
            includeLightningNotifications = true,
        ).requiresLightningAcquisition(110))
        assertFalse(RainAlarmWidgetConfig(
            1,
            notificationsEnabled = false,
            includeLightningNotifications = true,
        ).requiresLightningAcquisition(110))
        assertTrue(RainAlarmWidgetConfig(1).requiresLightningAcquisition(400))
    }

    @Test fun `NOTIFY CFG 03 master off gates but preserves subordinate widget choices`() {
        val config = RainAlarmWidgetConfig(
            appWidgetId = 41,
            savedPlace = WidgetSavedPlaceTarget(
                "great-baddow", "Great Baddow", 51.717, .506,
            ),
            notificationsEnabled = false,
            includeLightningNotifications = true,
            quietHoursEnabled = true,
            quietStartMinuteOfDay = 60,
            quietEndMinuteOfDay = 60,
        )
        assertTrue(config.valid)
        assertTrue(config.includeLightningNotifications)
        assertTrue(config.quietHoursEnabled)
        assertFalse(config.requiresLightningAcquisition(110))
    }

    @Test fun `NOTIFY CFG 04 pre-claim rain consumption survives schema migration`() {
        val source = sourceFile("app/src/main/java/com/rainalarm/app/alerts/MonitoringStateStore.kt")
        assertTrue(source.contains("legacyRainEventIdentity(monitorKey)"))
        assertTrue(source.contains("state.consumedRainEvent?.removePrefix(\"rain:\")?.toLongOrNull()"))
    }

    @Test fun `NOTIFY RAIN 01 widget requires complete clear while app is immediately eligible`() {
        val future = RadarAlertEvaluation.Approaching(15, 35, 1L)
        val now = RadarAlertEvaluation.WetNow(1L)
        listOf(future, now).forEach { evaluation ->
            assertTrue(SubscriptionRainEligibilityPolicy.isEligible(evaluation, false, false))
            assertFalse(SubscriptionRainEligibilityPolicy.isEligible(evaluation, true, false))
            assertTrue(SubscriptionRainEligibilityPolicy.isEligible(evaluation, true, true))
        }
    }

    @Test fun `NOTIFY RAIN 02 only complete clear arms and failed delivery preserves eligibility`() {
        assertTrue(SubscriptionRainEligibilityPolicy.nextArmed(RadarAlertEvaluation.Clear, false))
        assertFalse(SubscriptionRainEligibilityPolicy.nextArmed(RadarAlertEvaluation.Unknown, false))
        assertTrue(SubscriptionRainEligibilityPolicy.nextArmed(RadarAlertEvaluation.Unknown, true))
        assertTrue(SubscriptionRainEligibilityPolicy.nextArmed(
            RadarAlertEvaluation.Approaching(15, 35, 1L),
            true,
        ))
        assertTrue(SubscriptionRainEligibilityPolicy.nextArmed(
            RadarAlertEvaluation.WetNow(1L),
            true,
        ))
    }

    @Test fun `NOTIFY XSR 01 app first suppresses matching widget only`() {
        val claim = claim("rain:episode-1", NotificationSourceClass.APP, "app")
        assertTrue(CrossSourceDeliveredClaimPolicy.suppresses(
            claim, "rain:episode-1", NotificationSourceClass.WIDGET,
        ))
        assertFalse(CrossSourceDeliveredClaimPolicy.suppresses(
            claim, "rain:episode-2", NotificationSourceClass.WIDGET,
        ))
        assertFalse(CrossSourceDeliveredClaimPolicy.suppresses(
            claim, "lightning:episode-1", NotificationSourceClass.WIDGET,
        ))
    }

    @Test fun `NOTIFY XSR 02 widget first suppresses app but never sibling widget`() {
        val claim = claim("rain:episode-1", NotificationSourceClass.WIDGET, "widget:41")
        assertTrue(CrossSourceDeliveredClaimPolicy.suppresses(
            claim, "rain:episode-1", NotificationSourceClass.APP,
        ))
        assertFalse(CrossSourceDeliveredClaimPolicy.suppresses(
            claim, "rain:episode-1", NotificationSourceClass.WIDGET,
        ))
    }

    @Test fun `NOTIFY XSR 03 provider and detail drift keep provider independent target identity`() {
        val regional = target(RadarProviderKind.METEOGROUP_REGIONAL)
        val open = target(RadarProviderKind.OPEN_RAINVIEWER)
        assertEquals(regional.monitoringKey, open.monitoringKey)
        assertFalse(regional.key == open.key)
    }

    @Test fun `NOTIFY TAP 01 coalesced acquisition preserves each widget label and target`() {
        val source = sourceFile("app/src/main/java/com/rainalarm/app/alerts/WeatherMonitoringCoordinator.kt")
        assertTrue(source.contains("val deliveryTarget = work.deliveryTarget(candidate.subscriptionId)"))
        assertTrue(source.contains("target = deliveryTarget"))
        assertTrue(source.contains("target = work.widgetTarget(config)"))
        assertTrue(source.contains("widgetStore.publish(listOf(id), snapshot)"))
    }

    @Test fun `NOTIFY TEXT 01 upcoming bounded episode includes all confirmed facts`() {
        val event = RadarAlertEvaluation.Approaching(
            15, 34, 1L,
            confirmedDurationMinutes = 20,
            confirmedPeakIntensity = .624f,
            expectedStartEpochSeconds = 15 * 60L,
            maximumKnownIntensity = .624f,
            expectedSeverity = QualitativeIntensity.LIGHT,
        )
        val text = RainAlertNotificationText.forApproaching(
            EnglishRainAlertTitleStrings,
            "Great Baddow", event, 0L, ZoneId.of("UTC"),
        )
        assertEquals("Light rain approaching Great Baddow", text.title)
        assertEquals("Starts at 00:15 · in 15 min", text.summary)
        assertEquals(
            "Starts at 00:15 · in 15 min · lasting about 20 min · peak intensity 62%",
            text.detail,
        )
    }

    @Test fun `NOTIFY TEXT 02 upcoming unbounded episode reports at least without an end`() {
        val event = RadarAlertEvaluation.Approaching(
            15, 60, 1L,
            expectedStartEpochSeconds = 15 * 60L,
            maximumKnownIntensity = .704f,
            expectedSeverity = QualitativeIntensity.MEDIUM,
        )
        val text = RainAlertNotificationText.forApproaching(
            EnglishRainAlertTitleStrings,
            "Great Baddow", event, 0L, ZoneId.of("UTC"),
        )
        assertEquals("Starts at 00:15 · in 15 min · at least 70%", text.detail)
        assertFalse(text.detail.contains("lasting"))
    }

    @Test fun `NOTIFY TEXT 03 bounded rain now includes stop remaining duration and peak`() {
        val event = RadarAlertEvaluation.WetNow(
            1L,
            confirmedRemainingMinutes = 40,
            maximumKnownIntensity = .617f,
            expectedStopEpochSeconds = 40 * 60L,
            expectedSeverity = QualitativeIntensity.MEDIUM,
        )
        val text = RainAlertNotificationText.forWetNow(
            EnglishRainAlertTitleStrings,
            "Great Baddow", event, ZoneId.of("UTC"),
        )
        assertEquals("Medium rain now at Great Baddow", text.title)
        assertEquals("Expected to stop at 00:40 · about 40 min remaining", text.summary)
        assertEquals(
            "Expected to stop at 00:40 · about 40 min remaining · peak intensity 62%",
            text.detail,
        )
    }

    @Test fun `NOTIFY TEXT 04 unbounded rain now and intensity bounds stay truthful`() {
        val hundred = RainAlertNotificationText.forWetNow(
            EnglishRainAlertTitleStrings,
            "Great Baddow",
            RadarAlertEvaluation.WetNow(1L, maximumKnownIntensity = 1.2f),
            ZoneId.of("UTC"),
        )
        assertEquals("At least 100%", hundred.detail)
        val zero = RainAlertNotificationText.forWetNow(
            EnglishRainAlertTitleStrings,
            "Great Baddow",
            RadarAlertEvaluation.WetNow(1L, maximumKnownIntensity = .004f),
            ZoneId.of("UTC"),
        )
        assertEquals("At least 0%", zero.detail)
        val missing = RainAlertNotificationText.forWetNow(
            EnglishRainAlertTitleStrings,
            "Great Baddow",
            RadarAlertEvaluation.WetNow(1L, maximumKnownIntensity = Float.NaN),
            ZoneId.of("UTC"),
        )
        assertEquals(missing.title, missing.detail)
    }

    @Test fun `NOTIFY TEXT 05 likely snow follows equivalent timing rules`() {
        val text = RainAlertNotificationText.forApproaching(
            EnglishRainAlertTitleStrings,
            "York",
            RadarAlertEvaluation.Approaching(
                1, 5, 1L,
                expectedStartEpochSeconds = 60L,
                likelySnow = true,
                maximumKnownIntensity = .5f,
                expectedSeverity = QualitativeIntensity.MEDIUM,
            ),
            0L,
            ZoneId.of("UTC"),
        )
        assertEquals("Medium snow likely approaching York", text.title)
        assertEquals("Starts at 00:01 · in 1 min · at least 50%", text.detail)
    }

    @Test fun `NOTIFY SEVERITY 01 exact Now graph bands and clamps are authoritative`() {
        fun quality(value: Float?) = RainEventSeverityPolicy.classifyNormalized(value)?.qualitative
        assertEquals(QualitativeIntensity.LIGHT, quality(-1f))
        assertEquals(QualitativeIntensity.LIGHT, quality(1f / 3f - 0.0001f))
        assertEquals(QualitativeIntensity.MEDIUM, quality(1f / 3f))
        assertEquals(QualitativeIntensity.MEDIUM, quality(2f / 3f - 0.0001f))
        assertEquals(QualitativeIntensity.SEVERE, quality(2f / 3f))
        assertEquals(QualitativeIntensity.SEVERE, quality(2f))
        assertEquals(null, quality(Float.NaN))
        assertEquals(null, quality(null))
    }

    @Test fun `NOTIFY SEVERITY 02 event upper envelope excludes unrelated later rain`() {
        val points = (0..60).map { minute ->
            when (minute) {
                in 10..12 -> RainMinutePoint(minute, .25f, .30f, .35f, true)
                in 30..35 -> RainMinutePoint(minute, .25f, .80f, 1f, true)
                else -> RainMinutePoint(minute, 0f, 0f, 0f, true)
            }
        }
        val series = RainMinuteSeries(
            0L, points, "test", 1.0, RainMinuteAvailability.AVAILABLE,
            intensityEncoding = RadarIntensityEncoding.REGIONAL_AREA_CHART,
        )
        val analysis = requireNotNull(RainMinuteSeriesAnalyzer.analyze(series))
        assertEquals(10, analysis.arrivalMinute)
        assertEquals(13, analysis.endMinute)
        assertEquals(
            QualitativeIntensity.LIGHT,
            RainEventSeverityPolicy.forEvent(series, analysis)?.qualitative,
        )
        assertEquals(
            QualitativeIntensity.LIGHT,
            (RainAlertDecisionEngine.evaluateMinuteSeries(series) as
                RadarAlertEvaluation.Approaching).expectedSeverity,
        )
    }

    @Test fun `NOTIFY SEVERITY 03 maximum graph envelope not average drives title`() {
        val points = (0..60).map { minute ->
            if (minute in 5..9) RainMinutePoint(minute, .25f, .30f, .75f, true)
            else RainMinutePoint(minute, 0f, 0f, 0f, true)
        }
        val event = RainAlertDecisionEngine.evaluateMinuteSeries(
            RainMinuteSeries(
                0L, points, "test", 1.0, RainMinuteAvailability.AVAILABLE,
                intensityEncoding = RadarIntensityEncoding.REGIONAL_AREA_CHART,
            ),
        ) as RadarAlertEvaluation.Approaching
        assertEquals(QualitativeIntensity.SEVERE, event.expectedSeverity)
        assertEquals(
            "Severe rain approaching Great Baddow",
            RainAlertNotificationText.forApproaching(
                EnglishRainAlertTitleStrings,
                "Great Baddow",
                event,
                0L,
                ZoneId.of("UTC"),
            ).title,
        )
    }

    @Test fun `NOTIFY SEVERITY 04 all bands rain now snow fallback and source parity`() {
        QualitativeIntensity.entries.forEach { severity ->
            val label = severity.name.lowercase().replaceFirstChar(Char::uppercase)
            val approaching = RadarAlertEvaluation.Approaching(
                5, 8, 1L, expectedSeverity = severity,
            )
            val app = RainAlertNotificationText.forApproaching(
                EnglishRainAlertTitleStrings, "Great Baddow", approaching, 0L, ZoneId.of("UTC"),
            )
            val widget = RainAlertNotificationText.forApproaching(
                EnglishRainAlertTitleStrings, "Great Baddow", approaching, 0L, ZoneId.of("UTC"),
            )
            assertEquals("$label rain approaching Great Baddow", app.title)
            assertEquals(app, widget)
        }
        assertEquals(
            "Light rain now at Great Baddow",
            RainAlertNotificationText.forWetNow(
                EnglishRainAlertTitleStrings,
                "Great Baddow",
                RadarAlertEvaluation.WetNow(1L, expectedSeverity = QualitativeIntensity.LIGHT),
                ZoneId.of("UTC"),
            ).title,
        )
        assertEquals(
            "Severe snow likely approaching York",
            RainAlertNotificationText.forApproaching(
                EnglishRainAlertTitleStrings,
                "York",
                RadarAlertEvaluation.Approaching(
                    5, 8, 1L, likelySnow = true,
                    expectedSeverity = QualitativeIntensity.SEVERE,
                ),
                0L,
                ZoneId.of("UTC"),
            ).title,
        )
        assertEquals(
            "Rain approaching Great Baddow",
            RainAlertNotificationText.forApproaching(
                EnglishRainAlertTitleStrings,
                "Great Baddow",
                RadarAlertEvaluation.Approaching(5, 8, 1L),
                0L,
                ZoneId.of("UTC"),
            ).title,
        )
    }

    @Test fun `NOTIFY SEVERITY 05 combined uses rain title while lightning only and identity stay unchanged`() {
        val source = sourceFile("app/src/main/java/com/rainalarm/app/alerts/WeatherMonitoringCoordinator.kt")
        assertTrue(source.contains("CombinedAlertPolicy.Kind.COMBINED -> rainContent?.title"))
        val strings = File(resourceRoot(), "values/strings.xml").readText()
        assertTrue(strings.contains(
            "<string name=\"notification_lightning_title\">Lightning activity detected near %1\$s</string>",
        ))
        val event = RadarAlertEvaluation.Approaching(
            5, 8, 77L, expectedSeverity = QualitativeIntensity.LIGHT,
        )
        val first = RainAlertDecisionEngine.decide(event, AlertMemory(), 1_000L)
        val changedTitleOnly = event.copy(expectedSeverity = QualitativeIntensity.SEVERE)
        val second = RainAlertDecisionEngine.decide(changedTitleOnly, first.nextMemory, 1_001L)
        assertTrue(first.shouldNotify)
        assertFalse(second.shouldNotify)
        assertEquals(first.nextMemory.lastEventIdentity, second.nextMemory.lastEventIdentity)
    }

    @Test fun `NOTIFY DEL 01 DND supports same day overnight and cross midnight bounds`() {
        val day = QuietHours(true, 9 * 60, 17 * 60)
        assertTrue(QuietHoursPolicy.contains(day, 9 * 60))
        assertTrue(QuietHoursPolicy.contains(day, 16 * 60 + 59))
        assertFalse(QuietHoursPolicy.contains(day, 17 * 60))
        val overnight = QuietHours(true, 22 * 60, 7 * 60)
        assertTrue(QuietHoursPolicy.isQuiet(
            overnight, Instant.parse("2026-10-01T23:00:00Z"), ZoneId.of("UTC"),
        ))
        assertFalse(QuietHoursPolicy.isQuiet(
            overnight, Instant.parse("2026-10-02T07:00:00Z"), ZoneId.of("UTC"),
        ))
    }

    @Test fun `NOTIFY DEL 02 components combine independently`() {
        assertEquals(CombinedAlertPolicy.Kind.RAIN, CombinedAlertPolicy.kind(true, false))
        assertEquals(CombinedAlertPolicy.Kind.LIGHTNING, CombinedAlertPolicy.kind(false, true))
        assertEquals(CombinedAlertPolicy.Kind.COMBINED, CombinedAlertPolicy.kind(true, true))
        assertEquals(CombinedAlertPolicy.Kind.NONE, CombinedAlertPolicy.kind(false, false))
    }

    @Test fun `NOTIFY DEL 03 successful-post ordering and transaction serialization are wired`() {
        val source = sourceFile("app/src/main/java/com/rainalarm/app/alerts/WeatherMonitoringCoordinator.kt")
        assertTrue(source.contains("transactionMutex.withLock"))
        assertTrue(source.contains("if (posted) monitoringStore.recordSuccessfulDelivery"))
        assertTrue(source.indexOf("publisher.publish(") <
            source.indexOf("monitoringStore.recordSuccessfulDelivery("))
        assertFalse(source.contains("delay("))
    }

    @Test fun `NOTIFY LTG 01 display-only evidence and setting toggles do not consume an episode`() {
        val source = sourceFile("app/src/main/java/com/rainalarm/app/alerts/MonitoringStateStore.kt")
        val reset = source.substringAfter("fun resetLightningEligibility")
            .substringBefore("fun evaluate")
        assertTrue(reset.contains("lightningAlertCheckpoint = null"))
        assertFalse(reset.contains("lightningDeliveredClaim = null"))
        assertFalse(reset.contains("lightningEpisodeActive = false"))
        val evaluation = source.substringAfter("fun evaluate")
            .substringBefore("fun resolveDelivery")
        assertFalse(evaluation.contains("!subscription.lightningEnabled && lightningEvent"))
    }

    @Test fun `NOTIFY CFG 05 configuration exposes per-widget controls and no content selector`() {
        val source = sourceFile("app/src/main/java/com/rainalarm/app/widget/WidgetConfigurationActivity.kt")
        assertTrue(source.contains("notificationsEnabled"))
        assertTrue(source.contains("includeLightning"))
        assertTrue(source.contains("if (notificationsEnabled)"))
        assertFalse(source.contains("FilterChip"))
    }

    @Test fun `NOTIFY I18N 01 all full locales contain concise shared notification resources`() {
        val root = resourceRoot()
        listOf("values", "values-de", "values-fr", "values-nl", "values-cy", "values-ga")
            .forEach { folder ->
                val xml = File(root, "$folder/strings.xml").readText()
                listOf(
                    "notification_starts_eta_plural",
                    "notification_bounded_detail",
                    "notification_unbounded_detail",
                    "notification_rain_now_title",
                    "notification_rain_severity_title",
                    "notification_snow_severity_title",
                    "notification_rain_now_severity_title",
                    "notification_snow_now_severity_title",
                    "notification_stops_remaining_plural",
                    "notification_lightning_detail",
                    "notification_combined_detail",
                    "widget_notifications",
                    "widget_include_lightning",
                ).forEach { key -> assertTrue("$folder missing $key", xml.contains("name=\"$key\"")) }
                val combined = Regex(
                    """<string name="notification_combined_detail">([^<]+)</string>""",
                ).find(xml)?.groupValues?.get(1)
                assertTrue("$folder combined text missing", !combined.isNullOrBlank())
                assertFalse("$folder retained obsolete qualification", combined!!.contains("forecast", true))
            }
    }

    private fun target(provider: RadarProviderKind) = FrozenMonitorTarget(
        "great-baddow",
        "Great Baddow",
        51.717,
        .506,
        provider,
    )

    private fun claim(
        event: String,
        source: NotificationSourceClass,
        subscription: String,
    ) = DeliveredEventClaim(event, source, subscription, 1L)

    private fun sourceFile(relative: String): String = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, relative) }
        .first(File::isFile)
        .readText()

    private fun resourceRoot(): File = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "app/src/main/res") }
        .first(File::isDirectory)
}
