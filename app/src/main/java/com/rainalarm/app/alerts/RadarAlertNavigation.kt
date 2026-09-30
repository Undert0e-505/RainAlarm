package com.rainalarm.app.alerts

import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import com.rainalarm.app.data.RadarMapLayer
import com.rainalarm.app.data.SavedPlace
import com.rainalarm.app.widget.FrozenMonitorTarget
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

object RadarAlertDeepLink {
    const val ACTION = "com.rainalarm.app.action.OPEN_MONITORED_RADAR"
    const val EXTRA_TARGET_ID = "monitor_target_id"
    const val EXTRA_TARGET_NAME = "monitor_target_name"
    const val EXTRA_TARGET_LATITUDE = "monitor_target_latitude"
    const val EXTRA_TARGET_LONGITUDE = "monitor_target_longitude"
    const val EXTRA_SHOW_LIGHTNING_TEMPORARILY = "show_lightning_temporarily"

    fun put(intent: Intent, target: FrozenMonitorTarget, showLightningTemporarily: Boolean): Intent =
        intent.setAction(ACTION)
            .putExtra(EXTRA_TARGET_ID, target.stableId)
            .putExtra(EXTRA_TARGET_NAME, target.displayName)
            .putExtra(EXTRA_TARGET_LATITUDE, target.latitude)
            .putExtra(EXTRA_TARGET_LONGITUDE, target.longitude)
            .putExtra(EXTRA_SHOW_LIGHTNING_TEMPORARILY, showLightningTemporarily)

    fun parse(intent: Intent?): RadarAlertNavigationRequest? {
        if (intent?.action != ACTION) return null
        val id = intent.getStringExtra(EXTRA_TARGET_ID)?.takeIf(String::isNotBlank) ?: return null
        val name = intent.getStringExtra(EXTRA_TARGET_NAME)?.takeIf(String::isNotBlank) ?: return null
        if (!intent.hasExtra(EXTRA_TARGET_LATITUDE) || !intent.hasExtra(EXTRA_TARGET_LONGITUDE)) return null
        val latitude = intent.getDoubleExtra(EXTRA_TARGET_LATITUDE, Double.NaN)
        val longitude = intent.getDoubleExtra(EXTRA_TARGET_LONGITUDE, Double.NaN)
        val place = runCatching { SavedPlace(name, latitude, longitude, id = id) }.getOrNull() ?: return null
        return RadarAlertNavigationRequest(
            place,
            intent.getBooleanExtra(EXTRA_SHOW_LIGHTNING_TEMPORARILY, false),
        )
    }
}

data class RadarAlertNavigationRequest(
    val place: SavedPlace,
    val temporaryLightning: Boolean,
)

@Serializable
data class TemporaryLightningLease(
    val targetId: String,
    val targetName: String,
    val latitude: Double,
    val longitude: Double,
    val expiresAtEpochSeconds: Long,
    val noticePending: Boolean = true,
) {
    fun target(): SavedPlace? = runCatching {
        SavedPlace(targetName, latitude, longitude, id = targetId)
    }.getOrNull()
}

enum class RadarLightningControlState { OFF, TEMPORARY, ON }

object RadarLightningControlPolicy {
    fun state(
        persistentEnabled: Boolean,
        lease: TemporaryLightningLease?,
        displayedPlace: SavedPlace?,
        nowEpochSeconds: Long,
    ): RadarLightningControlState {
        if (persistentEnabled) return RadarLightningControlState.ON
        val target = lease?.target()
        val matches = target != null && displayedPlace != null &&
            target.id == displayedPlace.id && target.latitude == displayedPlace.latitude &&
            target.longitude == displayedPlace.longitude && lease.expiresAtEpochSeconds > nowEpochSeconds
        return if (matches) RadarLightningControlState.TEMPORARY else RadarLightningControlState.OFF
    }

    fun persistentAfterTap(state: RadarLightningControlState): Boolean = when (state) {
        RadarLightningControlState.OFF, RadarLightningControlState.TEMPORARY -> true
        RadarLightningControlState.ON -> false
    }
}

class TemporaryLightningLeaseStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun begin(target: SavedPlace, nowEpochSeconds: Long = Instant.now().epochSecond): TemporaryLightningLease {
        val lease = TemporaryLightningLease(
            target.id,
            target.name,
            target.latitude,
            target.longitude,
            nowEpochSeconds + DURATION_SECONDS,
        )
        preferences.edit(commit = true) { putString(KEY_LEASE, json.encodeToString(lease)) }
        return lease
    }

    fun current(nowEpochSeconds: Long = Instant.now().epochSecond): TemporaryLightningLease? {
        val lease = preferences.getString(KEY_LEASE, null)?.let { raw ->
            runCatching { json.decodeFromString<TemporaryLightningLease>(raw) }.getOrNull()
        }
        if (lease == null || lease.expiresAtEpochSeconds <= nowEpochSeconds) {
            if (lease != null) clear()
            return null
        }
        return lease
    }

    fun consumeNotice(): TemporaryLightningLease? {
        val lease = current() ?: return null
        if (!lease.noticePending) return lease
        val updated = lease.copy(noticePending = false)
        preferences.edit(commit = true) { putString(KEY_LEASE, json.encodeToString(updated)) }
        return lease
    }

    fun cancel() {
        preferences.edit(commit = true) { remove(KEY_LEASE) }
    }

    fun clear() = cancel()

    private companion object {
        const val PREFS = "temporary_lightning_lease"
        const val KEY_LEASE = "lease"
        const val DURATION_SECONDS = 10 * 60L
    }
}
