/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.share

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.channels.Channels
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The virtual tree against the CinemaDNG golden fixture of `:media`. */
class ShareTreeTest {
    private val clock = ManualClock()
    private var calls = 0
    private var takes = listOf(ShareFixture.take())
    private val tree = ShareTree({ calls++; takes }, clock)

    @Before
    fun fixturePresent() {
        assertTrue("missing ${ShareFixture.ocraw.absolutePath}", ShareFixture.ocraw.isFile)
    }

    private fun bytes(file: ShareFile, offset: Long = 0, length: Long = file.size): ByteArray {
        val out = ByteArrayOutputStream()
        file.writeRange(offset, length, Channels.newChannel(out))
        return out.toByteArray()
    }

    private fun path(vararg segments: String, slash: Boolean = false) = SharePath(segments.toList(), slash)

    @Test
    fun rootListsOneFolderPerTake() {
        val folders = tree.takeFolders()
        assertEquals(listOf(ShareFixture.TAKE), folders.map { it.name })
        assertEquals(ShareFixture.MODIFIED_MS, tree.root().modifiedMs)
        assertTrue(tree.resolve(path()) is ShareCollection)
        assertEquals(ShareFixture.TAKE, (tree.resolve(path(ShareFixture.TAKE)) as ShareCollection).name)
    }

    @Test
    fun folderHoldsTheConvertibleFramesTheWavAndTheOriginal() {
        val take = tree.takeFolders().single().take!!
        val names = tree.files(take).map { it.name }
        val golden = ShareFixture.golden.list()!!.toSet()
        val dngs = names.filter { it.endsWith(".dng") }
        // RAW10 and LJ92 frames (0, 1, 2, 4, 6) always convert; frame 5 (unknown encoding) never
        // does; frame 3 (block-pack) needs the native codec, so it is offered only where that loads.
        assertTrue(dngs.toString(), dngs.containsAll(listOf(0, 1, 2, 4, 6).map { "A001_C001_00000$it.dng" }))
        assertTrue("A001_C001_000005.dng" !in dngs)
        assertTrue(golden.containsAll(dngs))
        assertEquals(dngs.sorted(), dngs)
        assertEquals(listOf("A001_C001.wav", "A001_C001.ocraw"), names.takeLast(2))
        assertEquals(ShareFixture.ocraw.length(), tree.files(take).last().size)
        assertEquals(ShareTree.DNG_TYPE, tree.files(take).first().contentType)
    }

    @Test
    fun listedSizesEqualTheBytesServedAndTheGoldenFiles() {
        val take = tree.takeFolders().single().take!!
        for (file in tree.files(take)) {
            val served = bytes(file)
            assertEquals(file.name, file.size, served.size.toLong())
            val expected = if (file.name.endsWith(".ocraw")) ShareFixture.ocraw.readBytes() else File(ShareFixture.golden, file.name).readBytes()
            assertArrayEquals(file.name, expected, served)
        }
        assertEquals(0, ShareFixture.open.get())
    }

    @Test
    fun rangesAreSlicesOfTheWholeFile() {
        val take = tree.takeFolders().single().take!!
        for (file in tree.files(take)) {
            val whole = bytes(file)
            val size = file.size
            for ((offset, length) in listOf(0L to 1L, 0L to size, size - 1 to 1L, 1L to size - 2, size / 3 to size / 3, 7L to 0L)) {
                assertArrayEquals("${file.name} $offset+$length", whole.copyOfRange(offset.toInt(), (offset + length).toInt()), bytes(file, offset, length))
            }
        }
        assertEquals(0, ShareFixture.open.get())
    }

    @Test
    fun everyReadOpensAndClosesItsOwnDescriptor() {
        val take = tree.takeFolders().single().take!!
        val file = tree.files(take).first()
        val before = ShareFixture.opened.get()
        bytes(file)
        bytes(file, 10, 10)
        assertEquals(before + 2, ShareFixture.opened.get())
        assertEquals(0, ShareFixture.open.get())
    }

    @Test
    fun namesResolveByExactLookupOnly() {
        assertNotNull(tree.resolve(path(ShareFixture.TAKE, "A001_C001_000000.dng")))
        assertNotNull(tree.resolve(path(ShareFixture.TAKE, "A001_C001.wav")))
        assertNotNull(tree.resolve(path(ShareFixture.TAKE, "A001_C001.ocraw")))
        for (miss in listOf("A001_C001_000005.dng", "A001_C001_000000.DNG", "A001_C001_0.dng", "desktop.ini", ".DS_Store",
            "._A001_C001_000000.dng", "Thumbs.db", "A001_C001_000000.dng ", "folder.jpg")) {
            assertNull(miss, tree.resolve(path(ShareFixture.TAKE, miss)))
        }
        assertNull(tree.resolve(path("A001_C002")))
        assertNull(tree.resolve(path("a001_c001")))
        assertNull(tree.resolve(path(ShareFixture.TAKE, "A001_C001_000000.dng", "x")))
    }

