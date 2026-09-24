package com.rainalarm.app.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.LocationSettingsStatusCodes
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Task
import java.util.concurrent.CancellationException as TaskCancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class LocationPermissionState(val coarse: Boolean, val fine: Boolean) {
    val any: Boolean get() = coarse || fine
}

sealed interface LocationEngineEvent {
    data class Fix(val value: NavigationLocationFix) : LocationEngineEvent
    data class ApproximatePermission(val message: String) : LocationEngineEvent
    data class DisabledSettings(val message: String) : LocationEngineEvent
    data class Unavailable(val message: String) : LocationEngineEvent
}

private class HighAccuracySettingsException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/**
 * One process-wide foreground location gateway. Google fused location is primary; framework GPS
 * is the precise fallback. NETWORK_PROVIDER is used only as an explicitly provisional Current
 * fallback and is never registered beside GPS, so it cannot displace a good GPS fix by recency.
 */
class PlatformLocationClient(private val context: Context) {
    private val manager = context.getSystemService(LocationManager::class.java)
    private val fused: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(context)
    }

    fun permissions(): LocationPermissionState = LocationPermissionState(
        coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED,
        fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED,
    )

    fun hasForegroundPermission(): Boolean = permissions().any
    fun hasPrecisePermission(): Boolean = permissions().fine

    @Suppress("DEPRECATION")
    suspend fun localityOrFallback(latitude: Double, longitude: Double): String = withContext(Dispatchers.IO) {
        val fallback = LocationNameResolver.fallback(latitude, longitude)
        if (!Geocoder.isPresent()) return@withContext fallback
        runCatching {
            val address = Geocoder(context).getFromLocation(latitude, longitude, 1)?.firstOrNull()
            LocationNameResolver.preferred(
                address?.locality, address?.subAdminArea, address?.adminArea, fallback,
            )
        }.getOrDefault(fallback)
    }

    fun foregroundEvents(profile: LocationRequestProfile): Flow<LocationEngineEvent> = flow {
        if (profile.mode == LocationRequestMode.OFF) return@flow
        val permission = permissions()
        if (!permission.any) {
            emit(LocationEngineEvent.Unavailable(
                "Location permission is unavailable. Grant foreground location or select a saved place.",
            ))
            return@flow
        }
        if (profile.requiresFinePermission && !permission.fine) {
            emit(LocationEngineEvent.ApproximatePermission(
                "Precise location is required for Follow. Enable precise location in Android settings.",
            ))
            return@flow
        }

        var fusedFailure: Throwable? = null
        if (fusedAvailable()) {
            try {
                currentFused(profile, permission)?.let { emit(LocationEngineEvent.Fix(it)) }
                emitAll(fusedUpdates(profile, permission))
                return@flow
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                fusedFailure = failure
            }
        }

        try {
            currentFramework(profile, permission)?.let { emit(LocationEngineEvent.Fix(it)) }
            emitAll(frameworkUpdates(profile, permission))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            val settingsFailure = sequenceOf(failure, fusedFailure).filterNotNull().any {
                it is HighAccuracySettingsException
            }
            emit(if (settingsFailure) {
                LocationEngineEvent.DisabledSettings(
                    "High-accuracy location is disabled. Turn on precise device location and try again.",
                )
            } else {
                LocationEngineEvent.Unavailable("Live location updates are unavailable.")
            })
        }
    }

    private fun fusedAvailable(): Boolean = runCatching {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    }.getOrDefault(false)

    private fun fusedRequest(profile: LocationRequestProfile, permission: LocationPermissionState): LocationRequest =
        LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, profile.intervalMillis)
            .setMinUpdateIntervalMillis(profile.fastestIntervalMillis)
            .setMaxUpdateDelayMillis(profile.maxDelayMillis)
            .setMaxUpdateAgeMillis(profile.initialMaxAgeMillis)
            .setMinUpdateDistanceMeters(0f)
            .setWaitForAccurateLocation(profile.waitForAccurateFix && permission.fine)
            .setGranularity(if (permission.fine) Granularity.GRANULARITY_FINE else Granularity.GRANULARITY_COARSE)
            .build()

    @SuppressLint("MissingPermission")
    private suspend fun currentFused(
        profile: LocationRequestProfile,
        permission: LocationPermissionState,
    ): NavigationLocationFix? {
        val request = fusedRequest(profile, permission)
        verifySettings(request)
        val cancellation = CancellationTokenSource()
        val currentRequest = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setGranularity(if (permission.fine) Granularity.GRANULARITY_FINE else Granularity.GRANULARITY_COARSE)
            .setMaxUpdateAgeMillis(profile.initialMaxAgeMillis)
            .setDurationMillis(10_000)
            .build()
        val result = withTimeoutOrNull(11_000) {
            fused.getCurrentLocation(currentRequest, cancellation.token).awaitTask {
                cancellation.cancel()
            }
        }
        return result?.toNavigation(LocationFixSource.FUSED, permission.fine)
    }

    @SuppressLint("MissingPermission")
    private fun fusedUpdates(
        profile: LocationRequestProfile,
        permission: LocationPermissionState,
    ): Flow<LocationEngineEvent> = callbackFlow {
        val request = fusedRequest(profile, permission)
        try {
            verifySettings(request)
        } catch (failure: Throwable) {
            close(failure)
            return@callbackFlow
        }
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach { location ->
                    trySend(LocationEngineEvent.Fix(
                        location.toNavigation(LocationFixSource.FUSED, permission.fine),
                    ))
                }
            }
        }
        val registration = fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
        registration.addOnFailureListener { close(it) }
        registration.addOnCanceledListener { close(CancellationException("Fused location request cancelled")) }
        awaitClose { fused.removeLocationUpdates(callback) }
    }

    private suspend fun verifySettings(request: LocationRequest) {
        val settingsRequest = LocationSettingsRequest.Builder().addLocationRequest(request).build()
        try {
            LocationServices.getSettingsClient(context).checkLocationSettings(settingsRequest).awaitTask()
        } catch (failure: ApiException) {
            if (failure.statusCode == LocationSettingsStatusCodes.RESOLUTION_REQUIRED ||
                failure.statusCode == LocationSettingsStatusCodes.SETTINGS_CHANGE_UNAVAILABLE
            ) throw HighAccuracySettingsException("High-accuracy settings are disabled", failure)
            throw failure
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun currentFramework(
        profile: LocationRequestProfile,
        permission: LocationPermissionState,
    ): NavigationLocationFix? {
        val provider = frameworkProvider(profile, permission)
            ?: throw HighAccuracySettingsException("No suitable framework provider is enabled")
        val source = if (provider == LocationManager.GPS_PROVIDER) LocationFixSource.GPS else LocationFixSource.NETWORK
        val live = withTimeoutOrNull(10_000) { awaitCurrent(provider) }
        if (live != null) return live.toNavigation(source, permission.fine)
        return runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
            ?.takeIf { System.currentTimeMillis() - it.time in 0..profile.initialMaxAgeMillis }
            ?.toNavigation(source, permission.fine)
    }

    @SuppressLint("MissingPermission")
    private fun frameworkUpdates(
        profile: LocationRequestProfile,
        permission: LocationPermissionState,
    ): Flow<LocationEngineEvent> = callbackFlow {
        val provider = frameworkProvider(profile, permission)
        if (provider == null) {
            close(HighAccuracySettingsException("No suitable framework provider is enabled"))
            return@callbackFlow
        }
        val source = if (provider == LocationManager.GPS_PROVIDER) LocationFixSource.GPS else LocationFixSource.NETWORK
        val listener = LocationListener { location ->
            trySend(LocationEngineEvent.Fix(location.toNavigation(source, permission.fine)))
        }
        try {
            manager.requestLocationUpdates(
                provider, profile.intervalMillis, 0f, listener, Looper.getMainLooper(),
            )
        } catch (failure: SecurityException) {
            close(failure)
            return@callbackFlow
        }
        awaitClose { manager.removeUpdates(listener) }
    }

    private fun frameworkProvider(
        profile: LocationRequestProfile,
        permission: LocationPermissionState,
    ): String? {
        val gps = permission.fine && runCatching {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        }.getOrDefault(false)
        if (gps) return LocationManager.GPS_PROVIDER
        if (profile.mode != LocationRequestMode.FOLLOW && permission.coarse && runCatching {
                manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }.getOrDefault(false)
        ) return LocationManager.NETWORK_PROVIDER
        return null
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private suspend fun awaitCurrent(provider: String): Location? = suspendCancellableCoroutine { continuation ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            manager.getCurrentLocation(provider, signal, context.mainExecutor) { location ->
                if (continuation.isActive) continuation.resume(location)
            }
        } else {
            lateinit var listener: LocationListener
            listener = LocationListener { location ->
                manager.removeUpdates(listener)
                if (continuation.isActive) continuation.resume(location)
            }
            continuation.invokeOnCancellation { manager.removeUpdates(listener) }
            manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
        }
    }

    private fun Location.toNavigation(source: LocationFixSource, fine: Boolean) = NavigationLocationFix(
        latitude = latitude,
        longitude = longitude,
        wallTimeMillis = time,
        elapsedRealtimeNanos = elapsedRealtimeNanos,
        accuracyMetres = if (hasAccuracy()) accuracy else Float.MAX_VALUE,
        source = source,
        finePermission = fine,
    )
}

private suspend fun <T> Task<T>.awaitTask(onCancel: () -> Unit = {}): T =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { onCancel() }
        addOnSuccessListener { value -> if (continuation.isActive) continuation.resume(value) }
        addOnFailureListener { failure -> if (continuation.isActive) continuation.resumeWithException(failure) }
        addOnCanceledListener {
            if (continuation.isActive) continuation.resumeWithException(
                TaskCancellationException("Google Play services task cancelled"),
            )
        }
    }

