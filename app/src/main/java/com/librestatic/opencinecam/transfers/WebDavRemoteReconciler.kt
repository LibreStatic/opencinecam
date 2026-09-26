/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.transfers

import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * One bounded GET under a borrowed owner; never retries, follows redirects, enters or leaves.
 * A complete body is read-back evidence, not proof that local bytes still match: the coordinator
 * must validate its fresh local hash before applying reconciliation with the exact durable lease.
 *
 * The absolute monotonic deadline bounds acceptance of evidence. Each network timeout is capped
 * to remaining time, but blocking platform calls/cleanup are not instantly preempted on expiry.
 * No watchdog, canceled Future or timeout completes the retirement receipt; real cleanup must end.
 */
internal class WebDavRemoteReconciler(
    private val connectionFactory: (URI) -> HttpURLConnection = { uri ->
        require(uri.scheme.equals("https", ignoreCase = true))
        uri.toURL().openConnection() as HttpURLConnection
    },
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
    private val totalDeadlineMs: Long = 120_000,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    init {
        require(connectTimeoutMs in 1..120_000 && readTimeoutMs in 1..120_000)
        require(totalDeadlineMs in 1..600_000)
    }

    /** Injected connection factories are internal test/selected-Network seams, not HTTP settings. */
    fun inspect(destination: WebDavDestination, remoteName: String, expectedBytes: Long,
        scope: WebDavOperationScope): WebDavRemoteObservation {
        require(expectedBytes > 0)
        val started = nanoTime()
        val budget = TimeUnit.MILLISECONDS.toNanos(totalDeadlineMs)
        fun remainingMillis(): Int {
            scope.checkRunning()
            val elapsed = nanoTime() - started
            check(elapsed >= 0 && elapsed < budget) { "Reconciliation deadline elapsed" }
            return ((budget - elapsed + 999_999L) / 1_000_000L).toInt().coerceAtLeast(1)
        }
        var socket: HttpURLConnection? = null
        val observation = try {
            remainingMillis()
            val uri = destination.clipUri(remoteName)
            socket = connectionFactory(uri)
            val connection = requireNotNull(socket)
            WebDavLanTls.apply(connection, destination)
            scope.bind(connection)
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.requestMethod = "GET"
            connection.doOutput = false
            connection.connectTimeout = minOf(connectTimeoutMs, remainingMillis())
            connection.readTimeout = minOf(readTimeoutMs, remainingMillis())
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("Cache-Control", "no-cache")
            connection.setRequestProperty("Connection", "close")
            destination.credentials?.let { connection.setRequestProperty("Authorization", it.authorization()) }
            remainingMillis()
            val status = connection.responseCode
            remainingMillis()
            when (status) {
                404 -> WebDavRemoteObservation.NotFound404
                401, 403 -> WebDavRemoteObservation.AuthenticationRejected
                200 -> {
                    val headers = connection.headerFields
                    fun header(name: String): String? {
                        val values = headers.entries.filter { it.key?.equals(name, ignoreCase = true) == true }.flatMap { it.value }
                        require(values.size <= 1)
                        return values.singleOrNull()
                    }
                    val encoding = header("Content-Encoding")
                    require(encoding == null || encoding.equals("identity", ignoreCase = true))
                    require(header("Content-Range") == null)
                    val transfer = header("Transfer-Encoding")
                    require(transfer == null || transfer.equals("chunked", ignoreCase = true))
                    val length = header("Content-Length")?.let { value ->
                        require(value.length in 1..19 && value.all { it in '0'..'9' })
                        requireNotNull(value.toLongOrNull()).also { require(it >= 0 && it.toString() == value) }
                    }
                    require(transfer == null || length == null)
                    require(length == null || length <= expectedBytes)
                    connection.readTimeout = minOf(readTimeoutMs, remainingMillis())
                    val digest = MessageDigest.getInstance("SHA-256")
                    var received = 0L
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            connection.readTimeout = minOf(readTimeoutMs, remainingMillis())
                            // Never compute expectedBytes + 1: Long.MAX_VALUE is a valid boundary.
                            val room = expectedBytes - received
                            val count = input.read(buffer, 0, if (room == 0L) 1 else minOf(room, buffer.size.toLong()).toInt())
                            remainingMillis()
                            if (count == -1) break
                            require(count > 0 && count <= room)
                            received = Math.addExact(received, count.toLong())
                            digest.update(buffer, 0, count)
                        }
                        require(length == null || received == length)
                    }
                    remainingMillis() // Closing the body can itself block past the evidence deadline.
                    WebDavRemoteObservation.CompleteBody(received, digest.digest().joinToString("") { "%02x".format(it) })
                }
                else -> WebDavRemoteObservation.Inconclusive
            }
        } catch (_: Exception) {
            // Includes cancellation, malformed framing, truncated transport and deadline expiry.
            // The scope retains its stop reason; the worker must not manufacture fresh evidence.
            WebDavRemoteObservation.Inconclusive
        } finally {
            scope.unbind()
            socket?.disconnectQuietly()
        }
        return try {
            remainingMillis()
            observation
        } catch (_: Exception) { WebDavRemoteObservation.Inconclusive }
    }
}
