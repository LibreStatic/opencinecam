/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.share

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareNetworksTest {
    private fun ip(text: String): InetAddress = InetAddress.getByName(text)
    private fun nic(name: String, vararg addresses: String, up: Boolean = true, loopback: Boolean = false) =
        ShareInterface(name, up, loopback, addresses.map(::ip))

    private fun selected(vararg interfaces: ShareInterface, names: Set<String> = emptySet(), addresses: Set<InetAddress> = emptySet()) =
        selectShareBindings(interfaces.toList(), names, addresses).map { it.address.hostAddress }

    @Test
    fun privateIpv4OnWifiHotspotAndUsbPasses() {
        assertEquals(
            listOf("192.168.1.23", "10.0.0.5", "172.16.4.1", "172.31.255.254", "192.168.43.1", "169.254.10.20"),
            selected(
                nic("wlan0", "192.168.1.23", "fe80::1", "2001:db8::5"),
                nic("eth0", "10.0.0.5"),
                nic("ap0", "172.16.4.1"),
                nic("swlan0", "172.31.255.254"),
                nic("wlan1", "192.168.43.1"),
                nic("rndis0", "169.254.10.20"),
            ),
        )
        assertEquals(
            listOf("10.42.0.1", "192.168.42.129", "192.168.44.1", "192.168.50.2", "192.168.88.2"),
            selected(nic("ncm0", "10.42.0.1"), nic("usb0", "192.168.42.129"), nic("bt-pan", "192.168.44.1"),
                nic("softap0", "192.168.50.2"), nic("eth0", "192.168.88.2")),
        )
    }

    @Test
    fun bindingsCarryTheirInterfaceName() {
        assertEquals(listOf(ShareBinding("wlan0", ip("192.168.1.23") as java.net.Inet4Address)),
            selectShareBindings(listOf(nic("wlan0", "192.168.1.23", "fe80::1"))))
    }

    @Test
    fun unknownInterfaceNamesAndWifiDirectAreRefused() {
        assertEquals(
            emptyList<String>(),
            selected(
                nic("p2p-wlan0-0", "192.168.49.1"),
                nic("p2p0", "192.168.49.2"),
                nic("weird0", "10.9.9.9"),
                nic("lan0", "192.168.1.5"),
                nic("br0", "192.168.1.6"),
                nic("usb_rmnet0", "10.0.0.9"),
            ),
        )
        assertTrue(looksCellularOrTunnel("p2p-wlan0-0"))
        assertFalse(isShareableInterfaceName("p2p0"))
        assertTrue(isShareableInterfaceName("SWLAN0"))
    }

    @Test
    fun publicLoopbackWildcardAndIpv6AreRefused() {
        assertEquals(
            emptyList<String>(),
            selected(
                nic("wlan0", "8.8.8.8", "172.32.0.1", "172.15.255.255", "100.64.0.1", "fd00::1", "fe80::2"),
                nic("lo", "127.0.0.1", loopback = true),
                nic("eth1", "127.0.0.2"),
                nic("eth2", "0.0.0.0"),
                nic("wlan2", "192.168.0.9", up = false),
            ),
        )
    }

    @Test
    fun cellularAndVpnInterfacesAreRefusedEvenWithPrivateAddresses() {
        assertEquals(
            listOf("192.168.1.23"),
            selected(
                nic("wlan0", "192.168.1.23"),
                nic("rmnet_data0", "10.120.4.7"),
                nic("ccmni1", "10.33.0.2"),
                nic("v4-rmnet_data0", "192.0.0.4"),
                nic("tun0", "10.8.0.2"),
                nic("wg0", "10.66.0.2"),
                nic("ipsec1", "10.1.1.1"),
                nic("ppp0", "10.64.64.64"),
                nic("eth5", "10.9.9.9"),
                nic("wlan9", "10.4.4.4"),
                names = setOf("eth5"),
                addresses = setOf(ip("10.4.4.4")),
            ),
        )
    }

    @Test
    fun anInterfaceSharingAnAddressWithACellularNetworkIsRefusedWhole() {
        assertEquals(emptyList<String>(), selected(nic("eth0", "192.168.5.5", "10.4.4.4"), addresses = setOf(ip("10.4.4.4"))))
    }

    @Test
    fun duplicatesCollapse() {
        assertEquals(listOf("192.168.1.23"), selected(nic("wlan0", "192.168.1.23"), nic("wlan0:1", "192.168.1.23")))
    }

    @Test
    fun namePrefixesAreCaseInsensitive() {
        assertTrue(looksCellularOrTunnel("RMNET_IPA0"))
        assertTrue(looksCellularOrTunnel("clat4"))
        assertFalse(looksCellularOrTunnel("wlan0"))
        assertFalse(looksCellularOrTunnel("rndis0"))
        assertFalse(looksCellularOrTunnel("ap0"))
    }
}
