package com.rainalarm.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.rainalarm.app.LocationUiState
import com.rainalarm.app.R
import com.rainalarm.app.data.PlaceCollection
import com.rainalarm.app.data.PlaceCollectionRules
import com.rainalarm.app.data.PlaceCoordinatePolicy
import com.rainalarm.app.data.PlaceDuplicateFeedbackPolicy
import com.rainalarm.app.data.LiveLocationSavePolicy
import com.rainalarm.app.data.LiveLocationMatchPolicy
import com.rainalarm.app.data.PlaceGeocoder
import com.rainalarm.app.data.PlaceSearchResult
import com.rainalarm.app.data.SavedPlace
import com.rainalarm.app.data.CURRENT_LOCATION_ID
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private val PlacesSurface: Color @Composable get() = LocalRainAlarmPalette.current.surface
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

internal object PlacesTopControlGeometry {
    const val heightDp = 56
    const val cornerRadiusDp = 16
    const val outlineWidthDp = 1
}

@Composable
fun PlacesScreen(
    collection: PlaceCollection,
    defaultStartupId: String,
    locationState: LocationUiState,
    useCurrentLocation: () -> Unit,
    saveAndSelect: (SavedPlace) -> Unit,
    selectPlace: (String) -> Unit,
    deletePlace: (String, (Boolean) -> Unit) -> Unit,
    setDefaultStartupId: (String) -> Unit,
    renamePlace: (String, String) -> Unit,
    reorderPlaces: (List<String>) -> Unit,
    chooseOnMap: () -> Unit,
    screenActive: Boolean = true,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val locale = LocalConfiguration.current.locales[0]
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun dismissSearchInput() {
        focusManager.clearFocus(force = true)
        keyboard?.hide()
    }
    DisposableEffect(focusManager, keyboard) {
        onDispose {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        }
    }
    LaunchedEffect(screenActive) {
        if (!screenActive) dismissSearchInput()
    }
    var message by remember { mutableStateOf<String?>(null) }
    val geocoder = remember { PlaceGeocoder() }
    var searchQuery by remember { mutableStateOf("") }
    var submittedQuery by remember { mutableStateOf<String?>(null) }
    var searchRequest by remember { mutableIntStateOf(0) }
    var searchState by remember { mutableStateOf<PlaceSearchState>(PlaceSearchState.Idle) }
    var searchDuplicateToken by remember { mutableIntStateOf(0) }
    var currentSaveDuplicateToken by remember { mutableIntStateOf(0) }
    var currentChoicePending by remember { mutableStateOf(false) }
    LaunchedEffect(submittedQuery, searchRequest, locale.language) {
        val submitted = submittedQuery ?: return@LaunchedEffect
        searchState = PlaceSearchState.Loading
        searchState = runCatching {
            PlaceSearchState.Results(geocoder.search(submitted, locale.language))
        }.getOrElse { PlaceSearchState.Failed(resources.getString(R.string.places_search_failed)) }
    }
    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.any { it }) {
            message = null
            useCurrentLocation()
        } else {
            currentChoicePending = false
            message = resources.getString(R.string.places_permission_denied)
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
    var savingCurrent by remember { mutableStateOf<SavedPlace?>(null) }
    val density = LocalDensity.current
    val currentSelected = collection.selectedId == CURRENT_LOCATION_ID
    val currentSelectionActive = currentSelected || currentChoicePending
    val liveSnapshot = if (currentSelectionActive) {
        LiveLocationSavePolicy.snapshot((locationState as? LocationUiState.Active)?.place)
    } else null
    val matchingLivePlace = LiveLocationMatchPolicy.matchingPlace(
        collection.places, liveSnapshot, defaultStartupId,
    )
    val liveSnapshotAlreadySaved = matchingLivePlace != null
    val chooseCurrentLocation = {
        currentChoicePending = true
        searchDuplicateToken = 0
        message = null
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) useCurrentLocation() else locationPermission.launch(arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ))
    }

    LaunchedEffect(collection.selectedId) {
        if (collection.selectedId != CURRENT_LOCATION_ID) currentChoicePending = false
    }
    LaunchedEffect(searchDuplicateToken) {
        if (searchDuplicateToken == 0) return@LaunchedEffect
        delay(PlaceDuplicateFeedbackPolicy.DURATION_MILLIS)
        searchDuplicateToken = 0
    }
    LaunchedEffect(currentSaveDuplicateToken) {
        if (currentSaveDuplicateToken == 0) return@LaunchedEffect
        delay(PlaceDuplicateFeedbackPolicy.DURATION_MILLIS)
        currentSaveDuplicateToken = 0
    }

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

    var rootBounds by remember { mutableStateOf(Rect.Zero) }
    var searchBounds by remember { mutableStateOf(Rect.Zero) }
    Box(
        Modifier.fillMaxSize()
            .onGloballyPositioned { rootBounds = it.boundsInRoot() }
            .pointerInput(rootBounds, searchBounds) {
                awaitEachGesture {
                    val down = awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Initial,
                    )
                    val pointInRoot = Offset(
                        rootBounds.left + down.position.x,
                        rootBounds.top + down.position.y,
                    )
                    if (searchBounds == Rect.Zero || !searchBounds.contains(pointInRoot)) {
                        dismissSearchInput()
                    }
                }
            },
    ) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
    ) {
        item(key = "places-header") {
        Column {
        Text(
            stringResource(R.string.places_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(R.string.places_subtitle),
            color = PlacesSecondary,
        )
        Spacer(Modifier.height(12.dp))
        val submitSearch = {
            dismissSearchInput()
            val query = searchQuery.trim()
            if (query.length >= 2 && searchState !is PlaceSearchState.Loading) {
                searchDuplicateToken = 0
                submittedQuery = query
                searchRequest++
            }
        }
        OutlinedTextField(
            value = searchQuery,
            onValueChange = {
                searchQuery = it
                searchDuplicateToken = 0
            },
            label = { Text(stringResource(R.string.places_search_hint)) },
            singleLine = true,
            shape = RoundedCornerShape(PlacesTopControlGeometry.cornerRadiusDp.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = LocalRainAlarmPalette.current.border,
                focusedBorderColor = PlacesAccent,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submitSearch() }),
            trailingIcon = {
                if (searchState is PlaceSearchState.Loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                else IconButton(
                    onClick = submitSearch,
                    enabled = searchQuery.trim().length >= 2 &&
                        searchState !is PlaceSearchState.Loading,
                ) { Icon(Icons.Default.Search,
                    contentDescription = stringResource(R.string.places_search_description)) }
            },
            modifier = Modifier.fillMaxWidth()
                .height(PlacesTopControlGeometry.heightDp.dp)
                .onGloballyPositioned { searchBounds = it.boundsInRoot() },
        )
        if (searchDuplicateToken != 0) {
            Text(
                stringResource(R.string.places_duplicate),
                color = PlacesDanger,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        when (val search = searchState) {
            PlaceSearchState.Idle, PlaceSearchState.Loading -> Unit
            is PlaceSearchState.Failed -> Text(search.message, color = PlacesDanger, fontSize = 12.sp)
            is PlaceSearchState.Results -> {
                if (search.places.isEmpty()) Text(stringResource(R.string.places_no_matches),
                    color = PlacesSecondary)
                search.places.take(5).forEach { result ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(result.name, fontWeight = FontWeight.SemiBold)
                            Text(result.detail, color = PlacesSecondary, fontSize = 11.sp)
                        }
                        Button(onClick = {
                            focusManager.clearFocus(force = true)
                            keyboard?.hide()
                            val incoming = result.toSavedPlace()
                            if (collection.places.any { PlaceCoordinatePolicy.nearDuplicate(it, incoming) }) {
                                searchDuplicateToken++
                            } else {
                                searchDuplicateToken = 0
                                saveAndSelect(incoming)
                                searchQuery = ""
                                submittedQuery = null
                                searchState = PlaceSearchState.Idle
                            }
                        }) { Text(stringResource(R.string.places_add_select)) }
                    }
                }
                Text(stringResource(R.string.places_search_sources),
                    color = PlacesSecondary, fontSize = 10.sp)
            }
        }
        OutlinedButton(
            onClick = {
                dismissSearchInput()
                searchDuplicateToken = 0
                chooseOnMap()
            },
            shape = RoundedCornerShape(PlacesTopControlGeometry.cornerRadiusDp.dp),
            border = BorderStroke(
                PlacesTopControlGeometry.outlineWidthDp.dp,
                LocalRainAlarmPalette.current.border,
            ),
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                .height(PlacesTopControlGeometry.heightDp.dp),
        ) {
            Icon(Icons.Default.Map, contentDescription = null)
            Text(stringResource(R.string.places_choose_map), modifier = Modifier.padding(start = 8.dp))
        }
        CurrentLocationSelectionButton(
            selected = currentSelectionActive,
            locating = currentSelectionActive && locationState is LocationUiState.Locating,
            onClick = chooseCurrentLocation,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                .height(PlacesTopControlGeometry.heightDp.dp),
        )
        if (currentSelected) {
            Text(
                stringResource(
                    if (liveSnapshotAlreadySaved) R.string.places_current_saved_status
                    else R.string.places_current_selected_status,
                ),
                color = PlacesAccent,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 5.dp),
            )
            if (liveSnapshot != null && !liveSnapshotAlreadySaved) Button(
                onClick = {
                    currentSaveDuplicateToken = 0
                    savingCurrent = liveSnapshot
                    editedName = liveSnapshot.name
                },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(48.dp),
            ) {
                Text(stringResource(R.string.places_save_current))
            }
            val locationProblem = when (locationState) {
                is LocationUiState.Active -> locationState.message
                is LocationUiState.Unavailable -> locationState.message
                LocationUiState.Idle, LocationUiState.Locating -> null
            }
            if (locationProblem != null) Text(
                locationProblem,
                color = PlacesDanger,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 5.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.places_saved_heading), fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.places_reorder_hint), color = PlacesSecondary, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        }
        }
        items(displayedPlaces, key = { it.id }) { place ->
            val isDragging = draggingId == place.id
            val moveUpDescription = stringResource(R.string.places_move_up, place.name)
            val moveDownDescription = stringResource(R.string.places_move_down, place.name)
            PlaceRow(
                place = place,
                selected = place.id == collection.selectedId ||
                    (currentSelected && place.id == matchingLivePlace?.id),
                liveCoordinateMatch = currentSelected && place.id == matchingLivePlace?.id,
                startup = place.id == defaultStartupId,
                onSelect = {
                    currentChoicePending = false
                    searchDuplicateToken = 0
                    selectPlace(place.id)
                },
                onDelete = {
                    if (place.id !in pendingDeletes) {
                        pendingDeletes = pendingDeletes + place.id
                        pendingOrder = null
                        displayedPlaces.removeAll { it.id == place.id }
                        deletePlace(place.id) { succeeded ->
                            if (!succeeded) {
                                pendingDeletes = pendingDeletes - place.id
                                message = resources.getString(R.string.places_delete_failed, place.name)
                            }
                        }
                    }
                },
                onMakeStartup = { setDefaultStartupId(place.id) },
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
                            CustomAccessibilityAction(moveUpDescription) {
                                val from = displayedPlaces.indexOfFirst { it.id == place.id }
                                if (from <= 0) false else {
                                    val moved = displayedPlaces.removeAt(from)
                                    displayedPlaces.add(from - 1, moved)
                                    pendingOrder = displayedPlaces.map { it.id }
                                    reorderPlaces(requireNotNull(pendingOrder))
                                    true
                                }
                            },
                            CustomAccessibilityAction(moveDownDescription) {
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
        Spacer(Modifier.height(14.dp))
        if (message != null) {
            Text(message!!, color = PlacesDanger, modifier = Modifier.padding(top = 10.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.places_privacy), fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.places_privacy_body), color = PlacesSecondary)
        }
        }
    }
    }

    val renaming = collection.places.firstOrNull { it.id == renamingId }
    if (renaming != null) AlertDialog(
        onDismissRequest = { renamingId = null },
        title = { Text(stringResource(R.string.places_rename_title)) },
        text = {
            OutlinedTextField(
                value = editedName,
                onValueChange = { editedName = it },
                label = { Text(stringResource(R.string.places_name_label)) },
                singleLine = true,
                isError = editedName.isNotEmpty() && !PlaceCollectionRules.validName(editedName),
                supportingText = if (!PlaceCollectionRules.validName(editedName)) {
                    { Text(stringResource(R.string.places_name_error)) }
                } else null,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { renamePlace(renaming.id, editedName.trim()); renamingId = null },
                enabled = PlaceCollectionRules.validName(editedName),
            ) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = { renamingId = null }) {
            Text(stringResource(R.string.common_cancel))
        } },
    )
    val currentToSave = savingCurrent
    if (currentToSave != null) AlertDialog(
        onDismissRequest = { savingCurrent = null; currentSaveDuplicateToken = 0 },
        title = { Text(stringResource(R.string.places_save_current)) },
        text = {
            OutlinedTextField(
                value = editedName,
                onValueChange = { editedName = it; currentSaveDuplicateToken = 0 },
                label = { Text(stringResource(R.string.places_name_label)) },
                singleLine = true,
                isError = currentSaveDuplicateToken != 0 ||
                    (editedName.isNotEmpty() && !PlaceCollectionRules.validName(editedName)),
                supportingText = when {
                    currentSaveDuplicateToken != 0 ->
                        { { Text(stringResource(R.string.places_duplicate)) } }
                    !PlaceCollectionRules.validName(editedName) ->
                        { { Text(stringResource(R.string.places_name_error)) } }
                    else -> null
                },
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val incoming = currentToSave.copy(name = editedName.trim())
                    if (collection.places.any { PlaceCoordinatePolicy.nearDuplicate(it, incoming) }) {
                        currentSaveDuplicateToken++
                    } else {
                        saveAndSelect(incoming)
                        savingCurrent = null
                        currentSaveDuplicateToken = 0
                    }
                },
                enabled = PlaceCollectionRules.validName(editedName),
            ) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = {
            savingCurrent = null
            currentSaveDuplicateToken = 0
        }) {
            Text(stringResource(R.string.common_cancel))
        } },
    )
}

