package com.rainalarm.app

import android.app.Application
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.content.ContextCompat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import com.rainalarm.app.data.DEFAULT_PLACE
import com.rainalarm.app.data.ForecastSnapshot
import com.rainalarm.app.data.ForecastRepository
import com.rainalarm.app.data.RadarAwareForecastRepository
import com.rainalarm.app.data.PlacePreferences
import com.rainalarm.app.data.PlaceCollection
import com.rainalarm.app.data.PlaceCollectionRules
import com.rainalarm.app.data.StartupPermissionPreferences
import com.rainalarm.app.data.StartupPermissionPolicy
import com.rainalarm.app.data.StartupPermissionStep
import com.rainalarm.app.data.PlatformLocationClient
import com.rainalarm.app.data.LiveLocationPolicy
import com.rainalarm.app.data.ForegroundLocationSnapshot
import com.rainalarm.app.data.CURRENT_LOCATION_ID
import com.rainalarm.app.data.CurrentLocationSelectionPolicy
import com.rainalarm.app.data.LocationNameResolver
import com.rainalarm.app.data.SavedPlace
import com.rainalarm.app.data.RadarPlaybackSpeed
import com.rainalarm.app.data.AppearanceMode
import com.rainalarm.app.data.RadarProviderKind
import com.rainalarm.app.data.RadarSettingsRepository
import com.rainalarm.app.data.RadarMapLayer
import com.rainalarm.app.data.RadarLoadDeadline
import com.rainalarm.app.data.NowWeatherMetric
import com.rainalarm.app.data.CurrentWeather
import com.rainalarm.app.data.WeatherLayerRepository
import com.rainalarm.app.data.forecastSelectionKey
import com.rainalarm.app.domain.RadarCameraMemory
import com.rainalarm.app.ui.LiveRadarScreen
import com.rainalarm.app.ui.RadarChartTimeRequest
import com.rainalarm.app.ui.RadarChartTimeLink
import com.rainalarm.app.ui.PlacesScreen
import com.rainalarm.app.ui.SettingsScreen
import com.rainalarm.app.ui.LocalRainAlarmPalette
import com.rainalarm.app.ui.DarkRainPalette
import com.rainalarm.app.ui.LightRainPalette
import com.rainalarm.app.alerts.AlertSnapshot
import com.rainalarm.app.alerts.RainAlertPreferences
import com.rainalarm.app.alerts.RainAlertScheduler
import com.rainalarm.app.alerts.AlertStartupPolicy
import com.rainalarm.app.alerts.AlertStartupAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.cancel
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Background: Color @Composable get() = LocalRainAlarmPalette.current.background
private val Surface: Color @Composable get() = LocalRainAlarmPalette.current.surface
private val TextPrimary: Color @Composable get() = LocalRainAlarmPalette.current.text
private val TextSecondary: Color @Composable get() = LocalRainAlarmPalette.current.muted
private val Accent: Color @Composable get() = LocalRainAlarmPalette.current.accent
private val Border: Color @Composable get() = LocalRainAlarmPalette.current.border
private val Danger: Color @Composable get() = LocalRainAlarmPalette.current.danger
private const val CURRENT_LOCATION_TAG = "RainCurrentLocation"
private const val POST_NOTIFICATIONS_PERMISSION = "android.permission.POST_NOTIFICATIONS"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val appViewModel: RainAlarmViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
            val appearance by appViewModel.appAppearance.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            val dark = appearance.isDark(systemDark)
            SideEffect {
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            RainAlarmTheme(dark) { RainAlarmApp(appViewModel) }
        }
    }
}

sealed interface ForecastUiState {
    data object Loading : ForecastUiState
    data class Ready(val forecast: ForecastSnapshot) : ForecastUiState
    data class Error(val message: String) : ForecastUiState
}

sealed interface NowRefreshStatus {
    data object Idle : NowRefreshStatus
    data object Refreshing : NowRefreshStatus
    data object Updated : NowRefreshStatus
    data class Failed(val message: String) : NowRefreshStatus
}

private data class ForecastLoadRequest(
    val place: SavedPlace?,
    val provider: RadarProviderKind,
    val showLikelySnow: Boolean,
    val refreshVersion: Long,
) {
    val key: String? = place?.let {
        "${forecastSelectionKey(it)}|${provider.name}|${provider == RadarProviderKind.OPEN_RAINVIEWER && showLikelySnow}"
    }
}

sealed interface LocationUiState {
    data object Idle : LocationUiState
    data object Locating : LocationUiState
    data class Active(val place: SavedPlace) : LocationUiState
    data class Unavailable(val message: String) : LocationUiState
}

