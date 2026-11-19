/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.media.MediaFormat
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * PLAN-072, ADR-0037: the side index of a drive take, its life next to the recording journal, and the moov rebuild
 * recovery runs from it before an interrupted take is published.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], shadows = [ShadowDocumentsProviderQuery::class])
class TakeIndexRecoveryTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var drive: FakeExternalStorageProvider
    private lateinit var tree: Uri
    private lateinit var databaseFile: File
    private lateinit var indexes: TakeIndexFiles
    private val destination get() = RecordingDestination.UsbFolder(tree, FakeExternalStorageProvider.VOLUME)

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        drive = FakeExternalStorageProvider.install(temp.newFolder("drive"))
        check(drive.file("Rec").mkdir())
        tree = drive.tree("Rec")
        FakeExternalStorageProvider.grant(context, tree)
        databaseFile = File(context.noBackupFilesDir, "recording-recovery.sqlite")
        databaseFile.delete()
        indexes = TakeIndexFiles(context)
        File(context.filesDir, TakeIndexFiles.FOLDER).deleteRecursively()
    }

    private fun journal() = RecordingCaptureRecovery(context) { it.equals(drive.volume, ignoreCase = true) && drive.mounted }

    private fun <T> database(block: (SQLiteDatabase) -> T): T =
        SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READWRITE).use(block)

    private fun takeDir(name: String = "A001") = drive.file("Rec/OpenCineCam/$name")
    private fun write(uri: Uri, bytes: ByteArray) =
        requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { it.write(bytes) }

    // ---- a synthetic AVC take as MPEG4Writer leaves it after a pull ---------------------------------------------------

    private fun i32(v: Int) = ByteBuffer.allocate(4).putInt(v).array()
    private val annexB = byteArrayOf(0, 0, 0, 1)
    private fun avcNal(type: Int, first: Boolean, body: Int, seed: Int) =
        i32(2 + body) + byteArrayOf((0x60 or type).toByte(), if (first) 0x88.toByte() else 0x10) + ByteArray(body) { (1 + (it + seed) % 200).toByte() }
    private fun avcAu(key: Boolean, seed: Int) = avcNal(9, false, 1, seed) + avcNal(if (key) 5 else 1, true, 70, seed)

    private class Take(val file: ByteArray, val sizes: List<Int>)

    /** ftyp, free, a 64-bit placeholder mdat, three video samples and a torn tail: no moov. */
    private fun takeWithoutMoov(): Take {
        val out = ByteArrayOutputStream()
        out.write(i32(24) + "ftyp".toByteArray() + "isom".toByteArray() + i32(0) + "isom".toByteArray() + "iso2".toByteArray())
        out.write(i32(3325) + "free".toByteArray() + ByteArray(3317))
        out.write(i32(1) + "mdat".toByteArray() + ByteArray(8) { 0x3f })
        val aus = listOf(avcAu(true, 1), avcAu(false, 2), avcAu(false, 3))
        aus.forEach { out.write(it) }
        // The torn tail opens like the next picture's first slice (type 1, first_mb 0), cut short.
        out.write(i32(1000) + byteArrayOf(0x41, 0x88.toByte()) + ByteArray(120) { 7 })
        return Take(out.toByteArray(), aus.map { it.size })
    }

    private fun writeIndex(id: String, take: Take) {
        indexes.ensureFolder()
        TakeSampleIndexWriter(indexes.fileOf(id)).use { writer ->
            writer.writeTrack(TrackKind.VIDEO, TakeTrackInfo("video/avc", 1920, 1080, 90,
                csd0 = annexB + byteArrayOf(0x67, 0x64, 0, 0x28, 0xAC.toByte()), csd1 = annexB + byteArrayOf(0x68, 0xEE.toByte())))
            take.sizes.forEachIndexed { i, size -> writer.writeSample(TrackKind.VIDEO, size, i * 33_333L, if (i == 0) 1 else 0) }
        }
    }

    private fun hasMoov(bytes: ByteArray): Boolean {
        var pos = 0L
        while (pos + 8 <= bytes.size) {
            val size32 = ByteBuffer.wrap(bytes, pos.toInt(), 4).int.toLong() and 0xFFFFFFFFL
            val type = String(bytes, pos.toInt() + 4, 4, Charsets.ISO_8859_1)
            val size = if (size32 == 1L) ByteBuffer.wrap(bytes, pos.toInt() + 8, 8).long else size32
            // Only a whole box counts: a torn moov header whose body runs past EOF is no moov.
            if (size < 8 || size > bytes.size - pos) return false
            if (type == "moov") return true
            pos += size
        }
        return false
    }

    /** An interrupted drive take as a dead process leaves it: journalled, folder and marker, outputs as written. */
    private fun interrupted(mp4: ByteArray, reason: String? = null): String {
        journal().recover()
        val id = UUID.randomUUID().toString()
        database { it.execSQL("INSERT INTO captures(id, committed, tree, volume) VALUES(?, 0, ?, ?)", arrayOf(id, tree.toString(), drive.volume)) }
        val rows = SafRecordingRows(context, tree, null) { folder ->
            database { db -> db.execSQL("UPDATE captures SET folder = ? WHERE id = ?", arrayOf(folder.toString(), id)) }
        }
        write(rows.insert(id, "DCIM", "A001.mp4", "video/mp4"), mp4)
        write(rows.insert(id, "Download", "A001.timing.json", "application/json"), """{"schema":"opencinecam.recording.v1"}""".toByteArray())
        if (reason != null) journal().markLeft(id, reason)
        return id
    }

    private fun format() = MediaFormat().apply {
        setString(MediaFormat.KEY_MIME, "video/avc")
        setInteger(MediaFormat.KEY_WIDTH, 1920)
        setInteger(MediaFormat.KEY_HEIGHT, 1080)
        setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1, 0x67)))
    }

    // ---- the index's life ---------------------------------------------------------------------------------------------

    @Test fun aDriveTakeCreatesItsIndexOnTheFirstTrackAndAPendingTakeKeepsIt() {
        val output = VideoOutput.create(context, null, null, destination)
        val id = output.recoveryGroup.id
        val listener = requireNotNull(output.sampleListener())
        // Nothing is created for a route that never calls the listener (MediaRecorder).
        assertFalse(indexes.fileOf(id).exists())
        listener.onTrack(true, format(), 90)
        listener.onSample(true, 100, 0L, 1)
        listener.onSample(true, 50, 33_333L, 0)
        output.sampleListener()!!.let { assertTrue(it === listener) }
        assertTrue(indexes.fileOf(id).isFile)

        // The take holds bytes, so closing it leaves it pending for recovery: the index stays, its writer closed.
        write(output.uri, ByteArray(64) { 1 })
        output.close()
        val kept = requireNotNull(readTakeSampleIndex(indexes.fileOf(id)))
        assertEquals(2, kept.samplesOf(TrackKind.VIDEO).size)
        assertEquals(90, kept.tracks.getValue(TrackKind.VIDEO).rotationDegrees)
        assertNull(output.sampleListener())
    }

    @Test fun aNeverRecordedDriveTakeDeletesItsIndex() {
        val output = VideoOutput.create(context, null, null, destination)
        val id = output.recoveryGroup.id
        requireNotNull(output.sampleListener()).onTrack(true, format(), 0)
        assertTrue(indexes.fileOf(id).isFile)
        output.discardNeverRecorded()
        assertFalse(indexes.fileOf(id).exists())
    }

    @Test fun commitDeletesTheIndex() {
        val group = journal().start(destination)
        writeIndex(group.id, takeWithoutMoov())
        assertTrue(indexes.fileOf(group.id).isFile)
        journal().commit(group.id)
        assertFalse(indexes.fileOf(group.id).exists())
    }

    // ---- recovery -----------------------------------------------------------------------------------------------------

    @Test fun aTakeWithoutAMoovIsRebuiltPublishedAndItsIndexDeleted() {
        val take = takeWithoutMoov()
        val id = interrupted(take.file, IncompleteTake.DRIVE_DISCONNECTED)
        writeIndex(id, take)

        assertEquals(RecordingRecoveryReport(incompleteGroups = 1), journal().recover())

        val mp4 = File(takeDir(), "A001.mp4").readBytes()
        assertTrue(hasMoov(mp4))
        val timing = File(takeDir(), "A001.timing.json").readText()
        assertEquals(listOf(IncompleteTake.DRIVE_DISCONNECTED), IncompleteTake.reasonsOf(timing))
        assertTrue(timing.contains("\"moovRebuilt\": true"))
        assertTrue(timing.contains("\"video\": 3"))
        assertFalse(File(takeDir(), SafRecordingRows.MARKER).exists())
        assertFalse(indexes.fileOf(id).exists())
    }

    @Test fun aRebuildWhosePublicationFailedStillSaysSoOnTheRetry() {
        val take = takeWithoutMoov()
        val id = interrupted(take.file, IncompleteTake.DRIVE_DISCONNECTED)
        writeIndex(id, take)
        // The moov is rebuilt, then the sidecar cannot be read: the take is not published and stays journalled.
        drive.unopenable = setOf("A001.timing.json")
        journal().recover()
        assertTrue(hasMoov(File(takeDir(), "A001.mp4").readBytes()))
        assertFalse(File(takeDir(), "A001.timing.json").readText().contains("moovRebuilt"))
        assertTrue(indexes.receiptOf(id).isFile)

        // The retry finds the moov in place (NotNeeded) and reports the rebuild from the receipt.
        drive.unopenable = emptySet()
        assertEquals(RecordingRecoveryReport(incompleteGroups = 1), journal().recover())
        val timing = File(takeDir(), "A001.timing.json").readText()
        assertTrue(timing.contains("\"moovRebuilt\": true"))
        assertTrue(timing.contains("\"video\": 3"))
        assertFalse(indexes.fileOf(id).exists())
        assertFalse(indexes.receiptOf(id).exists())
    }

    @Test fun aFinalizedFileHasNoReceiptAndIsNotLabelledRebuilt() {
        val take = takeWithoutMoov()
        val id = interrupted(take.file)
        writeIndex(id, take)
        // A receipt for another file of the take is never used for this one.
        indexes.writeReceipt(id, "content://elsewhere/other.mp4", Mp4RebuildResult.Rebuilt(9, 9, 1))
        // The muxer sized its mdat and wrote a moov after it.
        val finalized = take.file + i32(8) + "moov".toByteArray()
        val mdatStart = 24 + 3325
        ByteBuffer.wrap(finalized, mdatStart + 8, 8).putLong((take.file.size - mdatStart).toLong())
        File(takeDir(), "A001.mp4").writeBytes(finalized)
        assertEquals(RecordingRecoveryReport(incompleteGroups = 1), journal().recover())
        assertFalse(File(takeDir(), "A001.timing.json").readText().contains("moovRebuilt"))
        assertFalse(indexes.receiptOf(id).exists())
    }

    @Test fun aTakeWithoutAnIndexIsPublishedAsBefore() {
        val take = takeWithoutMoov()
        val id = interrupted(take.file)

        assertEquals(RecordingRecoveryReport(incompleteGroups = 1), journal().recover())

        assertArrayEquals(take.file, File(takeDir(), "A001.mp4").readBytes())
        val timing = File(takeDir(), "A001.timing.json").readText()
        assertEquals(listOf(IncompleteTake.PROCESS_DEATH), IncompleteTake.reasonsOf(timing))
        assertFalse(timing.contains("moovRebuilt"))
        assertFalse(indexes.fileOf(id).exists())
    }

    @Test fun anIndexThatCannotRebuildLeavesTheFileAsItWasAndStillPublishes() {
        val take = takeWithoutMoov()
        val id = interrupted(take.file)
        // Sizes that match nothing in the file: the walk finds no sample, the rebuild fails and nothing changes.
        indexes.ensureFolder()
        TakeSampleIndexWriter(indexes.fileOf(id)).use { it.writeTrack(TrackKind.VIDEO, TakeTrackInfo("video/avc", 1, 1)) }

        assertEquals(RecordingRecoveryReport(incompleteGroups = 1), journal().recover())

        assertArrayEquals(take.file, File(takeDir(), "A001.mp4").readBytes())
        assertFalse(File(takeDir(), "A001.timing.json").readText().contains("moovRebuilt"))
        assertFalse(indexes.fileOf(id).exists())
    }

    @Test fun aDeferredTakeKeepsItsIndexUntilTheDriveIsBack() {
        val take = takeWithoutMoov()
        val id = interrupted(take.file)
        writeIndex(id, take)
        drive.mounted = false
        assertEquals(RecordingRecoveryReport(deferredGroups = 1), journal().recover())
        assertTrue(indexes.fileOf(id).isFile)
        assertArrayEquals(take.file, File(takeDir(), "A001.mp4").readBytes())

        drive.mounted = true
        assertEquals(RecordingRecoveryReport(incompleteGroups = 1), journal().recover())
        assertTrue(hasMoov(File(takeDir(), "A001.mp4").readBytes()))
        assertFalse(indexes.fileOf(id).exists())
    }

    @Test fun aDiscardedTakeDeletesItsIndex() {
        // The folder was never recorded: nothing reached the drive, so the entry is discarded.
        journal().recover()
        val id = UUID.randomUUID().toString()
        database { it.execSQL("INSERT INTO captures(id, committed, tree, volume) VALUES(?, 0, ?, ?)", arrayOf(id, tree.toString(), drive.volume)) }
        writeIndex(id, takeWithoutMoov())
        assertEquals(RecordingRecoveryReport(discardedGroups = 1), journal().recover())
        assertFalse(indexes.fileOf(id).exists())
    }

    @Test fun theOrphanSweepDeletesOldIndexesWithoutAJournalRowOnly() {
        journal().recover()
        val take = takeWithoutMoov()
        val old = UUID.randomUUID().toString()
        val fresh = UUID.randomUUID().toString()
        val journalled = UUID.randomUUID().toString()
        listOf(old, fresh, journalled).forEach { writeIndex(it, take) }
        indexes.writeReceipt(old, "content://x/old.mp4", Mp4RebuildResult.Rebuilt(1, 0, 33_333))
        val longAgo = System.currentTimeMillis() - 25L * 60 * 60 * 1000
        assertTrue(indexes.fileOf(old).setLastModified(longAgo))
        assertTrue(indexes.receiptOf(old).setLastModified(longAgo))
        assertTrue(indexes.fileOf(journalled).setLastModified(longAgo))
        // The journalled take is deferred (its drive is away), so its row, and with it the index, stays.
        drive.mounted = false
        database { it.execSQL("INSERT INTO captures(id, committed, tree, volume) VALUES(?, 0, ?, ?)", arrayOf(journalled, tree.toString(), drive.volume)) }

        journal().recover()

        assertFalse(indexes.fileOf(old).exists())
        assertFalse(indexes.receiptOf(old).exists())
        assertTrue(indexes.fileOf(fresh).exists())
        assertTrue(indexes.fileOf(journalled).exists())
        assertNotNull(readTakeSampleIndex(indexes.fileOf(journalled)))
    }
}
