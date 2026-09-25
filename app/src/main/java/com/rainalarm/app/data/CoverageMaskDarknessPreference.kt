package com.rainalarm.app.data

/**
 * User-facing strength of the shared unknown/outside radar-coverage scrim.
 *
 * The persisted value is normalized rather than tied to one map theme's opacity. Missing values
 * use the 50% default; malformed and future out-of-range values fail safely into 0%..100%.
 */
object CoverageMaskDarknessPreference {
    const val MIN = 0f
    const val MAX = 1f
    const val DEFAULT = 0.50f

    fun decode(value: Float?): Float =
        value?.takeIf { it.isFinite() }?.coerceIn(MIN, MAX) ?: DEFAULT
}
