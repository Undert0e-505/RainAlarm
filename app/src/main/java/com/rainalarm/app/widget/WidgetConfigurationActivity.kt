package com.rainalarm.app.widget

import android.app.Activity
import android.app.TimePickerDialog
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.rainalarm.app.MainActivity
import com.rainalarm.app.R
import com.rainalarm.app.alerts.QuietHours
import com.rainalarm.app.alerts.QuietHoursPolicy
import com.rainalarm.app.alerts.RainAlertScheduler
import com.rainalarm.app.data.PlaceCollectionRules
import com.rainalarm.app.data.PlacePreferences
import com.rainalarm.app.domain.MiniCompassPresentation
import com.rainalarm.app.domain.PrecipitationPresentationKind
import com.rainalarm.app.ui.RainAlarmSwitch
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.util.Locale

class WidgetConfigurationActivity : AppCompatActivity() {
    private var appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)
        appWidgetId = intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        enableEdgeToEdge()
        setContent {
            val appearance = remember { WidgetAppearanceStore(this).current() }
            val palette = WidgetPalette.forKind(appearance.app)
            MaterialTheme(
                colorScheme = if (appearance.app == WidgetPaletteKind.LIGHT) {
                    androidx.compose.material3.lightColorScheme(
                        primary = androidx.compose.ui.graphics.Color(palette.accent),
                        background = androidx.compose.ui.graphics.Color(palette.background),
                        surface = androidx.compose.ui.graphics.Color(palette.surface),
                        onBackground = androidx.compose.ui.graphics.Color(palette.text),
                        onSurface = androidx.compose.ui.graphics.Color(palette.text),
                        outline = androidx.compose.ui.graphics.Color(palette.border),
                    )
                } else androidx.compose.material3.darkColorScheme(
                    primary = androidx.compose.ui.graphics.Color(palette.accent),
                    background = androidx.compose.ui.graphics.Color(palette.background),
                    surface = androidx.compose.ui.graphics.Color(palette.surface),
                    onBackground = androidx.compose.ui.graphics.Color(palette.text),
                    onSurface = androidx.compose.ui.graphics.Color(palette.text),
                    outline = androidx.compose.ui.graphics.Color(palette.border),
                ),
            ) {
                WidgetConfigurationScreen(
                    appWidgetId,
                    RainAlarmWidgetStore(this).configuration(appWidgetId),
                    palette,
                    onCancel = ::finish,
                    onSave = ::save,
                )
            }
        }
    }

    private fun save(config: RainAlarmWidgetConfig) {
        val store = RainAlarmWidgetStore(this)
        val acquisitionChanged = store.save(config)
        RainAlertScheduler(this).reconcile(enqueueImmediate = false)
        if (acquisitionChanged) {
            // Persist and enqueue before finishing. Widget work is independent of Activity
            // visibility, so the target cannot be stranded in the finish/onStop lifecycle gap.
            RainAlertScheduler(this).enqueueWidgetInitialRefresh()
        }
        lifecycleScope.launch {
            // The launcher must not retain the initial "Tap to configure" RemoteViews. Complete
            // the first Glance publication before finishing; finishing first cancels this scope on
            // sufficiently fast launchers.
            runCatching { WidgetUpdatePublisher.updateAll(this@WidgetConfigurationActivity) }
            setResult(
                Activity.RESULT_OK,
                Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
            )
            finish()
        }
    }
}

