package com.rainalarm.app.ui

import com.rainalarm.app.data.ProviderCadencePolicy

internal enum class RadarForegroundRefreshStream {
    RADAR,
    CLOUDS,
    LIGHTNING,
    WIND,
}

internal data class RadarForegroundStreamFreshness(
    val acquiredAtElapsedMillis: Long? = null,
    val lastAttemptAtElapsedMillis: Long? = null,
    val cadenceSeconds: Long,
) {
    init {
        require(acquiredAtElapsedMillis == null || acquiredAtElapsedMillis >= 0L)
        require(lastAttemptAtElapsedMillis == null || lastAttemptAtElapsedMillis >= 0L)
        require(cadenceSeconds in 60L..3_600L)
    }

    val freshnessAnchorElapsedMillis: Long?
        get() = listOfNotNull(acquiredAtElapsedMillis, lastAttemptAtElapsedMillis).maxOrNull()
}

internal data class RadarForegroundTransition(
    val generation: Long,
    val backgroundedAtElapsedMillis: Long,
    val resumedAtElapsedMillis: Long,
)

/** Durable lifecycle state owned by the ViewModel rather than a lifecycle-aware UI collector. */
internal data class RadarForegroundLifecycleState(
    val hasObservedForeground: Boolean = false,
    val appForeground: Boolean = false,
    val backgroundedAtElapsedMillis: Long? = null,
    val latestTransition: RadarForegroundTransition? = null,
    val consumedGeneration: Long = 0L,
    val nextGeneration: Long = 1L,
) {
    val pendingTransition: RadarForegroundTransition?
        get() = latestTransition?.takeIf { it.generation > consumedGeneration }
}

internal enum class RadarForegroundStreamReadiness {
    /** Identity, viewport or first attempt is still settling; retain the resume transition. */
    WAITING,

    /** Acquisition or its latest attempt provides the cadence/throttle anchor. */
    EVALUABLE,

    /** The enabled stream is deterministically unavailable and must not block other streams. */
    UNAVAILABLE,
}

internal data class RadarForegroundRefreshStreamState(
    val readiness: RadarForegroundStreamReadiness,
    val freshness: RadarForegroundStreamFreshness? = null,
) {
    init {
        require(readiness != RadarForegroundStreamReadiness.EVALUABLE ||
            freshness?.freshnessAnchorElapsedMillis != null)
    }
}

internal data class RadarForegroundResumeRefreshDecision(
    val transition: RadarForegroundTransition? = null,
    val dueStreams: Set<RadarForegroundRefreshStream> = emptySet(),
    val waitingStreams: Set<RadarForegroundRefreshStream> = emptySet(),
)

/**
 * Android-free foreground-edge policy for retained map weather.
 *
 * Provider valid times are deliberately not used as an age clock: regional observations can be
 * delayed upstream by tens of minutes. A successful acquisition (or the latest failed attempt)
 * opens one provider-cadence window plus the normal publication-arrival grace. Only a genuine
 * background -> foreground edge may consume that window, and an edge resumed on another page is
 * retained until Radar becomes active.
 */
internal object RadarForegroundResumeRefreshPolicy {
    const val publicationArrivalGraceMillis =
        ProviderCadencePolicy.publicationGraceSeconds * 1_000L

    fun observeLifecycle(
        state: RadarForegroundLifecycleState,
        appForeground: Boolean,
        nowElapsedMillis: Long,
    ): RadarForegroundLifecycleState {
        require(nowElapsedMillis >= 0L)
        if (state.appForeground == appForeground) return state

        if (!appForeground) {
            if (!state.hasObservedForeground) return state
            return state.copy(
                appForeground = false,
                backgroundedAtElapsedMillis = nowElapsedMillis,
            )
        }
        if (!state.hasObservedForeground) {
            // The first ON_START is startup, not a return from the background. Initial acquisition
            // already owns this transition.
            return state.copy(
                hasObservedForeground = true,
                appForeground = true,
                backgroundedAtElapsedMillis = null,
            )
        }
        if (state.appForeground) return state

        val transition = RadarForegroundTransition(
            generation = state.nextGeneration,
            backgroundedAtElapsedMillis = state.backgroundedAtElapsedMillis ?: nowElapsedMillis,
            resumedAtElapsedMillis = nowElapsedMillis,
        )
        return state.copy(
            appForeground = true,
            backgroundedAtElapsedMillis = null,
            latestTransition = transition,
            nextGeneration = state.nextGeneration + 1L,
        )
    }

    fun markConsumed(
        state: RadarForegroundLifecycleState,
        generation: Long,
    ): RadarForegroundLifecycleState {
        require(generation > 0L)
        val latestGeneration = state.latestTransition?.generation ?: return state
        if (generation > latestGeneration || generation <= state.consumedGeneration) return state
        return state.copy(consumedGeneration = generation)
    }

    fun consumeWhenRadarActive(
        state: RadarForegroundLifecycleState,
        screenActive: Boolean,
        nowElapsedMillis: Long,
        expectedStreams: Set<RadarForegroundRefreshStream>,
        streams: Map<RadarForegroundRefreshStream, RadarForegroundRefreshStreamState>,
    ): RadarForegroundResumeRefreshDecision {
        require(nowElapsedMillis >= 0L)
        val transition = state.pendingTransition
            ?: return RadarForegroundResumeRefreshDecision()
        if (!state.appForeground || !screenActive) {
            return RadarForegroundResumeRefreshDecision()
        }
        val waiting = expectedStreams.filterTo(linkedSetOf()) { stream ->
            streams[stream]?.readiness == null ||
                streams[stream]?.readiness == RadarForegroundStreamReadiness.WAITING
        }
        if (waiting.isNotEmpty()) {
            return RadarForegroundResumeRefreshDecision(waitingStreams = waiting)
        }
        val due = expectedStreams.filterTo(linkedSetOf()) { stream ->
            val freshness = streams[stream]
                ?.takeIf { it.readiness == RadarForegroundStreamReadiness.EVALUABLE }
                ?.freshness
                ?: return@filterTo false
            nowElapsedMillis >= nextOpportunityAfter(
                requireNotNull(freshness.freshnessAnchorElapsedMillis),
                freshness.cadenceSeconds,
                transition.backgroundedAtElapsedMillis,
            )
        }
        return RadarForegroundResumeRefreshDecision(
            transition = transition,
            dueStreams = due,
        )
    }

    fun nextOpportunityElapsedMillis(anchorElapsedMillis: Long, cadenceSeconds: Long): Long {
        require(anchorElapsedMillis >= 0L)
        require(cadenceSeconds in 60L..3_600L)
        return anchorElapsedMillis + cadenceSeconds * 1_000L + publicationArrivalGraceMillis
    }

    private fun nextOpportunityAfter(
        anchorElapsedMillis: Long,
        cadenceSeconds: Long,
        afterElapsedMillis: Long,
    ): Long {
        val first = nextOpportunityElapsedMillis(anchorElapsedMillis, cadenceSeconds)
        if (first > afterElapsedMillis) return first
        val cadenceMillis = cadenceSeconds * 1_000L
        val elapsedCadences = (afterElapsedMillis - first) / cadenceMillis + 1L
        return first + elapsedCadences * cadenceMillis
    }
}
