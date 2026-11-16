/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue

/*
 * Live listening on headphones as the places that offer it see it (the equaliser page, and the capture screen's key
 * and panel): which outputs are offered and how they are labelled, what the service's status means for the controls,
 * how a start, a stop and a volume change touch the settings, and the two-step start that waits for the service to
 * adopt a new route. The service does the playing (AudioListeningController).
 */

/** The listen controls' inputs: the headphone outputs on offer and what the service says about listening. */
internal data class ListenUiState(
    /** Only headphone outputs: see [listenDevices]. */
    val devices: List<AudioListeningDevice> = emptyList(),
    val phase: AudioListeningPhase = AudioListeningPhase.DISABLED,
    /** The device the service plays to or was asked for, for the status line. */
    val deviceName: String? = null,
    val volumePercent: Int = 50,
    /** The settings' listening route is enabled and a headphone one, so the service's phase is about it. */
    val headphoneRoute: Boolean = false,
    /** Something else holds the microphone for now (the equaliser's snippet loop), so live listening rests. */
    val paused: Boolean = false,
)

/**
 * The outputs offered for live listening: never the phone's speaker, which would play the microphone back into
 * itself.
 */
internal fun listenDevices(outputs: List<AudioListeningDevice>): List<AudioListeningDevice> =
    outputs.filter { it.output != AudioListeningOutput.SPEAKER }

/**
 * The label of each device in [devices], by id: its name, and " (2)", " (3)" after the later ones (by id) when
 * different devices share a name, so two identical headsets can be told apart.
 */
internal fun listenDeviceLabels(devices: List<AudioListeningDevice>): Map<Int, String> =
    devices.groupBy { it.name }.values.flatMap { same ->
        same.sortedBy { it.id }.mapIndexed { index, device -> device.id to if (index == 0) device.name else "${device.name} (${index + 1})" }
    }.toMap()

/** The listen controls' state for the settings, the service's status and its outputs. */
internal fun listenUiState(
    settings: CameraSettings,
    status: AudioListeningStatus,
    outputs: List<AudioListeningDevice>,
    paused: Boolean = false,
): ListenUiState {
    val devices = listenDevices(outputs)
    val request = settings.audioListening
    val id = status.effectiveDeviceId ?: status.requestedDeviceId ?: settings.audioListeningOutputDeviceId
    // Of two identical headsets, the numbered label tells which one plays; the service only knows the product name.
    val numbered = devices.firstOrNull { it.id == id }?.let { device -> listenDeviceLabels(devices)[device.id]?.takeIf { it != device.name } }
    return ListenUiState(
        devices = devices,
        phase = status.phase,
        deviceName = numbered ?: status.deviceName ?: outputs.firstOrNull { it.id == id }?.name,
        volumePercent = request.volumePercent,
        headphoneRoute = request.enabled && request.output != AudioListeningOutput.SPEAKER,
        paused = paused,
    )
}

/** What the listen controls show. */
internal enum class ListenView {
    /** No headphones: the hint to connect some. */
    NO_OUTPUT,

    /** Headphones and nothing playing: a start button per device. */
    IDLE,

    /** Starting or playing on headphones: the status, the volume and Stop. */
    LIVE,

    /** The headphone route was lost or failed: what happened, and the start buttons again. */
    RETRY,
}

/** The phases in which the service holds an explicit arm: starting or playing. */
private val LISTEN_ARMED_PHASES = setOf(AudioListeningPhase.CONNECTING, AudioListeningPhase.WAITING_PCM, AudioListeningPhase.ACTIVE)
private val LISTEN_RETRY_PHASES = setOf(AudioListeningPhase.DISCONNECTED, AudioListeningPhase.FAILED, AudioListeningPhase.NO_OUTPUT)

/** The service is listening or about to: armed, whether or not sound reaches the headphones yet. */
internal fun listenArmed(status: AudioListeningStatus): Boolean = status.phase in LISTEN_ARMED_PHASES

/** Sound is reaching the listening output. */
internal fun listenAudible(status: AudioListeningStatus): Boolean = status.phase == AudioListeningPhase.ACTIVE

