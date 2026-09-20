/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import org.junit.Assert.*
import org.junit.Test

class MediaDeletePlanTest {
    private val id = "2b2a7bb1-5061-4379-86c1-cd7cbca97e42"
    private val namespace = MediaNamespace(id, true)
    private val owner = "app.owner"
    private val video = LocalMediaArtifact("content://media/external_primary/video/media/1", "primary.mp4", "video/mp4", 100, 123)
    private val audio = LocalMediaArtifact("content://media/external_primary/audio/media/2", "secondary.wav", "audio/wav", 50, 124)
    private val metadata = LocalMediaArtifact("content://media/external_primary/downloads/3", "metadata.json", "application/json", 20, 125)
    private val selected = LocalMediaTake(namespace.key, video, listOf(video, audio), listOf(metadata), LocalMediaKind.VIDEO, null, LocalMediaRelationStatus.MISSING_METADATA)
    private val observed get() = listOf(MediaDeleteRow(video, namespace.path("DCIM"), owner, 0),
        MediaDeleteRow(audio, namespace.path("Music"), owner, 0), MediaDeleteRow(metadata, namespace.path("Download"), owner, 0))
    private fun rejected(block: () -> Unit) { assertTrue(runCatching(block).isFailure) }
    private inner class Access : MediaDeleteAccess {
        val events = mutableListOf<String>()
        val remaining = observed.map { it.artifact.uri }.toMutableSet()
        var reserveFailure = false
        var preflightFailure = false
        var admissionFailure = false
        var closeFailure = false
        var finalFailure = false
        var beforeDelete: (MediaDeleteRow) -> Int? = { null }
        var beforeProbe: (MediaDeleteRow) -> Unit = { }
        override fun reserve(): AutoCloseable {
            events += "reserve"
            check(!reserveFailure) { "Active transfer" }
            return AutoCloseable { events += "release"; check(!closeFailure) { "Guard release failed" } }
        }
        override fun preflight(selected: LocalMediaTake): MediaDeletePlan {
            events += "preflight"
            check(!preflightFailure) { "Uncertain complete namespace" }
            return mediaDeletePlan(selected, observed, owner)
        }
        override fun beginDeletion(plan: MediaDeletePlan) {
            events += "begin-deletion"
            check(!admissionFailure) { "Source queue persistence failed" }
        }
        override fun delete(row: MediaDeleteRow): Int {
            events += "delete:${row.artifact.name}"
            beforeDelete(row)?.let { return it }
            return if (remaining.remove(row.artifact.uri)) 1 else 0
        }
        override fun isAbsent(row: MediaDeleteRow): Boolean {
            events += "probe:${row.artifact.name}"
            beforeProbe(row)
            return row.artifact.uri !in remaining
        }
        override fun verifyEmpty(plan: MediaDeletePlan) {
            events += "verify-empty"
            check(!finalFailure && remaining.isEmpty()) { "Namespace uncertain or has new members" }
        }
    }

    @Test fun queueInvalidationPrecedesEveryDeletionAndFailurePreservesAllMembers() {
        val rejected = Access().apply { admissionFailure = true }
        val failed = executeMediaDelete(selected, rejected)
        assertFalse(failed.complete)
        assertTrue(failed.files.all { it.status == MediaDeleteStatus.NOT_ATTEMPTED })
        assertEquals(observed.map { it.artifact.uri }.toSet(), rejected.remaining)
        assertEquals(listOf("reserve", "preflight", "begin-deletion", "release"), rejected.events)
        val admitted = Access()
        assertTrue(executeMediaDelete(selected, admitted).complete)
        assertTrue(admitted.events.indexOf("begin-deletion") < admitted.events.indexOfFirst { it.startsWith("delete:") })
    }

