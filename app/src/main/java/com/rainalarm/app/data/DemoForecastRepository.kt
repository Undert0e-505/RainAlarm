package com.rainalarm.app.data

import com.rainalarm.app.domain.PrecipitationSlot
import com.rainalarm.app.domain.RainAnalyzer
import com.rainalarm.app.domain.RainSummary
import java.time.Instant
import java.time.temporal.ChronoUnit

data class ForecastSnapshot(
    val locationName: String,
    val fetchedAt: Instant,
    val slots: List<PrecipitationSlot>,
    val summary: RainSummary,
    val isDemo: Boolean,
    val sourceLabel: String = "Demo",
    val isCached: Boolean = false,
    val isRadarNowcast: Boolean = false,
    val nowcastSeries: com.rainalarm.app.domain.RainMinuteSeries? = null,
)

interface ForecastRepository {
    suspend fun forecast(now: Instant = Instant.now()): ForecastSnapshot
}

class DemoForecastRepository : ForecastRepository {
    override suspend fun forecast(now: Instant): ForecastSnapshot {
        val bucket = now.truncatedTo(ChronoUnit.HOURS)
            .plus((now.atZone(java.time.ZoneOffset.UTC).minute / 15L) * 15L, ChronoUnit.MINUTES)
        val amounts = listOf(0.0, 0.0, 0.16, 0.42, 0.8, 0.34, 0.08, 0.0, 0.0)
        val chances = listOf(18, 31, 58, 74, 86, 78, 49, 28, 16)
        val slots = amounts.mapIndexed { index, amount ->
            PrecipitationSlot(
                startsAt = bucket.plus(index * 15L, ChronoUnit.MINUTES),
                precipitationMm = amount,
                probabilityPercent = chances[index],
            )
        }
        return ForecastSnapshot(
            locationName = "London demo",
            fetchedAt = now,
            slots = slots,
            summary = RainAnalyzer.analyze(slots, now, now),
            isDemo = true,
        )
    }
}
