/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.transfers

import org.junit.Assert.*
import org.junit.Test

class WebDavQueuePreferencesTest {
    private val first = WebDavQueueEndpoint(id(1), "https://example.test/captures/")
    private val second = WebDavQueueEndpoint(id(2), "https://backup.test/captures/")
    private val configured get() = WebDavQueuePreferences(7, true, false, first.id, listOf(first, second))
    private val document get() = WebDavQueuePreferencesCodec.encode(configured).toString(Charsets.UTF_8)

    @Test fun defaultsDoNotEnableTransfersOrCellularAndHaveNoEndpoint() {
        val value = WebDavQueuePreferences()
        assertEquals(0L, value.revision)
        assertFalse(value.enabled)
        assertFalse(value.allowCellular)
        assertNull(value.activeEndpointId)
        assertNull(value.activeEndpoint)
        assertTrue(value.endpoints.isEmpty())
        assertSame(value, value.updated("", enabled = false, allowCellular = false))
    }

    @Test fun enabledPreferencesRequireAnExistingActiveEndpoint() {
        assertThrows(IllegalArgumentException::class.java) { WebDavQueuePreferences(enabled = true) }
        assertThrows(IllegalArgumentException::class.java) { WebDavQueuePreferences(activeEndpointId = id(9), endpoints = listOf(first)) }
        assertThrows(IllegalArgumentException::class.java) { WebDavQueuePreferences().updated(" ", true, false) }
        assertEquals(first, configured.activeEndpoint)
    }

    @Test fun urlsRequireHttpsAndExcludeCredentialsQueryFragmentAndInvalidPorts() {
        for (url in listOf("http://example.test/", "ftp://example.test/", "file:///tmp/output", "https:///missing-host",
            "https://user:password@example.test/", "https://user@example.test/", "https://example.test/?token=secret",
            "https://example.test/#fragment", "https://example.test:0/", "https://example.test:65536/",
            "https://example.test:-1/", "https://exa mple.test/", "HTTPS://example.test/", "//example.test/")) {
            assertThrows(url, Exception::class.java) { normalizeQueueEndpoint(url) }
        }
    }

    @Test fun supportedHttpsPortsIpv6AndEscapedPathsRemainExact() {
        for (url in listOf("https://example.test:1/", "https://example.test:65535/", "https://[::1]:8443/captures/",
            "https://example.test/capture%20files/", "https://example.test/a%3Fb%23c/")) {
            assertEquals(url, normalizeQueueEndpoint(url))
            assertEquals(url, WebDavQueueEndpoint(first.id, url).url)
        }
    }

    @Test fun normalizationOccursOnUpdatesButStoredEndpointMustAlreadyBeCanonical() {
        val raw = "https://example.test/a/../café/"
        val normalized = "https://example.test/caf%C3%A9/"
        assertEquals(normalized, normalizeQueueEndpoint(raw))
        assertThrows(IllegalArgumentException::class.java) { WebDavQueueEndpoint(first.id, raw) }
        val changed = WebDavQueuePreferences().updated("  $raw  ", true, false)
        assertEquals(normalized, changed.activeEndpoint?.url)
        assertEquals(1L, changed.revision)
    }

    @Test fun endpointLengthAndControlCharactersAreBoundedBeforeAndAfterAsciiEncoding() {
        for (url in listOf("", "https://example.test/\nfile", "https://example.test/\u0000", "https://example.test/\u007f",
            "https://example.test/" + "a".repeat(2048), "https://example.test/" + "é".repeat(400))) {
            assertThrows(Exception::class.java) { normalizeQueueEndpoint(url) }
        }
        val prefix = "https://example.test/"
        val largest = prefix + "a".repeat(2048 - prefix.length)
        assertEquals(largest, normalizeQueueEndpoint(largest))
    }

