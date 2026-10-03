/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import com.librestatic.opencinecam.camera.*
import com.librestatic.opencinecam.GalleryMediaKind
import com.librestatic.opencinecam.GallerySettings
import com.librestatic.opencinecam.ProductionSlateSettings
import com.librestatic.opencinecam.productionSlateJson
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LocalMediaCatalogTest {
    private val id = "2b2a7bb1-5061-4379-86c1-cd7cbca97e42"
    private val still = MediaNamespace(id, false)
    private val recording = MediaNamespace(id, true)
    private fun row(id: Long, kind: LocalMediaKind = LocalMediaKind.PHOTO,
        namespace: MediaNamespace = still, date: Long = 100, name: String = "image.jpg"): CatalogRow {
        val segment = when (kind) { LocalMediaKind.PHOTO -> "images"; LocalMediaKind.VIDEO -> "video"; LocalMediaKind.AUDIO -> "audio" }
        val mime = when (kind) { LocalMediaKind.PHOTO -> "image/jpeg"; LocalMediaKind.VIDEO -> "video/mp4"; LocalMediaKind.AUDIO -> "audio/wav" }
        return CatalogRow(LocalMediaArtifact("content://media/external_primary/$segment/media/$id", name, mime, 12, date), id, kind, namespace)
    }
    private fun document(text: String, n: Int = 1) = MetadataDocument(
        LocalMediaArtifact("content://media/external_primary/downloads/$n", "metadata$n.json", "application/json", text.length.toLong(), 100), text)
    private fun stillJson(rows: List<CatalogRow>, slate: ProductionSlateSettings? = null, bundle: String = id): String = buildJsonObject {
        put("schemaVersion", 1); put("bundleId", bundle)
        slate?.let { put("productionSlate", productionSlateJson(it)) }
        put("images", buildJsonArray { rows.forEach { row -> add(buildJsonObject {
            put("uri", row.artifact.uri); put("displayName", row.artifact.name)
            put("mimeType", row.artifact.mimeType); put("bytes", row.artifact.sizeBytes)
            put("sha256", "not-hash-verified-by-catalog")
        }) } })
    }.toString()
    private fun recordingJson(row: CatalogRow, slate: ProductionSlateSettings) = buildJsonObject {
        val audio = row.kind == LocalMediaKind.AUDIO
        put("schema", if (audio) "opencinecam-audio-sidecar-v1" else "opencinecam.recording.v1")
        if (!audio) put("bundleId", id)
        put(if (audio) "audioUri" else "videoUri", row.artifact.uri)
        put("productionSlate", productionSlateJson(slate))
    }.toString()

    @Test fun canonicalNamespacesHaveRootBoundariesAndSeparateStillRecordingIdentities() {
        assertEquals(still, mediaNamespace("DCIM/OpenCineCam/OCC_$id/"))
        assertEquals(still, mediaNamespace("Download/OpenCineCam/OCC_$id/nested/"))
        assertEquals(recording, mediaNamespace("Music/OpenCineCam/OCC_TAKE_$id/"))
        assertNotEquals(still.key, recording.key)
        listOf("DCIM/OpenCineCamOther/OCC_$id/", "DCIM/OpenCineCam/OCC_$id", "Music/OpenCineCam/OCC_$id/",
            "DCIM/OpenCineCam/OCC_${id.uppercase()}/", "DCIM/OpenCineCam/OCC_$id/../", "DCIM/OpenCineCam/OCC_bad/")
            .forEach { assertNull(it, mediaNamespace(it)) }
        assertTrue(isCatalogPath("DCIM/OpenCineCam/"))
        assertFalse(isCatalogPath("DCIM/OpenCineCam/unknown/"))
    }
    @Test fun primaryUsesVideoThenMinimumOriginalIdNotNewestSecondary() {
        val video = row(9, LocalMediaKind.VIDEO, recording, date = 1)
        val audio = row(1, LocalMediaKind.AUDIO, recording, date = 900)
        assertEquals(video, catalogPrimary(listOf(audio, video)))
        assertEquals(audio, catalogPrimary(listOf(audio)))
        assertEquals(2L, catalogPrimary(listOf(row(5, date = 1), row(2, date = 900)))?.id)
    }
    @Test fun orderIsDateThenCollectionThenIdInEitherDirection() {
        val photo = row(9, date = 10)
        val video = row(1, LocalMediaKind.VIDEO, recording, date = 10)
        val earlier = row(2, date = 5)
        assertEquals(listOf(photo, video, earlier), listOf(video, earlier, photo).sortedWith(catalogComparator(true)))
        assertEquals(listOf(earlier, photo, video), listOf(video, earlier, photo).sortedWith(catalogComparator(false)))
        assertEquals(listOf(2L, 9L), listOf(photo, row(2, date = 10)).sortedWith(catalogComparator(true)).map { it.id })
    }
    @Test fun declarationsValidateNamespaceIdAndExactUriNotBasenameOrHash() {
        val image = row(1)
        val valid = requireNotNull(catalogTake(still, listOf(image), listOf(document(stillJson(listOf(image))))))
        assertEquals(LocalMediaRelationStatus.DECLARED, valid.relationStatus)
        assertEquals(LocalMediaRelationStatus.INVALID_METADATA, catalogTake(still, listOf(image),
            listOf(document(stillJson(listOf(image), bundle = "11111111-1111-1111-1111-111111111111"))))?.relationStatus)
        val wrongUri = row(2, name = image.artifact.name)
        val missing = requireNotNull(catalogTake(still, listOf(image), listOf(document(stillJson(listOf(wrongUri))))))
        assertEquals(LocalMediaRelationStatus.INCOMPLETE, missing.relationStatus)
        assertEquals(listOf(image.artifact), missing.originals)
    }
    @Test fun missingMetadataDoesNotInventSlateOrRelation() {
        val take = requireNotNull(catalogTake(still, listOf(row(1)), emptyList()))
        assertEquals(LocalMediaRelationStatus.MISSING_METADATA, take.relationStatus)
        assertNull(take.slate)
    }
    @Test fun damagedOrUnsupportedMetadataRemainsExplicit() {
        val image = row(1)
        listOf("{", "[]", "{\"schemaVersion\":2}", stillJson(listOf(image)).replace("image.jpg", "other.jpg"))
            .forEach { assertEquals(LocalMediaRelationStatus.INVALID_METADATA,
                catalogTake(still, listOf(image), listOf(document(it)))?.relationStatus) }
    }
    @Test fun missingDeclaredMembersAndUnreferencedAvailableMembersAreIncomplete() {
        val images = listOf(row(1), row(2))
        assertEquals(LocalMediaRelationStatus.INCOMPLETE, catalogTake(still, images.take(1), listOf(document(stillJson(images))))?.relationStatus)
        assertEquals(LocalMediaRelationStatus.INCOMPLETE, catalogTake(still, images, listOf(document(stillJson(images.take(1)))))?.relationStatus)
        assertEquals(LocalMediaRelationStatus.INCOMPLETE, catalogTake(still, images, listOf(document(stillJson(images))), incomplete = true)?.relationStatus)
    }
    @Test fun deletedMetadataIsIncompleteRatherThanCorrupt() {
        val image = row(1)
        val deleted = document(stillJson(listOf(image))).copy(text = null, disappeared = true)
        assertEquals(LocalMediaRelationStatus.INCOMPLETE, catalogTake(still, listOf(image), listOf(deleted))?.relationStatus)
    }
    @Test fun recordingAudioAndVideoUseNamespaceAndTheirExplicitUris() {
        val video = row(9, LocalMediaKind.VIDEO, recording)
        val audio = row(2, LocalMediaKind.AUDIO, recording)
        val slate = ProductionSlateSettings(project = "Feature", scene = "12", goodTake = true)
        val take = requireNotNull(catalogTake(recording, listOf(audio, video), listOf(
            document(recordingJson(video, slate)), document(recordingJson(audio, slate), 2))))
        assertEquals(LocalMediaRelationStatus.DECLARED, take.relationStatus)
        assertEquals(video.artifact, take.primary)
        assertEquals(slate, take.slate)
        assertEquals(LocalMediaKind.VIDEO, take.kind)
    }
    @Test fun encodingComesFromTheOcLogAndAudioSidecarsOfAValidTake() {
        val video = row(9, LocalMediaKind.VIDEO, recording)
        val audio = row(2, LocalMediaKind.AUDIO, recording)
        val slate = ProductionSlateSettings(project = "Feature")
        val oclog = buildJsonObject {
            put("schema", "opencinecam-oclog-sidecar-v2"); put("bundleId", id); put("videoUri", video.artifact.uri)
            put("productionSlate", productionSlateJson(slate))
            put("encoding", buildJsonObject { put("mime", "video/hevc"); put("profile", "Main10"); put("codecName", "c2.hevc") })
        }.toString()
        val wav = JsonObject(Json.parseToJsonElement(recordingJson(audio, slate)).jsonObject +
            mapOf("container" to JsonPrimitive("WAV"), "encoding" to JsonPrimitive("PCM_24"))).toString()
        var encoding: LocalMediaEncoding? = null
        val take = requireNotNull(catalogTake(recording, listOf(audio, video), listOf(document(recordingJson(video, slate)),
            document(oclog, 2), document(wav, 3))) { encoding = it })
        assertEquals(LocalMediaRelationStatus.DECLARED, take.relationStatus)
        assertEquals(LocalMediaEncoding("video/hevc", "Main10", "WAV", "PCM_24"), encoding)
        // An invalid take reports nothing, and a take that declares no codec reports nothing either.
        encoding = null
        catalogTake(recording, listOf(audio, video), listOf(document(oclog), document(recordingJson(audio, ProductionSlateSettings(project = "B")), 2))) { encoding = it }
        assertNull(encoding)
        catalogTake(recording, listOf(audio, video), listOf(document(recordingJson(video, slate)), document(recordingJson(audio, slate), 2))) { encoding = it }
        assertNull(encoding)
    }
    @Test fun conflictingSlatesAreInvalidAndDoNotPickOne() {
        val video = row(9, LocalMediaKind.VIDEO, recording)
        val audio = row(2, LocalMediaKind.AUDIO, recording)
        val take = requireNotNull(catalogTake(recording, listOf(audio, video), listOf(
            document(recordingJson(video, ProductionSlateSettings(project = "A"))),
            document(recordingJson(audio, ProductionSlateSettings(project = "B")), 2))))
        assertEquals(LocalMediaRelationStatus.INVALID_METADATA, take.relationStatus)
        assertNull(take.slate)
    }
    @Test fun searchIncludesSlateBeyondFilenameAndGoodTakeNeedsDeclaredSlate() {
        val image = row(1)
        val take = requireNotNull(catalogTake(still, listOf(image), listOf(document(stillJson(listOf(image),
            ProductionSlateSettings(project = "Feature", scene = "Bridge", goodTake = true))))))
        assertTrue(catalogMatches(take, GallerySettings(goodTakesOnly = true), "BRIDGE"))
        assertTrue(catalogMatches(take, GallerySettings(kind = GalleryMediaKind.PHOTO), "image"))
        assertFalse(catalogMatches(take, GallerySettings(kind = GalleryMediaKind.AUDIO), ""))
        assertFalse(catalogMatches(take.copy(slate = null), GallerySettings(goodTakesOnly = true), ""))
    }
    @Test fun cursorFingerprintTracksOnlyQueryAndFilteringAndOrdering() {
        val settings = GallerySettings()
        assertEquals(catalogFilter(settings, " Scene "), catalogFilter(settings.copy(showSlate = false, showTechnical = true), "scene"))
        assertNotEquals(catalogFilter(settings, "a"), catalogFilter(settings, "b"))
        assertNotEquals(catalogFilter(settings, ""), catalogFilter(settings.copy(newestFirst = false), ""))
        assertNotEquals(catalogFilter(settings, ""), catalogFilter(settings.copy(goodTakesOnly = true), ""))
        assertNotEquals(catalogFilter(settings, ""), catalogFilter(settings.copy(kind = GalleryMediaKind.AUDIO), ""))
    }
    @Test fun metadataNestingIsBoundedButQuotedBracketsDoNotCount() {
        assertTrue(jsonNestingWithinBound("{\"x\":\"[[[\"}"))
        assertFalse(jsonNestingWithinBound("[".repeat(33) + "]".repeat(33)))
        assertFalse(jsonNestingWithinBound("{\"x\":\"unterminated}"))
    }
    @Test fun realStillBurstBracketAndAccumulationGeneratorsRemainCatalogCompatible() {
        val slate = ProductionSlateSettings(project = "Actual generator", goodTake = true)
        val rows = (1L..10L).map { row(it, name = "OCC_${id}_$it.jpg") }
        val images = rows.map { StillPublishedImage(StillImageKind.JPEG, it.artifact.uri,
            it.artifact.name, stillSha256(ByteArray(12)), it.artifact.sizeBytes) }
        fun capture(index: Int): CapturedStill {
            val timestamp = 1_000_000L + index
            return CapturedStill(index + 1L, timestamp, 90, 93,
                PhotoFlashReport(PhotoFlashSelection(), 1, 0, null, 2, 0, null, timestamp),
                listOf(StillImagePayload(StillImageKind.JPEG, ByteArray(12), 640, 480)))
        }
        fun assertGenerated(members: List<CatalogRow>, text: String) {
            val take = requireNotNull(catalogTake(still, members, listOf(document(text))))
            assertEquals(text, LocalMediaRelationStatus.DECLARED, take.relationStatus)
            assertEquals(slate, take.slate)
            assertEquals(members.size, take.originals.size)
            assertEquals(members.first().artifact, take.primary)
        }
        assertGenerated(rows.take(1), stillRelationshipJson(id, capture(0), images.take(1), slate))
        val burst = CapturedBurst(10, 10, (0..9).map {
            BurstFrame(it, capture(it), 10_000L, 100)
        }, 93, PhotoAspectSelection())
        assertGenerated(rows, burstRelationshipJson(id, burst, images, slate))
        val bracket = CapturedBracket(20, BracketSelection(9, BracketStep.TWO_EV), (0..8).map {
            BracketFrame(it, (it - 4) * 2.0, it - 4, it - 4, 10_000L, 100, capture(it))
        })
        assertGenerated(rows.take(9), bracketRelationshipJson(id, bracket, images.take(9), slate))
        val accumulation = CapturedAccumulation(30, AccumulationSelection(mode = AccumulationMode.LIGHT),
            (0..2).map { AccumulationFrame(it, it + 1L, 1_000_000L + it * 250_000_000L, 10_000L, 100) },
            StillImagePayload(StillImageKind.JPEG, ByteArray(12), 640, 480), 0, 93, false)
        // Accumulation frames are source evidence, not output URIs: only its image node declares a file.
        assertGenerated(rows.take(1), accumulationRelationshipJson(id, accumulation, images.first(), slate))
    }

    @Test fun duplicateIdentityUriAndSlateKeysAreRejectedBeforeTreeParsing() {
        val image = row(1)
        val valid = stillJson(listOf(image), ProductionSlateSettings(project = "Feature"))
        val cases = listOf(
            valid.replace("\"bundleId\":", "\"bundleId\":\"$id\",\"bundleId\":"),
            valid.replace("\"uri\":", "\"uri\":\"${image.artifact.uri}\",\"uri\":"),
            valid.replace("\"productionSlate\":", "\"productionSlate\":{},\"productionSlate\":"),
            valid.replace("\"project\":", "\"project\":\"Different\",\"project\":"),
            valid.replace("\"bundleId\":", "\"bundle\\u0049d\":\"$id\",\"bundleId\":"),
            valid.replace("\"project\":", "\"pro\\u006aect\":\"Different\",\"project\":"),
        )
        cases.forEach { text ->
            assertFalse(text, jsonObjectKeysUnique(text))
            val take = requireNotNull(catalogTake(still, listOf(image), listOf(document(text))))
            assertEquals(text, LocalMediaRelationStatus.INVALID_METADATA, take.relationStatus)
            assertNull(take.slate)
        }
    }
    @Test fun uniqueKeyPreflightAllowsRepeatedKeysInDifferentFramesAndQuotedValueText() {
        val text = """{"frames":[{"uri":"first","details":{"project":"A"}},{"uri":"second","details":{"project":"B"}}],"note":"\"uri\": not a key","empty":{}}"""
        assertTrue(jsonObjectKeysUnique(text))
        assertTrue(jsonObjectKeysUnique("""{"bundle\u0049d":"one"}"""))
        assertFalse(jsonObjectKeysUnique("""{"a\/b":1,"a/b":2}"""))
        val images = listOf(row(1), row(2))
        assertEquals(LocalMediaRelationStatus.DECLARED,
            catalogTake(still, images, listOf(document(stillJson(images))))?.relationStatus)
    }

}
