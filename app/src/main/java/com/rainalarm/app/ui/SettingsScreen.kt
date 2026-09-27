package com.rainalarm.app.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.rainalarm.app.BuildConfig
import com.rainalarm.app.R
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Language
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selectableGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rainalarm.app.data.RadarProviderKind
import com.rainalarm.app.data.RadarProviderCapabilityResolver
import com.rainalarm.app.data.RadarProviderCoverageState
import com.rainalarm.app.data.SavedPlace
import com.rainalarm.app.domain.GeoPoint
import com.rainalarm.app.data.RadarPlaybackSpeed
import com.rainalarm.app.data.RadarMapLayer
import com.rainalarm.app.data.WindArrowSizePreference
import com.rainalarm.app.data.CoverageMaskDarknessPreference
import com.rainalarm.app.data.NowCardAppearance
import com.rainalarm.app.data.PlaceCollection
import com.rainalarm.app.data.CURRENT_LOCATION_ID
import com.rainalarm.app.data.AppearanceMode
import com.rainalarm.app.data.AppearancePhase
import com.rainalarm.app.data.AppearanceProfile
import com.rainalarm.app.data.AutomaticAppearancePreferences
import com.rainalarm.app.data.ProfileMapAppearance
import com.rainalarm.app.data.NowWeatherMetric
import com.rainalarm.app.alerts.AlertSnapshot
import kotlin.math.roundToInt
import java.util.Date

internal object RainAlarmSwitchGeometry {
    const val trackWidthDp = 52
    const val trackHeightDp = 32
    const val thumbDiameterDp = 28
    const val touchHeightDp = 48
}

/** Settings switches deliberately share the same semantic accent as a selected startup pin. */
internal object RainAlarmSwitchColorPolicy {
    fun activeTrack(accent: Color): Color = accent
}

private val SettingsSurface: Color @Composable get() = LocalRainAlarmPalette.current.surface
private val SettingsSecondary: Color @Composable get() = LocalRainAlarmPalette.current.muted
private val SettingsAccent: Color @Composable get() = LocalRainAlarmPalette.current.accent
private val SettingsBorder: Color @Composable get() = LocalRainAlarmPalette.current.border

internal object AppearanceGridPolicy {
    const val columnCount = 4
    val appColumns: List<AppearanceMode?> = listOf(
        AppearanceMode.DARK, AppearanceMode.LIGHT, AppearanceMode.FOLLOW_SYSTEM, null,
    )
    val mapColumns: List<AppearanceMode?> = listOf(
        AppearanceMode.DARK, AppearanceMode.LIGHT, AppearanceMode.FOLLOW_SYSTEM, AppearanceMode.SLATE,
    )
    val profileMapColumns: List<ProfileMapAppearance?> = listOf(
        ProfileMapAppearance.DARK,
        ProfileMapAppearance.LIGHT,
        ProfileMapAppearance.FOLLOW_APP,
        ProfileMapAppearance.SLATE,
    )
    val cardColumns: List<NowCardAppearance?> = listOf(
        NowCardAppearance.DARK, NowCardAppearance.LIGHT, NowCardAppearance.FOLLOW_APP, NowCardAppearance.SLATE,
    )
}

/** A stable near-track-height thumb in both states, with an accessible 48dp touch target. */
@Composable
private fun RainAlarmSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val position by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "Rain Alarm switch thumb",
    )
    val track = if (checked) RainAlarmSwitchColorPolicy.activeTrack(SettingsAccent)
        else SettingsBorder.copy(alpha = 0.86f)
    val thumb = if (checked) MaterialTheme.colorScheme.onPrimary else SettingsSecondary
    Canvas(
        modifier.size(
            RainAlarmSwitchGeometry.trackWidthDp.dp,
            RainAlarmSwitchGeometry.touchHeightDp.dp,
        ).toggleable(
            value = checked,
            role = Role.Switch,
            onValueChange = onCheckedChange,
        ),
    ) {
        val trackHeight = RainAlarmSwitchGeometry.trackHeightDp.dp.toPx()
        val trackTop = (size.height - trackHeight) / 2f
        drawRoundRect(
            color = track,
            topLeft = Offset(0f, trackTop),
            size = Size(size.width, trackHeight),
            cornerRadius = CornerRadius(trackHeight / 2f),
        )
        val radius = RainAlarmSwitchGeometry.thumbDiameterDp.dp.toPx() / 2f
        val startX = radius + 2.dp.toPx()
        val endX = size.width - radius - 2.dp.toPx()
        drawCircle(
            color = thumb,
            radius = radius,
            center = Offset(startX + (endX - startX) * position, size.height / 2f),
        )
    }
}

