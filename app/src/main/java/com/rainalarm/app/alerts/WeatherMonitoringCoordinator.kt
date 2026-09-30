package com.rainalarm.app.alerts

import android.Manifest
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ListenableWorker
import com.rainalarm.app.MainActivity
import com.rainalarm.app.R
import com.rainalarm.app.data.CURRENT_LOCATION_ID
import com.rainalarm.app.data.ForegroundLocationSnapshot
import com.rainalarm.app.data.PlaceCollection
import com.rainalarm.app.data.PlacePreferences
import com.rainalarm.app.data.RadarLoadMode
import com.rainalarm.app.data.RadarProviderCoordinator
import com.rainalarm.app.data.RadarProviderKind
import com.rainalarm.app.data.RadarSession
import com.rainalarm.app.data.RadarSettingsRepository
import com.rainalarm.app.data.MeteoGroupRadarSessionLoader
import com.rainalarm.app.data.RegionalRainChartService
import com.rainalarm.app.data.RegionalRadarAreas
import com.rainalarm.app.data.SavedPlace
import com.rainalarm.app.data.WeatherLayerRepository
import com.rainalarm.app.data.AutomaticAppearancePolicy
import com.rainalarm.app.data.buildOpenRadarMinuteSeries
import com.rainalarm.app.domain.ProviderMinuteSeriesBuilder
import com.rainalarm.app.domain.RainMinuteAvailability
import com.rainalarm.app.domain.RainMinuteSeries
import com.rainalarm.app.domain.normalizeBearing
import com.rainalarm.app.widget.FrozenMonitorTarget
import com.rainalarm.app.widget.RainAlarmWidgetConfig
import com.rainalarm.app.widget.RainAlarmWidgetStore
import com.rainalarm.app.widget.WidgetLightningState
import com.rainalarm.app.widget.WidgetTargetResolution
import com.rainalarm.app.widget.WidgetTargetResolver
import com.rainalarm.app.widget.WidgetUpdatePublisher
import com.rainalarm.app.widget.WidgetWeatherSnapshot
import com.rainalarm.app.widget.WidgetAppearanceStore
import com.rainalarm.app.widget.WidgetRefreshTrace
import com.rainalarm.app.widget.WidgetSnapshotCachePolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

object AppVisibilityState {
    @Volatile
    var visible: Boolean = false
        private set

    fun update(value: Boolean) {
        visible = value
    }
}

private data class TargetWork(
    val target: FrozenMonitorTarget,
    val widgetConfigurations: MutableList<RainAlarmWidgetConfig> = mutableListOf(),
    var appSubscription: Boolean = false,
    var requiresLightningDisplay: Boolean = false,
) {
    val widgetIds: List<Int> get() = widgetConfigurations.map(RainAlarmWidgetConfig::appWidgetId)
}

private data class RainAcquisition(
    val series: RainMinuteSeries,
    val activeProvider: RadarProviderKind,
)

private data class TargetProcessOutcome(
    val retryInitialRefresh: Boolean = false,
)

