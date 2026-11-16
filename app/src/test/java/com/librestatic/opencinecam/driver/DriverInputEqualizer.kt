/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.driver

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.librestatic.opencinecam.settings.ActiveEqProfile
import com.librestatic.opencinecam.settings.AudioListeningDevice
import com.librestatic.opencinecam.settings.AudioListeningPhase
import com.librestatic.opencinecam.settings.AudioListeningStatus
import com.librestatic.opencinecam.settings.CameraSettings
import com.librestatic.opencinecam.settings.EqSnippetPhase
import com.librestatic.opencinecam.settings.EqSnippetUiState
import com.librestatic.opencinecam.settings.EQ_GRAPH_SAMPLE_RATE_HZ
import com.librestatic.opencinecam.settings.InputEqualizerContent
import com.librestatic.opencinecam.settings.InputEqualizerUiState
import com.librestatic.opencinecam.settings.activeEqProfile
import com.librestatic.opencinecam.settings.eqListenUiState
import com.librestatic.opencinecam.camera.AudioSpectrum
import com.librestatic.opencinecam.camera.EqCurve
import com.librestatic.opencinecam.camera.EqFilterType
import com.librestatic.opencinecam.camera.EqPoint
import com.librestatic.opencinecam.camera.EqProfile
import com.librestatic.opencinecam.camera.responseDb
import com.librestatic.opencinecam.settings.withDeletedEqProfile
import com.librestatic.opencinecam.settings.withEditedEqCurve
import com.librestatic.opencinecam.settings.withListenStart
import com.librestatic.opencinecam.settings.withListenStop
import com.librestatic.opencinecam.settings.withListenVolume
import com.librestatic.opencinecam.settings.withEqProfile
import com.librestatic.opencinecam.settings.withResetEq
import com.librestatic.opencinecam.settings.withSavedEqProfile
import kotlin.math.exp
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sin

/** The Razr's bundled profile as `BundledEqProfiles` ships it (PLAN-074 decision 8). */
internal val DriverReferenceEqProfile = EqProfile(
    "bundled-motorola-blanc", "Razr Fold microphones",
    EqCurve(listOf(
        EqPoint(EqFilterType.BELL, 10_000.0, -9.5, 1.1),
        EqPoint(EqFilterType.BELL, 8_100.0, -9.5, 1.8),
        EqPoint(EqFilterType.HIGH_SHELF, 13_500.0, -3.5, 0.9),
    )),
    bundled = true, device = "blanc",
)

/** A user profile for a lavalier: rumble cut, less boxiness, presence and air. */
internal val DriverLavalierEqProfile = EqProfile(
    "u-lavalier", "Lavalier voice",
    EqCurve(listOf(
        EqPoint(EqFilterType.HIGH_PASS, 80.0, 0.0, 0.71),
        EqPoint(EqFilterType.BELL, 250.0, -3.0, 1.4),
        EqPoint(EqFilterType.BELL, 3_200.0, 2.5, 1.0),
        EqPoint(EqFilterType.HIGH_SHELF, 10_000.0, 1.5, 0.71),
    ), trimDb = -1.0),
)

internal val DriverInterviewEqProfile = EqProfile("u-interview", "Interview room", EqCurve(listOf(EqPoint(EqFilterType.NOTCH, 60.0, 0.0, 8.0))))

/** One-sixth octave bands from 25 Hz to 20 kHz, as the analyser's bands are spaced. */
private val DriverSpectrumBands: List<Float> = (0..58).map { (25.0 * 2.0.pow(it / 6.0)).toFloat() }.filter { it <= 20_000f }

/**
 * A speech-like input (a low-mid hump falling off above 4 kHz, with some ripple) and the same through [curve], so
 * the "after" layer follows the nodes the way the service's analyser would.
 */
internal fun driverSpectrum(curve: EqCurve): AudioSpectrum {
    val input = DriverSpectrumBands.map { f ->
        val octave = log2(f / 300.0)
        (-34.0 - 2.2 * octave * octave - (if (f > 4_000f) 6.0 * log2(f / 4_000.0) else 0.0) + 2.5 * sin(octave * 3.1)).toFloat()
    }
    val output = DriverSpectrumBands.zip(input) { f, level -> (level + curve.responseDb(f.toDouble(), EQ_GRAPH_SAMPLE_RATE_HZ)).toFloat() }
    return AudioSpectrum(DriverSpectrumBands, input, output, capturedAtElapsedRealtimeMs = 0L)
}

/**
 * Held peaks over [spectrum]'s "after" layer: louder moments a few dB above it, more in the voice's presence region.
 * [clipped] pushes the presence peaks to full scale, as a curve with too much boost there would.
 */
internal fun driverPeaks(spectrum: AudioSpectrum, clipped: Boolean = false): List<Float> =
    spectrum.bandCentresHz.zip(spectrum.outputDb) { f, level ->
        val presence = exp(-log2(f / 3_000.0).let { it * it } * 1.5)
        (level + 5f + 4f * presence.toFloat() + (if (clipped) 48f * presence.toFloat() else 0f)).coerceAtMost(0f)
    }

/** The length of the fake snippet the playing screen loops, and that a stop keeps when the fake caught less. */
internal const val DRIVER_SNIPPET_MS = 7_000

