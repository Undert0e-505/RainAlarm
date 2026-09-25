package com.rainalarm.app.ui

import android.content.Context
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

internal enum class RadarRendererStage {
    EGL_CREATE,
    PROGRAM_LINK,
    MESH_READY,
    RADAR_UPLOAD,
    VELOCITY_UPLOAD,
    FIRST_DRAW,
    SWAP,
}

internal interface RadarRendererStageStore {
    fun read(): String?
    fun write(value: String?)
}

internal data class RadarRendererStageRecord(
    val processToken: String,
    val ownerToken: String,
    val stage: RadarRendererStage,
) {
    fun encode(): String = listOf(FORMAT_VERSION, processToken, ownerToken, stage.name).joinToString(SEPARATOR)

    companion object {
        private const val FORMAT_VERSION = "2"
        private const val SEPARATOR = "|"

        fun decode(value: String?): RadarRendererStageRecord? {
            val parts = value?.split(SEPARATOR) ?: return null
            if (parts.size != 4 || parts[0] != FORMAT_VERSION || parts[1].isBlank() || parts[2].isBlank()) {
                return null
            }
            val stage = RadarRendererStage.entries.firstOrNull { it.name == parts[3] } ?: return null
            return RadarRendererStageRecord(parts[1], parts[2], stage)
        }
    }
}

private object RadarRendererProcessIdentity {
    val token: String = UUID.randomUUID().toString()
    val lock = Any()
    private val nextOwner = AtomicLong()

    fun ownerToken(): String = nextOwner.incrementAndGet().toString()
}

internal class RadarRendererStageGuard(
    private val store: RadarRendererStageStore,
    private val processToken: String = RadarRendererProcessIdentity.token,
    private val ownerToken: String = RadarRendererProcessIdentity.ownerToken(),
) {
    val interruptedStage: RadarRendererStage? = synchronized(RadarRendererProcessIdentity.lock) {
        val stored = store.read()
        val record = RadarRendererStageRecord.decode(stored)
        when {
            record == null -> {
                // Version-one breadcrumbs did not identify their renderer and routinely mistook a
                // normal overlapping session handoff for a process interruption. Discard them.
                if (stored != null) store.write(null)
                null
            }
            record.processToken == processToken -> null
            else -> record.stage
        }
    }

    val compatibilityMode: Boolean get() = interruptedStage != null

    @Volatile private var acceptingMarks = true

    fun mark(stage: RadarRendererStage) = synchronized(RadarRendererProcessIdentity.lock) {
        if (acceptingMarks) store.write(RadarRendererStageRecord(processToken, ownerToken, stage).encode())
    }

    fun clear() = synchronized(RadarRendererProcessIdentity.lock) {
        val active = RadarRendererStageRecord.decode(store.read())
        if (active?.processToken == processToken && active.ownerToken == ownerToken) store.write(null)
    }

    /** Prevent queued work from recreating a breadcrumb after an expected session handoff. */
    fun finishExpected() {
        acceptingMarks = false
        clear()
    }
}

internal object RadarRendererRecoveryPolicy {
    private const val RECOVERY_MESSAGE = "Radar display recovered in compatibility mode."

    fun status(interruptedStage: RadarRendererStage?): RadarRendererStatus =
        if (interruptedStage == null) RadarRendererStatus.Ready
        else RadarRendererStatus.Compatibility(RECOVERY_MESSAGE)
}

internal class SharedPreferencesRadarRendererStageStore(context: Context) : RadarRendererStageStore {
    private val preferences = context.applicationContext.getSharedPreferences("radar_renderer_safety", Context.MODE_PRIVATE)

    override fun read(): String? = preferences.getString(KEY, null)

    // Synchronous persistence is intentional: this breadcrumb must reach disk before a
    // vendor GL process abort can terminate the app between two renderer stages.
    @android.annotation.SuppressLint("ApplySharedPref", "UseKtx")
    override fun write(value: String?) {
        val editor = preferences.edit()
        if (value == null) editor.remove(KEY) else editor.putString(KEY, value)
        editor.commit()
    }

    private companion object {
        const val KEY = "meteo_interrupted_stage"
    }
}

internal data class LegacyTextureLayout(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val backingWidth: Int,
    val backingHeight: Int,
    val offsetX: Float,
    val offsetY: Float,
    val scaleX: Float,
    val scaleY: Float,
) {
    companion object {
        fun plan(width: Int, height: Int, forcePowerOfTwo: Boolean): LegacyTextureLayout {
            require(width > 0 && height > 0)
            val backingWidth = if (forcePowerOfTwo) nextPowerOfTwo(width) else width
            val backingHeight = if (forcePowerOfTwo) nextPowerOfTwo(height) else height
            return LegacyTextureLayout(
                width,
                height,
                backingWidth,
                backingHeight,
                0.5f / backingWidth,
                0.5f / backingHeight,
                (width - 1).coerceAtLeast(0).toFloat() / backingWidth,
                (height - 1).coerceAtLeast(0).toFloat() / backingHeight,
            )
        }

        private fun nextPowerOfTwo(value: Int): Int {
            var result = 1
            while (result < value) {
                require(result <= (1 shl 29)) { "Texture dimension is too large" }
                result = result shl 1
            }
            return result
        }
    }
}

/** Access-order pair policy. Eviction is returned before the caller allocates a replacement. */
internal class LegacyPairResidency(private val maxPairs: Int) {
    private val frames = LinkedHashSet<Int>()

    init {
        require(maxPairs in 1..3)
    }

    fun beforeAccess(frameId: Int): Int? {
        if (frames.remove(frameId)) {
            frames.add(frameId)
            return null
        }
        val evicted = if (frames.size >= maxPairs) frames.first().also { frames.remove(it) } else null
        frames.add(frameId)
        return evicted
    }

    fun remove(frameId: Int) {
        frames.remove(frameId)
    }

    fun snapshot(): Set<Int> = frames.toSet()
}

internal object LegacyFrameRequestPlan {
    fun forDraw(firstIndex: Int, secondIndex: Int): List<Int> =
        if (firstIndex == secondIndex) listOf(firstIndex) else listOf(firstIndex, secondIndex)
}

internal object LegacyTextureRowCodec {
    fun encode(pixels: IntArray, velocity: Boolean, target: ByteBuffer, pixelCount: Int = pixels.size) {
        require(pixelCount in 0..pixels.size)
        val needed = pixelCount * if (velocity) 2 else 1
        require(target.capacity() >= needed)
        target.clear()
        for (index in 0 until pixelCount) {
            val color = pixels[index]
            target.put(((color shr 16) and 0xff).toByte())
            if (velocity) target.put(((color shr 8) and 0xff).toByte())
        }
        target.position(0)
        target.limit(needed)
    }
}

internal object LegacyTextureStripPlan {
    const val ROWS = 16
    const val UNPACK_ALIGNMENT = 1

    fun rowsAt(startRow: Int, imageHeight: Int): Int {
        require(startRow in 0 until imageHeight)
        return minOf(ROWS, imageHeight - startRow)
    }
}

internal object RadarStaticFallbackPolicy {
    fun shouldShow(hasLegacyArchive: Boolean, status: RadarRendererStatus): Boolean =
        hasLegacyArchive && status is RadarRendererStatus.Error
}
