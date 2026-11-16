/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.settings

import com.librestatic.opencinecam.AppSection
import com.librestatic.opencinecam.CaptureMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioListenModelTest {
    private val usb = AudioListeningDevice(7, "USB-C Headset", AudioListeningOutput.WIRED_USB)
    private val buds = AudioListeningDevice(9, "Buds", AudioListeningOutput.BLUETOOTH)
    private val speaker = AudioListeningDevice(2, "Phone speaker", AudioListeningOutput.SPEAKER)

    @Test fun liveListeningNeverOffersTheSpeaker() {
        assertEquals(listOf(usb, buds), listenDevices(listOf(speaker, usb, buds)))
        val state = listenUiState(CameraSettings(), AudioListeningStatus(), listOf(speaker))
        assertTrue(state.devices.isEmpty())
        assertEquals(ListenView.NO_OUTPUT, listenView(state))
        // A speaker route playing from Settings is not this card's: it offers the headphones.
        val speakerOn = CameraSettings(audioListening = AudioListeningSettings(enabled = true, output = AudioListeningOutput.SPEAKER))
        val playing = listenUiState(speakerOn, AudioListeningStatus(AudioListeningPhase.ACTIVE, effectiveDeviceId = 2), listOf(speaker, usb))
        assertFalse(playing.headphoneRoute)
        assertEquals(ListenView.IDLE, listenView(playing))
    }

    @Test fun listenViewFollowsThePhaseOfAHeadphoneRoute() {
        val on = CameraSettings().withListenStart(usb)
        fun view(phase: AudioListeningPhase, outputs: List<AudioListeningDevice> = listOf(usb), settings: CameraSettings = on) =
            listenView(listenUiState(settings, AudioListeningStatus(phase, requestedDeviceId = 7), outputs))
        assertEquals(ListenView.IDLE, view(AudioListeningPhase.DISABLED, settings = CameraSettings()))
        assertEquals(ListenView.IDLE, view(AudioListeningPhase.NEEDS_CONNECT))
        for (phase in listOf(AudioListeningPhase.CONNECTING, AudioListeningPhase.WAITING_PCM, AudioListeningPhase.ACTIVE)) {
            assertEquals(ListenView.LIVE, view(phase))
            // Still live while the device list catches up with an unplug.
            assertEquals(ListenView.LIVE, view(phase, outputs = emptyList()))
        }
        for (phase in listOf(AudioListeningPhase.DISCONNECTED, AudioListeningPhase.FAILED, AudioListeningPhase.NO_OUTPUT)) {
            assertEquals(ListenView.RETRY, view(phase))
            assertEquals(ListenView.NO_OUTPUT, view(phase, outputs = emptyList()))
        }
        // A stopped route's stale phase is not shown as live.
        assertEquals(ListenView.IDLE, view(AudioListeningPhase.ACTIVE, settings = on.withListenStop()))
        assertFalse(listenStartEnabled(listenUiState(on, AudioListeningStatus(AudioListeningPhase.RETIRING), listOf(usb))))
        assertTrue(listenStartEnabled(listenUiState(on, AudioListeningStatus(AudioListeningPhase.FAILED), listOf(usb))))
    }

    @Test fun listenStatusNamesTheDevice() {
        val on = CameraSettings().withListenStart(usb).withListenVolume(65)
        val active = listenUiState(on, AudioListeningStatus(AudioListeningPhase.ACTIVE, 7, 7, "USB-C Headset (reported)"), listOf(usb))
        assertEquals("USB-C Headset (reported)", active.deviceName)
        assertEquals(65, active.volumePercent)
        assertTrue(active.headphoneRoute)
        // Before the service reports a name, the requested device's name from the list.
        assertEquals("USB-C Headset", listenUiState(on, AudioListeningStatus(AudioListeningPhase.CONNECTING, 7), listOf(usb)).deviceName)
        // The second of two identical headsets is named with its number, as on its start button.
        val twin = AudioListeningDevice(8, "USB-C Headset", AudioListeningOutput.WIRED_USB)
        val second = listenUiState(CameraSettings().withListenStart(twin), AudioListeningStatus(AudioListeningPhase.ACTIVE, 8, 8, "USB-C Headset"), listOf(usb, twin))
        assertEquals("USB-C Headset (2)", second.deviceName)
        assertEquals(100, CameraSettings().withListenVolume(140).audioListening.volumePercent)
        assertEquals(0, CameraSettings().withListenVolume(-3).audioListening.volumePercent)
    }

    @Test fun startingOnADeviceSetsItsRouteAndWaitsForTheServiceOnlyWhenTheRouteChanges() {
        val off = CameraSettings(audioListening = AudioListeningSettings(volumePercent = 30))
        val started = off.withListenStart(buds)
        assertEquals(AudioListeningSettings(enabled = true, volumePercent = 30, output = AudioListeningOutput.BLUETOOTH), started.audioListening)
        assertEquals(9, started.audioListeningOutputDeviceId)
        assertTrue(off.listenRouteChanges(buds))
        assertFalse(started.listenRouteChanges(buds))
        assertTrue(started.listenRouteChanges(usb))
        assertTrue(started.withListenStop().listenRouteChanges(buds))
        // Volume is not part of the route.
        assertFalse(started.withListenVolume(80).listenRouteChanges(buds))
        assertEquals(started.audioListening.copy(enabled = false), started.withListenStop().audioListening)
    }

    @Test fun aPendingStartArmsOnlyOnceTheServiceWaitsOnThatDevice() {
        assertTrue(listenReadyToArm(AudioListeningStatus(AudioListeningPhase.NEEDS_CONNECT, requestedDeviceId = 9), 9))
        assertFalse(listenReadyToArm(AudioListeningStatus(AudioListeningPhase.NEEDS_CONNECT, requestedDeviceId = 7), 9))
        assertFalse(listenReadyToArm(AudioListeningStatus(AudioListeningPhase.NEEDS_CONNECT, requestedDeviceId = null), 9))
        assertFalse(listenReadyToArm(AudioListeningStatus(AudioListeningPhase.DISABLED, requestedDeviceId = 9), 9))
        assertFalse(listenReadyToArm(AudioListeningStatus(AudioListeningPhase.ACTIVE, requestedDeviceId = 9), 9))
    }

    @Test fun listenStatusIsTheControlsOwn() {
        val on = CameraSettings().withListenStart(usb)
        fun status(phase: AudioListeningPhase, outputs: List<AudioListeningDevice> = listOf(usb), paused: Boolean = false) =
            listenStatus(listenUiState(on, AudioListeningStatus(phase, requestedDeviceId = 7), outputs, paused = paused))
        assertEquals(ListenStatus.CONNECTING, status(AudioListeningPhase.CONNECTING))
        assertEquals(ListenStatus.CONNECTING, status(AudioListeningPhase.WAITING_PCM))
        assertEquals(ListenStatus.ACTIVE, status(AudioListeningPhase.ACTIVE))
        assertEquals(ListenStatus.DISCONNECTED, status(AudioListeningPhase.DISCONNECTED))
        assertEquals(ListenStatus.FAILED, status(AudioListeningPhase.FAILED))
        assertEquals(ListenStatus.NOT_FOUND, status(AudioListeningPhase.NO_OUTPUT))
        // A paused live route (the equaliser's snippet holds the microphone) says so.
        assertEquals(ListenStatus.PAUSED, status(AudioListeningPhase.ACTIVE, paused = true))
        assertNull(status(AudioListeningPhase.NEEDS_CONNECT))
        assertNull(status(AudioListeningPhase.FAILED, outputs = emptyList()))
        assertNull(listenStatus(listenUiState(CameraSettings(), AudioListeningStatus(), listOf(usb))))
    }

    @Test fun identicalNamesGetANumberAfterTheFirst() {
        val left = AudioListeningDevice(30, "Galaxy Buds3 Pro", AudioListeningOutput.BLUETOOTH)
        val right = AudioListeningDevice(12, "Galaxy Buds3 Pro", AudioListeningOutput.BLUETOOTH)
        val third = AudioListeningDevice(41, "Galaxy Buds3 Pro", AudioListeningOutput.BLUETOOTH)
        assertEquals(mapOf(12 to "Galaxy Buds3 Pro", 30 to "Galaxy Buds3 Pro (2)", 41 to "Galaxy Buds3 Pro (3)", 7 to "USB-C Headset", 9 to "Buds"),
            listenDeviceLabels(listOf(left, usb, right, buds, third)))
        assertEquals(mapOf(7 to "USB-C Headset", 9 to "Buds"), listenDeviceLabels(listOf(usb, buds)))
        assertTrue(listenDeviceLabels(emptyList()).isEmpty())
    }

    @Test fun armedIsStartingOrPlayingAndAudibleOnlyPlaying() {
        for (phase in AudioListeningPhase.entries) {
            val status = AudioListeningStatus(phase)
            assertEquals(phase.name, phase in setOf(AudioListeningPhase.WAITING_PCM, AudioListeningPhase.CONNECTING, AudioListeningPhase.ACTIVE),
                listenArmed(status))
            assertEquals(phase.name, phase == AudioListeningPhase.ACTIVE, listenAudible(status))
        }
    }

    @Test fun theKeyShowsInModesWithSoundWhenThereAreHeadphonesOrListeningIsArmed() {
        val audio = CameraSettings(audioEnabled = true)
        val idle = AudioListeningStatus()
        val armed = AudioListeningStatus(AudioListeningPhase.WAITING_PCM)
        for (mode in listOf(CaptureMode.VIDEO, CaptureMode.LOG, CaptureMode.HLG, CaptureMode.RAW_VIDEO)) {
            assertTrue(mode.name, listenKeyVisible(mode, audio, idle, listOf(speaker, usb)))
            // Only the speaker: nothing to start on.
            assertFalse(mode.name, listenKeyVisible(mode, audio, idle, listOf(speaker)))
            // Armed: shown so it can be stopped, even once the headphones dropped off the list.
            assertTrue(mode.name, listenKeyVisible(mode, audio, armed, emptyList()))
            assertFalse(mode.name, listenKeyVisible(mode, audio.copy(audioEnabled = false), armed, listOf(usb)))
        }
        for (mode in listOf(CaptureMode.PHOTO, CaptureMode.RAW_PHOTO, CaptureMode.BURST, CaptureMode.SLOW_MOTION, CaptureMode.TIME_LAPSE)) {
            assertFalse(mode.name, listenKeyVisible(mode, audio, armed, listOf(usb)))
        }
        // Off-speed video is silent.
        assertFalse(listenKeyVisible(CaptureMode.VIDEO, audio.copy(videoOffSpeed = true), idle, listOf(usb)))
    }

    @Test fun aTapStopsStartsOnTheRememberedOrOnlyDeviceOrOpensThePanel() {
        val other = AudioListeningDevice(11, "Wired", AudioListeningOutput.WIRED_USB)
        val idle = AudioListeningStatus()
        assertEquals(ListenTapAction.Stop, listenKeyTapAction(CameraSettings(), AudioListeningStatus(AudioListeningPhase.ACTIVE), listOf(usb)))
        assertEquals(ListenTapAction.Stop, listenKeyTapAction(CameraSettings(), AudioListeningStatus(AudioListeningPhase.CONNECTING), emptyList()))
        // The remembered device, while it is still connected.
        val remembered = CameraSettings(audioListeningOutputDeviceId = 9)
        assertEquals(ListenTapAction.Start(buds), listenKeyTapAction(remembered, idle, listOf(speaker, usb, buds, other)))
        // Gone: the only headphones left, or the panel when there are several.
        assertEquals(ListenTapAction.Start(usb), listenKeyTapAction(remembered, idle, listOf(speaker, usb)))
        assertEquals(ListenTapAction.OpenPanel, listenKeyTapAction(remembered, idle, listOf(usb, other)))
        // Nothing remembered.
        assertEquals(ListenTapAction.Start(usb), listenKeyTapAction(CameraSettings(), idle, listOf(speaker, usb)))
        assertEquals(ListenTapAction.OpenPanel, listenKeyTapAction(CameraSettings(), idle, listOf(usb, buds)))
        // A remembered speaker is never started live; nor is the speaker the only device.
        assertEquals(ListenTapAction.OpenPanel, listenKeyTapAction(CameraSettings(audioListeningOutputDeviceId = 2), idle, listOf(speaker)))
        // A lost route is not armed: a tap starts again.
        assertEquals(ListenTapAction.Start(usb),
            listenKeyTapAction(CameraSettings(audioListeningOutputDeviceId = 7), AudioListeningStatus(AudioListeningPhase.DISCONNECTED), listOf(usb, buds)))
    }

    @Test fun theKeyIsAmberWhileArmedButSilent() {
        assertEquals(ListenKeyLook.OFF, listenKeyLook(AudioListeningStatus()))
        for (phase in listOf(AudioListeningPhase.NEEDS_CONNECT, AudioListeningPhase.DISCONNECTED, AudioListeningPhase.FAILED,
            AudioListeningPhase.NO_OUTPUT, AudioListeningPhase.RETIRING)) {
            assertEquals(phase.name, ListenKeyLook.OFF, listenKeyLook(AudioListeningStatus(phase)))
        }
        // Connecting, or RAW video before a take, where no PCM reaches the route yet.
        assertEquals(ListenKeyLook.PENDING, listenKeyLook(AudioListeningStatus(AudioListeningPhase.CONNECTING)))
        assertEquals(ListenKeyLook.PENDING, listenKeyLook(AudioListeningStatus(AudioListeningPhase.WAITING_PCM)))
        assertEquals(ListenKeyLook.ON, listenKeyLook(AudioListeningStatus(AudioListeningPhase.ACTIVE)))
    }

    @Test fun leavingSettingsForTheCaptureScreenAsksOnlyWhileListeningIsArmed() {
        val armed = listOf(AudioListeningPhase.CONNECTING, AudioListeningPhase.WAITING_PCM, AudioListeningPhase.ACTIVE)
        for (phase in armed) {
            assertTrue(phase.name, listenExitPrompt(AppSection.SETTINGS, AppSection.CAPTURE, AudioListeningStatus(phase)))
        }
        for (phase in AudioListeningPhase.entries - armed.toSet()) {
            assertFalse(phase.name, listenExitPrompt(AppSection.SETTINGS, AppSection.CAPTURE, AudioListeningStatus(phase)))
        }
        val active = AudioListeningStatus(AudioListeningPhase.ACTIVE)
        // Other moves never ask: Settings to the gallery, the gallery to the capture screen, a section to itself.
        assertFalse(listenExitPrompt(AppSection.SETTINGS, AppSection.MEDIA, active))
        assertFalse(listenExitPrompt(AppSection.MEDIA, AppSection.CAPTURE, active))
        assertFalse(listenExitPrompt(AppSection.SETTINGS, AppSection.SETTINGS, active))
        assertFalse(listenExitPrompt(AppSection.CAPTURE, AppSection.CAPTURE, active))
    }
}
