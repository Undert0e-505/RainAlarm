package com.rainalarm.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.rainalarm.app.LocationUiState
import com.rainalarm.app.data.PlaceCollection
import com.rainalarm.app.data.OpenMeteoGeocoder
import com.rainalarm.app.data.PlaceSearchResult
import com.rainalarm.app.data.SavedPlace
import com.rainalarm.app.data.CURRENT_LOCATION_ID
import java.util.Locale

private val PlacesSurface: Color @Composable get() = LocalRainAlarmPalette.current.surface
private val PlacesBackground: Color @Composable get() = LocalRainAlarmPalette.current.background
private val PlacesSecondary: Color @Composable get() = LocalRainAlarmPalette.current.muted
private val PlacesAccent: Color @Composable get() = LocalRainAlarmPalette.current.accent
private val PlacesDanger: Color @Composable get() = LocalRainAlarmPalette.current.danger

private sealed interface PlaceSearchState {
    data object Idle : PlaceSearchState
    data object Loading : PlaceSearchState
    data class Results(val places: List<PlaceSearchResult>) : PlaceSearchState
    data class Failed(val message: String) : PlaceSearchState
}

@Composable
fun PlacesScreen(
    collection: PlaceCollection,
    locationState: LocationUiState,
    useCurrentLocation: () -> Unit,
    saveAndSelect: (SavedPlace) -> Unit,
    selectPlace: (String) -> Unit,
    deletePlace: (String) -> Unit,
    setPinned: (String, Boolean) -> Unit,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    DisposableEffect(focusManager, keyboard) {
        onDispose {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        }
    }
    var message by remember { mutableStateOf<String?>(null) }
    val geocoder = remember { OpenMeteoGeocoder() }
    var searchQuery by remember { mutableStateOf("") }
    var submittedQuery by remember { mutableStateOf<String?>(null) }
    var searchRequest by remember { mutableIntStateOf(0) }
    var searchState by remember { mutableStateOf<PlaceSearchState>(PlaceSearchState.Idle) }
    LaunchedEffect(submittedQuery, searchRequest) {
        val submitted = submittedQuery ?: return@LaunchedEffect
        searchState = PlaceSearchState.Loading
        searchState = runCatching {
            PlaceSearchState.Results(geocoder.search(submitted, Locale.getDefault().language))
        }.getOrElse { PlaceSearchState.Failed(it.message ?: "Place search failed") }
    }
    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.any { it }) {
            message = null
            useCurrentLocation()
        } else {
            message = "Location permission was not granted. Your saved selection is unchanged."
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Text(
            "Places",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            "Your selected place drives Now, Radar, and the map marker.",
            color = PlacesSecondary,
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Find a town or postcode") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = {
                    submittedQuery = searchQuery.trim()
                    searchRequest++
                },
                enabled = searchQuery.trim().length >= 2 && searchState !is PlaceSearchState.Loading,
                modifier = Modifier.size(48.dp),
            ) {
                if (searchState is PlaceSearchState.Loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.Search, contentDescription = "Search places")
            }
        }
        when (val search = searchState) {
            PlaceSearchState.Idle, PlaceSearchState.Loading -> Unit
            is PlaceSearchState.Failed -> Text(search.message, color = PlacesDanger, fontSize = 12.sp)
            is PlaceSearchState.Results -> {
                if (search.places.isEmpty()) Text("No matching places", color = PlacesSecondary)
                search.places.take(5).forEach { result ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(result.name, fontWeight = FontWeight.SemiBold)
                            Text(result.detail, color = PlacesSecondary, fontSize = 11.sp)
                        }
                        Button(onClick = {
                            focusManager.clearFocus(force = true)
                            keyboard?.hide()
                            saveAndSelect(result.toSavedPlace())
                            searchQuery = ""
                            submittedQuery = null
                            searchState = PlaceSearchState.Idle
                        }) { Text("Add & select") }
                    }
                }
                Text("Search: Open-Meteo / GeoNames", color = PlacesSecondary, fontSize = 10.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
        collection.places.forEach { place ->
            PlaceRow(
                place = place,
                selected = place.id == collection.selectedId,
                onSelect = { selectPlace(place.id) },
                onDelete = { deletePlace(place.id) },
                onPin = { setPinned(place.id, !place.pinned) },
            )
            Spacer(Modifier.height(8.dp))
        }
        Button(
            onClick = {
                val granted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                    ) == PackageManager.PERMISSION_GRANTED
                if (granted) {
                    useCurrentLocation()
                } else {
                    locationPermission.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        ),
                    )
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = PlacesAccent,
                contentColor = PlacesBackground,
            ),
        ) {
            if (locationState is LocationUiState.Locating) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                Text(" Retry live current location")
            } else {
                Icon(Icons.Default.MyLocation, contentDescription = null)
                Text(" Use live current location")
            }
        }
        if (collection.selectedId == CURRENT_LOCATION_ID) {
            Text("Selected: live current location · not saved", color = PlacesAccent,
                fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
        }
        val locationError = (locationState as? LocationUiState.Unavailable)?.message
        if (locationError != null) Text(locationError, color = PlacesDanger, fontSize = 12.sp)

        Spacer(Modifier.height(22.dp))
        if (message != null) {
            Text(message!!, color = PlacesDanger, modifier = Modifier.padding(top = 10.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text("Privacy", fontWeight = FontWeight.Bold)
        Text(
            "No accounts, ads, analytics, billing, or background location. Saved-place alerts use stored coordinates; live current coordinates stay in memory only.",
            color = PlacesSecondary,
        )
    }
}

@Composable
private fun PlaceRow(
    place: SavedPlace,
    selected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    onPin: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selected) LocalRainAlarmPalette.current.selection else PlacesSurface,
        ),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.LocationOn, contentDescription = null, tint = PlacesAccent)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(place.name, fontWeight = FontWeight.Bold)
                Text(
                    String.format(
                        Locale.ROOT,
                        "%.4f, %.4f%s",
                        place.latitude,
                        place.longitude,
                        if (place.isCurrentLocation) " - current fix" else "",
                    ),
                    color = PlacesSecondary,
                    fontSize = 12.sp,
                )
            }
            IconButton(onClick = onPin) {
                Icon(
                    if (place.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                    contentDescription = if (place.pinned) "Unpin ${place.name}" else "Pin ${place.name}",
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete ${place.name}")
            }
        }
    }
}