@Composable
private fun CurrentLocationSelectionButton(
    selected: Boolean,
    locating: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedDescription = stringResource(R.string.selected_now)
    val content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
        if (locating) CircularProgressIndicator(
            Modifier.size(22.dp),
            strokeWidth = 2.dp,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else PlacesAccent,
        ) else Icon(Icons.Default.MyLocation, contentDescription = null)
        Text(
            stringResource(R.string.places_use_current),
            modifier = Modifier.padding(start = 8.dp),
        )
    }
    val semanticModifier = modifier.semantics {
        if (selected) stateDescription = selectedDescription
    }
    if (selected) Button(
        onClick = onClick,
        modifier = semanticModifier,
        shape = RoundedCornerShape(PlacesTopControlGeometry.cornerRadiusDp.dp),
        content = content,
    ) else OutlinedButton(
        onClick = onClick,
        modifier = semanticModifier,
        shape = RoundedCornerShape(PlacesTopControlGeometry.cornerRadiusDp.dp),
        border = BorderStroke(
            PlacesTopControlGeometry.outlineWidthDp.dp,
            LocalRainAlarmPalette.current.border,
        ),
        content = content,
    )
}

@Composable
private fun PlaceRow(
    place: SavedPlace,
    selected: Boolean,
    liveCoordinateMatch: Boolean,
    startup: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    onMakeStartup: () -> Unit,
    onRename: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val locale = LocalConfiguration.current.locales[0]
    val startupDescription = stringResource(
        if (startup) R.string.places_startup_current_description
        else R.string.places_startup_set_description,
        place.name,
    )
    val startupState = stringResource(
        if (startup) R.string.opens_on_startup else R.string.places_not_startup,
    )
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
            Icon(Icons.Default.DragHandle,
                contentDescription = stringResource(R.string.places_drag_description, place.name),
                tint = PlacesAccent)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(place.name, fontWeight = FontWeight.Bold)
                Text(
                    stringResource(
                        R.string.places_coordinates,
                        String.format(locale, "%.4f", place.latitude),
                        String.format(locale, "%.4f", place.longitude),
                    ),
                    color = PlacesSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                )
                if (selected && !liveCoordinateMatch) Text(stringResource(R.string.selected_now), color = PlacesAccent,
                    fontSize = 11.sp)
                if (liveCoordinateMatch) Text(
                    stringResource(R.string.places_live_location_match),
                    color = PlacesAccent,
                    fontSize = 11.sp,
                )
                if (startup) Text(stringResource(R.string.opens_on_startup), color = PlacesAccent,
                    fontSize = 11.sp)
            }
            IconButton(
                onClick = onMakeStartup,
                modifier = Modifier.semantics { stateDescription = startupState },
            ) {
                Icon(
                    if (startup) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                    contentDescription = startupDescription,
                    tint = if (startup) PlacesAccent else PlacesSecondary,
                )
            }
            IconButton(onClick = onRename) {
                Icon(Icons.Default.Edit,
                    contentDescription = stringResource(R.string.places_edit_description, place.name))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete,
                    contentDescription = stringResource(R.string.places_delete_description, place.name))
            }
        }
    }
}
