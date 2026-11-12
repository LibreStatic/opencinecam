/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.share

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** How a GET/HEAD answers its `Range` header. */
internal sealed interface RangeDecision {
    /** 200 with the whole file: no Range, an unknown unit, a malformed or a multi-range header. */
    data object Full : RangeDecision

    /** 206 with bytes `[start, endInclusive]`. */
    data class Partial(val start: Long, val endInclusive: Long) : RangeDecision {
        val length: Long get() = endInclusive - start + 1
    }

    /** 416 with `Content-Range: bytes * /size`. */
    data object Unsatisfiable : RangeDecision
}

/**
 * RFC 9110 §14 for one file of [size] bytes. Single ranges only: `a-b` (an end past the file
 * is clamped), `a-` and the suffix `-n` (the last n bytes, the whole file when n exceeds it).
 * A first byte at or past the end, a zero suffix or any range of an empty file is
 * unsatisfiable. A header the server may ignore (another unit, bad syntax, `b < a`) and a
 * multi-range request are served whole with 200, so no multipart response is ever built.
 */
internal fun decideRange(header: String?, size: Long): RangeDecision {
    if (header == null) return RangeDecision.Full
    val equals = header.indexOf('=')
    if (equals < 0 || !header.substring(0, equals).trim().equals("bytes", ignoreCase = true)) return RangeDecision.Full
    val spec = header.substring(equals + 1).trim()
    if (',' in spec) return RangeDecision.Full
    val dash = spec.indexOf('-')
    if (dash < 0) return RangeDecision.Full
    val first = spec.substring(0, dash).trim()
    val last = spec.substring(dash + 1).trim()
    fun number(text: String): Long? = text.takeIf { it.isNotEmpty() && it.length <= 18 && it.all(Char::isDigit) }?.toLong()
    if (first.isEmpty()) {
        val suffix = number(last) ?: return RangeDecision.Full
        if (suffix == 0L || size == 0L) return RangeDecision.Unsatisfiable
        return RangeDecision.Partial(maxOf(0, size - suffix), size - 1)
    }
    val start = number(first) ?: return RangeDecision.Full
    val end = if (last.isEmpty()) null else number(last) ?: return RangeDecision.Full
    if (end != null && end < start) return RangeDecision.Full
    if (start >= size) return RangeDecision.Unsatisfiable
    return RangeDecision.Partial(start, minOf(end ?: (size - 1), size - 1))
}

/** `Depth` of a PROPFIND: 0, 1, or null for infinity (also when absent, RFC 4918 §9.1). */
internal fun parseDepth(header: String?): Int? = when (header?.trim()?.lowercase(Locale.ROOT)) {
    null, "infinity" -> null
    "0" -> 0
    "1" -> 1
    else -> throw HttpParseException(400, "bad depth")
}

private val HTTP_DATE = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).withZone(ZoneOffset.UTC)
private val ISO_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).withZone(ZoneOffset.UTC)

/** IMF-fixdate (RFC 9110 §5.6.7), e.g. `Mon, 02 Nov 2026 10:00:00 GMT`. */
internal fun httpDate(epochMs: Long): String = HTTP_DATE.format(Instant.ofEpochMilli(epochMs))

/** RFC 3339, for `creationdate`. */
internal fun isoDate(epochMs: Long): String = ISO_DATE.format(Instant.ofEpochMilli(epochMs))

/** Escapes text for XML and HTML content and attribute values. */
internal fun xmlEscape(text: String): String {
    val out = StringBuilder(text.length + 8)
    for (c in text) when (c) {
        '&' -> out.append("&amp;")
        '<' -> out.append("&lt;")
        '>' -> out.append("&gt;")
        '"' -> out.append("&quot;")
        '\'' -> out.append("&apos;")
        // Characters XML 1.0 cannot carry at all; the tree never offers such names.
        else -> if (c.code < 0x20 && c != '\t' && c != '\n' && c != '\r' || c == '￾' || c == '￿') out.append('�') else out.append(c)
    }
    return out.toString()
}

/** The href of [node]: the tree path with every segment percent-encoded; collections end with `/`. */
internal fun hrefOf(node: ShareNode, folder: String?): String = when (node) {
    is ShareCollection -> if (node.name.isEmpty()) "/" else "/" + percentEncodeSegment(node.name) + "/"
    is ShareFile -> "/" + percentEncodeSegment(requireNotNull(folder)) + "/" + percentEncodeSegment(node.name)
}

/**
 * The `207 Multi-Status` body for [nodes] (RFC 4918 §9.1), one `response` per node with the
 * properties every client here asks for: displayname, resourcetype, getlastmodified,
 * creationdate and, for files, getcontentlength, getcontenttype and getetag. The request body
 * is not consulted: every PROPFIND is answered as `allprop`. Returned as parts so the server
 * can count the bytes for Content-Length and then stream them.
 */
internal fun multistatusParts(nodes: List<Pair<ShareNode, String?>>): Sequence<String> = sequence {
    yield("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<D:multistatus xmlns:D=\"DAV:\">\n")
    for ((node, folder) in nodes) yield(responseXml(node, folder))
    yield("</D:multistatus>\n")
}

private fun responseXml(node: ShareNode, folder: String?): String = buildString {
    append("<D:response><D:href>").append(xmlEscape(hrefOf(node, folder))).append("</D:href><D:propstat><D:prop>")
    val display = if (node is ShareCollection && node.name.isEmpty()) "OpenCineCam" else node.name
    append("<D:displayname>").append(xmlEscape(display)).append("</D:displayname>")
    when (node) {
        is ShareCollection -> append("<D:resourcetype><D:collection/></D:resourcetype>")
        is ShareFile -> {
            append("<D:resourcetype/>")
            append("<D:getcontentlength>").append(node.size).append("</D:getcontentlength>")
            append("<D:getcontenttype>").append(xmlEscape(node.contentType)).append("</D:getcontenttype>")
            append("<D:getetag>").append(xmlEscape(node.etag)).append("</D:getetag>")
        }
    }
    append("<D:getlastmodified>").append(httpDate(node.modifiedMs)).append("</D:getlastmodified>")
    append("<D:creationdate>").append(isoDate(node.modifiedMs)).append("</D:creationdate>")
    append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>\n")
}

/** A plain listing for a browser that GETs a folder; names escaped, links percent-encoded. */
internal fun collectionHtml(title: String, entries: List<Pair<ShareNode, String?>>): String = buildString {
    append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>").append(xmlEscape(title)).append("</title></head><body><ul>")
    for ((node, folder) in entries) {
        val label = if (node is ShareCollection) node.name + "/" else node.name
        append("<li><a href=\"").append(xmlEscape(hrefOf(node, folder))).append("\">").append(xmlEscape(label)).append("</a></li>")
    }
    append("</ul></body></html>\n")
}

internal fun reasonPhrase(status: Int): String = when (status) {
    100 -> "Continue"
    200 -> "OK"
    206 -> "Partial Content"
    207 -> "Multi-Status"
    400 -> "Bad Request"
    401 -> "Unauthorized"
    403 -> "Forbidden"
    404 -> "Not Found"
    405 -> "Method Not Allowed"
    408 -> "Request Timeout"
    413 -> "Content Too Large"
    414 -> "URI Too Long"
    416 -> "Range Not Satisfiable"
    429 -> "Too Many Requests"
    431 -> "Request Header Fields Too Large"
    500 -> "Internal Server Error"
    501 -> "Not Implemented"
    503 -> "Service Unavailable"
    505 -> "HTTP Version Not Supported"
    else -> "Status"
}