/** One acquisition/decision path shared by periodic work and foreground Now refreshes. */
class WeatherMonitoringCoordinator(
    private val context: Context,
    private val widgetStore: RainAlarmWidgetStore = RainAlarmWidgetStore(context),
    private val monitoringStore: MonitoringStateStore = MonitoringStateStore(context),
    private val lightningRepository: LightningObservationRepository = LightningObservationRepository(),
) {
    private val placePreferences = PlacePreferences(context)
    private val radarSettings = RadarSettingsRepository(context)
    private val rainPreferences = RainAlertPreferences(context)
    private val lightningPreferences = LightningAlertPreferences(context)

    suspend fun run(initialWidgetRefreshOnly: Boolean = false): ListenableWorker.Result =
        transactionMutex.withLock {
        runCatching { runResolvedTargets(initialWidgetRefreshOnly) }.fold(
            onSuccess = { retry ->
                if (retry || widgetStore.hasPendingInitialRefresh()) {
                    ListenableWorker.Result.retry()
                } else {
                    ListenableWorker.Result.success()
                }
            },
            onFailure = { failure ->
                if (failure is CancellationException) throw failure
                ListenableWorker.Result.retry()
            },
        )
    }

    /** Called after the visible Now surface has already acquired a normalized rain series. */
    suspend fun publishForeground(
        place: SavedPlace,
        requestedProvider: RadarProviderKind,
        series: RainMinuteSeries,
        temperatureC: Double?,
    ) = transactionMutex.withLock {
        val places = placePreferences.collection.first()
        val work = resolveTargets(places, requestedProvider).values.firstOrNull { candidate ->
            sameCoordinate(candidate.target, place)
        } ?: return@withLock
        processTarget(
            work,
            places,
            seriesOverride = RainAcquisition(series, requestedProvider),
            temperatureOverride = temperatureC,
            backgroundTransaction = false,
        )
        recentForegroundPublications[work.target.monitoringKey] = System.currentTimeMillis()
        WidgetUpdatePublisher.updateAll(context)
    }

    /**
     * Foreground fallback for location/setting changes which may not produce a usable Now series.
     * A successful visible Now publication wins and suppresses this acquisition for the same
     * selection generation, avoiding duplicate network work.
     */
    suspend fun refreshForeground(
        place: SavedPlace,
        requestedProvider: RadarProviderKind,
        skipIfPublishedAfterMillis: Long = 0L,
    ) = transactionMutex.withLock {
        val places = placePreferences.collection.first()
        val work = resolveTargets(places, requestedProvider).values.firstOrNull { candidate ->
            sameCoordinate(candidate.target, place)
        } ?: return@withLock
        if ((recentForegroundPublications[work.target.monitoringKey] ?: 0L) >=
            skipIfPublishedAfterMillis && skipIfPublishedAfterMillis > 0L
        ) return@withLock
        processTarget(work, places, backgroundTransaction = false)
        recentForegroundPublications[work.target.monitoringKey] = System.currentTimeMillis()
        WidgetUpdatePublisher.updateAll(context)
    }

    private suspend fun runResolvedTargets(initialWidgetRefreshOnly: Boolean): Boolean {
        if (!notificationPermissionGranted()) {
            var changed = false
            if (rainPreferences.snapshot().enabled) {
                rainPreferences.markPermissionNeeded()
                changed = true
            }
            if (lightningPreferences.snapshot().enabled) {
                lightningPreferences.markPermissionNeeded()
                changed = true
            }
            if (changed) RainAlertScheduler(context).reconcile(enqueueImmediate = false)
        }
        val places = placePreferences.collection.first()
        val migration = widgetStore.migrateLegacy(
            places,
            placePreferences.defaultStartupId.first(),
        )
        if (migration.migratedWidgetIds.isNotEmpty() ||
            migration.configurationRequiredWidgetIds.isNotEmpty()
        ) {
            WidgetRefreshTrace.migration(
                migration.migratedWidgetIds.size,
                migration.configurationRequiredWidgetIds.size,
            )
        }
        updateResolvedWidgetAppearance(places)
        val provider = radarSettings.selectedProvider()
        val allTargets = resolveTargets(places, provider)
        val activeSubscriptions = buildSet {
            if (rainPreferences.snapshot().enabled || lightningPreferences.snapshot().enabled) add(APP_SUBSCRIPTION)
            allTargets.values.flatMap(TargetWork::widgetConfigurations)
                .forEach { add(widgetSubscription(it.appWidgetId)) }
        }
        monitoringStore.retainSubscriptions(activeSubscriptions)
        val pendingIds = widgetStore.pendingInitialWidgetIds()
        val targets = if (initialWidgetRefreshOnly) {
            allTargets.filterValues { work -> work.widgetIds.any(pendingIds::contains) }
        } else {
            allTargets
        }
        if (targets.isEmpty()) {
            WidgetUpdatePublisher.updateAll(context)
            return false
        }
        var retry = false
        for (work in targets.values) {
            retry = processTarget(work, places, backgroundTransaction = true).retryInitialRefresh || retry
        }
        WidgetUpdatePublisher.updateAll(context)
        return retry
    }

    private suspend fun updateResolvedWidgetAppearance(places: PlaceCollection) {
        val automatic = radarSettings.automaticAppearance.first()
        val manualApp = radarSettings.appAppearance.first()
        val manualMap = radarSettings.mapAppearance.first()
        val manualCompass = radarSettings.compassAppearance.first()
        val manualGraph = radarSettings.graphAppearance.first()
        val selected = if (places.selectedId == CURRENT_LOCATION_ID) {
            ForegroundLocationSnapshot.freshPlace()
        } else places.selected
        val weather = if (automatic.enabled && selected != null) {
            runCatching { WeatherLayerRepository.point(selected) }.getOrNull()
        } else null
        val nightMode = context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK
        val systemDark = nightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val resolved = AutomaticAppearancePolicy.resolve(
            automatic,
            manualApp,
            manualMap,
            manualCompass,
            manualGraph,
            Instant.now().epochSecond,
            weather,
            systemDark,
        )
        WidgetAppearanceStore(context).update(
            resolved.app,
            resolved.compass,
            resolved.graph,
            systemDark,
        )
    }

    private suspend fun resolveTargets(
        places: PlaceCollection,
        provider: RadarProviderKind,
    ): LinkedHashMap<String, TargetWork> {
        val targets = LinkedHashMap<String, TargetWork>()
        val rainEnabled = rainPreferences.snapshot().enabled
        val lightningEnabled = lightningPreferences.snapshot().enabled
        if (rainEnabled || lightningEnabled) {
            val selected = if (places.selectedId == CURRENT_LOCATION_ID) {
                ForegroundLocationSnapshot.freshPlace()
            } else places.selected
            selected?.let { place ->
                val target = place.toMonitorTarget(provider)
                targets.getOrPut(target.key) { TargetWork(target) }.appSubscription = true
            }
        }
        widgetStore.configurations().forEach { config ->
            when (val resolved = WidgetTargetResolver.resolve(config, places, provider)) {
                is WidgetTargetResolution.Resolved -> targets.getOrPut(resolved.target.key) {
                    TargetWork(resolved.target)
                }.also { work ->
                    work.widgetConfigurations += config
                    work.requiresLightningDisplay = work.requiresLightningDisplay ||
                        widgetRequiresLightning(config)
                    WidgetRefreshTrace.subscriptionRegistered(
                        config.appWidgetId,
                        resolved.target.key,
                        widgetStore.isInitialRefreshPending(config.appWidgetId),
                    )
                }
                is WidgetTargetResolution.Unavailable -> {
                    widgetStore.markProblem(config.appWidgetId, resolved.problem)
                }
            }
        }
        return targets
    }

    private fun widgetRequiresLightning(config: RainAlarmWidgetConfig): Boolean {
        val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(config.appWidgetId)
        val width = maxOf(
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0),
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0),
        ).takeIf { it > 0 }
        return config.requiresLightningDisplay(width)
    }

    private suspend fun processTarget(
        work: TargetWork,
        placesBefore: PlaceCollection,
        seriesOverride: RainAcquisition? = null,
        temperatureOverride: Double? = null,
        backgroundTransaction: Boolean = false,
    ): TargetProcessOutcome {
        val pendingIds = work.widgetIds.filter(widgetStore::isInitialRefreshPending)
        if (backgroundTransaction && AppVisibilityState.visible && work.widgetIds.isEmpty()) {
            return TargetProcessOutcome(retryInitialRefresh = pendingIds.isNotEmpty())
        }
        WidgetRefreshTrace.targetStarted(work.target.key, work.widgetIds)
        val generation = generations.incrementAndGet()
        if (work.widgetIds.isNotEmpty()) {
            widgetStore.prepare(work.widgetIds, work.target, generation)
            WidgetUpdatePublisher.updateAll(context)
        }
        val rainStartedAt = SystemClock.elapsedRealtime()
        val rain = seriesOverride ?: acquireRain(work.target)
        WidgetRefreshTrace.streamFinished(
            work.target.key,
            "rain",
            rain.series.availability.name.lowercase(),
            SystemClock.elapsedRealtime() - rainStartedAt,
        )
        val temperature = if (temperatureOverride != null) temperatureOverride else if (work.widgetIds.isNotEmpty()) {
            runCatching { WeatherLayerRepository.point(work.target.asPlace()).temperatureC }.getOrNull()
        } else null
        val needLightning = lightningPreferences.snapshot().enabled || work.requiresLightningDisplay
        val currentRainEnabled = rainPreferences.snapshot().enabled
        val currentLightningEnabled = lightningPreferences.snapshot().enabled
        val canDeliverNotification = notificationPermissionGranted()
        val priorState = monitoringStore.target(work.target.monitoringKey)
        val lightningCheckpoint = if (currentLightningEnabled && canDeliverNotification) {
            priorState.lightningAlertCheckpoint
        } else {
            priorState.lightningContiguousCheckpoint
        }
        val lightningStartedAt = SystemClock.elapsedRealtime()
        val lightning = if (needLightning) lightningRepository.evaluate(
            place = work.target.asPlace(),
            policy = LightningDetectionPolicy(radiusKilometres = work.target.radiusKilometres),
            lastContiguousEpochSeconds = lightningCheckpoint,
        ) else null
        if (needLightning) WidgetRefreshTrace.streamFinished(
            work.target.key,
            "lightning",
            lightning?.outcome?.name?.lowercase() ?: "unavailable",
            SystemClock.elapsedRealtime() - lightningStartedAt,
        )
        if (backgroundTransaction && AppVisibilityState.visible && work.widgetIds.isEmpty()) {
            widgetStore.cancelUpdating(work.widgetIds, generation)
            return TargetProcessOutcome(retryInitialRefresh = pendingIds.isNotEmpty())
        }
        val rainEvaluation = RainAlertDecisionEngine.evaluateMinuteSeries(rain.series)
        val appRainDecision = if (work.appSubscription && currentRainEnabled &&
            canDeliverNotification
        ) RainAlertDecisionEngine.decide(
            rainEvaluation,
            rainPreferences.memory(work.target.stableId),
            Instant.now().epochSecond,
        ) else null
        val subscriptions = buildList {
            if (work.appSubscription && (currentRainEnabled || currentLightningEnabled)) {
                add(MonitoringSubscription(APP_SUBSCRIPTION))
            }
            work.widgetConfigurations.forEach { config ->
                if (currentRainEnabled || currentLightningEnabled) {
                    add(
                        MonitoringSubscription(
                            widgetSubscription(config.appWidgetId),
                            config.quietHours,
                            rainRequiresPriorClear = true,
                        ),
                    )
                }
            }
        }
        val evaluatedAt = Instant.now()
        val decision = monitoringStore.apply(
            target = work.target,
            rainEvaluation = rainEvaluation,
            lightningEvaluation = lightning,
            rainAlertEnabled = currentRainEnabled,
            lightningAlertEnabled = currentLightningEnabled,
            subscriptions = subscriptions,
            now = evaluatedAt,
            appRainEventIdentity = (rainEvaluation as? RadarAlertEvaluation.Approaching)
                ?.frameIdentity?.takeIf { appRainDecision?.shouldNotify == true },
            notificationDeliveryAvailable = canDeliverNotification,
        )

        if (!stillCurrent(work, placesBefore)) {
            widgetStore.cancelUpdating(work.widgetIds, generation)
            return TargetProcessOutcome(
                retryInitialRefresh = work.widgetIds.any(widgetStore::isInitialRefreshPending),
            )
        }
        if (decision.kind != CombinedAlertPolicy.Kind.NONE && canDeliverNotification) {
            MonitorNotificationPublisher(context).publish(
                work.target,
                decision.kind,
                rainEvaluation as? RadarAlertEvaluation.Approaching,
                evaluatedAt.epochSecond,
                listOfNotNull(decision.rainEventIdentity, decision.lightningEventIdentity).joinToString("|"),
            )
        }
        if (work.appSubscription && appRainDecision != null && canDeliverNotification) {
            rainPreferences.record(
                evaluatedAt.epochSecond,
                rainPreferences.snapshot().status,
                work.target.stableId,
                appRainDecision.nextMemory,
            )
        }
        if (work.appSubscription) updateSettingsStatus(
            work.target,
            rainEvaluation,
            lightning,
            evaluatedAt.epochSecond,
        )
        if (work.widgetIds.isNotEmpty()) {
            val lightningState = when (decision.targetState.lightningDisplayObservation) {
                LightningFrameObservation.DETECTED -> WidgetLightningState.DETECTED
                LightningFrameObservation.NO_DETECTION -> WidgetLightningState.NO_DETECTION
                LightningFrameObservation.UNAVAILABLE -> WidgetLightningState.UNAVAILABLE
                null -> if (needLightning) WidgetLightningState.UNAVAILABLE
                    else WidgetLightningState.NOT_REQUESTED
            }
            val snapshot = WidgetWeatherSnapshot.from(
                target = work.target,
                series = rain.series,
                temperatureC = temperature,
                lightning = lightningState,
                lightningFrameEpochSeconds = decision.targetState.lightningDisplayFrameEpochSeconds,
                updatedEpochSeconds = evaluatedAt.epochSecond,
                generation = generation,
            ).copy(
                provider = rain.activeProvider,
                updateUnavailable = rain.series.availability == RainMinuteAvailability.UNAVAILABLE &&
                    lightningState == WidgetLightningState.UNAVAILABLE && temperature == null,
            )
            val usable = WidgetSnapshotCachePolicy.hasUsableData(snapshot, evaluatedAt.epochSecond)
            if (usable) {
                widgetStore.publish(work.widgetIds, snapshot)
                widgetStore.completeInitialRefresh(pendingIds)
                WidgetRefreshTrace.stateWritten(
                    work.target.key, work.widgetIds, true, "complete",
                )
            } else {
                val failures = widgetStore.recordInitialFailure(pendingIds)
                val ordinaryIds = work.widgetIds.filterNot(pendingIds::contains)
                val publishIds = ordinaryIds + failures.terminalIds
                if (publishIds.isNotEmpty()) {
                    widgetStore.publish(
                        publishIds,
                        snapshot.copy(updateUnavailable = true),
                    )
                }
                widgetStore.cancelUpdating(failures.retryIds, generation)
                WidgetRefreshTrace.stateWritten(
                    work.target.key,
                    work.widgetIds,
                    false,
                    if (failures.retryIds.isNotEmpty()) "retry" else "terminal",
                )
                return TargetProcessOutcome(retryInitialRefresh = failures.retryIds.isNotEmpty())
            }
        }
        return TargetProcessOutcome()
    }

    private suspend fun stillCurrent(work: TargetWork, placesBefore: PlaceCollection): Boolean {
        val currentPlaces = placePreferences.collection.first()
        val provider = radarSettings.selectedProvider()
        val resolved = resolveTargets(currentPlaces, provider)[work.target.key] ?: return false
        val originalIds = work.widgetIds.toSet()
        if (resolved.widgetIds.toSet() != originalIds) return false
        if (work.appSubscription && !resolved.appSubscription) return false
        return placesBefore.selectedId == currentPlaces.selectedId || !work.appSubscription
    }

    private suspend fun acquireRain(target: FrozenMonitorTarget): RainAcquisition {
        if (target.selectedProvider == RadarProviderKind.METEOGROUP_REGIONAL &&
            RegionalRadarAreas.forPoint(target.latitude, target.longitude) != null
        ) {
            val direct = runCatching {
                coroutineScope {
                    val place = target.asPlace()
                    val now = Instant.now().epochSecond
                    val chart = async { RegionalRainChartService().load(place, now) }
                    val travelBearing = async {
                        withTimeoutOrNull(REGIONAL_DIRECTION_TIMEOUT_MILLIS) {
                            MeteoGroupRadarSessionLoader().travelBearingDegrees(place)
                        }
                    }
                    chart.await().let { series ->
                        travelBearing.await()?.let { bearing ->
                            series.copy(
                                travelBearingDegrees = bearing,
                                sourceBearingDegrees = normalizeBearing(bearing + 180.0),
                            )
                        } ?: series
                    }
                }
            }.getOrNull()
            if (direct != null) {
                return RainAcquisition(direct, RadarProviderKind.METEOGROUP_REGIONAL)
            }
        }
        var session: RadarSession? = null
        return try {
            session = RadarProviderCoordinator(radarSettings).load(
                target.asPlace(),
                maxFrames = 5,
                mode = RadarLoadMode.ALERT_ANALYSIS,
            )
            val evaluatedAt = Instant.now().epochSecond
            val series = if (session.providerSelection.active == RadarProviderKind.METEOGROUP_REGIONAL) {
                val fallback = ProviderMinuteSeriesBuilder.fromSamples(
                    requireNotNull(session.legacyArchive).pointSamples,
                    "MeteoGroup provider forecast",
                    evaluatedAt,
                )
                RegionalRainChartService().preferChart(target.asPlace(), evaluatedAt, fallback)
            } else buildOpenRadarMinuteSeries(session, evaluatedAt)
            RainAcquisition(series, session.providerSelection.active)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            RainAcquisition(
                RainMinuteSeries.unavailable(
                    Instant.now().epochSecond,
                    "Radar estimate",
                    "Radar update unavailable",
                ),
                target.selectedProvider,
            )
        } finally {
            session?.release()
        }
    }

    private fun updateSettingsStatus(
        target: FrozenMonitorTarget,
        rain: RadarAlertEvaluation,
        lightning: LightningEvaluationResult?,
        now: Long,
    ) {
        if (rainPreferences.snapshot().enabled) {
            val status = when (rain) {
                is RadarAlertEvaluation.Approaching -> context.getString(
                    if (rain.likelySnow) R.string.notification_status_snow else R.string.notification_status_rain,
                    target.displayName,
                    rain.etaStartMinutes,
                )
                RadarAlertEvaluation.WetNow -> context.getString(R.string.notification_status_wet, target.displayName)
                RadarAlertEvaluation.Clear -> context.getString(R.string.notification_status_clear, target.displayName)
                RadarAlertEvaluation.Unknown -> context.getString(R.string.notification_status_unknown, target.displayName)
            }
            rainPreferences.record(now, status)
        }
        if (lightningPreferences.snapshot().enabled) {
            val status = when (lightning?.outcome) {
                LightningEvaluationOutcome.DETECTED -> context.getString(
                    R.string.notification_status_lightning_detected,
                    target.displayName,
                )
                LightningEvaluationOutcome.NO_DETECTION,
                LightningEvaluationOutcome.NO_NEW_FRAMES,
                -> context.getString(R.string.notification_status_lightning_clear, target.displayName)
                LightningEvaluationOutcome.UNAVAILABLE, null ->
                    context.getString(R.string.notification_status_lightning_unavailable)
            }
            lightningPreferences.recordCheck(now, status)
        }
    }

    private fun notificationPermissionGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED

    private fun SavedPlace.toMonitorTarget(provider: RadarProviderKind) = FrozenMonitorTarget(
        id,
        name,
        latitude,
        longitude,
        provider,
    )

    private fun sameCoordinate(target: FrozenMonitorTarget, place: SavedPlace): Boolean =
        target.stableId == place.id && target.latitude == place.latitude && target.longitude == place.longitude

    private companion object {
        const val APP_SUBSCRIPTION = "app"
        const val REGIONAL_DIRECTION_TIMEOUT_MILLIS = 8_000L
        val transactionMutex = Mutex()
        val generations = AtomicLong(System.currentTimeMillis())
        val recentForegroundPublications = mutableMapOf<String, Long>()
        fun widgetSubscription(id: Int) = "widget:$id"
    }
}

