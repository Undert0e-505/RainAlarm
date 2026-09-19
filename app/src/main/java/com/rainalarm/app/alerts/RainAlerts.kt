package com.rainalarm.app.alerts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.rainalarm.app.MainActivity
import com.rainalarm.app.R
import com.rainalarm.app.data.PlacePreferences
import com.rainalarm.app.data.RadarLoadMode
import com.rainalarm.app.data.RadarSessionLoader
import com.rainalarm.app.data.RadarProviderCoordinator
import com.rainalarm.app.data.RadarProviderKind
import com.rainalarm.app.data.RadarSettingsRepository
import com.rainalarm.app.data.SavedPlace
import com.rainalarm.app.data.CURRENT_LOCATION_ID
import com.rainalarm.app.data.ForegroundLocationSnapshot
import com.rainalarm.app.data.forecastSelectionKey
import com.rainalarm.app.domain.IntensityGrid
import com.rainalarm.app.domain.PhysicalRadarMotion
import com.rainalarm.app.domain.RadarMotionPolicy
import com.rainalarm.app.domain.RadarResolutionTier
import com.rainalarm.app.domain.RadarPointTimeline
import com.rainalarm.app.domain.RadarVelocityField
import com.rainalarm.app.domain.DenseRadarAdvection
import com.rainalarm.app.domain.OpenMinuteSeriesBuilder
import com.rainalarm.app.domain.ProviderMinuteSeriesBuilder
import com.rainalarm.app.domain.RainMinuteSeries
import com.rainalarm.app.domain.RainMinuteSeriesAnalyzer
import com.rainalarm.app.data.RegionalRainChartService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import java.util.concurrent.TimeUnit

private const val PREFS = "rain_alerts"
private const val UNIQUE_PERIODIC = "rain-approaching-periodic"
private const val UNIQUE_IMMEDIATE = "rain-approaching-immediate"
// A new channel lets existing installs receive an audible default-priority alert; Android
// does not permit changing the importance of an already-created low-priority channel.
private const val CHANNEL_ID = "rain_approaching_v2"
private const val LEGACY_COOLDOWN_SECONDS = 2 * 60 * 60L
private const val FORECAST_END_GRACE_SECONDS = 10 * 60L

data class AlertSnapshot(
    val enabled: Boolean = false,
    val lastCheckedEpochSeconds: Long = 0,
    val status: String = "Off",
)

sealed interface RadarAlertEvaluation {
    data class Approaching(
        val etaStartMinutes: Int,
        val etaEndMinutes: Int,
        val frameIdentity: Long,
        val confirmedDurationMinutes: Int? = null,
        val confirmedPeakIntensity: Float? = null,
        val expectedStartEpochSeconds: Long? = null,
    ) : RadarAlertEvaluation
    data object WetNow : RadarAlertEvaluation
    data object Clear : RadarAlertEvaluation
    data object Unknown : RadarAlertEvaluation
}

data class AlertMemory(
    val lastNotifiedEpochSeconds: Long = 0,
    val lastEventIdentity: Long = 0,
    val suppressedUntilEpochSeconds: Long = 0,
)

data class AlertDecision(
    val shouldNotify: Boolean,
    val nextMemory: AlertMemory,
)

object RainAlertDecisionEngine {
    fun isEligible(place: SavedPlace): Boolean = place.id.isNotBlank()

