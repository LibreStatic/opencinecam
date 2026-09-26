/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.transfers

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Test

class WebDavRemoteReconcilerTest {
    private val policy = WebDavUploadPolicy(enabled = true, network = WebDavNetwork.WIFI)
    private val destination = WebDavDestination(URI("https://dav.example/takes/"), WebDavCredentials("camera", "secret"))

    @Test fun completeBodyIsActuallyHashedAndOwnerIsNotRetired() = withOwner { control, attempt, scope ->
        val bytes = ByteArray(196_613) { it.toByte() }
        val socket = Connection(bytes = bytes, headers = mapOf("Content-Length" to listOf(bytes.size.toString())))
        val observation = WebDavRemoteReconciler({ socket }).inspect(destination, "take.mp4", bytes.size.toLong(), scope)
        assertEquals(WebDavRemoteObservation.CompleteBody(bytes.size.toLong(), hash(bytes)), observation)
        assertEquals("GET", socket.requestMethod)
        assertEquals("identity", socket.getRequestProperty("Accept-Encoding"))
        assertEquals("no-cache", socket.getRequestProperty("Cache-Control"))
        assertEquals("Basic Y2FtZXJhOnNlY3JldA==", socket.getRequestProperty("Authorization"))
        assertFalse(socket.instanceFollowRedirects)
        assertFalse(socket.useCaches)
        assertEquals(1, socket.disconnects)
        assertFalse(attempt.retirement.isRetired)
        assertEquals(WebDavStopReason.BUSY, control.enter().reason)
    }

    @Test fun shorterCompletelyFramedBodyAndEmptyBodyAreHonestConflictEvidence() = withOwner { _, _, scope ->
        for (bytes in listOf(byteArrayOf(), byteArrayOf(1))) {
            val socket = Connection(bytes = bytes, headers = mapOf("Content-Length" to listOf(bytes.size.toString())))
            assertEquals(WebDavRemoteObservation.CompleteBody(bytes.size.toLong(), hash(bytes)),
                WebDavRemoteReconciler({ socket }).inspect(destination, "take.mp4", 2, scope))
        }
    }

    @Test fun truncatedAdvertisedLengthIsNotACompleteBody() = withOwner { _, _, scope ->
        val socket = Connection(bytes = byteArrayOf(1), headers = mapOf("Content-Length" to listOf("2")))
        assertEquals(WebDavRemoteObservation.Inconclusive, WebDavRemoteReconciler({ socket }).inspect(destination, "take.mp4", 2, scope))
    }

    @Test fun oversizedUnknownLengthReadsAtMostExpectedPlusOneByte() = withOwner { _, _, scope ->
        val socket = Connection(bytes = ByteArray(1000))
        assertEquals(WebDavRemoteObservation.Inconclusive, WebDavRemoteReconciler({ socket }).inspect(destination, "take.mp4", 10, scope))
        assertEquals(11L, socket.bytesRead)
    }

    @Test fun oversizedAdvertisedLengthDoesNotOpenBody() = withOwner { _, _, scope ->
        val socket = Connection(headers = mapOf("Content-Length" to listOf("100")))
        assertEquals(WebDavRemoteObservation.Inconclusive, WebDavRemoteReconciler({ socket }).inspect(destination, "take.mp4", 10, scope))
        assertEquals(0, socket.opens)
    }

    @Test fun longMaxExpectedSizeNeverOverflowsOrAllocatesTheWholeBody() = withOwner { _, _, scope ->
        val bytes = byteArrayOf(1)
        assertEquals(WebDavRemoteObservation.CompleteBody(1, hash(bytes)),
            WebDavRemoteReconciler({ Connection(bytes = bytes) }).inspect(destination, "take.mp4", Long.MAX_VALUE, scope))
    }

    @Test fun duplicateMalformedCompressedAndPartialFramingAreRejected() = withOwner { _, _, scope ->
        val invalid = listOf(
            mapOf("Content-Length" to listOf("1", "1")),
            mapOf("Content-Length" to listOf("1"), "content-length" to listOf("1")),
            mapOf("Content-Length" to listOf("01")), mapOf("Content-Length" to listOf("+1")),
            mapOf("Content-Length" to listOf("-1")), mapOf("Content-Length" to listOf("9223372036854775808")),
            mapOf("Transfer-Encoding" to listOf("chunked"), "Content-Length" to listOf("1")),
            mapOf("Transfer-Encoding" to listOf("gzip, chunked")), mapOf("Content-Encoding" to listOf("gzip")),
            mapOf("Content-Range" to listOf("bytes 0-0/1")),
        )
        for (headers in invalid) {
            val socket = Connection(headers = headers)
            assertEquals(headers.toString(), WebDavRemoteObservation.Inconclusive,
                WebDavRemoteReconciler({ socket }).inspect(destination, "take.mp4", 1, scope))
            assertEquals(0, socket.opens)
        }
    }