@android.annotation.SuppressLint("LogNotTimber") // Local adb diagnostics must work without a logging dependency.
class RainAlarmViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = RadarAwareForecastRepository(application)
    private val places = PlacePreferences(application)
    private val locationClient = PlatformLocationClient(application)
    private val alertScheduler = RainAlertScheduler(application)
    private val alertPreferences = RainAlertPreferences(application)
    private val startupPermissions = StartupPermissionPreferences(application)
    private val radarSettings = RadarSettingsRepository(application)
    private val _state = MutableStateFlow<ForecastUiState>(ForecastUiState.Loading)
    val state: StateFlow<ForecastUiState> = _state.asStateFlow()
    private val forecastRefreshVersion = MutableStateFlow(0L)
    private var activeForecastKey: String? = null
    private var displayedForecastKey: String? = null
    private val _refreshStatus = MutableStateFlow<NowRefreshStatus>(NowRefreshStatus.Idle)
    val refreshStatus: StateFlow<NowRefreshStatus> = _refreshStatus.asStateFlow()
    private var pendingPlaceSelectionId: String? = null
    private var pendingProviderSelection: RadarProviderKind? = null
    val placesState = places.collection.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        PlaceCollectionRules.freshInstall(),
    )
    private val _startupReady = MutableStateFlow(false)
    val startupReady: StateFlow<Boolean> = _startupReady.asStateFlow()
    private val _livePlace = MutableStateFlow<SavedPlace?>(null)
    // Foreground-only marker position; small fixes do not invalidate point forecasts.
    private val _liveMapPlace = MutableStateFlow<SavedPlace?>(null)
    val liveMapPlace: StateFlow<SavedPlace?> = _liveMapPlace.asStateFlow()
    private var lastLiveFixTimeMillis = 0L
    private var recenterAfterNextFix = false
    private val _foreground = MutableStateFlow(false)
    val selectedPlace = combine(placesState, _livePlace, _startupReady) { collection, live, ready ->
        if (!ready) null else if (collection.selectedId == CURRENT_LOCATION_ID) live else collection.selected
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val defaultStartupId = places.defaultStartupId.stateIn(
        viewModelScope, SharingStarted.Eagerly, CURRENT_LOCATION_ID,
    )
    val alertState = alertPreferences.snapshots.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        alertPreferences.snapshot(),
    )
    val radarProvider = radarSettings.provider.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        RadarProviderKind.METEOGROUP_REGIONAL,
    )
    val showLikelySnow = radarSettings.showLikelySnow.stateIn(
        viewModelScope, SharingStarted.Eagerly, false,
    )
    val radarPlaybackSpeed = radarSettings.playbackSpeed.stateIn(
        viewModelScope, SharingStarted.Eagerly, RadarPlaybackSpeed.DOUBLE,
    )
    val appAppearance = radarSettings.appAppearance.stateIn(
        viewModelScope, SharingStarted.Eagerly, AppearanceMode.DARK,
    )
    val mapAppearance = radarSettings.mapAppearance.stateIn(
        viewModelScope, SharingStarted.Eagerly, AppearanceMode.DARK,
    )
    val compassAppearance = radarSettings.compassAppearance.stateIn(
        viewModelScope, SharingStarted.Eagerly, com.rainalarm.app.data.NowCardAppearance.FOLLOW_APP,
    )
    val graphAppearance = radarSettings.graphAppearance.stateIn(
        viewModelScope, SharingStarted.Eagerly, com.rainalarm.app.data.NowCardAppearance.FOLLOW_APP,
    )
    val mapLayer = radarSettings.mapLayer.stateIn(
        viewModelScope, SharingStarted.Eagerly, RadarMapLayer.OFF,
    )
    val windArrowScale = radarSettings.windArrowScale.stateIn(
        viewModelScope, SharingStarted.Eagerly, com.rainalarm.app.data.WindArrowSizePreference.DEFAULT,
    )
    val visibleWeatherMetrics = radarSettings.nowMetrics.stateIn(
        viewModelScope, SharingStarted.Eagerly, NowWeatherMetric.entries.toSet(),
    )
    private val _currentWeather = MutableStateFlow<Pair<String, CurrentWeather>?>(null)
    val currentWeather: StateFlow<Pair<String, CurrentWeather>?> = _currentWeather.asStateFlow()
    private val _locationState = MutableStateFlow<LocationUiState>(LocationUiState.Idle)
    private val _currentLocationRequest = MutableStateFlow(0L)
    private var currentSelectionJob: Job? = null
    private var placeNameJob: Job? = null
    val locationState: StateFlow<LocationUiState> = _locationState.asStateFlow()
    private val _currentRecenterTick = MutableStateFlow(0)
    val currentRecenterTick: StateFlow<Int> = _currentRecenterTick.asStateFlow()
    private val _settingsMessage = MutableStateFlow<String?>(null)
    val settingsMessage: StateFlow<String?> = _settingsMessage.asStateFlow()

    init {
        viewModelScope.launch {
            val freshInstall = places.ensureMigrated()
            val explicitAlerts = alertPreferences.snapshot().enabled
                .takeIf { alertPreferences.hasExplicitEnabledChoice() }
            val wantsAlerts = explicitAlerts != false
            if (freshInstall) startupPermissions.initializeFreshInstall(wantsAlerts)
            places.applyStartupDefault()
            when (AlertStartupPolicy.decide(explicitAlerts, notificationPermissionGranted(),
                startupPermissions.notificationAttempted())) {
                AlertStartupAction.SCHEDULE -> alertScheduler.ensureScheduledOnStartup()
                AlertStartupAction.REQUEST_PERMISSION -> startupPermissions.queueNotificationIfUnanswered()
                AlertStartupAction.PERMISSION_NEEDED -> alertPreferences.markPermissionNeeded()
                AlertStartupAction.NONE -> Unit
            }
            _startupReady.value = true
        }
        viewModelScope.launch {
            combine(placesState.map { it.selectedId }, _foreground, _currentLocationRequest) { id, foreground, request ->
                Triple(id, foreground, request)
            }.distinctUntilChanged().collectLatest { (id, foreground, _) ->
                    if (id != CURRENT_LOCATION_ID || !foreground) {
                        recenterAfterNextFix = false
                        placeNameJob?.cancel()
                        if (id == CURRENT_LOCATION_ID) {
                            _livePlace.value = null
                            _liveMapPlace.value = null
                            lastLiveFixTimeMillis = 0L
                            ForegroundLocationSnapshot.clear()
                            _locationState.value = LocationUiState.Unavailable("Live location is available only while the app is in the foreground.")
                        } else {
                            _livePlace.value = null
                            _liveMapPlace.value = null
                            lastLiveFixTimeMillis = 0L
                            ForegroundLocationSnapshot.clear()
                        }
                        return@collectLatest
                    }
                    if (!locationClient.hasForegroundPermission()) {
                        recenterAfterNextFix = false
                        Log.w(CURRENT_LOCATION_TAG, "Current selected without foreground permission")
                        _livePlace.value = null
                        _liveMapPlace.value = null
                        lastLiveFixTimeMillis = 0L
                        ForegroundLocationSnapshot.clear()
                        _locationState.value = LocationUiState.Unavailable("Location permission is unavailable. Select a saved place or grant foreground location.")
                        return@collectLatest
                    }
                    _locationState.value = LocationUiState.Locating
                    Log.i(CURRENT_LOCATION_TAG, "Starting foreground fix acquisition")
                    val initialFix = try {
                        locationClient.currentOrLastKnown()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                    initialFix?.let { acceptLiveFix(it) }
                        ?: run {
                            Log.w(CURRENT_LOCATION_TAG, "No fresh initial fix; awaiting foreground updates")
                            if (!LiveLocationPolicy.isFresh(lastLiveFixTimeMillis, System.currentTimeMillis())) {
                                _livePlace.value = null
                                _liveMapPlace.value = null
                                lastLiveFixTimeMillis = 0L
                                ForegroundLocationSnapshot.clear()
                                _locationState.value = LocationUiState.Unavailable("No fresh device fix is available yet.")
                            }
                        }
                    if (_locationState.value is LocationUiState.Locating) {
                        _livePlace.value?.let { _locationState.value = LocationUiState.Active(it) }
                    }
                    try {
                        coroutineScope {
                            val periodicFix = launch {
                                while (true) {
                                    delay(LiveLocationPolicy.REFRESH_CHECK_INTERVAL_MILLIS)
                                    val now = System.currentTimeMillis()
                                    if (!LiveLocationPolicy.needsForegroundRefresh(lastLiveFixTimeMillis, now)) continue
                                    val refreshed = try { locationClient.currentOrLastKnown() }
                                        catch (cancelled: CancellationException) { throw cancelled }
                                        catch (_: Exception) { null }
                                    if (refreshed != null) acceptLiveFix(refreshed)
                                    else if (!LiveLocationPolicy.isFresh(lastLiveFixTimeMillis, now)) {
                                        _livePlace.value = null
                                        _liveMapPlace.value = null
                                        ForegroundLocationSnapshot.clear()
                                        _locationState.value = LocationUiState.Unavailable("No fresh device fix is available yet.")
                                    }
                                }
                            }
                            try {
                                locationClient.foregroundUpdates().collect { fix ->
                                    if (LiveLocationPolicy.isFresh(fix.time, System.currentTimeMillis())) acceptLiveFix(fix)
                                }
                            } finally { periodicFix.cancel() }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        Log.w(CURRENT_LOCATION_TAG, "Foreground location updates unavailable")
                        _livePlace.value = null
                        _liveMapPlace.value = null
                        lastLiveFixTimeMillis = 0L
                        ForegroundLocationSnapshot.clear()
                        _locationState.value = LocationUiState.Unavailable("Live location updates are unavailable.")
                    }
                }
        }
        viewModelScope.launch {
            var observedKey: String? = null
            combine(selectedPlace, radarProvider, showLikelySnow, forecastRefreshVersion) {
                    place, provider, snow, refreshVersion ->
                ForecastLoadRequest(place, provider, snow, refreshVersion)
            }.distinctUntilChanged { previous, next ->
                previous.key == next.key && previous.refreshVersion == next.refreshVersion
            }.collectLatest { request ->
                val selectionChanged = request.key != observedKey
                observedKey = request.key
                if (pendingPlaceSelectionId == request.place?.id) pendingPlaceSelectionId = null
                if (pendingProviderSelection == request.provider) pendingProviderSelection = null
                loadForecast(request, selectionChanged)
            }
        }
        viewModelScope.launch {
            selectedPlace.collectLatest { place ->
                _currentWeather.value = null
                if (place != null) loadCurrentWeather(place)
            }
        }
    }

    private fun notificationPermissionGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(getApplication(), POST_NOTIFICATIONS_PERMISSION) ==
                PackageManager.PERMISSION_GRANTED

    fun onStartupLocationPermissionResult(granted: Boolean) {
        if (granted) useCurrentLocation()
        else _locationState.value = LocationUiState.Unavailable(
            "Location permission was not granted. Current location is unavailable; choose a saved place or retry.",
        )
    }

    fun onStartupNotificationPermissionResult(granted: Boolean) {
        if (granted) alertScheduler.ensureScheduledOnStartup()
        else alertPreferences.markPermissionNeeded()
    }

    fun setForeground(value: Boolean) {
        _foreground.value = value
        ForegroundLocationSnapshot.setForeground(value)
        if (!value) {
            placeNameJob?.cancel()
            _livePlace.value = null
        }
    }

    fun refresh() {
        if (selectedPlace.value == null) {
            if (CurrentLocationSelectionPolicy.needsFixRetry(placesState.value.selectedId, false) &&
                _foreground.value && locationClient.hasForegroundPermission()) {
                useCurrentLocation()
                return
            }
            _refreshStatus.value = NowRefreshStatus.Failed("A fresh current-location fix or saved place is required.")
            return
        }
        _refreshStatus.value = NowRefreshStatus.Refreshing
        forecastRefreshVersion.value++
        refreshPointWeather()
    }

    fun refreshOnNowEntry() {
        if (!_startupReady.value) return
        val place = selectedPlace.value
        val key = place?.let {
            "${forecastSelectionKey(it)}|${radarProvider.value.name}|" +
                (radarProvider.value == RadarProviderKind.OPEN_RAINVIEWER && showLikelySnow.value)
        }
        val selectionPending = pendingPlaceSelectionId != null || pendingProviderSelection != null
        if (key == null) {
            if (CurrentLocationSelectionPolicy.needsFixRetry(placesState.value.selectedId, false) &&
                _foreground.value && locationClient.hasForegroundPermission() &&
                _locationState.value !is LocationUiState.Locating) useCurrentLocation()
            return
        }
        if (selectionPending) return // the selected-place/provider collector owns that load
        _refreshStatus.value = NowRefreshStatus.Refreshing
        if (NowEntryRefreshPolicy.shouldStart(key, activeForecastKey, selectionPending))
            forecastRefreshVersion.value++
        refreshPointWeather()
    }

    fun refreshPointWeather() {
        selectedPlace.value?.let { place -> viewModelScope.launch { loadCurrentWeather(place, force = true) } }
    }

    private suspend fun loadCurrentWeather(place: SavedPlace, force: Boolean = false) {
        try {
            val weather = WeatherLayerRepository.point(place, force)
            val active = selectedPlace.value
            if (active?.let(::forecastSelectionKey) == forecastSelectionKey(place))
                _currentWeather.value = forecastSelectionKey(place) to weather
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (selectedPlace.value?.let(::forecastSelectionKey) == forecastSelectionKey(place))
            _currentWeather.value = null }
    }

    private suspend fun loadForecast(request: ForecastLoadRequest, selectionChanged: Boolean) {
        val place = request.place
        val key = request.key
        if (place == null) {
            displayedForecastKey = null
            _state.value = ForecastUiState.Error("Current location unavailable. Get a fresh foreground fix or choose a saved place.")
            return
        }
        activeForecastKey = key
        if (selectionChanged) _refreshStatus.value = NowRefreshStatus.Idle
        if (selectionChanged || displayedForecastKey != key || _state.value !is ForecastUiState.Ready)
            _state.value = ForecastUiState.Loading
        try {
            val result = try {
                ForecastUiState.Ready(withTimeout(RadarLoadDeadline.NOW_TOTAL_MILLIS) {
                    repository.forecastFor(place)
                })
            } catch (_: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                ForecastUiState.Error("Forecast took too long. Tap refresh to try again.")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                ForecastUiState.Error(failure.message ?: "Forecast failed")
            }
            if (result is ForecastUiState.Ready) {
                _state.value = result
                displayedForecastKey = key
                if (_refreshStatus.value is NowRefreshStatus.Refreshing) {
                    _refreshStatus.value = NowRefreshStatus.Updated
                    viewModelScope.launch {
                        delay(3_000)
                        if (forecastRefreshVersion.value == request.refreshVersion &&
                            _refreshStatus.value is NowRefreshStatus.Updated)
                            _refreshStatus.value = NowRefreshStatus.Idle
                    }
                }
            } else {
                val message = (result as ForecastUiState.Error).message
                if (displayedForecastKey != key || selectionChanged || _state.value !is ForecastUiState.Ready) {
                    _state.value = result
                    displayedForecastKey = null
                }
                _refreshStatus.value = NowRefreshStatus.Failed(message)
            }
        } finally {
            activeForecastKey = null
        }
    }

    private suspend fun acceptLiveFix(fix: android.location.Location) {
        if (!LiveLocationPolicy.isFresh(fix.time, System.currentTimeMillis()) ||
            !fix.latitude.isFinite() || !fix.longitude.isFinite() ||
            fix.latitude !in -90.0..90.0 || fix.longitude !in -180.0..180.0) return
        if (_liveMapPlace.value != null && !LiveLocationPolicy.isNewerFix(fix.time, lastLiveFixTimeMillis)) return
        lastLiveFixTimeMillis = fix.time
        val prior = _livePlace.value
        val marker = try {
            (prior ?: SavedPlace(
                name = LocationNameResolver.fallback(fix.latitude, fix.longitude),
                latitude = fix.latitude, longitude = fix.longitude, isCurrentLocation = true,
            )).copy(latitude = fix.latitude, longitude = fix.longitude)
        } catch (_: Exception) { return }
        _liveMapPlace.value = marker
        if (recenterAfterNextFix) {
            recenterAfterNextFix = false
            _currentRecenterTick.value++
        }
        if (prior != null && !LiveLocationPolicy.materiallyMoved(
                prior.latitude, prior.longitude, fix.latitude, fix.longitude,
            )) {
            ForegroundLocationSnapshot.update(prior, fix.time)
            _locationState.value = LocationUiState.Active(prior)
            return
        }
        val place = try {
            SavedPlace(
                name = LocationNameResolver.fallback(fix.latitude, fix.longitude),
                latitude = fix.latitude, longitude = fix.longitude,
                isCurrentLocation = true,
            )
        } catch (_: Exception) {
            return
        }
        _currentWeather.value = null
        _livePlace.value = place
        _liveMapPlace.value = place
        ForegroundLocationSnapshot.update(place, fix.time)
        _locationState.value = LocationUiState.Active(place)
        Log.i(CURRENT_LOCATION_TAG, "Fresh live fix active")
        alertScheduler.enqueueImmediate()
        placeNameJob?.cancel()
        placeNameJob = viewModelScope.launch {
            val name = try {
                withTimeoutOrNull(2_000L) {
                    locationClient.localityOrFallback(fix.latitude, fix.longitude)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } ?: return@launch
            // An older reverse-geocode result must never rename a newer fix or saved selection.
            val active = _livePlace.value
            if (placesState.value.selectedId == CURRENT_LOCATION_ID && _foreground.value &&
                active != null && active.latitude == fix.latitude && active.longitude == fix.longitude
            ) {
                val named = active.copy(name = name)
                _livePlace.value = named
                _locationState.value = LocationUiState.Active(named)
            }
        }
    }

    fun useCurrentLocation() = requestCurrentLocation(recenterMap = false)

    fun recenterToCurrentLocation() = requestCurrentLocation(recenterMap = true)

    fun recenterToSelectedPlace() {
        val place = selectedPlace.value
        if (com.rainalarm.app.domain.RadarSelectedCenterPolicy.canCenter(placesState.value.selectedId, place)) {
            _currentRecenterTick.value++
        }
    }

    private fun requestCurrentLocation(recenterMap: Boolean) {
        val transition = CurrentLocationSelectionPolicy.request(
            placesState.value.selectedId, _currentLocationRequest.value,
            locationClient.hasForegroundPermission(),
        )
        if (!transition.allowed) {
            Log.w(CURRENT_LOCATION_TAG, "Current selection denied: no foreground permission")
            _locationState.value = LocationUiState.Unavailable(
                "Location permission is unavailable. Your selected place is unchanged.",
            )
            return
        }
        placeNameJob?.cancel()
        val preserveFix = placesState.value.selectedId == CURRENT_LOCATION_ID &&
            LiveLocationPolicy.isFresh(lastLiveFixTimeMillis, System.currentTimeMillis()) &&
            _livePlace.value != null
        if (preserveFix && recenterMap) _currentRecenterTick.value++
        if (!preserveFix) {
            _livePlace.value = null
            _liveMapPlace.value = null
            lastLiveFixTimeMillis = 0L
            ForegroundLocationSnapshot.clear()
        }
        recenterAfterNextFix = recenterMap
        _locationState.value = LocationUiState.Locating
        Log.i(CURRENT_LOCATION_TAG, "Selecting virtual Current and requesting a fresh fix")
        pendingPlaceSelectionId = CURRENT_LOCATION_ID
        _currentLocationRequest.value = transition.requestGeneration
        currentSelectionJob?.cancel()
        currentSelectionJob = viewModelScope.launch {
            places.select(transition.selectedId)
            alertScheduler.enqueueImmediate()
        }
    }

    fun saveAndSelect(place: SavedPlace) {
        pendingPlaceSelectionId = place.id.takeIf { it != placesState.value.selectedId }
        if (!place.isCurrentLocation) {
            currentSelectionJob?.cancel()
            placeNameJob?.cancel()
        }
        viewModelScope.launch {
            places.upsert(place, select = true)
            if (!place.isCurrentLocation) {
                _livePlace.value = null
                _liveMapPlace.value = null
                ForegroundLocationSnapshot.clear()
            }
            _locationState.value = LocationUiState.Idle
            alertScheduler.enqueueImmediate()
        }
    }

    fun selectPlace(id: String) {
        pendingPlaceSelectionId = id.takeIf { it != placesState.value.selectedId }
        if (id != CURRENT_LOCATION_ID) {
            currentSelectionJob?.cancel()
            placeNameJob?.cancel()
        }
        viewModelScope.launch {
            places.select(id)
            if (id != CURRENT_LOCATION_ID) {
                _livePlace.value = null
                _liveMapPlace.value = null
                ForegroundLocationSnapshot.clear()
                _locationState.value = LocationUiState.Idle
            }
            alertScheduler.enqueueImmediate()
        }
    }

    fun deletePlace(id: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val succeeded = try {
                places.delete(id)
                alertPreferences.clearDeletedSavedPlace(id)
                true
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                false
            }
            onResult(succeeded)
            if (succeeded) alertScheduler.enqueueImmediate()
        }
    }

    fun setPinned(id: String, pinned: Boolean) {
        viewModelScope.launch { places.setPinned(id, pinned) }
    }

    fun renamePlace(id: String, name: String) {
        viewModelScope.launch { places.rename(id, name) }
    }

    fun reorderPlaces(orderedIds: List<String>) {
        viewModelScope.launch { places.reorder(orderedIds) }
    }

    fun setDefaultStartupPlace(id: String) {
        viewModelScope.launch { places.setDefaultStartupId(id) }
    }

    fun enableAlerts() {
        alertScheduler.enable()
    }

    fun disableAlerts() = alertScheduler.disable()

    fun setRadarProvider(provider: RadarProviderKind) {
        if (provider == radarProvider.value) return
        pendingProviderSelection = provider
        viewModelScope.launch {
            runCatching { radarSettings.setProvider(provider) }
                .onSuccess { _settingsMessage.value = null }
                .onFailure {
                    if (pendingProviderSelection == provider) pendingProviderSelection = null
                    _settingsMessage.value = "Couldn't save the radar provider. Please try again."
                }
        }
    }

    fun setShowLikelySnow(enabled: Boolean) {
        viewModelScope.launch {
            runCatching { radarSettings.setShowLikelySnow(enabled) }
                .onSuccess { _settingsMessage.value = null }
                .onFailure { _settingsMessage.value = "Couldn't save the snow display setting." }
        }
    }

    fun setRadarPlaybackSpeed(speed: RadarPlaybackSpeed) {
        viewModelScope.launch {
            runCatching { radarSettings.setPlaybackSpeed(speed) }
                .onSuccess { _settingsMessage.value = null }
                .onFailure { _settingsMessage.value = "Couldn't save animation speed. Please try again." }
        }
    }

    fun setAppAppearance(mode: AppearanceMode) {
        viewModelScope.launch {
            try { radarSettings.setAppAppearance(mode); _settingsMessage.value = null }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _settingsMessage.value = "Couldn't save app appearance." }
        }
    }

    fun setMapAppearance(mode: AppearanceMode) {
        viewModelScope.launch {
            try { radarSettings.setMapAppearance(mode); _settingsMessage.value = null }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _settingsMessage.value = "Couldn't save map appearance." }
        }
    }

    fun setCompassAppearance(mode: com.rainalarm.app.data.NowCardAppearance) {
        viewModelScope.launch { radarSettings.setCompassAppearance(mode) }
    }

    fun setGraphAppearance(mode: com.rainalarm.app.data.NowCardAppearance) {
        viewModelScope.launch { radarSettings.setGraphAppearance(mode) }
    }

    fun setMapLayer(layer: RadarMapLayer) {
        viewModelScope.launch { radarSettings.setMapLayer(layer) }
    }

    fun setWindArrowScale(scale: Float) {
        viewModelScope.launch { radarSettings.setWindArrowScale(scale) }
    }

    fun setWeatherMetricVisible(metric: NowWeatherMetric, visible: Boolean) {
        viewModelScope.launch { radarSettings.setNowMetricVisible(metric, visible) }
    }
}

