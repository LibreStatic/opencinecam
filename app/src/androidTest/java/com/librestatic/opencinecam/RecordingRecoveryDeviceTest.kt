/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.storage.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

/** Native SQLite/MediaStore ownership protocol, not codec or playable-media qualification.
 * Every database and namespace belongs to this fixture; no global recovery database is edited. */
class RecordingRecoveryDeviceTest {
    @Test fun durableReservationExistsBeforeTheFirstProviderInsert() = Fixture().use { f ->
        val group = f.group()
        assertEquals(0L, f.state(group.id))
        assertTrue(f.journal.allRows(group.id).isEmpty())
        assertEquals(RecordingRecoveryReport(), RecordingCaptureRecovery(f.context).recover())
        val member = f.member(group, RecordingMemberKind.VIDEO)
        val row = f.output(member, "before-insert.mp4", "video/mp4", CaptureArtifactRole.VIDEO)
        assertEquals(setOf(row.uri), f.journal.allRows(group.id))
        assertEquals(0L, f.state(group.id))
        member.abort()
        assertNull(f.state(group.id)); assertTrue(f.journal.allRows(group.id).isEmpty())
    }

    @Test fun twoInstancesSkipAbortedLiveGroupUntilEveryMemberAndNativeGuardRetire() = Fixture().use { f ->
        val group = f.group()
        val video = f.member(group, RecordingMemberKind.VIDEO)
        val audio = f.member(group, RecordingMemberKind.AUDIO)
        val guard = f.guard(group)
        val rows = listOf(f.output(video, "held.mp4", "video/mp4", CaptureArtifactRole.VIDEO),
            f.output(audio, "held.wav", "audio/wav", CaptureArtifactRole.AUDIO),
            f.output(audio, "held.audio.json", "application/json", CaptureArtifactRole.AUDIO_METADATA))
        val before = rows.associate { it.uri to f.digest(it.uri) }
        video.abort()
        assertEquals(RecordingRecoveryReport(), RecordingCaptureRecovery(f.context).recover())
        assertEquals(before.keys, f.journal.allRows(group.id))
        audio.abort()
        assertEquals(RecordingRecoveryReport(), RecordingCaptureRecovery(f.context).recover())
        assertEquals(0L, f.state(group.id))
        before.forEach { (uri, digest) -> assertEquals(digest, f.digest(uri)) }
        guard.close()
        assertNull(f.state(group.id)); assertTrue(f.journal.allRows(group.id).isEmpty())
        guard.close(); video.abort(); audio.abort()
        assertEquals(RecordingRecoveryReport(), f.journal.recover())
    }

    @Test fun oneRetiredMemberCannotDeleteTheOtherMembersOpenOutput() = Fixture().use { f ->
        val group = f.group()
        val video = f.member(group, RecordingMemberKind.VIDEO)
        val audio = f.member(group, RecordingMemberKind.AUDIO)
        val v = f.output(video, "live.mp4", "video/mp4", CaptureArtifactRole.VIDEO)
        val a = f.output(audio, "live.flac", "audio/flac", CaptureArtifactRole.AUDIO)
        requireNotNull(f.context.contentResolver.openFileDescriptor(a.uri, "rw")).use { descriptor ->
            video.abort()
            assertEquals(RecordingRecoveryReport(), RecordingCaptureRecovery(f.context).recover())
            assertTrue(descriptor.fileDescriptor.valid())
            assertEquals(setOf(v.uri, a.uri), f.journal.allRows(group.id))
        }
        audio.abort()
        assertTrue(f.journal.allRows(group.id).isEmpty()); assertNull(f.state(group.id))
    }

    @Test fun commitWaitsForEveryPreparedPublishedMemberAndPreservesTheirBytes() = Fixture().use { f ->
        val group = f.group()
        val video = f.member(group, RecordingMemberKind.VIDEO)
        val audio = f.member(group, RecordingMemberKind.AUDIO)
        val v = listOf(f.output(video, "whole.mp4", "video/mp4", CaptureArtifactRole.VIDEO),
            f.output(video, "whole.timing.json", "application/json", CaptureArtifactRole.VIDEO_METADATA))
        val a = listOf(f.output(audio, "whole.wav", "audio/wav", CaptureArtifactRole.AUDIO),
            f.output(audio, "whole.audio.json", "application/json", CaptureArtifactRole.AUDIO_METADATA))
        val rows = v + a
        val before = rows.associate { it.uri to f.digest(it.uri) }
        video.prepared(v.map { it.artifact }); f.publish(v); video.published()
        assertEquals(0L, f.state(group.id))
        assertEquals(RecordingRecoveryReport(), RecordingCaptureRecovery(f.context).recover())
        audio.prepared(a.map { it.artifact })
        assertEquals(0L, f.state(group.id))
        f.publish(a); audio.published()
        // Successful commit prunes its receipt; disappearance never licenses a namespace scan.
        assertNull(f.state(group.id))
        video.abort(); audio.abort()
        assertEquals(RecordingRecoveryReport(), RecordingCaptureRecovery(f.context).recover())
        assertEquals(before.keys, f.journal.allRows(group.id))
        before.forEach { (uri, digest) -> assertEquals(digest, f.digest(uri)); assertEquals(0, f.pending(uri)) }
    }

