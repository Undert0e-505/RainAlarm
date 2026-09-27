package com.rainalarm.app.data

import android.annotation.SuppressLint
import android.content.Context

const val FEATURE_TOUR_VERSION = 1

enum class FeatureTourStage { NOW, RADAR }
enum class FeatureTourLaunchState { NOW_PENDING, RADAR_PENDING, COMPLETE }

data class FeatureTourProgress(
    val stage: FeatureTourStage,
    val stepIndex: Int,
)

sealed interface FeatureTourAction {
    data object Advance : FeatureTourAction
    data object Skip : FeatureTourAction
}

internal object FeatureTourPolicy {
    const val nowStepCount = 4
    const val radarStepCount = 3

    fun initial(
        launchState: FeatureTourLaunchState?,
        onboardingComplete: Boolean,
        nowSelected: Boolean,
        firstTargetReady: Boolean,
    ): FeatureTourProgress? = if (
        launchState == FeatureTourLaunchState.NOW_PENDING && onboardingComplete && nowSelected && firstTargetReady
    ) FeatureTourProgress(FeatureTourStage.NOW, 0) else null

    fun resumeRadar(
        launchState: FeatureTourLaunchState?,
        onboardingComplete: Boolean,
    ): FeatureTourProgress? = if (
        launchState == FeatureTourLaunchState.RADAR_PENDING && onboardingComplete
    ) FeatureTourProgress(FeatureTourStage.RADAR, 0) else null

    fun reduce(progress: FeatureTourProgress, action: FeatureTourAction): FeatureTourProgress? = when (action) {
        FeatureTourAction.Skip -> null
        FeatureTourAction.Advance -> when (progress.stage) {
            FeatureTourStage.NOW -> if (progress.stepIndex < nowStepCount - 1) {
                progress.copy(stepIndex = progress.stepIndex + 1)
            } else FeatureTourProgress(FeatureTourStage.RADAR, 0)
            FeatureTourStage.RADAR -> if (progress.stepIndex < radarStepCount - 1) {
                progress.copy(stepIndex = progress.stepIndex + 1)
            } else null
        }
    }

    fun completes(progress: FeatureTourProgress, action: FeatureTourAction): Boolean =
        action == FeatureTourAction.Skip ||
            (action == FeatureTourAction.Advance && progress.stage == FeatureTourStage.RADAR &&
                progress.stepIndex == radarStepCount - 1)
}

internal object FeatureTourPersistencePolicy {
    fun initialState(
        freshInstall: Boolean,
        storedNowComplete: Boolean?,
        storedRadarComplete: Boolean?,
    ): FeatureTourLaunchState {
        val default = !freshInstall
        val nowComplete = storedNowComplete ?: default
        val radarComplete = storedRadarComplete ?: default
        return when {
            !nowComplete -> FeatureTourLaunchState.NOW_PENDING
            !radarComplete -> FeatureTourLaunchState.RADAR_PENDING
            else -> FeatureTourLaunchState.COMPLETE
        }
    }
}

/**
 * Synchronous, versioned first-install gate. A missing key is eligible only when the Places store
 * independently proves this is a fresh install; upgrades therefore default to completed.
 */
@SuppressLint("ApplySharedPref", "UseKtx")
class FeatureTourPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("feature_tour", Context.MODE_PRIVATE)

    fun initialize(freshInstall: Boolean): FeatureTourLaunchState {
        val nowKey = stageKey(FEATURE_TOUR_VERSION, "now")
        val radarKey = stageKey(FEATURE_TOUR_VERSION, "radar")
        val storedNow = preferences.getBoolean(nowKey, false).takeIf { preferences.contains(nowKey) }
        val storedRadar = preferences.getBoolean(radarKey, false).takeIf { preferences.contains(radarKey) }
        val initial = FeatureTourPersistencePolicy.initialState(freshInstall, storedNow, storedRadar)
        if (!preferences.contains(nowKey) || !preferences.contains(radarKey)) {
            val nowComplete = initial != FeatureTourLaunchState.NOW_PENDING
            val radarComplete = initial == FeatureTourLaunchState.COMPLETE
            preferences.edit()
                .putBoolean(nowKey, nowComplete)
                .putBoolean(radarKey, radarComplete)
                .commit()
        }
        return initial
    }

    fun launchState(): FeatureTourLaunchState {
        val now = preferences.getBoolean(stageKey(FEATURE_TOUR_VERSION, "now"), true)
        val radar = preferences.getBoolean(stageKey(FEATURE_TOUR_VERSION, "radar"), true)
        return when {
            !now -> FeatureTourLaunchState.NOW_PENDING
            !radar -> FeatureTourLaunchState.RADAR_PENDING
            else -> FeatureTourLaunchState.COMPLETE
        }
    }

    fun completeNow(): Boolean = preferences.edit()
        .putBoolean(stageKey(FEATURE_TOUR_VERSION, "now"), true)
        .commit()

    fun complete(): Boolean = preferences.edit()
        .putBoolean(stageKey(FEATURE_TOUR_VERSION, "now"), true)
        .putBoolean(stageKey(FEATURE_TOUR_VERSION, "radar"), true)
        .commit()

    fun resetForReplay(): Boolean = preferences.edit()
        .putBoolean(stageKey(FEATURE_TOUR_VERSION, "now"), false)
        .putBoolean(stageKey(FEATURE_TOUR_VERSION, "radar"), false)
        .commit()

    internal companion object {
        fun stageKey(version: Int, stage: String): String = "feature_tour_v${version}_$stage"
    }
}
