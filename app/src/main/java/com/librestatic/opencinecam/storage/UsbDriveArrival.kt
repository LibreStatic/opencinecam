/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.storage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How long after a USB mass-storage device attaches its drive counts as starting rather than absent. Android announces the
 * device at once but mounts its volume only after the disk is ready, and a 3.5" disk needs tens of seconds to spin up.
 */
internal const val DRIVE_ARRIVAL_WINDOW_MILLIS = 45_000L

/**
 * Whether a USB device is mass storage: the device's own class (usually 0, "per interface") or any of its interfaces' is
 * `USB_CLASS_MASS_STORAGE`. Takes the class numbers so it can be tested without a device.
 */
internal fun isMassStorageDevice(deviceClass: Int, interfaceClasses: Iterable<Int>): Boolean =
    deviceClass == UsbConstants.USB_CLASS_MASS_STORAGE || interfaceClasses.any { it == UsbConstants.USB_CLASS_MASS_STORAGE }

/**
 * The USB storage devices attached in the last [windowMillis], by device name and attach time (elapsed-realtime
 * milliseconds the caller passes in). The window is per device, so a device that stays attached with no volume, such as a
 * hub's empty card reader, stops counting once its window closes. Not thread-safe.
 */
internal class DriveArrivalTracker(private val windowMillis: Long = DRIVE_ARRIVAL_WINDOW_MILLIS) {
    private val attachedAt = HashMap<String, Long>()

    fun attached(name: String, nowMillis: Long) { attachedAt[name] = nowMillis }

    fun detached(name: String) { attachedAt.remove(name) }

    /** Whether any device attached less than the window before [nowMillis]. */
    fun arriving(nowMillis: Long): Boolean = attachedAt.values.any { nowMillis - it < windowMillis }

    /** When the earliest window still open at [nowMillis] closes; null when none is open. */
    fun nextExpiryMillis(nowMillis: Long): Long? =
        attachedAt.values.map { it + windowMillis }.filter { it > nowMillis }.minOrNull()
}

/**
 * PLAN-072: whether a USB drive is starting, i.e. a mass-storage device attached in the last [DRIVE_ARRIVAL_WINDOW_MILLIS]
 * ([arriving]). Seeded with the storage devices attached now, counted as just attached, since the app is often launched
 * by the attach itself (the `.UsbDriveAttachAlias` activity). Reads only the device list and interface classes,
 * which need no USB permission. Everything runs on the main thread; [close] unregisters. Without a `UsbManager` or the USB
 * host feature [arriving] stays false.
 */
internal class UsbDriveArrivalMonitor(context: Context) : AutoCloseable {
    private val context = context.applicationContext
    private val usb: UsbManager? = this.context.getSystemService(UsbManager::class.java)
        ?.takeIf { this.context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST) }
    private val tracker = DriveArrivalTracker()
    private val handler = Handler(Looper.getMainLooper())
    private val expiry = Runnable { evaluate() }
    private val arrivingState = MutableStateFlow(false)
    private var receiver: BroadcastReceiver? = null

    val arriving: StateFlow<Boolean> = arrivingState.asStateFlow()

    init {
        if (usb != null) {
            val now = SystemClock.elapsedRealtime()
            storageDevices(usb).forEach { tracker.attached(it, now) }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context, intent: Intent) {
                    val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java) ?: return
                    when (intent.action) {
                        UsbManager.ACTION_USB_DEVICE_ATTACHED -> if (device.isMassStorage()) tracker.attached(device.deviceName, SystemClock.elapsedRealtime())
                        // A detached device's interfaces may no longer read; its name is enough to forget it.
                        UsbManager.ACTION_USB_DEVICE_DETACHED -> tracker.detached(device.deviceName)
                    }
                    evaluate()
                }
            }
            val filter = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            // Protected system broadcasts: only the system sends them, so exporting the receiver admits nothing else.
            // Without a handler they are delivered on the main thread, like the expiry callback.
            ContextCompat.registerReceiver(this.context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
            this.receiver = receiver
            evaluate()
        }
    }

    override fun close() {
        handler.removeCallbacks(expiry)
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
    }

    /** Publishes the state now and schedules the next re-evaluation for when the earliest open window closes. */
    private fun evaluate() {
        val now = SystemClock.elapsedRealtime()
        arrivingState.value = tracker.arriving(now)
        handler.removeCallbacks(expiry)
        tracker.nextExpiryMillis(now)?.let { handler.postDelayed(expiry, it - now) }
    }

    /** The names of the mass-storage devices attached now. */
    private fun storageDevices(usb: UsbManager): List<String> = runCatching {
        usb.deviceList.values.filter { it.isMassStorage() }.map { it.deviceName }
    }.onFailure { Log.w(LOG_TAG, "The USB devices could not be read", it) }.getOrDefault(emptyList())

    private fun UsbDevice.isMassStorage(): Boolean = isMassStorageDevice(deviceClass, (0 until interfaceCount).map { getInterface(it).interfaceClass })

    private companion object {
        const val LOG_TAG = "UsbDriveArrival"
    }
}
