package com.rainalarm.app.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.currentState
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.color.ColorProvider
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.datastore.preferences.core.Preferences
import com.rainalarm.app.MainActivity
import com.rainalarm.app.BuildConfig
import com.rainalarm.app.R
import com.rainalarm.app.alerts.RadarAlertDeepLink
import com.rainalarm.app.alerts.RainAlertScheduler
import com.rainalarm.app.domain.PrecipitationPresentationKind
import com.rainalarm.app.domain.QualitativeIntensity
import com.rainalarm.app.domain.WeatherPresentationPolicy
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date

class RainAlarmWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = runCatching { GlanceAppWidgetManager(context).getAppWidgetId(id) }
            .getOrNull() ?: return
        provideContent {
            val refreshToken = currentState<Preferences>()[WidgetUpdatePublisher.refreshToken] ?: 0L
            WidgetContent(context, appWidgetId, refreshToken)
        }
    }

    suspend fun updateAllWidgets(context: Context) = updateAll(context)
}

class RainAlarmWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RainAlarmWidget()

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        // Also performs idempotent legacy target migration after app update/process recreation.
        RainAlertScheduler(context).reconcile(enqueueImmediate = true)
        WidgetPresentationTicker.reconcile(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        RainAlarmWidgetStore(context).delete(appWidgetIds)
        val state = com.rainalarm.app.alerts.MonitoringStateStore(context)
        appWidgetIds.forEach { state.removeSubscription("widget:$it") }
        RainAlertScheduler(context).reconcile(enqueueImmediate = false)
        WidgetPresentationTicker.reconcile(context)
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        RainAlertScheduler(context).reconcile(enqueueImmediate = true)
        WidgetPresentationTicker.reconcile(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        // Crossing a responsive breakpoint can add or remove Lightning presentation demand.
        RainAlertScheduler(context).reconcile(enqueueImmediate = true)
        WidgetPresentationTicker.reconcile(context)
    }
}

@Composable
private fun WidgetContent(context: Context, appWidgetId: Int, refreshToken: Long) {
    // The token is intentionally a composition input. Widget data lives in the existing compact
    // SharedPreferences store, while Glance state provides the observable invalidation signal.
    @Suppress("UNUSED_VARIABLE")
    val observedRefreshToken = refreshToken
    val size = LocalSize.current
    val width = size.width.value.toInt()
    val sizeClass = WidgetResponsivePolicy.sizeClass(width)
    val store = RainAlarmWidgetStore(context)
    val config = store.configuration(appWidgetId)
    val snapshot = store.snapshot(appWidgetId)?.let(WidgetSnapshotCachePolicy::forDisplay)
    val targetProblem = store.problem(appWidgetId)
    val appearance = WidgetAppearanceStore(context).current()
    val appPalette = WidgetPalette.forKind(appearance.app)
    val compassPalette = WidgetPalette.forKind(appearance.compass)
    val graphPalette = WidgetPalette.forKind(appearance.graph)
    val opacity = config?.backgroundOpacityPercent ?: 100
    val background = WidgetOpacityPolicy.applyToArgb(appPalette.surface, opacity)
    val clickIntent = widgetClickIntent(
        context,
        appWidgetId,
        config,
        snapshot,
        targetProblem,
        config?.requiresLightningDisplay(width) == true,
    )
    if (BuildConfig.DEBUG && snapshot != null) {
        val nowSeconds = System.currentTimeMillis() / 1_000L
        val currentSeries = snapshot.rainSeries(nowSeconds)
        val presentation = WidgetRainPresentationPolicy.from(snapshot, currentSeries)
        android.util.Log.d(
            "RainWidgetMinute",
            "render widget=$appWidgetId kind=${presentation.kind} " +
                "arrival=${presentation.arrivalMinute ?: -1} now=$nowSeconds " +
                "start=${snapshot.seriesStartEpochSeconds ?: -1} " +
                "points=${currentSeries?.points?.size ?: 0} " +
                "updated=${snapshot.lastSuccessfulEpochSeconds}",
        )
    }

    Box(
        GlanceModifier.fillMaxSize()
            .background(widgetColor(background))
            .cornerRadius(24.dp)
            .clickable(actionStartActivity(clickIntent))
            .padding(if (sizeClass == WidgetSizeClass.COMPACT) 7.dp else 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (config == null) {
            EmptyWidget(context.getString(R.string.widget_tap_to_configure), appPalette)
        } else if (targetProblem != null) {
            EmptyWidget(
                context.getString(
                    when (targetProblem) {
                        WidgetTargetProblem.CONFIGURATION_REQUIRED -> R.string.widget_configuration_required
                    },
                ),
                appPalette,
            )
        } else if (snapshot == null) {
            EmptyWidget(
                context.getString(R.string.widget_updating),
                appPalette,
            )
        } else when (sizeClass) {
            WidgetSizeClass.COMPACT -> CompactWidget(context, config, snapshot, compassPalette)
            WidgetSizeClass.SMALL -> SmallWidget(context, config, snapshot, appPalette, compassPalette)
            WidgetSizeClass.MEDIUM -> MediumWidget(context, config, snapshot, appPalette, compassPalette)
            WidgetSizeClass.EXPANDED -> ExpandedWidget(
                context,
                config,
                snapshot,
                appPalette,
                compassPalette,
                graphPalette,
                width,
            )
        }
    }
}

@Composable
private fun EmptyWidget(label: String, palette: WidgetPalette) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "—",
            style = TextStyle(
                color = widgetColor(palette.accent),
                fontSize = 25.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            ),
        )
        Text(
            label,
            style = TextStyle(
                color = widgetColor(palette.text),
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
            ),
            maxLines = 2,
        )
    }
}

