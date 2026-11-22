/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The gallery's read-only view of the recovery journals ([uncommittedCaptureNamespaces]) against the schemas their writers create. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class GalleryRecoveryJournalTest {
    private lateinit var context: Context
    private lateinit var directory: File
    private val recording get() = File(directory, RecordingCaptureRecovery.DATABASE)
    private val still get() = File(directory, StillCaptureRecovery.DATABASE)

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        directory = context.noBackupFilesDir
        listOf(recording, still).forEach { it.delete() }
    }

    private fun <T> database(file: File, block: (SQLiteDatabase) -> T): T =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use(block)

    private fun insert(file: File, id: String, committed: Int, tree: String? = null) = database(file) { db ->
        db.execSQL("INSERT INTO captures(id, committed, tree) VALUES(?, ?, ?)", arrayOf<Any?>(id, committed, tree))
    }

    /** The journal as its writer creates it, at its current schema, with no entry. */
    private fun recordingJournal() {
        RecordingCaptureRecovery(context) { false }.recover()
        assertEquals(RecordingCaptureRecovery.JOURNAL_VERSION, database(recording) { it.version })
    }

    private fun stillJournal() {
        StillCaptureRecovery(context, mounted = { false }).recover()
        assertEquals(StillCaptureRecovery.JOURNAL_VERSION, database(still) { it.version })
    }

    private fun id() = UUID.randomUUID().toString()

    @Test fun missingJournalsReadAsEmpty() {
        assertEquals(emptySet<String>(), uncommittedCaptureNamespaces(directory))
    }

    @Test fun readsUncommittedTakesFromTheCurrentRecordingJournal() {
        recordingJournal()
        val take = id()
        insert(recording, take, 0)
        assertEquals(setOf("take:$take"), uncommittedCaptureNamespaces(directory))
    }

    @Test fun readsUncommittedGroupsFromTheCurrentStillJournal() {
        stillJournal()
        val group = id()
        insert(still, group, 0)
        assertEquals(setOf("still:$group"), uncommittedCaptureNamespaces(directory))
    }

    @Test fun committedEntriesAreNotReturned() {
        recordingJournal()
        stillJournal()
        val take = id()
        val group = id()
        insert(recording, take, 0)
        insert(recording, id(), 1)
        insert(still, group, 0)
        insert(still, id(), 1)
        assertEquals(setOf("take:$take", "still:$group"), uncommittedCaptureNamespaces(directory))
    }

    @Test fun aRecordingJournalNewerThanItsWriterIsRefused() {
        recordingJournal()
        database(recording) { it.version = RecordingCaptureRecovery.JOURNAL_VERSION + 1 }
        assertThrows(IllegalStateException::class.java) { uncommittedCaptureNamespaces(directory) }
    }

    @Test fun aStillJournalNewerThanItsWriterIsRefused() {
        stillJournal()
        database(still) { it.version = StillCaptureRecovery.JOURNAL_VERSION + 1 }
        assertThrows(IllegalStateException::class.java) { uncommittedCaptureNamespaces(directory) }
    }

    @Test fun phoneAndDriveTakesAreBoundedApart() {
        recordingJournal()
        val expected = HashSet<String>()
        repeat(RecordingCaptureRecovery.MAX_PHONE_TAKES) { id().also { insert(recording, it, 0); expected += "take:$it" } }
        repeat(3) { id().also { insert(recording, it, 0, tree = "content://drive/tree/$it"); expected += "take:$it" } }
        assertEquals(expected, uncommittedCaptureNamespaces(directory))
    }

    @Test fun aRecordingJournalOverItsCapacityIsRefused() {
        recordingJournal()
        database(recording) { db ->
            db.beginTransaction()
            try {
                repeat(RecordingCaptureRecovery.MAX_PHONE_TAKES + RecordingCaptureRecovery.MAX_DRIVE_TAKES + 1) {
                    db.execSQL("INSERT INTO captures(id, committed) VALUES(?, 1)", arrayOf<Any?>(id()))
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        assertThrows(IllegalStateException::class.java) { uncommittedCaptureNamespaces(directory) }
    }

    @Test fun aMalformedIdentityIsRefused() {
        recordingJournal()
        insert(recording, "not-a-take", 0)
        assertThrows(IllegalStateException::class.java) { uncommittedCaptureNamespaces(directory) }
    }
}