/** Compatibility helpers retained for worker freshness and existing pure tests. */
object LiveLocationPolicy {
    const val MAX_FIX_AGE_MILLIS = 5 * 60 * 1000L
    const val MATERIAL_MOVE_METRES = WeatherAnalysisAnchorPolicy.ordinaryDistanceMetres
    const val UPDATE_INTERVAL_MILLIS = 5_000L
    const val UPDATE_DISTANCE_METRES = 0f
    const val ACTIVE_REFRESH_AFTER_MILLIS = 2 * 60 * 1000L
    const val IMMEDIATE_FIX_AGE_MILLIS = 5_000L
    const val REFRESH_CHECK_INTERVAL_MILLIS = 15_000L

    fun isNewerFix(fixTimeMillis: Long, lastAcceptedMillis: Long): Boolean = fixTimeMillis > lastAcceptedMillis

    fun allowedProviders(enabled: List<String>, fineGranted: Boolean): List<String> = when {
        fineGranted && "gps" in enabled -> listOf("gps")
        "network" in enabled -> listOf("network")
        else -> emptyList()
    }

    fun isFresh(fixTimeMillis: Long, nowMillis: Long): Boolean =
        fixTimeMillis > 0 && nowMillis - fixTimeMillis in 0..MAX_FIX_AGE_MILLIS

