package com.rainalarm.app.widget

import com.rainalarm.app.alerts.LightningDetectionPolicy
import com.rainalarm.app.data.PlaceCollection
import com.rainalarm.app.data.RadarProviderKind
import com.rainalarm.app.data.SavedPlace
import com.rainalarm.app.domain.MiniCompassPresentation
import com.rainalarm.app.domain.PrecipitationPresentationKind
import com.rainalarm.app.domain.RadarIntensityEncoding
import com.rainalarm.app.domain.RainMinuteAvailability
import com.rainalarm.app.domain.RainMinutePoint
import com.rainalarm.app.domain.RainMinuteSeries
import com.rainalarm.app.domain.WeatherPresentationPolicy
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class WidgetPoliciesTest {
    private val place = SavedPlace("York", 53.96, -1.08, id = "york")

    @Test fun `saved widget targets resolve independently of app selection`() {
        val second = SavedPlace("Cardiff", 51.48, -3.18, id = "cardiff")
        val places = PlaceCollection(places = listOf(place, second), selectedId = second.id)
        val fixed = WidgetTargetResolver.resolve(
            RainAlarmWidgetConfig(1, savedPlace = WidgetSavedPlaceTarget.from(place)),
            places,
            RadarProviderKind.METEOGROUP_REGIONAL,
        ) as WidgetTargetResolution.Resolved
        val other = WidgetTargetResolver.resolve(
            RainAlarmWidgetConfig(2, savedPlace = WidgetSavedPlaceTarget.from(second)),
            places,
            RadarProviderKind.METEOGROUP_REGIONAL,
        ) as WidgetTargetResolution.Resolved
        assertEquals(place.id, fixed.target.stableId)
        assertEquals(second.id, other.target.stableId)
        assertEquals(LightningDetectionPolicy.defaultRadiusKilometres, fixed.target.radiusKilometres, 0.0)
    }

    @Test fun `widget without a saved target requires configuration`() {
        val places = PlaceCollection(places = listOf(place), selectedId = place.id)
        val config = RainAlarmWidgetConfig(3)
        assertEquals(
            WidgetTargetProblem.CONFIGURATION_REQUIRED,
            (WidgetTargetResolver.resolve(
                config,
                places,
                RadarProviderKind.METEOGROUP_REGIONAL,
            ) as WidgetTargetResolution.Unavailable).problem,
        )
    }

    @Test fun `legacy follow freezes selected saved place then stays idempotent`() {
        val second = SavedPlace("Cardiff", 51.48, -3.18, id = "cardiff")
        val places = PlaceCollection(places = listOf(place, second), selectedId = second.id)
        val legacy = RainAlarmWidgetConfig(4)
        val migrated = WidgetLegacyTargetMigrationPolicy.resolve(legacy, places, place.id)
        assertEquals(second.id, migrated?.stableId)
        val frozen = legacy.copy(savedPlace = migrated)
        val changedSelection = places.copy(selectedId = place.id)
        assertEquals(migrated, WidgetLegacyTargetMigrationPolicy.resolve(frozen, changedSelection, place.id))
    }

    @Test fun `legacy current selection falls back to startup saved place`() {
        val current = SavedPlace("Current", 52.0, -1.0, id = "current", isCurrentLocation = true)
        val places = PlaceCollection(places = listOf(current, place), selectedId = current.id)
        val target = WidgetLegacyTargetMigrationPolicy.resolve(
            RainAlarmWidgetConfig(5), places, place.id,
        )
        assertEquals(place.id, target?.stableId)
    }

    @Test fun `responsive priority and opacity remain deterministic`() {
        assertEquals(WidgetSizeClass.COMPACT, WidgetResponsivePolicy.sizeClass(149))
        assertEquals(WidgetSizeClass.SMALL, WidgetResponsivePolicy.sizeClass(150))
        assertEquals(WidgetSizeClass.MEDIUM, WidgetResponsivePolicy.sizeClass(250))
        assertEquals(WidgetSizeClass.EXPANDED, WidgetResponsivePolicy.sizeClass(340))
        assertEquals(4, WidgetResponsivePolicy.visibleGroups(150))
        assertEquals(4, WidgetResponsivePolicy.visibleGroups(250))
        assertEquals(0, WidgetOpacityPolicy.applyToArgb(0xff112233.toInt(), 0).ushr(24))
        assertEquals(255, WidgetOpacityPolicy.applyToArgb(0xff112233.toInt(), 100).ushr(24))
        assertEquals(0x112233, WidgetOpacityPolicy.applyToArgb(0xff112233.toInt(), 50) and 0xffffff)
    }

    @Test fun `text bearing breakpoints share fixed compass while compact fills its square`() {
        assertEquals(null, WidgetResponsivePolicy.compassDiameterDp(WidgetSizeClass.COMPACT))
        assertEquals(88, WidgetResponsivePolicy.compassDiameterDp(WidgetSizeClass.SMALL))
        assertEquals(88, WidgetResponsivePolicy.compassDiameterDp(WidgetSizeClass.MEDIUM))
        assertEquals(88, WidgetResponsivePolicy.compassDiameterDp(WidgetSizeClass.EXPANDED))
        // Representative Samsung One UI widths retain useful status space at 2x1 and 3x1.
        assertEquals(86, WidgetResponsivePolicy.textStatusWidthDp(200))
        assertEquals(166, WidgetResponsivePolicy.textStatusWidthDp(280))
    }

    @Test fun `expanded graph grows into dead space and spans the full axis-free plot`() {
        val previousWidthDp = 152
        val graphWidthDp = WidgetGraphLayoutPolicy.widthDp(400)
        assertEquals(192, graphWidthDp)
        assertTrue(graphWidthDp >= previousWidthDp * 1.25f)
        assertEquals(
            400,
            WidgetResponsivePolicy.expandedFixedContentWidthDp + graphWidthDp,
        )
        assertEquals(576, WidgetGraphLayoutPolicy.pixels(graphWidthDp, 3f))
        val inset = 3f
        assertEquals(inset, WidgetGraphLayoutPolicy.sampleX(0, 61, 576, inset), 0f)
        assertEquals(573f, WidgetGraphLayoutPolicy.sampleX(60, 61, 576, inset), 0f)
        assertEquals(inset, WidgetGraphLayoutPolicy.sampleY(1f, 228, inset), 0f)
        assertEquals(225f, WidgetGraphLayoutPolicy.sampleY(0f, 228, inset), 0f)
    }

    @Test fun `mini compass uses same wet threshold colour snow and clear semantics as Now`() {
        val rain = WeatherPresentationPolicy.from(series(wet = true, snow = false), 8.0)
        assertEquals(PrecipitationPresentationKind.RAIN, rain.kind)
        assertEquals("Now", rain.centerText)
        assertTrue(rain.peakColorArgb != null)
        val snow = WeatherPresentationPolicy.from(series(wet = false, snow = true), 8.0)
        assertEquals(PrecipitationPresentationKind.LIKELY_SNOW, snow.kind)
        assertEquals(10, snow.arrivalMinute)
        assertTrue(snow.peakColorArgb != rain.peakColorArgb)
        val clear = WeatherPresentationPolicy.from(series(), 8.4)
        assertEquals(PrecipitationPresentationKind.CLEAR, clear.kind)
        assertEquals("8°", clear.centerText)
        val partial = series().copy(
            points = series().points.take(30),
            availability = RainMinuteAvailability.PARTIAL,
        )
        assertEquals(PrecipitationPresentationKind.UNKNOWN, WeatherPresentationPolicy.from(partial, 8.0).kind)
        assertEquals(null, WeatherPresentationPolicy.from(partial, 8.0).peakColorArgb)
    }

    @Test fun `known provider direction survives cache and every rainy responsive face`() {
        val target = FrozenMonitorTarget(
            place.id, place.name, place.latitude, place.longitude,
            RadarProviderKind.METEOGROUP_REGIONAL,
        )
        val directional = series(wet = true).copy(
            travelBearingDegrees = 120.0,
            sourceBearingDegrees = 300.0,
        )
        val snapshot = WidgetWeatherSnapshot.from(
            target, directional, 8.0, WidgetLightningState.NO_DETECTION,
            1_000L, 1_000L, 1L,
        )
        val restored = requireNotNull(snapshot.rainSeries(1_000L))
        assertEquals(300.0, restored.sourceBearingDegrees!!, 0.0)
        val rain = WidgetRainPresentationPolicy.from(snapshot, restored)
        val primary = WidgetPrimaryPresentation("Now", null, "Rain now")
        WidgetSizeClass.entries.forEach { sizeClass ->
            val mode = if (sizeClass == WidgetSizeClass.COMPACT) {
                WidgetCompassContentMode.COMPASS_ONLY
            } else WidgetCompassContentMode.TEXT_BEARING
            val face = WidgetCompassContentPolicy.presentation(
                mode, WidgetPrimaryContent.SMART, rain, primary,
            )
            assertTrue(face.hasPrecipitation)
            assertEquals("$sizeClass", 300.0, face.sourceBearingDegrees!!, 0.0)
        }
    }

    @Test fun `unavailable provider direction never invents a widget marker`() {
        val target = FrozenMonitorTarget(
            place.id, place.name, place.latitude, place.longitude,
            RadarProviderKind.METEOGROUP_REGIONAL,
        )
        val snapshot = WidgetWeatherSnapshot.from(
            target, series(wet = true), 8.0, WidgetLightningState.NO_DETECTION,
            1_000L, 1_000L, 1L,
        )
        val rain = WidgetRainPresentationPolicy.from(snapshot, snapshot.rainSeries(1_000L))
        assertTrue(rain.hasPrecipitation)
        assertNull(rain.sourceBearingDegrees)
    }

    @Test fun `compact dry compass selects one temperature or neutral centre payload`() {
        val dry = MiniCompassPresentation(
            kind = PrecipitationPresentationKind.CLEAR,
            centerText = "—",
            completeHour = false,
        )
        val temperature = WidgetPrimaryPresentation("17°", null, "17°")
        val withTemperature = WidgetCompassContentPolicy.presentation(
            WidgetCompassContentMode.COMPASS_ONLY,
            WidgetPrimaryContent.SMART,
            dry,
            temperature,
        )
        assertEquals("17°", withTemperature.centerText)
        assertNull(withTemperature.centerUnit)
        assertFalse(withTemperature.hasPrecipitation)

        val withoutTemperature = WidgetCompassContentPolicy.presentation(
            WidgetCompassContentMode.COMPASS_ONLY,
            WidgetPrimaryContent.SMART,
            dry,
            WidgetPrimaryPresentation("—", null, "Dry now"),
        )
        assertEquals("—", withoutTemperature.centerText)
        assertNull(withoutTemperature.centerUnit)
    }

    @Test fun `text bearing dry compass reserves temperature for status column`() {
        val sharedClear = MiniCompassPresentation(
            kind = PrecipitationPresentationKind.CLEAR,
            centerText = "17°",
            completeHour = true,
            knownHorizonMinutes = 60,
        )
        val temperature = WidgetPrimaryPresentation("17°", null, "17°")
        val face = WidgetCompassContentPolicy.presentation(
            WidgetCompassContentMode.TEXT_BEARING,
            WidgetPrimaryContent.SMART,
            sharedClear,
            temperature,
        )
        assertEquals("—", face.centerText)
        assertNull(face.centerUnit)
        assertEquals("17°", temperature.headline)
    }

    @Test fun `compact precipitation faces stay intact and legacy content choices are ignored`() {
        val rain = WeatherPresentationPolicy.from(series(wet = true), 17.0)
        val snow = WeatherPresentationPolicy.from(series(snow = true), 17.0)
        val rainFace = WidgetCompassContentPolicy.presentation(
            WidgetCompassContentMode.COMPASS_ONLY,
            WidgetPrimaryContent.SMART,
            rain,
            WidgetPrimaryPresentation(rain.centerText, rain.centerUnit, "Rain now"),
        )
        val snowFace = WidgetCompassContentPolicy.presentation(
            WidgetCompassContentMode.COMPASS_ONLY,
            WidgetPrimaryContent.RAIN,
            snow,
            WidgetPrimaryPresentation(snow.centerText, snow.centerUnit, "Snow in 10 min"),
        )
        assertEquals(rain, rainFace)
        assertEquals(snow, snowFace)

        val legacyLightningFace = WidgetCompassContentPolicy.presentation(
            WidgetCompassContentMode.COMPASS_ONLY,
            WidgetPrimaryContent.LIGHTNING,
            rain,
            WidgetPrimaryPresentation("⚡", null, "Lightning nearby", lightningBadge = true),
        )
        assertEquals(rain, legacyLightningFace)
    }

    @Test fun `successful partial rain horizon is not reported as a whole update failure`() {
        val target = FrozenMonitorTarget(
            place.id,
            place.name,
            place.latitude,
            place.longitude,
            RadarProviderKind.METEOGROUP_REGIONAL,
        )
        val partial = series().copy(
            points = series().points.take(30),
            availability = RainMinuteAvailability.PARTIAL,
        )
        val snapshot = WidgetWeatherSnapshot.from(
            target,
            partial,
            null,
            WidgetLightningState.NO_DETECTION,
            1_000L,
            1_000L,
            1L,
        )
        assertEquals(
            WidgetRainAnswer.INCOMPLETE,
            WidgetRainAnswerPolicy.answer(snapshot, snapshot.rainSeries(1_000L)),
        )
        assertEquals(
            WidgetRainAnswer.UNAVAILABLE,
            WidgetRainAnswerPolicy.answer(
                snapshot.copy(updateUnavailable = true),
                snapshot.rainSeries(1_000L),
            ),
        )
    }

    @Test fun `widget configuration validates dnd and separates display from notification demand`() {
        assertFalse(RainAlarmWidgetConfig(1, quietHoursEnabled = true).copy(
            quietStartMinuteOfDay = 60,
            quietEndMinuteOfDay = 60,
        ).valid)
        assertFalse(RainAlarmWidgetConfig(1).requiresLightningDisplay(100))
        assertTrue(RainAlarmWidgetConfig(1).requiresLightningDisplay(400))
        assertFalse(RainAlarmWidgetConfig(1).requiresLightningAcquisition(100))
        assertTrue(RainAlarmWidgetConfig(
            1,
            notificationsEnabled = true,
            includeLightningNotifications = true,
        ).requiresLightningAcquisition(100))
        assertFalse(RainAlarmWidgetConfig(
            1,
            notificationsEnabled = false,
            includeLightningNotifications = true,
        ).requiresLightningAcquisition(100))
    }

    @Test fun `failed refresh retains last coherent rain snapshot for the same target`() {
        val target = FrozenMonitorTarget(
            place.id,
            place.name,
            place.latitude,
            place.longitude,
            RadarProviderKind.METEOGROUP_REGIONAL,
        )
        val previous = WidgetWeatherSnapshot.from(
            target,
            series(wet = true),
            8.0,
            WidgetLightningState.NO_DETECTION,
            900L,
            1_000L,
            1L,
        )
        val failed = WidgetWeatherSnapshot.from(
            target,
            RainMinuteSeries.unavailable(1_100L, "test", "network"),
            null,
            WidgetLightningState.UNAVAILABLE,
            null,
            1_100L,
            2L,
        )
        val merged = WidgetSnapshotCachePolicy.merge(previous, failed, nowEpochSeconds = 1_100L)
        assertEquals(previous.points, merged.points)
        assertEquals(previous.lastSuccessfulEpochSeconds, merged.lastSuccessfulEpochSeconds)
        assertEquals(previous.temperatureC, merged.temperatureC)
        assertFalse(merged.updateUnavailable)
        assertEquals(2L, merged.generation)
    }

    @Test fun `cached widget state expires only at explicit hard boundary and recovers`() {
        val target = FrozenMonitorTarget(
            place.id, place.name, place.latitude, place.longitude,
            RadarProviderKind.METEOGROUP_REGIONAL,
        )
        val previous = WidgetWeatherSnapshot.from(
            target, series(), 8.0, WidgetLightningState.NO_DETECTION, 900L, 1_000L, 1L,
        )
        val failed = WidgetWeatherSnapshot.from(
            target,
            RainMinuteSeries.unavailable(1_100L, "test", "network"),
            null,
            WidgetLightningState.UNAVAILABLE,
            null,
            1_100L,
            2L,
        )
        val within = WidgetSnapshotCachePolicy.merge(
            previous,
            failed,
            1_000L + WidgetSnapshotCachePolicy.HARD_EXPIRY_SECONDS,
        )
        assertEquals(previous.points, within.points)
        val expired = WidgetSnapshotCachePolicy.merge(
            previous,
            failed,
            1_001L + WidgetSnapshotCachePolicy.HARD_EXPIRY_SECONDS,
        )
        assertTrue(expired.updateUnavailable)
        assertEquals(RainMinuteAvailability.UNAVAILABLE, expired.seriesAvailability)
        val recovered = WidgetSnapshotCachePolicy.merge(expired, previous.copy(generation = 3L), 2_000L)
        assertEquals(RainMinuteAvailability.AVAILABLE, recovered.seriesAvailability)
    }

    @Test fun `cached rain countdown advances and disappears when timestamped coverage ends`() {
        val target = FrozenMonitorTarget(
            place.id, place.name, place.latitude, place.longitude,
            RadarProviderKind.METEOGROUP_REGIONAL,
        )
        val snapshot = WidgetWeatherSnapshot.from(
            target, series(snow = true), 8.0, WidgetLightningState.NO_DETECTION,
            1_000L, 1_000L, 1L,
        )
        val initial = WeatherPresentationPolicy.from(snapshot.rainSeries(1_000L), 8.0)
        val advanced = WeatherPresentationPolicy.from(snapshot.rainSeries(1_300L), 8.0)
        assertEquals(10, initial.arrivalMinute)
        assertEquals(5, advanced.arrivalMinute)
        assertEquals(
            null,
            snapshot.rainSeries(
                1_000L + 60 * 60L + 1L,
            ),
        )
    }

    @Test fun `partial stream success keeps its own freshness without extending stale fields`() {
        val target = FrozenMonitorTarget(
            place.id, place.name, place.latitude, place.longitude,
            RadarProviderKind.METEOGROUP_REGIONAL,
        )
        val previous = WidgetWeatherSnapshot.from(
            target, series(), 8.0, WidgetLightningState.NO_DETECTION, 1_000L, 1_000L, 1L,
        )
        val temperatureOnly = WidgetWeatherSnapshot.from(
            target,
            RainMinuteSeries.unavailable(4_700L, "test", "radar unavailable"),
            9.0,
            WidgetLightningState.UNAVAILABLE,
            null,
            4_700L,
            2L,
        )
        val merged = WidgetSnapshotCachePolicy.merge(previous, temperatureOnly, 4_700L)
        assertEquals(4_700L, merged.lastSuccessfulEpochSeconds)
        assertEquals(9.0, merged.temperatureC!!, 0.0)
        assertEquals(null, merged.rainSeries(4_700L))
        assertFalse(merged.updateUnavailable)
        val displayed = WidgetSnapshotCachePolicy.forDisplay(merged, 4_700L)
        assertEquals(9.0, displayed.temperatureC!!, 0.0)
        assertEquals(WidgetLightningState.UNAVAILABLE, displayed.lightning)
    }

    @Test fun `provider changes do not create a new lightning episode identity`() {
        val regional = FrozenMonitorTarget(
            place.id, place.name, place.latitude, place.longitude,
            RadarProviderKind.METEOGROUP_REGIONAL,
        )
        val open = regional.copy(selectedProvider = RadarProviderKind.OPEN_RAINVIEWER)
        assertEquals(regional.monitoringKey, open.monitoringKey)
        assertTrue(regional.key != open.key)
    }

    @Test fun `presentation clock advances cached countdown without changing acquisition time`() {
        val target = FrozenMonitorTarget(
            place.id, place.name, place.latitude, place.longitude,
            RadarProviderKind.METEOGROUP_REGIONAL,
        )
        val start = 10_000L
        val countdownSeries = RainMinuteSeries(
            startEpochSeconds = start,
            points = (0..60).map { minute ->
                val value = if (minute in 47..55) 0.7f else 0f
                RainMinutePoint(minute, value, value, value, minute > 0)
            },
            sourceLabel = "test",
            confidence = 1.0,
            availability = RainMinuteAvailability.AVAILABLE,
            intensityEncoding = RadarIntensityEncoding.REGIONAL_GRAYSCALE,
        )
        val snapshot = WidgetWeatherSnapshot.from(
            target,
            countdownSeries,
            8.0,
            WidgetLightningState.NO_DETECTION,
            lightningFrameEpochSeconds = start,
            updatedEpochSeconds = start,
            generation = 1L,
        )

        fun presentation(atSeconds: Long) = WeatherPresentationPolicy.from(
            snapshot.rainSeries(atSeconds),
            snapshot.temperatureC,
        )

        assertEquals(47, presentation(start + 10).arrivalMinute)
        assertEquals(46, presentation(start + 60 + 10).arrivalMinute)
        assertEquals(1, presentation(start + 46 * 60 + 10).arrivalMinute)
        val rainingNow = presentation(start + 47 * 60)
        assertTrue(rainingNow.rainingNow)
        assertEquals("Now", rainingNow.centerText)
        assertNull(rainingNow.centerUnit)
        assertEquals(start, snapshot.updatedEpochSeconds)
        assertNull(snapshot.rainSeries(start + 60 * 60 + 1))
    }

    @Test fun `presentation ticker follows wall clock boundaries across time changes`() {
        val utc = ZoneOffset.UTC
        fun clock(value: String, zone: ZoneId = utc) = Clock.fixed(Instant.parse(value), zone)

        assertEquals(
            Instant.parse("2026-09-30T12:35:00Z"),
            WidgetPresentationTickPolicy.nextMinuteBoundary(clock("2026-09-30T12:34:12.345Z")),
        )
        assertEquals(
            Instant.parse("2026-10-01T00:00:00Z"),
            WidgetPresentationTickPolicy.nextMinuteBoundary(clock("2026-09-30T23:59:59.500Z")),
        )
        val london = ZoneId.of("Europe/London")
        assertEquals(
            Instant.parse("2026-03-29T01:00:00Z"),
            WidgetPresentationTickPolicy.nextMinuteBoundary(
                clock("2026-03-29T00:59:30Z", london),
            ),
        )
        // Re-reading an injected clock after a manual time jump computes a new boundary directly;
        // it never carries forward a stale interval chain.
        assertEquals(30_000L, WidgetPresentationTickPolicy.delayUntilNextMinuteMillis(
            clock("2026-09-30T09:12:30Z"),
        ))
        assertEquals(5_000L, WidgetPresentationTickPolicy.delayUntilNextMinuteMillis(
            clock("2026-09-30T18:47:55Z"),
        ))
    }

    @Test fun `every rain-first widget with usable data keeps the shared ticker active`() {
        val target = FrozenMonitorTarget(
            place.id, place.name, place.latitude, place.longitude,
            RadarProviderKind.METEOGROUP_REGIONAL,
        )
        val snapshot = WidgetWeatherSnapshot.from(
            target, series(snow = true), 8.0, WidgetLightningState.NO_DETECTION,
            1_000L, 1_000L, 1L,
        )
        val smart = RainAlarmWidgetConfig(1, primaryContent = WidgetPrimaryContent.SMART)
        val temperature = RainAlarmWidgetConfig(2, primaryContent = WidgetPrimaryContent.TEMPERATURE)
        assertTrue(WidgetPresentationTickPolicy.requiresTick(smart, snapshot, 110, 1_000L))
        assertTrue(WidgetPresentationTickPolicy.requiresTick(temperature, snapshot, 110, 1_000L))
        assertTrue(WidgetPresentationTickPolicy.requiresTick(temperature, snapshot, 200, 1_000L))
        assertFalse(WidgetPresentationTickPolicy.requiresTick(smart, snapshot, 110, 4_601L))
        assertFalse(WidgetPresentationTickPolicy.shouldSchedule(emptyList()))
        assertFalse(WidgetPresentationTickPolicy.shouldSchedule(listOf(false, false)))
        assertTrue(WidgetPresentationTickPolicy.shouldSchedule(listOf(false, true)))
    }

    @Test fun `minute ticker is presentation only and non wakeup`() {
        val source = listOf(
            File("src/main/java/com/rainalarm/app/widget/WidgetPresentationTicker.kt"),
            File("app/src/main/java/com/rainalarm/app/widget/WidgetPresentationTicker.kt"),
        ).first(File::isFile).readText()
        assertTrue(source.contains("AlarmManager.ELAPSED_REALTIME"))
        assertTrue(source.contains("isInteractive"))
        assertTrue(source.contains("WidgetUpdatePublisher.updateAll"))
        assertTrue(source.contains("duplicate_suppressed"))
        assertFalse(source.contains("ELAPSED_REALTIME_WAKEUP"))
        assertFalse(source.contains("setExact"))
        assertFalse(source.contains("WeatherMonitoringCoordinator"))
        val minuteDeliveryBranch = source.substringAfter(
            "WidgetPresentationTicker.ACTION_TICK ->",
        ).substringBefore("Intent.ACTION_BOOT_COMPLETED")
        assertFalse(minuteDeliveryBranch.contains("RainAlertScheduler"))
        assertFalse(source.contains("PlatformLocationClient"))
    }

    private fun series(wet: Boolean = false, snow: Boolean = false): RainMinuteSeries {
        val points = (0..60).map { minute ->
            val value = when {
                wet && minute <= 8 -> 0.7f
                !wet && snow && minute in 10..20 -> 0.7f
                else -> 0f
            }
            RainMinutePoint(minute, value, value, value, minute > 0, snow && value > 0f)
        }
        return RainMinuteSeries(
            1_000L,
            points,
            "test",
            1.0,
            RainMinuteAvailability.AVAILABLE,
            intensityEncoding = RadarIntensityEncoding.REGIONAL_GRAYSCALE,
        )
    }
}