    fun evaluateField(
        latest: IntensityGrid,
        motion: PhysicalRadarMotion?,
        frameIdentity: Long,
        minimumConfidence: Double = 0.45,
    ): RadarAlertEvaluation {
        val centerX = (latest.width - 1) / 2.0
        val centerY = (latest.height - 1) / 2.0
        val current = latest.wetFractionAround(centerX, centerY) ?: return RadarAlertEvaluation.Unknown
        if (current >= 0.12) return RadarAlertEvaluation.WetNow
        val reliableMotion = motion?.takeIf {
            RadarMotionPolicy.usable(it) && it.confidence >= minimumConfidence
        }
        if (reliableMotion == null) {
            return if (latest.values.none { it >= 0.16f }) {
                RadarAlertEvaluation.Clear
            } else {
                RadarAlertEvaluation.Unknown
            }
        }
        val (detailDxPixelsPerMinute, detailDyPixelsPerMinute) =
            reliableMotion.pixelsPerMinute(RadarResolutionTier.DETAIL)
        val dxPixelsPerMinute = detailDxPixelsPerMinute *
            latest.width / RadarResolutionTier.DETAIL.imageSize
        val dyPixelsPerMinute = detailDyPixelsPerMinute *
            latest.height / RadarResolutionTier.DETAIL.imageSize
        var first: Int? = null
        var last: Int? = null
        for (minute in 1..60) {
            val sourceX = centerX - dxPixelsPerMinute * minute
            val sourceY = centerY - dyPixelsPerMinute * minute
            val wet = latest.wetFractionAround(sourceX, sourceY)
                ?: return RadarAlertEvaluation.Unknown
            if (wet >= 0.12) {
                if (first == null) first = minute
                last = minute
            } else if (first != null) {
                break
            }
        }
        return if (first == null) RadarAlertEvaluation.Clear
        else RadarAlertEvaluation.Approaching(first, last ?: first, frameIdentity)
    }

    fun evaluateDenseField(
        latest: IntensityGrid,
        field: RadarVelocityField?,
        frameIdentity: Long,
        minimumConfidence: Double = 0.18,
    ): RadarAlertEvaluation {
        val centerX = (latest.width - 1) / 2.0
        val centerY = (latest.height - 1) / 2.0
        val current = latest.wetFractionAround(centerX, centerY) ?: return RadarAlertEvaluation.Unknown
        if (current >= 0.12) return RadarAlertEvaluation.WetNow
        val reliable = field?.takeIf { it.confidence >= minimumConfidence }
            ?: return if (latest.values.none { it >= 0.16f }) RadarAlertEvaluation.Clear else RadarAlertEvaluation.Unknown
        var first: Int? = null
        var last: Int? = null
        for (minute in 1..60) {
            val source = DenseRadarAdvection.sourcePoint(
                0.5,
                0.5,
                minute.toDouble(),
                reliable,
                latest.width,
                latest.height,
            )
            val wet = latest.wetFractionAround(source.first, source.second)
                ?: return RadarAlertEvaluation.Unknown
            if (wet >= 0.12) {
                if (first == null) first = minute
                last = minute
            } else if (first != null) break
        }
        return if (first == null) RadarAlertEvaluation.Clear
        else RadarAlertEvaluation.Approaching(first, last ?: first, frameIdentity)
    }

    fun evaluateProviderForecast(
        session: com.rainalarm.app.data.RadarSession,
        place: SavedPlace = session.place,
        nowEpochSeconds: Long = Instant.now().epochSecond,
    ): RadarAlertEvaluation {
        val archive = session.legacyArchive ?: return RadarAlertEvaluation.Unknown
        return evaluateMinuteSeries(
            ProviderMinuteSeriesBuilder.fromSamples(
                archive.pointSamples, "MeteoGroup provider forecast", nowEpochSeconds,
            ),
        )
    }

    fun evaluateMinuteSeries(series: RainMinuteSeries): RadarAlertEvaluation {
        val analysis = RainMinuteSeriesAnalyzer.analyze(series) ?: return RadarAlertEvaluation.Unknown
        if (analysis.rainingNow) return RadarAlertEvaluation.WetNow
        val first = analysis.arrivalMinute ?: return if (
            series.availability == com.rainalarm.app.domain.RainMinuteAvailability.PARTIAL
        ) RadarAlertEvaluation.Unknown else RadarAlertEvaluation.Clear
        return RadarAlertEvaluation.Approaching(
            first,
            analysis.endMinute?.minus(1)?.coerceAtLeast(first) ?: series.points.last().minute,
            series.startEpochSeconds,
            confirmedDurationMinutes = analysis.endMinute?.minus(first),
            confirmedPeakIntensity = analysis.endMinute?.let { end ->
                series.points.subList(first, end).maxOfOrNull { it.average }
            },
            expectedStartEpochSeconds = series.startEpochSeconds + first * 60L,
        )
    }

