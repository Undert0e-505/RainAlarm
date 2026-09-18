package com.rainalarm.app.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.Geocoder
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.channels.awaitClose
import kotlin.math.*
import kotlin.coroutines.resume

class PlatformLocationClient(private val context: Context) {
    private val manager = context.getSystemService(LocationManager::class.java)

    private fun enabledForegroundProviders(): List<String> {
        val enabled = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        return LiveLocationPolicy.allowedProviders(enabled, fine)
    }

    fun hasForegroundPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

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

    @SuppressLint("MissingPermission")
    suspend fun currentOrLastKnown(): Location? {
        if (!hasForegroundPermission()) return null
        val now = System.currentTimeMillis()
        val cached = lastKnown()?.takeIf { LiveLocationPolicy.isFresh(it.time, now) }
        // A very recent OS fix is already a foreground-quality answer; do not wait for a
        // second provider that may take its full timeout while the retry UI is blocked.
        if (cached != null && LiveLocationPolicy.isImmediatelyUsable(cached.time, now))
            return cached
        // Network may be enabled but unable to produce a fix. Ask every permitted source
        // concurrently and accept the first fresh answer, cancelling remaining requests.
        val current = supervisorScope {
            val providers = enabledForegroundProviders()
            val results = Channel<Location?>(Channel.UNLIMITED)
            val jobs = providers.map { provider ->
                launch {
                    val fix = try { withTimeoutOrNull(8_000) { awaitCurrent(provider) } }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { null }
                    results.trySend(fix)
                }
            }
            try {
                repeat(providers.size) {
                    val fix = results.receive()
                    if (fix != null && LiveLocationPolicy.isFresh(fix.time, System.currentTimeMillis()))
                        return@supervisorScope fix
                }
                null
            } finally {
                jobs.forEach { it.cancel() }
                results.close()
            }
        }
        return current ?: cached?.takeIf { LiveLocationPolicy.isFresh(it.time, System.currentTimeMillis()) }
    }

    @SuppressLint("MissingPermission")
    fun foregroundUpdates(): Flow<Location> = callbackFlow {
        if (!hasForegroundPermission()) {
            close(IllegalStateException("Location permission is unavailable"))
            return@callbackFlow
        }
        val providers = enabledForegroundProviders()
        if (providers.isEmpty()) {
            close(IllegalStateException("Device location is unavailable"))
            return@callbackFlow
        }
        val listener = LocationListener { fix -> trySend(fix) }
        var registered = 0
        providers.forEach { provider ->
            try {
                manager.requestLocationUpdates(provider, LiveLocationPolicy.UPDATE_INTERVAL_MILLIS,
                    LiveLocationPolicy.UPDATE_DISTANCE_METRES, listener, Looper.getMainLooper())
                registered++
            } catch (_: SecurityException) {
                // A provider can reject this permission level independently. Retain any
                // working provider instead of discarding a valid live Current selection.
            }
        }
        if (registered == 0) {
            manager.removeUpdates(listener)
            close(IllegalStateException("No permitted device location update source is available"))
            return@callbackFlow
        }
        awaitClose { manager.removeUpdates(listener) }
    }

    @Suppress("DEPRECATION") // requestSingleUpdate is the API < 30 fallback only.
    @SuppressLint("MissingPermission")
    private suspend fun awaitCurrent(provider: String): Location? =
        suspendCancellableCoroutine { continuation ->
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

    @SuppressLint("MissingPermission")
    private fun lastKnown(): Location? =
        listOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        ).mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull { it.time }
}

/** Pure freshness and movement rules shared by foreground updates and worker decisions. */
object LiveLocationPolicy {
    const val MAX_FIX_AGE_MILLIS = 5 * 60 * 1000L
    const val MATERIAL_MOVE_METRES = 250.0
    const val UPDATE_INTERVAL_MILLIS = 7_500L
    const val UPDATE_DISTANCE_METRES = 0f
    const val ACTIVE_REFRESH_AFTER_MILLIS = 2 * 60 * 1000L
    const val IMMEDIATE_FIX_AGE_MILLIS = 30_000L
    const val REFRESH_CHECK_INTERVAL_MILLIS = 60_000L

    fun isNewerFix(fixTimeMillis: Long, lastAcceptedMillis: Long): Boolean =
        fixTimeMillis > lastAcceptedMillis

    fun allowedProviders(enabled: List<String>, fineGranted: Boolean): List<String> =
        enabled.filter { it != "gps" || fineGranted }

    fun isFresh(fixTimeMillis: Long, nowMillis: Long): Boolean =
        fixTimeMillis > 0 && nowMillis - fixTimeMillis in 0..MAX_FIX_AGE_MILLIS

    fun isImmediatelyUsable(fixTimeMillis: Long, nowMillis: Long): Boolean =
        fixTimeMillis > 0 && nowMillis - fixTimeMillis in 0..IMMEDIATE_FIX_AGE_MILLIS

    fun needsForegroundRefresh(fixTimeMillis: Long, nowMillis: Long): Boolean =
        fixTimeMillis <= 0 || nowMillis - fixTimeMillis >= ACTIVE_REFRESH_AFTER_MILLIS

    fun materiallyMoved(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Boolean {
        val radius = 6_371_000.0
        val dLat = Math.toRadians(toLat - fromLat)
        val dLon = Math.toRadians(toLon - fromLon)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(fromLat)) * cos(Math.toRadians(toLat)) * sin(dLon / 2).pow(2)
        return 2 * radius * asin(sqrt(a.coerceIn(0.0, 1.0))) >= MATERIAL_MOVE_METRES
    }
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
