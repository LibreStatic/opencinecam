/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.transfers

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.Principal
import java.security.cert.Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory
import org.junit.Assert.*
import org.junit.Test

class WebDavLanTlsTest {
    private fun local(value: String) = WebDavLanTls.isLocalAddress(URI("https://$value/takes/"))
    private fun destination(ignore: Boolean = true) = WebDavDestination(URI("https://192.168.1.12/takes/"), ignoreTlsErrors = ignore)

    @Test fun canonicalPrivateLoopbackAndLinkLocalIpv4AreAccepted() {
        listOf("10.0.0.0", "10.255.255.255", "172.16.0.0", "172.31.255.255", "192.168.0.0",
            "192.168.255.255", "127.0.0.1", "127.255.255.255", "169.254.0.0", "169.254.255.255")
            .forEach { assertTrue(it, local(it)) }
    }

    @Test fun publicReservedAndAmbiguousIpv4AreRejectedWithoutResolution() {
        listOf("8.8.8.8", "172.15.255.255", "172.32.0.0", "192.167.255.255", "192.169.0.0",
            "169.253.255.255", "169.255.0.0", "100.64.0.1", "0.0.0.0", "255.255.255.255",
            "224.0.0.1", "192.0.2.1", "127.1", "2130706433", "0177.0.0.1", "0x7f.0.0.1",
            "192.168.001.1", "192.168.1.01", "10.0.0.256", "10.0.0.1.")
            .forEach { assertFalse(it, local(it)) }
    }

    @Test fun ulaLinkLocalAndLoopbackIpv6AreAcceptedInCanonicalLiteralForms() {
        listOf("[::1]", "[0:0:0:0:0:0:0:1]", "[fc00::]", "[fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff]",
            "[FD12:3456::ABCD]", "[fe80::1]", "[febf:ffff:ffff:ffff:ffff:ffff:ffff:ffff]")
            .forEach { assertTrue(it, local(it)) }
    }

    @Test fun ipv6PublicUnspecifiedMulticastMappedAndScopedAreRejected() {
        listOf("[::]", "[::2]", "[2001:db8::1]", "[2001:4860:4860::8888]", "[fbff::1]", "[fe00::1]",
            "[fe7f::1]", "[fec0::1]", "[ff02::1]", "[::ffff:8.8.8.8]", "[::ffff:192.168.1.1]",
            "[::ffff:c0a8:101]", "[::127.0.0.1]", "[fe80::1%25eth0]", "[fe80::1%1]")
            .forEach { assertFalse(it, local(it)) }
    }

    @Test fun dnsNamesAreNeverTreatedAsLanAddresses() {
        listOf("localhost", "camera.local", "internal.example", "192.168.1.12.example", "10.local")
            .forEach { assertFalse(it, local(it)) }
        assertFalse(WebDavLanTls.isLocalAddress(URI("/takes/")))
    }

    @Test fun defaultPolicyDoesNotInspectOrMutateConnection() {
        val socket = Socket("https://public.example/takes/take.mp4")
        val factory = socket.sslSocketFactory
        val verifier = socket.hostnameVerifier
        WebDavLanTls.apply(socket, WebDavDestination(URI("https://public.example/takes/")))
        assertEquals(0, socket.factoryWrites); assertEquals(0, socket.verifierWrites)
        assertSame(factory, socket.sslSocketFactory); assertSame(verifier, socket.hostnameVerifier)
        val plain = Plain(URL("http://192.168.1.12/takes/take.mp4"))
        WebDavLanTls.apply(plain, destination(false))
        assertFalse(plain.connectedForTest)
    }

    @Test fun explicitOptInChangesOnlyThatHttpsInstanceNeverGlobalDefaults() {
        val defaultFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
        val defaultVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
        val untouched = Socket()
        val socket = Socket()
        WebDavLanTls.apply(socket, destination())
        assertEquals(1, socket.factoryWrites); assertEquals(1, socket.verifierWrites)
        assertNotSame(defaultFactory, socket.sslSocketFactory)
        assertTrue(socket.hostnameVerifier.verify("untrusted-fixture", null))
        assertSame(defaultFactory, HttpsURLConnection.getDefaultSSLSocketFactory())
        assertSame(defaultVerifier, HttpsURLConnection.getDefaultHostnameVerifier())
        assertSame(defaultFactory, untouched.sslSocketFactory)
        assertSame(defaultVerifier, untouched.hostnameVerifier)
        assertFalse(socket.ioStarted)
    }

    @Test fun sameOriginAllowsEquivalentDefaultHttpsPortAndIpv6Case() {
        WebDavLanTls.apply(Socket("https://192.168.1.12:443/takes/take.mp4"), destination())
        val ipv6 = WebDavDestination(URI("https://[FD12::ABCD]:8443/takes/"), ignoreTlsErrors = true)
        val socket = Socket("https://[fd12::abcd]:8443/takes/take.mp4")
        WebDavLanTls.apply(socket, ipv6)
        assertEquals(1, socket.factoryWrites)
    }

    @Test fun crossOriginFactoryResultsRejectBeforeChangingTrustOrStartingIo() {
        listOf("https://192.168.1.13/takes/take.mp4", "https://8.8.8.8/takes/take.mp4",
            "https://192.168.1.12:8443/takes/take.mp4", "https://user@192.168.1.12/takes/take.mp4")
            .forEach { url ->
                val socket = Socket(url)
                expectRejected { WebDavLanTls.apply(socket, destination()) }
                assertEquals(0, socket.factoryWrites); assertEquals(0, socket.verifierWrites)
                assertFalse(socket.ioStarted)
            }
    }