    fun evaluateProviderTimeline(timeline: RadarPointTimeline): RadarAlertEvaluation {
        if (timeline.currentlyWet) return RadarAlertEvaluation.WetNow
        val first = timeline.wetForecastMinutes.minOrNull() ?: return RadarAlertEvaluation.Clear
        val orderedWet = timeline.wetForecastMinutes.sorted()
        var last = first
        for (minute in orderedWet.dropWhile { it < first }) {
            if (minute - last > 6) break
            last = minute
        }
        return RadarAlertEvaluation.Approaching(first, last, timeline.latestObservationEpochSeconds)
    }

    fun decide(
        evaluation: RadarAlertEvaluation,
        memory: AlertMemory,
        nowEpochSeconds: Long,
        endGraceSeconds: Long = FORECAST_END_GRACE_SECONDS,
    ): AlertDecision = when (evaluation) {
        is RadarAlertEvaluation.Approaching -> {
            val notify = nowEpochSeconds >= memory.suppressedUntilEpochSeconds
            AlertDecision(
                shouldNotify = notify,
                nextMemory = if (notify) {
                    AlertMemory(
                        lastNotifiedEpochSeconds = nowEpochSeconds,
                        lastEventIdentity = evaluation.frameIdentity,
                        suppressedUntilEpochSeconds = suppressionUntil(
                            evaluation, nowEpochSeconds, endGraceSeconds,
                        ),
                    )
                } else {
                    // A moving forecast must not extend the interval accepted by the user.
                    memory.copy(lastEventIdentity = evaluation.frameIdentity)
                },
            )
        }
        RadarAlertEvaluation.Clear -> AlertDecision(false, memory)
        RadarAlertEvaluation.WetNow, RadarAlertEvaluation.Unknown -> AlertDecision(false, memory)
    }

    /** etaEndMinutes is the final inclusive wet minute, so expiry starts one minute later. */
    internal fun suppressionUntil(
        evaluation: RadarAlertEvaluation.Approaching,
        evaluatedAtEpochSeconds: Long,
        endGraceSeconds: Long = FORECAST_END_GRACE_SECONDS,
    ): Long {
        val forecastBase = evaluation.expectedStartEpochSeconds
            ?.minus(evaluation.etaStartMinutes * 60L)
            ?: evaluatedAtEpochSeconds
        val inclusiveEnd = evaluation.etaEndMinutes.coerceAtLeast(evaluation.etaStartMinutes)
        return forecastBase + (inclusiveEnd + 1L) * 60L + endGraceSeconds
    }
}

internal object RainAlertMemoryKeys {
    fun armed(placeId: String) = "armed_$placeId"
    fun lastNotified(placeId: String) = "last_notified_$placeId"
    fun lastEvent(placeId: String) = "last_event_$placeId"
    fun suppressedUntil(placeId: String) = "suppressed_until_$placeId"
    fun all(placeId: String) = setOf(
        armed(placeId), lastNotified(placeId), lastEvent(placeId), suppressedUntil(placeId),
    )

    fun forDeletedSavedPlace(placeId: String): Set<String> =
        if (placeId == CURRENT_LOCATION_ID) emptySet() else all(placeId)
}

internal object RainAlertMemoryMigration {
    fun suppressionUntil(storedSuppression: Long?, lastNotified: Long): Long =
        storedSuppression ?: if (lastNotified > 0) lastNotified + LEGACY_COOLDOWN_SECONDS else 0
}

