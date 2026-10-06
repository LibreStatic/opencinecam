/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.media.audio

import android.media.AudioDeviceInfo
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioInputRoutingTest {
    private fun input(id: Int, name: String, type: Int, address: String = "") =
        SelectableAudioInput(id, name, type, listOf(48_000), listOf(1), emptyList(), address)

    private val builtIn = input(1, "", AudioDeviceInfo.TYPE_BUILTIN_MIC, "bottom")
    private val lav = input(42, "USB-C Lavalier", AudioDeviceInfo.TYPE_USB_DEVICE, "card=2;device=0")
    private val bt = input(50, "Buds", AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "AA:BB")

    @Test fun exactKeyWinsOverAnotherPortOfTheSameProduct() {
        val other = input(43, "USB-C Lavalier", AudioDeviceInfo.TYPE_USB_DEVICE, "card=3;device=0")
        assertEquals(other, resolveAudioInput(other.key, listOf(builtIn, lav, other)))
    }

    @Test fun reconnectionOnAnotherAddressFallsBackToTypeAndProduct() {
        val remembered = AudioInputKey(AudioDeviceInfo.TYPE_USB_DEVICE, "USB-C Lavalier", "card=1;device=0")
        assertEquals(lav, resolveAudioInput(remembered, listOf(builtIn, lav)))
    }

    @Test fun twoIdenticalProductsWithoutAnAddressMatchAreAmbiguous() {
        val twin = input(43, "USB-C Lavalier", AudioDeviceInfo.TYPE_USB_DEVICE, "card=3;device=0")
        val remembered = AudioInputKey(AudioDeviceInfo.TYPE_USB_DEVICE, "USB-C Lavalier", "card=9;device=0")
        assertNull(resolveAudioInput(remembered, listOf(lav, twin)))
    }

    @Test fun missingProductResolvesToNothing() {
        assertNull(resolveAudioInput(AudioInputKey(AudioDeviceInfo.TYPE_USB_DEVICE, "Gone", ""), listOf(builtIn)))
    }

    @Test fun autoPrefersUsbOrWiredThenBuiltInAndBluetoothOnlyAsTheLastResort() {
        assertEquals(lav, preferredAutoInput(listOf(builtIn, bt, lav)))
        assertEquals(builtIn, preferredAutoInput(listOf(bt, builtIn)))
        assertEquals(bt, preferredAutoInput(listOf(bt)))
        assertEquals(builtIn, preferredAutoInput(listOf(builtIn)))
        assertNull(preferredAutoInput(emptyList()))
    }

    @Test fun externalCoversUsbWiredAndBluetoothFamilies() {
        for (type in listOf(AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET)) {
            assertTrue(type.toString(), isExternalInputType(type))
        }
        assertEquals(false, isExternalInputType(AudioDeviceInfo.TYPE_BUILTIN_MIC))
        assertEquals(AudioInputKind.USB, audioInputKind(AudioDeviceInfo.TYPE_USB_HEADSET))
        assertEquals("INT", audioInputKind(AudioDeviceInfo.TYPE_BUILTIN_MIC).badge)
    }

    @Test fun lossPolicyDecisionPerPath() {
        val p = AudioInputLossPolicy.entries
        for (policy in p) assertEquals(AudioInputLossAction.IGNORE, decideInputLoss(policy, AudioCapturePath.PREVIEW))
        assertEquals(AudioInputLossAction.STOP_TAKE, decideInputLoss(AudioInputLossPolicy.STOP_TAKE, AudioCapturePath.AUDIO_RECORD))
        assertEquals(AudioInputLossAction.STOP_TAKE, decideInputLoss(AudioInputLossPolicy.STOP_TAKE, AudioCapturePath.MEDIA_RECORDER))
        assertEquals(AudioInputLossAction.FALLBACK, decideInputLoss(AudioInputLossPolicy.FALLBACK_BUILTIN, AudioCapturePath.AUDIO_RECORD))
        assertEquals(AudioInputLossAction.FALLBACK, decideInputLoss(AudioInputLossPolicy.FALLBACK_BUILTIN, AudioCapturePath.MEDIA_RECORDER))
        assertEquals(AudioInputLossAction.SILENCE, decideInputLoss(AudioInputLossPolicy.CONTINUE_SILENT, AudioCapturePath.AUDIO_RECORD))
        // MediaRecorder exposes no PCM, so it cannot write silence.
        assertEquals(AudioInputLossAction.STOP_TAKE, decideInputLoss(AudioInputLossPolicy.CONTINUE_SILENT, AudioCapturePath.MEDIA_RECORDER))
    }

    @Test fun routeClassification() {
        assertEquals(RouteVerdict.CONFIRMED, classifyRoute(42, 42, true))
        assertEquals(RouteVerdict.UNCONFIRMED, classifyRoute(42, null, true))
        assertEquals(RouteVerdict.LOST, classifyRoute(42, 1, true))
        assertEquals(RouteVerdict.LOST, classifyRoute(42, 42, false))
        assertEquals(RouteVerdict.CONFIRMED, classifyRoute(null, 1, true))
        assertEquals(RouteVerdict.UNCONFIRMED, classifyRoute(null, null, true))
    }

    @Test fun silenceZeroesInPlaceAndKeepsTheSampleCount() {
        val buffer = ByteBuffer.allocate(64)
        for (i in 0 until 64) buffer.put(i, (i + 1).toByte())
        buffer.position(0); buffer.limit(48)
        silencePcm(buffer, 48)
        assertEquals(0, buffer.position()); assertEquals(48, buffer.limit())
        buffer.limit(64)
        for (i in 0 until 48) assertEquals(0.toByte(), buffer.get(i))
        assertEquals(49.toByte(), buffer.get(48))
    }

    @Test fun portsThatAreNotMicrophonesAreNotCaptureInputs() {
        // The Razr Fold lists these next to its microphones.
        for (type in listOf(18, 25, 16, 17, 28)) assertEquals(false, isCaptureInputType(type))
        for (type in listOf(15, 11, 22, 7, 3, 26)) assertEquals(true, isCaptureInputType(type))
    }
}
