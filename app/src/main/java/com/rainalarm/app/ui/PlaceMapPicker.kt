package com.rainalarm.app.ui

import android.os.Bundle
import android.view.Gravity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.rainalarm.app.R
import com.rainalarm.app.data.PlaceCollectionRules
import com.rainalarm.app.data.PlaceCoordinatePolicy
import com.rainalarm.app.data.PlaceDuplicateFeedbackPolicy
import com.rainalarm.app.data.PlacePickerNamePolicy
import com.rainalarm.app.data.PlaceNameSuggestionRepository
import com.rainalarm.app.data.RadarMapStyle
import com.rainalarm.app.data.SavedPlace
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import kotlinx.coroutines.delay

@Composable
fun PlaceMapPicker(
    initial: SavedPlace?,
    existingPlaces: List<SavedPlace>,
    mapStyle: RadarMapStyle,
    onCancel: () -> Unit,
    onConfirm: (SavedPlace) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val initialLatitude = initial?.latitude ?: 51.5
    val initialLongitude = initial?.longitude ?: -0.1
    var latitude by remember { mutableStateOf(initialLatitude) }
    var longitude by remember { mutableStateOf(initialLongitude) }
    var editedName by remember {
        mutableStateOf(initial?.name ?: PlacePickerNamePolicy.coordinateFallback(
            initialLatitude, initialLongitude,
        ))
    }
    var nameEdited by remember { mutableStateOf(initial != null) }
    var nameRequestToken by remember { mutableIntStateOf(if (initial == null) 1 else 0) }
    var resolvingName by remember { mutableStateOf(false) }
    var duplicateErrorToken by remember { mutableIntStateOf(0) }
    val placeNameRepository = remember { PlaceNameSuggestionRepository() }
    val locale = LocalConfiguration.current.locales[0]
    LaunchedEffect(nameRequestToken, locale.language) {
        if (nameRequestToken == 0) return@LaunchedEffect
        val requestToken = nameRequestToken
        val requestLatitude = latitude
        val requestLongitude = longitude
        resolvingName = true
        val resolved = placeNameRepository.suggestedName(
            requestLatitude, requestLongitude, locale.language,
        )
        if (PlacePickerNamePolicy.shouldApplyResult(requestToken, nameRequestToken, nameEdited)) {
            editedName = resolved
        }
        if (requestToken == nameRequestToken) resolvingName = false
    }
    LaunchedEffect(duplicateErrorToken) {
        if (duplicateErrorToken == 0) return@LaunchedEffect
        delay(PlaceDuplicateFeedbackPolicy.DURATION_MILLIS)
        duplicateErrorToken = 0
    }
    val mapView = remember(mapStyle) {
        SatelliteAmbientCache.configure(context)
        val options = MapLibreMapOptions.createFromAttributes(context, null)
            .textureMode(true)
            .foregroundLoadColor(RadarMapAppearance.loadingBackgroundArgb(mapStyle))
            .logoEnabled(false)
            .attributionEnabled(true)
            .attributionGravity(Gravity.BOTTOM or Gravity.START)
            .attributionMargins(intArrayOf(
                4, 4, 4, 4,
            ))
        MapView(context, options).apply {
            setBackgroundColor(RadarMapAppearance.loadingBackgroundArgb(mapStyle))
            onCreate(Bundle())
        }
    }
    DisposableEffect(mapView, lifecycleOwner) {
        val observer = PickerMapLifecycle(mapView)
        lifecycleOwner.lifecycle.addObserver(observer)
        mapView.getMapAsync { map ->
            map.uiSettings.isRotateGesturesEnabled = false
            map.uiSettings.isTiltGesturesEnabled = false
            map.uiSettings.isAttributionEnabled = true
            map.cameraPosition = CameraPosition.Builder()
                .target(LatLng(initialLatitude, initialLongitude))
                .zoom(initial?.let { 11.0 } ?: 5.5)
                .bearing(0.0)
                .tilt(0.0)
                .build()
            map.setStyle(RadarMapAppearance.styleUrl(mapStyle))
                map.addOnCameraIdleListener {
                map.cameraPosition.target?.let {
                    val changed = kotlin.math.abs(latitude - it.latitude) > 0.000001 ||
                        kotlin.math.abs(longitude - it.longitude) > 0.000001
                    latitude = it.latitude
                    longitude = it.longitude
                    if (changed) {
                        editedName = PlacePickerNamePolicy.coordinateFallback(latitude, longitude)
                        nameEdited = false
                        nameRequestToken++
                    }
                    duplicateErrorToken = 0
                }
            }
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            observer.destroy()
        }
    }

    val palette = LocalRainAlarmPalette.current
    CompositionLocalProvider(LocalContentColor provides palette.text) {
    Column(Modifier.fillMaxSize().background(palette.background)) {
        Row(
            Modifier.fillMaxWidth().background(palette.surface.copy(alpha = 0.94f))
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onCancel) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_cancel))
            }
            Text(stringResource(R.string.picker_title), modifier = Modifier.weight(1f))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
            Canvas(Modifier.align(Alignment.Center).size(42.dp)) {
                val centre = Offset(size.width / 2f, size.height / 2f)
                val colour = Color(0xFF43BEEE)
                drawLine(colour, Offset(centre.x - 18.dp.toPx(), centre.y),
                    Offset(centre.x + 18.dp.toPx(), centre.y), 3.dp.toPx(), StrokeCap.Round)
                drawLine(colour, Offset(centre.x, centre.y - 18.dp.toPx()),
                    Offset(centre.x, centre.y + 18.dp.toPx()), 3.dp.toPx(), StrokeCap.Round)
                drawCircle(colour, radius = 4.dp.toPx(), center = centre)
            }
        }
        Column(
            Modifier.fillMaxWidth()
                .background(palette.surface.copy(alpha = 0.96f))
                .navigationBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.picker_instruction), color = palette.muted)
            Text(stringResource(
                R.string.places_coordinates,
                String.format(locale, "%.4f", latitude),
                String.format(locale, "%.4f", longitude),
            ))
            if (resolvingName) Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(
                    stringResource(R.string.picker_resolving_name),
                    color = palette.muted,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            PlaceNameEditor(
                value = editedName,
                onValueChange = { editedName = it; nameEdited = true; duplicateErrorToken = 0 },
                duplicate = duplicateErrorToken != 0,
            )
            Button(
                onClick = {
                    val name = editedName.trim()
                    val incoming = SavedPlace(name = name, latitude = latitude, longitude = longitude)
                    if (existingPlaces.any { PlaceCoordinatePolicy.nearDuplicate(it, incoming) }) {
                        duplicateErrorToken++
                    }
                    else onConfirm(incoming)
                },
                enabled = PlaceCollectionRules.validName(editedName),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.picker_confirm)) }
        }
    }
    }
}

