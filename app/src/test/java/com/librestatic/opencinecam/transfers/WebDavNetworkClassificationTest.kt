/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.transfers

import org.junit.Assert.assertEquals
import org.junit.Test

class WebDavNetworkClassificationTest {
    @Test fun unmeteredWifiIsWifiEvenWithoutPublicInternetValidation() {
        assertEquals(WebDavNetwork.WIFI, classifyWebDavNetwork(wifi = true, notMetered = true))
        assertEquals(WebDavNetwork.WIFI, classifyWebDavNetwork(wifi = true, internet = true, validated = true, notMetered = true))
    }

    @Test fun emulatorDefaultWifiStillAdmitsWithoutCellularConsent() {
        // API 30 emulator "AndroidWifi": TRANSPORT_WIFI + INTERNET + VALIDATED + NOT_METERED.
        val network = classifyWebDavNetwork(wifi = true, internet = true, validated = true, notMetered = true)
        assertEquals(WebDavNetwork.WIFI, network)
        assertEquals(null, WebDavUploadPolicy(enabled = true, allowCellular = false, network = network).stopReason())
    }

    @Test fun meteredWifiAndHotspotRequireTheCellularConsent() {
        assertEquals(WebDavNetwork.CELLULAR, classifyWebDavNetwork(wifi = true, internet = true, validated = true))
        val policy = WebDavUploadPolicy(enabled = true, network = classifyWebDavNetwork(wifi = true, internet = true, validated = true))
        assertEquals(WebDavStopReason.CELLULAR_CONSENT_REQUIRED, policy.stopReason())
        assertEquals(null, policy.copy(allowCellular = true).stopReason())
    }

    @Test fun vpnIsClassifiedByItsUnderlyingTransport() {
        assertEquals(WebDavNetwork.WIFI, classifyWebDavNetwork(wifi = true, vpn = true, internet = true, validated = true, notMetered = true))
        assertEquals(WebDavNetwork.CELLULAR, classifyWebDavNetwork(wifi = true, vpn = true, internet = true, validated = true))
        assertEquals(WebDavNetwork.CELLULAR, classifyWebDavNetwork(cellular = true, vpn = true, internet = true, validated = true))
        // Unmetered VPN over both transports still counts as metered: the path is not known to be Wi-Fi.
        assertEquals(WebDavNetwork.CELLULAR, classifyWebDavNetwork(wifi = true, cellular = true, vpn = true, internet = true, validated = true, notMetered = true))
    }

    @Test fun unknownUnderlyingTransportFailsClosed() {
        assertEquals(WebDavNetwork.OTHER, classifyWebDavNetwork(vpn = true, internet = true, validated = true, notMetered = true))
        assertEquals(WebDavNetwork.OTHER, classifyWebDavNetwork(internet = true, validated = true, notMetered = true))
        assertEquals(WebDavNetwork.OFFLINE, classifyWebDavNetwork(cellular = true, internet = true))
        assertEquals(WebDavNetwork.OFFLINE, classifyWebDavNetwork())
    }
}
