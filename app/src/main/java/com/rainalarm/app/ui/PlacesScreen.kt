package com.rainalarm.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.spring
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.rainalarm.app.LocationUiState
import com.rainalarm.app.data.PlaceCollection
import com.rainalarm.app.data.PlaceCollectionRules
import com.rainalarm.app.data.LiveLocationSavePolicy
import com.rainalarm.app.data.PlaceGeocoder
import com.rainalarm.app.data.PlaceSearchResult
import com.rainalarm.app.data.SavedPlace
import com.rainalarm.app.data.CURRENT_LOCATION_ID
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

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

internal data class DisplayedPlaceRows(
    val places: List<SavedPlace>,
    val pendingOrder: List<String>?,
    val pendingDeletes: Set<String>,
)

/** A deletion changes the ID set, so it must invalidate any optimistic drag order. */
internal object DisplayedPlaceRowsPolicy {
    fun reconcile(
        collection: PlaceCollection,
        pendingOrder: List<String>?,
        pendingDeletes: Set<String> = emptySet(),
    ): DisplayedPlaceRows {
        val incomingIds = collection.places.map { it.id }
        val matches = pendingOrder != null && incomingIds.toSet() == pendingOrder.toSet()
        val places = if (matches) PlaceCollectionRules.reorder(collection, pendingOrder).places
            else collection.places
        val remainingOrder = if (pendingOrder == incomingIds || !matches) null else pendingOrder
        val awaitingStore = pendingDeletes.intersect(incomingIds.toSet())
        return DisplayedPlaceRows(places.filterNot { it.id in awaitingStore }, remainingOrder, awaitingStore)
    }
}