    @Test fun only200And404CanProduceEvidenceAndErrorBodiesAreNotRead() = withOwner { _, _, scope ->
        for (status in listOf(204, 206, 301, 302, 307, 308, 401, 403, 404, 412, 500)) {
            val socket = Connection(status = status)
            val result = WebDavRemoteReconciler({ socket }).inspect(destination, "take.mp4", 1, scope)
            assertEquals(when (status) {
                404 -> WebDavRemoteObservation.NotFound404
                401, 403 -> WebDavRemoteObservation.AuthenticationRejected
                else -> WebDavRemoteObservation.Inconclusive
            }, result)
            assertEquals(0, socket.opens)
            assertEquals(1, socket.disconnects)
        }
    }

    @Test fun deadlineRejectsLate200And404HeadersWithoutOpeningTheirBody() = withOwner { _, _, scope ->
        for (status in listOf(200, 404)) {
            val time = AtomicLong()
            val socket = Connection(status = status, onResponse = { time.set(10_000_000) })
            assertEquals(WebDavRemoteObservation.Inconclusive,
                WebDavRemoteReconciler({ socket }, totalDeadlineMs = 10, nanoTime = time::get).inspect(destination, "take.mp4", 1, scope))
            assertEquals(0, socket.opens)
            assertTrue(socket.connectTimeout <= 10)
        }
    }

    @Test fun remainingReadTimeoutShrinksAndLateBodyIsRejected() = withOwner { _, _, scope ->
        val time = AtomicLong()
        val socket = Connection(onRead = { time.addAndGet(6_000_000) })
        assertEquals(WebDavRemoteObservation.Inconclusive,
            WebDavRemoteReconciler({ socket }, totalDeadlineMs = 10, nanoTime = time::get).inspect(destination, "take.mp4", 1, scope))
        assertEquals(4, socket.readTimeout)
        assertEquals(1, socket.disconnects)
    }

    @Test fun deadlineIncludesBodyCloseAndDisconnectBeforeEvidenceCanEscape() = withOwner { _, _, scope ->
        for (duringClose in listOf(true, false)) {
            val time = AtomicLong()
            val socket = Connection(onClose = { if (duringClose) time.set(10_000_000) },
                onDisconnect = { if (!duringClose) time.set(10_000_000) })
            assertEquals(WebDavRemoteObservation.Inconclusive,
                WebDavRemoteReconciler({ socket }, totalDeadlineMs = 10, nanoTime = time::get).inspect(destination, "take.mp4", 1, scope))
        }
    }

    @Test fun stoppedScopeCannotCreateConnectionAndExceptionsStaySanitized() = withOwner { control, _, scope ->
        val bad = Connection(onRead = { throw SocketTimeoutException("secret destination") })
        val result = WebDavRemoteReconciler({ bad }).inspect(destination, "take.mp4", 1, scope)
        assertEquals(WebDavRemoteObservation.Inconclusive, result)
        assertFalse(result.toString().contains("secret"))
        control.pauseForRecording()
        assertEquals(WebDavRemoteObservation.Inconclusive,
            WebDavRemoteReconciler({ error("No request after stop") }).inspect(destination, "take.mp4", 1, scope))
    }

    @Test fun noProgressAndCloseFailuresAreNotVerification() = withOwner { _, _, scope ->
        val noProgress = object : Connection() {
            override fun getInputStream() = object : InputStream() {
                override fun read() = 0
                override fun read(bytes: ByteArray, offset: Int, length: Int) = 0
            }
        }
        assertEquals(WebDavRemoteObservation.Inconclusive, WebDavRemoteReconciler({ noProgress }).inspect(destination, "take.mp4", 1, scope))
        val closeFailure = Connection(onClose = { throw java.io.IOException("private close error") })
        assertEquals(WebDavRemoteObservation.Inconclusive, WebDavRemoteReconciler({ closeFailure }).inspect(destination, "take.mp4", 1, scope))
    }

