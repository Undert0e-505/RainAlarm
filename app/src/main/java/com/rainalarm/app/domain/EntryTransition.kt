package com.rainalarm.app.domain

/**
 * Lifecycle of a retained page's entry presentation.
 *
 * A page is prepared only after it is fully off-screen. Navigation requests playback on that same
 * generation, and a completion callback may settle only the generation it started. This keeps a
 * retained page from briefly exposing its old settled presentation before resetting.
 */
enum class EntryTransitionPhase { PREPARED, PLAY_REQUESTED, SETTLED }

data class EntryTransitionState(
    val generation: Int,
    val phase: EntryTransitionPhase,
)

object EntryTransitionPolicy {
    fun initial(active: Boolean): EntryTransitionState = EntryTransitionState(
        generation = 1,
        phase = if (active) EntryTransitionPhase.PLAY_REQUESTED else EntryTransitionPhase.PREPARED,
    )

    fun prepare(state: EntryTransitionState): EntryTransitionState =
        if (state.phase == EntryTransitionPhase.PREPARED) state
        else EntryTransitionState(state.generation + 1, EntryTransitionPhase.PREPARED)

    fun requestPlay(state: EntryTransitionState): EntryTransitionState = when (state.phase) {
        EntryTransitionPhase.PREPARED -> state.copy(phase = EntryTransitionPhase.PLAY_REQUESTED)
        EntryTransitionPhase.PLAY_REQUESTED -> state
        EntryTransitionPhase.SETTLED -> EntryTransitionState(
            state.generation + 1,
            EntryTransitionPhase.PLAY_REQUESTED,
        )
    }

    fun settle(state: EntryTransitionState, generation: Int): EntryTransitionState =
        if (state.generation == generation && state.phase == EntryTransitionPhase.PLAY_REQUESTED) {
            state.copy(phase = EntryTransitionPhase.SETTLED)
        } else state
}

/** Native MapLibre start-frame acknowledgement for one entry generation. */
class RadarEntryFrameHandshake {
    private var generation: Int? = null
    private var frameStarted = false
    private var acknowledged = false

    fun prepare(generation: Int) {
        this.generation = generation
        frameStarted = false
        acknowledged = false
    }

    fun cancel(generation: Int) {
        if (this.generation == generation) {
            this.generation = null
            frameStarted = false
            acknowledged = false
        }
    }

    fun frameStarted() {
        if (generation != null && !acknowledged) frameStarted = true
    }

    fun frameRendered(
        fully: Boolean,
        cameraMatchesStart: Boolean,
        retainedStyleWasCoherent: Boolean = false,
    ): Int? {
        val current = generation
        // A retained MapLibre style has already produced a complete coherent frame. Its next
        // camera frame can legitimately be reported as incomplete while additional tiles for the
        // zoomed-out start camera stream in. Requiring `fully` in that case made slow provider/GPU
        // combinations miss the short entry window even though MapLibre had produced the exact
        // prepared frame. A newly loaded/replaced style still requires a complete frame.
        val coherentFrame = fully || retainedStyleWasCoherent
        val qualifies = current != null && !acknowledged && frameStarted && coherentFrame &&
            cameraMatchesStart
        frameStarted = false
        if (!qualifies) return null
        acknowledged = true
        return current
    }
}

object RadarEntryFramePolicy {
    /**
     * This is a genuine native-render failure bound, not an animation scheduling delay. Normal
     * retained-map entries acknowledge the first matching frame and never wait for this value.
     */
    const val PLAY_READINESS_FAILURE_TIMEOUT_MILLIS = 8_000L
    private const val COORDINATE_TOLERANCE = 0.000_02
    private const val ZOOM_TOLERANCE = 0.015

    fun cameraMatchesStart(actual: RadarCameraTarget, start: RadarCameraTarget): Boolean =
        kotlin.math.abs(actual.latitude - start.latitude) <= COORDINATE_TOLERANCE &&
            kotlin.math.abs(actual.longitude - start.longitude) <= COORDINATE_TOLERANCE &&
            kotlin.math.abs(actual.zoom - start.zoom) <= ZOOM_TOLERANCE
}

/** Imperative native child visibility for one retained-Radar entry generation. */
class RadarNativePresentationGate {
    private var hiddenGeneration: Int? = null
    private var revealedGeneration: Int? = null

    val isHidden: Boolean get() = hiddenGeneration != null

    /** Returns true only when callers must newly hide the native presentation. */
    fun prepare(generation: Int): Boolean {
        if (revealedGeneration == generation || hiddenGeneration == generation) return false
        hiddenGeneration = generation
        return true
    }

    /** Stale native callbacks cannot reveal a newer prepared generation. */
    fun reveal(generation: Int): Boolean {
        if (hiddenGeneration != generation) return false
        hiddenGeneration = null
        revealedGeneration = generation
        return true
    }

    fun isHiddenFor(generation: Int): Boolean = hiddenGeneration == generation
}