@Composable
private fun WidgetConfigurationScreen(
    appWidgetId: Int,
    existing: RainAlarmWidgetConfig?,
    palette: WidgetPalette,
    onCancel: () -> Unit,
    onSave: (RainAlarmWidgetConfig) -> Unit,
) {
    val context = LocalContext.current
    val places by remember(context) { PlacePreferences(context).collection }
        .collectAsStateWithLifecycle(initialValue = PlaceCollectionRules.freshInstall())
    var fixedPlaceId by rememberSaveable {
        mutableStateOf(
            existing?.savedPlace?.stableId ?: existing?.fixedPlaceId ?:
                places.places.firstOrNull()?.id,
        )
    }
    var primary by rememberSaveable {
        mutableStateOf(existing?.primaryContent ?: WidgetPrimaryContent.SMART)
    }
    var opacity by rememberSaveable {
        mutableIntStateOf(existing?.backgroundOpacityPercent ?: 100)
    }
    var quietEnabled by rememberSaveable {
        mutableStateOf(existing?.quietHoursEnabled ?: false)
    }
    var quietStart by rememberSaveable {
        mutableIntStateOf(existing?.quietStartMinuteOfDay ?: 22 * 60)
    }
    var quietEnd by rememberSaveable {
        mutableIntStateOf(existing?.quietEndMinuteOfDay ?: 7 * 60)
    }
    LaunchedEffect(places.places) {
        if (places.places.none { it.id == fixedPlaceId }) {
            fixedPlaceId = places.places.firstOrNull()?.id
        }
    }
    val quiet = QuietHours(quietEnabled, quietStart, quietEnd)
    val selectedSavedPlace = places.places.firstOrNull { it.id == fixedPlaceId }
    val validLocation = selectedSavedPlace != null
    val valid = validLocation && (!quietEnabled || quiet.valid)
    val surface = androidx.compose.ui.graphics.Color(
        WidgetOpacityPolicy.applyToArgb(palette.surface, opacity),
    )
    val previewDescription = stringResource(R.string.widget_preview_description, opacity)
    Box(
        Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(palette.background)),
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 28.dp),
        ) {
            Text(
                stringResource(R.string.widget_configure_title),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(R.string.widget_configure_subtitle),
                color = androidx.compose.ui.graphics.Color(palette.muted),
                modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
            )

            SectionHeading(stringResource(R.string.widget_location_heading), palette)
            Card(
                colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color(palette.surface)),
                shape = RoundedCornerShape(22.dp),
            ) {
                places.places.forEachIndexed { index, place ->
                    if (index > 0) HorizontalDivider(color = androidx.compose.ui.graphics.Color(palette.border))
                    ChoiceRow(place.name, fixedPlaceId == place.id) {
                        fixedPlaceId = place.id
                    }
                }
            }
            if (places.places.isEmpty()) {
                Column(Modifier.padding(top = 8.dp)) {
                    Text(
                        stringResource(R.string.widget_no_saved_places),
                        color = androidx.compose.ui.graphics.Color(palette.muted),
                        fontSize = 12.sp,
                    )
                    Button(
                        onClick = {
                            context.startActivity(
                                Intent(context, MainActivity::class.java)
                                    .putExtra(MainActivity.EXTRA_OPEN_PLACES, true),
                            )
                        },
                        modifier = Modifier.padding(top = 8.dp),
                    ) { Text(stringResource(R.string.widget_open_places)) }
                }
            }

            Spacer(Modifier.height(22.dp))
            SectionHeading(stringResource(R.string.widget_compact_content_heading), palette)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                WidgetPrimaryContent.entries.forEach { option ->
                    FilterChip(
                        selected = primary == option,
                        onClick = { primary = option },
                        label = { Text(primaryLabel(option), fontSize = 11.sp) },
                    )
                }
            }

            Spacer(Modifier.height(22.dp))
            SectionHeading(stringResource(R.string.widget_appearance_heading), palette)
            Card(
                colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color(palette.surface)),
                shape = RoundedCornerShape(22.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.widget_background_opacity), fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(R.string.widget_percent, opacity),
                        color = androidx.compose.ui.graphics.Color(palette.muted),
                        fontSize = 12.sp,
                    )
                    Slider(
                        value = opacity.toFloat(),
                        onValueChange = { opacity = it.toInt().coerceIn(0, 100) },
                        valueRange = 0f..100f,
                        steps = 9,
                    )
                    Box(
                        Modifier.fillMaxWidth().height(88.dp).background(surface, RoundedCornerShape(18.dp))
                            .semantics {
                                contentDescription = previewDescription
                            },
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val preview = remember(palette) {
                                WidgetBitmapRenderer.compass(
                                    context,
                                    MiniCompassPresentation(
                                        PrecipitationPresentationKind.CLEAR,
                                        "12°",
                                        completeHour = true,
                                        knownHorizonMinutes = 60,
                                    ),
                                    palette,
                                    140,
                                ).asImageBitmap()
                            }
                            Image(preview, null, Modifier.size(68.dp))
                            Column(Modifier.padding(start = 10.dp)) {
                                Text(stringResource(R.string.widget_preview_place), fontWeight = FontWeight.Bold)
                                Text(
                                    quietSummary(context, quiet),
                                    color = androidx.compose.ui.graphics.Color(palette.muted),
                                    fontSize = 11.sp,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(22.dp))
            SectionHeading(stringResource(R.string.widget_quiet_hours_heading), palette)
            Card(
                colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color(palette.surface)),
                shape = RoundedCornerShape(22.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.widget_quiet_hours), fontWeight = FontWeight.SemiBold)
                        Text(
                            quietSummary(context, quiet),
                            color = androidx.compose.ui.graphics.Color(palette.muted),
                            fontSize = 12.sp,
                        )
                    }
                    val quietLabel = stringResource(R.string.widget_quiet_hours)
                    val quietState = stringResource(
                        if (quietEnabled) R.string.common_enabled else R.string.common_disabled,
                    )
                    RainAlarmSwitch(
                        quietEnabled,
                        { quietEnabled = it },
                        Modifier.semantics {
                            contentDescription = quietLabel
                            stateDescription = quietState
                        },
                    )
                }
                if (quietEnabled) {
                    HorizontalDivider(color = androidx.compose.ui.graphics.Color(palette.border))
                    TimeRow(stringResource(R.string.widget_quiet_start), quietStart) { quietStart = it }
                    HorizontalDivider(color = androidx.compose.ui.graphics.Color(palette.border))
                    TimeRow(stringResource(R.string.widget_quiet_end), quietEnd) { quietEnd = it }
                    if (!quiet.valid) {
                        Text(
                            stringResource(R.string.widget_quiet_times_differ),
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
            }
            Text(
                stringResource(R.string.widget_quiet_help),
                color = androidx.compose.ui.graphics.Color(palette.muted),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 7.dp),
            )

            Row(
                Modifier.fillMaxWidth().padding(top = 26.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onCancel) { Text(stringResource(android.R.string.cancel)) }
                Button(
                    onClick = {
                        onSave(
                            RainAlarmWidgetConfig(
                                appWidgetId = appWidgetId,
                                locationMode = WidgetLocationMode.FIXED,
                                fixedPlaceId = selectedSavedPlace?.id,
                                primaryContent = primary,
                                backgroundOpacityPercent = opacity,
                                quietHoursEnabled = quietEnabled,
                                quietStartMinuteOfDay = quietStart,
                                quietEndMinuteOfDay = quietEnd,
                                savedPlace = selectedSavedPlace?.let(WidgetSavedPlaceTarget::from),
                            ),
                        )
                    },
                    enabled = valid,
                    modifier = Modifier.padding(start = 8.dp),
                ) { Text(stringResource(R.string.widget_save)) }
            }
        }
    }
}