@Composable
fun SettingsScreen(
    selectedProvider: RadarProviderKind,
    selectProvider: (RadarProviderKind) -> Unit,
    showLikelySnow: Boolean,
    setShowLikelySnow: (Boolean) -> Unit,
    playbackSpeed: RadarPlaybackSpeed,
    selectPlaybackSpeed: (RadarPlaybackSpeed) -> Unit,
    appAppearance: AppearanceMode,
    selectAppAppearance: (AppearanceMode) -> Unit,
    mapAppearance: AppearanceMode,
    selectMapAppearance: (AppearanceMode) -> Unit,
    compassAppearance: NowCardAppearance,
    selectCompassAppearance: (NowCardAppearance) -> Unit,
    graphAppearance: NowCardAppearance,
    selectGraphAppearance: (NowCardAppearance) -> Unit,
    automaticAppearance: AutomaticAppearancePreferences,
    activeAppearancePhase: AppearancePhase?,
    nextAppearanceSwitchEpochSeconds: Long?,
    setAutomaticAppearanceEnabled: (Boolean) -> Unit,
    setAppearanceProfile: (AppearancePhase, AppearanceProfile) -> Unit,
    resolvedMapAppearance: AppearanceMode,
    enabledMapLayers: Set<RadarMapLayer>,
    windArrowScale: Float,
    selectWindArrowScale: (Float) -> Unit,
    coverageMaskDarkness: Float,
    selectCoverageMaskDarkness: (Float) -> Unit,
    alertSnapshot: AlertSnapshot,
    enableAlerts: () -> Unit,
    disableAlerts: () -> Unit,
    visibleMetrics: Set<NowWeatherMetric>,
    setMetricVisible: (NowWeatherMetric, Boolean) -> Unit,
    message: String? = null,
    selectedPlace: SavedPlace? = null,
    activeProvider: RadarProviderKind? = null,
    activeProviderCoverage: RadarProviderCoverageState? = null,
    activeLanguageName: String,
    openLanguageSelector: () -> Unit,
    showAppTour: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val locale = LocalConfiguration.current.locales[0]
    var permissionMessage by remember { mutableStateOf<String?>(null) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { permissionMessage = null; enableAlerts() }
        else { disableAlerts(); permissionMessage = resources.getString(R.string.settings_notification_denied) }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.settings_subtitle), color = SettingsSecondary,
            modifier = Modifier.padding(top = 4.dp))
        message?.let {
            Text(
                it,
                color = LocalRainAlarmPalette.current.danger,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
        val offStatus = stringResource(R.string.notification_status_off)
        (permissionMessage ?: alertSnapshot.status.takeIf {
            !alertSnapshot.enabled && it.isNotBlank() && it != offStatus
        })?.let {
            Text(it, color = LocalRainAlarmPalette.current.danger,
            fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(24.dp))
        Card(colors = CardDefaults.cardColors(containerColor = SettingsSurface),
            shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.settings_rain_notification), fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f))
                RainAlarmSwitch(checked = alertSnapshot.enabled, onCheckedChange = { enabled ->
                    if (!enabled) { disableAlerts(); permissionMessage = null }
                    else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else { permissionMessage = null; enableAlerts() }
                })
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.settings_provider_heading), color = SettingsAccent, fontSize = 12.sp,
            fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        val providerPoint = selectedPlace?.let { GeoPoint(it.latitude, it.longitude) }
        val providerCapabilities = providerPoint?.let(RadarProviderCapabilityResolver::capabilities)
            .orEmpty().toMutableMap().also { capabilities ->
                val provider = activeProvider ?: return@also
                val current = capabilities[provider] ?: return@also
                val state = activeProviderCoverage ?: return@also
                capabilities[provider] = current.copy(state = state)
            }
        val providerInUse = activeProvider ?: providerPoint?.let {
            RadarProviderCapabilityResolver.providersFor(selectedProvider, it).firstOrNull()
        }
        Card(
            colors = CardDefaults.cardColors(containerColor = SettingsSurface),
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            ProviderPinChoice(
                title = stringResource(R.string.settings_provider_meteo),
                provider = RadarProviderKind.METEOGROUP_REGIONAL,
                pinned = selectedProvider == RadarProviderKind.METEOGROUP_REGIONAL,
                capability = providerCapabilities[RadarProviderKind.METEOGROUP_REGIONAL]?.state,
                activeProvider = providerInUse,
                onClick = { selectProvider(RadarProviderKind.METEOGROUP_REGIONAL) },
            )
            HorizontalDivider(color = SettingsBorder)
            ProviderPinChoice(
                title = stringResource(R.string.settings_provider_opera),
                provider = RadarProviderKind.EUMETNET_OPERA,
                pinned = selectedProvider == RadarProviderKind.EUMETNET_OPERA,
                capability = providerCapabilities[RadarProviderKind.EUMETNET_OPERA]?.state,
                activeProvider = providerInUse,
                onClick = { selectProvider(RadarProviderKind.EUMETNET_OPERA) },
            )
            HorizontalDivider(color = SettingsBorder)
            ProviderPinChoice(
                title = stringResource(R.string.settings_provider_rainviewer),
                provider = RadarProviderKind.OPEN_RAINVIEWER,
                pinned = selectedProvider == RadarProviderKind.OPEN_RAINVIEWER,
                capability = providerCapabilities[RadarProviderKind.OPEN_RAINVIEWER]?.state,
                activeProvider = providerInUse,
                onClick = { selectProvider(RadarProviderKind.OPEN_RAINVIEWER) },
            )
            if (selectedProvider == RadarProviderKind.OPEN_RAINVIEWER) {
                HorizontalDivider(color = SettingsBorder)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.settings_show_snow), modifier = Modifier.weight(1f))
                    RainAlarmSwitch(checked = showLikelySnow, onCheckedChange = setShowLikelySnow)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.settings_indicators_heading), color = SettingsAccent, fontSize = 12.sp,
            fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Card(colors = CardDefaults.cardColors(containerColor = SettingsSurface),
            shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
            NowWeatherMetric.entries.forEachIndexed { index, metric ->
                if (index > 0) HorizontalDivider(color = SettingsBorder)
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(localizedMetricLabel(metric), modifier = Modifier.weight(1f))
                    RainAlarmSwitch(checked = metric in visibleMetrics,
                        onCheckedChange = { setMetricVisible(metric, it) })
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.settings_animation_heading), color = SettingsAccent, fontSize = 12.sp,
            fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = SettingsSurface),
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth().semantics { selectableGroup() },
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.settings_playback_speed), fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.settings_playback_help), color = SettingsSecondary,
                    fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly) {
                    RadarPlaybackSpeed.entries.forEach { speed ->
                        androidx.compose.material3.FilterChip(
                            selected = speed == playbackSpeed,
                            onClick = { selectPlaybackSpeed(speed) },
                            label = { Text(speed.label) },
                        )
                    }
                }
            }
        }
        if (RadarMapLayer.WIND in enabledMapLayers) {
            Spacer(Modifier.height(14.dp))
            Card(colors = CardDefaults.cardColors(containerColor = SettingsSurface),
                shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.settings_wind_size), fontWeight = FontWeight.SemiBold)
                    var previewScale by remember(windArrowScale) { mutableStateOf(windArrowScale) }
                    WindArrowPreview(previewScale)
                    Slider(
                        value = previewScale,
                        onValueChange = { previewScale = it },
                        onValueChangeFinished = { selectWindArrowScale(previewScale) },
                        valueRange = WindArrowSizePreference.MIN..WindArrowSizePreference.MAX,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("${String.format(locale, "%.1f", previewScale)}×",
                        color = SettingsSecondary, fontSize = 13.sp)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.settings_appearance_heading), color = SettingsAccent, fontSize = 12.sp,
            fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Card(colors = CardDefaults.cardColors(containerColor = SettingsSurface),
            shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_automatic_appearance), fontWeight = FontWeight.SemiBold)
                    val nextText = nextAppearanceSwitchEpochSeconds?.let {
                        android.text.format.DateFormat.getTimeFormat(context).format(Date(it * 1_000L))
                    }
                    Text(
                        when {
                            !automaticAppearance.enabled -> stringResource(R.string.settings_auto_off)
                            nextText != null -> stringResource(
                                R.string.settings_auto_active_next,
                                localizedPhase(activeAppearancePhase),
                                nextText,
                            )
                            else -> stringResource(
                                R.string.settings_auto_active,
                                localizedPhase(activeAppearancePhase),
                            )
                        },
                        color = SettingsSecondary,
                        fontSize = 12.sp,
                    )
                }
                RainAlarmSwitch(checked = automaticAppearance.enabled,
                    onCheckedChange = setAutomaticAppearanceEnabled)
            }
            if (automaticAppearance.enabled) {
                HorizontalDivider(color = SettingsBorder)
                AppearanceProfileEditor(
                    title = stringResource(R.string.settings_day_profile),
                    profile = automaticAppearance.day,
                    active = activeAppearancePhase == AppearancePhase.DAY,
                    onChange = { setAppearanceProfile(AppearancePhase.DAY, it) },
                )
                HorizontalDivider(color = SettingsBorder)
                AppearanceProfileEditor(
                    title = stringResource(R.string.settings_night_profile),
                    profile = automaticAppearance.night,
                    active = activeAppearancePhase == AppearancePhase.NIGHT,
                    onChange = { setAppearanceProfile(AppearancePhase.NIGHT, it) },
                )
            }
        }
        if (!automaticAppearance.enabled) {
            Spacer(Modifier.height(12.dp))
            Card(colors = CardDefaults.cardColors(containerColor = SettingsSurface),
                shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                AppearanceChoice(stringResource(R.string.settings_app), appAppearance,
                    AppearanceGridPolicy.appColumns, selectAppAppearance)
                HorizontalDivider(color = SettingsBorder)
                AppearanceChoice(stringResource(R.string.settings_map), mapAppearance,
                    AppearanceGridPolicy.mapColumns, selectMapAppearance)
            }
            Spacer(Modifier.height(12.dp))
            Card(colors = CardDefaults.cardColors(containerColor = SettingsSurface),
                shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                NowCardAppearanceChoice(stringResource(R.string.settings_compass_card), compassAppearance,
                    selectCompassAppearance)
                HorizontalDivider(color = SettingsBorder)
                NowCardAppearanceChoice(stringResource(R.string.settings_graph_card), graphAppearance,
                    selectGraphAppearance)
            }
            Text(stringResource(R.string.settings_manual_appearance_help),
                color = SettingsSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }
        Spacer(Modifier.height(12.dp))
        CoverageMaskDarknessControl(
            mapAppearance = resolvedMapAppearance,
            coverageMaskDarkness = coverageMaskDarkness,
            selectCoverageMaskDarkness = selectCoverageMaskDarkness,
        )
        Spacer(Modifier.height(24.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = SettingsSurface),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth().clickable(onClick = showAppTour)
                .semantics {
                    contentDescription = resources.getString(R.string.settings_show_app_tour_description)
                },
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(stringResource(R.string.settings_show_app_tour), fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.settings_show_app_tour_help), color = SettingsSecondary,
                    fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = SettingsSurface),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth().clickable(onClick = openLanguageSelector)
                .semantics {
                    contentDescription = resources.getString(
                        R.string.settings_language_description,
                        activeLanguageName,
                    )
                },
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Language, contentDescription = null)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(stringResource(R.string.settings_language), fontWeight = FontWeight.SemiBold)
                    Text(activeLanguageName, color = SettingsSecondary, fontSize = 13.sp)
                }
                Icon(Icons.Default.ChevronRight, contentDescription = null)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                color = SettingsSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
            TextButton(
                onClick = {
                    if (!openExternalUrl(context, "https://github.com/Undert0e-505/RainAlarm")) {
                        permissionMessage = resources.getString(R.string.settings_no_browser)
                    }
                },
                modifier = Modifier.semantics {
                    contentDescription = resources.getString(R.string.settings_source_description)
                },
            ) { Text(stringResource(R.string.settings_source_code)) }
        }
        var aboutExpanded by rememberSaveable { mutableStateOf(false) }
        Spacer(Modifier.height(8.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = SettingsSurface),
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier.fillMaxWidth().clickable { aboutExpanded = !aboutExpanded }
                    .semantics {
                        stateDescription = resources.getString(
                            if (aboutExpanded) R.string.common_expanded else R.string.common_collapsed,
                        )
                        contentDescription = resources.getString(
                            if (aboutExpanded) R.string.settings_about_collapse
                            else R.string.settings_about_expand,
                        )
                    }
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.settings_about_data), fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f))
                Icon(if (aboutExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null)
            }
            if (aboutExpanded) {
                HorizontalDivider(color = SettingsBorder)
                Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                    AboutSourceSection(
                        stringResource(R.string.settings_radar_sources),
                        stringResource(R.string.settings_about_radar_body),
                        listOf(
                            "MeteoGroup / DTN" to "https://consumer.dtn.com/hc/en-gb/articles/204759702-What-kind-of-data-do-I-see-in-WeatherPro",
                            "EUMETNET OPERA" to "https://www.eumetnet.eu/activities/observations-programme/current-activities/opera/",
                            "RainViewer" to "https://www.rainviewer.com/api.html",
                        ),
                        onOpenFailure = { permissionMessage = resources.getString(R.string.settings_no_browser) },
                    )
                    AboutSourceSection(
                        stringResource(R.string.settings_map_source),
                        stringResource(R.string.settings_about_map_body),
                        listOf(
                            "OpenStreetMap" to "https://www.openstreetmap.org/copyright",
                            "OpenFreeMap" to "https://openfreemap.org/quick_start/",
                            "OpenMapTiles" to "https://openmaptiles.org/license/",
                        ),
                        onOpenFailure = { permissionMessage = resources.getString(R.string.settings_no_browser) },
                    )
                    AboutSourceSection(
                        stringResource(R.string.settings_model_source),
                        stringResource(R.string.settings_about_model_body),
                        listOf("Open-Meteo" to "https://open-meteo.com/en/terms"),
                        onOpenFailure = { permissionMessage = resources.getString(R.string.settings_no_browser) },
                    )
                    AboutSourceSection(
                        stringResource(R.string.settings_satellite_source),
                        stringResource(R.string.settings_about_satellite_body),
                        listOf("EUMETSAT" to "https://www.eumetsat.int/terms-use"),
                        onOpenFailure = { permissionMessage = resources.getString(R.string.settings_no_browser) },
                    )
                    AboutSourceSection(
                        stringResource(R.string.settings_search_source),
                        stringResource(R.string.settings_about_search_body),
                        listOf(
                            "Open-Meteo" to "https://open-meteo.com/en/terms",
                            "Photon" to "https://photon.komoot.io/",
                            "Wikipedia / Wikimedia" to "https://foundation.wikimedia.org/wiki/Policy:Terms_of_Use",
                            "postcodes.io" to "https://postcodes.io/docs/licences/",
                        ),
                        onOpenFailure = { permissionMessage = resources.getString(R.string.settings_no_browser) },
                    )
                    AboutSourceSection(
                        stringResource(R.string.settings_privacy_source),
                        stringResource(R.string.settings_about_privacy_body),
                        listOf("Rain Alarm" to "https://github.com/Undert0e-505/RainAlarm#privacy-and-permissions"),
                        onOpenFailure = { permissionMessage = resources.getString(R.string.settings_no_browser) },
                    )
                    Text(stringResource(R.string.settings_safety_note), color = SettingsAccent,
                        fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}

private fun openExternalUrl(context: android.content.Context, url: String): Boolean {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching { context.startActivity(intent); true }.getOrDefault(false)
}

@Composable
private fun AboutSourceSection(
    title: String,
    description: String,
    links: List<Pair<String, String>>,
    onOpenFailure: () -> Unit,
) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
        Text(description, color = SettingsSecondary, fontSize = 13.sp, lineHeight = 18.sp,
            modifier = Modifier.padding(top = 3.dp))
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
            links.forEach { (label, url) ->
                val linkDescription = stringResource(R.string.settings_open_source_link, label)
                TextButton(
                    onClick = { if (!openExternalUrl(context, url)) onOpenFailure() },
                    modifier = Modifier.semantics { contentDescription = linkDescription },
                ) {
                    Text(label)
                }
            }
        }
    }
}

