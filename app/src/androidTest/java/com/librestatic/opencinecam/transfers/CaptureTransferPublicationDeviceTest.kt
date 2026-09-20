/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.transfers

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.librestatic.opencinecam.storage.CaptureArtifactRole
import com.librestatic.opencinecam.storage.PreparedCaptureArtifact
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real private files, SQLite and MediaStore metadata; deliberately not codec or network tests. */
@RunWith(AndroidJUnit4::class)
class CaptureTransferPublicationDeviceTest {
    @Test fun oneArtifactStagesBeforePublicationAndSealsAfterCallback() = completeSet(1)
    @Test fun twoArtifactsStageAsOneBundleAndSealAfterCallback() = completeSet(2)
    @Test fun fourArtifactsStageAsOneBundleAndSealAfterCallback() = completeSet(4)

    private fun completeSet(count: Int) = Fixture().use { f ->
        val state = enabled()
        CaptureTransferPublication.admit(f.context, state, f.id, f.database).use { publication ->
            assertNull(publication.admissionFailure)
            assertEquals(CaptureTransferEnrollment(f.id, state.preferences.activeEndpointId!!, 0L,
                state.preferences.revision), CaptureTransferEnrollmentFile(f.context).load(f.id))
            assertNull(CapturePublicationJournal.load(f.context, f.id))
            val artifacts = f.prepare(count)
            publication.observer.onPrepared(artifacts)
            WebDavSqliteOutbox(f.context, f.database).use { db ->
                val staged = requireNotNull(db.load(f.id))
                assertEquals(count, staged.artifacts.size)
                assertFalse(staged.sealed)
                assertEquals(WebDavBundleState.AWAITING_PUBLICATION, staged.state)
                assertEquals(artifacts.map { it.uri }.toSet(), staged.artifacts.map { it.spec.sourceUri }.toSet())
                assertTrue(staged.artifacts.all { it.spec.sizeBytes == Fixture.BYTES.size.toLong() })
                artifacts.forEach { assertTrue(f.probe.inspect(it, true).sizeBytes > 0) }
                f.publish(artifacts)
                // MediaStore alone is not evidence that the whole finalizer succeeded.
                assertFalse(requireNotNull(db.load(f.id)).sealed)
                assertEquals(CapturePublicationState.PREPARED, CapturePublicationJournal.load(f.context, f.id)!!.state)
                publication.observer.onPublished(artifacts)
                val sealed = requireNotNull(db.load(f.id))
                assertTrue(sealed.sealed)
                assertEquals(WebDavBundleState.QUEUED, sealed.state)
                assertTrue(sealed.artifacts.all { it.sha256 == null && it.attempt == null })
                assertEquals(artifacts.toSet(), CapturePublicationJournal.load(f.context, f.id)!!.artifacts.toSet())
                assertEquals(CapturePublicationState.COMMITTED, CapturePublicationJournal.load(f.context, f.id)!!.state)
                assertTrue(publication.registered)
                assertFalse(publication.registrationFailed)
                f.assertPublishedBytes(artifacts)
            }
        }
    }

    @Test fun endpointAndConsentAreFrozenAtAdmissionDespiteSettingsChange() = Fixture().use { f ->
        val settings = WebDavQueueSettings(File(f.privateDirectory, "settings.json"))
        val old = settings.save("https://first.example/takes/", true, false)
        CaptureTransferPublication.admit(f.context, settings.snapshotForAdmission(), f.id, f.database).use { publication ->
            val changed = settings.save("https://second.example/takes/", true, true)
            assertNotEquals(old.activeEndpointId, changed.activeEndpointId)
            assertTrue(changed.revision > old.revision)
            val artifacts = f.prepare(2)
            publication.observer.onPrepared(artifacts)
            f.publish(artifacts)
            publication.observer.onPublished(artifacts)
            val enrollment = requireNotNull(CaptureTransferEnrollmentFile(f.context).load(f.id))
            assertEquals(old.activeEndpointId, enrollment.endpointId)
            assertEquals(old.revision, enrollment.consentRevision)
            WebDavSqliteOutbox(f.context, f.database).use { db ->
                assertEquals(old.activeEndpointId, requireNotNull(db.load(f.id)).endpointId)
                assertEquals(0L, requireNotNull(db.load(f.id)).endpointRevision)
            }
        }
    }

