package com.rainalarm.app.data

/** Scale relative to the established 44 px wind glyph; invalid stored values use 1x. */
object WindArrowSizePreference {
    const val DEFAULT = 1f
    const val MIN = 0.5f
    const val MAX = 2f

    fun decode(value: Float?): Float = value?.takeIf { it.isFinite() }?.coerceIn(MIN, MAX) ?: DEFAULT
}
