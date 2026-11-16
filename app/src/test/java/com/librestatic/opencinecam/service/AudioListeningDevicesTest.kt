/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.service

import android.media.AudioDeviceInfo
import com.librestatic.opencinecam.settings.AudioListeningDevice
import com.librestatic.opencinecam.settings.AudioListeningOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioListeningDevicesTest {
    private val buds = "AA:BB:CC:DD:EE:01"
    private fun a2dp(id: Int, name: String = "Galaxy Buds3 Pro", address: String = buds) =
        ListeningCandidate(id, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, name, address)
    private fun sco(id: Int, name: String = "Galaxy Buds3 Pro", address: String = buds) =
        ListeningCandidate(id, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, name, address)
    private fun ble(id: Int, name: String = "Galaxy Buds3 Pro", address: String = buds) =
        ListeningCandidate(id, AudioDeviceInfo.TYPE_BLE_HEADSET, name, address)
    private fun bluetooth(id: Int, name: String = "Galaxy Buds3 Pro") = AudioListeningDevice(id, name, AudioListeningOutput.BLUETOOTH)

    @Test fun theCallProfileIsNeverOffered() {
        assertNull(listeningOutputClass(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        assertEquals(emptyList<AudioListeningDevice>(), listeningDevices(listOf(sco(5))))
    }

    @Test fun aHeadsetsMediaAndCallEntriesAreOne() {
        // The call entry may come first by id; automatic used to land on it.
        assertEquals(listOf(bluetooth(8)), listeningDevices(listOf(sco(4), a2dp(8))))
    }

    @Test fun a2dpIsPreferredOverLeAudioUnlessLeAudioIsInUse() {
        assertEquals(listOf(bluetooth(8)), listeningDevices(listOf(ble(3), a2dp(8))))
        assertEquals(listOf(bluetooth(8)), listeningDevices(listOf(ble(3), a2dp(8), sco(2))))
        // A running or chosen LE Audio route keeps its id in the list.
        assertEquals(listOf(bluetooth(3)), listeningDevices(listOf(ble(3), a2dp(8)), inUseIds = setOf(3)))
        assertEquals(listOf(bluetooth(8)), listeningDevices(listOf(ble(3), a2dp(8)), inUseIds = setOf(8)))
        // An id in use elsewhere does not change this headset's pick.
        assertEquals(listOf(bluetooth(8)), listeningDevices(listOf(ble(3), a2dp(8)), inUseIds = setOf(40)))
        // Two LE Audio entries (one per earbud): the lowest id.
        assertEquals(listOf(bluetooth(6)), listeningDevices(listOf(ble(9), ble(6))))
    }

    @Test fun differentHeadsetsStayApartEvenWithTheSameName() {
        val second = "AA:BB:CC:DD:EE:02"
        assertEquals(listOf(bluetooth(8), bluetooth(12)),
            listeningDevices(listOf(a2dp(8), sco(9), a2dp(12, address = second), sco(13, address = second))))
        assertEquals(listOf(bluetooth(8), bluetooth(12, "Pixel Buds Pro")),
            listeningDevices(listOf(a2dp(8), a2dp(12, "Pixel Buds Pro", "11:22:33:44:55:66"))))
    }

    @Test fun withoutAnAddressTheNameGroupsTheEntries() {
        assertEquals(listOf(bluetooth(8)), listeningDevices(listOf(ble(3, address = ""), a2dp(8, address = " "))))
        assertEquals(listOf(bluetooth(3, "Left"), bluetooth(8, "Right")),
            listeningDevices(listOf(ble(3, "Left", ""), a2dp(8, "Right", ""))))
    }

    @Test fun wiredUsbAndTheSpeakerAreListedAsTheyAre() {
        val candidates = listOf(
            ListeningCandidate(2, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "Phone speaker"),
            ListeningCandidate(21, AudioDeviceInfo.TYPE_USB_HEADSET, "USB-C Headset"),
            ListeningCandidate(22, AudioDeviceInfo.TYPE_USB_DEVICE, "USB-C Headset"),
            ListeningCandidate(15, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, "Headphones"),
            ListeningCandidate(1, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, "Earpiece"),
            ListeningCandidate(30, AudioDeviceInfo.TYPE_HEARING_AID, "Hearing aid", buds),
            ListeningCandidate(31, AudioDeviceInfo.TYPE_HDMI, "TV"),
        )
        assertEquals(listOf(
            AudioListeningDevice(2, "Phone speaker", AudioListeningOutput.SPEAKER),
            AudioListeningDevice(15, "Headphones", AudioListeningOutput.WIRED_USB),
            AudioListeningDevice(21, "USB-C Headset", AudioListeningOutput.WIRED_USB),
            AudioListeningDevice(22, "USB-C Headset", AudioListeningOutput.WIRED_USB),
        ), listeningDevices(candidates))
    }

    @Test fun longNamesAreCut() {
        assertEquals(160, listeningDevices(listOf(a2dp(8, "x".repeat(400)))).single().name.length)
    }
}
