package com.rainalarm.app.data

import android.content.Context
import com.rainalarm.app.domain.PrecipitationSlot
import com.rainalarm.app.domain.OpenMinuteSeriesBuilder
import com.rainalarm.app.domain.ProviderMinuteSeriesBuilder
import com.rainalarm.app.domain.RainMinuteAvailability
import com.rainalarm.app.domain.RainAnalyzer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import java.time.Instant

/** Builds the selected provider's compact radar minute series without retaining renderer resources. */
class RadarAwareForecastRepository(
    context: Context,
    private val fallback: OpenMeteoForecastRepository = OpenMeteoForecastRepository(context),
    private val places: PlacePreferences = PlacePreferences(context),
    private val coordinator: RadarProviderCoordinator = RadarProviderCoordinator(RadarSettingsRepository(context)),
    private val areaChart: RegionalRainChartService = RegionalRainChartService(),
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
                val preferredTier = if (session.detail != null) {
                    com.rainalarm.app.domain.RadarResolutionTier.DETAIL
                } else com.rainalarm.app.domain.RadarResolutionTier.REGIONAL
                OpenMinuteSeriesBuilder.fromDenseField(
                    requireNotNull(session.latestDetailIntensity) { "Open radar has no point sampling grid" },
                    session.velocity(preferredTier)?.futureField,
                    session.frames.last().frame.time,
                    "RainViewer radar estimate",
                    evaluatedAt.epochSecond,
                )
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
        } catch (_: Throwable) {
            fallback.forecastFor(place, now)
        } finally {
            session?.release()
        }
    }
}
