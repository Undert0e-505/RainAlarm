package com.rainalarm.app.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.rainalarm.app.BuildConfig
import com.rainalarm.app.domain.PrecipitationPresentationKind
import com.rainalarm.app.domain.WeatherPresentationPolicy
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Pure clock/layout policy; acquisition timestamps never participate in presentation ticks. */
internal object WidgetPresentationTickPolicy {
    fun nextMinuteBoundary(clock: Clock): Instant = ZonedDateTime.now(clock)
        .plusMinutes(1)
        .withSecond(0)
        .withNano(0)
        .toInstant()

    fun delayUntilNextMinuteMillis(clock: Clock): Long =
        (nextMinuteBoundary(clock).toEpochMilli() - clock.millis()).coerceAtLeast(1L)

    fun requiresTick(
        @Suppress("UNUSED_PARAMETER") config: RainAlarmWidgetConfig,
        snapshot: WidgetWeatherSnapshot?,
        @Suppress("UNUSED_PARAMETER") widthDp: Int,
        nowEpochSeconds: Long,
    ): Boolean {
        val series = snapshot?.rainSeries(nowEpochSeconds) ?: return false
        // Approaching/current precipitation changes countdown, intensity and stop state. A clear
        // complete series also needs one final repaint when its truthful prediction horizon ends.
        return WeatherPresentationPolicy.from(series, snapshot.temperatureC).kind !=
            PrecipitationPresentationKind.UNKNOWN
    }

    fun shouldSchedule(widgetDemands: Iterable<Boolean>): Boolean = widgetDemands.any { it }
}

/**
 * One process-wide, presentation-only minute ticker for every widget.
 *
 * The main-thread callback gives prompt screen-on repaint while this process lives. A single
 * non-wakeup, inexact elapsed-realtime alarm restores the chain after process death without
 * waking a sleeping device or requiring restricted exact-alarm permission.
 */
object WidgetPresentationTicker {
    internal const val ACTION_TICK = "com.rainalarm.app.widget.PRESENTATION_TICK"
    private const val REQUEST_CODE = 41_107
    private const val TAG = "RainWidgetMinute"
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private var callback: Runnable? = null
    private var lastDeliveredMinuteKey: Long? = null

    fun reconcile(
        context: Context,
        clock: Clock = Clock.system(ZoneId.systemDefault()),
    ) {
        val appContext = context.applicationContext
        val nowSeconds = clock.instant().epochSecond
        val store = RainAlarmWidgetStore(appContext)
        val manager = AppWidgetManager.getInstance(appContext)
        val needed = WidgetPresentationTickPolicy.shouldSchedule(store.configurations().map { config ->
            val width = runCatching {
                manager.getAppWidgetOptions(config.appWidgetId)
                    .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 110)
            }.getOrDefault(110)
            WidgetPresentationTickPolicy.requiresTick(
                config, store.snapshot(config.appWidgetId), width, nowSeconds,
            )
        })
        synchronized(lock) {
            callback?.let(handler::removeCallbacks)
            callback = null
            if (!needed) {
                alarmManager(appContext).cancel(pendingIntent(appContext))
                trace("cancelled no_dynamic_widget")
                return
            }
            val delay = WidgetPresentationTickPolicy.delayUntilNextMinuteMillis(clock)
            val wallTarget = clock.millis() + delay
            val runnable = Runnable { deliver(appContext) }
            callback = runnable
            handler.postDelayed(runnable, delay)
            val elapsedTarget = android.os.SystemClock.elapsedRealtime() + delay
            alarmManager(appContext).set(
                AlarmManager.ELAPSED_REALTIME,
                elapsedTarget,
                pendingIntent(appContext),
            )
            trace("scheduled wall=$wallTarget delayMs=$delay")
        }
    }

    fun deliver(context: Context, force: Boolean = false) {
        val appContext = context.applicationContext
        val interactive = appContext.getSystemService(PowerManager::class.java)?.isInteractive == true
        if (!interactive) {
            synchronized(lock) {
                callback?.let(handler::removeCallbacks)
                callback = null
            }
            // ELAPSED_REALTIME is deliberately non-wakeup. If delivery is batched while asleep,
            // retain one future alarm and derive directly from wall time after the next wake.
            trace("deferred screen_off")
            reconcile(appContext)
            return
        }
        val minuteKey = System.currentTimeMillis() / 60_000L
        val duplicate = synchronized(lock) {
            val alreadyDelivered = !force && lastDeliveredMinuteKey == minuteKey
            callback?.let(handler::removeCallbacks)
            callback = null
            if (!alreadyDelivered) lastDeliveredMinuteKey = minuteKey
            alreadyDelivered
        }
        // The Handler and AlarmManager intentionally protect the same boundary. Whichever wins
        // cancels the other; a delivery already in flight is suppressed by the minute key.
        alarmManager(appContext).cancel(pendingIntent(appContext))
        if (duplicate) {
            trace("duplicate_suppressed minute=$minuteKey")
            reconcile(appContext)
            return
        }
        trace("repaint")
        scope.launch {
            runCatching { WidgetUpdatePublisher.updateAll(appContext) }
                .onFailure { trace("repaint_failed ${it.javaClass.simpleName}") }
        }
    }

    fun environmentChanged(context: Context) {
        val appContext = context.applicationContext
        val interactive = appContext.getSystemService(PowerManager::class.java)?.isInteractive == true
        if (interactive) deliver(appContext, force = true) else reconcile(appContext)
    }

    private fun alarmManager(context: Context): AlarmManager =
        requireNotNull(context.getSystemService(AlarmManager::class.java))

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, WidgetPresentationTickReceiver::class.java).setAction(ACTION_TICK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun trace(message: String) {
        if (BuildConfig.DEBUG) android.util.Log.d(TAG, message)
    }
}

class WidgetPresentationTickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            WidgetPresentationTicker.ACTION_TICK -> WidgetPresentationTicker.deliver(context)
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            -> {
                WidgetPresentationTicker.environmentChanged(context)
                if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
                    com.rainalarm.app.alerts.RainAlertScheduler(context)
                        .reconcile(enqueueImmediate = true)
                }
            }
            else -> WidgetPresentationTicker.reconcile(context)
        }
    }
}