    @Test fun blockedGetBodyKeepsReceiptPendingUntilActualCleanupAndOuterLeave() {
        val control = WebDavUploadControl(policy)
        val attempt = requireNotNull(control.enter().attempt)
        val scope = WebDavOperationScope(attempt)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val socket = Connection(onRead = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) })
            val result = executor.submit<WebDavRemoteObservation> {
                try { WebDavRemoteReconciler({ socket }).inspect(destination, "take.mp4", 1, scope) }
                finally { control.leave(attempt) }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val receipt = control.pauseForRecording()
            assertFalse(receipt.isRetired)
            assertFalse(receipt.awaitRetired(0, TimeUnit.SECONDS))
            control.updatePolicy(policy)
            assertEquals(WebDavStopReason.BUSY, control.enter().reason)
            release.countDown()
            assertEquals(WebDavRemoteObservation.Inconclusive, result.get(5, TimeUnit.SECONDS))
            assertTrue(receipt.isRetired)
        } finally {
            release.countDown(); executor.shutdown()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            control.leave(attempt)
        }
    }

    @Test fun actualLoopbackGetUsesEncodedPathAndHashesChunkedBytesWithoutRedirects() = withOwner { _, _, scope ->
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val request = executor.submit<List<String>> {
                    server.accept().use { peer ->
                        peer.soTimeout = 5000
                        val reader = peer.getInputStream().bufferedReader(Charsets.US_ASCII)
                        val lines = mutableListOf<String>()
                        while (true) {
                            val line = requireNotNull(reader.readLine())
                            if (line.isEmpty()) break
                            check(lines.size < 100 && line.length < 8192)
                            lines.add(line)
                        }
                        peer.getOutputStream().write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n3\r\nabc\r\n0\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        lines
                    }
                }
                val reconciler = WebDavRemoteReconciler({ uri ->
                    URI("http://127.0.0.1:${server.localPort}${uri.rawPath}").toURL().openConnection() as HttpURLConnection
                })
                assertEquals(WebDavRemoteObservation.CompleteBody(3, hash("abc".toByteArray())),
                    reconciler.inspect(destination, "take:100%?#.mp4", 3, scope))
                val lines = request.get(5, TimeUnit.SECONDS)
                assertEquals("GET /takes/take%3A100%25%3F%23.mp4 HTTP/1.1", lines.first())
                assertTrue(lines.any { it.equals("Accept-Encoding: identity", ignoreCase = true) })
                assertTrue(lines.any { it == "Authorization: Basic Y2FtZXJhOnNlY3JldA==" })
            } finally {
                server.close(); executor.shutdownNow()
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
    }

    @Test fun invalidArgumentsCannotReachNetwork() = withOwner { _, _, scope ->
        val reconciler = WebDavRemoteReconciler({ error("Invalid input reached network") })
        assertThrows(IllegalArgumentException::class.java) { reconciler.inspect(destination, "take.mp4", 0, scope) }
        assertEquals(WebDavRemoteObservation.Inconclusive, reconciler.inspect(destination, "../other", 1, scope))
        assertThrows(IllegalArgumentException::class.java) { WebDavRemoteReconciler(totalDeadlineMs = 0) }
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun withOwner(block: (WebDavUploadControl, WebDavUploadControl.Attempt, WebDavOperationScope) -> Unit) {
        val control = WebDavUploadControl(policy)
        val attempt = requireNotNull(control.enter().attempt)
        try { block(control, attempt, WebDavOperationScope(attempt)) }
        finally { control.leave(attempt) }
    }

    private open class Connection(
        private val bytes: ByteArray = byteArrayOf(1),
        private val status: Int = 200,
        private val headers: Map<String, List<String>> = emptyMap(),
        private val onResponse: () -> Unit = {}, private val onRead: () -> Unit = {},
        private val onClose: () -> Unit = {}, private val onDisconnect: () -> Unit = {},
    ) : HttpURLConnection(URI("https://dav.example/").toURL()) {
        var opens = 0
        var bytesRead = 0L
        var disconnects = 0
        override fun getResponseCode(): Int { onResponse(); return status }
        override fun getHeaderFields(): Map<String, List<String>> = headers
        override fun getInputStream(): InputStream {
            opens++
            return object : ByteArrayInputStream(bytes) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    onRead()
                    return super.read(buffer, offset, length).also { if (it > 0) bytesRead += it }
                }
                override fun close() { onClose(); super.close() }
            }
        }
        override fun connect() = Unit
        override fun disconnect() { disconnects++; onDisconnect() }
        override fun usingProxy() = false
    }
}