@Composable
private fun CompactWidget(
    context: Context,
    config: RainAlarmWidgetConfig,
    snapshot: WidgetWeatherSnapshot,
    palette: WidgetPalette,
) {
    val primary = WidgetPresentationPolicy.primary(context, config, snapshot)
    val rain = WidgetRainPresentationPolicy.from(snapshot)
    val face = WidgetCompassContentPolicy.presentation(
        WidgetCompassContentMode.COMPASS_ONLY,
        config.primaryContent,
        rain,
        primary,
    )
    val bitmap = WidgetBitmapRenderer.compass(context, face, palette, 150)
    Image(
        ImageProvider(bitmap),
        contentDescription = widgetDescription(context, snapshot, includeLightning = false),
        modifier = GlanceModifier.fillMaxSize(),
        contentScale = ContentScale.Fit,
    )
}

@Composable
private fun SmallWidget(
    context: Context,
    config: RainAlarmWidgetConfig,
    snapshot: WidgetWeatherSnapshot,
    palette: WidgetPalette,
    compassPalette: WidgetPalette,
) {
    TextBearingWidget(context, config, snapshot, palette, compassPalette)
}

@Composable
private fun MediumWidget(
    context: Context,
    config: RainAlarmWidgetConfig,
    snapshot: WidgetWeatherSnapshot,
    palette: WidgetPalette,
    compassPalette: WidgetPalette,
) {
    TextBearingWidget(context, config, snapshot, palette, compassPalette)
}

@Composable
private fun TextBearingWidget(
    context: Context,
    config: RainAlarmWidgetConfig,
    snapshot: WidgetWeatherSnapshot,
    palette: WidgetPalette,
    compassPalette: WidgetPalette,
) {
    val compassDiameterDp = WidgetResponsivePolicy.textBearingCompassDiameterDp
    val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
    val rain = WidgetRainPresentationPolicy.from(snapshot)
    val face = WidgetCompassContentPolicy.presentation(
        WidgetCompassContentMode.TEXT_BEARING,
        config.primaryContent,
        rain,
        WidgetPresentationPolicy.primary(context, config, snapshot),
    )
    Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Image(
            ImageProvider(
                WidgetBitmapRenderer.compass(
                    context,
                    face,
                    compassPalette,
                    WidgetGraphLayoutPolicy.pixels(compassDiameterDp, density),
                ),
            ),
            contentDescription = widgetDescription(context, snapshot, includeLightning = true),
            modifier = GlanceModifier.size(compassDiameterDp.dp),
            contentScale = ContentScale.Fit,
        )
        Spacer(GlanceModifier.width(WidgetResponsivePolicy.textBearingGapDp.dp))
        CompactStatusColumn(
            context, config, snapshot, palette, GlanceModifier.defaultWeight(),
        )
    }
}

