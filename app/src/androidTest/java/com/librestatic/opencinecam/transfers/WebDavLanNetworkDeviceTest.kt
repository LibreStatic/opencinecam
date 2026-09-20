/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.transfers

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Capability vectors exercise classification; the last case checks actual Android getter wiring.
 * These cases do not claim physical offline Wi-Fi acceptance. */
class WebDavLanNetworkDeviceTest {
    @Test fun wifiLanWithoutInternetValidationCanTransfer() {
        val lan = classifyWebDavNetwork(wifi = true, internet = false, validated = false)
        assertEquals(WebDavNetwork.WIFI, lan)
        assertNull(WebDavUploadPolicy(enabled = true, network = lan).stopReason())
        assertEquals(WebDavNetwork.WIFI, classifyWebDavNetwork(wifi = true, internet = true, validated = false))
    }

    @Test fun lanAdmissionNeverBypassesVpnCellularOrRecordingGates() {
        val wifi = classifyWebDavNetwork(wifi = true)
        assertEquals(WebDavNetwork.OTHER, classifyWebDavNetwork(wifi = true, vpn = true))
        val cellular = classifyWebDavNetwork(cellular = true, internet = true, validated = true)
        assertEquals(WebDavNetwork.CELLULAR, cellular)
        assertEquals(WebDavStopReason.CELLULAR_CONSENT_REQUIRED, WebDavUploadPolicy(enabled = true, network = cellular).stopReason())
        assertEquals(WebDavStopReason.RECORDING, WebDavUploadPolicy(enabled = true, recording = true, network = wifi).stopReason())
        assertEquals(WebDavStopReason.DISABLED, WebDavUploadPolicy(network = wifi).stopReason())
        assertEquals(WebDavNetwork.OFFLINE, classifyWebDavNetwork())
        assertEquals(WebDavNetwork.OFFLINE, classifyWebDavNetwork(wifi = true, cellular = true))
    }

    @Test fun actualSelectedNetworkUsesTheSameCapabilityMapping() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val actual = requireNotNull(manager.getNetworkCapabilities(requireNotNull(manager.activeNetwork)))
        val expected = classifyWebDavNetwork(
            wifi = actual.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            cellular = actual.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
            vpn = actual.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
            internet = actual.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            validated = actual.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        )
        assertEquals(expected, classifyWebDavNetwork(actual))
        assertEquals(WebDavNetwork.WIFI, classifyWebDavNetwork(actual)) // Private test emulator's real route.
    }
}
