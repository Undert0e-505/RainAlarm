package com.rainalarm.app

/** Refresh only on a real return to Now, not startup, repeat taps, or recomposition. */
internal object NowEntryRefreshPolicy {
    fun entersNow(previous: Destination?, next: Destination): Boolean =
        previous != null && previous != Destination.NOW && next == Destination.NOW

    fun shouldStart(
        selectionKey: String?,
        activeLoadKey: String?,
        selectionChangePending: Boolean,
    ): Boolean = selectionKey != null && !selectionChangePending && selectionKey != activeLoadKey
}
