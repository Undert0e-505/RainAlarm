package com.rainalarm.app.data

enum class FollowRefreshStream { RADAR_NOW, CLOUDS, LIGHTNING, WIND, POINT_WEATHER }

data class ProviderPublicationClock(
    val latestEpochSeconds: Long,
    val cadenceSeconds: Long,
    val enabled: Boolean = true,
) {
    init { require(latestEpochSeconds > 0L && cadenceSeconds in 60L..3_600L) }
}

data class FollowRefreshTicket(
    val stream: FollowRefreshStream,
    val generation: Long,
    val previousLatestEpochSeconds: Long,
)

object ProviderCadencePolicy {
    const val publicationGraceSeconds = 45L
    const val regionalRadarFallbackSeconds = 5 * 60L
    const val openRadarFallbackSeconds = 10 * 60L
    const val modelCadenceSeconds = 15 * 60L
    val retryBackoffSeconds = listOf(60L, 120L, 300L)

    fun radarCadence(observationTimes: List<Long>, regional: Boolean): Long {
        val gaps = observationTimes.distinct().sorted().zipWithNext { first, second -> second - first }
            .filter { it in 60L..1_800L }.sorted()
        return gaps.getOrNull(gaps.size / 2)
            ?: if (regional) regionalRadarFallbackSeconds else openRadarFallbackSeconds
    }

    fun nextExpected(clock: ProviderPublicationClock): Long =
        clock.latestEpochSeconds + clock.cadenceSeconds + publicationGraceSeconds
}

/**
 * Mutable but Android-free scheduling core. The visible Follow composable owns one instance and
 * one loop; generations reject stale completions and every stream has at most one in-flight check.
 */
class FollowRefreshCoordinator {
    private data class State(
        var clock: ProviderPublicationClock,
        var nextDueEpochSeconds: Long,
        var generation: Long = 0L,
        var attempt: Int = 0,
        var inFlight: FollowRefreshTicket? = null,
    )

    private val states = mutableMapOf<FollowRefreshStream, State>()

    fun synchronize(clocks: Map<FollowRefreshStream, ProviderPublicationClock>, nowEpochSeconds: Long) {
        FollowRefreshStream.entries.forEach { stream ->
            val clock = clocks[stream]
            if (clock == null || !clock.enabled) {
                states.remove(stream)
                return@forEach
            }
            val existing = states[stream]
            if (existing == null) {
                states[stream] = State(clock, maxOf(nowEpochSeconds, ProviderCadencePolicy.nextExpected(clock)))
            } else if (clock.latestEpochSeconds > existing.clock.latestEpochSeconds) {
                existing.clock = clock
                existing.attempt = 0
                existing.inFlight = null
                existing.nextDueEpochSeconds = ProviderCadencePolicy.nextExpected(clock)
            } else {
                existing.clock = clock
            }
        }
    }

    fun due(nowEpochSeconds: Long): List<FollowRefreshStream> = states
        .filterValues { it.inFlight == null && nowEpochSeconds >= it.nextDueEpochSeconds }
        .keys.sortedBy(FollowRefreshStream::ordinal)

    fun start(stream: FollowRefreshStream, nowEpochSeconds: Long): FollowRefreshTicket? {
        val state = states[stream] ?: return null
        if (state.inFlight != null || nowEpochSeconds < state.nextDueEpochSeconds) return null
        val ticket = FollowRefreshTicket(stream, ++state.generation, state.clock.latestEpochSeconds)
        state.inFlight = ticket
        return ticket
    }

    fun complete(
        ticket: FollowRefreshTicket,
        observedLatestEpochSeconds: Long?,
        succeeded: Boolean,
        nowEpochSeconds: Long,
    ): Boolean {
        val state = states[ticket.stream] ?: return false
        if (state.inFlight != ticket || state.generation != ticket.generation) return false
        state.inFlight = null
        val advanced = succeeded && observedLatestEpochSeconds != null &&
            observedLatestEpochSeconds > ticket.previousLatestEpochSeconds
        if (advanced) {
            state.clock = state.clock.copy(latestEpochSeconds = requireNotNull(observedLatestEpochSeconds))
            state.attempt = 0
            state.nextDueEpochSeconds = ProviderCadencePolicy.nextExpected(state.clock)
        } else {
            val backoff = ProviderCadencePolicy.retryBackoffSeconds[
                state.attempt.coerceAtMost(ProviderCadencePolicy.retryBackoffSeconds.lastIndex)
            ]
            state.attempt = (state.attempt + 1).coerceAtMost(ProviderCadencePolicy.retryBackoffSeconds.size)
            state.nextDueEpochSeconds = nowEpochSeconds + backoff
        }
        return true
    }

    fun manualRebase(clocks: Map<FollowRefreshStream, ProviderPublicationClock>, nowEpochSeconds: Long) {
        states.clear()
        synchronize(clocks, nowEpochSeconds)
        states.values.forEach { state ->
            state.nextDueEpochSeconds = maxOf(
                ProviderCadencePolicy.nextExpected(state.clock),
                nowEpochSeconds + ProviderCadencePolicy.publicationGraceSeconds,
            )
        }
    }

    fun pause() = states.clear()
    fun isInFlight(stream: FollowRefreshStream): Boolean = states[stream]?.inFlight != null
    fun nextDue(stream: FollowRefreshStream): Long? = states[stream]?.nextDueEpochSeconds
}