@Composable
private fun AppearanceProfileEditor(
    title: String,
    profile: AppearanceProfile,
    active: Boolean,
    onChange: (AppearanceProfile) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (active) Text(stringResource(R.string.common_active_label), color = SettingsAccent,
                fontSize = 12.sp)
        }
        AppearanceProfilePreview(title, profile)
        Text(stringResource(R.string.settings_app), color = SettingsSecondary, fontSize = 12.sp,
            modifier = Modifier.padding(top = 8.dp))
        AppearanceGrid(
            listOf(AppearanceMode.DARK, AppearanceMode.LIGHT, null, null),
            profile.app,
            { localizedAppearanceLabel(it) },
        ) { onChange(profile.copy(app = it)) }
        Text(stringResource(R.string.settings_map), color = SettingsSecondary, fontSize = 12.sp,
            modifier = Modifier.padding(top = 6.dp))
        AppearanceGrid(AppearanceGridPolicy.profileMapColumns, profile.map, { localizedProfileMapLabel(it) }) {
            onChange(profile.copy(map = it))
        }
        Text(stringResource(R.string.settings_compass), color = SettingsSecondary, fontSize = 12.sp,
            modifier = Modifier.padding(top = 6.dp))
        AppearanceGrid(AppearanceGridPolicy.cardColumns, profile.compass, { localizedCardLabel(it) }) {
            onChange(profile.copy(compass = it))
        }
        Text(stringResource(R.string.settings_graph), color = SettingsSecondary, fontSize = 12.sp,
            modifier = Modifier.padding(top = 6.dp))
        AppearanceGrid(AppearanceGridPolicy.cardColumns, profile.graph, { localizedCardLabel(it) }) {
            onChange(profile.copy(graph = it))
        }
    }
}

