/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.share

import java.io.IOException
import java.io.InputStream
import java.util.Locale

/**
 * One parsed HTTP/1.x request head (OCC-PLAN-071 §8, route 3). Header names are lower-cased;
 * repeated headers keep every value in order. [target] is the raw request-target, never decoded.
 */
internal class HttpRequest(
    val method: String,
    val target: String,
    /** 0 for HTTP/1.0, 1 for HTTP/1.1. */
    val minorVersion: Int,
    val headers: List<Pair<String, String>>,
) {
    fun header(name: String): String? = headers.firstOrNull { it.first == name }?.second
    fun headerValues(name: String): List<String> = headers.filter { it.first == name }.map { it.second }

    /** Whether the client asked to keep the connection; HTTP/1.0 needs an explicit keep-alive. */
    val keepAlive: Boolean get() {
        val tokens = headerValues("connection").flatMap { it.split(',') }.map { it.trim().lowercase(Locale.ROOT) }
        return if (minorVersion >= 1) "close" !in tokens else "keep-alive" in tokens
    }
}

/** A request that cannot be served; [status] is the response to send before closing. */
internal class HttpParseException(val status: Int, message: String) : IOException(message)

/** The connection ended cleanly before a new request began. */
internal class HttpConnectionClosed : IOException("connection closed")

/**
 * Reads request heads with hard limits: the request line up to [maxRequestLine] bytes (414
 * beyond), the header section up to [maxHeaderBytes] bytes and [maxHeaderLines] lines (431
 * beyond). Bytes outside visible ASCII and tab are refused (400), as are obsolete line folding,
 * malformed header names, conflicting framing headers and versions other than HTTP/1.0 and 1.1.
 * [deadline] is checked between bytes so a slow sender cannot hold the worker past it.
 */