    fun isImmediatelyUsable(fixTimeMillis: Long, nowMillis: Long): Boolean =
        fixTimeMillis > 0 && nowMillis - fixTimeMillis in 0..IMMEDIATE_FIX_AGE_MILLIS

    fun needsForegroundRefresh(fixTimeMillis: Long, nowMillis: Long): Boolean =
        fixTimeMillis <= 0 || nowMillis - fixTimeMillis >= ACTIVE_REFRESH_AFTER_MILLIS

    fun materiallyMoved(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Boolean =
        distanceMetres(fromLat, fromLon, toLat, toLon) >= MATERIAL_MOVE_METRES
}

/** In-process only: never writes coordinates or implies background-location permission. */
object ForegroundLocationSnapshot {
    @Volatile private var foreground = false
    @Volatile private var place: SavedPlace? = null
    @Volatile private var fixTimeMillis: Long = 0

    fun setForeground(value: Boolean) {
        foreground = value
        if (!value) { place = null; fixTimeMillis = 0 }
    }

    fun update(value: SavedPlace, timeMillis: Long) {
        if (foreground && value.isCurrentLocation) { place = value; fixTimeMillis = timeMillis }
    }

    fun clear() { place = null; fixTimeMillis = 0 }

    fun freshPlace(nowMillis: Long = System.currentTimeMillis()): SavedPlace? =
        if (foreground && LiveLocationPolicy.isFresh(fixTimeMillis, nowMillis)) place else null
}
