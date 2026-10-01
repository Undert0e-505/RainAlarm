package com.rainalarm.app.widget

import android.content.Context
import androidx.core.content.edit
import com.rainalarm.app.data.CURRENT_LOCATION_ID
import com.rainalarm.app.data.PlaceCollection
import kotlinx.serialization.json.Json

class RainAlarmWidgetStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun configurations(): List<RainAlarmWidgetConfig> = synchronized(lock) {
        ids().mapNotNull(::configuration)
    }

    fun configuration(appWidgetId: Int): RainAlarmWidgetConfig? = synchronized(lock) {
        preferences.getString(configKey(appWidgetId), null)?.let { raw ->
            runCatching { json.decodeFromString<RainAlarmWidgetConfig>(raw) }.getOrNull()
                ?.takeIf { it.appWidgetId == appWidgetId }
        }
    }

    /**
     * Idempotently freezes legacy Follow-app/fixed-id configurations to one saved-place snapshot.
     * Widgets with no lawful saved target remain configured in the launcher but explicitly require
     * reconfiguration; they are never pointed at live/current coordinates or another silent target.
     */
    fun migrateLegacy(
        places: PlaceCollection,
        startupDefaultId: String?,
    ): WidgetMigrationResult = synchronized(lock) {
        val migrated = linkedSetOf<Int>()
        val needsConfiguration = linkedSetOf<Int>()
        preferences.edit(commit = true) {
            ids().forEach { id ->
                val current = configuration(id) ?: return@forEach
                if (current.savedPlace?.valid == true) return@forEach
                val target = WidgetLegacyTargetMigrationPolicy.resolve(
                    current, places, startupDefaultId,
                )
                if (target == null) {
                    putString(problemKey(id), WidgetTargetProblem.CONFIGURATION_REQUIRED.name)
                    remove(snapshotKey(id))
                    remove(pendingKey(id))
                    remove(failureCountKey(id))
                    needsConfiguration += id
                } else {
                    val next = current.copy(
                        locationMode = WidgetLocationMode.FIXED,
                        fixedPlaceId = target.stableId,
                        savedPlace = target,
                    )
                    putString(configKey(id), json.encodeToString(next))
                    remove(problemKey(id))
                    remove(snapshotKey(id))
                    putBoolean(pendingKey(id), true)
                    remove(failureCountKey(id))
                    migrated += id
                }
            }
        }
        WidgetMigrationResult(migrated, needsConfiguration)
    }

    /** Atomically registers the subscription and its durable initial acquisition demand. */
    fun save(configuration: RainAlarmWidgetConfig): Boolean = synchronized(lock) {
        require(configuration.valid) { "Widget configuration is invalid" }
        val prior = configuration(configuration.appWidgetId)
        val targetChoiceChanged = prior != null && prior.savedPlace != configuration.savedPlace
        val acquisitionChanged = prior == null || targetChoiceChanged ||
            prior.includeLightningNotifications != configuration.includeLightningNotifications ||
            prior.notificationsEnabled != configuration.notificationsEnabled
        val ids = ids().toMutableSet().apply { add(configuration.appWidgetId) }
        preferences.edit(commit = true) {
            putString(KEY_IDS, ids.sorted().joinToString(","))
            putString(configKey(configuration.appWidgetId), json.encodeToString(configuration))
            remove(problemKey(configuration.appWidgetId))
            if (targetChoiceChanged) remove(snapshotKey(configuration.appWidgetId))
            if (acquisitionChanged) {
                putBoolean(pendingKey(configuration.appWidgetId), true)
                remove(failureCountKey(configuration.appWidgetId))
            }
        }
        WidgetRefreshTrace.configSaved(configuration, acquisitionChanged)
        acquisitionChanged
    }

    fun pendingInitialWidgetIds(): Set<Int> = synchronized(lock) {
        ids().filterTo(linkedSetOf()) { preferences.getBoolean(pendingKey(it), false) }
    }

    fun hasPendingInitialRefresh(): Boolean = pendingInitialWidgetIds().isNotEmpty()

    fun isInitialRefreshPending(appWidgetId: Int): Boolean = synchronized(lock) {
        configuration(appWidgetId) != null && preferences.getBoolean(pendingKey(appWidgetId), false)
    }

    fun completeInitialRefresh(appWidgetIds: Collection<Int>) = synchronized(lock) {
        preferences.edit(commit = true) {
            appWidgetIds.forEach { id ->
                remove(pendingKey(id))
                remove(failureCountKey(id))
            }
        }
    }

    fun recordInitialFailure(appWidgetIds: Collection<Int>): WidgetInitialFailureResult =
        synchronized(lock) {
            val retry = linkedSetOf<Int>()
            val terminal = linkedSetOf<Int>()
            val newCounts = mutableMapOf<Int, Int>()
            appWidgetIds.forEach { id ->
                if (configuration(id) != null && preferences.getBoolean(pendingKey(id), false)) {
                    val count = preferences.getInt(failureCountKey(id), 0) + 1
                    newCounts[id] = count
                    when (WidgetInitialRefreshPolicy.attemptDisposition(false, count)) {
                        WidgetInitialRefreshPolicy.AttemptDisposition.RETRY -> retry += id
                        WidgetInitialRefreshPolicy.AttemptDisposition.TERMINAL_FAILURE -> terminal += id
                        WidgetInitialRefreshPolicy.AttemptDisposition.COMPLETE -> Unit
                    }
                }
            }
            preferences.edit(commit = true) {
                newCounts.forEach { (id, count) ->
                    if (id in terminal) {
                        remove(pendingKey(id))
                        remove(failureCountKey(id))
                    } else {
                        putInt(failureCountKey(id), count)
                    }
                }
            }
            WidgetInitialFailureResult(retry, terminal)
        }

    fun prepare(ids: Collection<Int>, target: FrozenMonitorTarget, generation: Long) = synchronized(lock) {
        preferences.edit(commit = true) {
            ids.forEach { id ->
                val current = snapshot(id)
                if (current != null && current.targetKey != target.key) {
                    remove(snapshotKey(id))
                } else if (current != null) {
                    putString(
                        snapshotKey(id),
                        json.encodeToString(
                            current.copy(
                                updating = true,
                                updateUnavailable = false,
                                generation = generation,
                            ),
                        ),
                    )
                }
            }
        }
    }

    fun snapshot(appWidgetId: Int): WidgetWeatherSnapshot? = synchronized(lock) {
        preferences.getString(snapshotKey(appWidgetId), null)?.let { raw ->
            runCatching { json.decodeFromString<WidgetWeatherSnapshot>(raw) }.getOrNull()
        }
    }

    fun problem(appWidgetId: Int): WidgetTargetProblem? = synchronized(lock) {
        preferences.getString(problemKey(appWidgetId), null)?.let { value ->
            runCatching { WidgetTargetProblem.valueOf(value) }.getOrNull()
        }
    }

    fun markProblem(appWidgetId: Int, problem: WidgetTargetProblem) = synchronized(lock) {
        preferences.edit(commit = true) {
            putString(problemKey(appWidgetId), problem.name)
            remove(snapshotKey(appWidgetId))
            remove(pendingKey(appWidgetId))
            remove(failureCountKey(appWidgetId))
        }
    }

    fun markUpdating(ids: Collection<Int>, generation: Long) = synchronized(lock) {
        preferences.edit(commit = true) {
            ids.forEach { id ->
                snapshot(id)?.let { current ->
                    putString(
                        snapshotKey(id),
                        json.encodeToString(
                            current.copy(
                                updating = true,
                                updateUnavailable = false,
                                generation = generation,
                            ),
                        ),
                    )
                }
            }
        }
    }

    fun cancelUpdating(ids: Collection<Int>, generation: Long) = synchronized(lock) {
        preferences.edit(commit = true) {
            ids.forEach { id ->
                snapshot(id)?.takeIf { it.generation == generation }?.let { current ->
                    putString(snapshotKey(id), json.encodeToString(current.copy(updating = false)))
                }
            }
        }
    }

    fun publish(ids: Collection<Int>, snapshot: WidgetWeatherSnapshot) = synchronized(lock) {
        preferences.edit(commit = true) {
            ids.forEach { id ->
                val current = snapshot(id)
                if (current == null || snapshot.generation >= current.generation) {
                    val coherent = WidgetSnapshotCachePolicy.merge(
                        current,
                        snapshot.copy(updating = false),
                    )
                    putString(snapshotKey(id), json.encodeToString(coherent))
                    remove(problemKey(id))
                }
            }
        }
    }

    fun markUnavailable(ids: Collection<Int>, target: FrozenMonitorTarget?, generation: Long) = synchronized(lock) {
        preferences.edit(commit = true) {
            ids.forEach { id ->
                val current = snapshot(id)
                if (current != null && generation >= current.generation) {
                    // A failed/deferred run is not new weather evidence. Keep the coherent cached
                    // snapshot and its true last-success time; display-time freshness decides when
                    // that data can no longer support a current answer.
                    putString(
                        snapshotKey(id),
                        json.encodeToString(
                            current.copy(
                                updating = false,
                                generation = generation,
                            ),
                        ),
                    )
                } else if (current == null && target != null) {
                    putString(
                        snapshotKey(id),
                        json.encodeToString(
                            WidgetWeatherSnapshot(
                                targetKey = target.key,
                                targetStableId = target.stableId,
                                placeName = target.displayName,
                                latitude = target.latitude,
                                longitude = target.longitude,
                                provider = target.selectedProvider,
                                // An attempt timestamp is not a successful weather update.
                                updatedEpochSeconds = 0L,
                                lastSuccessfulEpochSeconds = 0L,
                                updateUnavailable = true,
                                generation = generation,
                            ),
                        ),
                    )
                }
            }
        }
    }

    fun renameTarget(targetStableId: String, name: String) = synchronized(lock) {
        preferences.edit(commit = true) {
            ids().forEach { id ->
                configuration(id)?.takeIf { it.savedPlace?.stableId == targetStableId }
                    ?.let { config ->
                        putString(
                            configKey(id),
                            json.encodeToString(
                                config.copy(savedPlace = config.savedPlace?.copy(displayName = name)),
                            ),
                        )
                    }
                snapshot(id)?.takeIf { it.targetStableId == targetStableId }?.let { current ->
                    putString(snapshotKey(id), json.encodeToString(current.copy(placeName = name)))
                }
            }
        }
    }

    fun markDeletedFixedTarget(targetStableId: String): List<Int> = synchronized(lock) {
        val affected = configurations().filter {
            it.savedPlace?.stableId == targetStableId
        }.map(RainAlarmWidgetConfig::appWidgetId)
        // A widget owns a frozen target snapshot. Deleting the source saved row must not silently
        // switch it or make it dependent on app state; it remains usable and may be reconfigured.
        affected
    }

    fun delete(appWidgetIds: IntArray) = synchronized(lock) {
        val removed = appWidgetIds.toSet()
        val remaining = ids().filterNot(removed::contains)
        preferences.edit(commit = true) {
            putString(KEY_IDS, remaining.joinToString(","))
            removed.forEach { id ->
                remove(configKey(id))
                remove(snapshotKey(id))
                remove(problemKey(id))
                remove(pendingKey(id))
                remove(failureCountKey(id))
            }
        }
        WidgetRefreshTrace.deleted(removed)
    }

    fun clearSnapshot(appWidgetId: Int) = synchronized(lock) {
        preferences.edit(commit = true) { remove(snapshotKey(appWidgetId)) }
    }

    private fun ids(): List<Int> = preferences.getString(KEY_IDS, null)
        ?.split(',')
        ?.mapNotNull(String::toIntOrNull)
        ?.distinct()
        .orEmpty()

    private companion object {
        const val PREFS = "rain_alarm_widgets"
        const val KEY_IDS = "ids"
        val lock = Any()
        fun configKey(id: Int) = "config_$id"
        fun snapshotKey(id: Int) = "snapshot_$id"
        fun problemKey(id: Int) = "problem_$id"
        fun pendingKey(id: Int) = "pending_initial_$id"
        fun failureCountKey(id: Int) = "initial_failures_$id"
    }
}

data class WidgetMigrationResult(
    val migratedWidgetIds: Set<Int>,
    val configurationRequiredWidgetIds: Set<Int>,
)