internal enum class Destination(val label: String) {
    NOW("Now"),
    RADAR("Radar"),
    PLACES("Places"),
    SETTINGS("Settings"),
}

private val Destination.icon: ImageVector
    get() = when (this) {
        Destination.NOW -> Icons.Default.Cloud
        Destination.RADAR -> Icons.Default.Map
        Destination.PLACES -> Icons.Default.LocationOn
        Destination.SETTINGS -> Icons.Default.Settings
    }

@Composable
private fun RainAlarmApp(viewModel: RainAlarmViewModel = androidx.lifecycle.viewmodel.compose.viewModel()) {
    val context = LocalContext.current
    val startupPermissions = remember(context) { StartupPermissionPreferences(context) }
    val startupReady by viewModel.startupReady.collectAsStateWithLifecycle()
    var startupPromptInFlight by rememberSaveable { mutableStateOf(false) }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        viewModel.onStartupLocationPermissionResult(result.values.any { it })
        startupPromptInFlight = false
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.onStartupNotificationPermissionResult(granted)
        startupPromptInFlight = false
    }
    LaunchedEffect(startupReady, startupPromptInFlight) {
        if (!startupReady || startupPromptInFlight) return@LaunchedEffect
        val locationGranted = listOf(Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION).any {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        val notificationGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, POST_NOTIFICATIONS_PERMISSION) ==
                PackageManager.PERMISSION_GRANTED
        val step = StartupPermissionPolicy.next(startupPermissions.locationPending(),
            startupPermissions.notificationPending(), locationGranted, notificationGranted)
        if (startupPermissions.locationPending() && startupPermissions.consumeLocation() &&
            step == StartupPermissionStep.LOCATION) {
            startupPromptInFlight = true
            locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION))
            return@LaunchedEffect
        }
        if (startupPermissions.notificationPending() && startupPermissions.consumeNotification() &&
            step == StartupPermissionStep.NOTIFICATION) {
            startupPromptInFlight = true
            notificationPermissionLauncher.launch(POST_NOTIFICATIONS_PERMISSION)
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.setForeground(true)
                Lifecycle.Event.ON_STOP -> viewModel.setForeground(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) viewModel.setForeground(true)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.setForeground(false)
        }
    }
    var destination by remember { mutableStateOf(Destination.NOW) }
    var nowVisitGeneration by remember { mutableIntStateOf(0) }
    var chartRequestSerial by remember { mutableIntStateOf(0) }
    var pendingChartTime by remember { mutableStateOf<RadarChartTimeRequest?>(null) }
    fun navigateTo(next: Destination) {
        val previous = destination
        if (previous == next) return
        if (RadarChartTimeLink.shouldDiscard(pendingChartTime, pendingChartTime?.selectedPlaceId.orEmpty(),
                previous == Destination.RADAR && next != Destination.RADAR)) pendingChartTime = null
        destination = next
        if (NowEntryRefreshPolicy.entersNow(previous, next)) {
            nowVisitGeneration++
            viewModel.refreshOnNowEntry()
        }
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val refreshStatus by viewModel.refreshStatus.collectAsStateWithLifecycle()
    val selectedPlace by viewModel.selectedPlace.collectAsStateWithLifecycle()
    val liveMapPlace by viewModel.liveMapPlace.collectAsStateWithLifecycle()
    val placesState by viewModel.placesState.collectAsStateWithLifecycle()
    LaunchedEffect(placesState.selectedId, pendingChartTime?.token) {
        if (RadarChartTimeLink.shouldDiscard(pendingChartTime, placesState.selectedId, false))
            pendingChartTime = null
    }
    val defaultStartupId by viewModel.defaultStartupId.collectAsStateWithLifecycle()
    val locationState by viewModel.locationState.collectAsStateWithLifecycle()
    val currentRecenterTick by viewModel.currentRecenterTick.collectAsStateWithLifecycle()
    val radarCameraMemory = remember { RadarCameraMemory(currentRecenterTick) }
    val alertState by viewModel.alertState.collectAsStateWithLifecycle()
    val radarProvider by viewModel.radarProvider.collectAsStateWithLifecycle()
    val showLikelySnow by viewModel.showLikelySnow.collectAsStateWithLifecycle()
    val radarPlaybackSpeed by viewModel.radarPlaybackSpeed.collectAsStateWithLifecycle()
    val appAppearance by viewModel.appAppearance.collectAsStateWithLifecycle()
    val mapAppearance by viewModel.mapAppearance.collectAsStateWithLifecycle()
    val compassAppearance by viewModel.compassAppearance.collectAsStateWithLifecycle()
    val graphAppearance by viewModel.graphAppearance.collectAsStateWithLifecycle()
    val mapLayer by viewModel.mapLayer.collectAsStateWithLifecycle()
    val windArrowScale by viewModel.windArrowScale.collectAsStateWithLifecycle()
    val visibleWeatherMetrics by viewModel.visibleWeatherMetrics.collectAsStateWithLifecycle()
    val selectedWeather by viewModel.currentWeather.collectAsStateWithLifecycle()
    var weatherClock by remember { mutableStateOf(java.time.Instant.now().epochSecond) }
    LaunchedEffect(selectedWeather) {
        if (selectedWeather != null) while (true) {
            delay(60_000)
            weatherClock = java.time.Instant.now().epochSecond
        }
    }
    val scopedWeather = selectedWeather?.takeIf { it.first == selectedPlace?.let(::forecastSelectionKey) }?.second
    val currentWeather = scopedWeather?.takeIf { it.freshAt(weatherClock) }
    LaunchedEffect(selectedPlace?.id, scopedWeather?.fetchedEpochSeconds, weatherClock / 60) {
        val weather = scopedWeather ?: return@LaunchedEffect
        // Refresh point facts on source cadence and when the selected place enters a new
        // local day; a stale forecast value cannot freeze yesterday's sunrise in an open app.
        if (weatherClock - weather.fetchedEpochSeconds >= 900L ||
            (weather.solarDate() != null && weather.solarToday(weatherClock) == null))
            viewModel.refreshPointWeather()
    }
    val systemDark = isSystemInDarkTheme()
    val settingsMessage by viewModel.settingsMessage.collectAsStateWithLifecycle()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp
        Scaffold(
            containerColor = Background,
            contentColor = TextPrimary,
            contentWindowInsets = WindowInsets.safeDrawing,
            bottomBar = {
                if (!wide) {
                    NavigationBar(containerColor = Surface, modifier = Modifier.navigationBarsPadding()) {
                        Destination.entries.forEach { item ->
                            NavigationBarItem(
                                selected = destination == item,
                                onClick = { navigateTo(item) },
                                icon = { Icon(item.icon, contentDescription = null, modifier = Modifier.size(29.dp)) },
                                label = { Text(item.label) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                if (wide) {
                    NavigationRail(containerColor = Surface, modifier = Modifier.fillMaxHeight()) {
                        Spacer(Modifier.height(24.dp))
                        Destination.entries.forEach { item ->
                            NavigationRailItem(
                                selected = destination == item,
                                onClick = { navigateTo(item) },
                                icon = { Icon(item.icon, contentDescription = null) },
                                label = { Text(item.label) },
                            )
                        }
                    }
                }
                AnimatedContent(
                    targetState = destination,
                    transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                    label = "screen",
                    modifier = Modifier.fillMaxSize(),
                ) { screen ->
                    when (screen) {
                        Destination.NOW -> com.rainalarm.app.ui.NowScreen(
                            state, viewModel::refresh,
                            selectedPlace?.name ?: if (placesState.selectedId == CURRENT_LOCATION_ID) "Current location" else null,
                            placesState, locationState, viewModel::selectPlace, viewModel::useCurrentLocation,
                            currentWeather, visibleWeatherMetrics,
                            compassAppearance = compassAppearance,
                            graphAppearance = graphAppearance,
                            refreshStatus = refreshStatus,
                            visitGeneration = nowVisitGeneration,
                            selectedLocationKey = selectedPlace?.let(::forecastSelectionKey),
                            onChartTimeSelected = { epochSeconds ->
                                if (selectedPlace != null) {
                                    chartRequestSerial++
                                    pendingChartTime = RadarChartTimeRequest(chartRequestSerial,
                                        placesState.selectedId, epochSeconds)
                                    navigateTo(Destination.RADAR)
                                }
                            },
                        )
                        Destination.RADAR -> LiveRadarScreen(
                            place = selectedPlace,
                            liveMapPlace = liveMapPlace,
                            places = placesState,
                            playbackSpeed = radarPlaybackSpeed,
                            darkMap = mapAppearance.isDark(systemDark),
                            saveAndSelect = viewModel::saveAndSelect,
                            locationState = locationState,
                            currentRecenterTick = currentRecenterTick,
                            useCurrentLocation = viewModel::useCurrentLocation,
                            recenterToCurrentLocation = viewModel::recenterToCurrentLocation,
                            recenterToSelectedPlace = viewModel::recenterToSelectedPlace,
                            cameraMemory = radarCameraMemory,
                            selectPlace = viewModel::selectPlace,
                            mapLayer = mapLayer,
                            windArrowScale = windArrowScale,
                            selectMapLayer = viewModel::setMapLayer,
                            currentWeather = currentWeather,
                            refreshPointWeather = viewModel::refreshPointWeather,
                            selectedPlaceId = placesState.selectedId,
                            chartTimeRequest = pendingChartTime,
                            onChartTimeConsumed = { token ->
                                if (pendingChartTime?.token == token) pendingChartTime = null
                            },
                            showLikelySnow = showLikelySnow,
                        )
                        Destination.PLACES -> PlacesScreen(
                            collection = placesState,
                            locationState = locationState,
                            useCurrentLocation = viewModel::useCurrentLocation,
                            saveAndSelect = viewModel::saveAndSelect,
                            selectPlace = viewModel::selectPlace,
                            deletePlace = viewModel::deletePlace,
                            setPinned = viewModel::setPinned,
                            renamePlace = viewModel::renamePlace,
                            reorderPlaces = viewModel::reorderPlaces,
                        )
                        Destination.SETTINGS -> SettingsScreen(
                            selectedProvider = radarProvider,
                            selectProvider = viewModel::setRadarProvider,
                            showLikelySnow = showLikelySnow,
                            setShowLikelySnow = viewModel::setShowLikelySnow,
                            playbackSpeed = radarPlaybackSpeed,
                            selectPlaybackSpeed = viewModel::setRadarPlaybackSpeed,
                            appAppearance = appAppearance,
                            selectAppAppearance = viewModel::setAppAppearance,
                            mapAppearance = mapAppearance,
                            selectMapAppearance = viewModel::setMapAppearance,
                            compassAppearance = compassAppearance,
                            selectCompassAppearance = viewModel::setCompassAppearance,
                            graphAppearance = graphAppearance,
                            selectGraphAppearance = viewModel::setGraphAppearance,
                            mapLayer = mapLayer,
                            windArrowScale = windArrowScale,
                            selectWindArrowScale = viewModel::setWindArrowScale,
                            savedPlaces = placesState,
                            defaultStartupId = defaultStartupId,
                            setDefaultStartupId = viewModel::setDefaultStartupPlace,
                            alertSnapshot = alertState,
                            enableAlerts = viewModel::enableAlerts,
                            disableAlerts = viewModel::disableAlerts,
                            visibleMetrics = visibleWeatherMetrics,
                            setMetricVisible = viewModel::setWeatherMetricVisible,
                            message = settingsMessage,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NowScreen(state: ForecastUiState, refresh: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("RAIN ALARM", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (state is ForecastUiState.Ready) state.forecast.locationName else "Your forecast",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
            }
            IconButton(onClick = refresh, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh forecast", tint = Accent)
            }
        }
        Spacer(Modifier.height(20.dp))
        when (state) {
            ForecastUiState.Loading -> LoadingCard()
            is ForecastUiState.Error -> ErrorCard(state.message, refresh)
            is ForecastUiState.Ready -> ForecastContent(state.forecast)
        }
    }
}

@Composable
private fun LoadingCard() {
    Card(colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(24.dp)) {
        Row(Modifier.fillMaxWidth().padding(28.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(30.dp), color = Accent, strokeWidth = 3.dp)
            Spacer(Modifier.width(18.dp))
            Column {
                Text("Checking the next two hours", fontWeight = FontWeight.SemiBold)
                Text("Building your local rain timeline...", color = TextSecondary)
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String, refresh: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(24.dp)) {
            Text("Forecast unavailable", color = Danger, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(message, color = TextSecondary, modifier = Modifier.padding(vertical = 12.dp))
            Button(onClick = refresh) { Text("Try again") }
        }
    }
}

@Composable
private fun ForecastContent(forecast: ForecastSnapshot) {
    val summary = forecast.summary
    Card(
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(Accent))
                Spacer(Modifier.width(8.dp))
                val age = java.time.Duration.between(forecast.fetchedAt, java.time.Instant.now()).toMinutes().coerceAtLeast(0)
                Text(
                    if (forecast.isCached) "Cached - updated ${age} min ago" else "Updated just now",
                    color = if (forecast.isCached) Danger else TextSecondary,
                    fontSize = 13.sp,
                )
            }
            Spacer(Modifier.height(18.dp))
            Text(
                summary.headline,
                fontSize = 38.sp,
                lineHeight = 42.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            Text(summary.detail, color = TextSecondary, fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Metric(
                    if (forecast.isRadarNowcast) "RADAR" else "PEAK",
                    if (forecast.isRadarNowcast) "Nowcast" else String.format(Locale.UK, "%.1f mm/h", summary.peakRateMmPerHour ?: 0.0),
                )
                Metric("CHANCE", summary.peakProbabilityPercent?.let { "${it}%" } ?: "--")
            }
        }
    }
    if (forecast.isDemo) {
        Text(
            "Demo forecast - choose current location in Places to prepare live data.",
            color = Accent,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 14.dp),
        )
    }
    Timeline(forecast)
    Spacer(Modifier.height(18.dp))
    Text(
        if (forecast.isRadarNowcast) {
            "Point nowcast: ${forecast.sourceLabel}. Future radar frames remain a forecast, not an observation."
        } else {
            "Point forecast: ${forecast.sourceLabel} (model-derived). No forecast is an observation."
        },
        color = TextSecondary,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    )
}

@Composable
private fun RowScope.Metric(label: String, value: String) {
    Column(
        Modifier.weight(1f).background(Background, RoundedCornerShape(18.dp)).padding(16.dp),
    ) {
        Text(label, color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text(value, color = TextPrimary, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Timeline(forecast: ForecastSnapshot) {
    Text("NEXT 2 HOURS", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(10.dp))
    Card(
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth().semantics {
            contentDescription = "Two hour precipitation timeline in fifteen minute buckets"
        },
    ) {
        val visible = forecast.slots.take(9)
        val max = visible.maxOfOrNull { it.precipitationMm }?.coerceAtLeast(0.1) ?: 0.1
        Row(
            Modifier.fillMaxWidth().height(150.dp).padding(horizontal = 14.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            visible.forEachIndexed { index, slot ->
                val formatter = DateTimeFormatter.ofPattern("HH:mm")
                val time = formatter.format(slot.startsAt.atZone(ZoneId.systemDefault()))
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.weight(1f).width(12.dp).clip(RoundedCornerShape(8.dp))
                            .background(Border),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        Box(
                            Modifier.fillMaxWidth()
                                .fillMaxHeight((slot.precipitationMm / max).toFloat().coerceIn(0.04f, 1f))
                                .background(if (slot.precipitationMm >= 0.1) Accent else TextSecondary),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(if (index % 2 == 0) time else "", color = TextSecondary, fontSize = 9.sp)
                }
            }
        }
    }
}

@Composable
private fun RainAlarmTheme(dark: Boolean, content: @Composable () -> Unit) {
    val palette = if (dark) DarkRainPalette else LightRainPalette
    CompositionLocalProvider(LocalRainAlarmPalette provides palette) {
        MaterialTheme(colorScheme = palette.materialScheme(), content = content)
    }
}