internal object RainAlertNotificationIdentity {
    /** Stable per place: a later accepted event replaces the old card but can alert again. */
    fun forPlace(placeId: String): Int =
        (4107 xor placeId.hashCode()).and(Int.MAX_VALUE).coerceAtLeast(1)
}

internal object RainAlertNotificationText {
    data class Content(val title: String, val summary: String, val detail: String)

    fun forApproaching(
        placeName: String,
        approaching: RadarAlertEvaluation.Approaching,
        evaluatedAtEpochSeconds: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Content {
        val start = approaching.expectedStartEpochSeconds
            ?: evaluatedAtEpochSeconds + approaching.etaStartMinutes * 60L
        val clock = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
            .withZone(zone).format(Instant.ofEpochSecond(start))
        val title = "Rain approaching $placeName"
        val summary = "In ${approaching.etaStartMinutes} min · around $clock"
        val confirmed = approaching.confirmedDurationMinutes?.takeIf { it > 0 }
        val peak = approaching.confirmedPeakIntensity?.takeIf { it.isFinite() && it in 0f..1f }
        val detail = if (confirmed != null && peak != null) {
            "$summary · about $confirmed min; peak intensity ${(peak * 100).roundToInt()}%"
        } else summary
        return Content(title, summary, detail)
    }
}

object RainAlertSelectionGuard {
    fun stillSelected(expectedId: String, expectedPlace: SavedPlace, currentId: String,
                      currentPlace: SavedPlace?): Boolean =
        expectedId == currentId && currentPlace != null &&
            forecastSelectionKey(expectedPlace) == forecastSelectionKey(currentPlace)
}

class RainAlertPreferences(private val context: Context) {
    private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hasExplicitEnabledChoice(): Boolean = preferences.contains("enabled")

    val snapshots: Flow<AlertSnapshot> = callbackFlow {
        fun emit() { trySend(snapshot()) }
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> emit() }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        emit()
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun snapshot(): AlertSnapshot = AlertSnapshot(
        enabled = preferences.getBoolean("enabled", false),
        lastCheckedEpochSeconds = preferences.getLong("last_checked", 0),
        status = preferences.getString("status", "Off") ?: "Off",
    )

    fun setEnabled(enabled: Boolean) {
        preferences.edit {
            putBoolean("enabled", enabled)
            putString("status", if (enabled) "Scheduled" else "Off")
        }
    }

    fun markPermissionNeeded() {
        preferences.edit {
            putBoolean("enabled", false)
            putString("status", "Notification permission needed")
        }
    }

    fun memory(placeId: String): AlertMemory {
        val lastNotified = preferences.getLong(RainAlertMemoryKeys.lastNotified(placeId), 0)
        val suppressionKey = RainAlertMemoryKeys.suppressedUntil(placeId)
        return AlertMemory(
            lastNotifiedEpochSeconds = lastNotified,
            lastEventIdentity = preferences.getLong(RainAlertMemoryKeys.lastEvent(placeId), 0),
            suppressedUntilEpochSeconds = RainAlertMemoryMigration.suppressionUntil(
                preferences.getLong(suppressionKey, 0).takeIf { preferences.contains(suppressionKey) },
                lastNotified,
            ),
        )
    }

    fun record(
        nowEpochSeconds: Long,
        status: String,
        placeId: String? = null,
        memory: AlertMemory? = null,
    ) {
        preferences.edit {
            putLong("last_checked", nowEpochSeconds)
            putString("status", status)
            if (placeId != null && memory != null) {
                remove(RainAlertMemoryKeys.armed(placeId))
                putLong(RainAlertMemoryKeys.lastNotified(placeId), memory.lastNotifiedEpochSeconds)
                putLong(RainAlertMemoryKeys.lastEvent(placeId), memory.lastEventIdentity)
                putLong(RainAlertMemoryKeys.suppressedUntil(placeId), memory.suppressedUntilEpochSeconds)
            }
        }
    }