/** The USB-C headset and Bluetooth earbuds of the listen screens, and the phone's speaker the card must not offer. */
internal val DriverUsbHeadset = AudioListeningDevice(21, "USB-C Headset", com.librestatic.opencinecam.settings.AudioListeningOutput.WIRED_USB)
internal val DriverBluetoothBuds = AudioListeningDevice(34, "Pixel Buds Pro", com.librestatic.opencinecam.settings.AudioListeningOutput.BLUETOOTH)
internal val DriverPhoneSpeaker = AudioListeningDevice(2, "Phone speaker", com.librestatic.opencinecam.settings.AudioListeningOutput.SPEAKER)

/**
 * The input equaliser page over its own settings and a fake profile list, applying the page's real editing rules
 * (`withEditedEqCurve` and friends), so picking, editing, saving and deleting behave as on a device.
 */
@Composable
internal fun InputEqualizerDriverPage(
    initial: CameraSettings,
    reference: EqProfile? = null,
    profiles: List<EqProfile> = emptyList(),
    liveSpectrum: Boolean = true,
    recording: Boolean = false,
    initialSelected: Int? = null,
    peaks: Boolean = true,
    inputClipped: Boolean = false,
    recordedClipped: Boolean = false,
    outputs: List<AudioListeningDevice> = listOf(DriverPhoneSpeaker),
    listeningStatus: AudioListeningStatus = AudioListeningStatus(),
    snippet: EqSnippetUiState = EqSnippetUiState(),
) = DriverTheme {
    var settings by remember { mutableStateOf(initial) }
    var userProfiles by remember { mutableStateOf(profiles) }
    // Reset peaks clears the held trace and the clip latches, as the service's resetAudioClip does.
    var held by remember { mutableStateOf(peaks) }
    var clips by remember { mutableStateOf(inputClipped to recordedClipped) }
    // Start plays at once and Stop disables: the fake service has no route to wait for.
    var status by remember { mutableStateOf(listeningStatus) }
    // The fake audition moves between phases at once; no time passes headless, so a stop keeps 7 s when it caught less.
    var audition by remember { mutableStateOf(snippet) }
    val spectrum = if (liveSpectrum) driverSpectrum(settings.audioEqCurve) else null
    val active = activeEqProfile(settings.audioEqProfileId, settings.audioEqCurve, reference, userProfiles)
    val state = InputEqualizerUiState(
        curve = settings.audioEqCurve,
        active = active,
        userProfiles = userProfiles,
        reference = reference,
        referenceWaiting = active is ActiveEqProfile.Reference && !settings.audioSource.lowLatency,
        spectrum = spectrum,
        recording = recording,
        peakDb = spectrum?.takeIf { held }?.let { driverPeaks(it, clipped = clips.second) },
        inputClipped = clips.first,
        recordedClipped = clips.second,
        listen = eqListenUiState(settings, status, outputs, snippetPlaying = audition.phase == EqSnippetPhase.PLAYING),
        snippet = audition,
    )
    InputEqualizerContent(
        state = state,
        onCurveChange = { curve ->
            val edit = settings.withEditedEqCurve(curve, userProfiles)
            settings = edit.settings
            edit.save?.let { saved -> userProfiles = userProfiles.map { if (it.id == saved.id) saved else it } }
        },
        onPickProfile = { settings = settings.withEqProfile(it) },
        onSaveAs = { name ->
            // The first unused id: a count alone repeats one after a delete.
            val id = generateSequence(1) { it + 1 }.map { "u-$it" }.first { id -> userProfiles.none { it.id == id } }
            val created = EqProfile(id, name, settings.audioEqCurve.copy(enabled = true))
            userProfiles = (userProfiles + created).sortedBy { it.name.lowercase() }
            settings = settings.withSavedEqProfile(created, settings.audioEqProfileId)
        },
        onRename = { name -> userProfiles = userProfiles.map { if (it.id == settings.audioEqProfileId) it.copy(name = name) else it } },
        onDelete = {
            settings.audioEqProfileId?.let { id ->
                userProfiles = userProfiles.filterNot { it.id == id }
                settings = settings.withDeletedEqProfile(id)
            }
        },
        onReset = { settings = settings.withResetEq(reference) },
        onBack = {},
        initialSelected = initialSelected,
        onListenStart = { device ->
            settings = settings.withListenStart(device)
            status = AudioListeningStatus(AudioListeningPhase.ACTIVE, device.id, device.id, device.name)
        },
        onListenStop = { settings = settings.withListenStop(); status = AudioListeningStatus() },
        onListenVolume = { settings = settings.withListenVolume(it) },
        onResetPeaks = { held = false; clips = false to false },
        onSnippetRecord = { audition = EqSnippetUiState(EqSnippetPhase.RECORDING) },
        onSnippetStopRecording = {
            audition = EqSnippetUiState(snippetMs = audition.recordedMs.takeIf { it >= 500 } ?: DRIVER_SNIPPET_MS)
        },
        onSnippetPlay = { audition = audition.copy(phase = EqSnippetPhase.PLAYING) },
        onSnippetStopPlayback = { audition = audition.copy(phase = EqSnippetPhase.IDLE) },
        onSnippetDiscard = { audition = EqSnippetUiState() },
    )
}
