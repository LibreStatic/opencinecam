/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

internal fun locationPermission(context: Context): LocationPermissionPrecision? = when {
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED -> LocationPermissionPrecision.PRECISE
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED -> LocationPermissionPrecision.APPROXIMATE
    else -> null
}

/** Process-local cache only. Collection exists solely while an opted-in Activity is resumed.
 * No background permission, location service, persisted cache or location query during capture.
 */
internal class CaptureLocations private constructor(private val context: android.app.Application) {
    private val manager = context.getSystemService(LocationManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val owners = mutableSetOf<Any>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private data class Access(val enabled: Boolean, val precision: LocationPermissionPrecision?, val foreground: Boolean, val providers: Set<String>)
    private data class Sample(val fix: CaptureLocationFix, val elapsedNanos: Long)
    private var access: Access? = null
    @Volatile private var foreground = false
    @Volatile private var sample: Sample? = null
    private var generation = 0L
    private var listener: LocationListener? = null
    private val mutableStatus = MutableStateFlow(CaptureLocationStatus.INACTIVE)
    val status: StateFlow<CaptureLocationStatus> = mutableStatus


    fun setForeground(owner: Any, resumed: Boolean) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (resumed) owners.add(owner) else owners.remove(owner)
        foreground = owners.isNotEmpty()
        refreshAccess()
    }

    fun refreshAccess() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { refreshAccess() }; return }
        val enabled = SettingsRepositories.get(context).states.value.geotaggingEnabled
        val precision = locationPermission(context)
        // Availability is part of access: the foreground tick recovers when all providers
        // were off (there was no registered listener to receive an enabled callback).
        val providers = if (enabled && precision != null && foreground) candidates.filterTo(mutableSetOf()) {
            try { manager?.isProviderEnabled(it) == true }
            catch (_: SecurityException) { false }
            catch (_: IllegalArgumentException) { false }
        } else emptySet()
        val next = Access(enabled, precision, foreground, providers)
        if (next != access) {
            retire()
            access = next
            if (next.enabled && next.precision != null && next.foreground) subscribe(next.precision, next.providers)
        }
        updateStatus()
    }

    /** Caller can be a capture callback thread. Permission and consent are rechecked at admission. */
    fun snapshot(): CaptureLocationSnapshot? {
        val current = sample
        return admittedCaptureLocation(SettingsRepositories.get(context).states.value.geotaggingEnabled,
            locationPermission(context), foreground, current?.fix, current?.elapsedNanos ?: -1, SystemClock.elapsedRealtimeNanos())
    }

    private fun retire() {
        ++generation
        listener?.let { current -> try { manager?.removeUpdates(current) } catch (_: SecurityException) { } }
        listener = null; sample = null
        main.removeCallbacks(tick)
    }

    private val candidates = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER) +
        if (Build.VERSION.SDK_INT >= 31) listOf(LocationManager.FUSED_PROVIDER) else emptyList()

    private fun subscribe(precision: LocationPermissionPrecision, providers: Set<String>) {
        val ticket = generation
        val receiver = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (ticket != generation || !foreground || !SettingsRepositories.get(context).states.value.geotaggingEnabled || locationPermission(context) != precision) return
                if (!location.hasAccuracy()) return
                val fix = try { CaptureLocationFix(location.latitude, location.longitude, location.accuracy, location.time, precision) }
                    catch (_: IllegalArgumentException) { return }
                val at = location.elapsedRealtimeNanos
                val now = SystemClock.elapsedRealtimeNanos()
                if (at < 0 || at > now || sample?.elapsedNanos?.let { at < it } == true) return
                sample = Sample(fix, at)
                updateStatus()
            }
            override fun onProviderDisabled(provider: String) { changedProvider() }
            override fun onProviderEnabled(provider: String) { changedProvider() }
            @Deprecated("Legacy provider status callback")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
            private fun changedProvider() {
                if (ticket != generation) return
                refreshAccess()
            }
        }
        listener = receiver
        for (provider in providers) {
            try {
                if (manager?.isProviderEnabled(provider) == true) {
                    manager.requestLocationUpdates(provider, 10_000L, 5f, receiver, Looper.getMainLooper())
                    manager.getLastKnownLocation(provider)?.let(receiver::onLocationChanged)
                }
            } catch (_: SecurityException) { /* A provider may require more than the currently granted coarse permission. */ }
            catch (_: IllegalArgumentException) { /* This device does not implement this public provider. */ }
        }
        main.postDelayed(tick, 1_000)
    }

    private val tick = object : Runnable {
        override fun run() {
            refreshAccess()
            main.removeCallbacks(this)
            if (listener != null) main.postDelayed(this, 1_000)
        }
    }
    init { scope.launch { SettingsRepositories.get(context).states.collect { refreshAccess() } } }

    private fun updateStatus() { mutableStatus.value = snapshot()?.status ?: CaptureLocationStatus.INACTIVE }

    companion object {
        @Volatile private var instance: CaptureLocations? = null
        fun get(context: Context): CaptureLocations = instance ?: synchronized(this) {
            instance ?: CaptureLocations(context.applicationContext as android.app.Application).also { instance = it }
        }
    }
}
