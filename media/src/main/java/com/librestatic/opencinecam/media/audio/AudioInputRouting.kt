/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.media.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRouting
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.nio.ByteBuffer

/**
 * Identity of an audio input that survives reconnection. [AudioDeviceInfo.getId] changes every
 * time a USB microphone is plugged in, so a remembered selection is stored by type, product name
 * and address instead (OCC-AUDIO-009, OCC-AUDIO-012).
 */
data class AudioInputKey(val type: Int, val productName: String, val address: String) {
    val isExternal: Boolean get() = isExternalInputType(type)
    val kind: AudioInputKind get() = audioInputKind(type)
}

/** The short family shown in the HUD badge and next to the device name in Settings. */
enum class AudioInputKind(val badge: String) { BUILT_IN("INT"), USB("USB"), BLUETOOTH("BT"), WIRED("WIRED"), OTHER("EXT") }

fun audioInputKind(type: Int): AudioInputKind = when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_MIC -> AudioInputKind.BUILT_IN
    AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY -> AudioInputKind.USB
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> AudioInputKind.BLUETOOTH
    AudioDeviceInfo.TYPE_WIRED_HEADSET -> AudioInputKind.WIRED
    else -> AudioInputKind.OTHER
}

/**
 * Android also lists ports that are not microphones as inputs: call audio, internal capture
 * (remote submix), the FM and TV tuners and the echo-canceller reference. None can record a take.
 */
fun isCaptureInputType(type: Int): Boolean = type !in NON_CAPTURE_INPUT_TYPES

// TYPE_ECHO_REFERENCE is API 31; the literal keeps the check valid on older releases.
private val NON_CAPTURE_INPUT_TYPES = setOf(
    AudioDeviceInfo.TYPE_TELEPHONY,
    AudioDeviceInfo.TYPE_REMOTE_SUBMIX,
    AudioDeviceInfo.TYPE_FM_TUNER,
    AudioDeviceInfo.TYPE_TV_TUNER,
    28,
)

fun isExternalInputType(type: Int): Boolean = audioInputKind(type).let {
    it == AudioInputKind.USB || it == AudioInputKind.WIRED || it == AudioInputKind.BLUETOOTH
}

/**
 * Exact type+product+address first. USB microphones may report a different address on another
 * port, so a unique type+product match is accepted next. Two identical products on different
 * ports with neither address matching are ambiguous and resolve to nothing.
 */
fun resolveAudioInput(key: AudioInputKey, inputs: List<SelectableAudioInput>): SelectableAudioInput? {
    inputs.firstOrNull { it.key == key }?.let { return it }
    val sameProduct = inputs.filter { it.type == key.type && it.label == key.productName }
    return sameProduct.singleOrNull()
}

/**
 * "Auto": an external USB or wired microphone wins, then the built-in one. Bluetooth headset
 * microphones record narrowband mono (8 or 16 kHz), and earbuds connected only for listening would
 * otherwise take over the take, so Auto picks one only when it is the sole input; choose it by hand.
 */
fun preferredAutoInput(inputs: List<SelectableAudioInput>): SelectableAudioInput? =
    inputs.minByOrNull { autoRank(it.type) }

private fun autoRank(type: Int): Int = when (audioInputKind(type)) {
    AudioInputKind.USB, AudioInputKind.WIRED -> 0
    AudioInputKind.BUILT_IN -> 1
    AudioInputKind.OTHER -> 2
    AudioInputKind.BLUETOOTH -> 3
}

/** What happens to a take when its audio input disappears or the platform reroutes it. */
enum class AudioInputLossPolicy { STOP_TAKE, FALLBACK_BUILTIN, CONTINUE_SILENT }

/** The capture path a guard watches. MediaRecorder exposes no PCM, so it cannot write silence. */
enum class AudioCapturePath { AUDIO_RECORD, MEDIA_RECORDER, PREVIEW }

enum class AudioInputLossAction { STOP_TAKE, FALLBACK, SILENCE, IGNORE }

fun decideInputLoss(policy: AudioInputLossPolicy, path: AudioCapturePath): AudioInputLossAction = when {
    path == AudioCapturePath.PREVIEW -> AudioInputLossAction.IGNORE
    policy == AudioInputLossPolicy.STOP_TAKE -> AudioInputLossAction.STOP_TAKE
    policy == AudioInputLossPolicy.FALLBACK_BUILTIN -> AudioInputLossAction.FALLBACK
    path == AudioCapturePath.MEDIA_RECORDER -> AudioInputLossAction.STOP_TAKE
    else -> AudioInputLossAction.SILENCE
}

enum class RouteVerdict { UNCONFIRMED, CONFIRMED, LOST }

/** Pure route check: a specific requested input must be the routed one, and must still be connected. */
fun classifyRoute(requestedId: Int?, routedId: Int?, requestedConnected: Boolean): RouteVerdict = when {
    requestedId == null -> if (routedId == null) RouteVerdict.UNCONFIRMED else RouteVerdict.CONFIRMED
    !requestedConnected -> RouteVerdict.LOST
    routedId == null -> RouteVerdict.UNCONFIRMED
    routedId == requestedId -> RouteVerdict.CONFIRMED
    else -> RouteVerdict.LOST
}

/** Zeroes [bytes] of interleaved PCM in place. Zero is silence for signed integer and float PCM alike. */
fun silencePcm(buffer: ByteBuffer, bytes: Int) {
    for (index in 0 until bytes) buffer.put(index, 0)
}

