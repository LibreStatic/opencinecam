/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import com.librestatic.opencinecam.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class MediaShareLocationTest {
    private val id = "2b2a7bb1-5061-4379-86c1-cd7cbca97e42"
    private val namespace = MediaNamespace(id, true)
    private val video = LocalMediaArtifact("content://media/external_primary/video/media/12", "original.mp4", "video/mp4", 123, 100)
    private val audio = LocalMediaArtifact("content://media/external_primary/audio/media/13", "original.wav", "audio/wav", 456, 100)
    private val slate = ProductionSlateSettings(project = "PRIVATE_PROJECT")
    private val fix = captureLocationJson(CaptureLocationSnapshot(CaptureLocationStatus.AVAILABLE,
        CaptureLocationFix(-34.5, -58.4, 12.5f, 1_700_000_000_000L, LocationPermissionPrecision.APPROXIMATE), 1750))
    private val unavailable = captureLocationJson(CaptureLocationSnapshot(CaptureLocationStatus.UNAVAILABLE))
    private fun snapshot(videoExtra: JsonObject = JsonObject(emptyMap()), audioExtra: JsonObject? = null,
        withSlate: Boolean = true): MediaShareSnapshot {
        val originals = if (audioExtra == null) listOf(video) else listOf(video, audio)
        val documents = originals.mapIndexed { index, artifact ->
            val text = buildJsonObject {
                put("schema", if (index == 0) "opencinecam.recording.v1" else "opencinecam-audio-sidecar-v1")
                put("bundleId", id)
                put(if (index == 0) "videoUri" else "audioUri", artifact.uri)
                if (index > 0) put("videoUri", video.uri)
                if (withSlate) put("productionSlate", productionSlateJson(slate))
                (if (index == 0) videoExtra else requireNotNull(audioExtra)).forEach { (key, value) -> put(key, value) }
            }.toString()
            MetadataDocument(LocalMediaArtifact("content://media/external_primary/downloads/${20 + index}",
                if (index == 0) "video.json" else "audio.json", "application/json", text.toByteArray().size.toLong(), 100), text)
        }
        val rows = originals.mapIndexed { index, artifact -> CatalogRow(artifact, 12L + index,
            if (index == 0) LocalMediaKind.VIDEO else LocalMediaKind.AUDIO, namespace) }
        val take = requireNotNull(catalogTake(namespace, rows, documents))
        assertEquals(LocalMediaRelationStatus.DECLARED, take.relationStatus)
        return MediaShareSnapshot(take, documents)
    }
    private fun location(value: JsonElement) = buildJsonObject { put("captureLocation", value) }
    private fun production(input: MediaShareSnapshot, metadata: MediaShareMetadata = MediaShareMetadata.PRODUCTION): JsonObject =
        Json.parseToJsonElement(mediaSharePlan(input, MediaSharingSettings(metadata = metadata)).generated.single { it.role == MediaShareRole.PRODUCTION }.text).jsonObject

    @Test fun productionAndBothPreserveEveryValidatedDeclarationWithItsOwnSourceAndPointer() {
        val input = snapshot(location(fix), buildJsonObject {
            put("a/b~c", buildJsonArray { add(location(unavailable)) })
        })
        val before = input.documents.map { it.text }
        for (selection in listOf(MediaShareMetadata.PRODUCTION, MediaShareMetadata.BOTH)) {
            val output = production(input, selection)
            val declarations = output.getValue("captureLocations").jsonArray.map { it.jsonObject }
            assertEquals(2, declarations.size)
            assertEquals(listOf("/captureLocation", "/a~1b~0c/0/captureLocation"), declarations.map { it.getValue("pointer").jsonPrimitive.content })
            assertEquals(listOf(fix, unavailable), declarations.map { it.getValue("captureLocation") })
            assertEquals(input.documents.map { it.artifact.uri }, declarations.map { it.getValue("source").jsonObject.getValue("uri").jsonPrimitive.content })
            assertEquals(input.documents.map { it.artifact.name }, declarations.map { it.getValue("source").jsonObject.getValue("filename").jsonPrimitive.content })
            assertEquals(2, output.getValue("files").jsonArray.size)
            assertFalse(output.containsKey("videoUri"))
        }
        assertEquals(before, input.documents.map { it.text })
    }

    @Test fun technicalRecursivelyRedactsLocationIncludingMalformedPayloadAndKeepsTechnicalData() {
        val input = snapshot(buildJsonObject {
            put("captureLocation", fix)
            put("nested", buildJsonArray { add(buildJsonObject {
                put("captureLocation", buildJsonObject { put("SECRET_LOCATION", "must disappear even when malformed") })
                put("productionSlate", buildJsonObject { put("project", "NESTED_SECRET") })
                put("sensorTimestampNs", 9_007_199_254_740_993L)
            }) })
        })
        val before = input.documents.map { it.text }
        val plan = mediaSharePlan(input, MediaSharingSettings(metadata = MediaShareMetadata.TECHNICAL))
        val technical = plan.generated.single().text
        for (secret in listOf("captureLocation", "latitude", "longitude", "SECRET_LOCATION", "PRIVATE_PROJECT", "NESTED_SECRET", "productionSlate")) {
            assertFalse(secret, technical.contains(secret))
            assertFalse(secret, mediaShareManifest(plan, emptyList()).contains(secret))
        }
        assertTrue(technical.contains("9007199254740993"))
        assertEquals(listOf(video), plan.originals)
        assertEquals(before, input.documents.map { it.text })
    }

    @Test fun bothKeepsLocationOnlyInProductionAndAudioAssociationDoesNotInventAnotherFix() {
        val input = snapshot(location(fix), JsonObject(emptyMap()))
        val plan = mediaSharePlan(input, MediaSharingSettings(metadata = MediaShareMetadata.BOTH))
        val production = Json.parseToJsonElement(plan.generated.single { it.role == MediaShareRole.PRODUCTION }.text).jsonObject
        val locations = production.getValue("captureLocations").jsonArray
        assertEquals(1, locations.size)
        assertEquals(input.documents.first().artifact.uri, locations.single().jsonObject.getValue("source").jsonObject.getValue("uri").jsonPrimitive.content)
        val technical = plan.generated.single { it.role == MediaShareRole.TECHNICAL }.text
        assertFalse(technical.contains("captureLocation")); assertFalse(technical.contains("latitude"))
        assertTrue(technical.contains(audio.uri)); assertTrue(technical.contains(video.uri))
        assertEquals(input.take.originals, plan.originals)
    }

    @Test fun locationOnlyProductionAndNonFixStatusesRemainHonestWithoutSyntheticSlateOrCoordinates() {
        for (status in CaptureLocationStatus.entries.filter { it != CaptureLocationStatus.AVAILABLE }) {
            val declaration = captureLocationJson(CaptureLocationSnapshot(status))
            val output = production(snapshot(location(declaration), withSlate = false))
            assertFalse(output.containsKey("productionSlate"))
            val preserved = output.getValue("captureLocations").jsonArray.single().jsonObject.getValue("captureLocation").jsonObject
            assertEquals(setOf("schema", "status"), preserved.keys)
            assertEquals(declaration, preserved)
        }
        val old = production(snapshot())
        assertEquals(setOf("schema", "takeId", "files", "productionSlate"), old.keys)
        assertThrows(IllegalArgumentException::class.java) { production(snapshot(withSlate = false)) }
    }

    @Test fun originalOnlyNeverInspectsOrRepackagesLocationDeclarations() {
        val input = snapshot(location(JsonPrimitive("malformed private location")))
        val before = input.documents.map { it.text }
        for (metadata in MediaShareMetadata.entries) {
            val plan = mediaSharePlan(input, MediaSharingSettings(content = MediaShareContent.ORIGINALS_ONLY, metadata = metadata))
            assertEquals(input.take.originals, plan.originals)
            assertTrue(plan.generated.isEmpty())
            assertFalse(mediaShareManifest(plan, emptyList()).contains("captureLocation"))
        }
        assertEquals(before, input.documents.map { it.text })
    }

    @Test fun malformedProductionLocationsRejectWithoutInventingOrDroppingDeclarations() {
        fun changed(key: String, value: JsonElement) = JsonObject(fix + (key to value))
        val malformed = listOf<JsonElement>(JsonNull, JsonPrimitive("coordinates"), JsonArray(emptyList()),
            JsonObject(fix - "latitude"), changed("latitude", JsonPrimitive(91)), changed("longitude", JsonPrimitive(-181)),
            changed("latitude", JsonPrimitive("-34.5")), changed("accuracyMeters", JsonPrimitive(-1)),
            changed("accuracyMeters", JsonPrimitive(1e100)), changed("ageMillis", JsonPrimitive(120001)),
            changed("ageMillis", JsonPrimitive("1")), changed("epochMillis", JsonPrimitive(0)),
            changed("epochMillis", JsonPrimitive(1.25)), changed("permissionPrecision", JsonPrimitive("UNKNOWN")),
            changed("status", JsonPrimitive("STALE")), changed("status", JsonPrimitive("UNKNOWN")),
            changed("schema", JsonPrimitive("opencinecam.capture-location.v2")), changed("altitude", JsonPrimitive(10)))
        for (value in malformed) for (selection in listOf(MediaShareMetadata.PRODUCTION, MediaShareMetadata.BOTH)) {
            val input = snapshot(buildJsonObject { put("nested", buildJsonArray { add(location(value)) }) })
            val before = input.documents.map { it.text }
            assertThrows("Malformed declaration=$value selection=$selection", IllegalArgumentException::class.java) { production(input, selection) }
            assertEquals(before, input.documents.map { it.text })
        }
    }
}