    @Test fun explicitBypassRejectsHttpConnectionAndNonLocalDestination() {
        expectRejected { WebDavLanTls.apply(Plain(URL("http://192.168.1.12/takes/take.mp4")), destination()) }
        listOf("https://example.org/takes/", "https://8.8.8.8/takes/", "https://[::ffff:8.8.8.8]/takes/")
            .forEach { expectRejected { WebDavDestination(URI(it), ignoreTlsErrors = true) } }
        assertFalse(WebDavDestination(URI("https://8.8.8.8/takes/")).ignoreTlsErrors)
    }

    @Test fun putAppliesLanTrustBeforeActualOutputAndRetiresSocket() {
        val socket = Socket()
        val control = WebDavUploadControl(WebDavUploadPolicy(enabled = true, network = WebDavNetwork.WIFI))
        val result = WebDavUploadTransport({ socket }).upload(source(), destination(), control)
        assertEquals(WebDavUploadResult.Uploaded(2, "take.mp4"), result)
        assertArrayEquals(byteArrayOf(1, 2), socket.output.toByteArray())
        assertEquals(1, socket.factoryWrites); assertEquals(1, socket.verifierWrites)
        assertEquals(1, socket.disconnects)
    }

    @Test fun getAppliesLanTrustBeforeResponseAndRetiresSocket() = owned { scope ->
        val socket = Socket()
        val result = WebDavRemoteReconciler({ socket }).inspect(destination(), "take.mp4", 2, scope)
        assertTrue(result is WebDavRemoteObservation.CompleteBody)
        assertEquals(2L, (result as WebDavRemoteObservation.CompleteBody).sizeBytes)
        assertEquals(1, socket.factoryWrites); assertEquals(1, socket.verifierWrites)
        assertEquals(1, socket.disconnects)
    }

    @Test fun putRejectedOriginClosesOwnedSocketWithoutSendingBytes() {
        val socket = Socket("https://8.8.8.8/takes/take.mp4")
        val control = WebDavUploadControl(WebDavUploadPolicy(enabled = true, network = WebDavNetwork.WIFI))
        expectRejected { WebDavUploadTransport({ socket }).upload(source(), destination(), control) }
        assertEquals(0, socket.output.size()); assertFalse(socket.ioStarted)
        assertEquals(1, socket.disconnects)
        val next = requireNotNull(control.enter().attempt)
        control.leave(next)
        assertTrue(next.retirement.isRetired)
    }

    @Test fun getRejectedOriginClosesOwnedSocketWithoutRemoteEvidence() = owned { scope ->
        val socket = Socket("https://8.8.8.8/takes/take.mp4")
        assertEquals(WebDavRemoteObservation.Inconclusive, WebDavRemoteReconciler({ socket })
            .inspect(destination(), "take.mp4", 2, scope))
        assertFalse(socket.ioStarted); assertEquals(1, socket.disconnects)
    }

    private fun source() = object : WebDavClipSource {
        override fun snapshot() = WebDavClipSnapshot("fixture", "take.mp4", 2, 1, true, "video/mp4")
        override fun open() = ByteArrayInputStream(byteArrayOf(1, 2))
    }
    private fun owned(block: (WebDavOperationScope) -> Unit) {
        val control = WebDavUploadControl(WebDavUploadPolicy(enabled = true, network = WebDavNetwork.WIFI))
        val attempt = requireNotNull(control.enter().attempt)
        try { block(WebDavOperationScope(attempt)) } finally { control.leave(attempt) }
        assertTrue(attempt.retirement.isRetired)
    }
    private fun expectRejected(block: () -> Unit) {
        try { block(); fail("Expected rejected LAN TLS policy") } catch (_: IllegalArgumentException) { }
    }
    private class Plain(url: URL) : HttpURLConnection(url) {
        var connectedForTest = false
        override fun connect() { connectedForTest = true }
        override fun disconnect() = Unit
        override fun usingProxy() = false
    }
    private class Socket(url: String = "https://192.168.1.12/takes/take.mp4") : HttpsURLConnection(URL(url)) {
        var factoryWrites = 0
        var verifierWrites = 0
        var ioStarted = false
        var disconnects = 0
        val output = ByteArrayOutputStream()
        override fun setSSLSocketFactory(value: SSLSocketFactory) { super.setSSLSocketFactory(value); factoryWrites++ }
        override fun setHostnameVerifier(value: HostnameVerifier) { super.setHostnameVerifier(value); verifierWrites++ }
        private fun io() { check(factoryWrites == 1 && verifierWrites == 1); ioStarted = true }
        override fun getOutputStream(): ByteArrayOutputStream { io(); return output }
        override fun getInputStream(): ByteArrayInputStream { io(); return ByteArrayInputStream(byteArrayOf(1, 2)) }
        override fun getResponseCode(): Int { io(); return if (requestMethod == "PUT") 201 else 200 }
        override fun getHeaderFields(): Map<String, List<String>> = mapOf("Content-Length" to listOf("2"))
        override fun connect() { io() }
        override fun disconnect() { disconnects++ }
        override fun usingProxy() = false
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getPeerPrincipal(): Principal? = null
        override fun getLocalPrincipal(): Principal? = null
    }
}
