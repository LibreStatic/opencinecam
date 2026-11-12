/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.storage

import android.content.Context
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], shadows = [ShadowDocumentsProviderQuery::class])
class SafDocumentsTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var drive: FakeExternalStorageProvider
    private lateinit var docs: SafDocuments

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        drive = FakeExternalStorageProvider.install(temp.newFolder("drive"))
        check(drive.file("Takes").mkdir())
        docs = SafDocuments(context.contentResolver, drive.tree("Takes"))
    }

    @Test fun directoryIsFoundOrCreatedOnce() {
        val first = docs.directory(docs.root, "OpenCineCam")
        assertEquals(first, docs.directory(docs.root, "OpenCineCam"))
        assertEquals("FFF1-7571:Takes/OpenCineCam", DocumentsContract.getDocumentId(first))
        assertEquals(listOf("OpenCineCam"), docs.children(docs.root).map { it.name })
        docs.newFile(docs.root, "file")
        assertThrows(IllegalStateException::class.java) { docs.directory(docs.root, "file") }
    }

    @Test fun newDirectoryTakesASuffixWhenTheNameIsTaken() {
        assertEquals("take", docs.newDirectory(docs.root, "take").name)
        val second = docs.newDirectory(docs.root, "take")
        assertEquals("take (1)", second.name)
        assertEquals(DocumentsContract.Document.MIME_TYPE_DIR, second.mime)
        assertTrue(drive.file("Takes/take (1)").isDirectory)
    }

    @Test fun newFileKeepsTheExactNameAndRefusesATakenOne() {
        val folder = docs.directory(docs.root, "take")
        for (name in listOf("A001.mp4", "A001.timing.json", "A001.gcsv", ".occ-in-progress", "A001.wav")) {
            val file = docs.newFile(folder, name)
            assertEquals(name, file.name)
            assertEquals(0L, file.size)
            assertTrue(drive.file("Takes/take/$name").isFile)
        }
        assertEquals("video/mp4", docs.child(folder, "A001.mp4")!!.mime)
        assertThrows(IllegalStateException::class.java) { docs.newFile(folder, "A001.mp4") }
        assertFalse(drive.file("Takes/take/A001.mp4 (1)").exists())
        assertEquals(5, docs.children(folder).size)
    }

    @Test fun filesAreWritableSeekableAndDeletable() {
        val folder = docs.directory(docs.root, "take")
        val file = docs.newFile(folder, "A001.mp4")
        requireNotNull(context.contentResolver.openFileDescriptor(file.uri, "rw")).use { pfd ->
            java.io.FileOutputStream(pfd.fileDescriptor).use { out ->
                out.write(ByteArray(10)); out.channel.position(2); out.write(byteArrayOf(7))
            }
        }
        assertEquals(10L, docs.describe(file.uri)!!.size)
        assertEquals(7, drive.file("Takes/take/A001.mp4").readBytes()[2].toInt())
        requireNotNull(context.contentResolver.openOutputStream(file.uri, "wt")).use { it.write(byteArrayOf(1, 2)) }
        assertEquals(2L, docs.describe(file.uri)!!.size)
        assertTrue(docs.delete(file.uri))
        assertNull(docs.describe(file.uri))
        assertTrue(docs.delete(file.uri))
        assertTrue(docs.delete(folder))
        assertTrue(docs.children(docs.root).isEmpty())
    }

    @Test fun missingOrUnmountedDocumentsDescribeAsNull() {
        val folder = docs.directory(docs.root, "take")
        assertNull(docs.child(folder, "nothing.json"))
        drive.mounted = false
        assertNull(docs.describe(folder))
    }

    @Test fun childIdsFollowTheProvidersScheme() {
        assertEquals("FFF1-7571:OpenCineCam", SafDocuments.childDocumentId("FFF1-7571:", "OpenCineCam"))
        assertEquals("FFF1-7571:RAW/OpenCineCam", SafDocuments.childDocumentId("FFF1-7571:RAW", "OpenCineCam"))
        assertFalse(SafDocuments.isPlainName("a/b"))
        assertFalse(SafDocuments.isPlainName(".."))
    }
}
