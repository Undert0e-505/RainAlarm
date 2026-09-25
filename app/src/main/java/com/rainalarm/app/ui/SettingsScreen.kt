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
import com.rainalarm.app.BuildConfig
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
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
import androidx.compose.ui.semantics.selected
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
import com.rainalarm.app.data.NowWeatherMetric
import com.rainalarm.app.alerts.AlertSnapshot
import kotlin.math.roundToInt

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
    val cardColumns: List<NowCardAppearance?> = listOf(
        NowCardAppearance.DARK, NowCardAppearance.LIGHT, NowCardAppearance.FOLLOW_APP, NowCardAppearance.SLATE,
    )
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
    enabledMapLayers: Set<RadarMapLayer>,
    windArrowScale: Float,
    selectWindArrowScale: (Float) -> Unit,
    coverageMaskDarkness: Float,
    selectCoverageMaskDarkness: (Float) -> Unit,
    savedPlaces: PlaceCollection,
    defaultStartupId: String,
    setDefaultStartupId: (String) -> Unit,
    alertSnapshot: AlertSnapshot,
    enableAlerts: () -> Unit,
    disableAlerts: () -> Unit,
    visibleMetrics: Set<NowWeatherMetric>,
    setMetricVisible: (NowWeatherMetric, Boolean) -> Unit,
    message: String? = null,
    selectedPlace: SavedPlace? = null,
    activeProvider: RadarProviderKind? = null,
    activeProviderCoverage: RadarProviderCoverageState? = null,
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
        Text("PREFERRED RADAR PROVIDER", color = SettingsAccent, fontSize = 12.sp,
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
                title = "MeteoGroup regional (recommended)",
                provider = RadarProviderKind.METEOGROUP_REGIONAL,
                pinned = selectedProvider == RadarProviderKind.METEOGROUP_REGIONAL,
                capability = providerCapabilities[RadarProviderKind.METEOGROUP_REGIONAL]?.state,
                activeProvider = providerInUse,
                onClick = { selectProvider(RadarProviderKind.METEOGROUP_REGIONAL) },
            )
            HorizontalDivider(color = SettingsBorder)
            ProviderPinChoice(
                title = "Open European / OPERA",
                provider = RadarProviderKind.EUMETNET_OPERA,
                pinned = selectedProvider == RadarProviderKind.EUMETNET_OPERA,
                capability = providerCapabilities[RadarProviderKind.EUMETNET_OPERA]?.state,
                activeProvider = providerInUse,
                onClick = { selectProvider(RadarProviderKind.EUMETNET_OPERA) },
            )
            HorizontalDivider(color = SettingsBorder)
            ProviderPinChoice(
                title = "Open radar / RainViewer",
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
                detail = "Foreground fused/GPS fixes while open; Follow needs Precise location. Coordinates are not saved.",
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
        if (RadarMapLayer.WIND in enabledMapLayers) {
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
            AppearanceChoice("App", appAppearance, AppearanceGridPolicy.appColumns, selectAppAppearance)
            HorizontalDivider(color = SettingsBorder)
            AppearanceChoice("Map", mapAppearance, AppearanceGridPolicy.mapColumns, selectMapAppearance)
            HorizontalDivider(color = SettingsBorder)
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                var previewDarkness by remember(coverageMaskDarkness) {
                    mutableStateOf(CoverageMaskDarknessPreference.decode(coverageMaskDarkness))
                }
                val previewStyle = mapAppearance.resolveMapStyle(isSystemInDarkTheme())
                val maximumOpacityPercent = (RadarCoverageMaskPolicy.palette(
                    previewStyle, CoverageMaskDarknessPreference.MAX,
                ).second * 100).roundToInt()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Coverage mask darkness", fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f))
                    Text("${(previewDarkness * 100).roundToInt()}%", color = SettingsSecondary,
                        fontSize = 13.sp)
                }
                Text(
                    "Darkens areas outside known radar coverage for every provider.",
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
                    Text("No mask", color = SettingsSecondary, fontSize = 12.sp,
                        modifier = Modifier.weight(1f))
                    Text("Dark ($maximumOpacityPercent%)", color = SettingsSecondary,
                        fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Card(colors = CardDefaults.cardColors(containerColor = SettingsSurface),
            shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
            NowCardAppearanceChoice("Compass card", compassAppearance, selectCompassAppearance)
            HorizontalDivider(color = SettingsBorder)
            NowCardAppearanceChoice("Graph card", graphAppearance, selectGraphAppearance)
        }
        Text("App and map can follow the device theme independently. Dark is the default. Map and cards offer Slate; cards can follow the app.",
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
                    "Regional radar: MeteoGroup/DTN. Fixed nominal coverage envelopes use national radar-network information from the Met Office and Met Éireann, KNMI, RMI, DWD, Météo-France and MeteoSwiss, cross-checked with EUMETNET. Contains public sector information licensed under the Open Government Licence v3.0; Met Éireann radar open data is CC BY 4.0. These envelopes approximate structural reach, not DTN-published masks or live availability. European open radar: EUMETNET OPERA Open Radar Data (CC BY 4.0). Worldwide open radar: RainViewer. OPERA and RainViewer future frames are app-labelled motion estimates, not provider forecasts. Wind and selected-place model weather: Open-Meteo (CC BY 4.0). Town search: Open-Meteo/GeoNames. Named-place search: Photon using © OpenStreetMap contributors data, with geocoded notable-place fallback from Wikipedia/Wikimedia. UK postcode lookup: postcodes.io using OS OpenData; contains Ordnance Survey, Crown, Royal Mail and National Statistics database rights. Satellite Lightning and daytime/nighttime Clouds imagery: © EUMETSAT (CC BY 4.0). Clouds show cloud structures or fog / low cloud, not confirmed surface fog. Maps: © OpenStreetMap contributors via OpenFreeMap/OpenMapTiles.",
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
                Text(
                    "Version ${BuildConfig.VERSION_NAME}",
                    color = SettingsSecondary,
                    fontSize = 12.sp,
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
        AppearanceGrid(AppearanceGridPolicy.cardColumns, selected, { it.label }, onSelect)
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
private fun CoverageMaskDarknessPreview(mapAppearance: AppearanceMode, darkness: Float) {
    val style = mapAppearance.resolveMapStyle(isSystemInDarkTheme())
    val (scrimArgb, opacity) = RadarCoverageMaskPolicy.palette(style, darkness)
    val (base, mapDetail) = when (style) {
        com.rainalarm.app.data.RadarMapStyle.DARK -> Color(0xFF222936) to Color(0xFF708096)
        com.rainalarm.app.data.RadarMapStyle.SLATE -> Color(0xFF45516E) to Color(0xFFA9B4C8)
        com.rainalarm.app.data.RadarMapStyle.LIGHT -> Color(0xFFE8EDF2) to Color(0xFF8795A3)
    }
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier.fillMaxWidth().padding(top = 9.dp).height(28.dp)
            .clip(shape).border(1.dp, SettingsBorder, shape)
            .semantics { contentDescription = "Coverage mask darkness preview" },
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
        Text("Covered", color = SettingsSecondary, fontSize = 11.sp,
            modifier = Modifier.weight(1f))
        Text("Outside", color = SettingsSecondary, fontSize = 11.sp)
    }
}

@Composable
private fun AppearanceChoice(title: String, selected: AppearanceMode, columns: List<AppearanceMode?>,
    onSelect: (AppearanceMode) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold)
        AppearanceGrid(columns, selected, { it.label }, onSelect)
    }
}

@Composable
private fun <T> AppearanceGrid(columns: List<T?>, selected: T, label: (T) -> String,
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
    val detail = RadarProviderSettingsPolicy.detail(
        pinned, capability, activeProvider == provider,
    )
    Row(
        Modifier.fillMaxWidth()
            .clickable(role = Role.Button, onClick = { if (!pinned) onClick() })
            .semantics {
                selected = pinned
                contentDescription = buildString {
                    append(title)
                    if (detail.isNotBlank()) append(", ").append(detail)
                    append(if (pinned) ", pinned as preferred radar provider"
                    else ", set as preferred radar provider")
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