@Composable
fun PlacesScreen(
    collection: PlaceCollection,
    locationState: LocationUiState,
    useCurrentLocation: () -> Unit,
    saveAndSelect: (SavedPlace) -> Unit,
    selectPlace: (String) -> Unit,
    deletePlace: (String, (Boolean) -> Unit) -> Unit,
    setPinned: (String, Boolean) -> Unit,
    renamePlace: (String, String) -> Unit,
    reorderPlaces: (List<String>) -> Unit,
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
    val geocoder = remember { PlaceGeocoder() }
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

    val listState = rememberLazyListState()
    val displayedPlaces = remember { mutableStateListOf<SavedPlace>().also { it.addAll(collection.places) } }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var pointerY by remember { mutableFloatStateOf(0f) }
    var pendingOrder by remember { mutableStateOf<List<String>?>(null) }
    var pendingDeletes by remember { mutableStateOf<Set<String>>(emptySet()) }
    var renamingId by remember { mutableStateOf<String?>(null) }
    var editedName by remember { mutableStateOf("") }
    val density = LocalDensity.current

    LaunchedEffect(collection.places, draggingId, pendingOrder, pendingDeletes) {
        if (draggingId != null) return@LaunchedEffect
        val reconciled = DisplayedPlaceRowsPolicy.reconcile(collection, pendingOrder, pendingDeletes)
        displayedPlaces.clear()
        displayedPlaces.addAll(reconciled.places)
        pendingOrder = reconciled.pendingOrder
        pendingDeletes = reconciled.pendingDeletes
    }

    fun moveDraggedToPointer() {
        val id = draggingId ?: return
        val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id } ?: return
        val centre = info.offset + dragOffset + info.size / 2f
        val target = listState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
            item.key != id && displayedPlaces.any { it.id == item.key } &&
                centre >= item.offset && centre < item.offset + item.size
        } ?: return
        val from = displayedPlaces.indexOfFirst { it.id == id }
        val to = displayedPlaces.indexOfFirst { it.id == target.key }
        if (from >= 0 && to >= 0 && from != to) {
            val moved = displayedPlaces.removeAt(from)
            displayedPlaces.add(to, moved)
            dragOffset += info.offset - target.offset
        }
    }

    LaunchedEffect(draggingId) {
        while (isActive && draggingId != null) {
            val layout = listState.layoutInfo
            val edge = with(density) { 64.dp.toPx() }
            val step = with(density) { 10.dp.toPx() }
            val scroll = when {
                pointerY < layout.viewportStartOffset + edge -> -step
                pointerY > layout.viewportEndOffset - edge -> step
                else -> 0f
            }
            if (scroll != 0f) {
                val consumed = listState.scrollBy(scroll)
                dragOffset += consumed
                moveDraggedToPointer()
            }
            delay(16)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
    ) {
        item(key = "places-header") {
        Column {
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
                Text("Towns: Open-Meteo · UK postcodes: postcodes.io",
                    color = PlacesSecondary, fontSize = 10.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("Saved places · hold and drag to reorder", color = PlacesSecondary, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        }
        }
        items(displayedPlaces, key = { it.id }) { place ->
            val isDragging = draggingId == place.id
            PlaceRow(
                place = place,
                selected = place.id == collection.selectedId,
                onSelect = { selectPlace(place.id) },
                onDelete = {
                    if (place.id !in pendingDeletes) {
                        pendingDeletes = pendingDeletes + place.id
                        pendingOrder = null
                        displayedPlaces.removeAll { it.id == place.id }
                        deletePlace(place.id) { succeeded ->
                            if (!succeeded) {
                                pendingDeletes = pendingDeletes - place.id
                                message = "Could not delete ${place.name}. Try again."
                            }
                        }
                    }
                },
                onPin = { setPinned(place.id, !place.pinned) },
                onRename = { renamingId = place.id; editedName = place.name },
                modifier = Modifier
                    .animateItem(placementSpec = if (isDragging) null else spring())
                    .zIndex(if (isDragging) 1f else 0f)
                    .graphicsLayer {
                        translationY = if (isDragging) dragOffset else 0f
                        shadowElevation = if (isDragging) 14.dp.toPx() else 0f
                        shape = RoundedCornerShape(18.dp)
                    }
                    .semantics {
                        customActions = listOf(
                            CustomAccessibilityAction("Move ${place.name} up") {
                                val from = displayedPlaces.indexOfFirst { it.id == place.id }
                                if (from <= 0) false else {
                                    val moved = displayedPlaces.removeAt(from)
                                    displayedPlaces.add(from - 1, moved)
                                    pendingOrder = displayedPlaces.map { it.id }
                                    reorderPlaces(requireNotNull(pendingOrder))
                                    true
                                }
                            },
                            CustomAccessibilityAction("Move ${place.name} down") {
                                val from = displayedPlaces.indexOfFirst { it.id == place.id }
                                if (from < 0 || from >= displayedPlaces.lastIndex) false else {
                                    val moved = displayedPlaces.removeAt(from)
                                    displayedPlaces.add(from + 1, moved)
                                    pendingOrder = displayedPlaces.map { it.id }
                                    reorderPlaces(requireNotNull(pendingOrder))
                                    true
                                }
                            },
                        )
                    }
                    .pointerInput(place.id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { start ->
                                draggingId = place.id
                                dragOffset = 0f
                                pointerY = (listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == place.id }?.offset
                                    ?: 0) + start.y
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                dragOffset += amount.y
                                pointerY = (listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == place.id }?.offset
                                    ?: 0) + change.position.y
                                moveDraggedToPointer()
                            },
                            onDragEnd = {
                                val next = displayedPlaces.map { it.id }
                                draggingId = null
                                dragOffset = 0f
                                if (next != collection.places.map { it.id }) {
                                    pendingOrder = next
                                    reorderPlaces(next)
                                }
                            },
                            onDragCancel = {
                                draggingId = null
                                dragOffset = 0f
                                displayedPlaces.clear()
                                displayedPlaces.addAll(DisplayedPlaceRowsPolicy.reconcile(
                                    collection, pendingOrder, pendingDeletes).places)
                            },
                        )
                    },
            )
            Spacer(Modifier.height(8.dp))
        }
        item(key = "places-footer") {
        Column {
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
        val liveSnapshot = if (collection.selectedId == CURRENT_LOCATION_ID) {
            LiveLocationSavePolicy.snapshot((locationState as? LocationUiState.Active)?.place)
        } else null
        if (liveSnapshot != null) {
            OutlinedButton(
                onClick = { saveAndSelect(liveSnapshot) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(48.dp),
            ) {
                Text("Save current location")
            }
        }
        val locationError = when (locationState) {
            is LocationUiState.Active -> locationState.message
            is LocationUiState.Unavailable -> locationState.message
            else -> null
        }
        if (locationError != null) Text(locationError, color = PlacesDanger, fontSize = 12.sp)

        Spacer(Modifier.height(22.dp))
        if (message != null) {
            Text(message!!, color = PlacesDanger, modifier = Modifier.padding(top = 10.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text("Privacy", fontWeight = FontWeight.Bold)
        Text(
            "No accounts, ads, analytics, billing, or background location. Saved-place alerts use stored coordinates; live current coordinates stay in memory unless you explicitly save the current fix.",
            color = PlacesSecondary,
        )
        }
        }
    }

    val renaming = collection.places.firstOrNull { it.id == renamingId }
    if (renaming != null) AlertDialog(
        onDismissRequest = { renamingId = null },
        title = { Text("Rename saved place") },
        text = {
            OutlinedTextField(
                value = editedName,
                onValueChange = { editedName = it },
                label = { Text("Place name") },
                singleLine = true,
                isError = editedName.isNotEmpty() && !PlaceCollectionRules.validName(editedName),
                supportingText = if (!PlaceCollectionRules.validName(editedName)) {
                    { Text("Enter 1–60 characters without control characters") }
                } else null,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { renamePlace(renaming.id, editedName.trim()); renamingId = null },
                enabled = PlaceCollectionRules.validName(editedName),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = { renamingId = null }) { Text("Cancel") } },
    )
}

@Composable
private fun PlaceRow(
    place: SavedPlace,
    selected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    onPin: () -> Unit,
    onRename: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selected) LocalRainAlarmPalette.current.selection else PlacesSurface,
        ),
        shape = RoundedCornerShape(18.dp),
        modifier = modifier.fillMaxWidth().clickable(onClick = onSelect),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.DragHandle, contentDescription = "Hold and drag ${place.name} to reorder", tint = PlacesAccent)
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
            IconButton(onClick = onRename) {
                Icon(Icons.Default.Edit, contentDescription = "Rename ${place.name}")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete ${place.name}")
            }
        }
    }
}
