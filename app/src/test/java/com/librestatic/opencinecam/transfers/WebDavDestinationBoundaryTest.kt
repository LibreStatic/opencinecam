/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.transfers

import java.net.URI
import org.junit.Assert.*
import org.junit.Test

class WebDavDestinationBoundaryTest {
    @Test fun encodedTrailingSlashIsNotACollectionSeparator() {
        for (url in listOf("https://dav.example/takes%2F", "https://dav.example/takes%2f")) {
            val uri = URI(url)
            assertTrue(uri.path.endsWith('/'))
            assertFalse(uri.rawPath.endsWith('/'))
            assertThrows(url, IllegalArgumentException::class.java) { WebDavDestination(uri) }
        }
    }

    @Test fun decodedTraversalIsRejectedEvenWhenRawUriNormalizationLeavesItUnchanged() {
        for (path in listOf("/%2e/", "/%2E%2e/", "/a/.%2e/", "/a/%2e./", "/a%2F..%2Fb/")) {
            val uri = URI("https://dav.example$path")
            assertEquals(uri, uri.normalize())
            assertThrows(path, IllegalArgumentException::class.java) { WebDavDestination(uri) }
        }
    }

    @Test fun literalSlashPercentUnicodeAndEncodedFilenameRemainBoundToTheSameCollection() {
        val name = "take ñ:100%?#.mp4"
        val encoded = "take%20%C3%B1%3A100%25%3F%23.mp4"
        for ((collection, rawPath) in listOf(
            "https://dav.example/" to "/",
            "https://dav.example/takes%20pro/" to "/takes%20pro/",
            "https://dav.example/100%25/" to "/100%25/",
            "https://dav.example/takes-ñ/" to "/takes-%C3%B1/",
        )) {
            val destination = WebDavDestination(URI(collection))
            val target = destination.clipUri(name)
            assertEquals(rawPath + encoded, target.rawPath)
            assertEquals("dav.example", target.host)
            assertEquals("https", target.scheme)
            assertNull(target.rawQuery)
            assertNull(target.rawFragment)
        }
    }

    @Test fun legacyEncodedTrailingSlashSettingsRoundTripWithoutRewritingOrRebinding() {
        for (url in listOf("https://dav.example/takes%2f", "https://dav.example/takes%2F", "https://dav.example/takes")) {
            val initial = WebDavQueuePreferences().updated(url, true, false)
            val id = initial.activeEndpointId
            val bytes = WebDavQueuePreferencesCodec.encode(initial)
            val decoded = WebDavQueuePreferencesCodec.decode(bytes)
            assertEquals(url, decoded.activeEndpoint!!.url)
            assertEquals(id, decoded.activeEndpointId)
            assertArrayEquals(bytes, WebDavQueuePreferencesCodec.encode(decoded))
            assertThrows(IllegalArgumentException::class.java) { WebDavDestination(URI(decoded.activeEndpoint!!.url)) }
            assertEquals(initial, decoded.updated(url, true, false))
            assertEquals(id, decoded.updated(url, false, false).activeEndpointId)
            assertEquals(url, decoded.updated(url, false, false).activeEndpoint!!.url)
            // Explicit correction is a different immutable profile; old identity/address remain.
            val corrected = decoded.updated("https://dav.example/takes/", true, false)
            assertNotEquals(id, corrected.activeEndpointId)
            assertEquals(url, corrected.endpoints.single { it.id == id }.url)
        }
    }
}
