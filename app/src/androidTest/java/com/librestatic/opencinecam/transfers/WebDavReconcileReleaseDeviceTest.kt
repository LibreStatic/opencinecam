/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.transfers

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Real private SQLite CAS/rollback; no MediaStore or network evidence is manufactured here. */
class WebDavReconcileReleaseDeviceTest {
    private val hash = "d".repeat(64)
    private val admission = WebDavOutboxAdmission(id(10), 9_007_199_254_740_993L,
        WebDavUploadPolicy(enabled = true, network = WebDavNetwork.WIFI))

    @Test fun exactReleaseSurvivesReopenAndPreservesEveryOtherFieldAndArtifact() = database { fixture ->
        val (before, lease) = fixture.open().use { active(it) }
        fixture.open().use { store ->
            assertEquals(before, store.load(id(1)))
            assertTrue(store.finishReconcile(lease))
            assertEquals(expectedRelease(before, lease), store.load(id(1)))
            assertFalse(store.finishReconcile(lease))
        }
        fixture.open().use { store ->
            val after = requireNotNull(store.load(id(1)))
            assertEquals(expectedRelease(before, lease), after)
            val released = after.artifacts.single { it.spec.id == lease.artifactId }
            assertEquals(WebDavArtifactState.UNCERTAIN, released.state)
            assertEquals(hash, released.sha256)
            assertEquals(9_007_199_254_740_993L, released.modifiedSeconds)
            assertEquals(admission, released.lastAdmission)
            assertTrue(released.remoteMayExist)
            assertNull(released.attempt)
            assertEquals(before.artifacts.single { it.spec.id != lease.artifactId }, after.artifacts.single { it.spec.id != lease.artifactId })
            assertFalse(store.finishReconcile(lease))
        }
    }

    @Test fun wrongProcessRevisionArtifactDestinationAndPutLeaseCannotReleaseSqlAttempt() = database { fixture ->
        fixture.open().use { store ->
            val (before, lease) = active(store)
            val stale = listOf(
                lease.copy(bundleId = id(90)), lease.copy(endpointId = id(91)),
                lease.copy(endpointRevision = lease.endpointRevision + 1),
                lease.copy(artifactId = id(3)), lease.copy(artifactId = id(92)),
                lease.copy(artifactRevision = lease.artifactRevision + 1),
                lease.copy(artifactRevision = lease.artifactRevision - 1),
                lease.copy(attempt = lease.attempt.copy(processToken = id(93))),
                lease.copy(attempt = lease.attempt.copy(id = id(94))),
                lease.copy(attempt = lease.attempt.copy(kind = WebDavAttemptKind.PUT)),
            )
            for (candidate in stale) {
                assertFalse(candidate.toString(), store.finishReconcile(candidate))
                assertEquals(before, store.load(id(1)))
            }
            // An exact currently-owned PUT is a semantic mismatch, not just a stale lease.
            val metadata = before.artifacts.single { it.spec.id == id(3) }
            val put = requireNotNull(store.claim(id(1), id(3), metadata.revision, id(40), WebDavAttemptKind.PUT, admission))
            val uploading = store.load(id(1))
            assertFalse(store.finishReconcile(put))
            assertEquals(uploading, store.load(id(1)))
        }
    }

    @Test fun freshReconciliationAfterReleaseRejectsEveryOldCompletionWithoutResettingUncertainty() = database { fixture ->
        val old = fixture.open().use { active(it).second }
        val fresh = fixture.open().use { store ->
            assertTrue(store.finishReconcile(old))
            val released = requireNotNull(store.load(id(1)))
            val artifact = released.artifacts.single { it.spec.id == old.artifactId }
            assertThrows(IllegalArgumentException::class.java) {
                store.claim(id(1), artifact.spec.id, artifact.revision, id(50), WebDavAttemptKind.PUT, admission)
            }
            requireNotNull(store.claim(id(1), artifact.spec.id, artifact.revision, id(51), WebDavAttemptKind.RECONCILE,
                admission.copy(processToken = id(11), policyRevision = admission.policyRevision + 1)))
        }
        fixture.open().use { store ->
            val before = requireNotNull(store.load(id(1)))
            assertEquals(WebDavArtifactState.UNCERTAIN, before.artifacts.single { it.spec.id == old.artifactId }.state)
            assertFalse(store.finishReconcile(old))
            assertFalse(store.finishPut(old, WebDavPutOutcome.DEFINITELY_NOT_STARTED))
            assertFalse(store.reconcile(old, evidence(before)))
            assertEquals(before, store.load(id(1)))
            assertTrue(store.finishReconcile(fresh))
            assertEquals(expectedRelease(before, fresh), store.load(id(1)))
        }
    }

