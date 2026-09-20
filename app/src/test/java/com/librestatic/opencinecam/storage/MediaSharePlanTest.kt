/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import com.librestatic.opencinecam.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class MediaSharePlanTest {
    private val id = "2b2a7bb1-5061-4379-86c1-cd7cbca97e42"
    private val namespace = MediaNamespace(id, true)
    private val video = LocalMediaArtifact("content://media/external_primary/video/media/12", "Exact original.mp4", "video/mp4", 123, 100)
    private val slate = ProductionSlateSettings(project = "PRIVATE_EDITORIAL", scene = "Scene A", goodTake = true)
    private fun snapshot(extra: JsonObject = JsonObject(emptyMap()), production: ProductionSlateSettings? = slate): MediaShareSnapshot {
        val text = buildJsonObject {
            put("schema", "opencinecam.recording.v1"); put("bundleId", id); put("videoUri", video.uri)
            production?.let { put("productionSlate", productionSlateJson(it)) }
            extra.forEach { (key, value) -> put(key, value) }
        }.toString()
        val document = MetadataDocument(LocalMediaArtifact("content://media/external_primary/downloads/15", "recording.json", "application/json", text.toByteArray().size.toLong(), 100), text)
        val take = requireNotNull(catalogTake(namespace, listOf(CatalogRow(video, 12, LocalMediaKind.VIDEO, namespace)), listOf(document)))
        return MediaShareSnapshot(take, listOf(document))
    }
    private fun lut(hash: String) = buildJsonObject {
        put("originalCubeSha256", hash); put("baked", true); put("reapplyInEditor", false)
    }
    private fun rejected(block: () -> Unit) { assertTrue(runCatching(block).isFailure) }

    @Test fun selectionMatrixSeparatesOriginalsProductionAndTechnical() {
        for (content in MediaShareContent.entries) for (metadata in MediaShareMetadata.entries) {
            val plan = mediaSharePlan(snapshot(), MediaSharingSettings(content, metadata))
            assertEquals(content != MediaShareContent.METADATA_ONLY, plan.originals.isNotEmpty())
            val wanted = if (content == MediaShareContent.ORIGINALS_ONLY) emptySet() else when (metadata) {
                MediaShareMetadata.PRODUCTION -> setOf(MediaShareRole.PRODUCTION)
                MediaShareMetadata.TECHNICAL -> setOf(MediaShareRole.TECHNICAL)
                MediaShareMetadata.BOTH -> setOf(MediaShareRole.PRODUCTION, MediaShareRole.TECHNICAL)
            }
            assertEquals(wanted, plan.generated.map { it.role }.toSet())
            assertTrue(plan.generated.none { it.name == "recording.json" })
        }
    }
    @Test fun technicalExportRecursivelyStripsEditorialButPreservesActualTechnicalEvidence() {
        val input = snapshot(buildJsonObject {
            put("technical", buildJsonObject {
                put("rate", 24)
                put("nested", buildJsonArray { add(buildJsonObject {
                    put("productionSlate", buildJsonObject { put("project", "NESTED_SECRET") }); put("sensor", "actual")
                }) })
            })
        })
        val plan = mediaSharePlan(input, MediaSharingSettings(metadata = MediaShareMetadata.TECHNICAL))
        val technical = plan.generated.single().text
        assertFalse(technical.contains("productionSlate")); assertFalse(technical.contains("PRIVATE_EDITORIAL")); assertFalse(technical.contains("NESTED_SECRET"))
        assertTrue(technical.contains("actual")); assertTrue(technical.contains("24")); assertTrue(technical.contains(video.uri))
        val manifest = mediaShareManifest(plan, emptyList())
        assertFalse(manifest.contains("PRIVATE_EDITORIAL")); assertFalse(manifest.contains("productionSlate"))
    }
    @Test fun productionExportContainsOnlySlateAndTakeFileIdentities() {
        val plan = mediaSharePlan(snapshot(buildJsonObject { put("codecSecret", "TECHNICAL_VALUE") }),
            MediaSharingSettings(MediaShareContent.METADATA_ONLY, MediaShareMetadata.PRODUCTION))
        val doc = Json.parseToJsonElement(plan.generated.single().text).jsonObject
        assertEquals(setOf("schema", "takeId", "files", "productionSlate"), doc.keys)
        assertTrue(doc.toString().contains("PRIVATE_EDITORIAL")); assertFalse(doc.toString().contains("TECHNICAL_VALUE"))
        assertTrue(plan.originals.isEmpty())
        val manifest = Json.parseToJsonElement(mediaShareManifest(plan, emptyList())).jsonObject
        assertFalse(manifest.getValue("originals").jsonArray.single().jsonObject.getValue("included").jsonPrimitive.boolean)
        assertEquals("NOT_CHECKED", manifest.getValue("originalHashIntegrity").jsonPrimitive.content)
    }
    @Test fun metadataNeedsDeclaredConsistentSourcesWhileOriginalsOnlyPreservesExplicitStatus() {
        for (status in LocalMediaRelationStatus.entries.filter { it != LocalMediaRelationStatus.DECLARED }) {
            val input = snapshot().let { it.copy(take = it.take.copy(relationStatus = status)) }
            rejected { mediaSharePlan(input, MediaSharingSettings()) }
            val plan = mediaSharePlan(input, MediaSharingSettings(content = MediaShareContent.ORIGINALS_ONLY))
            assertEquals(listOf(video), plan.originals)
            assertTrue(mediaShareManifest(plan, emptyList()).contains(status.name))
        }
        val absent = snapshot(production = null)
        rejected { mediaSharePlan(absent, MediaSharingSettings(metadata = MediaShareMetadata.PRODUCTION)) }
        assertEquals(MediaShareRole.TECHNICAL, mediaSharePlan(absent, MediaSharingSettings(metadata = MediaShareMetadata.TECHNICAL)).generated.single().role)
    }
    @Test fun staleSelectionRejectsMemberSlateStatusAndArtifactChanges() {
        val take = snapshot().take
        requireUnchangedShareTake(take, take.copy())
        listOf(take.copy(slate = slate.copy(scene = "Changed")), take.copy(relationStatus = LocalMediaRelationStatus.INCOMPLETE),
            take.copy(originals = emptyList()), take.copy(primary = video.copy(name = "renamed.mp4")),
            take.copy(metadata = take.metadata.map { it.copy(modifiedSeconds = 101) }))
            .forEach { changed -> rejected { requireUnchangedShareTake(take, changed) } }
    }
    @Test fun metadataDuplicateKeysOversizeDeepNodesAndChangedSlateReject() {
        val input = snapshot()
        val text = requireNotNull(input.documents.single().text)
        val corrupt = listOf(text.replace("\"videoUri\":", "\"videoUri\":\"${video.uri}\",\"videoUri\":"),
            " ".repeat(512 * 1024) + text, "[".repeat(33) + "]".repeat(33), text.replace("PRIVATE_EDITORIAL", "CHANGED_EDITORIAL"))
        corrupt.forEach { value ->
            val changed = input.copy(documents = listOf(input.documents.single().copy(text = value)))
            rejected { mediaSharePlan(changed, MediaSharingSettings()) }
        }
    }
    @Test fun onlyActualRecordingLutDeclarationsAreSelectedAndOmissionIsExplicit() {
        val hash = "a".repeat(64)
        val input = snapshot(buildJsonObject {
            put("nested", buildJsonArray { add(buildJsonObject { put("recordingLut", lut(hash)) }) })
            put("activeMonitorLut", buildJsonObject { put("originalCubeSha256", "b".repeat(64)) })
        })
        val enabled = mediaSharePlan(input, MediaSharingSettings())
        assertEquals(listOf(hash), enabled.referencedLuts)
        val disabled = mediaSharePlan(input, MediaSharingSettings(includeReferencedLut = false))
        assertEquals(listOf(hash), disabled.referencedLuts)
        val manifest = mediaShareManifest(disabled, listOf(MediaShareLut(hash, null, null, false)))
        assertTrue(manifest.contains("REFERENCED_LUT_EXPORT_DISABLED")); assertFalse(manifest.contains("sha256Verified"))
        assertTrue(manifest.contains("\"baked\":true")); assertTrue(manifest.contains("\"reapplyInEditor\":false"))
        assertTrue(mediaSharePlan(input, MediaSharingSettings(metadata = MediaShareMetadata.PRODUCTION)).referencedLuts.isEmpty())
    }
    @Test fun malformedTooManyOrReapplyLutDeclarationsReject() {
        val invalid = listOf(lut("bad"), JsonObject(lut("a".repeat(64)) + ("reapplyInEditor" to JsonPrimitive(true))),
            JsonObject(lut("a".repeat(64)) + ("baked" to JsonPrimitive(false))))
        invalid.forEach { declaration -> rejected { mediaSharePlan(snapshot(buildJsonObject { put("recordingLut", declaration) }), MediaSharingSettings()) } }
        val tooMany = buildJsonObject { put("many", buildJsonArray { (1..9).forEach { index ->
            add(buildJsonObject { put("recordingLut", lut(index.toString().repeat(64))) })
        } }) }
        rejected { mediaSharePlan(snapshot(tooMany), MediaSharingSettings()) }
    }
    @Test fun originalUrisMustBeCanonicalMediaStoreIdentities() {
        assertEquals(LocalMediaKind.VIDEO to 12L, mediaOriginalIdentity(video.uri))
        listOf("file:///tmp/movie", video.uri + "?other=1", video.uri.replace("media/12", "media/012"),
            video.uri.replace("media/external_primary", "foreign/external_primary"), video.uri + "/", video.uri.replace("12", "999999999999999999999"))
            .forEach { assertNull(it, mediaOriginalIdentity(it)) }
    }
    @Test fun expiryDeletesOnlyCanonicalOldSessionsNotFutureOrNewOrUnknownEntries() {
        val now = 100_000_000L
        assertTrue(mediaShareSessionExpired(id, now - MEDIA_SHARE_EXPIRY_MS, now))
        assertFalse(mediaShareSessionExpired(id, now - MEDIA_SHARE_EXPIRY_MS + 1, now))
        assertFalse(mediaShareSessionExpired(id, now + 1, now))
        assertFalse(mediaShareSessionExpired(id, 0, now))
        assertFalse(mediaShareSessionExpired("not-our-session", 1, now))
        assertEquals(128, MEDIA_SHARE_MAX_SESSIONS)
        assertEquals(256 * 1024 * 1024L, MEDIA_SHARE_MAX_CACHE_BYTES)
        assertEquals(8, MEDIA_SHARE_MAX_LUTS)
        assertEquals(16 * 1024 * 1024, MEDIA_SHARE_MAX_LUT_BYTES)
    }
    @Test fun duplicateAndOversizedOriginalSelectionsAreRejectedEvenWithoutMetadataExport() {
        val input = snapshot()
        val settings = MediaSharingSettings(content = MediaShareContent.ORIGINALS_ONLY)
        rejected { mediaSharePlan(input.copy(take = input.take.copy(originals = listOf(video, video))), settings) }
        val tooMany = (1..33).map { video.copy(uri = "content://media/external_primary/video/media/$it") }
        rejected { mediaSharePlan(input.copy(take = input.take.copy(primary = tooMany.first(), originals = tooMany)), settings) }
        rejected { mediaSharePlan(input.copy(take = input.take.copy(primary = video.copy(name = "unselected"))), settings) }
    }
    @Test fun manifestKeepsExactOriginalIdentityAndScopesVerifiedHashToIncludedLut() {
        val hash = "a".repeat(64)
        val plan = mediaSharePlan(snapshot(buildJsonObject { put("recordingLut", lut(hash)) }), MediaSharingSettings())
        val manifest = Json.parseToJsonElement(mediaShareManifest(plan,
            listOf(MediaShareLut(hash, "$hash.cube", 1234, true)))).jsonObject
        val original = manifest.getValue("originals").jsonArray.single().jsonObject
        assertEquals(video.uri, original.getValue("file").jsonObject.getValue("uri").jsonPrimitive.content)
        assertEquals(video.name, original.getValue("file").jsonObject.getValue("filename").jsonPrimitive.content)
        assertEquals(video.sizeBytes, original.getValue("file").jsonObject.getValue("observedSizeBytes").jsonPrimitive.long)
        assertEquals("NOT_CHECKED", manifest.getValue("originalHashIntegrity").jsonPrimitive.content)
        assertFalse(original.containsKey("sha256Verified"))
        val exported = manifest.getValue("recordingLuts").jsonArray.single().jsonObject
        assertTrue(exported.getValue("sha256Verified").jsonPrimitive.boolean)
        assertFalse(exported.getValue("reapplyInEditor").jsonPrimitive.boolean)
        assertTrue(manifest.getValue("relationshipManifest").jsonObject.getValue("included").jsonPrimitive.boolean)
    }

    @Test fun deliveredOriginalNamesMustNotCollideAcrossCollectionsOrWithGeneratedManifest() {
        val input = snapshot()
        val audio = video.copy(uri = "content://media/external_primary/audio/media/20", mimeType = "audio/wav", name = video.name.uppercase())
        rejected { mediaSharePlan(input.copy(take = input.take.copy(originals = listOf(video, audio))),
            MediaSharingSettings(content = MediaShareContent.ORIGINALS_ONLY)) }
        val renamed = video.copy(name = "RELATIONSHIPS.JSON")
        val changed = input.copy(take = input.take.copy(primary = renamed, originals = listOf(renamed)))
        rejected { mediaSharePlan(changed, MediaSharingSettings(content = MediaShareContent.ORIGINALS_ONLY)) }
        // A filename appearing only in the identity manifest is not a delivered-file collision.
        val metadataOnly = mediaSharePlan(changed, MediaSharingSettings(content = MediaShareContent.METADATA_ONLY))
        assertTrue(metadataOnly.originals.isEmpty())
    }
    @Test fun generatedProductionAndReferencedLutNamesCannotAliasDeliveredOriginals() {
        val input = snapshot()
        val productionName = video.copy(name = "take.production.json")
        val changed = input.copy(take = input.take.copy(primary = productionName, originals = listOf(productionName)))
        rejected { mediaSharePlan(changed, MediaSharingSettings()) }
        assertEquals(listOf(productionName), mediaSharePlan(changed,
            MediaSharingSettings(metadata = MediaShareMetadata.TECHNICAL)).originals)
        val hash = "a".repeat(64)
        val withLut = snapshot(buildJsonObject { put("recordingLut", lut(hash)) })
        val lutName = video.copy(name = "$hash.cube")
        val collision = withLut.copy(take = withLut.take.copy(primary = lutName, originals = listOf(lutName)))
        rejected { mediaSharePlan(collision, MediaSharingSettings()) }
        assertEquals(listOf(lutName), mediaSharePlan(collision, MediaSharingSettings(includeReferencedLut = false)).originals)
    }
    @Test fun exportNamesRejectTraversalControlsAndPortableCaseOrUnicodeAliases() {
        listOf("", ".", "..", "../video.mp4", "folder/video.mp4", "folder\\video.mp4", "bad\nname.mp4", "bad\u0000name", "trailing.", "trailing ")
            .forEach { rejected { requirePortableShareNames(listOf(it)) } }
        rejected { requirePortableShareNames(listOf("Take.mp4", "take.MP4")) }
        rejected { requirePortableShareNames(listOf("Café.mp4", "Cafe\u0301.mp4")) }
        requirePortableShareNames(listOf("Original video.mp4", "original audio.wav", "relationships.json"))
    }

}