    @Test fun disabledAdmissionNeverEnrollsHistoricalCommittedCapture() = Fixture().use { f ->
        val settings = WebDavQueueSettings(File(f.privateDirectory, "settings.json"))
        CaptureTransferPublication.admit(f.context, settings.snapshotForAdmission(), f.id, f.database).use { publication ->
            settings.save("https://later.example/takes/", true, false)
            val artifacts = f.prepare(2)
            publication.observer.onPrepared(artifacts)
            f.publish(artifacts)
            publication.observer.onPublished(artifacts)
            assertFalse(publication.registered)
            assertNull(CaptureTransferEnrollmentFile(f.context).load(f.id))
            assertEquals(CapturePublicationState.COMMITTED, CapturePublicationJournal.load(f.context, f.id)!!.state)
        }
        WebDavSqliteOutbox(f.context, f.database).use { db ->
            assertEquals(CaptureOutboxRegistrationStatus.NOT_ENROLLED, f.bridge(db).registerCommitted().status)
            assertNull(db.load(f.id))
            assertNull(CaptureTransferEnrollmentFile(f.context).load(f.id))
        }
    }

    @Test fun abortPreservesUnsealedBundleAndDoesNotPublishOrDeleteRows() = Fixture().use { f ->
        val publication = CaptureTransferPublication.admit(f.context, enabled(), f.id, f.database)
        try {
            val artifacts = f.prepare(4)
            publication.observer.onPrepared(artifacts)
            assertTrue(publication.abort().isEmpty())
            assertTrue(publication.abort().isEmpty())
            assertEquals(CapturePublicationState.ABORTED, CapturePublicationJournal.load(f.context, f.id)!!.state)
            artifacts.forEach { assertFalse(f.probe.inspect(it, true).finalized) }
            WebDavSqliteOutbox(f.context, f.database).use { db ->
                val before = requireNotNull(db.load(f.id))
                assertFalse(before.sealed)
                assertEquals(CaptureOutboxRegistrationStatus.BLOCKED, f.bridge(db).registerCommitted().status)
                assertEquals(before, db.load(f.id))
            }
        } finally { publication.close() }
    }

    @Test fun committedRecoveryReopensWithoutResettingHashOrActiveLease() = Fixture().use { f ->
        val artifacts = f.prepare(2)
        CaptureTransferPublication.admit(f.context, enabled(), f.id, f.database).use { publication ->
            publication.observer.onPrepared(artifacts)
            f.publish(artifacts)
            publication.observer.onPublished(artifacts)
        }
        val before = WebDavSqliteOutbox(f.context, f.database).use { db ->
            val video = requireNotNull(db.load(f.id)).artifacts.single { it.spec.role == WebDavArtifactRole.VIDEO }
            val source = f.probe.inspect(artifacts.single { it.role == CaptureArtifactRole.VIDEO }, false)
            val hash = MessageDigest.getInstance("SHA-256").digest(Fixture.BYTES).joinToString("") { "%02x".format(it) }
            val hashed = requireNotNull(db.recordHash(f.id, video.revision, WebDavArtifactPublication(video.spec.id, source), hash))
            val ready = hashed.artifacts.single { it.spec.id == video.spec.id }
            assertNotNull(db.claim(f.id, ready.spec.id, ready.revision, UUID.randomUUID().toString(), WebDavAttemptKind.PUT,
                WebDavOutboxAdmission(UUID.randomUUID().toString(), 1L, WebDavUploadPolicy(enabled = true, network = WebDavNetwork.WIFI))))
            requireNotNull(db.load(f.id))
        }
        WebDavSqliteOutbox(f.context, f.database).use { reopened ->
            repeat(2) {
                assertEquals(CaptureOutboxRegistrationStatus.ALREADY_REGISTERED, f.bridge(reopened).registerCommitted().status)
                assertEquals(before, reopened.load(f.id))
            }
            assertEquals(WebDavBundleState.ACTIVE, requireNotNull(reopened.load(f.id)).state)
            f.assertPublishedBytes(artifacts)
        }
    }

    @Test fun recoveryDoesNotInferCommitFromAllRowsPublishedAfterPreparedOnly() = Fixture().use { f ->
        val artifacts = f.prepare(2)
        CaptureTransferPublication.admit(f.context, enabled(), f.id, f.database).use { publication ->
            publication.observer.onPrepared(artifacts)
            f.publish(artifacts)
            // Simulate termination between publishing rows and receiving finalizer success.
        }
        WebDavSqliteOutbox(f.context, f.database).use { db ->
            val before = db.load(f.id)
            assertEquals(CaptureOutboxRegistrationStatus.BLOCKED, f.bridge(db).registerCommitted().status)
            assertEquals(before, db.load(f.id))
            assertFalse(requireNotNull(before).sealed)
            f.assertPublishedBytes(artifacts)
        }
    }

