package com.rainalarm.app.ui

import com.rainalarm.app.data.EumetLayerMetadata
import com.rainalarm.app.data.EumetProduct
import com.rainalarm.app.data.RadarMapLayer
import com.rainalarm.app.data.WindGrid
import com.rainalarm.app.data.WindGridFailureDiagnostic
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherDataStatusPolicyTest {
    @Test fun `newly fetched wind grid is never future relative to presentation clock`() {
        val grid = WindGrid("viewport", emptyList(), emptyList(), fetchedEpochSeconds = 1_020L)

        assertEquals(1_020L, WindGridPresentationClockPolicy.reconcile(1_000L, grid))
        assertEquals(1_040L, WindGridPresentationClockPolicy.reconcile(1_040L, grid))
        assertEquals(1_000L, WindGridPresentationClockPolicy.reconcile(1_000L, null))
    }

    @Test fun `canonical vocabulary is exact with bounded progress`() {
        assertEquals("Radar loading", WeatherDataStatusPolicy.loading(WeatherDataKind.RADAR))
        assertEquals("Radar loading 3/49",
            WeatherDataStatusPolicy.loading(WeatherDataKind.RADAR, 3, 49))
        assertEquals("Radar loading 49/49",
            WeatherDataStatusPolicy.loading(WeatherDataKind.RADAR, 90, 49))
        assertEquals("Wind loading", WeatherDataStatusPolicy.loading(WeatherDataKind.WIND))
        assertEquals("Clouds loading 5/6",
            WeatherDataStatusPolicy.loading(WeatherDataKind.CLOUDS, 5, 6))
        assertEquals("Lightning loading 2/8",
            WeatherDataStatusPolicy.loading(WeatherDataKind.LIGHTNING, 2, 8))
        WeatherDataKind.entries.forEach { kind ->
            assertEquals("${kind.label} unavailable", WeatherDataStatusPolicy.unavailable(kind))
        }
    }

    @Test fun `stale wind and satellites produce one replacement identity`() {
        val now = 10_000L
        val freshGrid = WindGrid("viewport", emptyList(), emptyList(), now - 900L)
        val staleGrid = WindGrid("viewport", emptyList(), emptyList(), now - 901L)
        assertNull(WeatherDataReplacementPolicy.staleWindIdentity(freshGrid, "viewport", now))
        val stale = WeatherDataReplacementPolicy.staleWindIdentity(staleGrid, "viewport", now)
        assertNotNull(stale)
        assertNotNull(WeatherDataReplacementPolicy.staleWindIdentity(freshGrid, "other", now))
        assertTrue(WeatherDataReplacementPolicy.shouldRequest(stale, null))
        assertFalse(WeatherDataReplacementPolicy.shouldRequest(stale, stale))

        val freshSatellite = EumetLayerMetadata(
            RadarMapLayer.FOG, now - 3_600L, -20.0, 30.0, 20.0, 70.0,
            EumetProduct.CLOUD_TYPE,
        )
        val staleSatellite = freshSatellite.copy(
            validEpochSeconds = now - 3_601L,
            availableFromEpochSeconds = now - 3_601L,
            latestEpochSeconds = now - 3_601L,
        )
        assertNull(WeatherDataReplacementPolicy.staleSatelliteIdentity(freshSatellite, now))
        assertNotNull(WeatherDataReplacementPolicy.staleSatelliteIdentity(staleSatellite, now))
    }

    @Test fun `wind request identity rejects obsolete viewport and refresh generations`() {
        val first = WindGridLoadPolicy.identity("viewport-a", "window", 4)
        val refreshed = WindGridLoadPolicy.identity("viewport-a", "window", 5)
        val moved = WindGridLoadPolicy.identity("viewport-b", "window", 5)
        assertNull(WindGridLoadPolicy.identity(null, "window", 5))
        assertEquals(4, requireNotNull(first).refreshGeneration)
        assertEquals(5, requireNotNull(refreshed).refreshGeneration)
        assertEquals("viewport-b", requireNotNull(moved).viewportKey)

        val now = 10_000L
        val current = WindGrid("viewport-a", emptyList(), emptyList(), now - 900L)
        assertTrue(WindGridLoadPolicy.canRetain(current, "viewport-a", now))
        assertFalse(WindGridLoadPolicy.canRetain(current, "viewport-b", now))
        assertFalse(WindGridLoadPolicy.canRetain(current, "viewport-a", now + 1L))
    }

    @Test fun `manual refresh is synchronously loading and cannot expose old unavailable before deadline`() {
        val diagnostic = WindGridFailureDiagnostic("parse_validation")
        val firstRequest = requireNotNull(WindGridLoadPolicy.identity("viewport", "window", 1))
        var state: WindGridAcquisitionState = WindGridAcquisitionState.Unavailable(
            generation = 1, request = firstRequest, recordedFailure = diagnostic,
        )
        val pointWindSpeedKmh = 18.0 // Independent point weather must not affect grid status.
        state = WindGridAcquisitionReducer.reduce(state, WindGridAcquisitionEvent.Refresh(
            "viewport", "window", nowElapsedMillis = 1_000L, nowEpochSeconds = 10_000L,
        ))
        val loading = state as WindGridAcquisitionState.Loading
        assertEquals(2, loading.generation)
        assertEquals(18.0, pointWindSpeedKmh, 0.0)
        assertEquals(AncillaryStatus.Loading, WindGridAcquisitionReducer.status(state))

        val newGeneration = state
        state = WindGridAcquisitionReducer.reduce(state, WindGridAcquisitionEvent.Failed(
            firstRequest.refreshGeneration, firstRequest, diagnostic,
        ))
        state = WindGridAcquisitionReducer.reduce(state,
            WindGridAcquisitionEvent.DeadlineReached(
                firstRequest.refreshGeneration, firstRequest, 99_000L, 10_099L,
            ))
        state = WindGridAcquisitionReducer.reduce(state,
            WindGridAcquisitionEvent.RenderObserved(
                WindGridRenderToken(firstRequest.refreshGeneration, "viewport", 9_999L),
                0,
                99_000L,
            ))
        assertEquals(newGeneration, state)
        assertEquals(AncillaryStatus.Loading, WindGridAcquisitionReducer.status(state))

        state = WindGridAcquisitionReducer.reduce(state, WindGridAcquisitionEvent.Failed(
            loading.generation, loading.request, diagnostic,
        ))
        listOf(1_000L, 11_000L, 55_999L).forEach { now ->
            state = WindGridAcquisitionReducer.reduce(state,
                WindGridAcquisitionEvent.DeadlineReached(
                    loading.generation, loading.request, now, 10_000L,
                ))
            assertEquals(AncillaryStatus.Loading, WindGridAcquisitionReducer.status(state))
        }
        state = WindGridAcquisitionReducer.reduce(state,
            WindGridAcquisitionEvent.DeadlineReached(
                loading.generation, loading.request, 56_000L, 10_055L,
            ))
        assertTrue(state is WindGridAcquisitionState.Unavailable)
        assertEquals("Wind unavailable",
            (WindGridAcquisitionReducer.status(state) as AncillaryStatus.Unavailable).message)
    }

    @Test fun `toggle on and downloaded data stay loading until a matching arrow is drawn`() {
        val nowEpoch = 15_000L
        var state: WindGridAcquisitionState = WindGridAcquisitionState.Disabled()
        state = WindGridAcquisitionReducer.reduce(state, WindGridAcquisitionEvent.Environment(
            enabled = true,
            viewportKey = null,
            timelineKey = "window",
            nowElapsedMillis = 0L,
            nowEpochSeconds = nowEpoch,
        ))
        assertTrue(state is WindGridAcquisitionState.AwaitingViewport)
        assertEquals(AncillaryStatus.Loading, WindGridAcquisitionReducer.presentationStatus(
            state, enabled = true, viewportKey = null, nowEpochSeconds = nowEpoch,
        ))
        state = WindGridAcquisitionReducer.reduce(state, WindGridAcquisitionEvent.Environment(
            true, "viewport", "window", 1L, nowEpoch,
        ))
        val loading = state as WindGridAcquisitionState.Loading
        val loaded = grid("viewport", nowEpoch)
        state = WindGridAcquisitionReducer.reduce(state, WindGridAcquisitionEvent.Succeeded(
            loading.generation, loading.request, loaded,
        ))
        assertTrue(state is WindGridAcquisitionState.DataReadyPendingRender)
        assertEquals(AncillaryStatus.Loading, WindGridAcquisitionReducer.presentationStatus(
            state, true, "viewport", nowEpoch,
        ))
        val token = requireNotNull(WindGridAcquisitionReducer.renderRequest(
            state, "viewport", nowEpoch,
        )).token
        state = WindGridAcquisitionReducer.reduce(state,
            WindGridAcquisitionEvent.RenderObserved(token, 0, 2L))
        assertTrue(state is WindGridAcquisitionState.DataReadyPendingRender)
        assertEquals(AncillaryStatus.Loading, WindGridAcquisitionReducer.presentationStatus(
            state, true, "viewport", nowEpoch,
        ))
        state = WindGridAcquisitionReducer.reduce(state,
            WindGridAcquisitionEvent.RenderObserved(token, 1, 3L))
        assertTrue(state is WindGridAcquisitionState.Ready)
        assertTrue(WindGridAcquisitionReducer.presentationStatus(
            state, true, "viewport", nowEpoch,
        ) is AncillaryStatus.Wind)
        assertEquals(AncillaryStatus.Loading, WindGridAcquisitionReducer.presentationStatus(
            state, true, "different-viewport", nowEpoch,
        ))
    }

    @Test fun `stale render acknowledgement is ignored and renderer reset requires a new draw`() {
        val nowEpoch = 16_000L
        val request = requireNotNull(WindGridLoadPolicy.identity("viewport", "window", 3))
        val value = grid("viewport", nowEpoch)
        var state: WindGridAcquisitionState = WindGridAcquisitionState.DataReadyPendingRender(
            generation = 3,
            request = request,
            startedAtMillis = 0L,
            deadlineAtMillis = 55_000L,
            grid = value,
            retainedGrid = null,
            retainedRenderConfirmed = false,
        )
        val current = requireNotNull(WindGridAcquisitionReducer.renderRequest(
            state, "viewport", nowEpoch,
        )).token
        val old = current.copy(generation = 2)
        state = WindGridAcquisitionReducer.reduce(state,
            WindGridAcquisitionEvent.RenderObserved(old, 9, 1L))
        assertTrue(state is WindGridAcquisitionState.DataReadyPendingRender)
        state = WindGridAcquisitionReducer.reduce(state,
            WindGridAcquisitionEvent.RenderObserved(current, 9, 2L))
        assertTrue(state is WindGridAcquisitionState.Ready)
        state = WindGridAcquisitionReducer.reduce(state,
            WindGridAcquisitionEvent.RendererReset(3L))
        assertTrue(state is WindGridAcquisitionState.DataReadyPendingRender)
        assertEquals(AncillaryStatus.Loading, WindGridAcquisitionReducer.presentationStatus(
            state, true, "viewport", nowEpoch,
        ))
        state = WindGridAcquisitionReducer.reduce(state,
            WindGridAcquisitionEvent.RenderObserved(current, 9, 4L))
        assertTrue(state is WindGridAcquisitionState.Ready)
    }

    @Test fun `unconfirmed downloaded grid becomes unavailable at deadline`() {
        val nowEpoch = 17_000L
        val request = requireNotNull(WindGridLoadPolicy.identity("viewport", "window", 1))
        var state: WindGridAcquisitionState = WindGridAcquisitionState.DataReadyPendingRender(
            1, request, 0L, 55_000L, grid("viewport", nowEpoch), null, false,
        )
        state = WindGridAcquisitionReducer.reduce(state,
            WindGridAcquisitionEvent.DeadlineReached(
                1, request, 54_999L, nowEpoch + 54L,
            ))
        assertTrue(state is WindGridAcquisitionState.DataReadyPendingRender)
        state = WindGridAcquisitionReducer.reduce(state,
            WindGridAcquisitionEvent.DeadlineReached(
                1, request, 55_000L, nowEpoch + 55L,
            ))
        assertTrue(state is WindGridAcquisitionState.Unavailable)
    }

    @Test fun `ready manual refresh retains arrows and failed replacement never downgrades usable grid`() {
        val nowEpoch = 20_000L
        val retained = grid("viewport", fetchedAt = nowEpoch - 100L)
        val firstRequest = requireNotNull(WindGridLoadPolicy.identity("viewport", "window", 1))
        var state: WindGridAcquisitionState = WindGridAcquisitionState.Ready(
            generation = 1, request = firstRequest, grid = retained,
        )
        state = WindGridAcquisitionReducer.reduce(state, WindGridAcquisitionEvent.Refresh(
            "viewport", "window", nowElapsedMillis = 500L, nowEpochSeconds = nowEpoch,
        ))
        val loading = state as WindGridAcquisitionState.Loading
        assertEquals(2, loading.generation)
        assertEquals(retained, loading.retainedGrid)
        assertEquals(retained,
            WindGridAcquisitionReducer.renderGrid(state, "viewport", nowEpoch))
        assertEquals(AncillaryStatus.Loading, WindGridAcquisitionReducer.status(state))

        val diagnostic = WindGridFailureDiagnostic("network_io", retriesExhausted = true)
        state = WindGridAcquisitionReducer.reduce(state, WindGridAcquisitionEvent.Failed(
            loading.generation, loading.request, diagnostic,
        ))
        state = WindGridAcquisitionReducer.reduce(state,
            WindGridAcquisitionEvent.DeadlineReached(
                loading.generation, loading.request, 55_499L, nowEpoch + 54L,
            ))
        assertTrue(state is WindGridAcquisitionState.Loading)
        state = WindGridAcquisitionReducer.reduce(state,
            WindGridAcquisitionEvent.DeadlineReached(
                loading.generation, loading.request, 55_500L, nowEpoch + 55L,
            ))
        assertTrue(state is WindGridAcquisitionState.Ready)
        assertEquals(retained, (state as WindGridAcquisitionState.Ready).grid)
        assertTrue(WindGridAcquisitionReducer.status(state) is AncillaryStatus.Wind)
    }

    @Test fun `expired retained grid becomes unavailable only at replacement deadline`() {
        val nowEpoch = 20_000L
        val retained = grid("viewport", fetchedAt = nowEpoch - 890L)
        val request = requireNotNull(WindGridLoadPolicy.identity("viewport", "window", 1))
        var state: WindGridAcquisitionState = WindGridAcquisitionState.Ready(1, request, retained)
        state = WindGridAcquisitionReducer.reduce(state, WindGridAcquisitionEvent.Refresh(
            "viewport", "window", 0L, nowEpoch,
        ))
        val loading = state as WindGridAcquisitionState.Loading
        state = WindGridAcquisitionReducer.reduce(state, WindGridAcquisitionEvent.Failed(
            loading.generation, loading.request, WindGridFailureDiagnostic("http", 400),
        ))
        state = WindGridAcquisitionReducer.reduce(state,
            WindGridAcquisitionEvent.DeadlineReached(
                loading.generation, loading.request, 54_999L, nowEpoch + 54L,
            ))
        assertTrue(state is WindGridAcquisitionState.Loading)
        state = WindGridAcquisitionReducer.reduce(state,
            WindGridAcquisitionEvent.DeadlineReached(
                loading.generation, loading.request, 55_000L, nowEpoch + 55L,
            ))
        assertTrue(state is WindGridAcquisitionState.Unavailable)
    }

    @Test fun `success viewport replacement stale events disable and repeated refresh are generation safe`() {
        val nowEpoch = 30_000L
        var state: WindGridAcquisitionState = WindGridAcquisitionState.AwaitingViewport(1, false)
        state = WindGridAcquisitionReducer.reduce(state, WindGridAcquisitionEvent.Environment(
            true, "viewport-a", "window", 0L, nowEpoch,
        ))
        val first = state as WindGridAcquisitionState.Loading
        state = WindGridAcquisitionReducer.reduce(state, WindGridAcquisitionEvent.Refresh(
            "viewport-b", "window", 40_000L, nowEpoch + 40L,
        ))
        val second = state as WindGridAcquisitionState.Loading
        assertEquals(first.generation + 1, second.generation)
        assertEquals("viewport-b", second.request.viewportKey)
        val unchanged = WindGridAcquisitionReducer.reduce(state,
            WindGridAcquisitionEvent.Succeeded(
                first.generation, first.request, grid("viewport-a", nowEpoch),
            ))
        assertEquals(state, unchanged)
        assertEquals(state, WindGridAcquisitionReducer.reduce(state,
            WindGridAcquisitionEvent.DeadlineReached(
                first.generation, first.request, 99_000L, nowEpoch + 99L,
            )))
        assertEquals(state, WindGridAcquisitionReducer.reduce(state,
            WindGridAcquisitionEvent.Succeeded(
                second.generation, second.request, grid("viewport-a", nowEpoch + 41L),
            )))

        state = WindGridAcquisitionReducer.reduce(state,
            WindGridAcquisitionEvent.Succeeded(
                second.generation, second.request, grid("viewport-b", nowEpoch + 41L),
            ))
        assertTrue(state is WindGridAcquisitionState.DataReadyPendingRender)
        val renderToken = requireNotNull(WindGridAcquisitionReducer.renderRequest(
            state, "viewport-b", nowEpoch + 41L,
        )).token
        state = WindGridAcquisitionReducer.reduce(state,
            WindGridAcquisitionEvent.RenderObserved(renderToken, 9, 41_500L))
        assertTrue(state is WindGridAcquisitionState.Ready)
        state = WindGridAcquisitionReducer.reduce(state, WindGridAcquisitionEvent.Refresh(
            "viewport-b", "window", 42_000L, nowEpoch + 42L,
        ))
        val thirdGeneration = state.generation
        state = WindGridAcquisitionReducer.reduce(state, WindGridAcquisitionEvent.Refresh(
            "viewport-b", "window", 43_000L, nowEpoch + 43L,
        ))
        assertEquals(thirdGeneration + 1, state.generation)
        state = WindGridAcquisitionReducer.reduce(state, WindGridAcquisitionEvent.Environment(
            false, "viewport-b", "window", 44_000L, nowEpoch + 44L,
        ))
        assertTrue(state is WindGridAcquisitionState.Disabled)
        assertEquals(AncillaryStatus.Off, WindGridAcquisitionReducer.status(state))
    }

    @Test fun `wind screen uses reducer as the only presentation authority`() {
        val radar = listOf(
            File("src/main/java/com/rainalarm/app/ui/RadarScreen.kt"),
            File("app/src/main/java/com/rainalarm/app/ui/RadarScreen.kt"),
        ).first(File::isFile).readText()
        assertTrue(radar.contains("var windAcquisition by remember"))
        assertTrue(radar.contains("WindGridAcquisitionEvent.Environment"))
        assertTrue(radar.contains("WindGridAcquisitionEvent.Succeeded"))
        assertTrue(radar.contains("WindGridAcquisitionEvent.Failed"))
        assertTrue(radar.contains("WindGridAcquisitionEvent.DeadlineReached"))
        assertTrue(radar.contains("WindGridAcquisitionEvent.RenderObserved"))
        assertTrue(radar.contains("WindGridAcquisitionEvent.RendererReset"))
        assertTrue(radar.contains("WindGridAcquisitionReducer.presentationStatus("))
        assertTrue(radar.contains("WindGridAcquisitionReducer.renderRequest("))
        assertFalse(radar.contains("var windStatus"))
        assertFalse(radar.contains("windReplacementPhase"))

        val readout = radar.substringAfter("private fun RadarLayerStatus(")
            .substringBefore("private fun RadarLayerStatuses(")
        assertTrue(readout.contains("val wind = weather?.takeIf"))
        assertFalse(readout.contains("windAcquisition"))
    }

    @Test fun `replacement presents loading then canonical unavailable without retry loop`() {
        val source = AncillaryStatus.Unavailable("provider model delayed; tap refresh")
        assertEquals(AncillaryStatus.Loading, WeatherReplacementPresentationPolicy.status(
            WeatherDataKind.CLOUDS, source, WeatherReplacementPhase.LOADING, "old", "old",
        ))
        val failed = WeatherReplacementPresentationPolicy.status(
            WeatherDataKind.CLOUDS, source, WeatherReplacementPhase.UNAVAILABLE, "old", "old",
        ) as AncillaryStatus.Unavailable
        assertEquals("Clouds unavailable", failed.message)
        assertEquals(AncillaryStatus.Loading, WeatherReplacementPresentationPolicy.status(
            WeatherDataKind.CLOUDS, source, WeatherReplacementPhase.IDLE, "new", "old",
        ))
        val attempted = WeatherReplacementPresentationPolicy.status(
            WeatherDataKind.CLOUDS, source, WeatherReplacementPhase.IDLE, "old", "old",
        ) as AncillaryStatus.Unavailable
        assertEquals("Clouds unavailable", attempted.message)
    }

    @Test fun `manual refresh retries exactly enabled layers and exposes canonical loading`() {
        val enabled = setOf(RadarMapLayer.WIND, RadarMapLayer.FOG, RadarMapLayer.LIGHTNING)
        assertEquals(enabled, ManualWeatherRefreshPolicy.enabledLayers(enabled))
        assertEquals(emptySet<RadarMapLayer>(), ManualWeatherRefreshPolicy.enabledLayers(emptySet()))
        assertEquals("Radar loading", RadarRefreshOverlayPolicy.initialLoading(0, 0).label)
        enabled.forEach { layer ->
            val kind = when (layer) {
                RadarMapLayer.WIND -> WeatherDataKind.WIND
                RadarMapLayer.FOG -> WeatherDataKind.CLOUDS
                RadarMapLayer.LIGHTNING -> WeatherDataKind.LIGHTNING
                else -> error("unexpected")
            }
            if (layer == RadarMapLayer.WIND) {
                val request = requireNotNull(WindGridLoadPolicy.identity("viewport", "window", 1))
                val state = WindGridAcquisitionState.Loading(
                    1, request, 0L, WindGridAcquisitionDeadlinePolicy.windowMillis,
                    true, null,
                )
                assertEquals(AncillaryStatus.Loading, WindGridAcquisitionReducer.status(state))
            } else assertEquals(AncillaryStatus.Loading,
                WeatherReplacementPresentationPolicy.status(
                    kind,
                    AncillaryStatus.Unavailable("raw failure"),
                    WeatherReplacementPhase.LOADING,
                    null,
                    null,
                ))
        }
    }

    @Test fun `manual current-location retry changes unavailable to loading immediately`() {
        assertEquals("Location unavailable", CurrentLocationPresentationPolicy.operationalStatus(
            currentSelected = true,
            hasResolvedPlace = false,
            locating = false,
            capabilityMessage = "settings disabled",
            requestMessage = null,
        )?.label)
        assertEquals("Location loading", CurrentLocationPresentationPolicy.operationalStatus(
            currentSelected = true,
            hasResolvedPlace = false,
            locating = true,
            capabilityMessage = "settings disabled",
            requestMessage = null,
        )?.label)
        val radar = listOf(
            File("src/main/java/com/rainalarm/app/ui/RadarScreen.kt"),
            File("app/src/main/java/com/rainalarm/app/ui/RadarScreen.kt"),
        ).first(File::isFile).readText()
        assertTrue(radar.contains("locationRequestPending = true"))
        assertTrue(radar.contains("locating = locationRequestPending ||"))
    }

    @Test fun `weather queue never exposes diagnostic status jargon`() {
        val entries = RadarPreparationStackPolicy.entries(
            radar = RadarRefreshOverlayPolicy.status(false, false, 0, 0, "provider failed"),
            enabledLayers = setOf(RadarMapLayer.WIND, RadarMapLayer.FOG, RadarMapLayer.LIGHTNING),
            statuses = mapOf(
                RadarMapLayer.WIND to AncillaryStatus.Unavailable("Wind model stale · refresh"),
                RadarMapLayer.FOG to AncillaryStatus.Unavailable("Satellite image delayed"),
                RadarMapLayer.LIGHTNING to AncillaryStatus.Unavailable("provider failed"),
            ),
            preparation = mapOf(
                RadarMapLayer.FOG to SatellitePreparationStatus.Failed("preparing failed"),
            ),
        )
        assertEquals(listOf(
            "Radar unavailable", "Wind unavailable", "Clouds unavailable", "Lightning unavailable",
        ), entries.map { it.label })
        val forbidden = listOf("stale", "delayed", "model", "provider", "preparing", "rendering",
            "failed", "tap refresh")
        entries.flatMap { listOf(it.label, it.accessibilityLabel) }.forEach { text ->
            forbidden.forEach { word -> assertFalse("$word leaked through $text", text.lowercase().contains(word)) }
        }
    }

    @Test fun `Now and Radar presentation do not pass raw backend reasons to users`() {
        fun source(name: String): String = listOf(
            File("src/main/java/com/rainalarm/app/ui/$name"),
            File("app/src/main/java/com/rainalarm/app/ui/$name"),
        ).first(File::isFile).readText()
        val now = source("NowScreen.kt")
        val radar = source("RadarScreen.kt")
        assertFalse(now.contains("series?.unavailableReason"))
        assertFalse(now.contains("NowPlaceholder(\"Forecast unavailable\", state.message"))
        assertFalse(now.contains("\"Refresh failed"))
        assertFalse(now.contains("tap to retry"))
        assertFalse(radar.contains("Wind model stale · refresh"))
        assertFalse(radar.contains("Satellite image delayed · refresh"))
        assertFalse(radar.contains("Radar data unavailable · tap refresh"))
        assertTrue(radar.contains("windAcquisition is WindGridAcquisitionState.Ready"))
        assertTrue(radar.contains("LaunchedEffect(lightningEnabled, staleLightningIdentity)"))
        assertTrue(radar.contains("LaunchedEffect(cloudsEnabled, staleCloudsIdentity)"))
        assertTrue(radar.contains("lightningRefresh++"))
        assertTrue(radar.contains("cloudsRefresh++"))
        val manual = radar.substringAfter("val startManualWindRefresh: () -> Unit = {")
            .substringBefore("BoxWithConstraints(Modifier.fillMaxSize())")
        assertTrue(manual.contains("error = null"))
        assertTrue(manual.contains("reload++"))
        assertTrue(manual.contains("ManualWeatherRefreshPolicy.enabledLayers(enabledMapLayers)"))
        assertTrue(manual.contains("lightningReplacementPhase = WeatherReplacementPhase.LOADING"))
        assertTrue(manual.contains("cloudsReplacementPhase = WeatherReplacementPhase.LOADING"))
        assertTrue(manual.contains("WindGridAcquisitionEvent.Refresh"))
        assertTrue(manual.contains("val after = WindGridAcquisitionReducer.reduce"))
        assertTrue(manual.contains("windAcquisition = after"))
        val unresolvedCurrent = manual.substringAfter("if (currentSelected && place == null) {")
            .substringBefore("} else {")
        assertTrue(unresolvedCurrent.contains("if (windEnabled) {"))
        assertTrue(unresolvedCurrent.contains("pendingWindRefresh = null"))
        assertTrue(unresolvedCurrent.contains("startManualWindRefresh()"))
        assertTrue(unresolvedCurrent.indexOf("startManualWindRefresh()") <
            unresolvedCurrent.indexOf("requestCurrentLocation(false)"))
    }

    private fun grid(viewportKey: String, fetchedAt: Long): WindGrid =
        WindGrid(viewportKey, emptyList(), emptyList(), fetchedAt)
}