    fun clearDeletedSavedPlace(placeId: String) {
        val keys = RainAlertMemoryKeys.forDeletedSavedPlace(placeId)
        if (keys.isEmpty()) return
        preferences.edit { keys.forEach { key -> remove(key) } }
        runCatching {
            NotificationManagerCompat.from(context).cancel(RainAlertNotificationIdentity.forPlace(placeId))
        }
    }
}

class RainAlertScheduler(private val context: Context) {
    private val work = WorkManager.getInstance(context)
    private val preferences = RainAlertPreferences(context)

    /** Restore an enabled choice or activate the unset first-run default without resetting its cadence. */
    fun ensureScheduledOnStartup() {
        if (!preferences.hasExplicitEnabledChoice()) preferences.setEnabled(true)
        if (!preferences.snapshot().enabled) return
        createNotificationChannel(context)
        val periodic = PeriodicWorkRequestBuilder<RainApproachingWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        work.enqueueUniquePeriodicWork(UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, periodic)
        enqueueImmediate()
    }

    fun enable() {
        createNotificationChannel(context)
        preferences.setEnabled(true)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val periodic = PeriodicWorkRequestBuilder<RainApproachingWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        work.enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodic,
        )
        enqueueImmediate()
    }

    fun disable() {
        preferences.setEnabled(false)
        work.cancelUniqueWork(UNIQUE_PERIODIC)
        work.cancelUniqueWork(UNIQUE_IMMEDIATE)
    }

    fun enqueueImmediate() {
        if (!preferences.snapshot().enabled) return
        val request = OneTimeWorkRequestBuilder<RainApproachingWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .build()
        work.enqueueUniqueWork(UNIQUE_IMMEDIATE, ExistingWorkPolicy.REPLACE, request)
    }
}

class RainApproachingWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result = workerMutex.withLock { doWorkSerially() }

    private suspend fun doWorkSerially(): Result {
        val alertPreferences = RainAlertPreferences(applicationContext)
        if (!alertPreferences.snapshot().enabled) return Result.success()
        val now = Instant.now()
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            alertPreferences.setEnabled(false)
            alertPreferences.record(now.epochSecond, "Off: notification permission is unavailable")
            return Result.success()
        }
        val placePreferences = PlacePreferences(applicationContext)
        val originalCollection = placePreferences.collection.first()
        val selectedId = originalCollection.selectedId
        val place = if (selectedId == CURRENT_LOCATION_ID) ForegroundLocationSnapshot.freshPlace()
        else originalCollection.selected
        if (place == null) {
            alertPreferences.record(now.epochSecond,
                "Unavailable: current location needs a fresh foreground fix; saved places work in background")
            return Result.success()
        }
        var session: com.rainalarm.app.data.RadarSession? = null
        return try {
            session = RadarProviderCoordinator(
                RadarSettingsRepository(applicationContext),
            ).load(
                place,
                maxFrames = 5,
                mode = RadarLoadMode.ALERT_ANALYSIS,
            )
            val evaluatedAt = Instant.now()
            val evaluation = if (session.providerSelection.active == RadarProviderKind.METEOGROUP_REGIONAL) {
                val rasterFallback = ProviderMinuteSeriesBuilder.fromSamples(
                    requireNotNull(session.legacyArchive).pointSamples,
                    "MeteoGroup provider forecast",
                    evaluatedAt.epochSecond,
                )
                val regionalSeries = RegionalRainChartService().preferChart(
                    place, evaluatedAt.epochSecond, rasterFallback,
                )
                RainAlertDecisionEngine.evaluateMinuteSeries(regionalSeries)
            } else {
                val preferredTier = if (session.detail != null) {
                    RadarResolutionTier.DETAIL
                } else {
                    RadarResolutionTier.REGIONAL
                }
                RainAlertDecisionEngine.evaluateMinuteSeries(
                    OpenMinuteSeriesBuilder.fromDenseField(
                        grid = requireNotNull(session.latestDetailIntensity) {
                            "Alert detail frame has no intensity grid"
                        },
                        field = session.velocity(preferredTier)?.futureField,
                        startEpochSeconds = session.frames.last().frame.time,
                        sourceLabel = "RainViewer radar estimate",
                        nowEpochSeconds = evaluatedAt.epochSecond,
                    ),
                )
            }
            val latestCollection = placePreferences.collection.first()
            val latestPlace = if (latestCollection.selectedId == CURRENT_LOCATION_ID) {
                ForegroundLocationSnapshot.freshPlace()
            } else latestCollection.selected
            val stillSelected = RainAlertSelectionGuard.stillSelected(
                selectedId, place, latestCollection.selectedId, latestPlace,
            )
            if (!stillSelected || !alertPreferences.snapshot().enabled) return Result.success()
            val decision = RainAlertDecisionEngine.decide(
                evaluation,
                alertPreferences.memory(selectedId),
                evaluatedAt.epochSecond,
            )
            val status = when (evaluation) {
                is RadarAlertEvaluation.Approaching -> {
                    if (decision.shouldNotify) {
                        val beforeNotify = placePreferences.collection.first()
                        val beforePlace = if (beforeNotify.selectedId == CURRENT_LOCATION_ID)
                            ForegroundLocationSnapshot.freshPlace() else beforeNotify.selected
                        if (!RainAlertSelectionGuard.stillSelected(selectedId, place, beforeNotify.selectedId, beforePlace) ||
                            !alertPreferences.snapshot().enabled) return Result.success()
                        notifyApproaching(place, evaluation, evaluatedAt.epochSecond)
                    }
                    "${place.name}: rain may start in ${evaluation.etaStartMinutes} min"
                }
                RadarAlertEvaluation.WetNow -> "Rain detected at ${place.name} now"
                RadarAlertEvaluation.Clear -> "${place.name}: dry; no confident rain in the next 60 min"
                RadarAlertEvaluation.Unknown -> "${place.name}: no confident radar nowcast"
            }
            alertPreferences.record(evaluatedAt.epochSecond, status, selectedId, decision.nextMemory)
            Result.success()
        } catch (_: IOException) {
            if (placePreferences.collection.first().selectedId == selectedId)
                alertPreferences.record(now.epochSecond, "Unavailable: network error")
            Result.retry()
        } catch (_: Exception) {
            if (placePreferences.collection.first().selectedId == selectedId)
                alertPreferences.record(now.epochSecond, "Unavailable: radar could not be analysed")
            Result.success()
        } finally {
            session?.release()
        }
    }

    private fun notifyApproaching(place: SavedPlace, approaching: RadarAlertEvaluation.Approaching, evaluatedAt: Long) {
        createNotificationChannel(applicationContext)
        val content = RainAlertNotificationText.forApproaching(place.name, approaching, evaluatedAt)
        val intent = Intent(applicationContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(content.title)
            .setContentText(content.summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.detail))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                NotificationManagerCompat.from(applicationContext)
                    .notify(
                        RainAlertNotificationIdentity.forPlace(place.id),
                        notification,
                    )
            } catch (_: SecurityException) {
                RainAlertScheduler(applicationContext).disable()
            }
        }
    }

    private companion object {
        // Immediate and periodic work have different unique names and can otherwise overlap.
        val workerMutex = Mutex()
    }
}

fun createNotificationChannel(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java)
    val channel = NotificationChannel(
        CHANNEL_ID,
        "Rain approaching",
        NotificationManager.IMPORTANCE_DEFAULT,
    ).apply {
        description = "Low-noise alerts when radar motion suggests rain is approaching"
    }
    manager.createNotificationChannel(channel)
}
