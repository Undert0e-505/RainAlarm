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

internal data class RadarForegroundResumeRefreshState(
    val hasObservedForeground: Boolean = false,
    val appForeground: Boolean = false,
    val backgroundedAtElapsedMillis: Long? = null,
    val pendingTransition: RadarForegroundTransition? = null,
    val nextGeneration: Long = 1L,
)

internal data class RadarForegroundResumeRefreshDecision(
    val state: RadarForegroundResumeRefreshState,
    val transition: RadarForegroundTransition? = null,
    val dueStreams: Set<RadarForegroundRefreshStream> = emptySet(),
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

    fun observeForeground(
        state: RadarForegroundResumeRefreshState,
        appForeground: Boolean,
        nowElapsedMillis: Long,
    ): RadarForegroundResumeRefreshState {
        require(nowElapsedMillis >= 0L)
        if (state.appForeground == appForeground &&
            (state.hasObservedForeground || !appForeground)) return state

        if (!appForeground) {
            return state.copy(
                appForeground = false,
                backgroundedAtElapsedMillis = nowElapsedMillis,
                // A later foreground edge supersedes an older deferred edge.
                pendingTransition = null,
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
            pendingTransition = transition,
            nextGeneration = state.nextGeneration + 1L,
        )
    }

    fun consumeWhenRadarActive(
        state: RadarForegroundResumeRefreshState,
        screenActive: Boolean,
        nowElapsedMillis: Long,
        streams: Map<RadarForegroundRefreshStream, RadarForegroundStreamFreshness>,
    ): RadarForegroundResumeRefreshDecision {
        require(nowElapsedMillis >= 0L)
        val transition = state.pendingTransition
            ?: return RadarForegroundResumeRefreshDecision(state)
        if (!state.appForeground || !screenActive) {
            return RadarForegroundResumeRefreshDecision(state)
        }
        val due = streams.filterValues { freshness ->
            freshness.freshnessAnchorElapsedMillis?.let { anchor ->
                nowElapsedMillis >= nextOpportunityAfter(
                    anchor,
                    freshness.cadenceSeconds,
                    transition.backgroundedAtElapsedMillis,
                )
            } == true
        }.keys
        return RadarForegroundResumeRefreshDecision(
            state = state.copy(pendingTransition = null),
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
