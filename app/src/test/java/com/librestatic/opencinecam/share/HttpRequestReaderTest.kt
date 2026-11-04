/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.share

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HttpRequestReaderTest {
    private fun read(text: String) = HttpRequestReader(ByteArrayInputStream(text.toByteArray(Charsets.ISO_8859_1))).read()

    private fun status(text: String): Int = try {
        read(text)
        fail("accepted: $text")
        0
    } catch (error: HttpParseException) {
        error.status
    }

    @Test
    fun parsesAWindowsPropfindHead() {
        val request = read("PROPFIND /A001/ HTTP/1.1\r\nConnection: Keep-Alive\r\nUser-Agent: Microsoft-WebDAV-MiniRedir/10.0.19045\r\n" +
            "Depth: 1\r\ntranslate: f\r\nContent-Length: 0\r\nHost: 192.168.1.5:8642\r\n\r\n")
        assertEquals("PROPFIND", request.method)
        assertEquals("/A001/", request.target)
        assertEquals(1, request.minorVersion)
        assertEquals("1", request.header("depth"))
        assertEquals("f", request.header("translate"))
        assertTrue(request.keepAlive)
    }

    @Test
    fun keepAliveFollowsTheVersionAndConnectionHeader() {
        assertFalse(read("GET / HTTP/1.1\r\nHost: a\r\nConnection: close\r\n\r\n").keepAlive)
        assertFalse(read("GET / HTTP/1.0\r\n\r\n").keepAlive)
        assertTrue(read("GET / HTTP/1.0\r\nConnection: keep-alive\r\n\r\n").keepAlive)
    }

    @Test
    fun toleratesBareLineFeedsAndOneLeadingEmptyLine() {
        assertEquals("/", read("\r\nGET / HTTP/1.1\nHost: a\n\n").target)
    }

    @Test
    fun requestLineLimitIs8KiB() {
        val atLimit = "GET /" + "a".repeat(HttpRequestReader.MAX_REQUEST_LINE - "GET / HTTP/1.1".length) + " HTTP/1.1"
        assertEquals(HttpRequestReader.MAX_REQUEST_LINE, atLimit.length)
        read("$atLimit\r\nHost: a\r\n\r\n")
        assertEquals(414, status("GET /" + "a".repeat(HttpRequestReader.MAX_REQUEST_LINE) + " HTTP/1.1\r\nHost: a\r\n\r\n"))
    }

    @Test
    fun headerSectionLimitsAre16KiBAnd100Lines() {
        val many = (1..HttpRequestReader.MAX_HEADER_LINES).joinToString("") { "X-$it: v\r\n" }
        read("GET / HTTP/1.1\r\nHost: a\r\n${(1 until HttpRequestReader.MAX_HEADER_LINES).joinToString("") { "X-$it: v\r\n" }}\r\n")
        assertEquals(431, status("GET / HTTP/1.1\r\nHost: a\r\n$many\r\n"))
        assertEquals(431, status("GET / HTTP/1.1\r\nHost: a\r\nX-Big: ${"b".repeat(HttpRequestReader.MAX_HEADER_BYTES)}\r\n\r\n"))
        assertEquals(431, status("GET / HTTP/1.1\r\nHost: a\r\n" + (1..20).joinToString("") { "X-$it: ${"b".repeat(1000)}\r\n" } + "\r\n"))
    }

    @Test
    fun malformedHeadsAreRefused() {
        assertEquals(400, status("GET /\r\n\r\n"))
        assertEquals(400, status("GET  / HTTP/1.1\r\nHost: a\r\n\r\n"))
        assertEquals(400, status("G(T / HTTP/1.1\r\nHost: a\r\n\r\n"))
        assertEquals(505, status("GET / HTTP/2.0\r\nHost: a\r\n\r\n"))
        assertEquals(400, status("GET / FOO/1.1\r\nHost: a\r\n\r\n"))
        assertEquals(400, status("GET / HTTP/1.1\r\nHost: a\r\nNoColon\r\n\r\n"))
        assertEquals(400, status("GET / HTTP/1.1\r\nHost: a\r\nBad Name: v\r\n\r\n"))
        assertEquals(400, status("GET / HTTP/1.1\r\nHost: a\r\nX: v\r\n folded\r\n\r\n"))
        assertEquals(400, status("GET /\u0000 HTTP/1.1\r\nHost: a\r\n\r\n"))
        assertEquals(400, status("GET /é HTTP/1.1\r\nHost: a\r\n\r\n"))
        assertEquals(400, status("GET / HTTP/1.1\r\nHost: a\r\nX: a\rb\r\n\r\n"))
        assertEquals(400, status("GET / HTTP/1.1\r\n\r\n")) // HTTP/1.1 without Host
        assertEquals(400, status("GET / HTTP/1.1\r\nHost: a\r\nHost: b\r\n\r\n"))
        assertEquals(400, status("GET / HTTP/1.1\r\nHost: a\r\n")) // truncated
        assertEquals(400, status("\r\n\r\n\r\nGET / HTTP/1.1\r\nHost: a\r\n\r\n"))
    }

    @Test
    fun framingHeadersThatCouldSmuggleAreRefused() {
        assertEquals(400, status("PROPFIND / HTTP/1.1\r\nHost: a\r\nContent-Length: 5\r\nTransfer-Encoding: chunked\r\n\r\n"))
        assertEquals(400, status("PROPFIND / HTTP/1.1\r\nHost: a\r\nContent-Length: 5\r\nContent-Length: 6\r\n\r\n"))
        assertEquals(400, status("PROPFIND / HTTP/1.1\r\nHost: a\r\nContent-Length: -1\r\n\r\n"))
        assertEquals(400, status("PROPFIND / HTTP/1.1\r\nHost: a\r\nContent-Length: 1e3\r\n\r\n"))
        assertEquals(501, status("PROPFIND / HTTP/1.1\r\nHost: a\r\nTransfer-Encoding: gzip, chunked\r\n\r\n"))
        read("PROPFIND / HTTP/1.1\r\nHost: a\r\nContent-Length: 5\r\nContent-Length: 5\r\n\r\n")
    }

    @Test
    fun anEmptyStreamIsACleanClose() {
        try {
            read("")
            fail()
        } catch (_: HttpConnectionClosed) {
        }
    }

    @Test
    fun bodiesAreBoundedForBothFramings() {
        val chunked = "4\r\nabcd\r\n3;ext=1\r\nefg\r\n0\r\nTrailer: x\r\n\r\n"
        val request = read("PROPFIND / HTTP/1.1\r\nHost: a\r\nTransfer-Encoding: chunked\r\n\r\n")
        assertEquals(7L, drainRequestBody(request, ByteArrayInputStream(chunked.toByteArray()), 64))
        try {
            drainRequestBody(request, ByteArrayInputStream("41\r\n${"a".repeat(65)}\r\n0\r\n\r\n".toByteArray()), 64)
            fail()
        } catch (error: HttpParseException) {
            assertEquals(413, error.status)
        }
        val sized = read("PROPFIND / HTTP/1.1\r\nHost: a\r\nContent-Length: 65\r\n\r\n")
        try {
            drainRequestBody(sized, ByteArrayInputStream(ByteArray(65)), 64)
            fail()
        } catch (error: HttpParseException) {
            assertEquals(413, error.status)
        }
        try {
            drainRequestBody(read("PROPFIND / HTTP/1.1\r\nHost: a\r\nContent-Length: 10\r\n\r\n"), ByteArrayInputStream(ByteArray(3)), 64)
            fail()
        } catch (error: HttpParseException) {
            assertEquals(400, error.status)
        }
        try {
            drainRequestBody(request, ByteArrayInputStream("zz\r\n".toByteArray()), 64)
            fail()
        } catch (error: HttpParseException) {
            assertEquals(400, error.status)
        }
    }
}