    @Test fun endpointIdentifiersAreCanonicalAndDuplicateIdsOrAddressesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { WebDavQueueEndpoint("1-1-1-1-1", first.url) }
        assertThrows(IllegalArgumentException::class.java) { WebDavQueueEndpoint("AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE", first.url) }
        assertThrows(IllegalArgumentException::class.java) { WebDavQueuePreferences(endpoints = listOf(first, first.copy(url = second.url))) }
        assertThrows(IllegalArgumentException::class.java) { WebDavQueuePreferences(endpoints = listOf(first, first.copy(id = second.id))) }
    }

    @Test fun addressesGetNewIdentitiesAndOldBindingsAreRetained() {
        val before = configured
        val after = before.updated("https://third.test/media/", true, false)
        assertEquals(before.revision + 1, after.revision)
        assertEquals(3, after.endpoints.size)
        assertNotEquals(first.id, after.activeEndpointId)
        assertNotEquals(second.id, after.activeEndpointId)
        assertEquals(first, after.endpoints.single { it.id == first.id })
        assertEquals(second, after.endpoints.single { it.id == second.id })
        assertEquals(2, before.endpoints.size)
        assertEquals(first.id, before.activeEndpointId)
    }

    @Test fun returningToKnownAddressReusesOriginalIdRatherThanRebindingAnotherEndpoint() {
        val other = configured.updated(second.url, true, false)
        assertEquals(second.id, other.activeEndpointId)
        assertEquals(2, other.endpoints.size)
        val returned = other.updated(first.url, true, false)
        assertEquals(first.id, returned.activeEndpointId)
        assertEquals(configured.endpoints, returned.endpoints)
        assertEquals(9L, returned.revision)
    }

    @Test fun disablingOrCellularConsentChangesRevisionWithoutReplacingEndpointIdentity() {
        val disabled = configured.updated(first.url, false, false)
        assertFalse(disabled.enabled)
        assertEquals(first.id, disabled.activeEndpointId)
        assertEquals(8L, disabled.revision)
        val cellular = disabled.updated(first.url, false, true)
        assertTrue(cellular.allowCellular)
        assertEquals(first.id, cellular.activeEndpointId)
        assertEquals(9L, cellular.revision)
    }

    @Test fun clearingActiveAddressRetainsHistoryAndRestoringItReusesIdentity() {
        val cleared = configured.updated("", false, false)
        assertNull(cleared.activeEndpointId)
        assertEquals(configured.endpoints, cleared.endpoints)
        val restored = cleared.updated(first.url, true, false)
        assertEquals(first.id, restored.activeEndpointId)
        assertEquals(configured.endpoints, restored.endpoints)
    }

    @Test fun unchangedNormalizedValuesDoNotIncrementRevisionOrAllocatePreferences() {
        val before = configured
        assertSame(before, before.updated(" ${first.url} ", true, false))
        val normalizedAlias = first.url + "temp/../"
        assertSame(before, before.updated(normalizedAlias, true, false))
    }

    @Test fun revisionCannotBeNegativeOrWrapAndNoOpAtMaximumRemainsPossible() {
        assertThrows(IllegalArgumentException::class.java) { WebDavQueuePreferences(revision = -1) }
        val maximum = WebDavQueuePreferences(Long.MAX_VALUE, true, false, first.id, listOf(first))
        assertSame(maximum, maximum.updated(first.url, true, false))
        assertThrows(ArithmeticException::class.java) { maximum.updated(first.url, false, false) }
        assertEquals(Long.MAX_VALUE, maximum.revision)
        assertTrue(maximum.enabled)
    }

    @Test fun endpointHistoryCapacityDoesNotEvictOldBindingsAndAllowsKnownEndpointSelection() {
        val endpoints = (1..32).map { WebDavQueueEndpoint(id(it), "https://example.test/$it/") }
        val full = WebDavQueuePreferences(1, true, false, endpoints.first().id, endpoints)
        assertEquals(32, full.updated(endpoints.last().url, true, false).endpoints.size)
        assertThrows(IllegalArgumentException::class.java) { full.updated("https://new.test/", true, false) }
        assertEquals(endpoints, full.endpoints)
        assertThrows(IllegalArgumentException::class.java) { WebDavQueuePreferences(endpoints = endpoints + WebDavQueueEndpoint(id(33), "https://new.test/")) }
    }

    @Test fun collectionIsCopiedSortedAndUnmodifiable() {
        val original = mutableListOf(second, first)
        val value = WebDavQueuePreferences(endpoints = original)
        original.clear()
        assertEquals(listOf(first, second), value.endpoints)
        assertThrows(UnsupportedOperationException::class.java) { (value.endpoints as MutableList<WebDavQueueEndpoint>).clear() }
        assertEquals(listOf(first, second), value.endpoints)
    }

    @Test fun valueEqualityAndHashIgnoreInputCollectionOrderButIncludeConsentAndRevision() {
        val same = WebDavQueuePreferences(7, true, false, first.id, listOf(second, first))
        assertEquals(configured, same)
        assertEquals(configured.hashCode(), same.hashCode())
        assertNotEquals(configured, WebDavQueuePreferences(8, true, false, first.id, same.endpoints))
        assertNotEquals(configured, WebDavQueuePreferences(7, true, true, first.id, same.endpoints))
    }

    @Test fun codecRoundTripsDefaultsAndConfiguredValuesCanonically() {
        for (value in listOf(WebDavQueuePreferences(), configured)) {
            val bytes = WebDavQueuePreferencesCodec.encode(value)
            val decoded = WebDavQueuePreferencesCodec.decode(bytes)
            assertEquals(value, decoded)
            assertArrayEquals(bytes, WebDavQueuePreferencesCodec.encode(decoded))
        }
        assertEquals("{\"version\":2,\"revision\":0,\"enabled\":false,\"allowCellular\":false,\"activeEndpointId\":null,\"endpoints\":[]}",
            WebDavQueuePreferencesCodec.encode(WebDavQueuePreferences()).toString(Charsets.UTF_8))
    }

    @Test fun codecPreservesLongRevisionWithoutDoubleRounding() {
        for (revision in listOf(9_007_199_254_740_993L, Long.MAX_VALUE)) {
            val value = WebDavQueuePreferences(revision, true, false, first.id, listOf(first))
            assertEquals(value, WebDavQueuePreferencesCodec.decode(WebDavQueuePreferencesCodec.encode(value)))
        }
    }

    @Test fun codecRejectsVersionAndScalarCoercion() {
        for (version in listOf("3", "\"2\"", "2.0", "null")) reject(document.replace("\"version\":2", "\"version\":$version"))
        for (revision in listOf("\"7\"", "7.0", "7e0", "-1", "9223372036854775808", "null")) reject(document.replace("\"revision\":7", "\"revision\":$revision"))
        reject(document.replace("\"enabled\":true", "\"enabled\":\"true\""))
        reject(document.replace("\"allowCellular\":false", "\"allowCellular\":0"))
        reject(document.replace("\"activeEndpointId\":\"${first.id}\"", "\"activeEndpointId\":0"))
    }

    @Test fun codecRejectsDuplicateMissingAndUnknownFields() {
        reject(document.replace("\"revision\":7", "\"revision\":7,\"revision\":7"))
        reject(document.replace("\"version\":2,", ""))
        reject(document.dropLast(1) + ",\"password\":\"hidden\"}")
        reject(document.replace("\"url\":\"${first.url}\"", "\"url\":\"${first.url}\",\"password\":\"hidden\""))
    }

    @Test fun codecRejectsNoncanonicalWhitespaceOrderingAndEndpointNormalization() {
        reject(" $document")
        reject(document + "\n")
        reject(document.replace("\"version\":2,", "").dropLast(1) + ",\"version\":2}")
        reject(document.replace(first.url, first.url + "temp/../"))
        reject(document.replace(first.id, "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"))
    }

    @Test fun codecRejectsInvalidReferencesDuplicateHistoryAndExcessiveHistory() {
        reject(document.replace("\"activeEndpointId\":\"${first.id}\"", "\"activeEndpointId\":\"${id(9)}\""))
        reject(document.replace(second.id, first.id))
        reject(document.replace(second.url, first.url))
        val entries = (1..33).joinToString(",") { "{\"id\":\"${id(it)}\",\"url\":\"https://example.test/$it/\",\"ignoreTlsErrors\":false}" }
        reject("{\"version\":2,\"revision\":0,\"enabled\":false,\"allowCellular\":false,\"activeEndpointId\":null,\"endpoints\":[$entries]}")
    }

    @Test fun codecRejectsMalformedUtf8OversizedBytesAndShortDeepDocuments() {
        assertThrows(Exception::class.java) { WebDavQueuePreferencesCodec.decode(byteArrayOf(0xc3.toByte(), 0x28)) }
        assertThrows(Exception::class.java) { WebDavQueuePreferencesCodec.decode(ByteArray(WebDavQueuePreferencesCodec.MAX_BYTES + 1)) }
        reject("[".repeat(400) + "0" + "]".repeat(400))
        reject("{partial")
        reject("")
    }

    @Test fun codecRoundTripsEscapedStructuralCharactersInEndpointPath() {
        val url = "https://example.test/%7Bsample%7D/"
        val value = WebDavQueuePreferences(0, false, false, null, listOf(WebDavQueueEndpoint(id(1), url)))
        assertEquals(value, WebDavQueuePreferencesCodec.decode(WebDavQueuePreferencesCodec.encode(value)))
    }

    @Test fun tlsExceptionDefaultsOffAndRequiresALiteralLocalEndpoint() {
        assertFalse(first.ignoreTlsErrors)
        for (url in listOf("https://192.168.1.20:8443/takes/", "https://10.0.2.2/takes/", "https://127.0.0.1/", "https://[::1]/")) {
            assertTrue(WebDavQueueEndpoint(id(3), url, ignoreTlsErrors = true).ignoreTlsErrors)
        }
        for (url in listOf(first.url, "https://localhost/", "https://8.8.8.8/", "https://[2001:4860:4860::8888]/")) {
            assertThrows(IllegalArgumentException::class.java) { WebDavQueueEndpoint(id(3), url, ignoreTlsErrors = true) }
        }
        assertThrows(IllegalArgumentException::class.java) { WebDavQueuePreferences().updated("", false, false, true) }
    }

    @Test fun changingTlsPolicyRevisesOnlyTheSelectedImmutableBinding() {
        val local = WebDavQueueEndpoint(id(3), "https://192.168.1.20/takes/")
        val before = WebDavQueuePreferences(7, true, true, local.id, listOf(first, local))
        val after = before.updated(local.url, true, true, ignoreTlsErrors = true)
        assertEquals(8L, after.revision)
        assertEquals(local.copy(ignoreTlsErrors = true), after.activeEndpoint)
        assertEquals(first, after.endpoints.single { it.id == first.id })
        assertEquals(2, after.endpoints.size)
        assertFalse(requireNotNull(before.activeEndpoint).ignoreTlsErrors)
        assertTrue(after.enabled)
        assertTrue(after.allowCellular)
        assertSame(after, after.updated(local.url, true, true, true))
        assertSame(after, after.updated(local.url, true, true))
        assertEquals(9L, after.updated(local.url, true, true, false).revision)
        assertNotEquals(before, WebDavQueuePreferences(7, true, true, local.id, after.endpoints))
    }

    @Test fun tlsPolicyIsRetainedOnConsentChangesAndKnownEndpointReuseButNeverInheritedByNewAddress() {
        val local = WebDavQueueEndpoint(id(3), "https://192.168.1.20/takes/", true)
        val original = WebDavQueuePreferences(1, true, false, local.id, listOf(first, local))
        val disabled = original.updated(local.url, false, true)
        assertTrue(requireNotNull(disabled.activeEndpoint).ignoreTlsErrors)
        val selected = disabled.updated(first.url, false, true)
        assertFalse(requireNotNull(selected.activeEndpoint).ignoreTlsErrors)
        val restored = selected.updated(local.url, false, true)
        assertEquals(local, restored.activeEndpoint)
        val fresh = restored.updated("https://192.168.1.21/takes/", false, true)
        assertFalse(requireNotNull(fresh.activeEndpoint).ignoreTlsErrors)
        assertEquals(local, fresh.endpoints.single { it.id == local.id })
        assertThrows(IllegalArgumentException::class.java) { original.updated(first.url, true, false, true) }
        assertEquals(local, original.activeEndpoint)
    }

    @Test fun tlsPolicyChangeAtCapacityReusesBindingAndRevisionOverflowDoesNotMutateIt() {
        val endpoints = (1..32).map { WebDavQueueEndpoint(id(it), "https://192.168.1.20/$it/") }
        val full = WebDavQueuePreferences(5, false, false, endpoints.first().id, endpoints)
        val changed = full.updated(endpoints.last().url, false, false, true)
        assertEquals(32, changed.endpoints.size)
        assertEquals(endpoints.last().id, changed.activeEndpointId)
        assertTrue(requireNotNull(changed.activeEndpoint).ignoreTlsErrors)
        val maximum = WebDavQueuePreferences(Long.MAX_VALUE, false, false, endpoints.first().id, endpoints)
        assertThrows(ArithmeticException::class.java) { maximum.updated(endpoints.first().url, false, false, true) }
        assertFalse(requireNotNull(maximum.activeEndpoint).ignoreTlsErrors)
    }

    @Test fun canonicalVersionOneMigratesAllBindingsAndExactRevisionWithTlsExceptionsOff() {
        val revision = 9_007_199_254_740_993L
        val legacy = document.replace("\"version\":2", "\"version\":1")
            .replace("\"revision\":7", "\"revision\":$revision")
            .replace(",\"ignoreTlsErrors\":false", "")
        val decoded = WebDavQueuePreferencesCodec.decode(legacy.toByteArray())
        assertEquals(WebDavQueuePreferences(revision, true, false, first.id, listOf(first, second)), decoded)
        assertTrue(decoded.endpoints.none { it.ignoreTlsErrors })
        val migrated = WebDavQueuePreferencesCodec.encode(decoded).toString(Charsets.UTF_8)
        assertTrue(migrated.startsWith("{\"version\":2,"))
        assertEquals(decoded, WebDavQueuePreferencesCodec.decode(migrated.toByteArray()))
        reject(" $legacy")
        reject(legacy.replace("\"version\":1", "\"version\":1,\"version\":1"))
        reject(legacy.replace("\"enabled\":true", "\"enabled\":\"true\""))
    }

    @Test fun codecRequiresStrictVersionSpecificTlsFieldAndRejectsPublicException() {
        reject(document.replace("\"version\":2", "\"version\":1"))
        reject(document.replace(",\"ignoreTlsErrors\":false", ""))
        for (value in listOf("\"false\"", "0", "null", "[]", "{}")) {
            reject(document.replace("\"ignoreTlsErrors\":false", "\"ignoreTlsErrors\":$value"))
        }
        reject(document.replace("\"ignoreTlsErrors\":false", "\"ignoreTlsErrors\":false,\"ignoreTlsErrors\":false"))
        reject(document.replace("\"ignoreTlsErrors\":false", "\"ignoreTlsErrors\":true"))
    }

    @Test fun codecRoundTripsEnabledLocalExceptionWithoutChangingIdentityOrConsent() {
        val local = WebDavQueueEndpoint(id(3), "https://192.168.1.20/takes/", true)
        val value = WebDavQueuePreferences(8, false, true, local.id, listOf(first, local))
        val bytes = WebDavQueuePreferencesCodec.encode(value)
        assertTrue(bytes.toString(Charsets.UTF_8).contains("\"ignoreTlsErrors\":true"))
        assertEquals(value, WebDavQueuePreferencesCodec.decode(bytes))
        assertArrayEquals(bytes, WebDavQueuePreferencesCodec.encode(WebDavQueuePreferencesCodec.decode(bytes)))
    }

    private fun reject(text: String) {
        assertThrows(Exception::class.java) { WebDavQueuePreferencesCodec.decode(text.toByteArray()) }
    }
    private fun id(value: Int) = "00000000-0000-0000-0000-${value.toString().padStart(12, '0')}"
}
