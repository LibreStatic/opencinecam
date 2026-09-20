/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import com.librestatic.opencinecam.ProductionSlateSettings
import com.librestatic.opencinecam.productionSlateJson
import com.librestatic.opencinecam.camera.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class MediaRenamePlanTest {
    private val id = "2b2a7bb1-5061-4379-86c1-cd7cbca97e42"
    private val namespace = MediaNamespace(id, false)
    private val owner = "app.owner"
    private val jpeg = LocalMediaArtifact("content://media/external_primary/images/media/1", "original.jpg", "image/jpeg", 12, 100)
    private val dng = LocalMediaArtifact("content://media/external_primary/images/media/2", "original.dng", "image/x-adobe-dng", 12, 101)
    private val slate = ProductionSlateSettings(project = "original.jpg", scene = "DO_NOT_REPLACE_EDITORIAL")
    private fun stillText(images: List<LocalMediaArtifact> = listOf(jpeg, dng)): String = buildJsonObject {
        put("schemaVersion", 1); put("bundleId", id); put("productionSlate", productionSlateJson(slate))
        put("images", buildJsonArray { images.forEach { artifact -> add(buildJsonObject {
            put("uri", artifact.uri); put("displayName", artifact.name); put("mimeType", artifact.mimeType)
            put("bytes", artifact.sizeBytes); put("sha256", "HASH_DECLARATION_NOT_REHASHED")
        }) } })
        put("recordingLut", buildJsonObject { put("originalCubeSha256", "a".repeat(64)) })
    }.toString()
    private data class Fixture(val take: LocalMediaTake, val rows: List<MediaDeleteRow>, val documents: List<MetadataDocument>)
    private fun fixture(text: String = stillText(), images: List<LocalMediaArtifact> = listOf(jpeg, dng), ns: MediaNamespace = namespace): Fixture {
        val meta = LocalMediaArtifact("content://media/external_primary/downloads/3", "original.still.json", "application/json", text.toByteArray().size.toLong(), 100)
        val documents = listOf(MetadataDocument(meta, text))
        val rows = images.map { artifact ->
            val kind = requireNotNull(mediaOriginalIdentity(artifact.uri)).first
            MediaDeleteRow(artifact, ns.path(if (kind == LocalMediaKind.AUDIO) "Music" else "DCIM"), owner, 0)
        } + MediaDeleteRow(meta, ns.path("Download"), owner, 0)
        val catalog = images.map { artifact -> val identity = requireNotNull(mediaOriginalIdentity(artifact.uri)); CatalogRow(artifact, identity.second, identity.first, ns) }
        return Fixture(requireNotNull(catalogTake(ns, catalog, documents)), rows, documents)
    }
    private fun plan(f: Fixture, stem: String = "Scene_12") = mediaRenamePlan(f.take, mediaRenamePreview(f.take, stem), f.rows, f.documents)
    private fun rejected(block: () -> Unit) { assertTrue(runCatching(block).isFailure) }
    private inner class Access(initial: Fixture = fixture()) : MediaRenameAccess {
        val rows = initial.rows.associateBy { it.artifact.uri }.toMutableMap()
        val bytes = initial.documents.associate { it.artifact.uri to requireNotNull(it.text).toByteArray() }.toMutableMap()
        val events = mutableListOf<String>()
        var collision = false
        var busy = false
        var writeFault: String? = null
        var writes = 0
        var renameName: (MediaDeleteRow, String) -> String = { _, name -> name }
        var afterRename: (MediaDeleteRow) -> Unit = { }
        override fun reserve(): AutoCloseable { events += "reserve"; check(!busy); return AutoCloseable { events += "release" } }
        override fun preflight(selected: LocalMediaTake, preview: MediaRenamePreview): MediaRenamePlan {
            events += "preflight"
            val documents = selected.metadata.map { MetadataDocument(rows.getValue(it.uri).artifact, bytes.getValue(it.uri).toString(Charsets.UTF_8)) }
            return mediaRenamePlan(selected, preview, rows.values.toList(), documents)
        }
        override fun checkDestinations(rows: List<MediaDeleteRow>, names: Map<String, String>) { events += "collision-check"; check(!collision) { "Occupied name" } }
        override fun holdOutbox(plan: MediaRenamePlan) { events += "hold-outbox" }
        override fun observe(row: MediaDeleteRow): MediaDeleteRow = rows.getValue(row.artifact.uri)
        override fun rename(row: MediaDeleteRow, newName: String): MediaDeleteRow {
            events += "rename:${row.artifact.uri}"
            check(observe(row) == row)
            val changed = row.copy(artifact = row.artifact.copy(name = renameName(row, newName), modifiedSeconds = row.artifact.modifiedSeconds + 1))
            rows[row.artifact.uri] = changed
            afterRename(changed)
            return changed
        }
        override fun readMetadata(row: MediaDeleteRow): ByteArray = bytes.getValue(row.artifact.uri).copyOf()
        override fun writeMetadata(row: MediaDeleteRow, expected: ByteArray, replacement: ByteArray): MediaDeleteRow {
            events += "write:${row.artifact.uri}"; writes++
            check(observe(row) == row && bytes.getValue(row.artifact.uri).contentEquals(expected))
            if (writes == 1 && writeFault == "BEFORE") error("Before write")
            val actual = if (writes == 1 && writeFault == "FOREIGN") "{foreign-metadata-not-ours}".toByteArray() else replacement.copyOf()
            bytes[row.artifact.uri] = actual
            val changed = row.copy(artifact = row.artifact.copy(sizeBytes = actual.size.toLong(), modifiedSeconds = row.artifact.modifiedSeconds + 1))
            rows[row.artifact.uri] = changed
            if (writes == 1 && writeFault in setOf("AFTER", "FOREIGN")) error("After write")
            return changed
        }
        override fun verify(plan: MediaRenamePlan, restored: Boolean) {
            events += "verify:$restored"
            for (target in plan.preview.files) check(rows.getValue(target.artifact.uri).artifact.name == if (restored) target.artifact.name else target.newName)
            for (meta in plan.metadata) check(bytes.getValue(meta.artifact.uri).contentEquals(if (restored) meta.before else meta.after))
        }
        fun fresh(): Fixture {
            val metadata = rows.values.filter { mediaDeleteIdentity(it.artifact.uri)?.collection == MediaDeleteCollection.METADATA }
            val documents = metadata.map { MetadataDocument(it.artifact, bytes.getValue(it.artifact.uri).toString(Charsets.UTF_8)) }
            val originals = rows.values.filter { it !in metadata }.map { row ->
                val identity = requireNotNull(mediaOriginalIdentity(row.artifact.uri)); CatalogRow(row.artifact, identity.second, identity.first, namespace)
            }
            return Fixture(requireNotNull(catalogTake(namespace, originals, documents)), rows.values.toList(), documents)
        }
    }

    @Test fun metadataRefreshWaitsForExactSizeAndStableModifiedTimeWithoutRewriting() {
        val before = fixture().rows.last()
        val expectedSize = before.artifact.sizeBytes + 123
        val updated = before.copy(artifact = before.artifact.copy(sizeBytes = expectedSize, modifiedSeconds = 102))
        val settled = updated.copy(artifact = updated.artifact.copy(modifiedSeconds = 103))
        val readings = ArrayDeque(listOf(before, before, updated, settled, settled))
        var reads = 0
        var pauses = 0
        var clock = 0L
        val result = awaitMediaRenameMetadataRow(before, expectedSize,
            observe = { readings.removeFirst() }, verifyBytes = { reads++ },
            pause = { pauses++; clock += it * 1_000_000L }, nanoTime = { clock })
        assertEquals(settled, result)
        assertEquals(4, reads)
        assertEquals(3, pauses)
        assertTrue(readings.isEmpty())
    }

    @Test fun metadataRefreshRejectsForeignScopeNameSizeAndBytesWithoutWaiting() {
        val before = fixture().rows.last()
        val expectedSize = before.artifact.sizeBytes + 123
        val invalid = listOf(before.copy(owner = "another.app"), before.copy(pending = 1),
            before.copy(relativePath = "Download/Foreign/"),
            before.copy(artifact = before.artifact.copy(name = "foreign.json")),
            before.copy(artifact = before.artifact.copy(sizeBytes = expectedSize + 1)),
            before.copy(artifact = before.artifact.copy(mimeType = "text/plain")))
        var pauses = 0
        invalid.forEach { row -> rejected {
            awaitMediaRenameMetadataRow(before, expectedSize, { row }, {}, { pauses++ }, { 0L })
        } }
        rejected { awaitMediaRenameMetadataRow(before, expectedSize, { before }, { error("Foreign bytes") }, { pauses++ }, { 0L }) }
        assertEquals(0, pauses)
    }

    @Test fun metadataRefreshHasAnObservationBoundEvenWhenMonotonicClockDoesNotAdvance() {
        val before = fixture().rows.last()
        var observations = 0
        var pauses = 0
        val failure = runCatching { awaitMediaRenameMetadataRow(before, before.artifact.sizeBytes + 123,
            { observations++; before }, {}, { pauses++ }, { 0L }) }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals(81, observations)
        assertEquals(79, pauses)
        assertTrue(requireNotNull(failure?.message).contains("observations=81"))
        assertTrue(requireNotNull(failure?.message).contains("expectedSize="))
    }

    @Test fun metadataRefreshHasAMonotonicDeadlineEvenBeforeObservationBound() {
        val before = fixture().rows.last()
        var observations = 0
        var clock = 0L
        val failure = runCatching { awaitMediaRenameMetadataRow(before, before.artifact.sizeBytes + 123,
            { observations++; before }, {}, { clock += 2_000_000_000L }, { clock }) }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals(2, observations)
        assertTrue(requireNotNull(failure?.message).contains("refresh deadline"))
    }

    @Test fun previewPreservesMimeExtensionsAndUsesStableCollisionFreeSuffixes() {
        val f = fixture()
        val preview = mediaRenamePreview(f.take, "Scene_12")
        assertEquals(listOf("Scene_12.jpg", "Scene_12-photo-01.dng", "Scene_12-metadata-01.json"), preview.files.map { it.newName })
        assertEquals(f.take.originals + f.take.metadata, preview.files.map { it.artifact })
        val swapped = mediaRenamePreview(f.take.copy(originals = f.take.originals.reversed()), "Scene_12")
        assertEquals(preview.files.associate { it.artifact.uri to it.newName }, swapped.files.associate { it.artifact.uri to it.newName })
    }
    @Test fun stemValidationIsSharedNfcBoundedAndRejectsTraversalControlsAndInvalidUnicode() {
        assertEquals("Café", validateMediaRenameStem("Cafe\u0301"))
        listOf("", "..", "path/name", "path\\name", "bad\nname", "trailing ", "x".repeat(129), "\uD800")
            .forEach { rejected { validateMediaRenameStem(it) } }
        assertEquals(128, validateMediaRenameStem("x".repeat(128)).length)
    }
    @Test fun onlyUriBoundFilenameFieldsChangeEditorialAndHashesRemainUntouched() {
        val f = fixture();val plan = plan(f)
        val before = Json.parseToJsonElement(f.documents.single().text!!).jsonObject
        val after = Json.parseToJsonElement(plan.metadata.single().after.toString(Charsets.UTF_8)).jsonObject
        assertEquals(before.getValue("productionSlate"), after.getValue("productionSlate"))
        assertEquals(before.getValue("recordingLut"), after.getValue("recordingLut"))
        val images = after.getValue("images").jsonArray
        assertEquals(jpeg.uri, images[0].jsonObject.getValue("uri").jsonPrimitive.content)
        assertEquals("Scene_12.jpg", images[0].jsonObject.getValue("displayName").jsonPrimitive.content)
        assertEquals("HASH_DECLARATION_NOT_REHASHED", images[0].jsonObject.getValue("sha256").jsonPrimitive.content)
        assertArrayEquals(f.documents.single().text!!.toByteArray(), plan.metadata.single().before)
    }
    @Test fun actualAudioSchemaChangesFileFieldByAudioUriNotArbitraryStringReplacement() {
        val ns = MediaNamespace(id, true)
        val sound = jpeg.copy(uri = "content://media/external_primary/audio/media/1", name = "sound.wav", mimeType = "audio/x-wav")
        val text = buildJsonObject {
            put("schema", "opencinecam-audio-sidecar-v1"); put("audioUri", sound.uri); put("file", sound.name)
            put("productionSlate", productionSlateJson(ProductionSlateSettings(project = sound.name)))
        }.toString()
        val plan = plan(fixture(text, listOf(sound), ns))
        val output = Json.parseToJsonElement(plan.metadata.single().after.toString(Charsets.UTF_8)).jsonObject
        assertEquals("Scene_12.wav", output.getValue("file").jsonPrimitive.content)
        assertEquals(sound.name, output.getValue("productionSlate").jsonObject.getValue("project").jsonPrimitive.content)
    }
    @Test fun corruptUnknownDuplicateAndOversizedMetadataRejectBeforeMutation() {
        listOf("{bad", stillText().replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
            stillText().replace("\"bundleId\":", "\"bundleId\":\"$id\",\"bundleId\":"), " ".repeat(512 * 1024) + stillText())
            .forEach { text -> val f = fixture(text); rejected { plan(f) } }
        val missingReference = fixture(stillText().replace(jpeg.uri, jpeg.uri + "9"))
        rejected { plan(missingReference) }
    }
    @Test fun completeRenameReportsVerifiedNamesAndDoesNotExposeAnOriginalByteWriter() {
        val f = fixture();val access = Access(f)
        val result = executeMediaRename(f.take, mediaRenamePreview(f.take, "Scene_12"), access)
        assertTrue(result.complete); assertFalse(result.compensationAttempted)
        assertTrue(result.files.all { it.status == MediaRenameStatus.RENAMED })
        assertEquals(1, access.events.count { it == "hold-outbox" })
        assertTrue(access.events.filter { it.startsWith("write:") }.all { it.contains("/downloads/") })
        assertEquals("release", access.events.last())
        assertEquals(LocalMediaRelationStatus.DECLARED, access.fresh().take.relationStatus)
    }

    /** Explicit linked-declaration fake, not evidence about real proxy files or providers. */
    private inner class LinkedAccess(
        val base: Access,
        private val failAfterApply: Boolean = false,
        private val failAfterVerify: Boolean = false,
        private val failRestore: Boolean = false,
    ) : MediaRenameAccess by base {
        var held = false
        var linkedName = jpeg.name
        override fun reserve(): AutoCloseable {
            val reservation = base.reserve()
            check(!held); held = true
            return AutoCloseable { try { reservation.close() } finally { held = false } }
        }
        override fun applyLinked(plan: MediaRenamePlan) {
            check(held); base.events += "linked:apply"
            check(plan.preview.files.all { base.rows.getValue(it.artifact.uri).artifact.name == it.newName })
            check(plan.metadata.all { base.bytes.getValue(it.artifact.uri).contentEquals(it.after) })
            linkedName = plan.preview.files.single { it.artifact.uri == jpeg.uri }.newName
            if (failAfterApply) error("Linked apply changed bytes then failed")
        }
        override fun restoreLinked(plan: MediaRenamePlan) {
            check(held); base.events += "linked:restore"
            // The link is compensated before any original filename is rolled back.
            check(plan.preview.files.all { base.rows.getValue(it.artifact.uri).artifact.name == it.newName })
            if (failRestore) error("Linked restore failed")
            linkedName = plan.selected.primary.name
        }
        override fun verify(plan: MediaRenamePlan, restored: Boolean) {
            check(held)
            base.verify(plan, restored)
            check(linkedName == if (restored) plan.selected.primary.name else plan.preview.files.single { it.artifact.uri == jpeg.uri }.newName) {
                "Linked declaration is not restored"
            }
            if (!restored && failAfterVerify) error("Failure after linked apply and verification")
        }
    }

    @Test fun linkedApplyRunsAfterMetadataBeforeVerificationUnderSameReservation() {
        val f = fixture(); val base = Access(f); val access = LinkedAccess(base)
        val result = executeMediaRename(f.take, mediaRenamePreview(f.take, "Scene_12"), access)
        assertTrue(result.complete); assertFalse(result.compensationAttempted)
        assertEquals("Scene_12.jpg", access.linkedName); assertFalse(access.held)
        assertEquals(1, base.events.count { it == "reserve" }); assertEquals(1, base.events.count { it == "release" })
        assertEquals(1, base.events.count { it == "linked:apply" }); assertFalse("linked:restore" in base.events)
        assertTrue(base.events.indexOf("hold-outbox") < base.events.indexOf("linked:apply"))
        assertTrue(base.events.indexOfLast { it.startsWith("write:") } < base.events.indexOf("linked:apply"))
        assertTrue(base.events.indexOf("linked:apply") < base.events.indexOf("verify:false"))
        assertEquals("release", base.events.last())
    }

    @Test fun failureDuringOrAfterLinkedApplyRestoresLinkedDeclarationNamesAndOriginalJson() {
        for (duringApply in listOf(true, false)) {
            val f = fixture(); val base = Access(f)
            val access = LinkedAccess(base, failAfterApply = duringApply, failAfterVerify = !duringApply)
            val result = executeMediaRename(f.take, mediaRenamePreview(f.take, "Scene_12"), access)
            assertFalse(result.complete); assertTrue(result.compensationAttempted); assertTrue(result.compensationComplete)
            assertEquals(jpeg.name, access.linkedName); assertFalse(access.held)
            assertTrue(result.files.all { it.finalName == it.artifact.name })
            assertArrayEquals(f.documents.single().text!!.toByteArray(), base.bytes.values.single())
            assertEquals(1, base.events.count { it == "linked:apply" }); assertEquals(1, base.events.count { it == "linked:restore" })
            val restoredAt = base.events.indexOf("linked:restore")
            assertTrue(restoredAt > base.events.indexOf("linked:apply"))
            assertTrue(base.events.drop(restoredAt + 1).any { it.startsWith("rename:") })
            assertTrue(base.events.indexOf("verify:true") > restoredAt)
            assertEquals("release", base.events.last())
        }
    }

    @Test fun failedLinkedRestorationNeverClaimsCompleteCompensationEvenWhenOriginalsRestore() {
        val f = fixture(); val base = Access(f)
        val access = LinkedAccess(base, failAfterApply = true, failRestore = true)
        val result = executeMediaRename(f.take, mediaRenamePreview(f.take, "Scene_12"), access)
        assertFalse(result.complete); assertTrue(result.compensationAttempted); assertFalse(result.compensationComplete)
        assertTrue(result.partial); assertTrue(result.linkedPartial)
        assertTrue(result.error.orEmpty().contains("Linked restore failed"))
        assertEquals("Scene_12.jpg", access.linkedName); assertFalse(access.held)
        assertTrue(result.files.all { it.finalName == it.artifact.name })
        assertArrayEquals(f.documents.single().text!!.toByteArray(), base.bytes.values.single())
        assertEquals(1, base.events.count { it == "linked:restore" })
        assertTrue("verify:true" in base.events); assertEquals("release", base.events.last())
    }
    @Test fun noOpDoesNotHoldOutboxOrWriteMetadata() {
        val f = fixture();val access = Access(f)
        assertTrue(executeMediaRename(f.take, mediaRenamePreview(f.take, "Scene_12"), access).complete)
        val fresh = access.fresh();access.events.clear()
        val result = executeMediaRename(fresh.take, mediaRenamePreview(fresh.take, "Scene_12"), access)
        assertTrue(result.complete)
        assertTrue(result.files.all { it.status == MediaRenameStatus.UNCHANGED })
        assertFalse(access.events.any { it == "hold-outbox" || it.startsWith("rename:") || it.startsWith("write:") })
    }
    @Test fun preflightCollisionAndActiveTransferNeverMutateOrHoldOutbox() {
        for (busy in listOf(false, true)) {
            val f = fixture();val access = Access(f).apply { this.busy = busy; collision = !busy }
            val result = executeMediaRename(f.take, mediaRenamePreview(f.take, "Scene_12"), access)
            assertFalse(result.complete);assertFalse(result.compensationAttempted)
            assertTrue(result.files.all { it.status == MediaRenameStatus.NOT_ATTEMPTED })
            assertFalse(access.events.any { it.startsWith("rename:") || it == "hold-outbox" })
        }
    }
    @Test fun providerAutoSuffixIsDetectedAndCompensatedUsingActualObservedName() {
        val f = fixture();val access = Access(f)
        var first = true
        access.renameName = { _, name -> if (first) { first = false; name + ".provider" } else name }
        val result = executeMediaRename(f.take, mediaRenamePreview(f.take, "Scene_12"), access)
        assertFalse(result.complete);assertTrue(result.compensationAttempted);assertTrue(result.compensationComplete)
        assertEquals(jpeg.name, access.rows.getValue(jpeg.uri).artifact.name)
        assertTrue(result.files.all { it.finalName == it.artifact.name })
    }
    @Test fun failureBeforeMetadataWriteRestoresAllNamesAndExactOriginalJson() {
        val f = fixture();val access = Access(f).apply { writeFault = "BEFORE" }
        val result = executeMediaRename(f.take, mediaRenamePreview(f.take, "Scene_12"), access)
        assertFalse(result.complete);assertTrue(result.compensationComplete);assertFalse(result.partial)
        assertArrayEquals(f.documents.single().text!!.toByteArray(), access.bytes.values.single())
        assertTrue(result.files.all { it.finalName == it.artifact.name })
    }
    @Test fun failureAfterMetadataWriteCompensatesFromKnownPlannedBytes() {
        val f = fixture();val access = Access(f).apply { writeFault = "AFTER" }
        val result = executeMediaRename(f.take, mediaRenamePreview(f.take, "Scene_12"), access)
        assertFalse(result.complete);assertTrue(result.compensationComplete)
        assertArrayEquals(f.documents.single().text!!.toByteArray(), access.bytes.values.single())
        assertEquals(2, access.writes)
    }
    @Test fun foreignMetadataBytesAreNeverOverwrittenByCompensation() {
        val f = fixture();val access = Access(f).apply { writeFault = "FOREIGN" }
        val result = executeMediaRename(f.take, mediaRenamePreview(f.take, "Scene_12"), access)
        assertFalse(result.complete);assertFalse(result.compensationComplete);assertTrue(result.partial)
        assertEquals(MediaRenameStatus.UNKNOWN, result.files.single { it.artifact in f.take.metadata }.status)
        assertArrayEquals("{foreign-metadata-not-ours}".toByteArray(), access.bytes.values.single())
        assertEquals(1, access.writes)
    }
    @Test fun exceptionAfterRealRenameReobservesFreshIdentityBeforeCompensating() {
        val f = fixture();val access = Access(f)
        var first = true
        access.afterRename = { if (first) { first = false; error("No returned witness") } }
        val result = executeMediaRename(f.take, mediaRenamePreview(f.take, "Scene_12"), access)
        assertTrue(result.compensationComplete)
        assertEquals(jpeg.name, access.rows.getValue(jpeg.uri).artifact.name)
    }
    @Test fun unknownPostRenameIdentityIsNotRenamedBackBlindly() {
        val f = fixture();val access = Access(f)
        access.afterRename = { changed ->
            access.rows[changed.artifact.uri] = changed.copy(owner = "foreign.owner")
            error("Identity changed")
        }
        val result = executeMediaRename(f.take, mediaRenamePreview(f.take, "Scene_12"), access)
        assertFalse(result.compensationComplete)
        assertEquals(MediaRenameStatus.UNKNOWN, result.files.first().status)
        assertEquals(1, access.events.count { it.startsWith("rename:") })
    }
    @Test fun missingMetadataAndLegacyFilesRetainTheirHonestRelationState() {
        val take = LocalMediaTake(namespace.key, jpeg, listOf(jpeg), emptyList(), LocalMediaKind.PHOTO, null, LocalMediaRelationStatus.MISSING_METADATA)
        assertTrue(mediaRenamePlan(take, mediaRenamePreview(take, "New"), listOf(MediaDeleteRow(jpeg, namespace.path("DCIM"), owner, 0)), emptyList()).metadata.isEmpty())
        val legacy = take.copy(id = "legacy:${jpeg.uri}", relationStatus = LocalMediaRelationStatus.LEGACY)
        assertTrue(mediaRenamePlan(legacy, mediaRenamePreview(legacy, "New"), listOf(MediaDeleteRow(jpeg, "DCIM/OpenCineCam/", owner, 0)), emptyList()).metadata.isEmpty())
    }
    @Test fun realStillBurstBracketAccumulationRelationshipGeneratorsRenameCoherently() {
        val images = (1L..3L).map { jpeg.copy(uri = "content://media/external_primary/images/media/$it", name = "capture_$it.jpg", modifiedSeconds = it) }
        val published = images.map { StillPublishedImage(StillImageKind.JPEG, it.uri, it.name, stillSha256(ByteArray(12)), 12) }
        fun capture(index: Int): CapturedStill {
            val time = 1000L + index
            return CapturedStill(index + 1L, time, 90, 93, PhotoFlashReport(PhotoFlashSelection(), 1, 0, null, 2, 0, null, time),
                listOf(StillImagePayload(StillImageKind.JPEG, ByteArray(12), 640, 480)))
        }
        val burst = CapturedBurst(10, 3, (0..2).map { BurstFrame(it, capture(it), 1000L, 100) }, 93, PhotoAspectSelection())
        val bracket = CapturedBracket(20, BracketSelection(3, BracketStep.TWO_EV), (0..2).map { BracketFrame(it, (it - 1) * 2.0, it - 1, it - 1, 1000L, 100, capture(it)) })
        val accumulation = CapturedAccumulation(30, AccumulationSelection(), (0..2).map { AccumulationFrame(it, it + 1L, 1000L + it, 1000L, 100) },
            StillImagePayload(StillImageKind.JPEG, ByteArray(12), 640, 480), 0, 93, false)
        val cases = listOf(
            images.take(1) to stillRelationshipJson(id, capture(0), published.take(1), slate),
            images to burstRelationshipJson(id, burst, published, slate),
            images to bracketRelationshipJson(id, bracket, published, slate),
            images.take(1) to accumulationRelationshipJson(id, accumulation, published.first(), slate),
        )
        cases.forEach { (members, text) ->
            val result = plan(fixture(text, members))
            val renamed = Json.parseToJsonElement(result.metadata.single().after.toString(Charsets.UTF_8)).jsonObject
            assertEquals(productionSlateJson(slate), renamed.getValue("productionSlate"))
            assertTrue(result.metadata.single().after.toString(Charsets.UTF_8).contains("Scene_12"))
        }
    }
}
