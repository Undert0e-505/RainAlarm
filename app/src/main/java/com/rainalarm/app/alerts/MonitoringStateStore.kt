package com.rainalarm.app.alerts

import android.content.Context
import androidx.core.content.edit
import com.rainalarm.app.widget.FrozenMonitorTarget
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class NotificationSourceClass { APP, WIDGET }

@Serializable
data class DeliveredEventClaim(
    val eventIdentity: String,
    val sourceClass: NotificationSourceClass,
    val subscriptionId: String,
    val deliveredEpochSeconds: Long,
)

@Serializable
data class TargetMonitoringState(
    val targetKey: String,
    val rainArmed: Boolean = false,
    val rainEpisodeActive: Boolean = false,
    val activeRainEventIdentity: Long = 0L,
    val lastRainSeriesIdentity: Long = 0L,
    val rainDeliveredClaim: DeliveredEventClaim? = null,
    val lightningArmed: Boolean = false,
    val lightningEpisodeActive: Boolean = false,
    val activeLightningEventIdentity: Long = 0L,
    val lightningDeliveredClaim: DeliveredEventClaim? = null,
    val lightningContiguousCheckpoint: Long? = null,
    val lightningAlertCheckpoint: Long? = null,
    val recentDetectedLightningFrames: List<Long> = emptyList(),
    val lightningDisplayObservation: LightningFrameObservation? = null,
    val lightningDisplayFrameEpochSeconds: Long? = null,
    val lightningRadiusKilometres: Double = LightningDetectionPolicy.defaultRadiusKilometres,
    val lightningConfigurationVersion: Int = LightningDetectionPolicy.configurationVersion,
    val lastUpdatedEpochSeconds: Long = 0L,
)

@Serializable
data class SubscriptionMonitoringState(
    val subscriptionId: String,
    val targetKey: String,
    val consumedRainEvent: String? = null,
    val consumedLightningEvent: String? = null,
    val rainArmed: Boolean = false,
    val activeRainEventIdentity: Long = 0L,
    val lastRainSeriesIdentity: Long = 0L,
)

data class MonitoringSubscription(
    val id: String,
    val sourceClass: NotificationSourceClass,
    val quietHours: QuietHours = QuietHours(),
    val rainEnabled: Boolean,
    val lightningEnabled: Boolean,
    val rainRequiresPriorClear: Boolean = false,
)

data class MonitoringDeliveryCandidate(
    val subscriptionId: String,
    val sourceClass: NotificationSourceClass,
    val quietHours: QuietHours,
    val rainEventIdentity: String? = null,
    val lightningEventIdentity: String? = null,
)

data class MonitoringEvaluation(
    val targetState: TargetMonitoringState,
    val candidates: List<MonitoringDeliveryCandidate>,
)

data class MonitoringDeliveryResolution(
    val deliverRainEvent: String? = null,
    val deliverLightningEvent: String? = null,
    val consumeRainEvent: String? = null,
    val consumeLightningEvent: String? = null,
) {
    val kind: CombinedAlertPolicy.Kind get() = CombinedAlertPolicy.kind(
        deliverRainEvent != null,
        deliverLightningEvent != null,
    )
}

/** Cross-source suppression is symmetric; widgets never suppress sibling widgets. */
object CrossSourceDeliveredClaimPolicy {
    fun suppresses(
        claim: DeliveredEventClaim?,
        eventIdentity: String?,
        candidateSource: NotificationSourceClass,
    ): Boolean {
        if (claim == null || eventIdentity == null || claim.eventIdentity != eventIdentity) return false
        return when (claim.sourceClass) {
            NotificationSourceClass.APP -> candidateSource == NotificationSourceClass.WIDGET
            NotificationSourceClass.WIDGET -> candidateSource == NotificationSourceClass.APP
        }
    }
}

/** Pure subscription-level rain gating shared by the persistent store and requirements tests. */
object SubscriptionRainEligibilityPolicy {
    fun isEligible(
        evaluation: RadarAlertEvaluation?,
        requiresPriorClear: Boolean,
        armedByCompleteClear: Boolean,
    ): Boolean = when (evaluation) {
        is RadarAlertEvaluation.Approaching,
        is RadarAlertEvaluation.WetNow,
        -> !requiresPriorClear || armedByCompleteClear
        else -> false
    }

    /** A failed post must not destroy eligibility while the current episode is still active. */
    fun nextArmed(
        evaluation: RadarAlertEvaluation?,
        previouslyArmed: Boolean,
    ): Boolean = when (evaluation) {
        RadarAlertEvaluation.Clear -> true
        else -> previouslyArmed
    }
}