@Composable
private fun ExpandedWidget(
    context: Context,
    config: RainAlarmWidgetConfig,
    snapshot: WidgetWeatherSnapshot,
    palette: WidgetPalette,
    compassPalette: WidgetPalette,
    graphPalette: WidgetPalette,
    widgetWidthDp: Int,
) {
    val series = snapshot.rainSeries()
    val mini = WidgetRainPresentationPolicy.from(snapshot, series)
    val primary = WidgetPresentationPolicy.primary(context, config, snapshot)
    val face = WidgetCompassContentPolicy.presentation(
        WidgetCompassContentMode.TEXT_BEARING,
        config.primaryContent,
        mini,
        primary,
    )
    val compassDiameterDp = WidgetResponsivePolicy.textBearingCompassDiameterDp
    val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
    Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Image(
            ImageProvider(
                WidgetBitmapRenderer.compass(
                    context,
                    face,
                    compassPalette,
                    WidgetGraphLayoutPolicy.pixels(compassDiameterDp, density),
                ),
            ),
            contentDescription = widgetDescription(context, snapshot, includeLightning = true),
            modifier = GlanceModifier.size(compassDiameterDp.dp),
            contentScale = ContentScale.Fit,
        )
        Spacer(GlanceModifier.width(WidgetResponsivePolicy.textBearingGapDp.dp))
        Column(GlanceModifier.width(WidgetResponsivePolicy.expandedStatusWidthDp.dp)) {
            PlaceText(snapshot.placeName, palette)
            PrimaryText(primary.headline, palette)
            mini.qualitativeIntensity?.let { MutedText(localizedIntensity(context, it), palette) }
            mini.confirmedStopMinute?.let { minute ->
                val stop = (series?.startEpochSeconds ?: snapshot.updatedEpochSeconds) + minute * 60L
                MutedText(context.getString(R.string.widget_stops_at, formatClock(context, stop)), palette)
            }
            if (snapshot.lightning == WidgetLightningState.DETECTED) LightningText(context, palette)
            MutedText(updateLabel(context, snapshot), palette)
        }
        Spacer(GlanceModifier.width(WidgetResponsivePolicy.textBearingGapDp.dp))
        val graphWidthDp = WidgetGraphLayoutPolicy.widthDp(widgetWidthDp)
        Image(
            ImageProvider(
                WidgetBitmapRenderer.graph(
                    context,
                    series,
                    graphPalette,
                    WidgetGraphLayoutPolicy.pixels(graphWidthDp, density),
                    WidgetGraphLayoutPolicy.pixels(WidgetGraphLayoutPolicy.graphHeightDp, density),
                ),
            ),
            contentDescription = context.getString(R.string.widget_graph_description),
            modifier = GlanceModifier.defaultWeight()
                .height(WidgetGraphLayoutPolicy.graphHeightDp.dp),
            contentScale = ContentScale.FillBounds,
        )
    }
}

@Composable
private fun CompactStatusColumn(
    context: Context,
    config: RainAlarmWidgetConfig,
    snapshot: WidgetWeatherSnapshot,
    palette: WidgetPalette,
    modifier: GlanceModifier,
) {
    val primary = WidgetPresentationPolicy.primary(context, config, snapshot)
    val series = snapshot.rainSeries()
    val rain = WidgetRainPresentationPolicy.from(snapshot, series)
    Column(modifier) {
        PlaceText(snapshot.placeName, palette)
        PrimaryText(primary.headline, palette)
        rain.qualitativeIntensity?.let { MutedText(localizedIntensity(context, it), palette) }
        rain.confirmedStopMinute?.let { minute ->
            val stop = (series?.startEpochSeconds ?: snapshot.updatedEpochSeconds) + minute * 60L
            MutedText(context.getString(R.string.widget_stops_at, formatClock(context, stop)), palette)
        }
        if (primary.lightningBadge) LightningText(context, palette)
        MutedText(updateLabel(context, snapshot), palette)
    }
}

@Composable
private fun PlaceText(value: String, palette: WidgetPalette) = Text(
    value,
    style = TextStyle(
        color = widgetColor(palette.text),
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
    ),
    maxLines = 1,
)

@Composable
private fun PrimaryText(value: String, palette: WidgetPalette) = Text(
    value,
    style = TextStyle(
        color = widgetColor(palette.text),
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
    ),
    maxLines = 2,
)

@Composable
private fun MutedText(value: String, palette: WidgetPalette) = Text(
    value,
    style = TextStyle(color = widgetColor(palette.muted), fontSize = 10.sp),
    maxLines = 1,
)

@Composable
private fun LightningText(context: Context, palette: WidgetPalette) = Text(
    context.getString(R.string.widget_lightning_nearby),
    style = TextStyle(
        color = widgetColor(palette.accent),
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
    ),
    maxLines = 1,
)

