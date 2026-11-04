/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.share

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Locale

/** One address the share listens on, with the interface that carries it. */
internal data class ShareBinding(val interfaceName: String, val address: Inet4Address)

/** One local interface as the filter sees it. */
internal data class ShareInterface(val name: String, val up: Boolean, val loopback: Boolean, val addresses: List<InetAddress>)

/**
 * The addresses the network drive may listen on (OCC-PLAN-071 §8): IPv4 only, private
 * (10/8, 172.16/12, 192.168/16) or link-local (169.254/16), on an interface that is up, not
 * loopback, named like Wi-Fi, the hotspot, USB tethering, Ethernet or Bluetooth PAN
 * ([isShareableInterfaceName]), not named like a modem, tunnel or Wi-Fi Direct interface
 * ([looksCellularOrTunnel]), not named in [excludedNames] (the interfaces of cellular and VPN
 * networks) and not carrying an address in [excludedAddresses]. Both lists must agree: an
 * unknown interface name is refused. Never the wildcard, never loopback.
 */
internal fun selectShareBindings(
    interfaces: List<ShareInterface>,
    excludedNames: Set<String> = emptySet(),
    excludedAddresses: Set<InetAddress> = emptySet(),
): List<ShareBinding> = interfaces
    .filter { it.up && !it.loopback && it.name !in excludedNames && isShareableInterfaceName(it.name) && !looksCellularOrTunnel(it.name) }
    .filter { candidate -> candidate.addresses.none { it in excludedAddresses } }
    .flatMap { nic -> nic.addresses.filterIsInstance<Inet4Address>().map { ShareBinding(nic.name, it) } }
    .filter { val a = it.address; !a.isAnyLocalAddress && !a.isLoopbackAddress && (a.isSiteLocalAddress || a.isLinkLocalAddress) }
    .distinctBy { it.address }

/** Wi-Fi (`wlan`, Samsung's `swlan`), hotspot (`ap`, `softap`), USB tethering (`rndis`, `ncm`, `usb`), Ethernet and Bluetooth PAN. */
internal fun isShareableInterfaceName(name: String): Boolean {
    val lower = name.lowercase(Locale.ROOT)
    return SHAREABLE_PREFIXES.any { lower.startsWith(it) }
}

/** Modem, CLAT, tunnel and Wi-Fi Direct interface names across vendors: refused whatever ConnectivityManager says. */
internal fun looksCellularOrTunnel(name: String): Boolean {
    val lower = name.lowercase(Locale.ROOT)
    return CELLULAR_OR_TUNNEL_PREFIXES.any { lower.startsWith(it) }
}

private val SHAREABLE_PREFIXES = listOf("wlan", "swlan", "ap", "softap", "rndis", "ncm", "usb", "eth", "bt-pan")

private val CELLULAR_OR_TUNNEL_PREFIXES = listOf(
    "rmnet", "ccmni", "v4-", "clat", "pdp", "wwan", "seth", "rev_rmnet", "qmi", "usb_rmnet", "ppp", "tun", "tap", "ipsec", "wg", "dummy", "sit", "ip6tnl", "gre",
    "p2p",
)

/**
 * Enumerates [NetworkInterface]s and excludes those of [ConnectivityManager] networks with
 * cellular or VPN transport. Tethering and hotspot interfaces are not networks there, so they
 * are found through [NetworkInterface] alone and pass the filter by address.
 */
internal class AndroidShareNetworks(context: Context) {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    fun bindings(): List<ShareBinding> {
        val excludedNames = HashSet<String>()
        val excludedAddresses = HashSet<InetAddress>()
        @Suppress("DEPRECATION")
        val networks = runCatching { connectivity?.allNetworks.orEmpty().toList() }.getOrDefault(emptyList())
        for (network in networks) {
            val capabilities = runCatching { connectivity?.getNetworkCapabilities(network) }.getOrNull() ?: continue
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) && !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            val link = runCatching { connectivity?.getLinkProperties(network) }.getOrNull() ?: continue
            link.interfaceName?.let(excludedNames::add)
            link.linkAddresses.forEach { excludedAddresses += it.address }
        }
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() }.getOrDefault(emptyList()).mapNotNull { nic ->
            runCatching { ShareInterface(nic.name, nic.isUp, nic.isLoopback, nic.inetAddresses.toList()) }.getOrNull()
        }
        return selectShareBindings(interfaces, excludedNames, excludedAddresses)
    }
}
