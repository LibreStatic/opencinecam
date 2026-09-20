/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import org.junit.Assert.*
import org.junit.Test

class ProxyFilenameTest {
    @Test fun legalStemsProduceCanonicalMp4NamesWithoutChangingLiteralContent() {
        for (stem in listOf("Scene_12", "Scene 12", "Film ñ", "鏡頭", "Take-01_proxy", "clip.v2")) {
            assertEquals("$stem.mp4", proxyFilename(stem))
            requireProxyFilename("$stem.mp4")
        }
    }

    @Test fun normalizesEditableStemToNfcButRejectsNoncanonicalStoredName() {
        assertEquals("Café.mp4", proxyFilename("Cafe\u0301"))
        requireProxyFilename("Café.mp4")
        assertThrows(IllegalArgumentException::class.java) { requireProxyFilename("Cafe\u0301.mp4") }
    }

    @Test fun existingDefaultUuidNameIsAcceptedUnchanged() {
        val name = "proxy-2b2a7bb1-5061-4379-86c1-cd7cbca97e42.mp4"
        requireProxyFilename(name)
        assertEquals(name, proxyFilename(name.removeSuffix(".mp4")))
    }

    @Test fun extensionInEditableStemIsLiteralNotSilentlyRemoved() {
        assertEquals("foo.mp4.mp4", proxyFilename("foo.mp4"))
        requireProxyFilename("foo.mp4.mp4")
        assertEquals("foo.MP4.mp4", proxyFilename("foo.MP4"))
        requireProxyFilename("foo.MP4.mp4")
        for (name in listOf("foo", "foo.MP4", "foo.mp4 ", "foo.mp4.", "foo.mov", ".mp4")) {
            assertThrows(name, IllegalArgumentException::class.java) { requireProxyFilename(name) }
        }
    }

    @Test fun invalidPortableStemsRejectBeforeAppendingExtension() {
        for (stem in listOf("", ".", "..", "../clip", "dir/clip", "dir\\clip", "clip\nname", "clip\u0000name",
            "clip\u202Ename", "\uD800", "\uDC00", "trailing ", "trailing.", "a:b", "a*b", "a?b", "a\"b", "a<b", "a>b", "a|b")) {
            assertThrows(stem, IllegalArgumentException::class.java) { proxyFilename(stem) }
            assertThrows(stem, IllegalArgumentException::class.java) { requireProxyFilename("$stem.mp4") }
        }
    }

    @Test fun stemLengthBoundaryUsesSharedValidatorAndIncludesNoHiddenTruncation() {
        val maximum = "x".repeat(128)
        assertEquals(maximum + ".mp4", proxyFilename(maximum))
        requireProxyFilename(maximum + ".mp4")
        assertThrows(IllegalArgumentException::class.java) { proxyFilename("x".repeat(129)) }
        assertThrows(IllegalArgumentException::class.java) { requireProxyFilename("x".repeat(129) + ".mp4") }
    }
    private val proxyId = "2b2a7bb1-5061-4379-86c1-cd7cbca97e42"
    private fun proxyRow() = MediaDeleteRow(LocalMediaArtifact("content://media/external_primary/video/media/7",
        "Custom.mp4", "video/mp4", 4096, 123), "Movies/OpenCineCamProxies/$proxyId/", "app.owner", 0)

    @Test fun proxyRenameConditionFreezesEveryIdentityFieldWithoutAdmittingCatalogMutation() {
        val row = proxyRow()
        val condition = proxyRenameCondition(row, proxyId, "app.owner")
        assertEquals(listOf("7", "app.owner", row.relativePath, "Custom.mp4", "video/mp4", "4096", "123"), condition.arguments)
        assertEquals("_id = ? AND owner_package_name = ? AND relative_path = ? AND _display_name = ? AND mime_type = ? AND _size = ? AND date_modified = ? AND is_pending = 0", condition.selection)
        assertThrows(IllegalArgumentException::class.java) { mediaDeleteCondition(row) }
    }

    @Test fun proxyRenameConditionRejectsOriginalsOtherNamespacesOwnersAndTypes() {
        val row = proxyRow()
        val invalid = listOf(row.copy(relativePath = "DCIM/OpenCineCam/"), row.copy(relativePath = "Movies/OpenCineCamProxies/other/"),
            row.copy(owner = "other.app"), row.copy(pending = 1), row.copy(artifact = row.artifact.copy(mimeType = "image/jpeg")),
            row.copy(artifact = row.artifact.copy(uri = "content://media/external_primary/downloads/7")),
            row.copy(artifact = row.artifact.copy(name = "../Custom.mp4")), row.copy(artifact = row.artifact.copy(sizeBytes = 0)))
        invalid.forEach { bad -> assertThrows(IllegalArgumentException::class.java) { proxyRenameCondition(bad, proxyId, "app.owner") } }
        assertThrows(IllegalArgumentException::class.java) { proxyRenameCondition(row, "not-uuid", "app.owner") }
    }
}