private fun widgetClickIntent(
    context: Context,
    appWidgetId: Int,
    config: RainAlarmWidgetConfig?,
    snapshot: WidgetWeatherSnapshot?,
    problem: WidgetTargetProblem?,
    displaysLightning: Boolean,
): Intent {
    if (config == null || snapshot == null || problem == WidgetTargetProblem.CONFIGURATION_REQUIRED) {
        return Intent(context, WidgetConfigurationActivity::class.java)
        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val target = FrozenMonitorTarget(
        snapshot.targetStableId,
        snapshot.placeName,
        snapshot.latitude,
        snapshot.longitude,
        snapshot.provider,
    )
    return RadarAlertDeepLink.put(
        Intent(context, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
        ),
        target,
        displaysLightning && snapshot.lightning == WidgetLightningState.DETECTED,
    )
}

private fun widgetDescription(
    context: Context,
    snapshot: WidgetWeatherSnapshot,
    includeLightning: Boolean,
): String {
    val series = snapshot.rainSeries()
    val presentation = WidgetRainPresentationPolicy.from(snapshot, series)
    val rain = when (presentation.kind) {
        PrecipitationPresentationKind.RAIN -> if (presentation.rainingNow) {
            context.getString(R.string.widget_rain_now)
        } else context.getString(R.string.widget_rain_in, presentation.arrivalMinute ?: 0)
        PrecipitationPresentationKind.LIKELY_SNOW -> if (presentation.rainingNow) {
            context.getString(R.string.widget_snow_now)
        } else context.getString(R.string.widget_snow_in, presentation.arrivalMinute ?: 0)
        PrecipitationPresentationKind.CLEAR -> context.getString(
            if (presentation.completeHour) R.string.widget_dry_next_hour else R.string.now_no_rain,
        )
        PrecipitationPresentationKind.UNKNOWN -> context.getString(R.string.widget_update_unavailable)
    }
    val facts = mutableListOf(snapshot.placeName, rain)
    presentation.qualitativeIntensity?.let { facts += localizedIntensity(context, it) }
    presentation.confirmedStopMinute?.let { minute ->
        val base = snapshot.seriesStartEpochSeconds ?: snapshot.updatedEpochSeconds
        facts += context.getString(R.string.widget_stops_at, formatClock(context, base + minute * 60L))
    }
    if (includeLightning) when (snapshot.lightning) {
        WidgetLightningState.DETECTED -> facts += context.getString(R.string.widget_lightning_nearby)
        WidgetLightningState.NO_DETECTION -> facts += context.getString(R.string.widget_no_recent_lightning)
        WidgetLightningState.UNAVAILABLE -> facts += context.getString(R.string.notification_status_lightning_unavailable)
        WidgetLightningState.NOT_REQUESTED -> Unit
    }
    facts += updateLabel(context, snapshot)
    return facts.joinToString(". ")
}

private fun updateLabel(context: Context, snapshot: WidgetWeatherSnapshot): String = when {
    snapshot.updating && snapshot.lastSuccessfulEpochSeconds > 0L -> listOf(
        context.getString(R.string.widget_updating),
        context.getString(
            R.string.widget_updated_at,
            formatClock(context, snapshot.lastSuccessfulEpochSeconds),
        ),
    ).joinToString(" · ")
    snapshot.updating -> context.getString(R.string.widget_updating)
    // A failed attempt is not a successful update. A still-usable last-good snapshot is rendered
    // above with its real timestamp; a terminal no-data state never fabricates an Updated line.
    snapshot.updateUnavailable -> context.getString(R.string.widget_update_unavailable)
    snapshot.lastSuccessfulEpochSeconds > 0L -> context.getString(
        R.string.widget_updated_at,
        formatClock(context, snapshot.lastSuccessfulEpochSeconds),
    )
    else -> context.getString(R.string.widget_update_unavailable)
}

private fun formatClock(context: Context, epochSeconds: Long): String =
    android.text.format.DateFormat.getTimeFormat(context).format(Date(epochSeconds * 1_000L))

private fun localizedIntensity(context: Context, intensity: QualitativeIntensity): String = context.getString(
    when (intensity) {
        QualitativeIntensity.LIGHT -> R.string.now_light
        QualitativeIntensity.MEDIUM -> R.string.now_medium
        QualitativeIntensity.SEVERE -> R.string.now_severe
    },
)

/** Public day/night Glance provider with one resolved app colour in both slots. */
private fun widgetColor(argb: Int) = ColorProvider(Color(argb), Color(argb))