@Composable
private fun AppearanceProfilePreview(title: String, profile: AppearanceProfile) {
    val appDark = profile.app == AppearanceMode.DARK
    val appColour = if (appDark) Color(0xFF101214) else Color(0xFFF6F7FA)
    val mapColour = when (profile.map) {
        ProfileMapAppearance.FOLLOW_APP -> appColour
        ProfileMapAppearance.LIGHT -> Color(0xFFE8EDF2)
        ProfileMapAppearance.DARK -> Color(0xFF222936)
        ProfileMapAppearance.SLATE -> Color(0xFF45516E)
    }
    fun cardColour(mode: NowCardAppearance): Color = when (mode) {
        NowCardAppearance.FOLLOW_APP -> appColour
        NowCardAppearance.LIGHT -> Color(0xFFF5F5F7)
        NowCardAppearance.DARK -> Color(0xFF191B20)
        NowCardAppearance.SLATE -> Color(0xFF4A536C)
    }
    val shape = RoundedCornerShape(8.dp)
    val previewDescription = stringResource(R.string.settings_profile_preview, title)
    Row(
        Modifier.fillMaxWidth().padding(top = 7.dp).height(24.dp)
            .clip(shape).border(1.dp, SettingsBorder, shape)
            .semantics { contentDescription = previewDescription },
    ) {
        listOf(
            appColour,
            mapColour,
            cardColour(profile.compass),
            cardColour(profile.graph),
        ).forEach { colour ->
            Box(Modifier.weight(1f).fillMaxSize().background(colour))
        }
    }
}

