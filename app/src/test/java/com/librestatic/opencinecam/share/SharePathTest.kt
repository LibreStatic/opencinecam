/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SharePathTest {
    private fun status(target: String): Int = try {
        parseSharePath(target)
        fail("accepted $target")
        0
    } catch (error: HttpParseException) {
        error.status
    }

    @Test
    fun parsesTheTreeShapes() {
        assertEquals(SharePath(emptyList(), true), parseSharePath("/"))
        assertEquals(SharePath(listOf("A001_C001"), true), parseSharePath("/A001_C001/"))
        assertEquals(SharePath(listOf("A001_C001"), false), parseSharePath("/A001_C001"))
        assertEquals(SharePath(listOf("A001_C001", "A001_C001_000000.dng"), false), parseSharePath("/A001_C001/A001_C001_000000.dng"))
        assertEquals(listOf("A 1"), parseSharePath("/A%201/").segments)
        assertEquals(listOf("A001"), parseSharePath("http://192.168.1.5:8642/A001/?x=1").segments)
        assertEquals(emptyList<String>(), parseSharePath("http://192.168.1.5:8642").segments)
    }

    @Test
    fun traversalAndEncodedSeparatorsAreRefused() {
        for (target in listOf("/../", "/a/../b", "/..", "/./a", "/%2e%2e/", "/%2E%2E", "/a/%2e%2e", "/.%2e/", "/%2e/",
            "/a%2fb", "/a%2Fb/c", "/a%5cb", "/a\\b", "/a//b", "//", "/a%00b", "/a%0ab", "/a%7f", "/%", "/%4", "/%zz", "/%c0%ae%c0%ae/",
            "/%ff", "/%e2%82", "*", "a/b", "")) {
            assertEquals(target, 400, status(target))
        }
    }

    @Test
    fun deepAndOverlongPathsAreRefused() {
        assertEquals(404, status("/a/b/c"))
        assertEquals(414, status("/" + "a".repeat(MAX_PATH_BYTES)))
    }

    @Test
    fun unicodeIsDecodedAndNormalised() {
        // "Café" with a decomposed é (as macOS may send it) and a composed one find the same name.
        val composed = parseSharePath("/Caf%C3%A9/").segments
        val decomposed = parseSharePath("/Cafe%CC%81/").segments
        assertEquals(listOf("Café"), composed)
        assertEquals(composed, decomposed)
        assertEquals(listOf("名前"), parseSharePath("/%E5%90%8D%E5%89%8D").segments)
    }

    @Test
    fun hrefSegmentsAreFullyEncoded() {
        assertEquals("A001_C001_000000.dng", percentEncodeSegment("A001_C001_000000.dng"))
        assertEquals("a%20b%26c%3C%3E%22%27%25%2F%3F%23", percentEncodeSegment("a b&c<>\"'%/?#"))
        assertEquals("Caf%C3%A9", percentEncodeSegment("Café"))
        val round = "Take #1 & <b>"
        assertTrue(parseSharePath("/" + percentEncodeSegment(round)).segments == listOf(round))
    }
}
