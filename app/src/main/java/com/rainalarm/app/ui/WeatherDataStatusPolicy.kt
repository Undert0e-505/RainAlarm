package com.rainalarm.app.ui

import com.rainalarm.app.data.EumetLayerMetadata
import com.rainalarm.app.data.RadarMapLayer
import com.rainalarm.app.data.WindGrid
import com.rainalarm.app.data.WindGridFailureDiagnostic
import com.rainalarm.app.data.WindGridNetworkPolicy

/** The complete user-facing vocabulary for weather-stream availability. */
internal enum class WeatherDataKind(val label: String) {
    LOCATION("Location"),
    RADAR("Radar"),
    WIND("Wind"),
    CLOUDS("Clouds"),
    LIGHTNING("Lightning"),
}

internal object WeatherDataStatusPolicy {
    fun loading(kind: WeatherDataKind, completed: Int? = null, total: Int? = null): String {
        val progress = if (completed != null && total != null && total > 0) {
            " ${completed.coerceIn(0, total)}/$total"
        } else ""
        return "${kind.label} loading$progress"
    }

    fun unavailable(kind: WeatherDataKind): String = "${kind.label} unavailable"
}

/**
 * One automatic replacement is requested for each stale source identity. A failed replacement
 * remains unavailable until a manual/coordinator refresh or a genuinely different identity is due.
 */
internal object WeatherDataReplacementPolicy {
    const val windMaximumAgeSeconds = 15L * 60L

    fun staleWindIdentity(
        grid: WindGrid?,
        expectedViewportKey: String?,
        nowEpochSeconds: Long,
    ): String? {
        grid ?: return null
        val age = nowEpochSeconds - grid.fetchedEpochSeconds
        if (grid.viewportKey == expectedViewportKey && age in 0L..windMaximumAgeSeconds) return null
        return "${grid.viewportKey}:${grid.fetchedEpochSeconds}:$expectedViewportKey"
    }

    fun staleSatelliteIdentity(metadata: EumetLayerMetadata?, nowEpochSeconds: Long): String? =
        metadata?.takeUnless { it.freshAt(nowEpochSeconds) }
            ?.let { "${it.product.name}:${it.availableFromEpochSeconds}:${it.latestEpochSeconds}" }

    fun shouldRequest(staleIdentity: String?, lastRequestedIdentity: String?): Boolean =
        staleIdentity != null && staleIdentity != lastRequestedIdentity
}

internal data class WindGridRequestIdentity(
    val viewportKey: String,
    val timelineKey: String,
    val refreshGeneration: Int,
)

/** Identifies the exact acquisition/grid/viewport that a WindFieldView must acknowledge. */
internal data class WindGridRenderToken(
    val generation: Int,
    val viewportKey: String,
    val fetchedEpochSeconds: Long,
)

internal data class WindGridRenderRequest(
    val grid: WindGrid,
    val token: WindGridRenderToken,
)

internal data class WindGridAcquisitionDeadline(
    val generation: Int,
    val request: WindGridRequestIdentity,
    val deadlineAtMillis: Long,
)

/** Keeps slow or cancelled viewport requests from replacing the latest requested wind field. */
internal object WindGridLoadPolicy {
    fun identity(
        viewportKey: String?,
        timelineKey: String,
        refreshGeneration: Int,
    ): WindGridRequestIdentity? = viewportKey?.let {
        WindGridRequestIdentity(it, timelineKey, refreshGeneration)
    }

    fun canRetain(
        grid: WindGrid?,
        viewportKey: String,
        nowEpochSeconds: Long,
    ): Boolean = grid != null && grid.viewportKey == viewportKey &&
        nowEpochSeconds - grid.fetchedEpochSeconds in
        0L..WeatherDataReplacementPolicy.windMaximumAgeSeconds

}

internal object WindGridAcquisitionDeadlinePolicy {
    const val windowMillis = WindGridNetworkPolicy.totalTimeoutMillis

    fun monotonicNowMillis(): Long = System.nanoTime() / 1_000_000L

