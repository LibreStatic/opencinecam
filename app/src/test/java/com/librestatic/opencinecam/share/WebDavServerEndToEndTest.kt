/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.share

import java.io.File
import java.net.Authenticator
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.PasswordAuthentication
import java.net.Socket
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The server end to end on 127.0.0.1 (test only: production never binds loopback), driven by
 * [RawHttpClient] with its own Digest implementation and by the JDK's HttpURLConnection,
 * whose Digest support is a second, independent client.
 */
class WebDavServerEndToEndTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val password = "k7pq2m9xwe"
    private val take = ShareFixture.TAKE
    private val dng0 = "/$take/${take}_000000.dng"
    private val servers = mutableListOf<WebDavServer>()

    private fun server(
        limits: ShareServerLimits = ShareServerLimits(),
        takes: () -> List<ShareTake> = { listOf(ShareFixture.take()) },
        includeOriginal: () -> Boolean = { true },
    ): WebDavServer {
        val clock = ShareClock.MONOTONIC
        val server = WebDavServer(ShareTree(takes, clock, includeOriginal = includeOriginal), DigestAuthenticator(ShareCredentials("occ", password), clock), clock, limits)
        val port = NetworkShareSessionTest.freePort()
        server.bind(listOf(loopback), port..port)
        servers += server
        return server
    }

    private fun client(server: WebDavServer, user: String? = "occ", pass: String? = password) = RawHttpClient(loopback, server.port, user, pass)

    @After
    fun closeAll() {
        servers.forEach { it.close() }
        Authenticator.setDefault(null)
    }

    @Test
    fun everyRequestNeedsDigestOptionsIncluded() {
        val server = server()
        for (method in listOf("OPTIONS", "PROPFIND", "GET", "HEAD", "DELETE")) {
            RawHttpClient(loopback, server.port).use { anonymous ->
                val response = anonymous.request(method, if (method == "GET") dng0 else "/")
                assertEquals(method, 401, response.status)
                val challenge = response.header("www-authenticate")!!
                assertTrue(challenge, challenge.startsWith("Digest ") && "qop=\"auth\"" in challenge && "algorithm=MD5" in challenge)
                assertEquals(0, response.body.size)
            }
        }
        RawHttpClient(loopback, server.port).use { assertEquals(401, it.request("OPTIONS", "*").status) }
        client(server).use { authed ->
            val options = authed.request("OPTIONS", "/")
            assertEquals(200, options.status)
            assertEquals("1", options.header("dav"))
            assertEquals("DAV", options.header("ms-author-via"))
            assertEquals("OPTIONS, PROPFIND, GET, HEAD", options.header("allow"))
            // Keep-alive: the same socket and nonce carry the next requests with rising nc.
            assertEquals(200, authed.send("OPTIONS", "*").status)
            assertEquals(207, authed.send("PROPFIND", "/", mapOf("Depth" to "0")).status)
        }
    }

    @Test
    fun wrongPasswordIsRefusedAndTenFailuresLockTheAddressOut() {
        val server = server()
        // Each connection: the anonymous probe and one wrong answer (a connection closes after three 401s anyway).
        repeat(DigestAuthenticator.MAX_FAILURES) {
            client(server, pass = "wrongwrong").use { guesser -> assertEquals(401, guesser.request("PROPFIND", "/", mapOf("Depth" to "0")).status) }
        }
        client(server, pass = "wrongwrong").use { guesser ->
            val locked = guesser.request("PROPFIND", "/", mapOf("Depth" to "0"))
            assertEquals(429, locked.status)
            assertEquals("30", locked.header("retry-after"))
            assertTrue(guesser.closedByServer)
        }
        // The right password from the same address is refused too until the lockout ends.
        client(server).use { assertEquals(429, it.request("OPTIONS", "/").status) }
    }

    @Test
    fun aReplayedAuthorizationIsRefused() {
        val server = server()
        client(server).use { c ->
            assertEquals(200, c.request("OPTIONS", "/").status)
            val replay = c.digest("OPTIONS", "/", nonceCount = 1)
            val stale = c.send("OPTIONS", "/", authorization = replay)
            assertEquals(401, stale.status)
            assertTrue(stale.header("www-authenticate")!!.contains("stale=true"))
            // Credentials for another resource do not open this one.
            assertEquals(401, c.send("GET", dng0, authorization = c.digest("GET", "/")).status)
        }
    }

    @Test
    fun propfindListsTheTreeWithSizesThatMatchTheBytesServed() {
        val server = server()
        client(server).use { c ->
            val root0 = c.request("PROPFIND", "/", mapOf("Depth" to "0"))
            assertEquals(207, root0.status)
            assertEquals(1, responses(root0.text).size)

            val root1 = c.send("PROPFIND", "/", mapOf("Depth" to "1"))
            assertEquals(listOf("/", "/$take/"), responses(root1.text).map { it.first })

            val folder = c.send("PROPFIND", "/$take/", mapOf("Depth" to "1"))
            assertEquals(207, folder.status)
            assertTrue(folder.header("content-type")!!.startsWith("application/xml"))
            val entries = responses(folder.text)
            assertEquals("/$take/", entries.first().first)
            val files = entries.drop(1)
            assertTrue(files.size >= 7) // frames 0-4, the WAV and the .ocraw
            for ((href, length) in files) {
                val get = c.send("GET", href)
                assertEquals(href, 200, get.status)
                assertEquals(href, length, get.body.size.toLong())
                val name = href.substringAfterLast('/')
                val expected = if (name.endsWith(".ocraw")) ShareFixture.ocraw.readBytes() else File(ShareFixture.golden, name).readBytes()
                assertArrayEquals(href, expected, get.body)
            }

            // A file's own PROPFIND, without the trailing slash the folder needs.
            val one = c.send("PROPFIND", dng0, mapOf("Depth" to "1"))
            assertEquals(listOf(dng0), responses(one.text).map { it.first })
            assertEquals(207, c.send("PROPFIND", "/$take", mapOf("Depth" to "0")).status)
        }
    }

    @Test
    fun theOriginalIsServedOnlyWhileShown() {
        var show = false
        val server = server(includeOriginal = { show })
        val original = "/$take/$take.ocraw"
        client(server).use { c ->
            val hidden = responses(c.request("PROPFIND", "/$take/", mapOf("Depth" to "1")).text).map { it.first }
            assertTrue(hidden.toString(), hidden.none { it.endsWith(".ocraw") })
            assertTrue(hidden.toString(), "/$take/$take.wav" in hidden && dng0 in hidden)
            assertEquals(404, c.send("GET", original).status)
            assertEquals(404, c.send("HEAD", original).status)
            assertEquals(404, c.send("GET", original, mapOf("Range" to "bytes=0-9")).status)
            assertEquals(404, c.send("PROPFIND", original, mapOf("Depth" to "0")).status)
            assertEquals(200, c.send("GET", dng0).status)

            // Turned on while the server runs: the next requests see it.
            show = true
            val shown = responses(c.send("PROPFIND", "/$take/", mapOf("Depth" to "1")).text)
            assertEquals(ShareFixture.ocraw.length(), shown.single { it.first == original }.second)
            val get = c.send("GET", original)
            assertEquals(200, get.status)
            assertArrayEquals(ShareFixture.ocraw.readBytes(), get.body)
            val part = c.send("GET", original, mapOf("Range" to "bytes=0-9"))
            assertEquals(206, part.status)
            assertArrayEquals(ShareFixture.ocraw.readBytes().copyOfRange(0, 10), part.body)

            show = false
            assertEquals(404, c.send("GET", original).status)
        }
    }

    @Test
    fun infiniteDepthIsForbidden() {
        val server = server()
        client(server).use { c ->
            val infinite = c.request("PROPFIND", "/", mapOf("Depth" to "infinity"))
            assertEquals(403, infinite.status)
            assertTrue(infinite.text.contains("propfind-finite-depth"))
            assertEquals(403, c.send("PROPFIND", "/").status) // no Depth means infinity
            assertEquals(400, c.send("PROPFIND", "/", mapOf("Depth" to "2")).status)
        }
    }

    @Test
    fun aPropfindBodyIsReadAndIgnored() {
        val server = server()
        val body = "<?xml version=\"1.0\"?><D:propfind xmlns:D=\"DAV:\"><D:prop><D:getcontentlength/></D:prop></D:propfind>".toByteArray()
        client(server).use { c ->
            assertEquals(207, c.request("PROPFIND", "/", mapOf("Depth" to "0", "Content-Type" to "text/xml"), body).status)
            // With Expect: 100-continue the server says Continue first; the client skips it.
            assertEquals(207, c.send("PROPFIND", "/$take/", mapOf("Depth" to "1", "Expect" to "100-continue"), body).status)
            assertEquals(413, c.send("PROPFIND", "/", mapOf("Depth" to "0"), ByteArray(64 * 1024 + 1)).status)
        }
    }

    @Test
    fun getHeadAndRanges() {
        val server = server()
        val golden = File(ShareFixture.golden, "${take}_000000.dng").readBytes()
        client(server).use { c ->
            val head = c.request("HEAD", dng0)
            assertEquals(200, head.status)
            assertEquals(golden.size.toString(), head.header("content-length"))
            assertEquals("bytes", head.header("accept-ranges"))
            assertEquals(ShareTree.DNG_TYPE, head.header("content-type"))
            val etag = head.header("etag")!!

            val part = c.send("GET", dng0, mapOf("Range" to "bytes=10-19"))
            assertEquals(206, part.status)
            assertEquals("bytes 10-19/${golden.size}", part.header("content-range"))
            assertArrayEquals(golden.copyOfRange(10, 20), part.body)

            val tail = c.send("GET", dng0, mapOf("Range" to "bytes=-5"))
            assertArrayEquals(golden.copyOfRange(golden.size - 5, golden.size), tail.body)

            val open = c.send("GET", dng0, mapOf("Range" to "bytes=${golden.size - 3}-"))
            assertEquals(3, open.body.size)

            val past = c.send("GET", dng0, mapOf("Range" to "bytes=${golden.size}-"))
            assertEquals(416, past.status)
            assertEquals("bytes */${golden.size}", past.header("content-range"))

            assertEquals(200, c.send("GET", dng0, mapOf("Range" to "bytes=0-1,4-5")).status)
            assertEquals(206, c.send("GET", dng0, mapOf("Range" to "bytes=0-1", "If-Range" to etag)).status)
            val changed = c.send("GET", dng0, mapOf("Range" to "bytes=0-1", "If-Range" to "\"old\""))
            assertEquals(200, changed.status)
            assertEquals(golden.size, changed.body.size)

            val headRange = c.send("HEAD", dng0, mapOf("Range" to "bytes=0-99"))
            assertEquals(206, headRange.status)
            assertEquals("100", headRange.header("content-length"))
        }
    }

    @Test
    fun foldersAnswerGetWithAnEscapedListing() {
        val server = server()
        client(server).use { c ->
            val root = c.request("GET", "/")
            assertEquals(200, root.status)
            assertEquals("nosniff", root.header("x-content-type-options"))
            assertTrue(root.text.contains("href=\"/$take/\""))
        }
    }

    @Test
    fun writeMethodsAre405() {
        val server = server()
        client(server).use { c ->
            assertEquals(200, c.request("OPTIONS", "/").status)
            for (method in listOf("PUT", "DELETE", "MKCOL", "COPY", "MOVE", "LOCK", "UNLOCK", "PROPPATCH", "POST", "TRACE", "CONNECT")) {
                val response = c.send(method, dng0)
                assertEquals(method, 405, response.status)
                assertEquals("OPTIONS, PROPFIND, GET, HEAD", response.header("allow"))
            }
        }
        // A write with a body is refused and the connection closed, the body never read.
        client(server).use { c ->
            assertEquals(200, c.request("OPTIONS", "/").status)
            assertEquals(405, c.send("PUT", "/$take/new.dng", body = ByteArray(10)).status)
            assertTrue(c.closedByServer)
        }
        client(server).use { c ->
            assertEquals(200, c.request("OPTIONS", "/").status)
            assertEquals(400, c.send("GET", dng0, body = ByteArray(10)).status)
        }
    }

    @Test
    fun traversalAndUnknownNamesNeverReachTheFilesystem() {
        val server = server()
        client(server).use { c ->
            assertEquals(200, c.request("OPTIONS", "/").status)
            for (target in listOf("/../", "/$take/../../etc/passwd", "/$take/%2e%2e/%2e%2e/etc/passwd", "/$take%2f..%2f..", "/$take/a%00.dng",
                "/$take/..%5c..%5cwin.ini", "/$take//${take}_000000.dng")) {
                assertEquals(target, 400, c.send("GET", target).status)
            }
            for (target in listOf("/etc/passwd", "/$take/desktop.ini", "/$take/${take}_000005.dng", "/$take/${take}_000000.dng/", "/$take/a/b")) {
                assertEquals(target, 404, c.send("GET", target).status)
            }
        }
    }

    @Test
    fun aForeignHostHeaderIsRefused() {
        val server = server()
        client(server).use { c ->
            assertEquals(200, c.request("OPTIONS", "/").status)
            val rebinding = "OPTIONS / HTTP/1.1\r\nHost: evil.example:${server.port}\r\nAuthorization: ${c.digest("OPTIONS", "/")}\r\n\r\n"
            assertEquals(400, c.sendRaw(rebinding.toByteArray()).status)
            assertTrue(c.closedByServer)
        }
        client(server).use { c ->
            assertEquals(200, c.request("OPTIONS", "/").status)
            val otherPort = "OPTIONS / HTTP/1.1\r\nHost: 127.0.0.1:1\r\nAuthorization: ${c.digest("OPTIONS", "/")}\r\n\r\n"
            assertEquals(400, c.sendRaw(otherPort.toByteArray()).status)
        }
    }

    @Test
    fun malformedAndOversizedRequestsAreRefusedAndClosed() {
        val server = server()
        client(server).use { c ->
            assertEquals(400, c.sendRaw("GARBAGE\r\n\r\n".toByteArray()).status)
            assertTrue(c.closedByServer)
        }
        client(server).use { c ->
            assertEquals(414, c.sendRaw("GET /${"a".repeat(9000)} HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray()).status)
        }
        client(server).use { c ->
            val headers = (1..200).joinToString("") { "X-$it: v\r\n" }
            assertEquals(431, c.sendRaw("GET / HTTP/1.1\r\nHost: x\r\n$headers\r\n".toByteArray()).status)
        }
        client(server).use { c ->
            assertEquals(400, c.sendRaw("GET / HTTP/1.1\r\nHost: x\r\nContent-Length: 3\r\nTransfer-Encoding: chunked\r\n\r\n".toByteArray()).status)
        }
    }

    @Test
    fun idleKeepAliveConnectionsAndSilentClientsAreClosed() {
        val server = server(ShareServerLimits(readTimeoutMs = 400, keepAliveIdleMs = 200))
        client(server).use { c ->
            assertEquals(200, c.request("OPTIONS", "/").status)
            Thread.sleep(600)
            assertTrue(c.closedByServer)
        }
        // A request head that never completes is cut at the read timeout.
        Socket(loopback, server.port).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: 127".toByteArray())
            socket.getOutputStream().flush()
            val response = RawHttpClient.readResponse(socket.getInputStream(), head = false)
            assertEquals(408, response.status)
        }
    }

    @Test
    fun connectionsOverTheCapGet503() {
        val server = server(ShareServerLimits(workers = 2, readTimeoutMs = 5_000))
        val held = List(2) { Socket(loopback, server.port) }
        try {
            Thread.sleep(300) // both taken by workers, waiting for a request head
            Socket(loopback, server.port).use { extra ->
                extra.soTimeout = 5_000
                val response = RawHttpClient.readResponse(extra.getInputStream(), head = false)
                assertEquals(503, response.status)
                assertEquals("2", response.header("retry-after"))
            }
        } finally {
            held.forEach { it.close() }
        }
    }

    @Test
    fun theJdkHttpClientAuthenticatesWithDigestAndDownloads() {
        val server = server()
        Authenticator.setDefault(object : Authenticator() {
            override fun getPasswordAuthentication() = PasswordAuthentication("occ", password.toCharArray())
        })
        val wav = File(ShareFixture.golden, "$take.wav").readBytes()
        val connection = URL("http://127.0.0.1:${server.port}/$take/$take.wav").openConnection() as HttpURLConnection
        try {
            assertEquals(200, connection.responseCode)
            assertEquals(ShareTree.WAV_TYPE, connection.contentType)
            assertArrayEquals(wav, connection.inputStream.use { it.readBytes() })
        } finally {
            connection.disconnect()
        }
        val ranged = URL("http://127.0.0.1:${server.port}$dng0").openConnection() as HttpURLConnection
        try {
            ranged.setRequestProperty("Range", "bytes=0-3")
            assertEquals(206, ranged.responseCode)
            assertArrayEquals(File(ShareFixture.golden, "${take}_000000.dng").readBytes().copyOf(4), ranged.inputStream.use { it.readBytes() })
        } finally {
            ranged.disconnect()
        }
    }

    @Test
    fun threeChallengesInARowCloseTheConnection() {
        val server = server()
        RawHttpClient(loopback, server.port).use { c ->
            assertEquals("keep-alive", c.send("OPTIONS", "/").header("connection"))
            assertEquals("keep-alive", c.send("OPTIONS", "/").header("connection"))
            val third = c.send("OPTIONS", "/")
            assertEquals(401, third.status)
            assertEquals("close", third.header("connection"))
            assertTrue(c.closedByServer)
        }
        // A normal client (one challenge, then authenticated) is never cut.
        client(server).use { c ->
            repeat(5) { assertEquals(200, c.request("OPTIONS", "/").status) }
        }
    }

    @Test
    fun oneAddressGetsAtMostThreeConnections() {
        val server = server(ShareServerLimits(readTimeoutMs = 5_000, unauthenticatedTimeoutMs = 5_000))
        val held = List(3) { Socket(loopback, server.port) }
        try {
            Thread.sleep(300)
            Socket(loopback, server.port).use { extra ->
                extra.soTimeout = 5_000
                assertEquals(503, RawHttpClient.readResponse(extra.getInputStream(), head = false).status)
            }
        } finally {
            held.forEach { it.close() }
        }
        Thread.sleep(300)
        // The slots come back once those close.
        client(server).use { assertEquals(200, it.request("OPTIONS", "/").status) }
    }

    @Test
    fun anAuthenticatedAddressMayOpenMoreConnections() {
        // Desktop clients such as gvfs open several connections at once; after the password
        // only the worker pool caps them.
        val server = server(ShareServerLimits(readTimeoutMs = 5_000, unauthenticatedTimeoutMs = 5_000))
        client(server).use { assertEquals(200, it.request("OPTIONS", "/").status) }
        val held = List(5) { Socket(loopback, server.port) }
        try {
            Thread.sleep(300)
            client(server).use { assertEquals(200, it.request("OPTIONS", "/").status) }
        } finally {
            held.forEach { it.close() }
        }
    }

    @Test
    fun unauthenticatedConnectionsGetTheShortTimeout() {
        val server = server(ShareServerLimits(readTimeoutMs = 5_000, keepAliveIdleMs = 5_000, unauthenticatedTimeoutMs = 300))
        Socket(loopback, server.port).use { silent ->
            silent.soTimeout = 3_000
            val started = System.nanoTime()
            assertEquals(-1, silent.getInputStream().read())
            assertTrue((System.nanoTime() - started) / 1_000_000 < 2_500)
        }
        // After a challenge, still the short wait.
        RawHttpClient(loopback, server.port).use { c ->
            assertEquals(401, c.send("OPTIONS", "/").status)
            Thread.sleep(800)
            assertTrue(c.closedByServer)
        }
        // Once authenticated, the keep-alive timeout applies.
        client(server).use { c ->
            assertEquals(200, c.request("OPTIONS", "/").status)
            Thread.sleep(800)
            assertEquals(200, c.send("OPTIONS", "/").status)
        }
    }

    @Test
    fun aTakeThatCannotBeIndexedIsA500() {
        val unreadable = ShareTake("bad", "B001_C001.ocraw", 4096, ShareFixture.MODIFIED_MS) { throw java.io.IOException("permission revoked") }
        val server = server(takes = { listOf(unreadable) })
        client(server).use { c ->
            assertEquals(207, c.request("PROPFIND", "/", mapOf("Depth" to "1")).status)
            val folder = c.send("PROPFIND", "/B001_C001/", mapOf("Depth" to "1"))
            assertEquals(500, folder.status)
            assertEquals("close", folder.header("connection"))
        }
        client(server).use { c ->
            assertEquals(500, c.request("GET", "/B001_C001/B001_C001_000000.dng").status)
        }
        client(server).use { c ->
            assertEquals(500, c.request("GET", "/B001_C001/").status)
        }
    }

    @Test
    fun closingTheServerDropsListenersAndConnections() {
        val server = server()
        val c = client(server)
        assertEquals(200, c.request("OPTIONS", "/").status)
        server.close()
        assertTrue(c.closedByServer)
        c.close()
        assertTrue(runCatching { Socket(loopback, server.port).close() }.isFailure)
        assertNull(server.boundAddresses().firstOrNull())
    }

    /** (href, getcontentlength or -1) per `response`, parsed as namespaced XML. */
    private fun responses(xml: String): List<Pair<String, Long>> {
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(xml.byteInputStream(Charsets.UTF_8))
        val nodes = document.getElementsByTagNameNS("DAV:", "response")
        return (0 until nodes.length).map { i ->
            val element = nodes.item(i) as Element
            val href = element.getElementsByTagNameNS("DAV:", "href").item(0).textContent
            val length = element.getElementsByTagNameNS("DAV:", "getcontentlength").item(0)?.textContent?.toLong() ?: -1L
            href to length
        }
    }
}
