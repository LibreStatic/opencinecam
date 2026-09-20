/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.transfers

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
import java.util.Base64
import java.security.MessageDigest

/** The caller owns finalization. Both snapshots must describe the same published, immutable clip. */
interface WebDavClipSource {
    fun snapshot(): WebDavClipSnapshot
    fun open(): InputStream
}

data class WebDavClipSnapshot(
    val identity: String,
    val displayName: String,
    val sizeBytes: Long,
    val modifiedSeconds: Long,
    val finalized: Boolean,
    val mimeType: String = "application/octet-stream",
)

/** Kept in memory only; deliberately not a data class and never included in results or errors. */
class WebDavCredentials(private val username: String, private val password: String) {
    init {
        require(username.isNotEmpty() && ':' !in username)
        require(username.length + password.length <= 8192)
        require((username + password).none { it == '\r' || it == '\n' })
    }
    internal fun authorization(): String = "Basic " + Base64.getEncoder()
        .encodeToString("$username:$password".toByteArray(Charsets.UTF_8))
    override fun toString(): String = "WebDavCredentials(redacted)"
}

class WebDavDestination(
    val collection: URI,
    internal val credentials: WebDavCredentials? = null,
    internal val ignoreTlsErrors: Boolean = false,
) {
    init {
        require(collection.scheme.equals("https", ignoreCase = true)) { "HTTPS is required" }
        require(!collection.host.isNullOrBlank() && collection.rawUserInfo == null)
        require(collection.rawQuery == null && collection.rawFragment == null)
        require(collection.port == -1 || collection.port in 1..65535)
        require(collection.rawPath.endsWith('/') && collection.normalize() == collection)
        require(collection.path.split('/').none { it == "." || it == ".." })
        require(!ignoreTlsErrors || WebDavLanTls.isLocalAddress(collection)) { "TLS bypass requires a literal local IP address" }
    }

    internal fun clipUri(name: String): URI {
        require(name.isNotBlank() && name != "." && name != "..")
        require(name.toByteArray(Charsets.UTF_8).size <= 255)
        require(name.none { it == '/' || it == '\\' || it.code < 32 || it.code == 127 })
        require(Charsets.UTF_8.newEncoder().canEncode(name))
        // Encode one path segment, including ':' and '%'; never parse a filename as a URI.
        val hex = "0123456789ABCDEF"
        val encoded = buildString {
            for (byte in name.toByteArray(Charsets.UTF_8)) {
                val value = byte.toInt() and 0xff
                if (value in 65..90 || value in 97..122 || value in 48..57 || value.toChar() in "-._~") {
                    append(value.toChar())
                } else {
                    append('%').append(hex[value ushr 4]).append(hex[value and 15])
                }
            }
        }
        return try {
            URI(collection.toASCIIString() + encoded)
        } catch (_: URISyntaxException) {
            throw IllegalArgumentException("Invalid destination filename")
        }
    }

    override fun toString(): String = "WebDavDestination(redacted)"
}

enum class WebDavFailure { SOURCE_NOT_FINALIZED, SOURCE_INVALID, SOURCE_CHANGED, SOURCE_IO, NETWORK_IO, HTTP_REJECTED, REDIRECT_REJECTED }

sealed interface WebDavUploadResult {
    /** Server acknowledged the complete conditional PUT; this is not a checksum/read-back claim. */
    data class Uploaded(val bytes: Long, val remoteName: String) : WebDavUploadResult
    data class Stopped(
        val reason: WebDavStopReason,
        val bytesSent: Long = 0,
        val remoteMayExist: Boolean = false,
    ) : WebDavUploadResult
    data class Failed(
        val reason: WebDavFailure,
        val httpStatus: Int? = null,
        val bytesSent: Long = 0,
        val remoteMayExist: Boolean = false,
    ) : WebDavUploadResult
}

/**
 * Blocking, bounded-memory WebDAV PUT for a finalized clip. Run on a dedicated worker.
 * HTTPS uses platform trust/hostname verification unless the original LAN profile explicitly opts
 * into per-connection certificate bypass. No redirect, credential challenge retry,
 * overwrite, background scheduling, automatic retry or remote deletion occurs here.
 *
 * An interrupted PUT can have reached the server even without its acknowledgement. The caller
 * must reconcile remoteMayExist before retrying; a 412 does not prove that the bytes are ours.
 * Atomic temporary-resource MOVE and resumable uploads require a separate qualified transport.
 */
