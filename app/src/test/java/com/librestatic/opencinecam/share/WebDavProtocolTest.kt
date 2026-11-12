/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.share

import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Element

class WebDavProtocolTest {
    @Test
    fun rangeEdgeCases() {
        val size = 1000L
        assertEquals(RangeDecision.Full, decideRange(null, size))
        assertEquals(RangeDecision.Partial(0, 0), decideRange("bytes=0-0", size))
        assertEquals(RangeDecision.Partial(0, 999), decideRange("bytes=0-", size))
        assertEquals(RangeDecision.Partial(500, 999), decideRange("bytes=500-", size))
        assertEquals(RangeDecision.Partial(999, 999), decideRange("bytes=999-999", size))
        assertEquals(RangeDecision.Partial(10, 999), decideRange("bytes=10-5000", size))
        assertEquals(RangeDecision.Partial(900, 999), decideRange("bytes=-100", size))
        assertEquals(RangeDecision.Partial(0, 999), decideRange("bytes=-5000", size))
        assertEquals(RangeDecision.Partial(5, 9), decideRange("Bytes = 5-9", size))
        assertEquals(5L, (decideRange("bytes=5-9", size) as RangeDecision.Partial).length)

        assertEquals(RangeDecision.Unsatisfiable, decideRange("bytes=1000-", size))
        assertEquals(RangeDecision.Unsatisfiable, decideRange("bytes=1000-1001", size))
        assertEquals(RangeDecision.Unsatisfiable, decideRange("bytes=-0", size))
        assertEquals(RangeDecision.Unsatisfiable, decideRange("bytes=0-", 0))
        assertEquals(RangeDecision.Unsatisfiable, decideRange("bytes=-1", 0))

        // Ignorable: served whole.
        for (header in listOf("bytes=5-1", "items=0-1", "bytes=0-1,5-6", "bytes=", "bytes=-", "bytes=a-b", "bytes 0-1", "bytes=--1",
            "bytes=0x10-", "bytes=99999999999999999999-", "bytes=1-2-3")) {
            assertEquals(header, RangeDecision.Full, decideRange(header, size))
        }
    }

    @Test
    fun depthIsZeroOneOrInfinity() {
        assertEquals(0, parseDepth("0"))
        assertEquals(1, parseDepth(" 1 "))
        assertEquals(null, parseDepth(null))
        assertEquals(null, parseDepth("Infinity"))
        for (bad in listOf("2", "-1", "", "one")) {
            try {
                parseDepth(bad)
                fail(bad)
            } catch (error: HttpParseException) {
                assertEquals(400, error.status)
            }
        }
    }

    @Test
    fun datesUseTheWireFormats() {
        assertEquals("Mon, 02 Nov 2026 12:00:00 GMT", httpDate(ShareFixture.MODIFIED_MS))
        assertEquals("2026-11-02T12:00:00Z", isoDate(ShareFixture.MODIFIED_MS))
    }

    @Test
    fun xmlEscapingCoversMarkupAndInvalidCharacters() {
        assertEquals("a&amp;b&lt;c&gt;d&quot;e&apos;f", xmlEscape("a&b<c>d\"e'f"))
        assertEquals("x�y", xmlEscape("x\u0001y"))
        assertEquals("tab\there", xmlEscape("tab\there"))
    }

    private fun file(name: String, size: Long = 42) =
        ShareFile(name, ShareFixture.MODIFIED_MS, size, ShareTree.DNG_TYPE, "\"e-$name\"") { _, _, _ -> }

    @Test
    fun multistatusIsWellFormedAndEscaped() {
        val odd = "Take <1> & \"q\" 'é' #%?"
        val nodes = listOf<Pair<ShareNode, String?>>(
            ShareCollection("", ShareFixture.MODIFIED_MS, null) to null,
            ShareCollection(odd, ShareFixture.MODIFIED_MS, null) to null,
            file("$odd.dng", 1234) to odd,
        )
        val xml = multistatusParts(nodes).joinToString("")
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val document = factory.newDocumentBuilder().parse(xml.byteInputStream(Charsets.UTF_8))
        val responses = document.getElementsByTagNameNS("DAV:", "response")
        assertEquals(3, responses.length)

        fun Element.text(tag: String): String? = getElementsByTagNameNS("DAV:", tag).item(0)?.textContent
        val root = responses.item(0) as Element
        assertEquals("/", root.text("href"))
        assertEquals("OpenCineCam", root.text("displayname"))
        assertEquals(1, root.getElementsByTagNameNS("DAV:", "collection").length)

        val folder = responses.item(1) as Element
        val encoded = percentEncodeSegment(odd)
        assertEquals("/$encoded/", folder.text("href"))
        assertEquals(odd, folder.text("displayname"))
        assertEquals(listOf(odd), parseSharePath(folder.text("href")!!).segments)

        val dng = responses.item(2) as Element
        assertEquals("/$encoded/${percentEncodeSegment("$odd.dng")}", dng.text("href"))
        assertEquals("1234", dng.text("getcontentlength"))
        assertEquals(ShareTree.DNG_TYPE, dng.text("getcontenttype"))
        assertEquals("\"e-$odd.dng\"", dng.text("getetag"))
        assertEquals("Mon, 02 Nov 2026 12:00:00 GMT", dng.text("getlastmodified"))
        assertEquals(0, dng.getElementsByTagNameNS("DAV:", "collection").length)
        assertEquals("HTTP/1.1 200 OK", dng.text("status"))
        // Hrefs carry no raw space, quote or non-ASCII byte.
        assertTrue(Regex("<D:href>[A-Za-z0-9%/._~-]+</D:href>").findAll(xml).count() == 3)
    }

    @Test
    fun collectionHtmlEscapesNamesAndLinks() {
        val html = collectionHtml("<x>", listOf(file("a<b>.dng") to "f\""))
        assertTrue(html.contains("<title>&lt;x&gt;</title>"))
        assertTrue(html.contains("href=\"/f%22/a%3Cb%3E.dng\""))
        assertTrue(html.contains(">a&lt;b&gt;.dng</a>"))
        assertTrue(!html.contains("<b>"))
    }
}
