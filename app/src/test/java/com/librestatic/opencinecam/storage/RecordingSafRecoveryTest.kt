/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.librestatic.opencinecam.media.ocraw.OcrawFrameHeader
import com.librestatic.opencinecam.media.ocraw.OcrawHeader
import com.librestatic.opencinecam.media.ocraw.OcrawStreamReader
import com.librestatic.opencinecam.media.ocraw.OcrawStreamWriter
import com.librestatic.opencinecam.media.ocraw.OcrawTimeBase
import com.librestatic.opencinecam.rawvideo.RawTakeFileDecision
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
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

/** PLAN-072 slice B: the take journal and its SAF backend against [FakeExternalStorageProvider]. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], shadows = [ShadowDocumentsProviderQuery::class])
class RecordingSafRecoveryTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var drive: FakeExternalStorageProvider
    private lateinit var tree: Uri
    private lateinit var databaseFile: File
    private val destination get() = RecordingDestination.UsbFolder(tree, FakeExternalStorageProvider.VOLUME)

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        drive = FakeExternalStorageProvider.install(temp.newFolder("drive"))
        check(drive.file("Rec").mkdir())
        tree = drive.tree("Rec")
        FakeExternalStorageProvider.grant(context, tree)
        databaseFile = File(context.noBackupFilesDir, "recording-recovery.sqlite")
        databaseFile.delete()
    }

    private fun journal() = RecordingCaptureRecovery(context) { it.equals(drive.volume, ignoreCase = true) && drive.mounted }

    private fun <T> database(block: (SQLiteDatabase) -> T): T =
        SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READWRITE).use(block)

    private fun entries(): List<List<String?>> = database { db ->
        db.rawQuery("SELECT id, committed, tree, volume, folder FROM captures ORDER BY id", null).use { c ->
            buildList { while (c.moveToNext()) add((0..4).map { c.getString(it) }) }
        }
    }

    private fun takeDir(name: String = "A001") = drive.file("Rec/OpenCineCam/$name")
    private fun write(uri: Uri, bytes: ByteArray) =
        requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { it.write(bytes) }

    /** An interrupted drive take as a dead process leaves it: journalled, its folder and marker, outputs as written. */
    private fun interrupted(vararg outputs: Pair<String, ByteArray>): String {
        assertEquals(RecordingRecoveryReport(), journal().recover())
        val id = UUID.randomUUID().toString()
        database { it.execSQL("INSERT INTO captures(id, committed, tree, volume) VALUES(?, 0, ?, ?)", arrayOf(id, tree.toString(), drive.volume)) }
        val rows = SafRecordingRows(context, tree, null) { folder ->
            database { db -> db.execSQL("UPDATE captures SET folder = ? WHERE id = ?", arrayOf(folder.toString(), id)) }
        }
        outputs.forEach { (name, bytes) ->
            val root = when (name.substringAfterLast('.')) { "mp4" -> "DCIM"; "wav", "flac" -> "Music"; else -> "Download" }
            write(rows.insert(id, root, name, "application/octet-stream"), bytes)
        }
        return id
    }

    @Test fun versionOneJournalMigratesWithItsRowsStayingOnMediaStore() {
        val id = UUID.randomUUID().toString()
        val committed = UUID.randomUUID().toString()
        SQLiteDatabase.openOrCreateDatabase(databaseFile, null).use { db ->
            db.execSQL("CREATE TABLE captures(id TEXT PRIMARY KEY NOT NULL, committed INTEGER NOT NULL CHECK(committed IN (0,1)))")
            db.execSQL("INSERT INTO captures(id, committed) VALUES(?, 0)", arrayOf(id))
            db.execSQL("INSERT INTO captures(id, committed) VALUES(?, 1)", arrayOf(committed))
            db.version = 1
        }
        // The MediaStore take cannot be resolved here (no primary volume under Robolectric): it is kept, not discarded.
        assertEquals(RecordingRecoveryReport(unresolvedGroups = 1), journal().recover())
        assertEquals(listOf(listOf(id, "0", null, null, null)), entries())
        assertEquals(2, database { it.version })

        database { it.version = 3 }
        val before = databaseFile.readBytes()
        assertThrows(IllegalStateException::class.java) { journal().recover() }
        assertArrayEquals(before, databaseFile.readBytes())
    }

    @Test fun driveTakeCommitsByRemovingItsMarker() {
        val group = journal().start(destination)
        assertEquals(listOf(listOf(group.id, "0", tree.toString(), "FFF1-7571", null)), entries())
        val video = group.member(RecordingMemberKind.VIDEO)
        val audio = group.member(RecordingMemberKind.AUDIO)
        val mp4 = video.insert("A001.mp4", "video/mp4", metadata = false)
        assertTrue(File(takeDir(), SafRecordingRows.MARKER).isFile)
        assertEquals(DocumentsContract.buildDocumentUriUsingTree(tree, "FFF1-7571:Rec/OpenCineCam/A001").toString(), entries().single()[4])
        write(mp4, ByteArray(64) { 1 })
        val timing = video.insert("A001.timing.json", "application/json", metadata = true).also { write(it, "{}".toByteArray()) }
        val clip = video.insertClipSidecar("A001.clip.json").also { write(it, "{}".toByteArray()) }
        val gyro = video.insertAuxiliary("A001.gcsv", GyroflowLog.MIME_TYPE).also { write(it, "t,x".toByteArray()) }
        val wav = audio.insert("A001.wav", "audio/wav", metadata = false).also { write(it, ByteArray(16)) }
        val audioJson = audio.insert("A001.audio.json", "application/json", metadata = true).also { write(it, "{}".toByteArray()) }

        video.prepared(listOf(PreparedCaptureArtifact(CaptureArtifactRole.VIDEO, mp4.toString(), "A001.mp4"),
            PreparedCaptureArtifact(CaptureArtifactRole.VIDEO_METADATA, timing.toString(), "A001.timing.json"),
            PreparedCaptureArtifact(CaptureArtifactRole.CLIP_METADATA, clip.toString(), "A001.clip.json")))
        listOf(gyro, timing, clip, mp4).forEach { video.publish(it, "publication failed") }
        video.published()
        // Not committed while a member is still pending.
        assertTrue(File(takeDir(), SafRecordingRows.MARKER).isFile)
        assertEquals("0", entries().single()[1])

        audio.prepared(listOf(PreparedCaptureArtifact(CaptureArtifactRole.AUDIO, wav.toString(), "A001.wav"),
            PreparedCaptureArtifact(CaptureArtifactRole.AUDIO_METADATA, audioJson.toString(), "A001.audio.json")))
        audio.publish(audioJson, "publication failed"); audio.publish(wav, "publication failed")
        audio.published()

        assertFalse(File(takeDir(), SafRecordingRows.MARKER).exists())
        assertEquals(setOf("A001.mp4", "A001.timing.json", "A001.clip.json", "A001.gcsv", "A001.wav", "A001.audio.json"),
            takeDir().list()!!.toSet())
        assertEquals(64L, File(takeDir(), "A001.mp4").length())
        assertTrue(entries().isEmpty())
        assertEquals(RecordingRecoveryReport(), journal().recover())
        assertEquals(6, takeDir().list()!!.size)
    }

    @Test fun preparationRequiresTheMarkerAndASecondTakeGetsItsOwnFolder() {
        val first = journal().start(destination)
        val member = first.member(RecordingMemberKind.VIDEO)
        val mp4 = member.insert("A001.mp4", "video/mp4", metadata = false).also { write(it, ByteArray(8)) }
        val second = journal().start(destination)
        val other = second.member(RecordingMemberKind.VIDEO)
        other.insert("A001.mp4", "video/mp4", metadata = false)
        assertTrue(File(takeDir("A001 (1)"), "A001.mp4").isFile)
        assertTrue(File(takeDir("A001 (1)"), SafRecordingRows.MARKER).isFile)

        assertTrue(File(takeDir(), SafRecordingRows.MARKER).delete())
        assertThrows(IllegalStateException::class.java) {
            member.prepared(listOf(PreparedCaptureArtifact(CaptureArtifactRole.VIDEO, mp4.toString(), "A001.mp4")))
        }
        other.abort()
        assertFalse(takeDir("A001 (1)").exists())
        assertEquals(listOf(first.id), entries().map { it[0] })
    }

    @Test fun abortedDriveTakeRemovesItsFilesMarkerAndFolder() {
        val group = journal().start(destination)
        val member = group.member(RecordingMemberKind.AUDIO)
        member.insert("B002.wav", "audio/wav", metadata = false)
        member.insert("B002.audio.json", "application/json", metadata = true)
        assertEquals(3, takeDir("B002").list()!!.size)
        member.abort()
        assertFalse(takeDir("B002").exists())
        assertTrue(drive.file("Rec/OpenCineCam").isDirectory)
        assertTrue(entries().isEmpty())
    }

    @Test fun interruptedDriveTakeWithBytesIsPublishedIncomplete() {
        val id = interrupted("A001.mp4" to ByteArray(100) { 2 }, "A001.timing.json" to """{"schema":"opencinecam.recording.v1"}""".toByteArray(),
            "A001.gcsv" to ByteArray(0), "A001.wav" to ByteArray(40) { 3 })
        assertTrue(File(takeDir(), SafRecordingRows.MARKER).isFile)

        assertEquals(RecordingRecoveryReport(incompleteGroups = 1), journal().recover())

        assertFalse(File(takeDir(), SafRecordingRows.MARKER).exists())
        assertEquals(setOf("A001.mp4", "A001.timing.json", "A001.wav", "A001.audio.incomplete.json"), takeDir().list()!!.toSet())
        assertEquals(100L, File(takeDir(), "A001.mp4").length())
        assertEquals(40L, File(takeDir(), "A001.wav").length())
        val timing = File(takeDir(), "A001.timing.json").readText()
        assertEquals(listOf(IncompleteTake.PROCESS_DEATH), IncompleteTake.reasonsOf(timing))
        assertTrue(timing.contains("\"takeStatus\": \"incomplete\""))
        assertEquals(listOf(IncompleteTake.PROCESS_DEATH), IncompleteTake.reasonsOf(File(takeDir(), "A001.audio.incomplete.json").readText()))
        assertTrue(entries().none { it[0] == id })
        assertEquals(RecordingRecoveryReport(), journal().recover())
    }

    @Test fun interruptedDriveTakeWithoutBytesLeavesNoFolder() {
        val id = interrupted("C003.mp4" to ByteArray(0), "C003.timing.json" to ByteArray(0))
        assertEquals(RecordingRecoveryReport(discardedGroups = 1), journal().recover())
        assertFalse(takeDir("C003").exists())
        assertTrue(entries().none { it[0] == id })
    }

    @Test fun takeOnAnAbsentDriveIsDeferredAndRecoveredOnceItIsBack() {
        val id = interrupted("A001.mp4" to ByteArray(10) { 1 })
        drive.mounted = false
        assertEquals(RecordingRecoveryReport(deferredGroups = 1), journal().recover())
        assertEquals(RecordingRecoveryReport(deferredGroups = 1), journal().recover())
        assertEquals("0", entries().single { it[0] == id }[1])

        // A lost folder permission defers it too: the drive's files are never judged without access.
        drive.mounted = true
        context.contentResolver.releasePersistableUriPermission(tree, FakeExternalStorageProvider.READ_WRITE)
        assertEquals(RecordingRecoveryReport(deferredGroups = 1), journal().recover())
        assertTrue(File(takeDir(), SafRecordingRows.MARKER).isFile)

        FakeExternalStorageProvider.grant(context, tree)
        assertEquals(RecordingRecoveryReport(incompleteGroups = 1), journal().recover())
        assertFalse(File(takeDir(), SafRecordingRows.MARKER).exists())
        assertEquals(setOf("A001.mp4", "A001.incomplete.json"), takeDir().list()!!.toSet())
        assertTrue(entries().isEmpty())
    }

    @Test fun takeIsRecoveredThroughTheSameFolderPickedUnderTheDrivesOtherName() {
        val id = interrupted("A001.mp4" to ByteArray(10) { 1 })
        // vold mounts the drive as FFF1-7571-01 after a cut-short mount: the recorded name is gone.
        drive.volume = "${FakeExternalStorageProvider.VOLUME}-01"
        assertEquals(RecordingRecoveryReport(deferredGroups = 1), journal().recover())
        // Another folder of the renamed drive is not the take's tree.
        check(drive.file("Other").mkdir())
        FakeExternalStorageProvider.grant(context, drive.tree("Other"))
        assertEquals(RecordingRecoveryReport(deferredGroups = 1), journal().recover())
        assertTrue(File(takeDir(), SafRecordingRows.MARKER).isFile)

        FakeExternalStorageProvider.grant(context, drive.tree("Rec"))
        assertEquals(RecordingRecoveryReport(incompleteGroups = 1), journal().recover())
        assertFalse(File(takeDir(), SafRecordingRows.MARKER).exists())
        assertEquals(setOf("A001.mp4", "A001.incomplete.json"), takeDir().list()!!.toSet())
        assertTrue(entries().none { it[0] == id })
    }

    @Test fun takeAbortedWhileItsDriveIsGoneStaysJournalledForRecovery() {
        val group = journal().start(destination)
        val member = group.member(RecordingMemberKind.VIDEO)
        write(member.insert("A001.mp4", "video/mp4", metadata = false), ByteArray(10) { 1 })
        drive.mounted = false
        // An unplugged drive answers like an empty folder; that must never pass for "nothing to keep".
        assertThrows(IllegalStateException::class.java) { member.abort() }
        assertEquals("0", entries().single()[1])
        assertEquals(RecordingRecoveryReport(deferredGroups = 1), journal().recover())
        drive.mounted = true
        assertEquals(RecordingRecoveryReport(incompleteGroups = 1), journal().recover())
        assertEquals(setOf("A001.mp4", "A001.incomplete.json"), takeDir().list()!!.toSet())
        assertTrue(entries().isEmpty())
    }

    @Test fun keptTakeIsLeftAloneUntilItsOwnerRelinquishesIt() {
        val group = journal().start(destination)
        val member = group.member(RecordingMemberKind.VIDEO)
        write(member.insert("A001.mp4", "video/mp4", metadata = false), ByteArray(10) { 1 })
        // A drive pulled mid-take: the publication failed and the output keeps its bytes, still owned by this process.
        member.keepBytes()
        drive.mounted = false
        assertEquals(RecordingRecoveryReport(), journal().recover())
        drive.mounted = true
        assertEquals(RecordingRecoveryReport(), journal().recover())
        assertTrue(File(takeDir(), SafRecordingRows.MARKER).isFile)

        // The service is done with the take: it is left pending, so a pass queued now recovers it in this same process.
        assertTrue(group.relinquish())
        assertEquals(RecordingRecoveryReport(incompleteGroups = 1), journal().recover())
        assertEquals(setOf("A001.mp4", "A001.incomplete.json"), takeDir().list()!!.toSet())
        assertTrue(entries().isEmpty())
    }

    @Test fun relinquishedTakeWaitsForItsNativeWriter() {
        val group = journal().start(destination)
        val member = group.member(RecordingMemberKind.VIDEO)
        write(member.insert("A001.mp4", "video/mp4", metadata = false), ByteArray(10) { 1 })
        val writer = group.retainNativeWriter()
        member.keepBytes()
        group.relinquish()
        assertEquals(RecordingRecoveryReport(), journal().recover())
        writer.close()
        assertEquals(RecordingRecoveryReport(incompleteGroups = 1), journal().recover())
    }

    @Test fun aCommitWhoseMarkerRemovalIsUnconfirmedIsNeverDiscardedByALaterAbort() {
        val group = journal().start(destination)
        val video = group.member(RecordingMemberKind.VIDEO)
        val mp4 = video.insert("A001.mp4", "video/mp4", metadata = false).also { write(it, ByteArray(32) { 1 }) }
        video.prepared(listOf(PreparedCaptureArtifact(CaptureArtifactRole.VIDEO, mp4.toString(), "A001.mp4")))
        video.publish(mp4, "publication failed")
        // The marker goes, then the drive leaves before its folder can be listed: the commit may or may not have happened.
        drive.afterDelete = { drive.afterDelete = null; drive.queriesBeforeUnplug = 1 }
        assertThrows(RecordingCommitUnconfirmed::class.java) { video.published() }
        // The recorder's compensation after the failed publication must not delete a take that may be committed.
        video.abort()
        assertEquals(setOf("A001.mp4"), takeDir().list()!!.toSet())
        assertEquals(32L, File(takeDir(), "A001.mp4").length())
        assertEquals(listOf(group.id), entries().map { it[0] })
        assertEquals("0", entries().single()[1])

        // The take left the live registry: the drive's return prunes the entry, the folder lists no marker.
        drive.queriesBeforeUnplug = null
        drive.mounted = true
        assertEquals(RecordingRecoveryReport(), journal().recover())
        assertTrue(entries().isEmpty())
        assertEquals(setOf("A001.mp4"), takeDir().list()!!.toSet())
    }

    @Test fun aRawTakeWhoseCommitIsUnconfirmedIsNotDeletedByALaterDiscard() {
        val output = SafRawVideoOutput.create(context, destination, recovery = journal())
        val name = output.displayName
        val folder = takeDir(name.removeSuffix(".ocraw"))
        writeOcraw(output.channel, frames = 2, finish = true)
        drive.afterDelete = { drive.afterDelete = null; drive.queriesBeforeUnplug = 1 }
        assertThrows(RecordingCommitUnconfirmed::class.java) { output.publish() }
        output.discard()
        drive.queriesBeforeUnplug = null
        drive.mounted = true
        assertEquals(setOf(name), folder.list()!!.toSet())
        assertEquals(2, scan(File(folder, name)).frameCount)
        assertEquals("0", entries().single()[1])

        assertEquals(RecordingRecoveryReport(), journal().recover())
        assertTrue(entries().isEmpty())
        assertEquals(setOf(name), folder.list()!!.toSet())
    }

    @Test fun takeWhoseMarkerIsGoneWasCommittedAndIsOnlyPruned() {
        val id = interrupted("A001.mp4" to ByteArray(10) { 1 }, "A001.timing.json" to "{}".toByteArray())
        assertTrue(File(takeDir(), SafRecordingRows.MARKER).delete())
        val before = File(takeDir(), "A001.timing.json").readBytes()
        assertEquals(RecordingRecoveryReport(), journal().recover())
        assertTrue(entries().none { it[0] == id })
        assertEquals(setOf("A001.mp4", "A001.timing.json"), takeDir().list()!!.toSet())
        assertArrayEquals(before, File(takeDir(), "A001.timing.json").readBytes())
    }

    @Test fun driveThatGoesQuietDuringRecoveryNeverDropsTheReceipt() {
        val id = interrupted("A001.mp4" to ByteArray(10) { 1 })
        val stale = RecordingCaptureRecovery(context) { true }
        // Gone before the folder is read: "no folder" is the drive not answering, not a take that never reached it.
        drive.mounted = false
        assertEquals(RecordingRecoveryReport(unresolvedGroups = 1), stale.recover())
        assertEquals("0", entries().single { it[0] == id }[1])

        // Gone between the folder and the marker: a marker that reads as missing is not a committed take.
        drive.mounted = true
        drive.queriesBeforeUnplug = 1
        assertEquals(RecordingRecoveryReport(unresolvedGroups = 1), stale.recover())
        assertEquals("0", entries().single { it[0] == id }[1])

        drive.queriesBeforeUnplug = null
        drive.mounted = true
        assertTrue(File(takeDir(), SafRecordingRows.MARKER).isFile)
        assertEquals(RecordingRecoveryReport(incompleteGroups = 1), journal().recover())
        assertTrue(entries().isEmpty())
    }

    @Test fun anUnansweredMarkerOrFolderQueryNeverDropsTheReceipt() {
        val id = interrupted("A001.mp4" to ByteArray(10) { 1 })
        // The marker's own query finds nothing, yet the folder still lists it: the take was not committed.
        drive.unanswered = setOf(SafRecordingRows.MARKER)
        assertEquals(RecordingRecoveryReport(unresolvedGroups = 1), journal().recover())
        assertEquals("0", entries().single { it[0] == id }[1])
        // The same for the take folder: its parent still lists it, so nothing is discarded.
        drive.unanswered = setOf(takeDir().name)
        assertEquals(RecordingRecoveryReport(unresolvedGroups = 1), journal().recover())
        assertEquals("0", entries().single { it[0] == id }[1])

        // Nor when its parent gives no answer either: the nearest folder that answers still lists the way to it.
        drive.unanswered = setOf(takeDir().name, takeDir().parentFile!!.name)
        assertEquals(RecordingRecoveryReport(unresolvedGroups = 1), journal().recover())
        assertEquals("0", entries().single { it[0] == id }[1])

        drive.unanswered = emptySet()
        assertTrue(File(takeDir(), SafRecordingRows.MARKER).isFile)
        assertEquals(RecordingRecoveryReport(incompleteGroups = 1), journal().recover())
        assertTrue(entries().isEmpty())
    }

    @Test fun takeWithNoFolderJournalledNeverReachedTheDrive() {
        assertEquals(RecordingRecoveryReport(), journal().recover())
        val id = UUID.randomUUID().toString()
        database { it.execSQL("INSERT INTO captures(id, committed, tree, volume) VALUES(?, 0, ?, ?)", arrayOf(id, tree.toString(), drive.volume)) }
        assertEquals(RecordingRecoveryReport(discardedGroups = 1), journal().recover())
        assertTrue(entries().isEmpty())
        assertFalse(drive.file("Rec/OpenCineCam").exists())
    }

    @Test fun mediaStoreEntriesAreLeftToTheirOwnBackend() {
        val drivePending = interrupted("A001.mp4" to ByteArray(10) { 1 })
        val phonePending = UUID.randomUUID().toString()
        val phoneCommitted = UUID.randomUUID().toString()
        database { db ->
            db.execSQL("INSERT INTO captures(id, committed) VALUES(?, 0)", arrayOf(phonePending))
            db.execSQL("INSERT INTO captures(id, committed) VALUES(?, 1)", arrayOf(phoneCommitted))
        }
        // The phone take goes through MediaStore, unavailable here, so it stays unresolved; the drive take recovers.
        assertEquals(RecordingRecoveryReport(unresolvedGroups = 1, incompleteGroups = 1), journal().recover())
        assertEquals(listOf(listOf(phonePending, "0", null, null, null)), entries())
        assertTrue(entries().none { it[0] == drivePending })
        assertNull(entries().firstOrNull { it[0] == phoneCommitted })
    }

    // PLAN-072 Delivery 3: RAW video takes on the drive.

    /** [frames] 32x8 RAW10 frames written through [channel], closed cleanly when [finish], else left as a crash leaves it. */
    private fun writeOcraw(channel: FileChannel, frames: Int, finish: Boolean) {
        val writer = OcrawStreamWriter(channel, OcrawHeader(ByteArray(16) { it.toByte() }), OcrawTimeBase(1, 1_000_000_000))
        writer.writeMetadata(mapOf("cameraId" to "0", "crop" to "0 0 32 8"), 0)
        repeat(frames) { index ->
            val header = OcrawFrameHeader.raw10(32, 8, 0, 0, 1_000L + index, index.toLong())
            writer.writeRawFrame(header, ByteBuffer.wrap(ByteArray(header.dataLength) { index.toByte() }), 1_000L + index)
        }
        if (finish) writer.finish() else writer.abort()
    }

    /** The bytes of an `.ocraw` a killed process leaves: [frames] complete frames and a torn tail. */
    private fun killedOcraw(frames: Int): ByteArray {
        val file = temp.newFile()
        RandomAccessFile(file, "rw").use { writeOcraw(it.channel, frames, finish = false) }
        return file.readBytes() + ByteArray(120) { 7 }
    }

    private fun scan(file: File) = RandomAccessFile(file, "r").use { OcrawStreamReader(it.channel).scan() }

    @Test fun rawTakeOnTheDriveKeepsItsNameAndPublishesByRemovingItsMarker() {
        val takeId = UUID.randomUUID().toString()
        val output = SafRawVideoOutput.create(context, destination, takeId = takeId, recovery = journal())
        val name = "OCC_$takeId.ocraw"
        assertEquals(name, output.displayName)
        val folder = takeDir("OCC_$takeId")
        assertEquals(setOf(name, SafRecordingRows.MARKER), folder.list()!!.toSet())
        assertTrue(RawVideoRecovery.isLive(output.uri))
        val entry = entries().single()
        assertEquals(listOf("0", tree.toString(), "FFF1-7571"), entry.subList(1, 4))
        assertEquals(DocumentsContract.buildDocumentUriUsingTree(tree, "FFF1-7571:Rec/OpenCineCam/OCC_$takeId").toString(), entry[4])
        // A take being written is never recovered under its writer.
        assertEquals(RecordingRecoveryReport(), journal().recover())

        writeOcraw(output.channel, frames = 4, finish = true)
        assertEquals(output.uri, output.publish())
        assertEquals(setOf(name), folder.list()!!.toSet())
        assertTrue(scan(File(folder, name)).cleanlyClosed)
        assertEquals(4, scan(File(folder, name)).frameCount)
        assertFalse(RawVideoRecovery.isLive(output.uri))
        assertTrue(entries().isEmpty())
        assertEquals(output.uri, output.publish())
    }

    @Test fun emptyRawTakeOnTheDriveLeavesNoFolder() {
        val output = SafRawVideoOutput.create(context, destination, recovery = journal())
        output.discard()
        assertTrue(drive.file("Rec/OpenCineCam").list()!!.isEmpty())
        assertTrue(entries().isEmpty())
        assertFalse(RawVideoRecovery.isLive(output.uri))
    }

    @Test fun rawTakeWhoseDriveWentStaysJournalledAndIsRecoveredOnItsReturn() {
        val output = SafRawVideoOutput.create(context, destination, recovery = journal())
        val name = output.displayName
        writeOcraw(output.channel, frames = 3, finish = false)
        drive.mounted = false
        // The marker cannot go: the take is relinquished, still journalled, and deferred while the drive is away.
        assertThrows(Exception::class.java) { output.publish() }
        assertFalse(RawVideoRecovery.isLive(output.uri))
        assertEquals(RecordingRecoveryReport(deferredGroups = 1), journal().recover())
        drive.mounted = true
        val report = journal().recover()
        assertEquals(RecordingRecoveryReport(rawTakes = RawVideoRecoveryReport(published = 1)), report)
        val folder = takeDir(name.removeSuffix(".ocraw"))
        assertEquals(setOf(name), folder.list()!!.toSet())
        assertTrue(scan(File(folder, name)).cleanlyClosed)
        assertEquals(3, scan(File(folder, name)).frameCount)
        assertTrue(entries().isEmpty())
    }

    @Test fun rawTakeOnTheDriveRegistersItsDescriptorsUntilItCloses() {
        val output = SafRawVideoOutput.create(context, destination, recovery = journal())
        assertEquals(1, DriveDescriptors.registered(FakeExternalStorageProvider.VOLUME))
        writeOcraw(output.channel, frames = 2, finish = true)
        output.publish()
        assertEquals(0, DriveDescriptors.registered(FakeExternalStorageProvider.VOLUME))
        assertEquals(0, DriveDescriptors.releaseVolume(FakeExternalStorageProvider.VOLUME))
    }

    @Test fun rawTakeReleasedWithItsDriveFailsItsWritesAndIsLeftForRecovery() {
        val output = SafRawVideoOutput.create(context, destination, recovery = journal())
        val name = output.displayName
        writeOcraw(output.channel, frames = 3, finish = false)
        // The drive leaves: its descriptors close at once, under the writer.
        assertEquals(1, DriveDescriptors.releaseVolume(FakeExternalStorageProvider.VOLUME.lowercase()))
        assertFalse(output.channel.isOpen)
        assertThrows(java.nio.channels.ClosedChannelException::class.java) { output.channel.write(ByteBuffer.wrap(ByteArray(4))) }
        // Even with the drive still mounted the take is not committed as is: it stays journalled for recovery.
        assertThrows(IllegalStateException::class.java) { output.publish() }
        assertFalse(RawVideoRecovery.isLive(output.uri))
        assertEquals(0, DriveDescriptors.registered(FakeExternalStorageProvider.VOLUME))
        val folder = takeDir(name.removeSuffix(".ocraw"))
        assertEquals(setOf(name, SafRecordingRows.MARKER), folder.list()!!.toSet())
        assertEquals(RecordingRecoveryReport(rawTakes = RawVideoRecoveryReport(published = 1)), journal().recover())
        assertEquals(setOf(name), folder.list()!!.toSet())
        assertEquals(3, scan(File(folder, name)).frameCount)
    }

    @Test fun interruptedRawTakeWithFramesIsRepairedAndPublished() {
        val id = interrupted("A001.ocraw" to killedOcraw(frames = 2))
        assertEquals(RecordingRecoveryReport(rawTakes = RawVideoRecoveryReport(published = 1)), journal().recover())
        assertEquals(setOf("A001.ocraw"), takeDir().list()!!.toSet())
        val repaired = scan(File(takeDir(), "A001.ocraw"))
        assertTrue(repaired.errors.toString(), repaired.cleanlyClosed)
        assertEquals(2, repaired.frameCount)
        assertTrue(entries().none { it[0] == id })
        assertEquals(RecordingRecoveryReport(), journal().recover())
    }

    @Test fun aTakeStartLeavesAnInterruptedRawTakeToALaterPass() {
        val id = interrupted("A001.ocraw" to killedOcraw(frames = 2))
        // A take starts on the main thread: its pass does not scan the RAW file.
        journal().start(destination).relinquish()
        assertEquals(setOf("A001.ocraw", SafRecordingRows.MARKER), takeDir().list()!!.toSet())
        assertTrue(entries().any { it[0] == id })
        // Nor does a RAW take's start on the drive.
        journal().startRaw(destination).discard()
        assertEquals(setOf("A001.ocraw", SafRecordingRows.MARKER), takeDir().list()!!.toSet())
        assertTrue(entries().any { it[0] == id })
        assertEquals(RawVideoRecoveryReport(published = 1), journal().recover().rawTakes)
        assertEquals(setOf("A001.ocraw"), takeDir().list()!!.toSet())
    }

    @Test fun interruptedRawTakeWithoutFramesIsDiscarded() {
        val id = interrupted("A001.ocraw" to killedOcraw(frames = 0))
        assertEquals(RecordingRecoveryReport(rawTakes = RawVideoRecoveryReport(deleted = 1)), journal().recover())
        assertFalse(takeDir().exists())
        assertTrue(entries().none { it[0] == id })
    }

    @Test fun interruptedRawTakeOnAnAbsentDriveIsDeferred() {
        val bytes = killedOcraw(frames = 1)
        interrupted("A001.ocraw" to bytes)
        drive.mounted = false
        assertEquals(RecordingRecoveryReport(deferredGroups = 1), journal().recover())
        drive.mounted = true
        context.contentResolver.releasePersistableUriPermission(tree, FakeExternalStorageProvider.READ_WRITE)
        assertEquals(RecordingRecoveryReport(deferredGroups = 1), journal().recover())
        // Untouched while deferred: neither repaired nor deleted.
        assertArrayEquals(bytes, File(takeDir(), "A001.ocraw").readBytes())
        assertTrue(File(takeDir(), SafRecordingRows.MARKER).isFile)
        FakeExternalStorageProvider.grant(context, tree)
        assertEquals(RecordingRecoveryReport(rawTakes = RawVideoRecoveryReport(published = 1)), journal().recover())
    }

    @Test fun driveRawRepairRunsWithTheJournalUnlocked() {
        val id = interrupted("A001.ocraw" to killedOcraw(frames = 2))
        val scanning = CountDownLatch(1)
        val resume = CountDownLatch(1)
        // The scan of a take of tens of GB on USB, held until the test lets it finish.
        val repairing = journal().apply { repairRaw = { _, _ -> scanning.countDown(); check(resume.await(30, TimeUnit.SECONDS)); RawTakeFileDecision.PUBLISH } }
        val threads = Executors.newFixedThreadPool(2)
        try {
            val pass = threads.submit<RecordingRecoveryReport> { repairing.recover() }
            assertTrue(scanning.await(30, TimeUnit.SECONDS))
            // A new take starts meanwhile; its own recovery pass leaves the take being repaired alone.
            val next = threads.submit<RecordingRecoveryGroup> { journal().start(destination) }.get(30, TimeUnit.SECONDS)
            assertEquals(RecordingRecoveryReport(), journal().recover())
            assertFalse(pass.isDone)
            assertTrue(File(takeDir(), SafRecordingRows.MARKER).isFile)
            assertEquals(setOf(id, next.id), entries().map { it[0] }.toSet())

            resume.countDown()
            assertEquals(RecordingRecoveryReport(rawTakes = RawVideoRecoveryReport(published = 1)), pass.get(30, TimeUnit.SECONDS))
            assertFalse(File(takeDir(), SafRecordingRows.MARKER).exists())
            assertEquals(listOf(next.id), entries().map { it[0] })
            // The repairing mark went with the pass: nothing is left for the next one.
            assertEquals(RecordingRecoveryReport(), journal().recover())
            next.relinquish()
        } finally {
            resume.countDown()
            threads.shutdownNow()
        }
    }

    @Test fun failedDriveRawRepairStaysJournalledForTheNextPass() {
        val id = interrupted("A001.ocraw" to killedOcraw(frames = 1))
        val failing = journal().apply { repairRaw = { _, _ -> throw java.io.IOException("drive left mid-scan") } }
        assertEquals(RecordingRecoveryReport(unresolvedGroups = 1), failing.recover())
        assertTrue(File(takeDir(), SafRecordingRows.MARKER).isFile)
        assertEquals("0", entries().single { it[0] == id }[1])
        assertEquals(RecordingRecoveryReport(rawTakes = RawVideoRecoveryReport(published = 1)), journal().recover())
        assertTrue(entries().isEmpty())
    }

    @Test fun rawRepairWaitsWhileATakeRecordsToADriveAndIsDueWhenItEnds() {
        val id = interrupted("A001.ocraw" to killedOcraw(frames = 2))
        val due = java.util.concurrent.atomic.AtomicInteger()
        RecordingCaptureRecovery.onRawRepairDue = { due.incrementAndGet() }
        try {
            val take = journal().start(destination)
            // The scan would read the drive the take writes to: the entry waits, untouched.
            val waiting = journal().apply { repairRaw = { _, _ -> error("scanned beside a live drive take") } }
            assertEquals(RecordingRecoveryReport(deferredGroups = 1), waiting.recover())
            assertTrue(File(takeDir(), SafRecordingRows.MARKER).isFile)
            assertEquals(setOf(id, take.id), entries().map { it[0] }.toSet())
            assertEquals(0, due.get())

            take.relinquish()
            assertEquals(1, due.get())
            assertEquals(1, journal().recover().rawTakes.published)
        } finally {
            RecordingCaptureRecovery.onRawRepairDue = null
        }
    }

    @Test fun aTakeStartingOnADriveCancelsARawRepairInFlight() {
        val id = interrupted("A001.ocraw" to killedOcraw(frames = 2))
        val scanning = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val due = java.util.concurrent.atomic.AtomicInteger()
        RecordingCaptureRecovery.onRawRepairDue = { due.incrementAndGet() }
        // A scan reading through a descriptor the pass holds: it ends when that is closed under it.
        val repairing = journal().apply {
            repairRaw = { _, scan ->
                scan.hold(AutoCloseable { closed.countDown() })
                scanning.countDown()
                check(closed.await(30, TimeUnit.SECONDS))
                throw java.io.IOException("descriptor closed mid-scan")
            }
        }
        val threads = Executors.newSingleThreadExecutor()
        try {
            val pass = threads.submit<RecordingRecoveryReport> { repairing.recover() }
            assertTrue(scanning.await(30, TimeUnit.SECONDS))
            val take = journal().start(destination)
            // Cancelled, not failed: the entry is deferred with its bytes and marker in place.
            assertEquals(RecordingRecoveryReport(deferredGroups = 1), pass.get(30, TimeUnit.SECONDS))
            assertTrue(File(takeDir(), SafRecordingRows.MARKER).isFile)
            assertEquals(setOf(id, take.id), entries().map { it[0] }.toSet())

            take.relinquish()
            assertEquals(1, due.get())
            assertEquals(1, journal().recover().rawTakes.published)
        } finally {
            RecordingCaptureRecovery.onRawRepairDue = null
            threads.shutdownNow()
        }
    }

    @Test fun aRawRepairScanCancelledBeforeItOpensClosesWhatItIsGiven() {
        val scan = RawRepairScan().apply { cancel() }
        var closes = 0
        assertThrows(java.io.IOException::class.java) { scan.hold(AutoCloseable { closes++ }) }
        assertEquals(1, closes)
        assertTrue(scan.cancelled)
    }

    @Test fun takesDeferredOnALostDriveNeitherBlockTheJournalNorFillIt() {
        assertEquals(RecordingRecoveryReport(), journal().recover())
        // A drive that never came back (lost, reformatted): its takes are deferred for good.
        val lostVolume = "1234-ABCD"
        val lost = DocumentsContract.buildTreeDocumentUri(FakeExternalStorageProvider.AUTHORITY, "$lostVolume:Rec")
        val deferred = List(RecordingCaptureRecovery.MAX_DRIVE_TAKES) { UUID.randomUUID().toString() }
        val phone = List(4) { UUID.randomUUID().toString() }
        database { db ->
            deferred.forEach { db.execSQL("INSERT INTO captures(id, committed, tree, volume) VALUES(?, 0, ?, ?)", arrayOf(it, lost.toString(), lostVolume)) }
            phone.forEach { db.execSQL("INSERT INTO captures(id, committed) VALUES(?, 0)", arrayOf(it)) }
        }
        // More entries than one bound: the journal still opens and recovers. The phone takes stay unresolved here only
        // because Robolectric has no MediaStore volume; they count against the phone bound alone.
        assertEquals(RecordingRecoveryReport(unresolvedGroups = phone.size, deferredGroups = deferred.size), journal().recover())

        // A drive take with the drive bound full forgets the oldest deferred entry, and only the entry.
        val take = journal().start(destination)
        assertEquals(deferred.drop(1).toSet() + phone + take.id, entries().map { it[0] }.toSet())
        take.relinquish()

        // An entry without a folder goes before an older one with a folder: nothing of it reached a drive.
        val lostFolder = DocumentsContract.buildDocumentUriUsingTree(lost, "$lostVolume:Rec/OpenCineCam/A001")
        val foldered = deferred.drop(1).take(3)
        database { db -> foldered.forEach { db.execSQL("UPDATE captures SET folder = ? WHERE id = ?", arrayOf(lostFolder.toString(), it)) } }
        val second = journal().start(destination)
        assertTrue(entries().map { it[0] }.containsAll(foldered))
        assertFalse(deferred[4] in entries().map { it[0] })
        second.relinquish()

        // An entry a pass could still resolve is never forgotten: on a drive that reads as mounted but does not answer,
        // every entry stays unresolved, and with the bound full the next drive take is refused.
        val folder = DocumentsContract.buildDocumentUriUsingTree(tree, "${drive.volume}:Rec/OpenCineCam/A001")
        val pinned = List(RecordingCaptureRecovery.MAX_DRIVE_TAKES) { UUID.randomUUID().toString() }
        database { db ->
            db.execSQL("DELETE FROM captures WHERE tree IS NOT NULL")
            pinned.forEach { db.execSQL("INSERT INTO captures(id, committed, tree, volume, folder) VALUES(?, 0, ?, ?, ?)",
                arrayOf(it, tree.toString(), drive.volume, folder.toString())) }
        }
        drive.mounted = false
        assertThrows(IllegalStateException::class.java) { RecordingCaptureRecovery(context) { true }.start(destination) }
        assertEquals(pinned.toSet(), entries().filter { it[2] != null }.map { it[0] }.toSet())
    }

    @Test fun qualificationScratchPurgeTouchesOnlyItsHiddenFolder() {
        val take = interrupted("A001.mp4" to ByteArray(10) { 1 })
        val scratch = RawQualificationDriveScratch(context.contentResolver, tree)
        val live = scratch.create("sweep")
        val orphan = scratch.create("long-0")
        RawVideoRecovery.release(orphan)
        val scratchDir = drive.file("Rec/OpenCineCam/${RawQualificationDriveScratch.FOLDER}")
        assertEquals(2, scratchDir.list()!!.size)
        assertTrue(scratchDir.list()!!.all { it.startsWith(RawVideoQualificationRows.NAME_PREFIX) && it.endsWith(".ocraw") })
        File(drive.file("Rec"), "notes.ocraw").writeText("mine")
        assertEquals(64L shl 30, scratch.freeBytes())

        // The orphan goes; the scratch being written, the interrupted take and the user's own files stay.
        assertEquals(1, scratch.purgeOrphans())
        assertEquals(1, scratchDir.list()!!.size)
        assertTrue(File(drive.file("Rec"), "notes.ocraw").isFile)
        assertTrue(File(takeDir(), "A001.mp4").isFile)
        assertTrue(entries().any { it[0] == take })
        scratch.delete(live)
        assertFalse(RawVideoRecovery.isLive(live))
        assertTrue(scratchDir.list()!!.isEmpty())
        // A drive that does not answer purges nothing, and one never qualified has no folder to purge.
        drive.mounted = false
        assertEquals(0, scratch.purgeOrphans())
        drive.mounted = true
        assertEquals(0, RawQualificationDriveScratch(context.contentResolver, drive.tree()).purgeOrphans())
    }
}
