/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.transfers

import android.annotation.SuppressLint
import java.net.HttpURLConnection
import java.net.URI
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** Explicit LAN-only opt-in. Certificate/hostname bypass permits LAN interception of media and
 * credentials; it is not server authentication. Never changes process-wide TLS defaults. */
internal object WebDavLanTls {
    /** Literal-only parser: no DNS lookup, zone IDs, legacy IPv4 spelling or embedded IPv4. */
    fun isLocalAddress(uri: URI): Boolean {
        val host = uri.host ?: return false
        if (host.startsWith('[') && host.endsWith(']')) {
            val groups = ipv6(host.substring(1, host.lastIndex)) ?: return false
            return groups.take(7).all { it == 0 } && groups[7] == 1 ||
                (groups[0] and 0xfe00) == 0xfc00 || (groups[0] and 0xffc0) == 0xfe80
        }
        val parts = host.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { part ->
            if (part.length !in 1..3 || part.any { it !in '0'..'9' } ||
                part.length > 1 && part.startsWith('0')) return false
            val value = part.toIntOrNull() ?: return false
            if (value !in 0..255) return false
            value
        }
        return octets[0] == 10 || octets[0] == 127 ||
            octets[0] == 172 && octets[1] in 16..31 ||
            octets[0] == 192 && octets[1] == 168 ||
            octets[0] == 169 && octets[1] == 254
    }

    private fun ipv6(value: String): List<Int>? {
        if (value.length !in 2..39 || value.any { it !in "0123456789abcdefABCDEF:" }) return null
        val compressed = value.indexOf("::")
        fun groups(part: String): List<Int>? {
            if (part.isEmpty()) return emptyList()
            return part.split(':').map {
                if (it.length !in 1..4) return null
                it.toIntOrNull(16) ?: return null
            }
        }
        if (compressed < 0) return groups(value)?.takeIf { it.size == 8 }
        if (value.indexOf("::", compressed + 2) >= 0) return null
        val left = groups(value.substring(0, compressed)) ?: return null
        val right = groups(value.substring(compressed + 2)) ?: return null
        val missing = 8 - left.size - right.size
        if (missing < 1) return null
        return left + List(missing) { 0 } + right
    }

    @get:SuppressLint("TrustAllX509TrustManager") // Deliberate opt-in, only applied after local/origin gates below.
    private val socketFactory by lazy {
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        }
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }.socketFactory
    }
    @SuppressLint("BadHostnameVerifier") // Deliberate per-instance LAN opt-in, never a global default.
    private val hostnameVerifier = HostnameVerifier { _, _ -> true }

    fun apply(connection: HttpURLConnection, destination: WebDavDestination) {
        if (!destination.ignoreTlsErrors) return
        val collection = destination.collection
        require(isLocalAddress(collection)) { "TLS bypass requires a literal local IP address" }
        require(connection is HttpsURLConnection) { "TLS bypass requires HTTPS" }
        val request = connection.url.toURI()
        fun port(uri: URI): Int = if (uri.port == -1) 443 else uri.port
        require(request.scheme.equals("https", ignoreCase = true) && request.rawUserInfo == null &&
            request.host.equals(collection.host, ignoreCase = true) && port(request) == port(collection) &&
            isLocalAddress(request)) { "TLS bypass cannot cross destination origins" }
        connection.sslSocketFactory = socketFactory
        connection.hostnameVerifier = hostnameVerifier
    }
}
