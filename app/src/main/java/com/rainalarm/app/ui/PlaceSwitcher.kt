package com.rainalarm.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.rainalarm.app.LocationUiState
import com.rainalarm.app.data.CURRENT_LOCATION_ID
import com.rainalarm.app.data.PlaceCollection
import com.rainalarm.app.data.LocationFixQuality
import com.rainalarm.app.R

/** Same selection semantics on Now and Radar; Current remains a virtual, unsaved place. */
@Composable
fun PlaceSwitcher(
    collection: PlaceCollection,
    locationState: LocationUiState,
    selectPlace: (String) -> Unit,
    useCurrentLocation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var permissionError by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) {
            permissionError = false
            useCurrentLocation()
        } else {
            permissionError = true
            expanded = true
        }
    }
    val requestCurrent = {
        val granted = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        if (granted) useCurrentLocation() else permissionLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
        )
    }
    val currentLabel = when (locationState) {
        LocationUiState.Locating -> "Current location · locating"
        is LocationUiState.Active -> when (locationState.quality) {
            LocationFixQuality.ACCURATE -> "Current location"
            LocationFixQuality.WEAK -> "Current location · weak signal"
            LocationFixQuality.PROVISIONAL -> "Current location · provisional"
            LocationFixQuality.APPROXIMATE -> "Current location · approximate"
        }
        is LocationUiState.Unavailable -> "Current location · unavailable"
        LocationUiState.Idle -> "Current location"
    }
    androidx.compose.foundation.layout.Box(modifier) {
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(48.dp)) {
            Icon(painterResource(R.drawable.ic_place_switch),
                contentDescription = "Switch place, selected ${if (collection.selectedId == CURRENT_LOCATION_ID) "Current location" else collection.selected.name}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(currentLabel) },
                onClick = { expanded = false; requestCurrent() },
                trailingIcon = if (collection.selectedId == CURRENT_LOCATION_ID) {
                    { Icon(Icons.Default.Check, contentDescription = "Selected") }
                } else null,
            )
            collection.places.forEach { place ->
                DropdownMenuItem(
                    text = { Text(place.name, maxLines = 1) },
                    onClick = { expanded = false; selectPlace(place.id) },
                    trailingIcon = if (collection.selectedId == place.id) {
                        { Icon(Icons.Default.Check, contentDescription = "Selected") }
                    } else null,
                )
            }
            if (permissionError) DropdownMenuItem(
                text = { Text("Location permission denied; place unchanged") },
                onClick = { expanded = false },
                enabled = false,
            )
        }
    }
}