    @Test fun completePlanOrdersSecondaryThenMetadataThenPrimary() {
        val plan = mediaDeletePlan(selected, observed.reversed(), owner)
        assertEquals(listOf(audio, metadata, video), plan.ordered.map { it.artifact })
        val access = Access()
        val result = executeMediaDelete(selected, access)
        assertTrue(result.complete); assertFalse(result.partial); assertNull(result.error)
        assertEquals(selected.originals + selected.metadata, result.files.map { it.artifact })
        assertTrue(result.files.all { it.status == MediaDeleteStatus.ABSENT_VERIFIED })
        assertEquals(listOf("delete:secondary.wav", "delete:metadata.json", "delete:primary.mp4"), access.events.filter { it.startsWith("delete:") })
        assertEquals("release", access.events.last())
        assertEquals(6, access.events.count { it.startsWith("probe:") })
    }
    @Test fun completePreflightAndGuardFailuresNeverMutateAnyMember() {
        val access = Access().apply { preflightFailure = true }
        val result = executeMediaDelete(selected, access)
        assertFalse(result.complete); assertFalse(result.partial)
        assertTrue(result.files.all { it.status == MediaDeleteStatus.NOT_ATTEMPTED })
        assertEquals(listOf("reserve", "preflight", "release"), access.events)
        val busy = Access().apply { reserveFailure = true }
        assertFalse(executeMediaDelete(selected, busy).complete)
        assertEquals(listOf("reserve"), busy.events)
    }
    @Test fun conditionalMissAfterSecondaryStopsAndPreservesPrimary() {
        val access = Access().apply { beforeDelete = { if (it.artifact == metadata) 0 else null } }
        val result = executeMediaDelete(selected, access)
        assertTrue(result.partial); assertFalse(result.complete)
        assertEquals(MediaDeleteStatus.ABSENT_VERIFIED, result.files.single { it.artifact == audio }.status)
        assertEquals(MediaDeleteStatus.RETAINED, result.files.single { it.artifact == metadata }.status)
        assertEquals(MediaDeleteStatus.NOT_ATTEMPTED, result.files.single { it.artifact == video }.status)
        assertTrue(video.uri in access.remaining)
        assertFalse(access.events.contains("delete:primary.mp4"))
    }
    @Test fun providerFailureAndUnknownProbeStopBeforeTheAnchor() {
        val access = Access().apply { beforeProbe = { if (it.artifact == audio) error("Provider query failed") } }
        val result = executeMediaDelete(selected, access)
        assertFalse(result.complete)
        assertEquals(MediaDeleteStatus.UNKNOWN, result.files.single { it.artifact == audio }.status)
        assertEquals(MediaDeleteStatus.NOT_ATTEMPTED, result.files.single { it.artifact == video }.status)
        assertFalse(audio.uri in access.remaining)
        assertTrue(metadata.uri in access.remaining && video.uri in access.remaining)
        assertEquals("release", access.events.last())
    }
    @Test fun thrownDeleteCanVerifyAbsenceButStillStopsRemainingRows() {
        val access = Access().apply { beforeDelete = { row -> remaining.remove(row.artifact.uri); error("Ambiguous delete failure") } }
        val result = executeMediaDelete(selected, access)
        assertTrue(result.partial); assertFalse(result.complete)
        assertEquals(MediaDeleteStatus.ABSENT_VERIFIED, result.files.single { it.artifact == audio }.status)
        assertTrue(metadata.uri in access.remaining && video.uri in access.remaining)
        assertEquals(1, access.events.count { it.startsWith("delete:") })
    }
    @Test fun zeroDeletionCountWithObservedAbsenceDoesNotPretendItDeletedBytes() {
        val access = Access().apply { remaining.remove(audio.uri) }
        val result = executeMediaDelete(selected, access)
        assertTrue(result.complete)
        assertEquals(MediaDeleteStatus.ABSENT_VERIFIED, result.files.single { it.artifact == audio }.status)
    }
    @Test fun impossibleProviderCountStopsEvenWhenTargetIsAbsent() {
        val access = Access().apply { beforeDelete = { row -> remaining.remove(row.artifact.uri); 2 } }
        val result = executeMediaDelete(selected, access)
        assertTrue(result.partial); assertFalse(result.complete)
        assertFalse(access.events.contains("delete:primary.mp4"))
    }
    @Test fun finalNamespaceUncertaintyNeverClaimsCompleteAndReleasesGuard() {
        val access = Access().apply { finalFailure = true }
        val result = executeMediaDelete(selected, access)
        assertFalse(result.complete); assertTrue(result.partial); assertNotNull(result.error)
        assertEquals("release", access.events.last())
    }
    @Test fun reappearingMemberInFinalPassMakesOutcomeRetainedNotComplete() {
        val access = Access()
        var audioProbes = 0
        access.beforeProbe = { row -> if (row.artifact == audio && ++audioProbes == 2) access.remaining += audio.uri }
        val result = executeMediaDelete(selected, access)
        assertFalse(result.complete)
        assertEquals(MediaDeleteStatus.RETAINED, result.files.single { it.artifact == audio }.status)
    }
    @Test fun guardReleaseFailureIsExplicitRatherThanSuccess() {
        val result = executeMediaDelete(selected, Access().apply { closeFailure = true })
        assertFalse(result.complete); assertTrue(result.partial)
        assertTrue(requireNotNull(result.error).contains("Guard release"))
    }
    @Test fun conditionalPredicateFreezesEveryObservedIdentityField() {
        val row = observed.first()
        val condition = mediaDeleteCondition(row)
        listOf("_id", "owner_package_name", "relative_path", "_display_name", "mime_type", "_size", "date_modified", "is_pending = 0")
            .forEach { assertTrue(condition.selection.contains(it)) }
        assertEquals(listOf("1", owner, namespace.path("DCIM"), video.name, video.mimeType, "100", "123"), condition.arguments)
        rejected { mediaDeleteCondition(row.copy(pending = 1)) }
        rejected { mediaDeleteCondition(row.copy(artifact = video.copy(uri = "file:///private"))) }
    }
    @Test fun mixedForeignPendingChangedAndOverflowSelectionsReject() {
        rejected { mediaDeletePlan(selected, observed.map { if (it.artifact == audio) it.copy(owner = "foreign") else it }, owner) }
        rejected { mediaDeletePlan(selected, observed.map { if (it.artifact == audio) it.copy(pending = 1) else it }, owner) }
        rejected { mediaDeletePlan(selected, observed.map { if (it.artifact == audio) it.copy(relativePath = "Music/Elsewhere/") else it }, owner) }
        rejected { mediaDeletePlan(selected, observed.dropLast(1), owner) }
        rejected { mediaDeletePlan(selected, observed + observed.first(), owner) }
        rejected { mediaDeletePlan(selected.copy(metadata = listOf(metadata, metadata)), observed, owner) }
        val changed = observed.map { it.copy(artifact = it.artifact.copy(modifiedSeconds = 999)) }
        rejected { mediaDeletePlan(selected, changed, owner) }
    }
    @Test fun incompleteAndCorruptRelationsDeleteOnlyActualCompleteObservedSet() {
        for (status in listOf(LocalMediaRelationStatus.INCOMPLETE, LocalMediaRelationStatus.INVALID_METADATA, LocalMediaRelationStatus.MISSING_METADATA)) {
            val plan = mediaDeletePlan(selected.copy(relationStatus = status), observed, owner)
            assertEquals(setOf(video, audio, metadata), plan.ordered.map { it.artifact }.toSet())
        }
        val forged = selected.copy(id = "take:11111111-1111-1111-1111-111111111111")
        rejected { mediaDeletePlan(forged, observed, owner) }
    }
    @Test fun legacyFilesAreIndividualAndCanonicalUriParserNeverAdmitsExtraRoutes() {
        val legacy = LocalMediaTake("legacy:${video.uri}", video, listOf(video), emptyList(), LocalMediaKind.VIDEO, null, LocalMediaRelationStatus.LEGACY)
        val row = MediaDeleteRow(video, "DCIM/OpenCineCam/", owner, 0)
        assertEquals(listOf(row), mediaDeletePlan(legacy, listOf(row), owner).ordered)
        rejected { mediaDeletePlan(legacy.copy(originals = listOf(video, audio)), listOf(row, observed[1]), owner) }
        listOf(video.uri + "?x=1", video.uri.replace("/1", "/01"), "content://other/external_primary/downloads/3", "file:///tmp/file")
            .forEach { assertNull(mediaDeleteIdentity(it)) }
        assertEquals(MediaDeleteCollection.METADATA, mediaDeleteIdentity(metadata.uri)?.collection)
    }
    @Test fun executorRejectsAPlanWithPrimaryFirstBeforeMutating() {
        val real = Access()
        val access = object : MediaDeleteAccess by real {
            override fun preflight(selected: LocalMediaTake): MediaDeletePlan = mediaDeletePlan(selected, observed, owner).let { it.copy(ordered = it.ordered.reversed()) }
        }
        val result = executeMediaDelete(selected, access)
        assertFalse(result.complete)
        assertTrue(result.files.all { it.status == MediaDeleteStatus.NOT_ATTEMPTED })
        assertFalse(real.events.any { it.startsWith("delete:") })
    }
}