/** The input a capture is actually using, as the HUD and the metadata report it. */
data class ActiveAudioInput(
    val deviceId: Int?,
    val key: AudioInputKey?,
    val label: String?,
    val type: Int?,
    /** The routed device is the requested one (or nothing specific was requested and a route exists). */
    val confirmed: Boolean,
    /** The requested input was lost and the take continues on the platform default device. */
    val fallback: Boolean = false,
    /** The requested input was lost and the take continues writing silence. */
    val silenced: Boolean = false,
)

data class AudioRouteChange(val elapsedRealtimeMs: Long, val deviceId: Int?, val type: Int?, val label: String?, val reason: String)

fun AudioDeviceInfo.inputKey(): AudioInputKey = AudioInputKey(type, productName?.toString().orEmpty(), address.orEmpty())

/**
 * Watches one capture's routing. Every reader attaches it after start: it reads the routed device,
 * listens for routing changes and for the requested device being unplugged, and applies the loss
 * policy once. A CONTINUE_SILENT decision is honoured by readers through [silenceIfMuted].
 */
class AudioInputRouteGuard(
    context: Context,
    private val requested: AudioDeviceInfo?,
    private val policy: AudioInputLossPolicy,
    /** True when the take starts with the remembered input missing and FALLBACK/SILENCE was applied. */
    private val startDegraded: AudioInputLossAction = AudioInputLossAction.IGNORE,
    private val onRoute: (ActiveAudioInput) -> Unit = {},
    private val onLoss: (AudioInputLossAction) -> Unit = {},
) {
    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    @Volatile var muted: Boolean = startDegraded == AudioInputLossAction.SILENCE
        private set
    @Volatile private var fallback = startDegraded == AudioInputLossAction.FALLBACK
    @Volatile private var lost = startDegraded == AudioInputLossAction.FALLBACK || startDegraded == AudioInputLossAction.SILENCE
    @Volatile private var path = AudioCapturePath.AUDIO_RECORD
    private var routing: AudioRouting? = null
    private val changes = mutableListOf<AudioRouteChange>()
    private val routingListener = AudioRouting.OnRoutingChangedListener { evaluate(it.routedDevice, "routing-changed") }
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            if (requested != null && removedDevices.any { it.id == requested.id }) {
                evaluate(routing?.routedDevice, "requested-input-removed", requestedConnected = false)
            }
        }
    }

    init {
        if (startDegraded == AudioInputLossAction.FALLBACK || startDegraded == AudioInputLossAction.SILENCE) {
            record(null, "requested-input-missing-at-start")
        }
    }

    val requestedDeviceId: Int? get() = requested?.id
    val requestedKey: AudioInputKey? get() = requested?.inputKey()

    @Synchronized fun routeChanges(): List<AudioRouteChange> = changes.toList()

    /** Call after the reader started. Safe to call once per guard. */
    fun attach(target: AudioRouting, capturePath: AudioCapturePath) {
        path = capturePath
        routing = target
        target.addOnRoutingChangedListener(routingListener, handler)
        audioManager.registerAudioDeviceCallback(deviceCallback, handler)
        // A take that started without its input cannot write silence through MediaRecorder.
        if (startDegraded == AudioInputLossAction.SILENCE &&
            decideInputLoss(policy, capturePath) == AudioInputLossAction.STOP_TAKE) {
            muted = false
            onLoss(AudioInputLossAction.STOP_TAKE)
        }
        evaluate(target.routedDevice, "started")
        // Some HALs publish the route a few hundred milliseconds after start.
        handler.postDelayed({ if (routing === target) evaluate(target.routedDevice, "settled") }, ROUTE_SETTLE_MS)
    }

    fun detach() {
        val target = routing ?: return
        routing = null
        handler.removeCallbacksAndMessages(null)
        runCatching { target.removeOnRoutingChangedListener(routingListener) }
        runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
    }

    fun silenceIfMuted(buffer: ByteBuffer, bytes: Int) {
        if (muted) silencePcm(buffer, bytes)
    }

    @Synchronized private fun evaluate(routed: AudioDeviceInfo?, reason: String, requestedConnected: Boolean = requestedStillConnected()) {
        val verdict = classifyRoute(requested?.id, routed?.id, requestedConnected)
        record(routed, reason)
        if (verdict == RouteVerdict.LOST && !lost) {
            lost = true
            val action = decideInputLoss(policy, path)
            when (action) {
                AudioInputLossAction.SILENCE -> muted = true
                AudioInputLossAction.FALLBACK -> fallback = true
                AudioInputLossAction.STOP_TAKE, AudioInputLossAction.IGNORE -> Unit
            }
            onLoss(action)
        }
        onRoute(ActiveAudioInput(
            deviceId = routed?.id,
            key = routed?.inputKey(),
            label = routed?.productName?.toString(),
            type = routed?.type,
            confirmed = !lost && verdict == RouteVerdict.CONFIRMED,
            fallback = fallback,
            silenced = muted,
        ))
    }

    @Synchronized private fun record(routed: AudioDeviceInfo?, reason: String) {
        val last = changes.lastOrNull()
        if (last != null && last.deviceId == routed?.id && reason == "settled") return
        changes += AudioRouteChange(SystemClock.elapsedRealtime(), routed?.id, routed?.type, routed?.productName?.toString(), reason)
    }

    private fun requestedStillConnected(): Boolean = requested == null ||
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).any { it.id == requested.id }

    companion object {
        private const val ROUTE_SETTLE_MS = 400L

        /** Resolves a runtime device id to the live [AudioDeviceInfo], or null when it is gone. */
        fun inputDevice(context: Context, id: Int?): AudioDeviceInfo? = id?.let { requestedId ->
            context.applicationContext.getSystemService(AudioManager::class.java)
                .getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == requestedId }
        }
    }
}
