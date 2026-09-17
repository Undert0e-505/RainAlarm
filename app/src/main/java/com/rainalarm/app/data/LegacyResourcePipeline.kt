package com.rainalarm.app.data

/** Forecast/current-first transfer order; the archive itself remains chronological. */
internal object LegacyFramePriority {
    fun ordered(frames: List<RegionalManifestFrame>): List<RegionalManifestFrame> {
        val now = frames.indexOfLast { !it.forecast }.coerceAtLeast(0)
        return buildList(frames.size) {
            add(frames[now])
            addAll(frames.drop(now + 1))
            addAll(frames.take(now).asReversed())
        }.distinctBy { it.timestamp }
    }

    fun boundedBatches(
        frames: List<RegionalManifestFrame>,
        concurrency: Int = 2,
    ): List<List<RegionalManifestFrame>> {
        require(concurrency in 1..4)
        return ordered(frames).chunked(concurrency)
    }
}

/** Guarantees release after upload, including exceptions such as cancellation and allocation failure. */
internal inline fun <T, R> consumeAndRelease(
    resource: T,
    release: (T) -> Unit,
    consume: (T) -> R,
): R = try {
    consume(resource)
} finally {
    release(resource)
}
