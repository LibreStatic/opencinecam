/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.transfers

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The persisted outbox across the Gyroflow log role: earlier bundles load as written, and a take with every role fits. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class WebDavSqliteOutboxTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun aBundlePersistedBeforeTheGyroflowLogLoadsAndSealsUnchanged() {
        // Five roles, the most a take had before: the shape (and SQL rows) earlier builds wrote.
        val plan = plan(base)
        WebDavSqliteOutbox(context, DB).use { it.stage(plan) }
        val loaded = WebDavSqliteOutbox(context, DB).use { requireNotNull(it.load(plan.id)) }
        assertEquals(plan.artifacts.toSet(), loaded.artifacts.map { it.spec }.toSet())
        val sealed = WebDavSqliteOutbox(context, DB).use { store ->
            requireNotNull(store.seal(plan.id, loaded.revision, plan.artifacts.map(::publication)))
        }
        assertEquals(WebDavBundleState.QUEUED, sealed.state)
        assertTrue(sealed.artifacts.none { it.spec.role == WebDavArtifactRole.MOTION_LOG })
    }

    @Test fun aTakeWithItsGyroflowLogPersistsTheLogsRemoteName() {
        val plan = plan(base + log)
        WebDavSqliteOutbox(context, DB).use { it.stage(plan) }
        val loaded = WebDavSqliteOutbox(context, DB).use { requireNotNull(it.load(plan.id)) }
        assertEquals(6, loaded.artifacts.size)
        val spec = loaded.artifacts.single { it.spec.role == WebDavArtifactRole.MOTION_LOG }.spec
        assertEquals("A001 (1).gcsv", spec.sourceName)
        assertEquals("A001.gcsv", spec.remoteName)
    }

    @Test fun aRoleThisBuildDoesNotKnowIsPreservedAsCorruptDataNotDropped() {
        val plan = plan(base)
        WebDavSqliteOutbox(context, DB).use { it.stage(plan) }
        SQLiteDatabase.openDatabase(context.getDatabasePath(DB).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("UPDATE artifacts SET role='FUTURE_ROLE' WHERE role='AUDIO'")
        }
        WebDavSqliteOutbox(context, DB).use { store -> assertThrows(WebDavOutboxCorruptData::class.java) { store.load(plan.id) } }
    }

    private val base = listOf(
        WebDavArtifactSpec(id(2), WebDavArtifactRole.VIDEO, "content://media/external/video/media/1", "A001.mp4", 100),
        WebDavArtifactSpec(id(3), WebDavArtifactRole.VIDEO_METADATA, "content://media/external/downloads/2", "A001.timing.json", 10),
        WebDavArtifactSpec(id(4), WebDavArtifactRole.CLIP_METADATA, "content://media/external/downloads/3", "A001.clip.json", 10),
        WebDavArtifactSpec(id(5), WebDavArtifactRole.AUDIO, "content://media/external/audio/media/4", "A001.wav", 10),
        WebDavArtifactSpec(id(6), WebDavArtifactRole.AUDIO_METADATA, "content://media/external/downloads/5", "A001.wav.json", 10),
    )
    private val log = WebDavArtifactSpec(id(7), WebDavArtifactRole.MOTION_LOG, "content://media/external/downloads/6",
        "A001 (1).gcsv", 10, "A001.gcsv")

    private fun plan(artifacts: List<WebDavArtifactSpec>) = WebDavBundlePlan(id(1), id(9), 0, artifacts)
    private fun publication(spec: WebDavArtifactSpec) = WebDavArtifactPublication(spec.id,
        WebDavClipSnapshot(spec.sourceUri, spec.sourceName, spec.sizeBytes, 11, true))
    private fun id(value: Int) = "00000000-0000-0000-0000-${value.toString().padStart(12, '0')}"

    private companion object { const val DB = "outbox-roles.db" }
}