internal fun listenView(state: ListenUiState): ListenView = when {
    // A live route shows even when its device has just dropped off the list, until the service says it is lost.
    state.headphoneRoute && state.phase in LISTEN_ARMED_PHASES -> ListenView.LIVE
    state.devices.isEmpty() -> ListenView.NO_OUTPUT
    state.headphoneRoute && state.phase in LISTEN_RETRY_PHASES -> ListenView.RETRY
    else -> ListenView.IDLE
}

/** The status line, in the controls' own words rather than the service's phases. */
internal enum class ListenStatus { CONNECTING, ACTIVE, PAUSED, DISCONNECTED, FAILED, NOT_FOUND }

/** The status line for [state], or null when the controls show none (no route, or only the start buttons). */
internal fun listenStatus(state: ListenUiState): ListenStatus? = when (listenView(state)) {
    ListenView.LIVE -> when {
        state.paused -> ListenStatus.PAUSED
        state.phase == AudioListeningPhase.ACTIVE -> ListenStatus.ACTIVE
        else -> ListenStatus.CONNECTING
    }
    ListenView.RETRY -> when (state.phase) {
        AudioListeningPhase.DISCONNECTED -> ListenStatus.DISCONNECTED
        AudioListeningPhase.FAILED -> ListenStatus.FAILED
        else -> ListenStatus.NOT_FOUND
    }
    ListenView.NO_OUTPUT, ListenView.IDLE -> null
}

/** The start buttons wait while the service closes the previous output. */
internal fun listenStartEnabled(state: ListenUiState): Boolean = state.phase != AudioListeningPhase.RETIRING

/** Listening on [device]: enabled, routed to its class and to that device. */
internal fun CameraSettings.withListenStart(device: AudioListeningDevice): CameraSettings =
    copy(audioListening = audioListening.copy(enabled = true, output = device.output), audioListeningOutputDeviceId = device.id)

internal fun CameraSettings.withListenStop(): CameraSettings = copy(audioListening = audioListening.copy(enabled = false))

internal fun CameraSettings.withListenVolume(percent: Int): CameraSettings =
    copy(audioListening = audioListening.copy(volumePercent = percent.coerceIn(0, 100)))

/**
 * True when starting on [device] changes the listening route. The service adopts settings asynchronously and disarms
 * on a route change, so such a start must wait for [listenReadyToArm]; an unchanged route can be armed at once.
 */
internal fun CameraSettings.listenRouteChanges(device: AudioListeningDevice): Boolean =
    !audioListening.enabled || audioListening.output != device.output || audioListeningOutputDeviceId != device.id

/**
 * A start waiting on [deviceId] can be armed: the service has configured that route and waits for the connect action.
 * Device ids are unique, so the requested id alone names the route.
 */
internal fun listenReadyToArm(status: AudioListeningStatus, deviceId: Int): Boolean =
    status.phase == AudioListeningPhase.NEEDS_CONNECT && status.requestedDeviceId == deviceId

/**
 * The capture modes whose microphone can be listened to: those that record sound (slow motion and the other off-speed
 * takes are silent), RAW video included, with audio switched on.
 */
internal fun listenMode(mode: CaptureMode, settings: CameraSettings): Boolean =
    settings.captureWantsAudio(mode) || (mode == CaptureMode.RAW_VIDEO && settings.audioEnabled)

/**
 * The capture screen's listen key shows in a mode with sound when there is a headphone output to start on, or while
 * listening is armed so it can be stopped (even after its headphones dropped off the list).
 */
internal fun listenKeyVisible(
    mode: CaptureMode,
    settings: CameraSettings,
    status: AudioListeningStatus,
    outputs: List<AudioListeningDevice>,
): Boolean = listenMode(mode, settings) && (listenDevices(outputs).isNotEmpty() || listenArmed(status))

/** How the listen key looks: off, amber while armed but silent (connecting, or RAW video before a take), or on. */
internal enum class ListenKeyLook { OFF, PENDING, ON }

internal fun listenKeyLook(status: AudioListeningStatus): ListenKeyLook = when {
    listenAudible(status) -> ListenKeyLook.ON
    listenArmed(status) -> ListenKeyLook.PENDING
    else -> ListenKeyLook.OFF
}

