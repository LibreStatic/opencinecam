/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.transfers

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.FileNotFoundException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebDavSqliteOutboxDeviceTest {
    private val hash = "a".repeat(64)
    private val process = id(10)
    private val admission = WebDavOutboxAdmission(process, 9_007_199_254_740_993,
        WebDavUploadPolicy(enabled = true, network = WebDavNetwork.WIFI))

    @Test fun unsealedCompletePlanSurvivesReopenButCannotStartNetworkWork() = database { fixture ->
        val plan = plan(withMetadata = true)
        fixture.open().use { store ->
            val staged = store.stage(plan)
            assertEquals(WebDavBundleState.AWAITING_PUBLICATION, staged.state)
            assertEquals(staged, store.stage(plan.copy(artifacts = plan.artifacts.reversed())))
        }
        fixture.open().use { store ->
            val reopened = requireNotNull(store.load(plan.id))
            assertEquals(2, reopened.artifacts.size)
            assertThrows(IllegalArgumentException::class.java) {
                store.claim(plan.id, id(2), 0, id(20), WebDavAttemptKind.PUT, admission)
            }
            assertEquals(reopened, store.load(plan.id))
        }
    }

    @Test fun acknowledgedPutRequiresRemoteVerificationForEveryArtifactAfterReopen() = database { fixture ->
        val plan = plan(withMetadata = true)
        fixture.open().use { store ->
            store.stage(plan)
            assertNotNull(store.seal(plan.id, 0, plan.artifacts.map { publication(it) }))
            for (spec in plan.artifacts) {
                val current = requireNotNull(store.load(plan.id)).artifacts.single { it.spec.id == spec.id }
                store.recordHash(plan.id, current.revision, publication(spec), hash)
                val revision = requireNotNull(store.load(plan.id)).artifacts.single { it.spec.id == spec.id }.revision
                val lease = requireNotNull(store.claim(plan.id, spec.id, revision, id(20), WebDavAttemptKind.PUT, admission))
                assertTrue(store.finishPut(lease, WebDavPutOutcome.ACKNOWLEDGED))
                assertNotEquals(WebDavBundleState.COMPLETE, store.load(plan.id)?.state)
            }
        }
        fixture.open().use { store ->
            assertEquals(WebDavBundleState.UNCERTAIN, store.load(plan.id)?.state)
            for (spec in plan.artifacts) {
                val artifact = requireNotNull(store.load(plan.id)).artifacts.single { it.spec.id == spec.id }
                val lease = requireNotNull(store.claim(plan.id, spec.id, artifact.revision, id(21), WebDavAttemptKind.RECONCILE, admission))
                assertTrue(store.reconcile(lease, WebDavReconciliationEvidence(publication(spec), hash,
                    WebDavRemoteObservation.CompleteBody(spec.sizeBytes, hash))))
            }
        }
        fixture.open().use { store ->
            assertEquals(WebDavBundleState.COMPLETE, store.load(plan.id)?.state)
            assertTrue(requireNotNull(store.load(plan.id)).artifacts.all { it.lastAdmission == admission && it.attempt == null })
        }
    }

    @Test fun retiredProcessRecoveryIsDurableAndFencesLateCallbacks() = database { fixture ->
        val lease = fixture.open().use { store ->
            val ready = prepare(store)
            requireNotNull(store.claim(ready.id, id(2), ready.artifacts.single().revision, id(20), WebDavAttemptKind.PUT, admission))
        }
        fixture.open().use { store ->
            assertEquals(WebDavBundleState.ACTIVE, store.load(id(1))?.state)
            assertEquals(0, store.recoverProcess(id(11)))
            assertEquals(1, store.recoverProcess(process))
            assertFalse(store.finishPut(lease, WebDavPutOutcome.ACKNOWLEDGED))
            assertEquals(WebDavBundleState.UNCERTAIN, store.load(id(1))?.state)
            assertEquals(0, store.recoverProcess(process))
        }
        fixture.open().use { store ->
            val artifact = requireNotNull(store.load(id(1))).artifacts.single()
            assertNull(artifact.attempt)
            assertEquals(admission, artifact.lastAdmission)
            assertThrows(IllegalArgumentException::class.java) {
                store.claim(id(1), id(2), artifact.revision, id(22), WebDavAttemptKind.PUT, admission)
            }
        }
    }

    @Test fun staleRevisionsCannotSealHashOrClaimAndEndpointIdentityCannotBeRebound() = database { fixture ->
        fixture.open().use { store ->
            val plan = plan()
            store.stage(plan)
            assertNull(store.seal(plan.id, 1, plan.artifacts.map { publication(it) }))
            val sealed = requireNotNull(store.seal(plan.id, 0, plan.artifacts.map { publication(it) }))
            assertNull(store.recordHash(plan.id, 0, publication(plan.artifacts.single()), hash))
            val ready = requireNotNull(store.recordHash(plan.id, sealed.artifacts.single().revision, publication(plan.artifacts.single()), hash))
            assertNull(store.claim(plan.id, id(2), 0, id(20), WebDavAttemptKind.PUT, admission))
            assertThrows(IllegalArgumentException::class.java) { store.stage(plan.copy(endpointId = id(99))) }
            assertEquals(ready, store.load(plan.id))
        }
    }

    @Test fun abortedSqlUpdateRollsBackBundleSealAndEveryArtifact() = database { fixture ->
        val plan = plan(withMetadata = true)
        fixture.open().use { it.stage(plan) }
        fixture.raw().use { db ->
            db.execSQL("CREATE TRIGGER fixture_abort BEFORE UPDATE ON artifacts WHEN NEW.role='VIDEO_METADATA' BEGIN SELECT RAISE(ABORT, 'fixture'); END")
        }
        fixture.open().use { store ->
            assertThrows(SQLiteException::class.java) { store.seal(plan.id, 0, plan.artifacts.map { publication(it) }) }
            val retained = requireNotNull(store.load(plan.id))
            assertFalse(retained.sealed)
            assertEquals(0L, retained.revision)
            assertTrue(retained.artifacts.all { it.revision == 0L && it.modifiedSeconds == null })
        }
        fixture.raw().use { it.execSQL("DROP TRIGGER fixture_abort") }
        fixture.open().use { assertNotNull(it.seal(plan.id, 0, plan.artifacts.map { spec -> publication(spec) })) }
    }

    @Test fun duplicateArtifactIdentityCannotLeaveAnOrphanBundle() = database { fixture ->
        fixture.open().use { store ->
            val first = plan()
            store.stage(first)
            val other = first.copy(id = id(99))
            assertThrows(SQLiteException::class.java) { store.stage(other) }
            assertNull(store.load(other.id))
            assertEquals(1, store.list().size)
        }
    }

    @Test fun sqlitePreservesLongsBeyondDoublePrecisionWithoutClamping() = database { fixture ->
        val plan = plan().let { it.copy(endpointRevision = Long.MAX_VALUE, artifacts = listOf(it.artifacts.single().copy(sizeBytes = Long.MAX_VALUE))) }
        val modified = 9_007_199_254_740_993L
        fixture.open().use { store ->
            store.stage(plan)
            store.seal(plan.id, 0, plan.artifacts.map { publication(it, modified) })
        }
        fixture.open().use { store ->
            val reopened = requireNotNull(store.load(plan.id))
            assertEquals(Long.MAX_VALUE, reopened.endpointRevision)
            assertEquals(Long.MAX_VALUE, reopened.artifacts.single().spec.sizeBytes)
            assertEquals(modified, reopened.artifacts.single().modifiedSeconds)
        }
        fixture.raw().use { db ->
            db.rawQuery("SELECT typeof(size_bytes), size_bytes, typeof(modified_seconds), modified_seconds FROM artifacts", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("integer", cursor.getString(0)); assertEquals(Long.MAX_VALUE, cursor.getLong(1))
                assertEquals("integer", cursor.getString(2)); assertEquals(modified, cursor.getLong(3))
            }
        }
    }

    @Test fun corruptEnumUriAndNumericStorageAreRejectedWithoutDeletingRows() = database { fixture ->
        fixture.open().use { it.stage(plan()) }
        for ((column, value, restore) in listOf(
            Triple("state", "'FUTURE_STATE'", "'QUEUED'"),
            Triple("source_uri", "'https://private.example/secret'", "'content://media/external/video/media/1'"),
            Triple("size_bytes", "100.5", "100"),
        )) {
            fixture.raw().use { it.execSQL("UPDATE artifacts SET $column=$value") }
            val retainedBytes = fixture.bytes()
            fixture.open().use { store -> assertThrows(WebDavOutboxCorruptData::class.java) { store.load(id(1)) } }
            assertArrayEquals(retainedBytes, fixture.bytes())
            fixture.raw().use { db ->
                db.rawQuery("SELECT count(*) FROM artifacts", null).use { cursor -> cursor.moveToFirst(); assertEquals(1, cursor.getInt(0)) }
                db.execSQL("UPDATE artifacts SET $column=$restore")
            }
        }
        fixture.open().use { assertNotNull(it.load(id(1))) }
    }

    @Test fun newerSchemaIsPreservedAndNeverResetToAnEmptyQueue() = database { fixture ->
        fixture.open().use { it.stage(plan()) }
        fixture.raw().use { it.version = 2 }
        val retainedBytes = fixture.bytes()
        fixture.open().use { store -> assertThrows(SQLiteException::class.java) { store.load(id(1)) } }
        assertArrayEquals(retainedBytes, fixture.bytes())
        fixture.raw().use { db ->
            assertEquals(2, db.version)
            db.rawQuery("SELECT count(*) FROM bundles", null).use { cursor -> cursor.moveToFirst(); assertEquals(1, cursor.getInt(0)) }
        }
    }

    @Test fun corruptDatabaseHeaderIsPreservedInsteadOfDefaultAutomaticDeletion() = database { fixture ->
        fixture.open().use { it.stage(plan()) }
        val damaged = fixture.bytes().apply { "NOT A SQLITE DB!".toByteArray(Charsets.US_ASCII).copyInto(this) }
        fixture.replaceBytes(damaged)
        fixture.open().use { store -> assertThrows(SQLiteException::class.java) { store.load(id(1)) } }
        assertArrayEquals(damaged, fixture.bytes())
    }

    @Test fun reconciliation404RecordsEligibleAdmissionAndDoesNotRetryChangedLocalBytes() = database { fixture ->
        fixture.open().use { store ->
            val ready = prepare(store)
            val put = requireNotNull(store.claim(id(1), id(2), ready.artifacts.single().revision, id(20), WebDavAttemptKind.PUT, admission))
            assertTrue(store.finishPut(put, WebDavPutOutcome.REMOTE_UNCERTAIN))
            val uncertain = requireNotNull(store.load(id(1))).artifacts.single()
            val cellular = admission.copy(policyRevision = admission.policyRevision + 1,
                policy = admission.policy.copy(network = WebDavNetwork.CELLULAR, allowCellular = true))
            val verify = requireNotNull(store.claim(id(1), id(2), uncertain.revision, id(21), WebDavAttemptKind.RECONCILE, cellular))
            assertTrue(store.reconcile(verify, WebDavReconciliationEvidence(publication(uncertain.spec), "b".repeat(64), WebDavRemoteObservation.NotFound404)))
            val stopped = requireNotNull(store.load(id(1))).artifacts.single()
            assertEquals(WebDavArtifactState.SOURCE_UNAVAILABLE, stopped.state)
            assertEquals(cellular, stopped.lastAdmission)
            assertThrows(IllegalArgumentException::class.java) { store.claim(id(1), id(2), stopped.revision, id(22), WebDavAttemptKind.PUT, cellular) }
        }
    }

    @Test fun twoIndependentConnectionsCannotClaimTheSameRevision() = database { fixture ->
        val ready = fixture.open().use { prepare(it) }
        val executor = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val requests = (0..1).map { index -> executor.submit<WebDavOutboxLease?> {
                fixture.open().use { store ->
                    start.await()
                    store.claim(id(1), id(2), ready.artifacts.single().revision, id(20 + index), WebDavAttemptKind.PUT, admission)
                }
            } }
            start.countDown()
            assertEquals(1, requests.map { it.get(10, TimeUnit.SECONDS) }.count { it != null })
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test fun missingSourceBeforeHashAndItsRecoverySurviveReopen() = database { fixture ->
        val plan = plan()
        val failure = WebDavSourceFailure(plan.artifacts.single().sourceUri, WebDavSourceFailureReason.MISSING)
        fixture.open().use { store ->
            store.stage(plan)
            val sealed = requireNotNull(store.seal(plan.id, 0, plan.artifacts.map { publication(it) }))
            assertTrue(store.sourceUnavailable(plan.id, id(2), sealed.artifacts.single().revision, failure))
        }
        fixture.open().use { store ->
            val artifact = requireNotNull(store.load(plan.id)).artifacts.single()
            assertEquals(failure, artifact.sourceFailure)
            assertEquals(WebDavArtifactState.SOURCE_UNAVAILABLE, artifact.state)
            assertEquals(11L, artifact.modifiedSeconds)
            assertNull(artifact.sha256); assertNull(artifact.lastAdmission); assertFalse(artifact.remoteMayExist)
            assertNotNull(store.sourceRecovered(plan.id, artifact.revision, publication(artifact.spec), hash))
        }
        fixture.open().use { store ->
            val recovered = requireNotNull(store.load(plan.id)).artifacts.single()
            assertEquals(WebDavArtifactState.QUEUED, recovered.state)
            assertEquals(hash, recovered.sha256)
            assertNull(recovered.sourceFailure); assertFalse(recovered.remoteMayExist)
        }
    }

    @Test fun actuallyDeletedMediaStoreRowPersistsMissingWithoutInventingSnapshot() = database { fixture ->
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "WebDav-outbox-${System.nanoTime()}.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        var deleted = false
        try {
            val bytes = ByteArray(1024) { it.toByte() }
            requireNotNull(resolver.openOutputStream(uri)).use { it.write(bytes) }
            val source = WebDavMediaStoreClip(resolver, uri)
            val spec = WebDavArtifactSpec(id(2), WebDavArtifactRole.VIDEO, uri.toString(), source.snapshot().displayName, bytes.size.toLong())
            val actualPlan = WebDavBundlePlan(id(1), id(9), 0, listOf(spec))
            fixture.open().use { store ->
                store.stage(actualPlan)
                assertEquals(1, resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null))
                val sealed = requireNotNull(store.seal(id(1), 0, listOf(WebDavArtifactPublication(id(2), source.snapshot()))))
                val rows = resolver.delete(uri, null, null)
                deleted = rows == 1
                assertEquals(1, rows)
                assertThrows(FileNotFoundException::class.java) { source.snapshot() }
                assertTrue(store.sourceUnavailable(id(1), id(2), sealed.artifacts.single().revision,
                    WebDavSourceFailure(uri.toString(), WebDavSourceFailureReason.MISSING)))
            }
            fixture.open().use { store ->
                val missing = requireNotNull(store.load(id(1))).artifacts.single()
                assertEquals(uri.toString(), missing.spec.sourceUri)
                assertEquals(WebDavSourceFailureReason.MISSING, missing.sourceFailure?.reason)
                assertEquals(WebDavArtifactState.SOURCE_UNAVAILABLE, missing.state)
                assertNull(missing.sha256)
                assertFalse(missing.remoteMayExist)
            }
        } finally {
            if (!deleted) resolver.delete(uri, null, null)
        }
    }

    @Test fun missingSourceDuringPutInvalidatesStaleCompletionAndRecoversOnlyToUncertain() = database { fixture ->
        val lease = fixture.open().use { store ->
            val ready = prepare(store)
            val claimed = requireNotNull(store.claim(id(1), id(2), ready.artifacts.single().revision, id(20), WebDavAttemptKind.PUT, admission))
            val failure = WebDavSourceFailure(ready.artifacts.single().spec.sourceUri, WebDavSourceFailureReason.READ_FAILED)
            assertFalse(store.sourceUnavailable(id(1), id(2), ready.artifacts.single().revision, failure))
            assertFalse(store.sourceUnavailable(id(1), id(2), claimed.artifactRevision, failure))
            assertTrue(store.sourceUnavailable(claimed, failure))
            claimed
        }
        fixture.open().use { store ->
            val missing = requireNotNull(store.load(id(1))).artifacts.single()
            assertTrue(missing.remoteMayExist)
            assertEquals(WebDavSourceFailureReason.READ_FAILED, missing.sourceFailure?.reason)
            assertFalse(store.finishPut(lease, WebDavPutOutcome.DEFINITELY_NOT_STARTED))
            assertFalse(store.sourceUnavailable(lease, requireNotNull(missing.sourceFailure)))
            val recovered = requireNotNull(store.sourceRecovered(id(1), missing.revision, publication(missing.spec), hash))
            assertEquals(WebDavArtifactState.UNCERTAIN, recovered.artifacts.single().state)
            assertThrows(IllegalArgumentException::class.java) {
                store.claim(id(1), id(2), recovered.artifacts.single().revision, id(22), WebDavAttemptKind.PUT, admission)
            }
        }
        fixture.open().use { store ->
            val uncertain = requireNotNull(store.load(id(1))).artifacts.single()
            val reconciliation = requireNotNull(store.claim(id(1), id(2), uncertain.revision, id(23), WebDavAttemptKind.RECONCILE, admission))
            assertTrue(store.reconcile(reconciliation, WebDavReconciliationEvidence(publication(uncertain.spec), hash, WebDavRemoteObservation.NotFound404)))
        }
        fixture.open().use { store ->
            val retry = requireNotNull(store.load(id(1))).artifacts.single()
            assertEquals(WebDavArtifactState.QUEUED, retry.state)
            assertFalse(retry.remoteMayExist)
        }
    }

    @Test fun sourceFailureRejectsWrongUriAndRecoveryCannotReplaceFixedFingerprint() = database { fixture ->
        fixture.open().use { store ->
            val ready = prepare(store)
            val artifact = ready.artifacts.single()
            assertThrows(IllegalArgumentException::class.java) {
                store.sourceUnavailable(id(1), id(2), artifact.revision,
                    WebDavSourceFailure("content://media/external/video/media/99", WebDavSourceFailureReason.MISSING))
            }
            assertEquals(ready, store.load(id(1)))
            assertTrue(store.sourceUnavailable(id(1), id(2), artifact.revision,
                WebDavSourceFailure(artifact.spec.sourceUri, WebDavSourceFailureReason.ACCESS_DENIED)))
            val stopped = requireNotNull(store.load(id(1)))
            assertThrows(IllegalArgumentException::class.java) {
                store.sourceRecovered(id(1), stopped.artifacts.single().revision, publication(artifact.spec), "b".repeat(64))
            }
            assertThrows(IllegalArgumentException::class.java) {
                store.sourceRecovered(id(1), stopped.artifacts.single().revision, publication(artifact.spec, 12), hash)
            }
            assertEquals(stopped, store.load(id(1)))
        }
    }

    @Test fun corruptPresenceFlagOrFailureReasonIsRejectedAndPreserved() = database { fixture ->
        fixture.open().use { store ->
            val plan = plan()
            store.stage(plan)
            val sealed = requireNotNull(store.seal(plan.id, 0, plan.artifacts.map { publication(it) }))
            store.sourceUnavailable(id(1), id(2), sealed.artifacts.single().revision,
                WebDavSourceFailure(plan.artifacts.single().sourceUri, WebDavSourceFailureReason.MISSING))
        }
        for ((column, invalid, restore) in listOf(
            Triple("remote_may_exist", "1", "0"), Triple("source_failure", "'FUTURE_REASON'", "'MISSING'"),
            Triple("source_failure", "NULL", "'MISSING'"),
        )) {
            fixture.raw().use { it.execSQL("UPDATE artifacts SET $column=$invalid") }
            val preserved = fixture.bytes()
            fixture.open().use { store -> assertThrows(WebDavOutboxCorruptData::class.java) { store.load(id(1)) } }
            assertArrayEquals(preserved, fixture.bytes())
            fixture.raw().use { it.execSQL("UPDATE artifacts SET $column=$restore") }
        }
        fixture.raw().use { db -> assertThrows(SQLiteException::class.java) { db.execSQL("UPDATE artifacts SET remote_may_exist=2") } }
        fixture.open().use { assertEquals(WebDavSourceFailureReason.MISSING, it.load(id(1))?.artifacts?.single()?.sourceFailure?.reason) }
    }

    @Test fun localRenameHoldPersistsIntentWithoutRebindingNamesHashesOrUnrelatedBundles() = database { f ->
        val first=plan(withMetadata=true)
        val other=WebDavBundlePlan(id(50),id(9),0,listOf(WebDavArtifactSpec(id(51),WebDavArtifactRole.VIDEO,
            "content://media/external/video/media/50","other.mp4",100)))
        lateinit var held:WebDavOutboxBundle
        f.open().use { store ->
            for (plan in listOf(first,other)) { store.stage(plan);store.seal(plan.id,0,plan.artifacts.map { publication(it) }) }
            val original=requireNotNull(store.load(first.id));val neighbor=store.load(other.id)
            held=store.holdSourcesForLocalRename(first.artifacts.map { it.sourceUri }.toSet()).single()
            assertEquals(WebDavBundleState.ATTENTION,held.state)
            assertEquals(original.artifacts.map { it.spec },held.artifacts.map { it.spec })
            assertTrue(held.artifacts.all { it.state==WebDavArtifactState.SOURCE_UNAVAILABLE &&
                it.sourceFailure?.reason==WebDavSourceFailureReason.LOCAL_RENAME_REQUESTED && !it.remoteMayExist && it.sha256==null })
            assertEquals(neighbor,store.load(other.id))
            assertEquals(listOf(held),store.holdSourcesForLocalRename(first.artifacts.map { it.sourceUri }.toSet()))
        }
        f.open().use { assertEquals(held,it.load(first.id)) }
    }

    @Test fun localRenameHoldPreservesVerifiedAndConflictingRemoteHistory() {
        for (conflict in listOf(false,true)) database { f ->
            f.open().use { store ->
                val ready=prepare(store);val spec=ready.artifacts.single().spec
                val put=requireNotNull(store.claim(ready.id,spec.id,ready.artifacts.single().revision,id(20),WebDavAttemptKind.PUT,admission))
                assertTrue(store.finishPut(put,WebDavPutOutcome.ACKNOWLEDGED))
                val current=requireNotNull(store.load(ready.id)).artifacts.single()
                val get=requireNotNull(store.claim(ready.id,spec.id,current.revision,id(21),WebDavAttemptKind.RECONCILE,admission))
                assertTrue(store.reconcile(get,WebDavReconciliationEvidence(publication(spec),hash,
                    WebDavRemoteObservation.CompleteBody(spec.sizeBytes,if(conflict) "b".repeat(64) else hash))))
                val before=requireNotNull(store.load(ready.id))
                assertEquals(if(conflict) WebDavArtifactState.CONFLICT else WebDavArtifactState.VERIFIED,before.artifacts.single().state)
                assertEquals(listOf(before),store.holdSourcesForLocalRename(setOf(spec.sourceUri)))
                assertEquals(before,store.load(ready.id))
            }
        }
    }

    @Test fun localRenameHoldKeepsUncertainRemotePresenceAndRequiresReconciliationAfterRecovery() = database { f ->
        f.open().use { store ->
            val ready=prepare(store);val artifact=ready.artifacts.single()
            val put=requireNotNull(store.claim(ready.id,artifact.spec.id,artifact.revision,id(20),WebDavAttemptKind.PUT,admission))
            assertTrue(store.finishPut(put,WebDavPutOutcome.REMOTE_UNCERTAIN))
            val before=requireNotNull(store.load(ready.id)).artifacts.single()
            val held=store.holdSourcesForLocalRename(setOf(artifact.spec.sourceUri)).single().artifacts.single()
            assertEquals(WebDavArtifactState.SOURCE_UNAVAILABLE,held.state)
            assertTrue(held.remoteMayExist);assertEquals(before.sha256,held.sha256);assertEquals(before.lastAdmission,held.lastAdmission)
            assertEquals(before.spec,held.spec)
            val recovered=requireNotNull(store.sourceRecovered(ready.id,held.revision,publication(held.spec),hash)).artifacts.single()
            assertEquals(WebDavArtifactState.UNCERTAIN,recovered.state);assertTrue(recovered.remoteMayExist)
        }
    }

    @Test fun localRenameHoldRejectsAnyUnsealedOrActiveMatchBeforeChangingAnEarlierBundle() {
        for (active in listOf(false,true)) database { f ->
            f.open().use { store ->
                val ready=prepare(store)
                val second=WebDavBundlePlan(id(50),id(9),0,listOf(WebDavArtifactSpec(id(51),WebDavArtifactRole.VIDEO,
                    "content://media/external/video/media/50","other.mp4",100)))
                store.stage(second)
                if(active) {
                    store.seal(second.id,0,second.artifacts.map { publication(it) })
                    val sealed=requireNotNull(store.load(second.id)).artifacts.single()
                    store.recordHash(second.id,sealed.revision,publication(sealed.spec),hash)
                    val hashed=requireNotNull(store.load(second.id)).artifacts.single()
                    assertNotNull(store.claim(second.id,hashed.spec.id,hashed.revision,id(52),WebDavAttemptKind.PUT,admission))
                }
                val before=store.list(1024)
                assertThrows(IllegalStateException::class.java) {
                    store.holdSourcesForLocalRename((ready.artifacts+requireNotNull(store.load(second.id)).artifacts).map { it.spec.sourceUri }.toSet())
                }
                assertEquals(before,store.list(1024))
            }
        }
    }

    @Test fun localRenameHoldSqlFailureRollsBackTheEntireSnapshot() = database { f ->
        f.open().use { store ->
            val plan=plan(withMetadata=true);store.stage(plan);store.seal(plan.id,0,plan.artifacts.map { publication(it) })
            val before=store.load(plan.id)
            f.raw().use { it.execSQL("CREATE TRIGGER abort_local_rename BEFORE UPDATE ON artifacts WHEN NEW.source_failure='LOCAL_RENAME_REQUESTED' BEGIN SELECT RAISE(ABORT,'fixture rename hold failure'); END") }
            assertThrows(SQLiteException::class.java) { store.holdSourcesForLocalRename(plan.artifacts.map { it.sourceUri }.toSet()) }
            assertEquals(before,store.load(plan.id))
        }
    }

    private fun prepare(store: WebDavOutboxStore): WebDavOutboxBundle {
        val plan = plan()
        store.stage(plan)
        val sealed = requireNotNull(store.seal(plan.id, 0, plan.artifacts.map { publication(it) }))
        return requireNotNull(store.recordHash(plan.id, sealed.artifacts.single().revision, publication(plan.artifacts.single()), hash))
    }
    private fun plan(withMetadata: Boolean = false): WebDavBundlePlan {
        val video = WebDavArtifactSpec(id(2), WebDavArtifactRole.VIDEO, "content://media/external/video/media/1", "take.mp4", 100)
        return WebDavBundlePlan(id(1), id(9), 0, if (withMetadata) listOf(video,
            WebDavArtifactSpec(id(3), WebDavArtifactRole.VIDEO_METADATA, "content://media/external/downloads/2", "take.timing.json", 50)) else listOf(video))
    }
    private fun publication(spec: WebDavArtifactSpec, modified: Long = 11) = WebDavArtifactPublication(spec.id,
        WebDavClipSnapshot(spec.sourceUri, spec.sourceName, spec.sizeBytes, modified, true))
    private fun id(value: Int) = "00000000-0000-0000-0000-${value.toString().padStart(12, '0')}"
    private fun database(test: (DatabaseFixture) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "webdav-outbox-test-${UUID.randomUUID()}.db"
        try { test(DatabaseFixture(context, name)) } finally { context.deleteDatabase(name) }
    }
    private class DatabaseFixture(private val context: Context, private val name: String) {
        fun open() = WebDavSqliteOutbox(context, name)
        fun raw(): SQLiteDatabase = SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE)
        fun bytes(): ByteArray = context.getDatabasePath(name).readBytes()
        fun replaceBytes(value: ByteArray) { context.getDatabasePath(name).writeBytes(value) }
    }
}
