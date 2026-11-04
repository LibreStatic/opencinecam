/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.share

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.text.Normalizer

/**
 * A request path split into decoded segments, for lookup by exact name in the virtual tree.
 * Nothing here ever becomes a filesystem path.
 */
internal data class SharePath(val segments: List<String>, val trailingSlash: Boolean)

/**
 * Parses a request-target into [SharePath] segments, or throws [HttpParseException]:
 *
 * - origin form (`/a/b`) or absolute form (`http://host/a/b`); a query is ignored, `*` and
 *   anything else is 400;
 * - at most [MAX_PATH_BYTES] raw bytes (414) and [MAX_SEGMENTS] segments (404: deeper than the tree);
 * - percent-escapes must be well formed and decode to valid UTF-8; an escaped `/` or `\`, any
 *   control character (NUL included), a backslash, `.` and `..` segments and empty inner segments
 *   (`//`) are 400;
 * - segments are NFC-normalised, so a client sending decomposed accents still finds a name.
 */
internal fun parseSharePath(target: String): SharePath {
    if (target.length > MAX_PATH_BYTES) throw HttpParseException(414, "path too long")
    var path = target
    if (path.startsWith("http://", ignoreCase = true) || path.startsWith("https://", ignoreCase = true)) {
        val afterScheme = path.indexOf("//") + 2
        val slash = path.indexOf('/', afterScheme)
        path = if (slash < 0) "/" else path.substring(slash)
    }
    if (!path.startsWith("/")) throw HttpParseException(400, "not an origin-form path")
    path = path.substringBefore('?').substringBefore('#')
    if (path == "/") return SharePath(emptyList(), trailingSlash = true)
    val trailingSlash = path.endsWith('/')
    val raw = path.substring(1, if (trailingSlash) path.length - 1 else path.length).split('/')
    val segments = raw.map { segment ->
        if (segment.isEmpty()) throw HttpParseException(400, "empty segment")
        val decoded = percentDecode(segment)
        if (decoded == "." || decoded == "..") throw HttpParseException(400, "dot segment")
        if (decoded.any { it == '/' || it == '\\' || it.code < 0x20 || it.code == 0x7f }) throw HttpParseException(400, "forbidden character")
        Normalizer.normalize(decoded, Normalizer.Form.NFC)
    }
    if (segments.size > MAX_SEGMENTS) throw HttpParseException(404, "deeper than the share")
    return SharePath(segments, trailingSlash)
}

private fun percentDecode(segment: String): String {
    if ('%' !in segment) {
        if (segment.any { it.code > 0x7e }) throw HttpParseException(400, "unescaped non-ASCII")
        return segment
    }
    val bytes = ByteArrayOutputStream(segment.length)
    var i = 0
    while (i < segment.length) {
        val c = segment[i]
        if (c == '%') {
            if (i + 2 >= segment.length) throw HttpParseException(400, "truncated escape")
            val hi = Character.digit(segment[i + 1], 16)
            val lo = Character.digit(segment[i + 2], 16)
            if (hi < 0 || lo < 0) throw HttpParseException(400, "bad escape")
            bytes.write(hi shl 4 or lo)
            i += 3
        } else {
            if (c.code > 0x7e) throw HttpParseException(400, "unescaped non-ASCII")
            bytes.write(c.code)
            i++
        }
    }
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return try {
        decoder.decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
    } catch (_: CharacterCodingException) {
        throw HttpParseException(400, "invalid UTF-8")
    }
}

/** Percent-encodes one name for an href: unreserved ASCII stays, every other UTF-8 byte is escaped. */
internal fun percentEncodeSegment(name: String): String {
    val out = StringBuilder(name.length + 8)
    for (byte in name.toByteArray(Charsets.UTF_8)) {
        val b = byte.toInt() and 0xff
        val c = b.toChar()
        if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in "-._~") out.append(c)
        else out.append('%').append(HEX[b shr 4]).append(HEX[b and 0xf])
    }
    return out.toString()
}

private const val HEX = "0123456789ABCDEF"

/** Raw request-target bytes accepted before parsing. */
internal const val MAX_PATH_BYTES = 2048

/** The tree is `/`, `/<take>/` and `/<take>/<file>`. */
internal const val MAX_SEGMENTS = 2