    @Test fun providerMimeMutationAfterAllocationIsRejectedWithoutChangingTheExpectedIdentity() = Fixture().use { f ->
        val group = f.group(); val member = f.member(group, RecordingMemberKind.VIDEO)
        val row = f.output(member, "changed.mp4", "video/mp4", CaptureArtifactRole.VIDEO)
        assertEquals(1, f.context.contentResolver.update(row.uri, ContentValues().apply {
            put(MediaStore.MediaColumns.MIME_TYPE, "video/3gpp")
        }, null, null))
        assertThrows(IllegalStateException::class.java) { member.prepared(listOf(row.artifact)) }
        assertEquals(0L, f.state(group.id))
        member.abort(); assertNull(f.state(group.id)); assertTrue(f.journal.allRows(group.id).isEmpty())
    }

    @Test fun retainedCommittedReceiptIsPrunedWithoutDeletingVisibleOrPendingRows() = Fixture().use { f ->
        val id = UUID.randomUUID().toString()
        f.receipt(id, 1L)
        val video = f.direct(id, "DCIM", "committed.mp4", "video/mp4", pending = 0)
        // IS_PENDING cannot override an explicit durable COMMITTED state either way.
        val metadata = f.direct(id, "Download", "committed.timing.json", "application/json", pending = 1)
        val before = listOf(video, metadata).associateWith(f::digest)
        assertEquals(RecordingRecoveryReport(), f.journal.recover())
        assertNull(f.state(id))
        assertEquals(RecordingRecoveryReport(), RecordingCaptureRecovery(f.context).recover())
        before.forEach { (uri, digest) -> assertEquals(digest, f.digest(uri)) }
    }

    @Test fun corruptLongRealAndTextStatesRemainUnresolvedWithoutMutatingTheirRows() = Fixture().use { f ->
        for (invalid in listOf<Any>(4_294_967_296L, 4_294_967_297L, 0.5, "not-a-state")) {
            val id = UUID.randomUUID().toString()
            f.receipt(id, 0L)
            val row = f.direct(id, "DCIM", "corrupt.mp4", "video/mp4", pending = 0)
            f.database { db ->
                db.execSQL("PRAGMA ignore_check_constraints=ON")
                db.execSQL("UPDATE captures SET committed = ? WHERE id = ?", arrayOf(invalid, id))
            }
            val before = f.digest(row)
            val literal = f.database { db -> db.rawQuery("SELECT typeof(committed), committed FROM captures WHERE id = ?", arrayOf(id)).use {
                assertTrue(it.moveToFirst()); it.getString(0) to it.getString(1)
            } }
            assertEquals(RecordingRecoveryReport(unresolvedGroups = 1), f.journal.recover())
            assertEquals(before, f.digest(row))
            f.database { db -> db.rawQuery("SELECT typeof(committed), committed FROM captures WHERE id = ?", arrayOf(id)).use {
                assertTrue(it.moveToFirst()); assertEquals(literal, it.getString(0) to it.getString(1))
            } }
            // Restore only this fixture receipt for subsequent independent cases and cleanup.
            f.database { it.execSQL("UPDATE captures SET committed = 0 WHERE id = ?", arrayOf(id)) }
            assertEquals(RecordingRecoveryReport(discardedGroups = 1), f.journal.recover())
            assertTrue(f.journal.allRows(id).isEmpty())
        }
    }

    @Test fun futureSchemaIsPreservedAndNeverTriggersProviderCompensation() = Fixture().use { f ->
        val id = UUID.randomUUID().toString()
        f.receipt(id, 0L)
        val row = f.direct(id, "Music", "future.wav", "audio/wav", pending = 1)
        f.database { it.version = 2 }
        val beforeDatabase = f.databaseFile.readBytes()
        val beforeRow = f.digest(row)
        assertThrows(IllegalStateException::class.java) { RecordingCaptureRecovery(f.context).recover() }
        assertArrayEquals(beforeDatabase, f.databaseFile.readBytes())
        assertEquals(beforeRow, f.digest(row))
        f.database { assertEquals(2, it.version); it.version = 1 }
        assertEquals(RecordingRecoveryReport(discardedGroups = 1), f.journal.recover())
    }

