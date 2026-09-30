package com.rainalarm.app.alerts

import android.content.Context
import androidx.core.content.edit
import com.rainalarm.app.widget.FrozenMonitorTarget
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.security.MessageDigest

@Serializable
data class TargetMonitoringState(
    val targetKey: String,
    val rainArmed: Boolean = false,
    val activeRainEventIdentity: Long = 0L,
    val lastRainSeriesIdentity: Long = 0L,
    val lightningArmed: Boolean = false,
    val lightningEpisodeActive: Boolean = false,
    val activeLightningEventIdentity: Long = 0L,
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
    val quietHours: QuietHours = QuietHours(),
    val rainRequiresPriorClear: Boolean = false,
)

data class MonitoringDecision(
    val kind: CombinedAlertPolicy.Kind,
    val rainEventIdentity: String?,
    val lightningEventIdentity: String?,
    val targetState: TargetMonitoringState,
)

/**
 * Small decision-only persistence. Images and coordinates are deliberately absent; target keys
 * are rounded identities supplied by [FrozenMonitorTarget]. All read/modify/write transitions are
 * process-atomic so immediate and periodic WorkManager requests cannot double-deliver.
 */
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
                preferences.getString(key, null)?.let { raw ->
                    runCatching { json.decodeFromString<SubscriptionMonitoringState>(raw) }
                        .getOrNull()?.let { state ->
                            putString(key, json.encodeToString(state.copy(rainArmed = false)))
                        }
                }
            }
        }
    }

    fun resetLightningEligibility() = synchronized(lock) {
        val keys = preferences.all.keys.filter { it.startsWith(TARGET_PREFIX) }
        preferences.edit(commit = true) {
            keys.forEach { key ->
                readTargetKey(key)?.let { state ->
                    putString(
                        key,
                        json.encodeToString(
                            state.copy(
                                lightningArmed = false,
                                lightningEpisodeActive = false,
                                activeLightningEventIdentity = 0L,
                                lightningAlertCheckpoint = null,
                            ),
                        ),
                    )
                }
            }
        }
    }

    fun apply(
        target: FrozenMonitorTarget,
        rainEvaluation: RadarAlertEvaluation?,
        lightningEvaluation: LightningEvaluationResult?,
        rainAlertEnabled: Boolean,
        lightningAlertEnabled: Boolean,
        subscriptions: List<MonitoringSubscription>,
        now: Instant,
        zoneId: ZoneId = ZoneId.systemDefault(),
        appRainEventIdentity: Long? = null,
        notificationDeliveryAvailable: Boolean = true,
    ): MonitoringDecision = synchronized(lock) {
        val monitorKey = target.monitoringKey
        var targetState = readTarget(monitorKey) ?: TargetMonitoringState(monitorKey)
        var lightningEvent: String? = null

        if (lightningEvaluation != null) {
            val contiguous = lightningEvaluation.lastContiguousEpochSeconds
            val detected = (targetState.recentDetectedLightningFrames +
                lightningEvaluation.frames.filter {
                    it.observation == LightningFrameObservation.DETECTED
                }.map(LightningFrameResult::epochSeconds))
                .distinct()
                .sortedDescending()
                .take(MAX_RECENT_LIGHTNING_IDENTITIES)
                .sorted()
            targetState = targetState.copy(
                lightningContiguousCheckpoint = contiguous,
                recentDetectedLightningFrames = detected,
                lightningDisplayObservation = when (lightningEvaluation.outcome) {
                    LightningEvaluationOutcome.DETECTED -> LightningFrameObservation.DETECTED
                    LightningEvaluationOutcome.NO_DETECTION -> LightningFrameObservation.NO_DETECTION
                    LightningEvaluationOutcome.NO_NEW_FRAMES -> targetState.lightningDisplayObservation
                    LightningEvaluationOutcome.UNAVAILABLE -> targetState.lightningDisplayObservation
                },
                lightningDisplayFrameEpochSeconds = lightningEvaluation.frames.lastOrNull {
                    it.observation != LightningFrameObservation.UNAVAILABLE
                }?.epochSeconds ?: targetState.lightningDisplayFrameEpochSeconds,
                lightningRadiusKilometres = lightningEvaluation.radiusKilometres,
                lightningConfigurationVersion = LightningDetectionPolicy.configurationVersion,
            )
            if (AlertDecisionEligibilityPolicy.mayAdvance(
                    lightningAlertEnabled,
                    notificationDeliveryAvailable,
                )
            ) {
                val transition = LightningEpisodePolicy.reduce(
                    LightningEpisodeState(
                        targetState.lightningEpisodeActive,
                        targetState.activeLightningEventIdentity,
                    ),
                    lightningEvaluation.observations,
                )
                targetState = targetState.copy(
                    lightningArmed = !transition.state.active,
                    lightningEpisodeActive = transition.state.active,
                    activeLightningEventIdentity = transition.state.activeEventIdentity,
                    lightningAlertCheckpoint = lightningEvaluation.lastContiguousEpochSeconds,
                )
                lightningEvent = transition.newEventIdentity?.let { "lightning:$it" }
            }
        }
        targetState = targetState.copy(lastUpdatedEpochSeconds = now.epochSecond)

        val candidateRainEvents = linkedMapOf<String, String?>()
        val candidates = subscriptions.distinctBy(MonitoringSubscription::id).map { subscription ->
            var saved = readSubscription(subscription.id)
                ?.takeIf { it.targetKey == monitorKey }
                ?: SubscriptionMonitoringState(subscription.id, monitorKey)
            val rainEvent = when {
                !AlertDecisionEligibilityPolicy.mayAdvance(
                    rainAlertEnabled,
                    notificationDeliveryAvailable,
                ) -> null
                subscription.rainRequiresPriorClear && rainEvaluation != null -> {
                    val transition = RainEpisodePolicy.reduce(
                        RainEpisodeState(
                            saved.rainArmed,
                            saved.activeRainEventIdentity,
                            saved.lastRainSeriesIdentity,
                        ),
                        rainEvaluation,
                    )
                    saved = saved.copy(
                        rainArmed = transition.state.armed,
                        activeRainEventIdentity = transition.state.activeEventIdentity,
                        lastRainSeriesIdentity = transition.state.lastSeriesIdentity,
                    )
                    transition.newEventIdentity?.let { "rain:$it" }
                }
                !subscription.rainRequiresPriorClear -> appRainEventIdentity?.let { "rain:$it" }
                else -> null
            }
            candidateRainEvents[subscription.id] = rainEvent
            DeliveryCandidate(
                subscription.id,
                subscription.quietHours,
                SubscriptionDeliveryState(saved.consumedRainEvent, saved.consumedLightningEvent),
                rainEvent,
                lightningEvent.takeIf { notificationDeliveryAvailable },
            )
        }
        val delivery = SubscriptionDeliveryPolicy.decide(
            candidates,
            now,
            zoneId,
        )
        preferences.edit(commit = true) {
            putString(targetKey(monitorKey), json.encodeToString(targetState))
            delivery.updated.forEach { (id, state) ->
                val saved = readSubscription(id)
                    ?.takeIf { it.targetKey == monitorKey }
                    ?: SubscriptionMonitoringState(id, monitorKey)
                val subscription = subscriptions.first { it.id == id }
                val rainState = if (subscription.rainRequiresPriorClear &&
                    AlertDecisionEligibilityPolicy.mayAdvance(
                        rainAlertEnabled,
                        notificationDeliveryAvailable,
                    ) && rainEvaluation != null
                ) RainEpisodePolicy.reduce(
                    RainEpisodeState(
                        saved.rainArmed,
                        saved.activeRainEventIdentity,
                        saved.lastRainSeriesIdentity,
                    ),
                    rainEvaluation,
                ).state else RainEpisodeState(
                    saved.rainArmed,
                    saved.activeRainEventIdentity,
                    saved.lastRainSeriesIdentity,
                )
                putString(
                    subscriptionKey(id),
                    json.encodeToString(
                        SubscriptionMonitoringState(
                            id,
                            monitorKey,
                            state.consumedRainEvent,
                            state.consumedLightningEvent,
                            rainState.armed,
                            rainState.activeEventIdentity,
                            rainState.lastSeriesIdentity,
                        ),
                    ),
                )
            }
        }
        val deliveredRainEvent = candidateRainEvents.values.firstOrNull { it != null }
        val kind = if (delivery.shouldNotify) CombinedAlertPolicy.kind(
            deliveredRainEvent != null,
            lightningEvent != null,
        ) else CombinedAlertPolicy.Kind.NONE
        MonitoringDecision(kind, deliveredRainEvent, lightningEvent, targetState)
    }

    fun removeSubscription(subscriptionId: String) = synchronized(lock) {
        preferences.edit(commit = true) { remove(subscriptionKey(subscriptionId)) }
    }

    fun retainSubscriptions(activeIds: Set<String>) = synchronized(lock) {
        preferences.edit(commit = true) {
            preferences.all.keys.filter { it.startsWith(SUBSCRIPTION_PREFIX) }.forEach { key ->
                val id = key.removePrefix(SUBSCRIPTION_PREFIX)
                if (id !in activeIds) remove(key)
            }
        }
    }

    private fun readTarget(targetKey: String): TargetMonitoringState? =
        readTargetKey(targetKey(targetKey))

    private fun readTargetKey(storageKey: String): TargetMonitoringState? =
        preferences.getString(storageKey, null)?.let { raw ->
            runCatching { json.decodeFromString<TargetMonitoringState>(raw) }.getOrNull()
        }

    private fun readSubscription(id: String): SubscriptionMonitoringState? =
        preferences.getString(subscriptionKey(id), null)?.let { raw ->
            runCatching { json.decodeFromString<SubscriptionMonitoringState>(raw) }.getOrNull()
        }

    private companion object {
        const val PREFS = "weather_monitoring_state"
        const val TARGET_PREFIX = "target_"
        const val SUBSCRIPTION_PREFIX = "subscription_"
        const val MAX_RECENT_LIGHTNING_IDENTITIES = 24
        val lock = Any()
        fun storageSuffix(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .take(16)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        fun targetKey(value: String) = TARGET_PREFIX + storageSuffix(value)
        fun subscriptionKey(value: String) = SUBSCRIPTION_PREFIX + value
    }
}