/** Persistent, process-atomic rain/lightning episode and delivered-claim storage. */
class MonitoringStateStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun target(targetKey: String): TargetMonitoringState = synchronized(lock) {
        readTarget(targetKey) ?: TargetMonitoringState(targetKey)
    }

    fun resetRainEligibility() = synchronized(lock) {
        val keys = preferences.all.keys.filter { it.startsWith(SUBSCRIPTION_PREFIX) }
        preferences.edit(commit = true) {
            keys.forEach { key ->
                readSubscriptionKey(key)?.let { state ->
                    putString(key, json.encodeToString(state.copy(rainArmed = false)))
                }
            }
        }
    }

    fun resetLightningEligibility() = synchronized(lock) {
        val keys = preferences.all.keys.filter { it.startsWith(TARGET_PREFIX) }
        preferences.edit(commit = true) {
            keys.forEach { key ->
                readTargetKey(key)?.let { state ->
                    putString(key, json.encodeToString(state.copy(
                        // Re-read the latest advertised observation after opt-in, but preserve
                        // the active episode, successful claim and subscription consumption.
                        // Toggling a switch must not manufacture a new meteorological episode.
                        lightningAlertCheckpoint = null,
                    )))
                }
            }
        }
    }

    fun evaluate(
        target: FrozenMonitorTarget,
        rainEvaluation: RadarAlertEvaluation?,
        lightningEvaluation: LightningEvaluationResult?,
        subscriptions: List<MonitoringSubscription>,
        now: Instant,
    ): MonitoringEvaluation = synchronized(lock) {
        val monitorKey = target.monitoringKey
        var targetState = readTarget(monitorKey) ?: TargetMonitoringState(monitorKey)
        val completeClear = rainEvaluation == RadarAlertEvaluation.Clear
        val precipitation = rainEvaluation is RadarAlertEvaluation.Approaching ||
            rainEvaluation is RadarAlertEvaluation.WetNow
        if (completeClear) {
            targetState = targetState.copy(
                rainEpisodeActive = false,
                activeRainEventIdentity = 0L,
                rainDeliveredClaim = null,
            )
        } else if (precipitation) {
            val sourceIdentity = when (rainEvaluation) {
                is RadarAlertEvaluation.Approaching -> rainEvaluation.frameIdentity
                is RadarAlertEvaluation.WetNow -> rainEvaluation.frameIdentity
            }
            val identity = targetState.activeRainEventIdentity.takeIf {
                targetState.rainEpisodeActive && it > 0L
            } ?: legacyRainEventIdentity(monitorKey)
                ?: now.epochSecond.coerceAtLeast(1L)
            targetState = targetState.copy(
                rainEpisodeActive = true,
                activeRainEventIdentity = identity,
                lastRainSeriesIdentity = sourceIdentity,
            )
        }

        if (lightningEvaluation != null) {
            val detectedFrames = (targetState.recentDetectedLightningFrames +
                lightningEvaluation.frames.filter {
                    it.observation == LightningFrameObservation.DETECTED
                }.map(LightningFrameResult::epochSeconds))
                .distinct().sortedDescending().take(MAX_RECENT_LIGHTNING_IDENTITIES).sorted()
            val transition = LightningEpisodePolicy.reduce(
                LightningEpisodeState(
                    targetState.lightningEpisodeActive,
                    targetState.activeLightningEventIdentity,
                ),
                lightningEvaluation.observations,
            )
            val cleared = targetState.lightningEpisodeActive && !transition.state.active
            targetState = targetState.copy(
                lightningArmed = !transition.state.active,
                lightningEpisodeActive = transition.state.active,
                activeLightningEventIdentity = transition.state.activeEventIdentity,
                lightningDeliveredClaim = if (cleared) null else targetState.lightningDeliveredClaim,
                lightningContiguousCheckpoint = lightningEvaluation.lastContiguousEpochSeconds,
                lightningAlertCheckpoint = lightningEvaluation.lastContiguousEpochSeconds,
                recentDetectedLightningFrames = detectedFrames,
                lightningDisplayObservation = when (lightningEvaluation.outcome) {
                    LightningEvaluationOutcome.DETECTED -> LightningFrameObservation.DETECTED
                    LightningEvaluationOutcome.NO_DETECTION -> LightningFrameObservation.NO_DETECTION
                    LightningEvaluationOutcome.NO_NEW_FRAMES,
                    LightningEvaluationOutcome.UNAVAILABLE,
                    -> targetState.lightningDisplayObservation
                },
                lightningDisplayFrameEpochSeconds = lightningEvaluation.frames.lastOrNull {
                    it.observation != LightningFrameObservation.UNAVAILABLE
                }?.epochSeconds ?: targetState.lightningDisplayFrameEpochSeconds,
                lightningRadiusKilometres = lightningEvaluation.radiusKilometres,
                lightningConfigurationVersion = LightningDetectionPolicy.configurationVersion,
            )
        }
        targetState = targetState.copy(lastUpdatedEpochSeconds = now.epochSecond)

        val rainEvent = targetState.activeRainEventIdentity.takeIf {
            targetState.rainEpisodeActive && it > 0L
        }?.let { "rain:$it" }
        val lightningEvent = targetState.activeLightningEventIdentity.takeIf {
            targetState.lightningEpisodeActive && it > 0L
        }?.let { "lightning:$it" }
        val candidates = mutableListOf<MonitoringDeliveryCandidate>()
        val states = linkedMapOf<String, SubscriptionMonitoringState>()
        subscriptions.distinctBy(MonitoringSubscription::id).forEach { subscription ->
            val previous = readSubscription(subscription.id)
                ?.takeIf { it.targetKey == monitorKey }
                ?: SubscriptionMonitoringState(subscription.id, monitorKey)
            val wasRainArmed = previous.rainArmed
            val rainEligible = subscription.rainEnabled && rainEvent != null &&
                SubscriptionRainEligibilityPolicy.isEligible(
                    rainEvaluation,
                    subscription.rainRequiresPriorClear,
                    wasRainArmed,
                )
            val lightningEligible = subscription.lightningEnabled && lightningEvent != null
            val next = previous.copy(
                // Disabled/display-only acquisition is evidence, not delivery state. It must not
                // consume an episode or create a delivered claim.
                consumedRainEvent = previous.consumedRainEvent,
                consumedLightningEvent = previous.consumedLightningEvent,
                // Eligibility is consumed only by a successful post, DND, or cross-source
                // suppression. Keeping this latch armed lets a permission-blocked or failed
                // delivery retry without requiring an impossible clear during the same rain.
                rainArmed = SubscriptionRainEligibilityPolicy.nextArmed(
                    rainEvaluation,
                    previous.rainArmed,
                ),
                activeRainEventIdentity = targetState.activeRainEventIdentity,
                lastRainSeriesIdentity = targetState.lastRainSeriesIdentity,
            )
            states[subscription.id] = next
            val candidateRain = rainEvent.takeIf {
                rainEligible && next.consumedRainEvent != rainEvent
            }
            val candidateLightning = lightningEvent.takeIf {
                lightningEligible && next.consumedLightningEvent != lightningEvent
            }
            if (candidateRain != null || candidateLightning != null) candidates +=
                MonitoringDeliveryCandidate(
                    subscription.id,
                    subscription.sourceClass,
                    subscription.quietHours,
                    candidateRain,
                    candidateLightning,
                )
        }
        preferences.edit(commit = true) {
            putString(targetStorageKey(monitorKey), json.encodeToString(targetState))
            states.forEach { (id, state) ->
                putString(subscriptionKey(id), json.encodeToString(state))
            }
        }
        MonitoringEvaluation(targetState, candidates)
    }

    fun resolveDelivery(
        targetKey: String,
        candidate: MonitoringDeliveryCandidate,
        now: Instant,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): MonitoringDeliveryResolution = synchronized(lock) {
        val target = readTarget(targetKey) ?: TargetMonitoringState(targetKey)
        val rainSuppressed = CrossSourceDeliveredClaimPolicy.suppresses(
            target.rainDeliveredClaim,
            candidate.rainEventIdentity,
            candidate.sourceClass,
        )
        val lightningSuppressed = CrossSourceDeliveredClaimPolicy.suppresses(
            target.lightningDeliveredClaim,
            candidate.lightningEventIdentity,
            candidate.sourceClass,
        )
        val quiet = QuietHoursPolicy.isQuiet(candidate.quietHours, now, zoneId)
        MonitoringDeliveryResolution(
            deliverRainEvent = candidate.rainEventIdentity.takeUnless { quiet || rainSuppressed },
            deliverLightningEvent = candidate.lightningEventIdentity.takeUnless {
                quiet || lightningSuppressed
            },
            consumeRainEvent = candidate.rainEventIdentity.takeIf { quiet || rainSuppressed },
            consumeLightningEvent = candidate.lightningEventIdentity.takeIf {
                quiet || lightningSuppressed
            },
        )
    }

    fun consumeWithoutDelivery(
        targetKey: String,
        subscriptionId: String,
        rainEvent: String?,
        lightningEvent: String?,
    ) = synchronized(lock) {
        val saved = readSubscription(subscriptionId)
            ?.takeIf { it.targetKey == targetKey } ?: return@synchronized
        writeSubscription(saved.copy(
            consumedRainEvent = rainEvent ?: saved.consumedRainEvent,
            consumedLightningEvent = lightningEvent ?: saved.consumedLightningEvent,
        ))
    }

    /** Must be called only after NotificationManager accepted the notification. */
    fun recordSuccessfulDelivery(
        targetKey: String,
        candidate: MonitoringDeliveryCandidate,
        rainEvent: String?,
        lightningEvent: String?,
        deliveredAt: Instant,
    ) = synchronized(lock) {
        val target = readTarget(targetKey) ?: TargetMonitoringState(targetKey)
        val subscription = readSubscription(candidate.subscriptionId)
            ?.takeIf { it.targetKey == targetKey }
            ?: SubscriptionMonitoringState(candidate.subscriptionId, targetKey)
        val rainClaim = rainEvent?.let {
            DeliveredEventClaim(it, candidate.sourceClass, candidate.subscriptionId, deliveredAt.epochSecond)
        }
        val lightningClaim = lightningEvent?.let {
            DeliveredEventClaim(it, candidate.sourceClass, candidate.subscriptionId, deliveredAt.epochSecond)
        }
        preferences.edit(commit = true) {
            putString(targetStorageKey(targetKey), json.encodeToString(target.copy(
                rainDeliveredClaim = rainClaim ?: target.rainDeliveredClaim,
                lightningDeliveredClaim = lightningClaim ?: target.lightningDeliveredClaim,
            )))
            putString(subscriptionKey(candidate.subscriptionId), json.encodeToString(subscription.copy(
                consumedRainEvent = rainEvent ?: subscription.consumedRainEvent,
                consumedLightningEvent = lightningEvent ?: subscription.consumedLightningEvent,
            )))
        }
    }

    fun removeSubscription(subscriptionId: String) = synchronized(lock) {
        preferences.edit(commit = true) { remove(subscriptionKey(subscriptionId)) }
    }

    fun retainSubscriptions(activeIds: Set<String>) = synchronized(lock) {
        preferences.edit(commit = true) {
            preferences.all.keys.filter { it.startsWith(SUBSCRIPTION_PREFIX) }.forEach { key ->
                if (key.removePrefix(SUBSCRIPTION_PREFIX) !in activeIds) remove(key)
            }
        }
    }

    private fun writeSubscription(state: SubscriptionMonitoringState) {
        preferences.edit(commit = true) {
            putString(subscriptionKey(state.subscriptionId), json.encodeToString(state))
        }
    }

    private fun readTarget(targetKey: String): TargetMonitoringState? =
        readTargetKey(targetStorageKey(targetKey))
    private fun readTargetKey(storageKey: String): TargetMonitoringState? =
        preferences.getString(storageKey, null)?.let { raw ->
            runCatching { json.decodeFromString<TargetMonitoringState>(raw) }.getOrNull()
        }
    private fun readSubscription(id: String): SubscriptionMonitoringState? =
        readSubscriptionKey(subscriptionKey(id))
    private fun readSubscriptionKey(key: String): SubscriptionMonitoringState? =
        preferences.getString(key, null)?.let { raw ->
            runCatching { json.decodeFromString<SubscriptionMonitoringState>(raw) }.getOrNull()
        }

    /** Preserve pre-claim consumption across the 0.9.0 state-schema migration. */
    private fun legacyRainEventIdentity(targetKey: String): Long? = preferences.all.keys
        .asSequence()
        .filter { it.startsWith(SUBSCRIPTION_PREFIX) }
        .mapNotNull(::readSubscriptionKey)
        .filter { it.targetKey == targetKey }
        .mapNotNull { state ->
            state.activeRainEventIdentity.takeIf { it > 0L }
                ?: state.consumedRainEvent?.removePrefix("rain:")?.toLongOrNull()
        }
        .firstOrNull()

    private companion object {
        const val PREFS = "weather_monitoring_state"
        const val TARGET_PREFIX = "target_"
        const val SUBSCRIPTION_PREFIX = "subscription_"
        const val MAX_RECENT_LIGHTNING_IDENTITIES = 24
        val lock = Any()
        fun storageSuffix(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).take(16)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        fun targetStorageKey(value: String) = TARGET_PREFIX + storageSuffix(value)
        fun subscriptionKey(value: String) = SUBSCRIPTION_PREFIX + value
    }
}