@Composable
private fun NowCardAppearanceChoice(title: String, selected: NowCardAppearance,
    onSelect: (NowCardAppearance) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold)
        AppearanceGrid(AppearanceGridPolicy.cardColumns, selected, { localizedCardLabel(it) }, onSelect)
    }
}

@Composable
private fun WindArrowPreview(scale: Float) {
    val colour = SettingsAccent
    val previewDescription = stringResource(R.string.settings_wind_preview)
    Canvas(Modifier.fillMaxWidth().height(90.dp).semantics {
        contentDescription = previewDescription
    }) {
        val centre = Offset(size.width / 2f, size.height / 2f)
        val length = WindArrowGeometry.length(scale)
        val back = WindArrowGeometry.headBack(scale)
        val half = WindArrowGeometry.headHalfWidth(scale)
        val start = Offset(centre.x, centre.y + length / 2f)
        val end = Offset(centre.x, centre.y - length / 2f)
        val stroke = 3.5f * scale
        drawLine(colour, start, end, strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(colour, end, Offset(end.x - half, end.y + back), strokeWidth = stroke,
            cap = StrokeCap.Round)
        drawLine(colour, end, Offset(end.x + half, end.y + back), strokeWidth = stroke,
            cap = StrokeCap.Round)
    }
}

@Composable
private fun CoverageMaskDarknessControl(
    mapAppearance: AppearanceMode,
    coverageMaskDarkness: Float,
    selectCoverageMaskDarkness: (Float) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SettingsSurface),
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            var previewDarkness by remember(coverageMaskDarkness) {
                mutableStateOf(CoverageMaskDarknessPreference.decode(coverageMaskDarkness))
            }
            val previewStyle = mapAppearance.resolveMapStyle(isSystemInDarkTheme())
            val maximumOpacityPercent = (RadarCoverageMaskPolicy.palette(
                previewStyle, CoverageMaskDarknessPreference.MAX,
            ).second * 100).roundToInt()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.settings_mask_darkness), fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f))
                Text("${(previewDarkness * 100).roundToInt()}%", color = SettingsSecondary,
                    fontSize = 13.sp)
            }
            Text(
                stringResource(R.string.settings_mask_help),
                color = SettingsSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 3.dp),
            )
            CoverageMaskDarknessPreview(mapAppearance, previewDarkness)
            Slider(
                value = previewDarkness,
                onValueChange = {
                    previewDarkness = CoverageMaskDarknessPreference.decode(it)
                    selectCoverageMaskDarkness(previewDarkness)
                },
                valueRange = CoverageMaskDarknessPreference.MIN..CoverageMaskDarknessPreference.MAX,
                // Nine internal stops plus the endpoints gives 0, 10, ... 100%.
                steps = 9,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_no_mask), color = SettingsSecondary, fontSize = 12.sp,
                    modifier = Modifier.weight(1f))
                Text(stringResource(R.string.settings_dark_percent, maximumOpacityPercent),
                    color = SettingsSecondary, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun CoverageMaskDarknessPreview(mapAppearance: AppearanceMode, darkness: Float) {
    val style = mapAppearance.resolveMapStyle(isSystemInDarkTheme())
    val (scrimArgb, opacity) = RadarCoverageMaskPolicy.palette(style, darkness)
    val (base, mapDetail) = when (style) {
        com.rainalarm.app.data.RadarMapStyle.DARK -> Color(0xFF222936) to Color(0xFF708096)
        com.rainalarm.app.data.RadarMapStyle.SLATE -> Color(0xFF45516E) to Color(0xFFA9B4C8)
        com.rainalarm.app.data.RadarMapStyle.LIGHT -> Color(0xFFE8EDF2) to Color(0xFF8795A3)
    }
    val shape = RoundedCornerShape(8.dp)
    val previewDescription = stringResource(R.string.settings_mask_preview)
    Row(
        Modifier.fillMaxWidth().padding(top = 9.dp).height(28.dp)
            .clip(shape).border(1.dp, SettingsBorder, shape)
            .semantics { contentDescription = previewDescription },
    ) {
        listOf(false, true).forEach { masked ->
            Box(Modifier.weight(1f).fillMaxSize().background(base)) {
                Canvas(Modifier.fillMaxSize()) {
                    drawLine(
                        mapDetail,
                        Offset(0f, size.height * 0.75f),
                        Offset(size.width, size.height * 0.28f),
                        strokeWidth = 2f,
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        mapDetail.copy(alpha = 0.75f),
                        Offset(size.width * 0.18f, size.height),
                        Offset(size.width * 0.64f, 0f),
                        strokeWidth = 1.5f,
                        cap = StrokeCap.Round,
                    )
                }
                if (masked && opacity > 0f) Box(
                    Modifier.fillMaxSize().background(Color(scrimArgb).copy(alpha = opacity)),
                )
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
        Text(stringResource(R.string.settings_covered), color = SettingsSecondary, fontSize = 11.sp,
            modifier = Modifier.weight(1f))
        Text(stringResource(R.string.settings_outside), color = SettingsSecondary, fontSize = 11.sp)
    }
}

@Composable
private fun localizedAppearanceLabel(mode: AppearanceMode): String = stringResource(when (mode) {
    AppearanceMode.DARK -> R.string.appearance_dark
    AppearanceMode.LIGHT -> R.string.appearance_light
    AppearanceMode.FOLLOW_SYSTEM -> R.string.appearance_follow_system
    AppearanceMode.SLATE -> R.string.appearance_slate
})

@Composable
private fun localizedProfileMapLabel(mode: ProfileMapAppearance): String = stringResource(when (mode) {
    ProfileMapAppearance.FOLLOW_APP -> R.string.appearance_follow_app
    ProfileMapAppearance.LIGHT -> R.string.appearance_light
    ProfileMapAppearance.DARK -> R.string.appearance_dark
    ProfileMapAppearance.SLATE -> R.string.appearance_slate
})

@Composable
private fun localizedCardLabel(mode: NowCardAppearance): String = stringResource(when (mode) {
    NowCardAppearance.FOLLOW_APP -> R.string.appearance_follow_app
    NowCardAppearance.LIGHT -> R.string.appearance_light
    NowCardAppearance.DARK -> R.string.appearance_dark
    NowCardAppearance.SLATE -> R.string.appearance_slate
})

@Composable
private fun localizedMetricLabel(metric: NowWeatherMetric): String = stringResource(when (metric) {
    NowWeatherMetric.TEMPERATURE -> R.string.metric_temperature
    NowWeatherMetric.PRESSURE -> R.string.metric_pressure
    NowWeatherMetric.HUMIDITY -> R.string.metric_humidity
    NowWeatherMetric.UV_INDEX -> R.string.metric_uv
    NowWeatherMetric.WIND -> R.string.metric_wind
})

@Composable
private fun localizedPhase(phase: AppearancePhase?): String = stringResource(
    if (phase == AppearancePhase.NIGHT) R.string.settings_night else R.string.settings_day,
)

@Composable
private fun AppearanceChoice(title: String, selected: AppearanceMode, columns: List<AppearanceMode?>,
    onSelect: (AppearanceMode) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold)
        AppearanceGrid(columns, selected, { localizedAppearanceLabel(it) }, onSelect)
    }
}

@Composable
private fun <T> AppearanceGrid(columns: List<T?>, selected: T, label: @Composable (T) -> String,
    onSelect: (T) -> Unit) {
    require(columns.size == AppearanceGridPolicy.columnCount)
    Row(Modifier.fillMaxWidth(),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(5.dp)) {
        columns.forEach { mode ->
            if (mode == null) Spacer(Modifier.weight(1f))
            else androidx.compose.material3.FilterChip(
                selected = selected == mode,
                onClick = { onSelect(mode) },
                label = {
                    Text(label(mode), fontSize = 11.sp, lineHeight = 13.sp, maxLines = 2,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private fun Double.formatCoordinate(): String = String.format(java.util.Locale.ROOT, "%.3f", this)

internal object RadarProviderSettingsPolicy {
    fun detail(
        pinned: Boolean,
        capability: RadarProviderCoverageState?,
        inUse: Boolean,
    ): String = buildList {
        if (pinned) add("Pinned default")
        when (capability) {
            RadarProviderCoverageState.COVERED -> add("Available here")
            RadarProviderCoverageState.SUPPORTED -> add("Available here")
            RadarProviderCoverageState.UNCOVERED -> add("No published coverage here")
            RadarProviderCoverageState.OUTSIDE_DOMAIN -> add("Outside coverage here")
            null -> Unit
        }
        if (inUse && !pinned) add("In use here")
    }.joinToString(" · ")
}

@Composable
private fun ProviderPinChoice(
    title: String,
    provider: RadarProviderKind,
    pinned: Boolean,
    capability: RadarProviderCoverageState?,
    activeProvider: RadarProviderKind?,
    onClick: () -> Unit,
) {
    val resources = LocalResources.current
    val detail = buildList {
        if (pinned) add(resources.getString(R.string.settings_provider_pinned))
        when (capability) {
            RadarProviderCoverageState.COVERED, RadarProviderCoverageState.SUPPORTED ->
                add(resources.getString(R.string.settings_provider_available))
            RadarProviderCoverageState.UNCOVERED ->
                add(resources.getString(R.string.settings_provider_no_coverage))
            RadarProviderCoverageState.OUTSIDE_DOMAIN ->
                add(resources.getString(R.string.settings_provider_outside))
            null -> Unit
        }
        if (activeProvider == provider && !pinned) {
            add(resources.getString(R.string.settings_provider_in_use))
        }
    }.joinToString(" · ")
    Row(
        Modifier.fillMaxWidth()
            .clickable(role = Role.Button, onClick = { if (!pinned) onClick() })
            .semantics {
                selected = pinned
                contentDescription = buildString {
                    append(title)
                    if (detail.isNotBlank()) append(", ").append(detail)
                    append(", ").append(resources.getString(
                        if (pinned) R.string.settings_provider_pinned_description
                        else R.string.settings_provider_set_description,
                    ))
                }
            }
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            if (detail.isNotBlank()) Text(
                detail,
                color = SettingsSecondary,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Icon(
            if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
            contentDescription = null,
            tint = if (pinned) SettingsAccent else SettingsSecondary,
        )
    }
}
