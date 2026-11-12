/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.share

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.librestatic.opencinecam.storage.FakeExternalStorageProvider
import com.librestatic.opencinecam.storage.RecordingDestination
import com.librestatic.opencinecam.storage.SafRecordingRows
import com.librestatic.opencinecam.storage.ShadowDocumentsProviderQuery
import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** PLAN-072 Delivery 2: the network drive offers the recording drive's RAW takes beside the phone's. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], shadows = [ShadowDocumentsProviderQuery::class])
class SafShareTakesTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var drive: FakeExternalStorageProvider
    private lateinit var tree: Uri
    private var destination: RecordingDestination = RecordingDestination.Phone
    private var granted = true

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        drive = FakeExternalStorageProvider.install(temp.newFolder("drive"))
        check(drive.file("Takes/OpenCineCam/Stills").mkdirs())
        tree = drive.tree("Takes")
        destination = RecordingDestination.UsbFolder(tree, FakeExternalStorageProvider.VOLUME)
    }

    private fun source() = SafShareTakes(context.contentResolver, { destination }, { granted })
    private fun put(path: String, text: String = "raw bytes", modified: Long? = null): File =
        drive.file("Takes/$path").also { it.parentFile!!.mkdirs(); it.writeText(text); modified?.let(it::setLastModified) }
    private fun docUri(path: String) =
        DocumentsContract.buildDocumentUriUsingTree(tree, "${FakeExternalStorageProvider.VOLUME}:Takes/$path").toString()

    @Test fun listsTheFirstOcrawOfEachCommittedTakeFolderAndReadsIt() {
        put("OpenCineCam/A001/A001.ocraw", "first take", modified = 2_000_000)
        put("OpenCineCam/A001/A001.recording.json", "{}")
        put("OpenCineCam/A002/A002.ocraw", "in progress")
        File(drive.file("Takes/OpenCineCam/A002"), SafRecordingRows.MARKER).createNewFile()
        put("OpenCineCam/A003/A003.mp4", "a video take")
        put("OpenCineCam/.occ-benchmark/x.ocraw")
        put("OpenCineCam/Stills/S001/S001.ocraw")
        put("OpenCineCam/loose.ocraw")
        val take = source().takes().single()
        assertEquals(docUri("OpenCineCam/A001/A001.ocraw"), take.key)
        assertEquals("A001.ocraw", take.fileName)
        assertEquals("A001", take.stem)
        assertEquals(10L, take.size)
        assertEquals(2_000_000L, take.modifiedMs)
        take.open().use { file ->
            val bytes = ByteBuffer.allocate(10)
            file.channel.read(bytes, 0)
            assertEquals("first take", String(bytes.array()))
        }
    }

    @Test fun contributesNothingUnmountedUnconfiguredOrWithoutTheGrant() {
        put("OpenCineCam/A001/A001.ocraw")
        assertEquals(1, source().takes().size)
        drive.mounted = false
        assertEquals(emptyList<ShareTake>(), source().takes())
        drive.mounted = true
        granted = false
        assertEquals(emptyList<ShareTake>(), source().takes())
        granted = true
        destination = RecordingDestination.Phone
        assertEquals(emptyList<ShareTake>(), source().takes())
        destination = RecordingDestination.UsbFolder(tree, FakeExternalStorageProvider.VOLUME)
        check(drive.file("Takes/OpenCineCam").deleteRecursively())
        assertEquals(emptyList<ShareTake>(), source().takes())
    }

    @Test fun theCompositeMergesNewestFirstAndAnEmptyDriveLeavesThePhoneTakes() {
        val phone = listOf(ShareFixture.take(key = "content://media/1", name = "B001.ocraw", modifiedMs = 3_000),
            ShareFixture.take(key = "content://media/2", name = "B002.ocraw", modifiedMs = 1_000))
        val drive = listOf(ShareFixture.take(key = "drive:1", name = "A001.ocraw", modifiedMs = 2_000),
            ShareFixture.take(key = "content://media/1", name = "B001.ocraw", modifiedMs = 3_000))
        val merged = CompositeShareTakes(listOf(ShareTakeSource { phone }, ShareTakeSource { drive })).takes()
        assertEquals(listOf("content://media/1", "drive:1", "content://media/2"), merged.map { it.key })
        val unplugged = CompositeShareTakes(listOf(ShareTakeSource { phone }, source().also { this.drive.mounted = false })).takes()
        assertEquals(phone.map { it.key }, unplugged.map { it.key })
        assertTrue(CompositeShareTakes(emptyList()).takes().isEmpty())
    }
}