/** What a tap on the listen key does. */
internal sealed interface ListenTapAction {
    /** Listening is armed: stop it. */
    data object Stop : ListenTapAction

    /** Start on [device]: the remembered one, or the only one. */
    data class Start(val device: AudioListeningDevice) : ListenTapAction

    /** No device to pick without asking: open the panel. */
    data object OpenPanel : ListenTapAction
}

internal fun listenKeyTapAction(
    settings: CameraSettings,
    status: AudioListeningStatus,
    outputs: List<AudioListeningDevice>,
): ListenTapAction {
    if (listenArmed(status)) return ListenTapAction.Stop
    val devices = listenDevices(outputs)
    val remembered = settings.audioListeningOutputDeviceId?.let { id -> devices.firstOrNull { it.id == id } }
    return when {
        remembered != null -> ListenTapAction.Start(remembered)
        devices.size == 1 -> ListenTapAction.Start(devices.single())
        else -> ListenTapAction.OpenPanel
    }
}

/** Start, stop and volume for the listen controls; see [rememberListenStarter]. */
internal class ListenStarter(
    val start: (AudioListeningDevice) -> Unit,
    val stop: () -> Unit,
    val volume: (Int) -> Unit,
)

/**
 * The capture screen's starter, provided by the root screen. The chrome reads it from here instead of taking it as a
 * parameter: its layout lambda already captures so many values that one more made D8 emit code ART's verifier rejects.
 */
internal val LocalListenStarter = staticCompositionLocalOf<ListenStarter?> { null }

/** The provided starter, or one of its own where nothing provides it (headless renders and tests). */
@Composable
internal fun currentListenStarter(settings: CameraSettings, status: AudioListeningStatus, onSettingsChange: (CameraSettings) -> Unit): ListenStarter =
    LocalListenStarter.current ?: rememberListenStarter(settings, status, onSettingsChange, LocalAudioListeningActions.current)

/**
 * The listen actions wired to the settings and the service. A start that changes the listening route waits for the
 * service to adopt it: the service reads the settings asynchronously and a route change disarms, so arming at once
 * would arm the old route and be undone. The service reports NEEDS_CONNECT for the new route once it has it, and that
 * is when [reconnect] arms it. The actions always read the latest [settings].
 */
@Composable
internal fun rememberListenStarter(
    settings: CameraSettings,
    status: AudioListeningStatus,
    onSettingsChange: (CameraSettings) -> Unit,
    reconnect: () -> Unit,
): ListenStarter {
    val latest by rememberUpdatedState(settings)
    val change by rememberUpdatedState(onSettingsChange)
    val arm by rememberUpdatedState(reconnect)
    var pending by remember { mutableStateOf<Int?>(null) }
    // Listening switched off elsewhere (the Settings switch) drops a waiting start, as Stop does: switching it back on
    // must not arm the old request. A start switches it on itself, so this never drops a live one.
    LaunchedEffect(settings.audioListening.enabled) {
        if (!settings.audioListening.enabled) pending = null
    }
    LaunchedEffect(pending, status) {
        val id = pending ?: return@LaunchedEffect
        if (listenReadyToArm(status, id)) {
            pending = null
            arm()
        }
    }
    return remember {
        ListenStarter(
            start = { device ->
                if (latest.listenRouteChanges(device)) {
                    pending = device.id
                    change(latest.withListenStart(device))
                } else {
                    // The service already has this route: one press arms it.
                    pending = null
                    arm()
                }
            },
            stop = { pending = null; change(latest.withListenStop()) },
            // Volume alone keeps the route, so the service keeps it armed.
            volume = { change(latest.withListenVolume(it)) },
        )
    }
}

/**
 * Leaving Settings for the capture screen asks first while listening is armed: the operator chooses whether monitoring
 * goes on, rather than finding it still playing once the page is gone. Moving within Settings, or to the gallery, never
 * asks.
 */
internal fun listenExitPrompt(from: AppSection, to: AppSection, status: AudioListeningStatus): Boolean =
    from == AppSection.SETTINGS && to == AppSection.CAPTURE && listenArmed(status)
