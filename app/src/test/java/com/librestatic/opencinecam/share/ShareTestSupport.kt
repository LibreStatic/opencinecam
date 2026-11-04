/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.share

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.Socket
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** The CinemaDNG golden fixture of `:media` (built by `tools/regen-cinemadng-golden.sh`). */
internal object ShareFixture {
    const val TAKE = "A001_C001"
    val dir = File("../media/src/test/resources/ocraw/cinemadng")
    val ocraw = File(dir, "$TAKE.ocraw")
    val golden = File(dir, TAKE)
    const val MODIFIED_MS = 1_793_620_800_000L // 2026-11-02T12:00:00Z

    /** Counts descriptors opened and still open, to prove every read closes its own. */
    val opened = AtomicInteger()
    val open = AtomicInteger()

    fun take(file: File = ocraw, name: String = file.name, key: String = file.path, modifiedMs: Long = MODIFIED_MS): ShareTake =
        ShareTake(key, name, file.length(), modifiedMs) {
            val raf = RandomAccessFile(file, "r")
            opened.incrementAndGet()
            open.incrementAndGet()
            object : ShareOpenFile {
                override val channel: FileChannel = raf.channel
                override fun close() { open.decrementAndGet(); raf.close() }
            }
        }
}

internal class ManualClock(start: Long = 1_000_000) : ShareClock {
    private val now = AtomicLong(start)
    override fun nowMs(): Long = now.get()
    fun advance(ms: Long) { now.addAndGet(ms) }
}

/** A raw HTTP/1.1 response as read off the socket. */
internal class RawResponse(val status: Int, val headers: Map<String, List<String>>, val body: ByteArray) {
    fun header(name: String): String? = headers[name.lowercase()]?.firstOrNull()
    val text: String get() = String(body, Charsets.UTF_8)
}

/**
 * A minimal HTTP/1.1 client over one keep-alive socket, with its own RFC 7616 Digest
 * implementation (MD5, qop=auth), so the server is checked against a second, independent
 * reading of the protocol.
 */
internal class RawHttpClient(host: InetAddress, port: Int, private val user: String? = null, private val password: String? = null) : AutoCloseable {
    private val socket = Socket(host, port).apply { soTimeout = 10_000 }
    private val input = socket.getInputStream().buffered()
    private val output = socket.getOutputStream()
    private val hostHeader = "${host.hostAddress}:$port"
    private var challenge: Map<String, String>? = null
    private var nc = 0

    /** Sends [method] [target]; on a 401 with credentials set, answers the challenge once. */
    fun request(method: String, target: String, headers: Map<String, String> = emptyMap(), body: ByteArray? = null, head: Boolean = method == "HEAD"): RawResponse {
        val first = send(method, target, headers, body, head)
        if (first.status != 401 || user == null) return first
        challenge = DigestAuthenticator.parseAuthParams(requireNotNull(first.header("www-authenticate")).substringAfter(' '))
        nc = 0
        return send(method, target, headers, body, head)
    }

    fun send(method: String, target: String, headers: Map<String, String> = emptyMap(), body: ByteArray? = null, head: Boolean = method == "HEAD", authorization: String? = digest(method, target)): RawResponse {
        val text = StringBuilder("$method $target HTTP/1.1\r\nHost: $hostHeader\r\n")
        authorization?.let { text.append("Authorization: ").append(it).append("\r\n") }
        headers.forEach { (k, v) -> text.append(k).append(": ").append(v).append("\r\n") }
        if (body != null) text.append("Content-Length: ").append(body.size).append("\r\n")
        text.append("\r\n")
        output.write(text.toString().toByteArray(Charsets.US_ASCII))
        if (body != null) output.write(body)
        output.flush()
        return readResponse(input, head)
    }

    fun sendRaw(bytes: ByteArray, head: Boolean = false): RawResponse {
        output.write(bytes)
        output.flush()
        return readResponse(input, head)
    }

    /** The Authorization value for the stored challenge, or null before one. */
    fun digest(method: String, uri: String, nonceCount: Int? = null, pass: String? = password): String? {
        val c = challenge ?: return null
        val count = "%08x".format(nonceCount ?: ++nc)
        val cnonce = "c0ffee${count}"
        val ha1 = DigestAuthenticator.md5Hex("$user:${c["realm"]}:$pass")
        val ha2 = DigestAuthenticator.md5Hex("$method:$uri")
        val response = DigestAuthenticator.md5Hex("$ha1:${c["nonce"]}:$count:$cnonce:auth:$ha2")
        return "Digest username=\"$user\", realm=\"${c["realm"]}\", nonce=\"${c["nonce"]}\", uri=\"$uri\", algorithm=MD5, " +
            "response=\"$response\", opaque=\"${c["opaque"]}\", qop=auth, nc=$count, cnonce=\"$cnonce\""
    }

    val closedByServer: Boolean get() = try { input.read() < 0 } catch (_: java.io.IOException) { true }

    override fun close() = socket.close()

    companion object {
        fun readResponse(input: InputStream, head: Boolean): RawResponse {
            fun line(): String {
                val out = ByteArrayOutputStream()
                while (true) {
                    val b = input.read()
                    require(b >= 0) { "connection closed mid-response" }
                    if (b == '\n'.code) break
                    out.write(b)
                }
                return out.toString(Charsets.US_ASCII.name()).trimEnd('\r')
            }
            var status = line()
            while (status.startsWith("HTTP/1.1 100")) { line(); status = line() }
            val code = status.split(' ')[1].toInt()
            val headers = LinkedHashMap<String, MutableList<String>>()
            while (true) {
                val h = line()
                if (h.isEmpty()) break
                headers.getOrPut(h.substringBefore(':').lowercase()) { mutableListOf() } += h.substringAfter(':').trim()
            }
            val length = headers["content-length"]?.first()?.toLong() ?: 0
            val body = if (head) ByteArray(0) else ByteArray(length.toInt()).also { buffer ->
                var read = 0
                while (read < buffer.size) {
                    val n = input.read(buffer, read, buffer.size - read)
                    require(n >= 0) { "short body" }
                    read += n
                }
            }
            return RawResponse(code, headers, body)
        }
    }
}
