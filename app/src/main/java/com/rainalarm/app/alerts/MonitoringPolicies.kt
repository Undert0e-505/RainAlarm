package com.rainalarm.app.alerts

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

data class RainEpisodeState(
    val armed: Boolean = false,
    val activeEventIdentity: Long = 0L,
    val lastSeriesIdentity: Long = 0L,
)

data class RainEpisodeTransition(
    val state: RainEpisodeState,
    val newEventIdentity: Long? = null,
)

/** Evidence-only reducer. Delivery/DND/claim consumption is deliberately handled separately. */
object RainEpisodePolicy {
    fun reduce(state: RainEpisodeState, evaluation: RadarAlertEvaluation): RainEpisodeTransition = when (evaluation) {
        RadarAlertEvaluation.Clear -> RainEpisodeTransition(
            state.copy(armed = true, activeEventIdentity = 0L),
        )
        is RadarAlertEvaluation.Approaching -> {
            val event = state.activeEventIdentity.takeIf { it > 0L } ?: evaluation.frameIdentity
            RainEpisodeTransition(
                state.copy(activeEventIdentity = event, lastSeriesIdentity = evaluation.frameIdentity),
                event.takeIf { state.armed },
            )
        }
        is RadarAlertEvaluation.WetNow -> {
            val event = state.activeEventIdentity.takeIf { it > 0L } ?: evaluation.frameIdentity
            RainEpisodeTransition(
                state.copy(activeEventIdentity = event, lastSeriesIdentity = evaluation.frameIdentity),
                event.takeIf { state.armed },
            )
        }
        RadarAlertEvaluation.Unknown -> RainEpisodeTransition(state)
    }
}

data class QuietHours(
    val enabled: Boolean = false,
    val startMinuteOfDay: Int = 22 * 60,
    val endMinuteOfDay: Int = 7 * 60,
) {
    val valid: Boolean
        get() = startMinuteOfDay in 0 until 24 * 60 && endMinuteOfDay in 0 until 24 * 60 &&
            startMinuteOfDay != endMinuteOfDay
}

/** Recurring local wall-clock quiet hours; start is inclusive and end is exclusive. */
object QuietHoursPolicy {
    fun isQuiet(
        quietHours: QuietHours,
        instant: Instant,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Boolean {
        if (!quietHours.enabled || !quietHours.valid) return false
        val local = instant.atZone(zoneId).toLocalTime()
        return contains(quietHours, local.hour * 60 + local.minute)
    }

    fun contains(quietHours: QuietHours, minuteOfDay: Int): Boolean {
        if (!quietHours.enabled || !quietHours.valid || minuteOfDay !in 0 until 24 * 60) return false
        return if (quietHours.startMinuteOfDay < quietHours.endMinuteOfDay) {
            minuteOfDay >= quietHours.startMinuteOfDay && minuteOfDay < quietHours.endMinuteOfDay
        } else {
            minuteOfDay >= quietHours.startMinuteOfDay || minuteOfDay < quietHours.endMinuteOfDay
        }
    }

    fun localTime(minutes: Int): LocalTime = LocalTime.of(minutes / 60, minutes % 60)
}

enum class AlertEvidenceKind { RAIN, LIGHTNING }

/** Notification evidence advances only when delivery is actually permitted. */
object AlertDecisionEligibilityPolicy {
    fun mayAdvance(enabled: Boolean, notificationPermissionGranted: Boolean): Boolean =
        enabled && notificationPermissionGranted
}

data class SubscriptionDeliveryState(
    val consumedRainEvent: String? = null,
    val consumedLightningEvent: String? = null,
)

data class DeliveryCandidate(
    val subscriptionId: String,
    val quietHours: QuietHours = QuietHours(),
    val state: SubscriptionDeliveryState = SubscriptionDeliveryState(),
    val rainEvent: String? = null,
    val lightningEvent: String? = null,
)

data class DeliveryDecision(
    val shouldNotify: Boolean,
    val updated: Map<String, SubscriptionDeliveryState>,
)

/**
 * Evidence is shared by target; delivery consumption is per subscription. Every participating
 * subscription consumes a new event whether quiet or not, preventing catch-up notifications.
 */
object SubscriptionDeliveryPolicy {
    fun decide(
        candidates: List<DeliveryCandidate>,
        now: Instant,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): DeliveryDecision {
        var outsideQuiet = false
        val updated = candidates.associate { candidate ->
            val hasUnconsumedRain = candidate.rainEvent != null &&
                candidate.state.consumedRainEvent != candidate.rainEvent
            val hasUnconsumedLightning = candidate.lightningEvent != null &&
                candidate.state.consumedLightningEvent != candidate.lightningEvent
            if ((hasUnconsumedRain || hasUnconsumedLightning) &&
                !QuietHoursPolicy.isQuiet(candidate.quietHours, now, zoneId)
            ) outsideQuiet = true
            candidate.subscriptionId to candidate.state.copy(
                consumedRainEvent = candidate.rainEvent ?: candidate.state.consumedRainEvent,
                consumedLightningEvent = candidate.lightningEvent ?:
                    candidate.state.consumedLightningEvent,
            )
        }
        return DeliveryDecision(outsideQuiet, updated)
    }

    /** Compatibility helper for callers where every subscription sees the same evidence. */
    fun decide(
        candidates: List<DeliveryCandidate>,
        rainEvent: String?,
        lightningEvent: String?,
        now: Instant,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): DeliveryDecision {
        return decide(
            candidates.map { it.copy(rainEvent = rainEvent, lightningEvent = lightningEvent) },
            now,
            zoneId,
        )
    }
}

object CombinedAlertPolicy {
    enum class Kind { NONE, RAIN, LIGHTNING, COMBINED }
    fun kind(rainTriggered: Boolean, lightningTriggered: Boolean): Kind = when {
        rainTriggered && lightningTriggered -> Kind.COMBINED
        rainTriggered -> Kind.RAIN
        lightningTriggered -> Kind.LIGHTNING
        else -> Kind.NONE
    }
}

/** Standalone Lightning stays compact; rain-bearing notifications retain their expanded detail. */
object MonitorNotificationPresentationPolicy {
    fun usesExpandedStyle(kind: CombinedAlertPolicy.Kind): Boolean =
        kind == CombinedAlertPolicy.Kind.RAIN || kind == CombinedAlertPolicy.Kind.COMBINED
}