    @Test
    fun probesForUnknownNamesDoNotIndexTheTake() {
        val before = ShareFixture.opened.get()
        assertNull(tree.resolve(path(ShareFixture.TAKE, "desktop.ini")))
        assertNull(tree.resolve(path(ShareFixture.TAKE, ".DS_Store")))
        assertEquals(before, ShareFixture.opened.get())
    }

    @Test
    fun theListingIsCachedBrieflyAndTheIndexUntilTheFileChanges() {
        tree.takeFolders()
        tree.takeFolders()
        assertEquals(1, calls)
        clock.advance(3_000)
        tree.takeFolders()
        assertEquals(2, calls)

        val take = tree.takeFolders().single().take!!
        tree.files(take)
        val opened = ShareFixture.opened.get()
        tree.files(take)
        assertEquals(opened, ShareFixture.opened.get())
        // A new modification time is a new cache key: indexed again.
        val touched = ShareFixture.take(modifiedMs = ShareFixture.MODIFIED_MS + 1)
        tree.files(touched)
        assertEquals(opened + 1, ShareFixture.opened.get())
    }

    @Test
    fun oddCatalogRowsAreSkippedAndDuplicatesKeepTheFirst() {
        takes = listOf(
            ShareFixture.take(),
            ShareFixture.take(key = "dup"),
            ShareFixture.take(name = ".ocraw", key = "empty"),
            ShareFixture.take(name = "notes.txt", key = "txt"),
            ShareFixture.take(name = "...ocraw", key = "dots"),
            ShareFixture.take(name = "B\u0001.ocraw", key = "ctl"),
            ShareFixture.take(name = "Café.OCRAW", key = "nfd"),
        )
        val folders = tree.takeFolders()
        assertEquals(listOf(ShareFixture.TAKE, "Café"), folders.map { it.name })
        assertEquals(ShareFixture.ocraw.path, (tree.resolve(path(ShareFixture.TAKE)) as ShareCollection).take!!.key)
        // The folder is found by its NFC name, the form parseSharePath produces.
        assertNotNull(tree.resolve(path("Café")))
    }

    @Test
    fun anEmptyCatalogIsAnEmptyRoot() {
        takes = emptyList()
        assertEquals(emptyList<ShareCollection>(), tree.takeFolders())
        assertEquals(0L, tree.root().modifiedMs)
        assertNull(tree.resolve(path(ShareFixture.TAKE)))
    }

    @Test
    fun etagsDifferPerFileAndChangeWithTheTake() {
        val take = tree.takeFolders().single().take!!
        val tags = tree.files(take).map { it.etag }
        assertEquals(tags.size, tags.toSet().size)
        assertTrue(tags.all { it.startsWith("\"") && it.endsWith("\"") })
        val touched = tree.files(ShareFixture.take(modifiedMs = ShareFixture.MODIFIED_MS + 1000)).map { it.etag }
        assertTrue(tags.zip(touched).all { (a, b) -> a != b })
    }

    @Test
    fun anIndexingFailureIsRememberedFor30Seconds() {
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        val unreadable = ShareTake("bad", "C001_C001.ocraw", 4096, ShareFixture.MODIFIED_MS) {
            attempts.incrementAndGet()
            throw java.io.IOException("permission revoked")
        }
        takes = listOf(unreadable)
        val dng = path("C001_C001", "C001_C001_000000.dng")
        val before = 0
        try {
            tree.resolve(dng)
            org.junit.Assert.fail("indexed an unreadable take")
        } catch (_: ShareIndexException) {
        }
        assertEquals(before + 1, attempts.get())
        clock.advance(ShareTree.FAILURE_TTL_MS - 1)
        // The listing expired too and is read again; the take's cache key is unchanged.
        try {
            tree.resolve(dng)
            org.junit.Assert.fail()
        } catch (_: ShareIndexException) {
        }
        assertEquals("retried within the window", before + 1, attempts.get())
        clock.advance(1)
        try {
            tree.resolve(dng)
            org.junit.Assert.fail()
        } catch (_: ShareIndexException) {
        }
        assertEquals(before + 2, attempts.get())
        // Probes still miss without touching the take.
        assertNull(tree.resolve(path("C001_C001", "desktop.ini")))
    }
}