/** Shared place-name/validation surface for both map picking and Radar long-press saves. */
@Composable
internal fun PlaceNameEditor(
    value: String,
    onValueChange: (String) -> Unit,
    duplicate: Boolean,
    modifier: Modifier = Modifier,
) {
    val valid = PlaceCollectionRules.validName(value)
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(R.string.places_name_label)) },
        singleLine = true,
        isError = duplicate || (value.isNotEmpty() && !valid),
        supportingText = when {
            duplicate -> { { Text(stringResource(R.string.places_duplicate)) } }
            value.isNotEmpty() && !valid -> { { Text(stringResource(R.string.places_name_error)) } }
            else -> null
        },
        modifier = modifier.fillMaxWidth(),
    )
}

private class PickerMapLifecycle(private val mapView: MapView) : DefaultLifecycleObserver {
    private var started = false
    private var resumed = false
    private var destroyed = false

    override fun onStart(owner: LifecycleOwner) {
        if (!destroyed && !started) { mapView.onStart(); started = true }
    }

    override fun onResume(owner: LifecycleOwner) {
        if (destroyed || resumed) return
        if (!started) onStart(owner)
        mapView.onResume()
        resumed = true
    }

    override fun onPause(owner: LifecycleOwner) { if (resumed) { mapView.onPause(); resumed = false } }
    override fun onStop(owner: LifecycleOwner) {
        if (resumed) onPause(owner)
        if (started) { mapView.onStop(); started = false }
    }

    fun destroy() {
        if (destroyed) return
        if (resumed) mapView.onPause()
        if (started) mapView.onStop()
        mapView.onDestroy()
        resumed = false
        started = false
        destroyed = true
    }
}