private class MonitorNotificationPublisher(private val context: Context) {
    fun publish(
        target: FrozenMonitorTarget,
        kind: CombinedAlertPolicy.Kind,
        approaching: RadarAlertEvaluation.Approaching?,
        evaluatedAtEpochSeconds: Long,
        eventIdentity: String,
    ) {
        createNotificationChannel(context)
        val rainContent = approaching?.let {
            RainAlertNotificationText.forApproaching(context, target.displayName, it, evaluatedAtEpochSeconds)
        }
        val title = when (kind) {
            CombinedAlertPolicy.Kind.RAIN -> rainContent?.title ?: return
            CombinedAlertPolicy.Kind.LIGHTNING -> context.getString(
                R.string.notification_lightning_title,
                target.displayName,
            )
            CombinedAlertPolicy.Kind.COMBINED -> context.getString(
                R.string.notification_combined_title,
                target.displayName,
            )
            CombinedAlertPolicy.Kind.NONE -> return
        }
        val summary = when (kind) {
            CombinedAlertPolicy.Kind.RAIN -> rainContent?.summary ?: return
            CombinedAlertPolicy.Kind.LIGHTNING -> context.getString(
                R.string.notification_lightning_detail,
                LightningDetectionPolicy.defaultRadiusKilometres.toInt(),
            )
            CombinedAlertPolicy.Kind.COMBINED -> context.getString(
                R.string.notification_combined_summary,
                rainContent?.summary ?: context.getString(R.string.notification_rain_approaching),
            )
            CombinedAlertPolicy.Kind.NONE -> return
        }
        val detail = when (kind) {
            CombinedAlertPolicy.Kind.RAIN -> rainContent?.detail ?: summary
            CombinedAlertPolicy.Kind.LIGHTNING -> context.getString(
                R.string.notification_lightning_detail,
                LightningDetectionPolicy.defaultRadiusKilometres.toInt(),
            )
            CombinedAlertPolicy.Kind.COMBINED -> context.getString(
                R.string.notification_combined_detail,
                rainContent?.detail ?: summary,
                LightningDetectionPolicy.defaultRadiusKilometres.toInt(),
            )
            CombinedAlertPolicy.Kind.NONE -> summary
        }
        val intent = RadarAlertDeepLink.put(
            Intent(context, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
            target,
            showLightningTemporarily = kind == CombinedAlertPolicy.Kind.LIGHTNING ||
                kind == CombinedAlertPolicy.Kind.COMBINED,
        )
        val requestCode = (target.key + eventIdentity).hashCode().and(Int.MAX_VALUE).coerceAtLeast(1)
        val pendingIntent = PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, "rain_approaching_v2")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(summary)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        if (MonitorNotificationPresentationPolicy.usesExpandedStyle(kind)) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(detail))
        }
        val notification = builder.build()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                NotificationManagerCompat.from(context).notify(
                    (4107 xor target.key.hashCode()).and(Int.MAX_VALUE).coerceAtLeast(1),
                    notification,
                )
            } catch (_: SecurityException) {
                // Permission may be revoked between the check and publication. The next
                // reconciliation records that state without crashing the worker.
            }
        }
    }
}