    fun remainingMillis(startedAtMillis: Long, nowMillis: Long): Long {
        val elapsed = (nowMillis - startedAtMillis).coerceAtLeast(0L)
        return (windowMillis - elapsed).coerceAtLeast(0L)
    }

}

/**
 * A newly downloaded grid is timestamped after the screen's low-frequency weather clock snapshot.
 * Reconcile the snapshot before freshness checks so fresh data can render immediately instead of
 * waiting for the next minute tick (or reaching the render-confirmation deadline first).
 */
internal object WindGridPresentationClockPolicy {
    fun reconcile(periodicNowEpochSeconds: Long, grid: WindGrid?): Long =
        maxOf(periodicNowEpochSeconds, grid?.fetchedEpochSeconds ?: periodicNowEpochSeconds)
}

internal sealed interface WindGridAcquisitionState {
    val generation: Int

    data class Disabled(override val generation: Int = 0) : WindGridAcquisitionState
    data class AwaitingViewport(
        override val generation: Int,
        val force: Boolean,
    ) : WindGridAcquisitionState
    data class Loading(
        override val generation: Int,
        val request: WindGridRequestIdentity,
        val startedAtMillis: Long,
        val deadlineAtMillis: Long,
        val force: Boolean,
        val retainedGrid: WindGrid?,
        val retainedRenderConfirmed: Boolean = false,
        val recordedFailure: WindGridFailureDiagnostic? = null,
    ) : WindGridAcquisitionState
    data class DataReadyPendingRender(
        override val generation: Int,
        val request: WindGridRequestIdentity,
        val startedAtMillis: Long,
        val deadlineAtMillis: Long,
        val grid: WindGrid,
        val retainedGrid: WindGrid?,
        val retainedRenderConfirmed: Boolean,
    ) : WindGridAcquisitionState
    data class Ready(
        override val generation: Int,
        val request: WindGridRequestIdentity,
        val grid: WindGrid,
    ) : WindGridAcquisitionState
    data class Unavailable(
        override val generation: Int,
        val request: WindGridRequestIdentity,
        val recordedFailure: WindGridFailureDiagnostic,
    ) : WindGridAcquisitionState
}

internal sealed interface WindGridAcquisitionEvent {
    data class Environment(
        val enabled: Boolean,
        val viewportKey: String?,
        val timelineKey: String,
        val nowElapsedMillis: Long,
        val nowEpochSeconds: Long,
    ) : WindGridAcquisitionEvent
    data class Refresh(
        val viewportKey: String?,
        val timelineKey: String,
        val nowElapsedMillis: Long,
        val nowEpochSeconds: Long,
    ) : WindGridAcquisitionEvent
    data class Succeeded(
        val generation: Int,
        val request: WindGridRequestIdentity,
        val grid: WindGrid,
    ) : WindGridAcquisitionEvent
    data class Failed(
        val generation: Int,
        val request: WindGridRequestIdentity,
        val diagnostic: WindGridFailureDiagnostic,
    ) : WindGridAcquisitionEvent
    data class DeadlineReached(
        val generation: Int,
        val request: WindGridRequestIdentity,
        val nowElapsedMillis: Long,
        val nowEpochSeconds: Long,
    ) : WindGridAcquisitionEvent
    data class RenderObserved(
        val token: WindGridRenderToken,
        val drawnArrowCount: Int,
        val nowElapsedMillis: Long,
    ) : WindGridAcquisitionEvent
    data class RendererReset(
        val nowElapsedMillis: Long,
    ) : WindGridAcquisitionEvent
}