    @Test fun enrollmentDirectoryFailureKeepsLocalPublicationAndJournal() = Fixture().use { f ->
        val obstruction = File(f.privateDirectory, "capture-transfer-enrollment").apply { writeText("preserve enrollment obstruction") }
        CaptureTransferPublication.admit(f.context, enabled(), f.id, f.database).use { publication ->
            assertNotNull(publication.admissionFailure)
            val artifacts = f.prepare(2)
            publication.observer.onPrepared(artifacts)
            f.publish(artifacts)
            publication.observer.onPublished(artifacts)
            assertFalse(publication.registered)
            assertTrue(publication.registrationFailed)
            assertEquals(CapturePublicationState.COMMITTED, CapturePublicationJournal.load(f.context, f.id)!!.state)
            assertEquals("preserve enrollment obstruction", obstruction.readText())
            f.assertPublishedBytes(artifacts)
            WebDavSqliteOutbox(f.context, f.database).use { assertNull(it.load(f.id)) }
        }
    }

    @Test fun isolatedJournalErrorsDoNotDeletePublishedBytesOrPreventExistingStageSeal() = Fixture().use { f ->
        val obstruction = File(f.privateDirectory, "capture-publication").apply { writeText("preserve journal obstruction") }
        CaptureTransferPublication.admit(f.context, enabled(), f.id, f.database).use { publication ->
            assertNull(publication.admissionFailure)
            val artifacts = f.prepare(2)
            // The capture finalizer isolates optional callback failures; this test exercises that boundary explicitly.
            assertThrows(CapturePublicationOutboxFailure::class.java) { publication.observer.onPrepared(artifacts) }
            WebDavSqliteOutbox(f.context, f.database).use { assertFalse(requireNotNull(it.load(f.id)).sealed) }
            f.publish(artifacts)
            assertThrows(CapturePublicationOutboxFailure::class.java) { publication.observer.onPublished(artifacts) }
            assertTrue(publication.registered)
            assertTrue(publication.registrationFailed)
            WebDavSqliteOutbox(f.context, f.database).use { assertTrue(requireNotNull(it.load(f.id)).sealed) }
            assertEquals("preserve journal obstruction", obstruction.readText())
            f.assertPublishedBytes(artifacts)
        }
    }

    private fun enabled() = WebDavQueueSettingsState(WebDavQueuePreferences().updated("https://dav.example/takes/", true, false))

    private class Fixture : AutoCloseable {
        private val base = ApplicationProvider.getApplicationContext<Context>()
        val id = UUID.randomUUID().toString()
        val database = "capture-transfer-publication-$id.db"
        val privateDirectory = File(base.cacheDir, "capture-transfer-publication-$id").apply { check(mkdirs()) }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = privateDirectory
        }
        val probe = MediaStorePreparedArtifactProbe(context.contentResolver)
        private val rows = mutableListOf<Uri>()

        fun prepare(count: Int): List<PreparedCaptureArtifact> {
            require(count in setOf(1, 2, 4))
            val roles = listOf(CaptureArtifactRole.VIDEO, CaptureArtifactRole.VIDEO_METADATA,
                CaptureArtifactRole.AUDIO, CaptureArtifactRole.AUDIO_METADATA).take(count)
            return roles.map { role ->
                val collection: Uri
                val extension: String
                val mime: String
                val directory: String
                when (role) {
                    CaptureArtifactRole.VIDEO -> { collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI; extension = "mp4"; mime = "video/mp4"; directory = Environment.DIRECTORY_MOVIES }
                    CaptureArtifactRole.AUDIO -> { collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI; extension = "wav"; mime = "audio/wav"; directory = Environment.DIRECTORY_MUSIC }
                    else -> { collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI; extension = "json"; mime = "application/json"; directory = Environment.DIRECTORY_DOWNLOADS }
                }
                val uri = requireNotNull(context.contentResolver.insert(collection, ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "publication-$id-${role.name}.$extension")
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "$directory/OpenCineCamTests")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }))
                rows.add(uri)
                requireNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(BYTES) }
                val name = requireNotNull(context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)).use {
                    check(it.moveToFirst()); it.getString(0)
                }
                PreparedCaptureArtifact(role, uri.toString(), name)
            }
        }

        fun publish(artifacts: List<PreparedCaptureArtifact>) = artifacts.forEach {
            assertEquals(1, context.contentResolver.update(Uri.parse(it.uri), ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null))
        }

        fun assertPublishedBytes(artifacts: List<PreparedCaptureArtifact>) = artifacts.forEach {
            assertTrue(probe.inspect(it, false).finalized)
            requireNotNull(context.contentResolver.openInputStream(Uri.parse(it.uri))).use { input -> assertArrayEquals(BYTES, input.readBytes()) }
        }

        fun bridge(db: WebDavOutboxStore) = CapturePublicationOutboxBridge(id, CapturePublicationJournal(context, id),
            { CapturePublicationJournal.load(context, it) }, CaptureTransferEnrollmentFile(context), db, probe)

        override fun close() {
            try { rows.forEach { context.contentResolver.delete(it, null, null) } }
            finally { base.deleteDatabase(database); privateDirectory.deleteRecursively() }
        }

        companion object { val BYTES = ByteArray(1024) { it.toByte() } }
    }
}