    @Test fun triggerFailureRollsBackBundleRevisionAndAttemptThenSameLeaseCanRetry() = database { fixture ->
        val (before, lease) = fixture.open().use { active(it) }
        fixture.raw().use {
            it.execSQL("CREATE TRIGGER reject_release AFTER UPDATE ON artifacts WHEN OLD.attempt_kind='RECONCILE' AND NEW.attempt_id IS NULL BEGIN SELECT RAISE(ABORT, 'retain reconciliation lease'); END")
        }
        fixture.open().use { store ->
            val failure = assertThrows(SQLiteException::class.java) { store.finishReconcile(lease) }
            assertTrue(failure.message.orEmpty().contains("retain reconciliation lease"))
            assertEquals(before, store.load(id(1)))
        }
        fixture.open().use { assertEquals(before, it.load(id(1))) }
        fixture.raw().use { it.execSQL("DROP TRIGGER reject_release") }
        fixture.open().use { store ->
            assertTrue(store.finishReconcile(lease))
            assertEquals(expectedRelease(before, lease), store.load(id(1)))
        }
    }

    @Test fun concurrentExactLeaseReleasesHaveOneSqlWinnerAndOneRevisionIncrement() = database { fixture ->
        val (before, lease) = fixture.open().use { active(it) }
        val start = CountDownLatch(1)
        val entered = CountDownLatch(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures = (1..2).map {
                pool.submit<Boolean> {
                    fixture.open().use { store ->
                        entered.countDown()
                        check(start.await(10, TimeUnit.SECONDS))
                        store.finishReconcile(lease)
                    }
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            start.countDown()
            assertEquals(listOf(false, true), futures.map { it.get(15, TimeUnit.SECONDS) }.sorted())
        } finally {
            start.countDown()
            pool.shutdown()
            assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS))
        }
        fixture.open().use { assertEquals(expectedRelease(before, lease), it.load(id(1))) }
    }

    private fun active(store: WebDavOutboxStore): Pair<WebDavOutboxBundle, WebDavOutboxLease> {
        val plan = WebDavBundlePlan(id(1), id(9), 7L, listOf(
            WebDavArtifactSpec(id(2), WebDavArtifactRole.VIDEO, "content://media/external/video/media/1", "take.mp4", 321L),
            WebDavArtifactSpec(id(3), WebDavArtifactRole.VIDEO_METADATA, "content://media/external/downloads/2", "take.json", 123L),
        ))
        store.stage(plan)
        store.seal(plan.id, 0, plan.artifacts.map(::publication))
        for (spec in plan.artifacts) {
            val current = requireNotNull(store.load(id(1))).artifacts.single { it.spec.id == spec.id }
            requireNotNull(store.recordHash(id(1), current.revision, publication(spec), hash))
        }
        val ready = requireNotNull(store.load(id(1))).artifacts.single { it.spec.id == id(2) }
        val put = requireNotNull(store.claim(id(1), id(2), ready.revision, id(20), WebDavAttemptKind.PUT, admission))
        assertTrue(store.finishPut(put, WebDavPutOutcome.REMOTE_UNCERTAIN))
        val uncertain = requireNotNull(store.load(id(1))).artifacts.single { it.spec.id == id(2) }
        val lease = requireNotNull(store.claim(id(1), id(2), uncertain.revision, id(21), WebDavAttemptKind.RECONCILE, admission))
        return requireNotNull(store.load(id(1))) to lease
    }

    private fun expectedRelease(before: WebDavOutboxBundle, lease: WebDavOutboxLease) = before.copy(
        revision = before.revision + 1, artifacts = before.artifacts.map {
            if (it.spec.id == lease.artifactId) it.copy(revision = it.revision + 1, attempt = null) else it
        })
    private fun publication(spec: WebDavArtifactSpec) = WebDavArtifactPublication(spec.id,
        WebDavClipSnapshot(spec.sourceUri, spec.sourceName, spec.sizeBytes, 9_007_199_254_740_993L, true))
    private fun evidence(bundle: WebDavOutboxBundle) = bundle.artifacts.single { it.spec.id == id(2) }.spec.let {
        WebDavReconciliationEvidence(publication(it), hash, WebDavRemoteObservation.CompleteBody(it.sizeBytes, hash))
    }
    private fun id(number: Int) = "00000000-0000-0000-0000-${number.toString().padStart(12, '0')}"
    private fun database(block: (Fixture) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "webdav-reconcile-release-${UUID.randomUUID()}.db"
        try { block(Fixture(context, name)) } finally { assertTrue("Own SQL fixture cleanup", context.deleteDatabase(name)) }
    }
    private class Fixture(private val context: Context, private val name: String) {
        fun open() = WebDavSqliteOutbox(context, name)
        fun raw(): SQLiteDatabase = SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE)
    }
}