/** Single authority for Wind request generations, retained arrows and user-facing status. */
internal object WindGridAcquisitionReducer {
    fun reduce(
        state: WindGridAcquisitionState,
        event: WindGridAcquisitionEvent,
    ): WindGridAcquisitionState = when (event) {
        is WindGridAcquisitionEvent.Environment -> environment(state, event)
        is WindGridAcquisitionEvent.Refresh -> start(
            state = state,
            viewportKey = event.viewportKey,
            timelineKey = event.timelineKey,
            nowElapsedMillis = event.nowElapsedMillis,
            nowEpochSeconds = event.nowEpochSeconds,
            force = true,
            reuseAwaitingGeneration = false,
        )
        is WindGridAcquisitionEvent.Succeeded -> {
            val loading = state as? WindGridAcquisitionState.Loading
            if (loading?.generation != event.generation || loading.request != event.request ||
                event.grid.viewportKey != event.request.viewportKey) state
            else WindGridAcquisitionState.DataReadyPendingRender(
                generation = event.generation,
                request = event.request,
                startedAtMillis = loading.startedAtMillis,
                deadlineAtMillis = loading.deadlineAtMillis,
                grid = event.grid,
                retainedGrid = loading.retainedGrid,
                retainedRenderConfirmed = loading.retainedRenderConfirmed,
            )
        }
        is WindGridAcquisitionEvent.Failed -> {
            val loading = state as? WindGridAcquisitionState.Loading
            if (loading?.generation != event.generation || loading.request != event.request) state
            else loading.copy(recordedFailure = event.diagnostic)
        }
        is WindGridAcquisitionEvent.DeadlineReached -> {
            val pending = when (state) {
                is WindGridAcquisitionState.Loading -> DeadlineState(
                    state.generation, state.request, state.deadlineAtMillis,
                    state.retainedGrid, state.retainedRenderConfirmed,
                    state.recordedFailure ?: WindGridFailureDiagnostic("deadline"),
                )
                is WindGridAcquisitionState.DataReadyPendingRender -> DeadlineState(
                    state.generation, state.request, state.deadlineAtMillis,
                    state.retainedGrid, state.retainedRenderConfirmed,
                    WindGridFailureDiagnostic("render_unconfirmed"),
                )
                else -> null
            }
            if (pending?.generation != event.generation || pending.request != event.request ||
                event.nowElapsedMillis < pending.deadlineAtMillis) state
            else pending.retainedGrid?.takeIf {
                pending.retainedRenderConfirmed &&
                WindGridLoadPolicy.canRetain(
                    it, pending.request.viewportKey, event.nowEpochSeconds,
                )
            }?.let {
                WindGridAcquisitionState.Ready(pending.generation, pending.request, it)
            } ?: WindGridAcquisitionState.Unavailable(
                pending.generation, pending.request, pending.failure,
            )
        }
        is WindGridAcquisitionEvent.RenderObserved -> renderObserved(state, event)
        is WindGridAcquisitionEvent.RendererReset -> rendererReset(state, event)
    }

    fun status(state: WindGridAcquisitionState): AncillaryStatus = when (state) {
        is WindGridAcquisitionState.Disabled -> AncillaryStatus.Off
        is WindGridAcquisitionState.AwaitingViewport,
        is WindGridAcquisitionState.Loading,
        is WindGridAcquisitionState.DataReadyPendingRender -> AncillaryStatus.Loading
        is WindGridAcquisitionState.Ready -> AncillaryStatus.Wind(state.grid)
        is WindGridAcquisitionState.Unavailable -> AncillaryStatus.Unavailable("Wind unavailable")
    }

    fun renderRequest(
        state: WindGridAcquisitionState,
        viewportKey: String?,
        nowEpochSeconds: Long,
    ): WindGridRenderRequest? {
        viewportKey ?: return null
        val candidate = when (state) {
            is WindGridAcquisitionState.Loading -> state.retainedGrid
            is WindGridAcquisitionState.DataReadyPendingRender -> state.grid
            is WindGridAcquisitionState.Ready -> state.grid
            else -> null
        }
        return candidate?.takeIf {
            WindGridLoadPolicy.canRetain(it, viewportKey, nowEpochSeconds)
        }?.let { grid ->
            WindGridRenderRequest(
                grid,
                WindGridRenderToken(state.generation, viewportKey, grid.fetchedEpochSeconds),
            )
        }
    }

    fun renderGrid(
        state: WindGridAcquisitionState,
        viewportKey: String?,
        nowEpochSeconds: Long,
    ): WindGrid? = renderRequest(state, viewportKey, nowEpochSeconds)?.grid

