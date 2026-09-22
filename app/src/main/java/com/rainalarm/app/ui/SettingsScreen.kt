package com.rainalarm.app.ui

import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selectableGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rainalarm.app.data.RadarProviderKind
import com.rainalarm.app.data.RadarPlaybackSpeed
import com.rainalarm.app.data.RadarMapLayer
import com.rainalarm.app.data.WindArrowSizePreference
import com.rainalarm.app.data.NowCardAppearance
import com.rainalarm.app.data.PlaceCollection
import com.rainalarm.app.data.CURRENT_LOCATION_ID
import com.rainalarm.app.data.AppearanceMode
import com.rainalarm.app.data.NowWeatherMetric
import com.rainalarm.app.alerts.AlertSnapshot

private val SettingsSurface: Color @Composable get() = LocalRainAlarmPalette.current.surface
private val SettingsSecondary: Color @Composable get() = LocalRainAlarmPalette.current.muted
private val SettingsAccent: Color @Composable get() = LocalRainAlarmPalette.current.accent
private val SettingsBorder: Color @Composable get() = LocalRainAlarmPalette.current.border

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
    mapLayer: RadarMapLayer,
    windArrowScale: Float,
    selectWindArrowScale: (Float) -> Unit,
    savedPlaces: PlaceCollection,
    defaultStartupId: String,
    setDefaultStartupId: (String) -> Unit,
    alertSnapshot: AlertSnapshot,
    enableAlerts: () -> Unit,
    disableAlerts: () -> Unit,
    visibleMetrics: Set<NowWeatherMetric>,
    setMetricVisible: (NowWeatherMetric, Boolean) -> Unit,
    message: String? = null,
) {
    val context = LocalContext.current
    var permissionMessage by remember { mutableStateOf<String?>(null) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { permissionMessage = null; enableAlerts() }
        else { disableAlerts(); permissionMessage = "Notification permission was not granted." }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        Text("Make the radar yours", color = SettingsSecondary, modifier = Modifier.padding(top = 4.dp))
        message?.let {
            Text(
                it,
                color = LocalRainAlarmPalette.current.danger,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
        (permissionMessage ?: alertSnapshot.status.takeIf { it == "Notification permission needed" })?.let {
            Text(it, color = LocalRainAlarmPalette.current.danger,
            fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(24.dp))
        Card(colors = CardDefaults.cardColors(containerColor = SettingsSurface),
            shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("Rain Notification", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Switch(checked = alertSnapshot.enabled, onCheckedChange = { enabled ->
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
        Text("RADAR DATA", color = SettingsAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = SettingsSurface),
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth().semantics { selectableGroup() },
        ) {
            ProviderChoice(
                title = "MeteoGroup regional",
                selected = selectedProvider == RadarProviderKind.METEOGROUP_REGIONAL,
                onClick = { selectProvider(RadarProviderKind.METEOGROUP_REGIONAL) },
            )
            HorizontalDivider(color = SettingsBorder)
            ProviderChoice(
                title = "Open radar / RainViewer",
                selected = selectedProvider == RadarProviderKind.OPEN_RAINVIEWER,
                onClick = { selectProvider(RadarProviderKind.OPEN_RAINVIEWER) },
            )
            if (selectedProvider == RadarProviderKind.OPEN_RAINVIEWER) {
                HorizontalDivider(color = SettingsBorder)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Show likely snow", modifier = Modifier.weight(1f))
                    Switch(checked = showLikelySnow, onCheckedChange = setShowLikelySnow)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Text("NOW INDICATORS", color = SettingsAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Card(colors = CardDefaults.cardColors(containerColor = SettingsSurface),
            shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
            NowWeatherMetric.entries.forEachIndexed { index, metric ->
                if (index > 0) HorizontalDivider(color = SettingsBorder)
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(metric.label, modifier = Modifier.weight(1f))
                    Switch(checked = metric in visibleMetrics,
                        onCheckedChange = { setMetricVisible(metric, it) })
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Text("STARTUP PLACE", color = SettingsAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Card(colors = CardDefaults.cardColors(containerColor = SettingsSurface),
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth().semantics { selectableGroup() }) {
            ProviderChoice(
                title = "Current location",
                detail = "Live while the app is open; needs a fresh foreground fix. Coordinates are not saved.",
                selected = defaultStartupId == CURRENT_LOCATION_ID,
                onClick = { setDefaultStartupId(CURRENT_LOCATION_ID) },
            )
            savedPlaces.places.forEach { place ->
                HorizontalDivider(color = SettingsBorder)
                ProviderChoice(
                    title = place.name,
                    detail = "Saved place · ${place.latitude.formatCoordinate()}, ${place.longitude.formatCoordinate()}",
                    selected = defaultStartupId == place.id,
                    onClick = { setDefaultStartupId(place.id) },
                )
            }
        }
        Text("This changes the next cold start, not your current selection. Alerts follow the current selection.",
            color = SettingsSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(24.dp))
        Text("ANIMATION", color = SettingsAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = SettingsSurface),
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth().semantics { selectableGroup() },
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Radar playback speed", fontWeight = FontWeight.SemiBold)
                Text("Starts paused. Change speed while playing or scrubbing.", color = SettingsSecondary,
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
        if (mapLayer == RadarMapLayer.WIND) {
            Spacer(Modifier.height(14.dp))
            Card(colors = CardDefaults.cardColors(containerColor = SettingsSurface),
                shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Wind arrow size", fontWeight = FontWeight.SemiBold)
                    var previewScale by remember(windArrowScale) { mutableStateOf(windArrowScale) }
                    WindArrowPreview(previewScale)
                    Slider(
                        value = previewScale,
                        onValueChange = { previewScale = it },
                        onValueChangeFinished = { selectWindArrowScale(previewScale) },
                        valueRange = WindArrowSizePreference.MIN..WindArrowSizePreference.MAX,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("${String.format(java.util.Locale.ROOT, "%.1f", previewScale)}×",
                        color = SettingsSecondary, fontSize = 13.sp)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Text("APPEARANCE", color = SettingsAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Card(colors = CardDefaults.cardColors(containerColor = SettingsSurface),
            shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
            AppearanceChoice("App", appAppearance, selectAppAppearance)
            HorizontalDivider(color = SettingsBorder)
            AppearanceChoice("Map", mapAppearance, selectMapAppearance)
        }
        Spacer(Modifier.height(12.dp))
        Card(colors = CardDefaults.cardColors(containerColor = SettingsSurface),
            shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
            NowCardAppearanceChoice("Compass card", compassAppearance, selectCompassAppearance)
            HorizontalDivider(color = SettingsBorder)
            NowCardAppearanceChoice("Graph card", graphAppearance, selectGraphAppearance)
        }
        Text("App and map can follow the device theme independently. Dark is the default.",
            color = SettingsSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(24.dp))
        Text("ABOUT THE DATA", color = SettingsAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = SettingsSurface),
            shape = RoundedCornerShape(22.dp),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text("Independent rain nowcasting", fontWeight = FontWeight.SemiBold)
                Text(
                    "Regional radar: MeteoGroup/DTN. Open radar: RainViewer. Wind and selected-place model weather: Open-Meteo (CC BY 4.0). Satellite lightning and fog / low-cloud layers: © EUMETSAT (CC BY 4.0). Maps: © OpenStreetMap contributors via OpenFreeMap/OpenMapTiles.",
                    color = SettingsSecondary,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    "Experimental service: data sources can be delayed, unavailable or withdrawn. Never rely on this app alone for safety-critical decisions.",
                    color = SettingsAccent,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    modifier = Modifier.padding(top = 14.dp),
                )
            }
        }
    }
}

@Composable
private fun NowCardAppearanceChoice(title: String, selected: NowCardAppearance,
    onSelect: (NowCardAppearance) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(5.dp)) {
            NowCardAppearance.entries.forEach { mode ->
                androidx.compose.material3.FilterChip(
                    selected = selected == mode,
                    onClick = { onSelect(mode) },
                    label = { Text(mode.label, fontSize = 11.sp, maxLines = 1) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun WindArrowPreview(scale: Float) {
    val colour = SettingsAccent
    Canvas(Modifier.fillMaxWidth().height(90.dp).semantics {
        contentDescription = "Wind arrow size preview"
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
private fun AppearanceChoice(title: String, selected: AppearanceMode, onSelect: (AppearanceMode) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(5.dp)) {
            AppearanceMode.entries.forEach { mode ->
                androidx.compose.material3.FilterChip(
                    selected = selected == mode,
                    onClick = { onSelect(mode) },
                    label = { Text(mode.label, fontSize = 11.sp, maxLines = 2) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun Double.formatCoordinate(): String = String.format(java.util.Locale.ROOT, "%.3f", this)

@Composable
private fun ProviderChoice(
    title: String,
    detail: String? = null,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            detail?.let { Text(it, color = SettingsSecondary, fontSize = 13.sp,
                lineHeight = 18.sp, modifier = Modifier.padding(top = 5.dp)) }
        }
    }
}