class WebDavUploadTransport internal constructor(
    private val connectionFactory: (URI) -> HttpURLConnection,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) {
    constructor() : this({ it.toURL().openConnection() as HttpURLConnection })

    init {
        require(connectTimeoutMs in 1..120_000 && readTimeoutMs in 1..120_000)
    }

    fun upload(
        source: WebDavClipSource,
        destination: WebDavDestination,
        control: WebDavUploadControl,
    ): WebDavUploadResult {
        val admission = control.enter()
        val attempt = admission.attempt ?: return WebDavUploadResult.Stopped(requireNotNull(admission.reason))
        return try {
            upload(source, destination, WebDavOperationScope(attempt))
        } finally {
            control.leave(attempt)
        }
    }

    /** Borrowed owner spans hashing, request and durable outcome; this overload never enters/leaves. */
    internal fun upload(
        source: WebDavClipSource,
        destination: WebDavDestination,
        scope: WebDavOperationScope,
        expectedSha256: String? = null,
        remoteName: String? = null,
    ): WebDavUploadResult {
        require(expectedSha256 == null || expectedSha256.matches(Regex("[0-9a-f]{64}")))
        val digest = expectedSha256?.let { MessageDigest.getInstance("SHA-256") }
        var connection: HttpURLConnection? = null
        var bytesSent = 0L
        var requestStarted = false
        var readingSource = true
        try {
            scope.checkRunning()
            val snapshot = source.snapshot()
            if (!snapshot.finalized) return WebDavUploadResult.Failed(WebDavFailure.SOURCE_NOT_FINALIZED)
            if (snapshot.sizeBytes <= 0 || snapshot.identity.isBlank() ||
                !snapshot.mimeType.matches(Regex("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+"))) {
                return WebDavUploadResult.Failed(WebDavFailure.SOURCE_INVALID)
            }
            val effectiveName = remoteName ?: snapshot.displayName
            val uri = try {
                val localNameUri = destination.clipUri(snapshot.displayName)
                if (remoteName == null) localNameUri else destination.clipUri(remoteName)
            } catch (_: IllegalArgumentException) {
                return WebDavUploadResult.Failed(WebDavFailure.SOURCE_INVALID)
            }
            source.open().use { input ->
                scope.checkRunning()
                readingSource = false
                connection = connectionFactory(uri)
                val socket = requireNotNull(connection).apply {
                    WebDavLanTls.apply(this, destination)
                    instanceFollowRedirects = false
                    useCaches = false
                    connectTimeout = connectTimeoutMs
                    readTimeout = readTimeoutMs
                    requestMethod = "PUT"
                    doOutput = true
                    setFixedLengthStreamingMode(snapshot.sizeBytes)
                    setRequestProperty("Content-Type", snapshot.mimeType)
                    setRequestProperty("If-None-Match", "*")
                    setRequestProperty("Connection", "close")
                    destination.credentials?.let { setRequestProperty("Authorization", it.authorization()) }
                }
                scope.bind(socket)
                scope.checkRunning()
                requestStarted = true
                socket.outputStream.use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (bytesSent < snapshot.sizeBytes) {
                        scope.checkRunning()
                        readingSource = true
                        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), snapshot.sizeBytes - bytesSent).toInt())
                        if (count <= 0) throw ChangedSource()
                        scope.checkRunning()
                        readingSource = false
                        output.write(buffer, 0, count)
                        digest?.update(buffer, 0, count)
                        bytesSent += count
                    }
                    scope.checkRunning()
                    readingSource = true
                    if (input.read() != -1 || source.snapshot() != snapshot) throw ChangedSource()
                    if (digest != null && digest.digest().joinToString("") { "%02x".format(it) } != expectedSha256) throw ChangedSource()
                    readingSource = false
                }
                scope.checkRunning()
                val status = socket.responseCode
                scope.checkRunning()
                return when (status) {
                    200, 201, 204 -> WebDavUploadResult.Uploaded(bytesSent, effectiveName)
                    in 300..399 -> WebDavUploadResult.Failed(WebDavFailure.REDIRECT_REJECTED, status, bytesSent, true)
                    else -> WebDavUploadResult.Failed(WebDavFailure.HTTP_REJECTED, status, bytesSent, true)
                }
            }
        } catch (_: WebDavUploadControl.UploadStopped) {
            return WebDavUploadResult.Stopped(requireNotNull(scope.stopReason), bytesSent, requestStarted)
        } catch (_: ChangedSource) {
            return WebDavUploadResult.Failed(WebDavFailure.SOURCE_CHANGED, bytesSent = bytesSent, remoteMayExist = requestStarted)
        } catch (_: IOException) {
            scope.stopReason?.let { return WebDavUploadResult.Stopped(it, bytesSent, requestStarted) }
            return WebDavUploadResult.Failed(
                if (readingSource) WebDavFailure.SOURCE_IO else WebDavFailure.NETWORK_IO,
                bytesSent = bytesSent, remoteMayExist = requestStarted,
            )
        } catch (_: SecurityException) {
            return WebDavUploadResult.Failed(
                if (readingSource) WebDavFailure.SOURCE_IO else WebDavFailure.NETWORK_IO,
                bytesSent = bytesSent, remoteMayExist = requestStarted,
            )
        } finally {
            scope.unbind()
            connection?.disconnectQuietly()
        }
    }

    private class ChangedSource : IOException()
}