    /** Includes the synchronous guard used before Environment effects reconcile a changed view. */
    fun presentationStatus(
        state: WindGridAcquisitionState,
        enabled: Boolean,
        viewportKey: String?,
        nowEpochSeconds: Long,
    ): AncillaryStatus {
        if (!enabled) return AncillaryStatus.Off
        val request = renderRequest(state, viewportKey, nowEpochSeconds)
        return if (state is WindGridAcquisitionState.Ready && request != null &&
            request.token == token(state)) AncillaryStatus.Wind(state.grid)
        else when (state) {
            is WindGridAcquisitionState.Unavailable ->
                AncillaryStatus.Unavailable("Wind unavailable")
            else -> AncillaryStatus.Loading
        }
    }

    fun latestGrid(state: WindGridAcquisitionState): WindGrid? = when (state) {
        is WindGridAcquisitionState.Loading -> state.retainedGrid
        is WindGridAcquisitionState.DataReadyPendingRender -> state.grid
        is WindGridAcquisitionState.Ready -> state.grid
        else -> null
    }

    fun deadline(state: WindGridAcquisitionState): WindGridAcquisitionDeadline? = when (state) {
        is WindGridAcquisitionState.Loading -> WindGridAcquisitionDeadline(
            state.generation, state.request, state.deadlineAtMillis,
        )
        is WindGridAcquisitionState.DataReadyPendingRender -> WindGridAcquisitionDeadline(
            state.generation, state.request, state.deadlineAtMillis,
        )
        else -> null
    }

    private fun environment(
        state: WindGridAcquisitionState,
        event: WindGridAcquisitionEvent.Environment,
    ): WindGridAcquisitionState {
        if (!event.enabled) {
            return if (state is WindGridAcquisitionState.Disabled) state
            else WindGridAcquisitionState.Disabled(nextGeneration(state))
        }
        if (event.viewportKey == null) {
            return if (state is WindGridAcquisitionState.AwaitingViewport) state
            else WindGridAcquisitionState.AwaitingViewport(nextGeneration(state), force = false)
        }
        val matchingRequest = when (state) {
            is WindGridAcquisitionState.Loading -> state.request
            is WindGridAcquisitionState.DataReadyPendingRender -> state.request
            is WindGridAcquisitionState.Ready -> state.request
            is WindGridAcquisitionState.Unavailable -> state.request
            else -> null
        }?.let { it.viewportKey == event.viewportKey && it.timelineKey == event.timelineKey } == true
        if (matchingRequest) return state
        return start(
            state, event.viewportKey, event.timelineKey, event.nowElapsedMillis,
            event.nowEpochSeconds, force = false,
            reuseAwaitingGeneration = state is WindGridAcquisitionState.AwaitingViewport,
        )
    }

    private fun start(
        state: WindGridAcquisitionState,
        viewportKey: String?,
        timelineKey: String,
        nowElapsedMillis: Long,
        nowEpochSeconds: Long,
        force: Boolean,
        reuseAwaitingGeneration: Boolean,
    ): WindGridAcquisitionState {
        val generation = if (reuseAwaitingGeneration) state.generation else nextGeneration(state)
        if (viewportKey == null) return WindGridAcquisitionState.AwaitingViewport(generation, force)
        val (confirmedGrid, confirmed) = confirmedGrid(state)
        val retained = confirmedGrid?.takeIf {
            WindGridLoadPolicy.canRetain(it, viewportKey, nowEpochSeconds)
        }
        val request = requireNotNull(WindGridLoadPolicy.identity(
            viewportKey, timelineKey, generation,
        ))
        return WindGridAcquisitionState.Loading(
            generation = generation,
            request = request,
            startedAtMillis = nowElapsedMillis,
            deadlineAtMillis = nowElapsedMillis + WindGridAcquisitionDeadlinePolicy.windowMillis,
            force = force || (state as? WindGridAcquisitionState.AwaitingViewport)?.force == true,
            retainedGrid = retained,
            retainedRenderConfirmed = retained != null && confirmed,
        )
    }

