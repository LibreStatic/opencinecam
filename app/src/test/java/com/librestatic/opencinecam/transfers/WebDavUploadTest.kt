/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.transfers

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class WebDavUploadTest {
    private val enabled = WebDavUploadPolicy(enabled = true, network = WebDavNetwork.WIFI)
    private val destination = WebDavDestination(URI("https://dav.example/collection/"), WebDavCredentials("camera", "secret"))

    @Test fun defaultPolicyNeverOpensMediaOrNetwork() {
        val source = object : WebDavClipSource {
            override fun snapshot(): WebDavClipSnapshot = error("Media opened without consent")
            override fun open(): InputStream = error("Media opened without consent")
        }
        val result = WebDavUploadTransport({ error("Network opened without consent") })
            .upload(source, destination, WebDavUploadControl())
        assertEquals(WebDavUploadResult.Stopped(WebDavStopReason.DISABLED), result)
    }

    @Test fun eligibilityRequiresWifiOrExplicitCellularConsentAndNoRecording() {
        assertNull(enabled.stopReason())
        assertEquals(WebDavStopReason.RECORDING, enabled.copy(recording = true).stopReason())
        assertEquals(WebDavStopReason.CELLULAR_CONSENT_REQUIRED, enabled.copy(network = WebDavNetwork.CELLULAR).stopReason())
        assertNull(enabled.copy(network = WebDavNetwork.CELLULAR, allowCellular = true).stopReason())
        assertEquals(WebDavStopReason.NETWORK_UNAVAILABLE, enabled.copy(network = WebDavNetwork.OTHER).stopReason())
        assertEquals(WebDavStopReason.NETWORK_UNAVAILABLE, enabled.copy(network = WebDavNetwork.OFFLINE).stopReason())
    }

    @Test fun pendingEmptyAndInvalidNameClipsNeverOpenNetwork() {
        val transport = WebDavUploadTransport({ error("Invalid clip reached network") })
        val control = WebDavUploadControl(enabled)
        val source = BytesSource(ByteArray(4))
        source.state = source.state.copy(finalized = false)
        assertEquals(WebDavFailure.SOURCE_NOT_FINALIZED, (transport.upload(source, destination, control) as WebDavUploadResult.Failed).reason)
        source.state = source.state.copy(finalized = true, sizeBytes = 0)
        assertEquals(WebDavFailure.SOURCE_INVALID, (transport.upload(source, destination, control) as WebDavUploadResult.Failed).reason)
        source.state = source.state.copy(sizeBytes = 4, displayName = "../other.mp4")
        assertEquals(WebDavFailure.SOURCE_INVALID, (transport.upload(source, destination, control) as WebDavUploadResult.Failed).reason)
        source.state = source.state.copy(displayName = "take.mp4", mimeType = "video/mp4\r\nX-Test: injected")
        assertEquals(WebDavFailure.SOURCE_INVALID, (transport.upload(source, destination, control) as WebDavUploadResult.Failed).reason)
    }

    @Test fun actualConditionalPutPreservesBytesAndEncodesDestinationName() {
        val bytes = ByteArray(196_613) { (it * 37).toByte() }
        Server().use { server ->
            val source = BytesSource(bytes).apply { state = state.copy(displayName = "take ñ #1?.mp4") }
            val result = server.transport().upload(source, destination, WebDavUploadControl(enabled))
            assertEquals(WebDavUploadResult.Uploaded(bytes.size.toLong(), source.state.displayName), result)
            val request = server.await()
            assertEquals("PUT /collection/take%20%C3%B1%20%231%3F.mp4 HTTP/1.1", request.requestLine)
            assertEquals("*", request.headers["if-none-match"])
            assertEquals(bytes.size.toString(), request.headers["content-length"])
            assertEquals("Basic Y2FtZXJhOnNlY3JldA==", request.headers["authorization"])
            assertArrayEquals(bytes, request.body)
            assertEquals(1, source.opens)
        }
    }

    @Test fun serverRejectsExistingNameWithoutOverwriteOrRetry() {
        Server(status = 412).use { server ->
            val result = server.transport().upload(BytesSource(byteArrayOf(1, 2)), destination, WebDavUploadControl(enabled)) as WebDavUploadResult.Failed
            assertEquals(WebDavFailure.HTTP_REJECTED, result.reason)
            assertEquals(412, result.httpStatus)
            assertTrue(result.remoteMayExist)
            assertEquals("*", server.await().headers["if-none-match"])
        }
    }

    @Test fun redirectDoesNotForwardCredentialsOrSendSecondRequest() {
        Server(status = 307, responseHeaders = "Location: http://127.0.0.1:9/stolen\r\n").use { server ->
            val result = server.transport().upload(BytesSource(byteArrayOf(3)), destination, WebDavUploadControl(enabled)) as WebDavUploadResult.Failed
            assertEquals(WebDavFailure.REDIRECT_REJECTED, result.reason)
            assertEquals(307, result.httpStatus)
            server.await()
            assertFalse(result.toString().contains("secret"))
        }
    }

    @Test fun nonSuccessStatusIsNotReportedAsUpload() {
        for (status in listOf(202, 401, 403, 507)) {
            Server(status).use { server ->
                val result = server.transport().upload(BytesSource(byteArrayOf(4)), destination, WebDavUploadControl(enabled))
                assertTrue("Status $status was accepted", result is WebDavUploadResult.Failed)
                assertFalse(result.toString().contains("secret"))
                server.await()
            }
        }
    }

    @Test fun truncatedOrGrowingSourceIsNotReportedAsUpload() {
        for (declared in listOf(3L, 5L)) {
            val socket = MemoryConnection()
            val source = BytesSource(ByteArray(4)).apply { state = state.copy(sizeBytes = declared) }
            val result = WebDavUploadTransport({ socket }).upload(source, destination, WebDavUploadControl(enabled)) as WebDavUploadResult.Failed
            assertEquals(WebDavFailure.SOURCE_CHANGED, result.reason)
            assertTrue(result.remoteMayExist)
            assertTrue(socket.disconnected)
        }
    }

    @Test fun sourceMutationAfterStreamingPreventsSuccess() {
        val bytes = ByteArray(4)
        val source = object : WebDavClipSource {
            var snapshots = 0
            override fun snapshot(): WebDavClipSnapshot {
                snapshots++
                return WebDavClipSnapshot("id", "take.mp4", 4, snapshots.toLong(), true)
            }
            override fun open() = ByteArrayInputStream(bytes)
        }
        val result = WebDavUploadTransport({ MemoryConnection() }).upload(source, destination, WebDavUploadControl(enabled)) as WebDavUploadResult.Failed
        assertEquals(WebDavFailure.SOURCE_CHANGED, result.reason)
        assertTrue(result.remoteMayExist)
    }

    @Test fun recordingPauseDisconnectsAndNeverAutoResumesAnAttempt() {
        val socket = MemoryConnection()
        val control = WebDavUploadControl(enabled)
        val source = object : WebDavClipSource {
            override fun snapshot() = WebDavClipSnapshot("id", "take.mp4", 131_072, 1, true)
            override fun open(): InputStream = object : ByteArrayInputStream(ByteArray(131_072)) {
                var reads = 0
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    if (++reads == 2) {
                        control.updatePolicy(enabled.copy(recording = true))
                        control.updatePolicy(enabled)
                    }
                    return super.read(bytes, offset, length)
                }
            }
        }
        val transport = WebDavUploadTransport({ socket })
        val result = transport.upload(source, destination, control) as WebDavUploadResult.Stopped
        assertEquals(WebDavStopReason.RECORDING, result.reason)
        assertEquals(65_536L, result.bytesSent)
        assertTrue(result.remoteMayExist)
        assertTrue(socket.disconnected)
        assertEquals(65_536, socket.body.size())
        assertTrue(transport.upload(BytesSource(byteArrayOf(1)), destination, control) is WebDavUploadResult.Uploaded)
    }

    @Test fun networkConsentRevocationAndCancellationStopTheBoundConnection() {
        val changes: List<Pair<WebDavStopReason, (WebDavUploadControl) -> Unit>> = listOf(
            WebDavStopReason.CANCELLED to { it.cancel() },
            WebDavStopReason.DISABLED to { it.updatePolicy(enabled.copy(enabled = false)) },
            WebDavStopReason.CELLULAR_CONSENT_REQUIRED to { it.updatePolicy(enabled.copy(network = WebDavNetwork.CELLULAR)) },
            WebDavStopReason.NETWORK_UNAVAILABLE to { it.updatePolicy(enabled.copy(network = WebDavNetwork.OFFLINE)) },
        )
        for ((reason, change) in changes) {
            val control = WebDavUploadControl(enabled)
            val socket = MemoryConnection(onWrite = { change(control) })
            val result = WebDavUploadTransport({ socket }).upload(BytesSource(ByteArray(131_072)), destination, control) as WebDavUploadResult.Stopped
            assertEquals(reason, result.reason)
            assertTrue(socket.disconnected)
            assertTrue(result.remoteMayExist)
        }
    }

    @Test fun concurrentAttemptIsBusyAndFailedAttemptReleasesAdmission() {
        val control = WebDavUploadControl(enabled)
        val admission = control.enter()
        assertNotNull(admission.attempt)
        val transport = WebDavUploadTransport({ throw IOException("secret must not reach result") })
        assertEquals(WebDavUploadResult.Stopped(WebDavStopReason.BUSY), transport.upload(BytesSource(byteArrayOf(1)), destination, control))
        control.leave(requireNotNull(admission.attempt))
        val failed = transport.upload(BytesSource(byteArrayOf(1)), destination, control) as WebDavUploadResult.Failed
        assertEquals(WebDavFailure.NETWORK_IO, failed.reason)
        assertFalse(failed.toString().contains("secret"))
        assertNotNull(control.enter().attempt)
    }

    @Test fun boundedChunksDoNotMaterializeEntireClip() {
        val size = 8L * 1024 * 1024 + 7
        val maxRequest = AtomicInteger()
        var supplied = 0L
        val source = object : WebDavClipSource {
            override fun snapshot() = WebDavClipSnapshot("id", "large.mp4", size, 1, true)
            override fun open(): InputStream = object : InputStream() {
                override fun read(): Int = if (supplied < size) { supplied++; 42 } else -1
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    maxRequest.accumulateAndGet(length) { first, second -> maxOf(first, second) }
                    if (supplied == size) return -1
                    val count = minOf(length.toLong(), size - supplied).toInt()
                    bytes.fill(42, offset, offset + count)
                    supplied += count
                    return count
                }
            }
        }
        var received = 0L
        val socket = object : HttpURLConnection(URI("https://dav.example/").toURL()) {
            override fun getOutputStream() = object : java.io.OutputStream() {
                override fun write(value: Int) { received++ }
                override fun write(bytes: ByteArray, offset: Int, length: Int) { received += length }
            }
            override fun getResponseCode() = 201
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
        }
        assertEquals(WebDavUploadResult.Uploaded(size, "large.mp4"), WebDavUploadTransport({ socket }).upload(source, destination, WebDavUploadControl(enabled)))
        assertEquals(size, received)
        assertEquals(65_536, maxRequest.get())
    }

    @Test fun destinationValidationRejectsCleartextCredentialsQueriesAndTraversal() {
        for (url in listOf("http://dav.example/", "https://u:p@dav.example/", "https://dav.example/?token=secret", "https://dav.example/#secret", "https://dav.example/a/../", "https://dav.example/%2e%2e/")) {
            assertThrows(IllegalArgumentException::class.java) { WebDavDestination(URI(url)) }
        }
        for (name in listOf("../x", "a/b", "a\\b", ".", "..", "x\r\ny", "a".repeat(256))) {
            assertThrows(IllegalArgumentException::class.java) { destination.clipUri(name) }
        }
        assertEquals("WebDavCredentials(redacted)", WebDavCredentials("camera", "secret").toString())
        assertEquals("WebDavDestination(redacted)", destination.toString())
    }

    @Test fun sourceFailureBeforeConnectionHasNoAmbiguousRemoteState() {
        val source = object : WebDavClipSource {
            override fun snapshot(): WebDavClipSnapshot = throw IOException("content://private/secret")
            override fun open(): InputStream = error("Not reached")
        }
        val result = WebDavUploadTransport({ error("Not reached") }).upload(source, destination, WebDavUploadControl(enabled)) as WebDavUploadResult.Failed
        assertEquals(WebDavFailure.SOURCE_IO, result.reason)
        assertFalse(result.remoteMayExist)
        assertFalse(result.toString().contains("secret"))
    }

    @Test fun destinationFilenameReservedCharactersRemainOneLiteralPathSegment() {
        val name = "take:100%?#.mp4"
        assertEquals("https://dav.example/collection/take%3A100%25%3F%23.mp4", destination.clipUri(name).toASCIIString())
        Server().use { server ->
            val source = BytesSource(byteArrayOf(8)).apply { state = state.copy(displayName = name) }
            assertEquals(WebDavUploadResult.Uploaded(1, name), server.transport().upload(source, destination, WebDavUploadControl(enabled)))
            assertEquals("PUT /collection/take%3A100%25%3F%23.mp4 HTTP/1.1", server.await().requestLine)
        }
        assertThrows(IllegalArgumentException::class.java) { destination.clipUri("invalid\uD800.mp4") }
    }

    @Test fun disconnectFailureDoesNotMaskResultOrStrandAdmission() {
        val control = WebDavUploadControl(enabled)
        val socket = MemoryConnection(disconnectFailure = IllegalStateException("secret cleanup failure"))
        val transport = WebDavUploadTransport({ socket })
        repeat(2) {
            val result = transport.upload(BytesSource(byteArrayOf(1)), destination, control)
            assertEquals(WebDavUploadResult.Uploaded(1, "take.mp4"), result)
            assertFalse(result.toString().contains("secret"))
        }
        val admission = control.enter()
        val attempt = requireNotNull(admission.attempt)
        attempt.bind(socket)
        control.cancel()
        assertEquals(WebDavStopReason.CANCELLED, attempt.reason)
        control.leave(attempt)
    }

    private class BytesSource(private val bytes: ByteArray) : WebDavClipSource {
        var state = WebDavClipSnapshot("id", "take.mp4", bytes.size.toLong(), 1, true, "video/mp4")
        var opens = 0
        override fun snapshot() = state
        override fun open(): InputStream { opens++; return ByteArrayInputStream(bytes) }
    }

    private class MemoryConnection(
        private val onWrite: () -> Unit = {},
        private val disconnectFailure: RuntimeException? = null,
    ) : HttpURLConnection(URI("https://dav.example/").toURL()) {
        val body = ByteArrayOutputStream()
        var disconnected = false
        override fun getOutputStream() = object : java.io.OutputStream() {
            override fun write(value: Int) { body.write(value); onWrite() }
            override fun write(bytes: ByteArray, offset: Int, length: Int) { body.write(bytes, offset, length); onWrite() }
        }
        override fun getResponseCode() = 201
        override fun connect() = Unit
        override fun disconnect() { disconnected = true; disconnectFailure?.let { throw it } }
        override fun usingProxy() = false
    }

    /** A real loopback HTTP peer; production still requires HTTPS and platform TLS trust. */
    private class Server(private val status: Int = 201, private val responseHeaders: String = "") : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newSingleThreadExecutor()
        private val request = executor.submit<Request> {
            server.accept().use { socket ->
                socket.soTimeout = 5000
                val input = socket.getInputStream()
                fun line(): String {
                    val bytes = ByteArrayOutputStream()
                    while (true) {
                        val value = input.read()
                        check(value >= 0) { "Unexpected request EOF" }
                        if (value == 10) break
                        check(bytes.size() < 16_384)
                        if (value != 13) bytes.write(value)
                    }
                    return bytes.toString(Charsets.US_ASCII.name())
                }
                val requestLine = line()
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val header = line()
                    if (header.isEmpty()) break
                    val split = header.indexOf(':')
                    check(split > 0)
                    headers[header.substring(0, split).lowercase()] = header.substring(split + 1).trim()
                }
                val body = ByteArray(requireNotNull(headers["content-length"]).toInt())
                var offset = 0
                while (offset < body.size) {
                    val read = input.read(body, offset, body.size - offset)
                    check(read > 0)
                    offset += read
                }
                socket.getOutputStream().write("HTTP/1.1 $status Result\r\n${responseHeaders}Content-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                Request(requestLine, headers, body)
            }
        }
        fun transport() = WebDavUploadTransport({ uri ->
            URI("http://127.0.0.1:${server.localPort}${uri.rawPath}").toURL().openConnection() as HttpURLConnection
        }, connectTimeoutMs = 5000, readTimeoutMs = 5000)
        fun await(): Request = request.get(10, TimeUnit.SECONDS)
        override fun close() { server.close(); executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)) }
        data class Request(val requestLine: String, val headers: Map<String, String>, val body: ByteArray)
    }
}
