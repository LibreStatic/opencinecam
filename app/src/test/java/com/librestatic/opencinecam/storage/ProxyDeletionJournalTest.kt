/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProxyDeletionJournalTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun intent(): ProxyDeletionIntent {
        val id = UUID.randomUUID().toString()
        val original = "content://media/external_primary/video/media/1"
        return ProxyDeletionIntent(id, "legacy:$original", original, "a".repeat(64),
            ProxyDeletionMember("content://media/external_primary/video/media/2", "proxy-$id.mp4", "video/mp4",
                "Movies/OpenCineCamProxies/$id/", "com.example.camera", 4096, "b".repeat(64)),
            ProxyDeletionMember("content://media/external_primary/downloads/3", "proxy-$id.json", "application/json",
                "Download/OpenCineCamProxies/$id/", "com.example.camera", 1024, "c".repeat(64)))
    }
    private open class Access(val intent: ProxyDeletionIntent, val journal: ProxyDeletionJournal) : ProxyDeletionAccess {
        val present = mutableSetOf(intent.video.uri, intent.metadata.uri)
        val events = mutableListOf<String>()
        var commits = 0
        override suspend fun validate(intent: ProxyDeletionIntent) { assertEquals(this.intent, intent); events += "validate" }
        override suspend fun isAbsent(member: ProxyDeletionMember): Boolean {
            assertNotEquals(intent.originalUri, member.uri)
            events += "absent:${member.name}"; return member.uri !in present
        }
        override suspend fun delete(member: ProxyDeletionMember) {
            assertEquals(intent, journal.read(intent.jobId))
            assertNotEquals(intent.originalUri, member.uri)
            events += "delete:${member.name}"; assertTrue(present.remove(member.uri))
        }
        override suspend fun verifyEmpty(intent: ProxyDeletionIntent) { assertTrue(present.isEmpty()); events += "empty" }
        override suspend fun commitDeletion(intent: ProxyDeletionIntent) {
            assertEquals(intent, journal.read(intent.jobId)); assertTrue(present.isEmpty())
            commits++; events += "commit"
        }
    }
    private suspend fun fails(block: suspend () -> Unit): Exception {
        try { block() } catch (failure: Exception) { return failure }
        error("Expected failure")
    }

    @Test fun confirmedPairPersistsBeforeDeleteAndRetiresOnlyAfterQueueCommit() = runBlocking<Unit> {
        val journal = ProxyDeletionJournal(temporary.newFolder()); val intent = intent(); val access = Access(intent, journal)
        journal.begin(intent, access)
        assertEquals(listOf("validate", "absent:${intent.video.name}", "delete:${intent.video.name}", "absent:${intent.video.name}",
            "absent:${intent.metadata.name}", "delete:${intent.metadata.name}", "absent:${intent.metadata.name}", "empty", "commit"), access.events)
        assertEquals(1, access.commits); assertNull(journal.read(intent.jobId)); assertTrue(journal.pending().isEmpty())
        assertFalse(journal.recover(intent.jobId, access)); assertEquals(1, access.commits)
    }

    @Test fun rejectedConfirmationWritesNoTombstoneAndDeletesNothing() = runBlocking<Unit> {
        val journal = ProxyDeletionJournal(temporary.newFolder()); val intent = intent()
        val access = object : Access(intent, journal) {
            override suspend fun validate(intent: ProxyDeletionIntent) { error("metadata identity changed") }
        }
        assertEquals("metadata identity changed", fails { journal.begin(intent, access) }.message)
        assertNull(journal.read(intent.jobId)); assertEquals(2, access.present.size); assertEquals(0, access.commits)
    }

    @Test fun interruptedAfterFirstRowRecoversRemainingIdentityWithoutReceiptOrOriginal() = runBlocking<Unit> {
        val directory = temporary.newFolder(); val journal = ProxyDeletionJournal(directory); val intent = intent()
        var interrupted = true
        val access = object : Access(intent, journal) {
            override suspend fun delete(member: ProxyDeletionMember) {
                super.delete(member)
                if (interrupted) { interrupted = false; error("process interrupted after video delete") }
            }
        }
        fails { journal.begin(intent, access) }
        assertEquals(setOf(intent.metadata.uri), access.present)
        val reopened = ProxyDeletionJournal(directory)
        assertEquals(listOf(intent), reopened.pending())
        assertTrue(reopened.recover(intent.jobId, access))
        assertEquals(1, access.events.count { it == "delete:${intent.video.name}" })
        assertEquals(1, access.events.count { it == "delete:${intent.metadata.name}" })
        assertTrue(access.present.isEmpty()); assertEquals(1, access.commits); assertNull(reopened.read(intent.jobId))
    }

    @Test fun survivingIdentityChangeHaltsRecoveryBeforeAnyNewDeletion() = runBlocking<Unit> {
        val journal = ProxyDeletionJournal(temporary.newFolder()); val intent = intent()
        val stopped = object : Access(intent, journal) {
            override suspend fun delete(member: ProxyDeletionMember) { error("provider unavailable") }
        }
        fails { journal.begin(intent, stopped) }
        val changed = object : Access(intent, journal) {
            override suspend fun validate(intent: ProxyDeletionIntent) { error("survivor hash differs") }
        }
        assertEquals("survivor hash differs", fails { journal.recover(intent.jobId, changed) }.message)
        assertEquals(intent, journal.read(intent.jobId)); assertEquals(2, changed.present.size); assertTrue(changed.events.isEmpty())
    }

    @Test fun unknownAbsenceOrRemainingNamespaceNeverCommits() = runBlocking<Unit> {
        for (namespaceFailure in listOf(false, true)) {
            val journal = ProxyDeletionJournal(temporary.newFolder()); val intent = intent()
            val access = object : Access(intent, journal) {
                override suspend fun isAbsent(member: ProxyDeletionMember): Boolean {
                    if (!namespaceFailure && member.uri !in present) error("absence query unavailable")
                    return super.isAbsent(member)
                }
                override suspend fun verifyEmpty(intent: ProxyDeletionIntent) { error("unexpected namespace member") }
            }
            fails { journal.begin(intent, access) }
            assertEquals(0, access.commits); assertEquals(intent, journal.read(intent.jobId))
        }
    }

    @Test fun commitFailureAndCrashAfterCommitRepeatTransitionWithoutDeletingTwice() = runBlocking<Unit> {
        val directory = temporary.newFolder(); val journal = ProxyDeletionJournal(directory); val intent = intent()
        var crash = true
        val access = object : Access(intent, journal) {
            override suspend fun commitDeletion(intent: ProxyDeletionIntent) {
                super.commitDeletion(intent)
                if (crash) { crash = false; error("queue transition durable but process died") }
            }
        }
        fails { journal.begin(intent, access) }
        assertTrue(access.present.isEmpty()); assertEquals(intent, journal.read(intent.jobId))
        assertTrue(ProxyDeletionJournal(directory).recover(intent.jobId, access))
        assertEquals(2, access.commits); assertEquals(2, access.events.count { it.startsWith("delete:") })
        assertTrue(journal.pending().isEmpty())
    }

    @Test fun cancellationAfterConfirmationWaitsForRetirement() = runBlocking<Unit> {
        val journal = ProxyDeletionJournal(temporary.newFolder()); val intent = intent()
        val reached = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val access = object : Access(intent, journal) {
            override suspend fun delete(member: ProxyDeletionMember) {
                if (member == intent.video) { reached.complete(Unit); release.await() }
                super.delete(member)
            }
        }
        val worker = launch { journal.begin(intent, access) }
        try {
            reached.await(); worker.cancel(); yield()
            assertFalse(worker.isCompleted); assertEquals(intent, journal.read(intent.jobId)); assertEquals(0, access.commits)
            release.complete(Unit); worker.join()
            assertTrue(worker.isCompleted); assertEquals(1, access.commits); assertNull(journal.read(intent.jobId))
        } finally { release.complete(Unit); worker.cancelAndJoin() }
    }

    @Test fun malformedOrDifferentIdentityNeverReplacesCommittedIntent() = runBlocking<Unit> {
        val directory = temporary.newFolder(); val journal = ProxyDeletionJournal(directory); val intent = intent()
        val stopped = object : Access(intent, journal) { override suspend fun delete(member: ProxyDeletionMember) { error("stop") } }
        fails { journal.begin(intent, stopped) }
        val file = File(directory, "${intent.jobId}.json"); val bytes = file.readBytes()
        fails { journal.begin(intent.copy(video = intent.video.copy(sha256 = "d".repeat(64))), stopped) }
        assertArrayEquals(bytes, file.readBytes())
        fails { journal.begin(intent.copy(video = intent.video.copy(uri = intent.originalUri)), stopped) }
        assertArrayEquals(bytes, file.readBytes())
        file.writeText("{broken")
        fails { journal.pending() }; fails { journal.recover(intent.jobId, stopped) }
        assertEquals("{broken", file.readText()); assertEquals(0, stopped.commits)
    }

    @Test fun duplicateKeysUnknownFieldsAndOversizedInputRejectWithoutEffects() = runBlocking<Unit> {
        val directory = temporary.newFolder(); val journal = ProxyDeletionJournal(directory); val intent = intent()
        val stopped = object : Access(intent, journal) { override suspend fun delete(member: ProxyDeletionMember) { error("stop") } }
        fails { journal.begin(intent, stopped) }
        val file = File(directory, "${intent.jobId}.json"); val original = file.readText()
        for (bad in listOf(original.replaceFirst("{", "{\"version\":1,"), original.replaceFirst("{", "{\"unknown\":1,"), " ".repeat(16385))) {
            file.writeText(bad)
            fails { journal.read(intent.jobId) }; assertEquals(bad, file.readText())
        }
        assertEquals(0, stopped.commits)
    }

    @Test fun uncommittedStagingNeverAuthorizesDeletionAndSymlinksReject() = runBlocking<Unit> {
        val directory = temporary.newFolder(); val journal = ProxyDeletionJournal(directory); val intent = intent()
        val stopped = object : Access(intent, journal) { override suspend fun delete(member: ProxyDeletionMember) { error("stop") } }
        fails { journal.begin(intent, stopped) }
        val file = File(directory, "${intent.jobId}.json"); val staging = File(directory, "${intent.jobId}.tmp")
        assertTrue(file.renameTo(staging))
        assertTrue(journal.pending().isEmpty()); assertFalse(journal.recover(intent.jobId, stopped))
        assertEquals(2, stopped.present.size)
        java.nio.file.Files.createSymbolicLink(file.toPath(), staging.toPath())
        fails { journal.read(intent.jobId) }; fails { journal.pending() }
        assertTrue(staging.isFile)
    }
    @Test fun platformParentAliasWorksButJournalDirectoryAliasRejects() = runBlocking<Unit> {
        val parent = temporary.newFolder()
        val alias = File(temporary.root, "platform-alias")
        java.nio.file.Files.createSymbolicLink(alias.toPath(), parent.toPath())
        val journal = ProxyDeletionJournal(File(alias, "journal"))
        val intent = intent()
        journal.begin(intent, Access(intent, journal))
        assertTrue(File(parent, "journal").isDirectory)
        assertTrue(journal.pending().isEmpty())
        val ownAlias = File(parent, "own-alias")
        java.nio.file.Files.createSymbolicLink(ownAlias.toPath(), File(parent, "journal").toPath())
        fails { ProxyDeletionJournal(ownAlias).pending() }
    }
    @Test fun customProxyFilenameSurvivesDurablePartialDeletionRecovery() = runBlocking<Unit> {
        val directory = temporary.newFolder()
        val base = intent()
        val selected = base.copy(video = base.video.copy(name = proxyFilename("Camera_é_01")))
        val journal = ProxyDeletionJournal(directory)
        val access = object : Access(selected, journal) {
            override suspend fun delete(member: ProxyDeletionMember) {
                super.delete(member)
                error("after custom video delete")
            }
        }
        fails { journal.begin(selected, access) }
        assertEquals(selected, ProxyDeletionJournal(directory).read(selected.jobId))
        assertEquals(setOf(selected.metadata.uri), access.present)
        val recovered = Access(selected, journal).apply { present.remove(selected.video.uri) }
        assertTrue(ProxyDeletionJournal(directory).recover(selected.jobId, recovered))
        assertEquals(1, recovered.commits)
        assertTrue(recovered.present.isEmpty()); assertNull(journal.read(selected.jobId))
    }
}