    private fun renderObserved(
        state: WindGridAcquisitionState,
        event: WindGridAcquisitionEvent.RenderObserved,
    ): WindGridAcquisitionState = when (state) {
        is WindGridAcquisitionState.Loading -> {
            val expected = state.retainedGrid?.let {
                WindGridRenderToken(state.generation, state.request.viewportKey, it.fetchedEpochSeconds)
            }
            if (event.token != expected) state
            else state.copy(retainedRenderConfirmed = event.drawnArrowCount > 0)
        }
        is WindGridAcquisitionState.DataReadyPendingRender -> {
            if (event.token != token(state) || event.drawnArrowCount <= 0) state
            else WindGridAcquisitionState.Ready(state.generation, state.request, state.grid)
        }
        is WindGridAcquisitionState.Ready -> {
            if (event.token != token(state) || event.drawnArrowCount > 0) state
            else WindGridAcquisitionState.DataReadyPendingRender(
                state.generation,
                state.request,
                event.nowElapsedMillis,
                event.nowElapsedMillis + WindGridAcquisitionDeadlinePolicy.windowMillis,
                state.grid,
                retainedGrid = null,
                retainedRenderConfirmed = false,
            )
        }
        else -> state
    }

    private fun rendererReset(
        state: WindGridAcquisitionState,
        event: WindGridAcquisitionEvent.RendererReset,
    ): WindGridAcquisitionState = when (state) {
        is WindGridAcquisitionState.Loading -> state.copy(retainedRenderConfirmed = false)
        is WindGridAcquisitionState.DataReadyPendingRender ->
            state.copy(retainedRenderConfirmed = false)
        is WindGridAcquisitionState.Ready -> WindGridAcquisitionState.DataReadyPendingRender(
            state.generation,
            state.request,
            event.nowElapsedMillis,
            event.nowElapsedMillis + WindGridAcquisitionDeadlinePolicy.windowMillis,
            state.grid,
            retainedGrid = null,
            retainedRenderConfirmed = false,
        )
        else -> state
    }

    private fun token(state: WindGridAcquisitionState.Ready): WindGridRenderToken =
        WindGridRenderToken(state.generation, state.request.viewportKey, state.grid.fetchedEpochSeconds)

    private fun token(state: WindGridAcquisitionState.DataReadyPendingRender): WindGridRenderToken =
        WindGridRenderToken(state.generation, state.request.viewportKey, state.grid.fetchedEpochSeconds)

    private fun confirmedGrid(state: WindGridAcquisitionState): Pair<WindGrid?, Boolean> = when (state) {
        is WindGridAcquisitionState.Ready -> state.grid to true
        is WindGridAcquisitionState.Loading -> state.retainedGrid to state.retainedRenderConfirmed
        is WindGridAcquisitionState.DataReadyPendingRender ->
            state.retainedGrid to state.retainedRenderConfirmed
        else -> null to false
    }

    private data class DeadlineState(
        val generation: Int,
        val request: WindGridRequestIdentity,
        val deadlineAtMillis: Long,
        val retainedGrid: WindGrid?,
        val retainedRenderConfirmed: Boolean,
        val failure: WindGridFailureDiagnostic,
    )

    private fun nextGeneration(state: WindGridAcquisitionState): Int =
        if (state.generation == Int.MAX_VALUE) 1 else state.generation + 1
}

internal enum class WeatherReplacementPhase { IDLE, LOADING, UNAVAILABLE }

internal object WeatherReplacementPresentationPolicy {
    fun status(
        kind: WeatherDataKind,
        source: AncillaryStatus,
        phase: WeatherReplacementPhase,
        staleIdentity: String?,
        lastRequestedIdentity: String?,
    ): AncillaryStatus = when {
        phase == WeatherReplacementPhase.LOADING -> AncillaryStatus.Loading
        phase == WeatherReplacementPhase.UNAVAILABLE ->
            AncillaryStatus.Unavailable(WeatherDataStatusPolicy.unavailable(kind))
        staleIdentity != null && staleIdentity == lastRequestedIdentity ->
            AncillaryStatus.Unavailable(WeatherDataStatusPolicy.unavailable(kind))
        staleIdentity != null -> AncillaryStatus.Loading
        else -> source
    }
}

internal object ManualWeatherRefreshPolicy {
    fun enabledLayers(enabled: Set<RadarMapLayer>): Set<RadarMapLayer> =
        enabled.intersect(RadarMapLayer.overlays.toSet())
}
