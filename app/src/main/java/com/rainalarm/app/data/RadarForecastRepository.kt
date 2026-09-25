package com.rainalarm.app.data

import android.content.Context
import android.util.Log
import com.rainalarm.app.domain.PrecipitationSlot
import com.rainalarm.app.domain.OpenMinuteSeriesBuilder
import com.rainalarm.app.domain.ProviderMinuteSeriesBuilder
import com.rainalarm.app.domain.RainMinuteAvailability
import com.rainalarm.app.domain.RainAnalyzer
import com.rainalarm.app.domain.RainMinuteSeries
import com.rainalarm.app.domain.RadarResolutionTier
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import java.time.Instant

/** One open-radar analysis path shared by the foreground Now screen and background alerts. */
fun buildOpenRadarMinuteSeries(session: RadarSession, nowEpochSeconds: Long): RainMinuteSeries {
    val samplingTier = if (session.detail != null) RadarResolutionTier.DETAIL
    else RadarResolutionTier.REGIONAL
    return OpenMinuteSeriesBuilder.fromBestAvailableMotion(
        grid = requireNotNull(session.latestDetailIntensity) { "Open radar has no point sampling grid" },
        field = session.velocity(samplingTier)?.futureField,
        aggregateMotion = session.motion,
        samplingTier = samplingTier,
        startEpochSeconds = session.frames.last().frame.time,
        sourceLabel = when (session.providerSelection.active) {
            RadarProviderKind.EUMETNET_OPERA -> "OPERA radar estimate"
            else -> "RainViewer radar estimate"
        },
        nowEpochSeconds = nowEpochSeconds,
        snowGrid = session.latestDetailSnow,
        coverageGrid = session.latestDetailCoverage,
    )
}

/** Builds the selected provider's compact radar minute series without retaining renderer resources. */
class RadarAwareForecastRepository(
    context: Context,
    private val fallback: OpenMeteoForecastRepository = OpenMeteoForecastRepository(context),
    private val places: PlacePreferences = PlacePreferences(context),
    private val radarSettings: RadarSettingsRepository = RadarSettingsRepository(context),
    private val coordinator: RadarProviderCoordinator = RadarProviderCoordinator(radarSettings),
    private val areaChart: RegionalRainChartService = RegionalRainChartService(),
    private val onProviderSelection: (SavedPlace, RadarProviderSelection) -> Unit = { _, _ -> },
) : ForecastRepository {
    override suspend fun forecast(now: Instant): ForecastSnapshot {
        val place = places.selected.first()
        require(!place.isCurrentLocation) { "A live device fix is required for current location" }
        return forecastFor(place, now)
    }

    suspend fun forecastFor(place: SavedPlace, now: Instant = Instant.now()): ForecastSnapshot {
        var session: RadarSession? = null
        return try {
            // Five observations give the open provider enough history for its confidence-gated
            // dense field. Regional provider forecasts are always retained through +60.
            session = coordinator.load(place, maxFrames = 5, mode = RadarLoadMode.ALERT_ANALYSIS)
            onProviderSelection(place, session.providerSelection)
            // The regional download can take long enough to cross a minute boundary.
            // Anchor the chart after loading, at the time the data is analysed.
            val evaluatedAt = Instant.now()
            val active = session.providerSelection.active
            val series = if (active == RadarProviderKind.METEOGROUP_REGIONAL) {
                val rasterFallback = ProviderMinuteSeriesBuilder.fromSamples(
                    requireNotNull(session.legacyArchive).pointSamples,
                    "MeteoGroup provider forecast",
                    evaluatedAt.epochSecond,
                )
                areaChart.preferChart(place, evaluatedAt.epochSecond, rasterFallback)
            } else {
                buildOpenRadarMinuteSeries(session, evaluatedAt.epochSecond)
            }
            val slots = if (series.availability != RainMinuteAvailability.UNAVAILABLE) {
                (0..series.points.lastIndex step 15).map { minute ->
                    val point = series.points[minute]
                    PrecipitationSlot(
                        startsAt = Instant.ofEpochSecond(series.startEpochSeconds + minute * 60L),
                        // Normalized radar intensity is mapped only for the legacy summary bridge.
                        precipitationMm = if (point.average >= series.wetThreshold) {
                            0.1 + point.average * 0.3
                        } else 0.0,
                        probabilityPercent = null,
                    )
                }
            } else emptyList()
            ForecastSnapshot(
                locationName = place.name,
                fetchedAt = evaluatedAt,
                slots = slots,
                // A short provider horizon cannot support a truthful "clear next hour" summary.
                summary = RainAnalyzer.analyze(slots.takeIf { it.isNotEmpty() &&
                    series.availability == RainMinuteAvailability.AVAILABLE }, evaluatedAt, evaluatedAt),
                isDemo = false,
                sourceLabel = series.sourceLabel,
                isCached = false,
                isRadarNowcast = true,
                nowcastSeries = series,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (timedOut: RadarLoadTimedOutException) {
            // A slow radar session is unknown, not a dry Open-Meteo result.
            throw timedOut
        } catch (failure: Throwable) {
            Log.w(
                "RainRadarForecast",
                "Radar analysis failed provider=${radarSettings.selectedProvider()} " +
                    "type=${failure.javaClass.simpleName} reason=${failure.message.orEmpty().take(120)}",
            )
            val selectedProvider = radarSettings.selectedProvider()
            if (selectedProvider != RadarProviderKind.METEOGROUP_REGIONAL) {
                val providerName = if (selectedProvider == RadarProviderKind.EUMETNET_OPERA) "OPERA" else "RainViewer"
                val unavailable = com.rainalarm.app.domain.RainMinuteSeries.unavailable(
                    now.epochSecond,
                    "$providerName radar estimate",
                    "Open radar could not provide a reliable local analysis",
                )
                ForecastSnapshot(
                    locationName = place.name,
                    fetchedAt = now,
                    slots = emptyList(),
                    summary = RainAnalyzer.analyze(null, now, now),
                    isDemo = false,
                    sourceLabel = unavailable.sourceLabel,
                    isCached = false,
                    isRadarNowcast = true,
                    nowcastSeries = unavailable,
                )
            } else fallback.forecastFor(place, now)
        } finally {
            session?.release()
        }
    }
}
