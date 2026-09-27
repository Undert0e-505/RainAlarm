package com.rainalarm.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.rainalarm.app.LocationUiState
import com.rainalarm.app.R
import com.rainalarm.app.data.CURRENT_LOCATION_ID
import com.rainalarm.app.data.PlaceCollection

/** A compact in-place selector. Places remains the separate management destination. */
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
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.any { it }) {
            permissionError = false
            expanded = false
            useCurrentLocation()
        } else {
            permissionError = true
            expanded = true
        }
    }
    val selectedName = if (collection.selectedId == CURRENT_LOCATION_ID) {
        stringResource(R.string.current_location)
    } else collection.selected.name

    Box(modifier.size(48.dp)) {
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(48.dp)) {
            Icon(
                painterResource(R.drawable.ic_place_switch),
                contentDescription = stringResource(R.string.places_switch_description, selectedName),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = {
            expanded = false
            permissionError = false
        }) {
            val currentSelected = collection.selectedId == CURRENT_LOCATION_ID
            DropdownMenuItem(
                text = {
                    Column {
                        Text(stringResource(R.string.current_location))
                        Text(
                            when (locationState) {
                                is LocationUiState.Active -> stringResource(R.string.places_current_available)
                                LocationUiState.Locating -> stringResource(R.string.places_current_locating)
                                is LocationUiState.Unavailable -> stringResource(R.string.places_current_unavailable)
                                LocationUiState.Idle -> stringResource(R.string.places_use_current)
                            },
                            color = LocalRainAlarmPalette.current.muted,
                            fontSize = 11.sp,
                        )
                    }
                },
                trailingIcon = {
                    if (currentSelected) Icon(
                        Icons.Default.Check,
                        contentDescription = stringResource(R.string.selected_now),
                    )
                },
                onClick = {
                    permissionError = false
                    val granted = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.ACCESS_COARSE_LOCATION,
                    ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
                        context, Manifest.permission.ACCESS_FINE_LOCATION,
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) {
                        expanded = false
                        useCurrentLocation()
                    } else permissionLauncher.launch(arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ))
                },
                modifier = Modifier.semantics { selected = currentSelected },
            )
            collection.places.forEach { place ->
                val selectedPlace = place.id == collection.selectedId
                DropdownMenuItem(
                    text = { Text(place.name) },
                    trailingIcon = {
                        if (selectedPlace) Icon(
                            Icons.Default.Check,
                            contentDescription = stringResource(R.string.selected_now),
                        )
                    },
                    onClick = {
                        expanded = false
                        permissionError = false
                        selectPlace(place.id)
                    },
                    modifier = Modifier.semantics { selected = selectedPlace },
                )
            }
            if (permissionError) DropdownMenuItem(
                text = {
                    Text(
                        stringResource(R.string.places_permission_denied),
                        color = LocalRainAlarmPalette.current.danger,
                        fontSize = 12.sp,
                    )
                },
                enabled = false,
                onClick = {},
            )
        }
    }
}
