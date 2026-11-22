/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.librestatic.opencinecam.motion.MotionSpoolFormat
import com.librestatic.opencinecam.motion.MotionSpoolWriter
import com.librestatic.opencinecam.motion.MotionTakeInfo
import com.librestatic.opencinecam.motion.MotionTakeRecorder
import com.librestatic.opencinecam.motion.MotionTakeRoute
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import org.junit.After
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
 * A video take whose process died leaves its motion spool in `filesDir/motion`: recovery renders it into the take's
 * `<video stem>.gcsv` and publishes it with the take, and deletes the spool once the take is resolved. Drive takes, since
 * Robolectric has no primary MediaStore volume; phone takes go through the same [RecordingCaptureRecovery] step.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], shadows = [ShadowDocumentsProviderQuery::class])
class MotionLogRecoveryTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var drive: FakeExternalStorageProvider
    private lateinit var tree: Uri
    private lateinit var databaseFile: File
    private lateinit var motion: MotionRecoveryFiles

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        drive = FakeExternalStorageProvider.install(temp.newFolder("drive"))
        check(drive.file("Rec").mkdir())
        tree = drive.tree("Rec")
        FakeExternalStorageProvider.grant(context, tree)
        databaseFile = File(context.noBackupFilesDir, "recording-recovery.sqlite")
        databaseFile.delete()
        File(context.filesDir, MotionTakeRecorder.SPOOL_DIRECTORY).deleteRecursively()
        File(context.cacheDir, MotionTakeRecorder.GCSV_DIRECTORY).deleteRecursively()
        motion = MotionRecoveryFiles(context)
    }

    private fun journal() = RecordingCaptureRecovery(context) { it.equals(drive.volume, ignoreCase = true) && drive.mounted }

    private fun <T> database(block: (SQLiteDatabase) -> T): T =
        SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READWRITE).use(block)

    private fun takeDir() = drive.file("Rec/OpenCineCam/A001")
    private fun write(uri: Uri, bytes: ByteArray) =
        requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { it.write(bytes) }

    /** An interrupted drive take as a dead process leaves it: journalled, folder and marker, its video [mp4] bytes. */
    private fun interrupted(mp4: ByteArray = ByteArray(64) { 1 }): String {
        journal().recover()
        val id = UUID.randomUUID().toString()
        database { it.execSQL("INSERT INTO captures(id, committed, tree, volume) VALUES(?, 0, ?, ?)", arrayOf(id, tree.toString(), drive.volume)) }
        val rows = SafRecordingRows(context, tree, null) { folder ->
            database { db -> db.execSQL("UPDATE captures SET folder = ? WHERE id = ?", arrayOf(folder.toString(), id)) }
        }
        write(rows.insert(id, "DCIM", "A001.mp4", "video/mp4"), mp4)
        write(rows.insert(id, "Download", "A001.timing.json", "application/json"), """{"schema":"opencinecam.recording.v1"}""".toByteArray())
        return id
    }

    private fun info(id: String, route: MotionTakeRoute = MotionTakeRoute.GPU) = MotionTakeInfo(
        takeId = id, videoFileName = "A001.mp4", orientation = "yxz", readoutDirection = 0, frontFacing = false,
        sensorOrientationDegrees = 90, storedRotationDegrees = 0, cameraClockRealtime = true, clockOffsetNs = 0L,
        startedAtUnixMs = 1_700_000_000_000L, firmware = "OpenCineCam test", route = route, captureFps = 30.0,
        gyroMaxHz = 200.0, accelerometer = true, accelMaxHz = 100.0,
    )

    /** One second at 30 fps with the gyroscope at 200 Hz, as a dead process leaves it (flushed, never closed). */
    private fun spool(id: String, route: MotionTakeRoute = MotionTakeRoute.GPU, frames: Int = 30) {
        motion.spoolOf(id).parentFile!!.mkdirs()
        val startNs = 40_000_000_000L
        val frameNs = 33_333_333L
        MotionSpoolWriter(FileOutputStream(motion.spoolOf(id))).apply {
            exposure(startNs, 10_000_000, 9_000_000)
            for (i in 0 until frames) frame(startNs + i * frameNs, 5_000_000_000L + i * frameNs)
            for (i in 0 until 190) {
                val t = startNs + 1_000_000L + i * 5_000_000L
                sample(MotionSpoolFormat.KIND_GYRO, t, 0.1f, 0.2f, 0.3f)
                if (i % 2 == 0) sample(MotionSpoolFormat.KIND_ACCEL, t, 0f, 9.8f, 0f)
            }
            flush()
        }
        MotionTakeRecorder.writeInfo(motion.infoOf(id), info(id, route))
    }

    private fun assertMotionFilesGone(id: String) {
        assertFalse(motion.spoolOf(id).exists())
        assertFalse(motion.infoOf(id).exists())
        assertFalse(motion.renderedOf(id).exists())
    }

    @Test fun anInterruptedTakeKeepsAGyroflowLogRenderedFromItsSpool() {
        val id = interrupted()
        spool(id)

        assertEquals(1, journal().recover().incompleteGroups)

        val log = File(takeDir(), "A001.gcsv")
        assertTrue(log.isFile)
        val lines = log.readLines()
        assertEquals("GYROFLOW IMU LOG", lines.first())
        val rows = lines.drop(lines.indexOf("t,gx,gy,gz,ax,ay,az") + 1).filter { it.isNotEmpty() }
        assertEquals(190, rows.size)
        // No muxer offset after a crash: the first frame is at file time 0, the first gyro sample 1 ms after it.
        assertEquals(1_000L, rows.first().substringBefore(',').toLong())
        // Published with the take: the marker went, the log is no empty row.
        assertFalse(File(takeDir(), SafRecordingRows.MARKER).exists())
        assertEquals(setOf("A001.mp4", "A001.timing.json", "A001.gcsv"), takeDir().list()!!.toSet())
        assertMotionFilesGone(id)
    }

    @Test fun aLogLeftHalfWrittenByTheDeadStopIsRewritten() {
        val id = interrupted()
        SafRecordingRows(context, tree, Uri.parse(database { db ->
            db.rawQuery("SELECT folder FROM captures WHERE id = ?", arrayOf(id)).use { it.moveToFirst(); it.getString(0) }
        })) { error("no folder") }.insert(id, "Download", "A001.gcsv", GyroflowLog.MIME_TYPE).also { write(it, "GYROFLOW IMU LOG\nt,g".toByteArray()) }
        spool(id)

        assertEquals(1, journal().recover().incompleteGroups)

        assertEquals(setOf("A001.mp4", "A001.timing.json", "A001.gcsv"), takeDir().list()!!.toSet())
        assertTrue(File(takeDir(), "A001.gcsv").readLines().size > 190)
        assertMotionFilesGone(id)
    }

    @Test fun aMediaRecorderTakeCannotBePlacedAfterACrashAndOnlyLosesItsLog() {
        val id = interrupted()
        spool(id, MotionTakeRoute.MEDIA_RECORDER)

        assertEquals(1, journal().recover().incompleteGroups)

        assertEquals(setOf("A001.mp4", "A001.timing.json"), takeDir().list()!!.toSet())
        assertMotionFilesGone(id)
    }

    @Test fun aSpoolWithABadHeaderOrABadDescriptionOnlyLosesTheLog() {
        val bad = interrupted()
        motion.spoolOf(bad).parentFile!!.mkdirs()
        motion.spoolOf(bad).writeBytes(ByteArray(256) { 7 })
        MotionTakeRecorder.writeInfo(motion.infoOf(bad), info(bad))

        assertEquals(1, journal().recover().incompleteGroups)
        assertEquals(setOf("A001.mp4", "A001.timing.json"), takeDir().list()!!.toSet())
        assertMotionFilesGone(bad)

        assertTrue(takeDir().deleteRecursively())
        val torn = interrupted()
        spool(torn)
        motion.infoOf(torn).writeText("{\"schema\":", Charsets.UTF_8)
        assertEquals(1, journal().recover().incompleteGroups)
        assertEquals(setOf("A001.mp4", "A001.timing.json"), takeDir().list()!!.toSet())
        assertMotionFilesGone(torn)
    }

    @Test fun aTornSpoolTailIsCutAndTheRestStillRenders() {
        val id = interrupted()
        spool(id)
        motion.spoolOf(id).let { file -> file.writeBytes(file.readBytes().let { it.copyOf(it.size - 7) }) }

        assertEquals(1, journal().recover().incompleteGroups)

        assertTrue(File(takeDir(), "A001.gcsv").length() > 0L)
        assertMotionFilesGone(id)
    }

    @Test fun aSpoolWithOneFrameRendersNothing() {
        val id = interrupted()
        spool(id, frames = 1)

        assertEquals(1, journal().recover().incompleteGroups)

        assertFalse(File(takeDir(), "A001.gcsv").exists())
        assertMotionFilesGone(id)
    }

    @Test fun aDiscardedTakeOnlyDeletesItsSpool() {
        val id = interrupted(mp4 = ByteArray(0))
        spool(id)

        assertEquals(1, journal().recover().discardedGroups)

        assertFalse(takeDir().exists())
        assertMotionFilesGone(id)
    }

    @Test fun aDeferredTakeKeepsItsSpoolAndItsLogForTheNextPass() {
        val id = interrupted()
        spool(id)
        drive.mounted = false

        assertEquals(1, journal().recover().deferredGroups)
        assertTrue(motion.spoolOf(id).isFile)
        assertTrue(motion.infoOf(id).isFile)
        assertNotNull(motion.rendered(id))

        drive.mounted = true
        assertEquals(1, journal().recover().incompleteGroups)
        assertTrue(File(takeDir(), "A001.gcsv").isFile)
        assertMotionFilesGone(id)
    }

    @Test fun aLiveTakesSpoolIsNeverRenderedNorSwept() {
        val group = journal().start(RecordingDestination.UsbFolder(tree, FakeExternalStorageProvider.VOLUME))
        spool(group.id)
        val old = System.currentTimeMillis() - 2 * TakeIndexFiles.ORPHAN_AGE_MS
        listOf(motion.spoolOf(group.id), motion.infoOf(group.id)).forEach { it.setLastModified(old) }
        // An orphan as old, of a take the journal no longer knows, goes.
        val orphan = UUID.randomUUID().toString()
        spool(orphan)
        listOf(motion.spoolOf(orphan), motion.infoOf(orphan)).forEach { it.setLastModified(old) }

        journal().recover()

        assertTrue(motion.spoolOf(group.id).isFile)
        assertTrue(motion.infoOf(group.id).isFile)
        assertNull(motion.rendered(group.id))
        assertFalse(motion.spoolOf(orphan).exists())
        assertFalse(motion.infoOf(orphan).exists())
    }

    // A take starting while the full pass still renders: the take-start pass must not resolve a phone take without its log.

    /**
     * An interrupted phone take (a journal entry without a tree) and the folder its rows stand in: Robolectric has no
     * MediaStore volume, so the journal recovers phone takes from a drive folder instead ([phoneJournal]).
     */
    private fun interruptedOnPhone(): Pair<String, Uri> {
        journal().recover()
        val id = UUID.randomUUID().toString()
        database { it.execSQL("INSERT INTO captures(id, committed) VALUES(?, 0)", arrayOf(id)) }
        var folder: Uri? = null
        val rows = SafRecordingRows(context, tree, null) { folder = it }
        write(rows.insert(id, "DCIM", "A001.mp4", "video/mp4"), ByteArray(64) { 1 })
        write(rows.insert(id, "Download", "A001.timing.json", "application/json"), """{"schema":"opencinecam.recording.v1"}""".toByteArray())
        return id to checkNotNull(folder)
    }

    private fun phoneJournal(folder: Uri) = journal().also { it.phoneRows = SafRecordingRows(context, tree, folder) { error("no folder") } }

    private fun pending(id: String): Boolean = database { db ->
        db.rawQuery("SELECT committed FROM captures WHERE id = ?", arrayOf(id)).use { it.moveToFirst() && it.getLong(0) == 0L }
    }

    private var renderDue = 0

    @Before fun countRenderDue() {
        renderDue = 0
        RecordingCaptureRecovery.onMotionRenderDue = { renderDue++ }
    }

    @After fun clearRenderDue() {
        RecordingCaptureRecovery.onMotionRenderDue = null
    }

    @Test fun aSpoolAwaitsItsRenderUntilOneIsTried() {
        assertFalse(motion.awaitsRender(UUID.randomUUID().toString()))

        val withoutInfo = UUID.randomUUID().toString()
        spool(withoutInfo)
        assertTrue(motion.infoOf(withoutInfo).delete())
        assertFalse(motion.awaitsRender(withoutInfo))

        val waiting = UUID.randomUUID().toString()
        spool(waiting)
        assertTrue(motion.awaitsRender(waiting))
        assertNotNull(motion.render(waiting))
        assertFalse(motion.awaitsRender(waiting))

        // A log already there, rendered by an earlier process.
        val rendered = UUID.randomUUID().toString()
        spool(rendered)
        motion.renderedOf(rendered).apply { parentFile!!.mkdirs(); writeText("GYROFLOW IMU LOG\n") }
        assertFalse(motion.awaitsRender(rendered))

        // A render that gave nothing is not waited for again.
        val failed = UUID.randomUUID().toString()
        spool(failed, MotionTakeRoute.MEDIA_RECORDER)
        assertTrue(motion.awaitsRender(failed))
        assertNull(motion.render(failed))
        assertFalse(motion.awaitsRender(failed))
        assertTrue(motion.spoolOf(failed).isFile)
    }

    @Test fun aTakeStartingBeforeTheRenderLeavesThePhoneTakeForTheFullPass() {
        val (id, folder) = interruptedOnPhone()
        spool(id)

        val take = phoneJournal(folder).start(RecordingDestination.UsbFolder(tree, FakeExternalStorageProvider.VOLUME))

        // Deferred: entry, rows, marker, spool and description intact, nothing rendered on the main thread.
        assertTrue(pending(id))
        assertEquals(setOf("A001.mp4", "A001.timing.json", SafRecordingRows.MARKER), takeDir().list()!!.toSet())
        assertTrue(motion.spoolOf(id).isFile)
        assertTrue(motion.infoOf(id).isFile)
        assertNull(motion.rendered(id))
        assertEquals(1, renderDue)
        take.relinquish()

        // The pass it queued renders the log, attaches it and resolves the take, and queues nothing more.
        assertEquals(1, phoneJournal(folder).recover().incompleteGroups)
        assertFalse(pending(id))
        assertEquals(setOf("A001.mp4", "A001.timing.json", "A001.gcsv"), takeDir().list()!!.toSet())
        assertTrue(File(takeDir(), "A001.gcsv").readLines().size > 190)
        assertMotionFilesGone(id)
        assertEquals(1, renderDue)
    }

    @Test fun aSpoolThatCannotRenderIsResolvedWithoutItsLogByTheFullPass() {
        val (id, folder) = interruptedOnPhone()
        spool(id, MotionTakeRoute.MEDIA_RECORDER)

        val take = phoneJournal(folder).start(RecordingDestination.UsbFolder(tree, FakeExternalStorageProvider.VOLUME))
        assertTrue(pending(id))
        assertEquals(1, renderDue)
        take.relinquish()

        assertEquals(1, phoneJournal(folder).recover().incompleteGroups)
        assertFalse(pending(id))
        assertEquals(setOf("A001.mp4", "A001.timing.json"), takeDir().list()!!.toSet())
        assertMotionFilesGone(id)
        assertEquals(1, renderDue)
    }

    @Test fun aTakeStartAfterAFailedRenderResolvesThePhoneTakeAtOnce() {
        val (id, folder) = interruptedOnPhone()
        spool(id, MotionTakeRoute.MEDIA_RECORDER)
        assertNull(motion.render(id))

        val take = phoneJournal(folder).start(RecordingDestination.UsbFolder(tree, FakeExternalStorageProvider.VOLUME))

        assertFalse(pending(id))
        assertEquals(setOf("A001.mp4", "A001.timing.json"), takeDir().list()!!.toSet())
        assertMotionFilesGone(id)
        assertEquals(0, renderDue)
        take.relinquish()
    }
}