internal class HttpRequestReader(
    private val input: InputStream,
    private val maxRequestLine: Int = MAX_REQUEST_LINE,
    private val maxHeaderBytes: Int = MAX_HEADER_BYTES,
    private val maxHeaderLines: Int = MAX_HEADER_LINES,
) {
    private val line = ByteArray(maxOf(maxRequestLine, maxHeaderBytes) + 2)

    /**
     * Reads the next request head. Throws [HttpConnectionClosed] when the stream ends before the
     * first byte, [HttpParseException] for a request to refuse, [IOException] (a socket timeout)
     * when [deadline] passes or the peer stalls.
     */
    fun read(deadline: () -> Unit = {}): HttpRequest {
        var requestLine: String
        var blank = 0
        while (true) {
            requestLine = readLine(maxRequestLine, 414, first = blank == 0, deadline = deadline)
            if (requestLine.isNotEmpty()) break
            // RFC 9112 §2.2: ignore at least one empty line before the request line.
            if (++blank > 2) throw HttpParseException(400, "empty request line")
        }
        val parts = requestLine.split(' ')
        if (parts.size != 3) throw HttpParseException(400, "malformed request line")
        val (method, target, version) = parts
        if (method.isEmpty() || !method.all(::isTokenChar)) throw HttpParseException(400, "malformed method")
        if (target.isEmpty()) throw HttpParseException(400, "empty target")
        val minor = when (version) {
            "HTTP/1.1" -> 1
            "HTTP/1.0" -> 0
            else -> if (version.startsWith("HTTP/")) throw HttpParseException(505, "unsupported version")
                else throw HttpParseException(400, "malformed version")
        }
        val headers = mutableListOf<Pair<String, String>>()
        var used = 0
        while (true) {
            val budget = maxHeaderBytes - used
            if (budget <= 0) throw HttpParseException(431, "header section too large")
            val text = readLine(budget, 431, first = false, deadline = deadline)
            used += text.length + 2
            if (text.isEmpty()) break
            if (headers.size >= maxHeaderLines) throw HttpParseException(431, "too many header lines")
            if (text[0] == ' ' || text[0] == '\t') throw HttpParseException(400, "obsolete line folding")
            val colon = text.indexOf(':')
            if (colon <= 0) throw HttpParseException(400, "malformed header")
            val name = text.substring(0, colon)
            if (!name.all(::isTokenChar)) throw HttpParseException(400, "malformed header name")
            headers += name.lowercase(Locale.ROOT) to text.substring(colon + 1).trim(' ', '\t')
        }
        val request = HttpRequest(method, target, minor, headers)
        checkFraming(request)
        return request
    }

    private fun checkFraming(request: HttpRequest) {
        val lengths = request.headerValues("content-length").flatMap { it.split(',') }.map { it.trim() }
        if (lengths.any { it.isEmpty() || it.length > 18 || !it.all(Char::isDigit) }) throw HttpParseException(400, "bad content-length")
        if (lengths.toSet().size > 1) throw HttpParseException(400, "conflicting content-length")
        val encodings = request.headerValues("transfer-encoding")
        // Request smuggling: never accept both framings.
        if (encodings.isNotEmpty() && lengths.isNotEmpty()) throw HttpParseException(400, "content-length with transfer-encoding")
        if (encodings.isNotEmpty()) {
            val tokens = encodings.flatMap { it.split(',') }.map { it.trim().lowercase(Locale.ROOT) }
            if (tokens != listOf("chunked")) throw HttpParseException(501, "unsupported transfer-encoding")
            if (request.minorVersion == 0) throw HttpParseException(400, "chunked in HTTP/1.0")
        }
        val hosts = request.headerValues("host")
        if (hosts.size > 1 || request.minorVersion >= 1 && hosts.isEmpty()) throw HttpParseException(400, "bad host")
    }

    /** One line without its terminator (CRLF, or a bare LF), at most [limit] bytes. */
    private fun readLine(limit: Int, overflowStatus: Int, first: Boolean, deadline: () -> Unit): String {
        var size = 0
        while (true) {
            deadline()
            val b = input.read()
            if (b < 0) {
                if (first && size == 0) throw HttpConnectionClosed()
                throw HttpParseException(400, "truncated request")
            }
            if (b == '\n'.code) break
            if (size >= limit + 1) throw HttpParseException(overflowStatus, "line too long")
            line[size++] = b.toByte()
        }
        if (size > 0 && line[size - 1] == '\r'.code.toByte()) size--
        if (size > limit) throw HttpParseException(overflowStatus, "line too long")
        for (i in 0 until size) {
            val c = line[i].toInt() and 0xff
            // Visible ASCII, space and tab only: no NUL, no bare CR, no other controls, no 8-bit.
            if (c != '\t'.code && (c < 0x20 || c > 0x7e)) throw HttpParseException(400, "invalid byte in request head")
        }
        return String(line, 0, size, Charsets.US_ASCII)
    }

    companion object {
        const val MAX_REQUEST_LINE = 8 * 1024
        const val MAX_HEADER_BYTES = 16 * 1024
        const val MAX_HEADER_LINES = 100

        /** RFC 9110 tchar. */
        fun isTokenChar(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "!#$%&'*+-.^_`|~"
    }
}

/**
 * Reads a request body of at most [limit] bytes, by Content-Length or chunked framing, and
 * returns its length. Bodies are discarded: only a PROPFIND may carry one, and it is served as
 * `allprop`. Throws [HttpParseException] 413 when the body is larger.
 */
internal fun drainRequestBody(request: HttpRequest, input: InputStream, limit: Int, deadline: () -> Unit = {}): Long {
    val length = request.header("content-length")?.substringBefore(',')?.trim()?.toLong()
    val buffer = ByteArray(4096)
    if (length != null) {
        if (length > limit) throw HttpParseException(413, "body too large")
        var left = length
        while (left > 0) {
            deadline()
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (n < 0) throw HttpParseException(400, "truncated body")
            left -= n
        }
        return length
    }
    if (request.header("transfer-encoding") == null) return 0
    var total = 0L
    while (true) {
        val sizeLine = readAsciiLine(input, 1024, deadline)
        val hex = sizeLine.substringBefore(';').trim()
        if (hex.isEmpty() || hex.length > 8 || !hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) throw HttpParseException(400, "bad chunk size")
        val size = hex.toLong(16)
        if (size == 0L) break
        total += size
        if (total > limit) throw HttpParseException(413, "body too large")
        var left = size
        while (left > 0) {
            deadline()
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (n < 0) throw HttpParseException(400, "truncated body")
            left -= n
        }
        if (readAsciiLine(input, 2, deadline).isNotEmpty()) throw HttpParseException(400, "bad chunk end")
    }
    // Trailer fields, bounded like the body.
    var trailer = 0
    while (true) {
        val text = readAsciiLine(input, 1024, deadline)
        if (text.isEmpty()) break
        if (++trailer > 16) throw HttpParseException(431, "too many trailers")
    }
    return total
}

private fun readAsciiLine(input: InputStream, limit: Int, deadline: () -> Unit): String {
    val out = StringBuilder()
    while (true) {
        deadline()
        val b = input.read()
        if (b < 0) throw HttpParseException(400, "truncated body")
        if (b == '\n'.code) break
        if (out.length > limit) throw HttpParseException(400, "line too long")
        out.append(b.toChar())
    }
    if (out.endsWith('\r')) out.setLength(out.length - 1)
    if (out.any { it.code < 0x20 && it != '\t' || it.code > 0x7e }) throw HttpParseException(400, "invalid byte")
    return out.toString()
}