@Composable
private fun SectionHeading(value: String, palette: WidgetPalette) = Text(
    value,
    color = androidx.compose.ui.graphics.Color(palette.accent),
    fontSize = 12.sp,
    fontWeight = FontWeight.Bold,
    modifier = Modifier.padding(bottom = 9.dp),
)

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, onClick)
        Text(label, modifier = Modifier.padding(start = 6.dp), maxLines = 1)
    }
}

@Composable
private fun TimeRow(label: String, minute: Int, onChanged: (Int) -> Unit) {
    val context = LocalContext.current
    val clock = formatLocalTime(context, minute)
    Row(
        Modifier.fillMaxWidth().clickable {
            TimePickerDialog(
                context,
                { _, hour, minuteOfHour -> onChanged(hour * 60 + minuteOfHour) },
                minute / 60,
                minute % 60,
                DateFormat.is24HourFormat(context),
            ).show()
        }.padding(horizontal = 16.dp, vertical = 13.dp)
            .semantics { contentDescription = "$label, $clock" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Text(clock, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun primaryLabel(value: WidgetPrimaryContent): String = stringResource(
    when (value) {
        WidgetPrimaryContent.SMART -> R.string.widget_content_smart
        WidgetPrimaryContent.RAIN -> R.string.widget_content_rain
        WidgetPrimaryContent.LIGHTNING -> R.string.widget_content_lightning
        WidgetPrimaryContent.TEMPERATURE -> R.string.widget_content_temperature
    },
)

@Composable
private fun quietSummary(context: android.content.Context, quiet: QuietHours): String = if (!quiet.enabled) {
    stringResource(R.string.widget_quiet_off)
} else stringResource(
    R.string.widget_quiet_summary,
    formatLocalTime(context, quiet.startMinuteOfDay),
    formatLocalTime(context, quiet.endMinuteOfDay),
    )

private fun formatLocalTime(context: android.content.Context, minute: Int): String {
    val time = QuietHoursPolicy.localTime(minute)
    val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
    return java.time.format.DateTimeFormatter.ofPattern(pattern, Locale.getDefault()).format(time)
}
