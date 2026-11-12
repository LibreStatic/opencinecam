/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.librestatic.opencinecam.camera.BurstFrame
import com.librestatic.opencinecam.camera.CapturedBurst
import com.librestatic.opencinecam.camera.CapturedStill
import com.librestatic.opencinecam.camera.PhotoAspectSelection
import com.librestatic.opencinecam.camera.PhotoFlashMode
import com.librestatic.opencinecam.camera.PhotoFlashReport
import com.librestatic.opencinecam.camera.PhotoFlashSelection
import com.librestatic.opencinecam.camera.StillImageKind
import com.librestatic.opencinecam.camera.StillImagePayload
import java.io.File
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** PLAN-072 slice C: still groups on a drive and their journal against [FakeExternalStorageProvider]. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], shadows = [ShadowDocumentsProviderQuery::class])
class StillSafRecoveryTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var drive: FakeExternalStorageProvider
    private lateinit var tree: Uri
    private lateinit var databaseFile: File
    private val destination get() = RecordingDestination.UsbFolder(tree, FakeExternalStorageProvider.VOLUME)
    private val stills get() = drive.file("Rec/OpenCineCam/Stills")
    private val timestamp = 9_007_199_254_740_993L

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        drive = FakeExternalStorageProvider.install(temp.newFolder("drive"))
        check(drive.file("Rec").mkdir())
        tree = drive.tree("Rec")
        FakeExternalStorageProvider.grant(context, tree)
        databaseFile = File(context.noBackupFilesDir, "still-recovery.sqlite")
        databaseFile.delete()
    }

    private fun journal() = StillCaptureRecovery(context, mounted = { it.equals(drive.volume, ignoreCase = true) && drive.mounted })
    private fun saver() = StillImageSaver(context, journal())

    private fun <T> database(block: (SQLiteDatabase) -> T): T =
        SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READWRITE).use(block)

    private fun entries(): List<List<String?>> = database { db ->
        db.rawQuery("SELECT id, committed, tree, volume, folder FROM captures ORDER BY id", null).use { c ->
            buildList { while (c.moveToNext()) add((0..4).map { c.getString(it) }) }
        }
    }

    private fun read(uri: String): ByteArray = requireNotNull(context.contentResolver.openInputStream(Uri.parse(uri))).use { it.readBytes() }

    private fun still(id: Long, kinds: List<StillImageKind> = listOf(StillImageKind.JPEG, StillImageKind.DNG), time: Long = timestamp) = CapturedStill(
        captureId = id, sensorTimestampNs = time, orientationDegrees = 90, quality = 93,
        flashReport = PhotoFlashReport(PhotoFlashSelection(PhotoFlashMode.ON, 2), 1, 1, 2, 2, 3, 2, time),
        images = kinds.mapIndexed { index, kind -> StillImagePayload(kind, byteArrayOf(1, 2, (index + 3).toByte(), id.toByte()), 640, 480) },
    )

    private fun burst(count: Int = 3): CapturedBurst = CapturedBurst(timestamp, count,
        (0 until count).map { BurstFrame(it, still(it + 1L, listOf(StillImageKind.JPEG), timestamp + it * 1_000_003L), 10_000L, 100) },
        93, PhotoAspectSelection())

    /** An interrupted drive group as a dead process leaves it: journalled, its folder and marker, files as written. */
    private fun interrupted(vararg files: Pair<String, ByteArray>): String {
        assertEquals(StillRecoveryReport(), journal().recover())
        val id = UUID.randomUUID().toString()
        database { it.execSQL("INSERT INTO captures(id, committed, tree, volume) VALUES(?, 0, ?, ?)", arrayOf(id, tree.toString(), drive.volume)) }
        val rows = SafStillRows(context, tree, null) { folder ->
            database { db -> db.execSQL("UPDATE captures SET folder = ? WHERE id = ?", arrayOf(folder.toString(), id)) }
        }
        files.forEach { (name, bytes) -> rows.writeClosed(rows.insert(name, "image/jpeg", false), bytes) }
        return id
    }

    @Test fun versionOneJournalMigratesWithItsRowsStayingOnMediaStore() {
        val id = UUID.randomUUID().toString()
        SQLiteDatabase.openOrCreateDatabase(databaseFile, null).use { db ->
            db.execSQL("CREATE TABLE captures(id TEXT PRIMARY KEY NOT NULL, committed INTEGER NOT NULL CHECK(committed IN (0,1)))")
            db.execSQL("INSERT INTO captures(id, committed) VALUES(?, 0)", arrayOf(id))
            db.execSQL("INSERT INTO captures(id, committed) VALUES(?, 1)", arrayOf(UUID.randomUUID().toString()))
            db.version = 1
        }
        // The MediaStore group cannot be resolved here (no MediaStore provider under Robolectric): it is kept, not discarded.
        assertEquals(StillRecoveryReport(unresolvedGroups = 1), journal().recover())
        assertEquals(listOf(listOf(id, "0", null, null, null)), entries())
        assertEquals(2, database { it.version })

        database { it.version = 3 }
        val before = databaseFile.readBytes()
        assertThrows(IllegalStateException::class.java) { journal().recover() }
        assertThrows(IllegalStateException::class.java) { saver().saveCapture(still(1), null, null, destination) }
        assertArrayEquals(before, databaseFile.readBytes())
        assertFalse(stills.exists())
    }

    @Test fun driveStillCommitsUnderExactNamesInItsGroupFolder() {
        val capture = still(7)
        val result = saver().saveCapture(capture, null, null, destination)
        val stem = "OCC_${result.id}"
        val folder = File(stills, stem)
        assertEquals(setOf("$stem.jpg", "$stem.dng", "$stem.still.json"), folder.list()!!.toSet())
        assertFalse(File(folder, SafRecordingRows.MARKER).exists())
        assertEquals(emptyList<List<String?>>(), entries())
        result.images.forEach { image ->
            assertEquals(DocumentsContract.buildDocumentUriUsingTree(tree, "FFF1-7571:Rec/OpenCineCam/Stills/$stem/${image.displayName}").toString(), image.uri)
            assertArrayEquals(capture.images.single { it.kind == image.kind }.bytes, read(image.uri))
        }
        assertTrue(read(result.metadataUri).decodeToString().contains(result.images.first().uri))

        // A legacy JPEG gets its own group folder too.
        val jpeg = saver().saveJpeg(byteArrayOf(9, 8, 7), null, null, destination)
        val name = DocumentsContract.getDocumentId(jpeg).substringAfterLast('/')
        assertArrayEquals(byteArrayOf(9, 8, 7), File(stills, "${name.substringBefore('.')}/$name").readBytes())
        assertEquals(2, stills.list()!!.size)
        assertTrue(stills.listFiles()!!.none { File(it, SafRecordingRows.MARKER).exists() })
        assertEquals(emptyList<List<String?>>(), entries())
    }

    @Test fun driveBurstCommitsEveryFrameAndItsRelationship() {
        val capture = burst(4)
        val result = saver().saveBurst(capture, null, null, destination)
        val stem = "OCC_${result.id}"
        // The folder is named after the first file, the first frame.
        val folder = File(stills, "${stem}_01")
        assertEquals((1..4).map { "${stem}_0$it.jpg" }.toSet() + "$stem.burst.json", folder.list()!!.toSet())
        result.images.forEachIndexed { index, image -> assertArrayEquals(capture.frames[index].capture.images.single().bytes, read(image.uri)) }
        assertFalse(File(folder, SafRecordingRows.MARKER).exists())
        assertEquals(emptyList<List<String?>>(), entries())
    }

    @Test fun verificationFailsOnceTheMarkerIsGoneAndTheGroupIsDiscarded() {
        val failure = assertThrows(IllegalStateException::class.java) {
            journal().publish(destination) { id, rows ->
                val store = requireNotNull(rows)
                publishStillCapture(still(3), object : StillPublicationStore by store {
                    override fun verify(uri: String, displayName: String, mimeType: String, size: Long, sha256: String) {
                        stills.listFiles()!!.single().resolve(SafRecordingRows.MARKER).delete()
                        store.verify(uri, displayName, mimeType, size, sha256)
                    }
                }, id)
            }
        }
        assertEquals("Still group is no longer in progress", failure.message)
        assertEquals(emptyList<String>(), stills.list()!!.toList())
        assertEquals(emptyList<List<String?>>(), entries())
    }

    @Test fun verificationRejectsAlteredBytes() {
        assertThrows(IllegalStateException::class.java) {
            journal().publish(destination) { id, rows ->
                val store = requireNotNull(rows)
                publishStillCapture(still(3), object : StillPublicationStore by store {
                    override fun writeClosed(uri: String, bytes: ByteArray) = store.writeClosed(uri, bytes.copyOf().also { it[0] = 99 })
                }, id)
            }
        }
        assertEquals(emptyList<String>(), stills.list()!!.toList())
        assertEquals(emptyList<List<String?>>(), entries())
    }

    @Test fun interruptedDriveGroupIsDiscardedOnRecovery() {
        val id = interrupted("A.jpg" to byteArrayOf(1, 2), "A.still.json" to "{}".toByteArray())
        val recorded = entries().single()
        assertEquals(DocumentsContract.buildDocumentUriUsingTree(tree, "FFF1-7571:Rec/OpenCineCam/Stills/A").toString(), recorded[4])
        assertTrue(File(stills, "A/${SafRecordingRows.MARKER}").isFile)

        assertEquals(StillRecoveryReport(discardedGroups = 1), journal().recover())
        assertFalse(File(stills, "A").exists())
        assertEquals(emptyList<List<String?>>(), entries())
        assertEquals(id, recorded[0])
    }

    @Test fun absentVolumeOrLostGrantDefersTheGroupUntilTheDriveIsBack() {
        interrupted("B.jpg" to byteArrayOf(5))
        drive.mounted = false
        assertEquals(StillRecoveryReport(deferredGroups = 1), journal().recover())
        assertEquals(1, entries().size)
        // A publication on the phone or the drive still runs recovery first; the deferred group stays.
        drive.mounted = true
        context.contentResolver.releasePersistableUriPermission(tree, FakeExternalStorageProvider.READ_WRITE)
        assertEquals(StillRecoveryReport(deferredGroups = 1), journal().recover())
        assertTrue(File(stills, "B/B.jpg").isFile)

        FakeExternalStorageProvider.grant(context, tree)
        assertEquals(StillRecoveryReport(discardedGroups = 1), journal().recover())
        assertFalse(File(stills, "B").exists())
        assertEquals(emptyList<List<String?>>(), entries())
    }

    @Test fun driveThatDoesNotAnswerIsNeverReadAsEmpty() {
        interrupted("C.jpg" to byteArrayOf(5))
        drive.mounted = false
        // The volume reports mounted, but the provider answers nothing: the entry and the files stay.
        val report = StillCaptureRecovery(context, mounted = { true }).recover()
        assertEquals(StillRecoveryReport(unresolvedGroups = 1), report)
        assertEquals(1, entries().size)
        drive.mounted = true
        assertTrue(File(stills, "C/C.jpg").isFile)
        assertTrue(File(stills, "C/${SafRecordingRows.MARKER}").isFile)
    }

    @Test fun markerGoneWithAnUncommittedEntryIsOnlyPruned() {
        interrupted("D.jpg" to byteArrayOf(1, 2, 3))
        assertTrue(File(stills, "D/${SafRecordingRows.MARKER}").delete())
        assertEquals(StillRecoveryReport(), journal().recover())
        assertEquals(emptyList<List<String?>>(), entries())
        assertArrayEquals(byteArrayOf(1, 2, 3), File(stills, "D/D.jpg").readBytes())
    }

    @Test fun groupWithNoFolderJournalledLeavesOnlyItsEntry() {
        assertEquals(StillRecoveryReport(), journal().recover())
        database { it.execSQL("INSERT INTO captures(id, committed, tree, volume) VALUES(?, 0, ?, ?)", arrayOf(UUID.randomUUID().toString(), tree.toString(), drive.volume)) }
        assertEquals(StillRecoveryReport(discardedGroups = 1), journal().recover())
        assertEquals(emptyList<List<String?>>(), entries())
    }

    @Test fun mediaStoreEntriesAreUntouchedByTheDriveLogic() {
        interrupted("E.jpg" to byteArrayOf(4))
        val phone = UUID.randomUUID().toString()
        database { it.execSQL("INSERT INTO captures(id, committed) VALUES(?, 0)", arrayOf(phone)) }
        drive.mounted = false
        assertEquals(StillRecoveryReport(unresolvedGroups = 1, deferredGroups = 1), journal().recover())
        drive.mounted = true
        assertEquals(StillRecoveryReport(discardedGroups = 1, unresolvedGroups = 1), journal().recover())
        assertEquals(listOf(listOf(phone, "0", null, null, null)), entries())
        assertFalse(File(stills, "E").exists())
    }
}