    @Test fun orphanRecoveryFindsProviderRenamesButPreservesSimilarAndTransferNamespaces() = Fixture().use { f ->
        val id = UUID.randomUUID().toString()
        f.receipt(id, 0L)
        val video = f.direct(id, "DCIM", "interrupted.mp4", "video/mp4", pending = 0)
        val audio = f.direct(id, "Music", "interrupted.wav", "audio/wav", pending = 1)
        val metadata = f.direct(id, "Download", "interrupted.timing.json", "application/json", pending = 0)
        assertEquals(1, f.context.contentResolver.update(video,
            ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, "provider-renamed.mp4") }, null, null))
        val similar = f.direct(id, "DCIM", "keep.mp4", "video/mp4", pending = 0,
            path = "DCIM/OpenCineCam/OCC_TAKE_${id}_other/")
        val transfer = f.direct(id, "Download", "OCC_${id}.timing.json", "application/json", pending = 0,
            path = "Download/OpenCineCam/")
        val before = listOf(similar, transfer).associateWith(f::digest)
        assertEquals(setOf(video, audio, metadata), f.journal.allRows(id))
        assertEquals(RecordingRecoveryReport(discardedGroups = 1), RecordingCaptureRecovery(f.context).recover())
        assertTrue(f.journal.allRows(id).isEmpty()); assertNull(f.state(id))
        assertEquals(RecordingRecoveryReport(), f.journal.recover())
        before.forEach { (uri, digest) -> assertEquals(digest, f.digest(uri)) }
    }

    private data class Output(val uri: Uri, val artifact: PreparedCaptureArtifact)

    private class Fixture : AutoCloseable {
        private val app = InstrumentationRegistry.getInstrumentation().targetContext
        private val directory = File(app.noBackupFilesDir, "recording-recovery-native-${UUID.randomUUID()}").apply {
            check(mkdirs())
        }
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = directory
        }
        val journal = RecordingCaptureRecovery(context)
        val databaseFile = File(directory, "recording-recovery.sqlite")
        private val rows = linkedSetOf<Uri>()
        private val members = mutableListOf<RecordingRecoveryMember>()
        private val guards = mutableListOf<AutoCloseable>()
        init { assertEquals(RecordingRecoveryReport(), journal.recover()) }
        fun group(): RecordingRecoveryGroup = RecordingCaptureRecovery.begin(context)
        fun member(group: RecordingRecoveryGroup, kind: RecordingMemberKind): RecordingRecoveryMember =
            group.member(kind).also { members += it }
        fun guard(group: RecordingRecoveryGroup): AutoCloseable = group.retainNativeWriter().also { guards += it }
        fun <T> database(block: (SQLiteDatabase) -> T): T = SQLiteDatabase.openDatabase(databaseFile.path, null,
            SQLiteDatabase.OPEN_READWRITE).use(block)
        fun receipt(id: String, state: Long) = database { db ->
            db.execSQL("INSERT INTO captures(id, committed) VALUES(?, ?)", arrayOf<Any>(id, state))
        }
        fun state(id: String): Long? = database { db ->
            db.rawQuery("SELECT committed FROM captures WHERE id = ?", arrayOf(id)).use {
                if (!it.moveToFirst()) null else {
                    assertEquals(Cursor.FIELD_TYPE_INTEGER, it.getType(0)); it.getLong(0)
                }
            }
        }
        fun output(member: RecordingRecoveryMember, name: String, mime: String, role: CaptureArtifactRole): Output {
            val metadata = role in setOf(CaptureArtifactRole.VIDEO_METADATA, CaptureArtifactRole.AUDIO_METADATA)
            val uri = member.insert(name, mime, metadata).also { rows += it }
            write(uri, name)
            return Output(uri, PreparedCaptureArtifact(role, uri.toString(), name))
        }
        fun direct(id: String, root: String, name: String, mime: String, pending: Int, path: String = RecordingCaptureRecovery.path(id, root)): Uri {
            val uri = requireNotNull(context.contentResolver.insert(RecordingCaptureRecovery.collection(root), ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, path); put(MediaStore.MediaColumns.IS_PENDING, 1)
            })).also { rows += it }
            write(uri, name)
            if (pending == 0) assertEquals(1, context.contentResolver.update(uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null))
            return uri
        }
        private fun write(uri: Uri, label: String) {
            requireNotNull(context.contentResolver.openOutputStream(uri, "w")).use {
                it.write("ownership protocol fixture: $label".toByteArray(Charsets.UTF_8))
            }
        }
        fun publish(outputs: List<Output>) = outputs.forEach { assertEquals(1, context.contentResolver.update(it.uri,
            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)) }
        fun pending(uri: Uri): Int = requireNotNull(context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_PENDING), null, null, null)).use {
            assertTrue(it.moveToFirst()); it.getInt(0)
        }
        fun digest(uri: Uri): String = requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
            MessageDigest.getInstance("SHA-256").digest(input.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        override fun close() {
            var failure: Throwable? = null
            fun cleanup(action: () -> Unit) {
                try { action() } catch (problem: Throwable) {
                    val first = failure
                    if (first == null) failure = problem else if (first !== problem) first.addSuppressed(problem)
                }
            }
            // Release every synthetic native ownership guard and member before disposing rows.
            guards.forEach { cleanup(it::close) }
            members.forEach { cleanup(it::abort) }
            rows.forEach { uri -> cleanup {
                val deleted = context.contentResolver.delete(ContentUris.removeId(uri),
                    "${MediaStore.MediaColumns._ID} = ? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?",
                    arrayOf(ContentUris.parseId(uri).toString(), context.packageName))
                check(deleted in 0..1)
            } }
            cleanup { check(directory.deleteRecursively()) }
            failure?.let { throw it }
        }
    }
}
