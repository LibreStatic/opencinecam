/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import com.librestatic.opencinecam.camera.*
import com.librestatic.opencinecam.CaptureNameSnapshot
import com.librestatic.opencinecam.CaptureNamingSettings
import com.librestatic.opencinecam.ProductionSlateSettings
import com.librestatic.opencinecam.parseProductionSlateJson
import java.io.IOException
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AccumulationPublicationTest {
    private val id = "2b2a7bb1-5061-4379-86c1-cd7cbca97e42"
    private val timestamp = 9_007_199_254_740_993L
    private fun capture(mode: AccumulationMode = AccumulationMode.LIGHT,
        user: Boolean = false, reported: Boolean = true): CapturedAccumulation = CapturedAccumulation(
        id = timestamp,
        selection = AccumulationSelection(mode = mode),
        frames = (0..2).map { index -> AccumulationFrame(index, timestamp + 100 + index,
            timestamp + index * 250_000_000L, if (reported) 10_000_001L + index else null,
            if (reported) 100 + index else null) },
        image = StillImagePayload(StillImageKind.JPEG, byteArrayOf(1, 2, 3), 640, 480),
        orientationDegrees = 0, quality = 93, completedByUser = user,
    )

    @Test fun imageAndRelationAreClosedAndVerifiedBeforePublishingEither() {
        val store = Store()
        val result = publishAccumulationCapture(capture(), store, id)
        assertEquals(listOf("insert:1", "write:1", "close:1", "verify:1", "insert:2",
            "write:2", "close:2", "verify:2", "publish:1", "publish:2"), store.events)
        assertEquals(id, result.id)
        assertEquals("row:1", result.image.uri)
        assertEquals("row:2", result.metadataUri)
        assertEquals("OCC_$id.jpg", result.image.displayName)
        assertEquals("OCC_$id.accumulation.json", store.rows.getValue(result.metadataUri).name)
        assertEquals("image/jpeg", store.rows.getValue(result.image.uri).mime)
        assertEquals("application/json", store.rows.getValue(result.metadataUri).mime)
        assertTrue(store.rows.getValue(result.metadataUri).metadata)
        assertFalse(store.rows.getValue(result.image.uri).metadata)
        assertTrue(store.rows.values.all { it.closed && it.verified && it.published })
        assertTrue(store.deleted.isEmpty())
    }

    @Test fun eachModeRecordsItsActualAlgorithmWithoutClaimingContinuousExposure() {
        val algorithms = mapOf(
            AccumulationMode.LIGHT to "LINEAR_SRGB_CHANNEL_MAX",
            AccumulationMode.WATER to "LINEAR_SRGB_TEMPORAL_MEAN",
            AccumulationMode.STARS to "LINEAR_SRGB_MAX_LUMINANCE_WHOLE_RGB",
            AccumulationMode.BULB to "LINEAR_SRGB_ADDITIVE_CLIPPED_SIMULATION")
        algorithms.forEach { (mode, algorithm) ->
            val store = Store()
            val result = publishAccumulationCapture(capture(mode), store, id)
            val json = relation(store, result)
            assertEquals(mode.name, json.getValue("mode").jsonPrimitive.content)
            assertEquals(algorithm, json.getValue("algorithm").jsonPrimitive.content)
            assertEquals("COMPLETE", json.getValue("result").jsonPrimitive.content)
            assertEquals("COMPUTATIONAL_ACCUMULATION_NOT_CONTINUOUS_EXPOSURE", json.getValue("output").jsonPrimitive.content)
            assertEquals("BEST_EFFORT_COMPENSATED_NOT_CRASH_ATOMIC", json.getValue("publicationSemantics").jsonPrimitive.content)
            assertFalse(json.containsKey("physicalExposureAccepted"))
            assertFalse(json.getValue("completedByUser").jsonPrimitive.boolean)
        }
    }

    @Test fun metadataPreservesLongIdentitiesAllFramesSelectionAndImageHash() {
        val input = capture(AccumulationMode.STARS)
        val store = Store()
        val result = publishAccumulationCapture(input, store, id)
        val json = relation(store, result)
        assertEquals(1, json.getValue("schemaVersion").jsonPrimitive.int)
        assertEquals(id, json.getValue("bundleId").jsonPrimitive.content)
        assertEquals(timestamp, json.getValue("accumulationId").jsonPrimitive.long)
        assertEquals(3, json.getValue("frameCount").jsonPrimitive.int)
        assertEquals(500_000_000L, json.getValue("timestampSpanNs").jsonPrimitive.long)
        val selection = json.getValue("selection").jsonObject
        assertEquals(input.selection.mode.name, selection.getValue("mode").jsonPrimitive.content)
        assertEquals(input.selection.durationMs, selection.getValue("durationMs").jsonPrimitive.long)
        assertEquals(input.selection.intervalMs, selection.getValue("intervalMs").jsonPrimitive.long)
        assertEquals(input.selection.maxEdge, selection.getValue("maxEdge").jsonPrimitive.int)
        assertEquals(input.selection.starsThreshold, selection.getValue("starsThreshold").jsonPrimitive.int)
        val frames = json.getValue("frames").jsonArray
        assertEquals(3, frames.size)
        frames.forEachIndexed { index, value ->
            val frame = value.jsonObject
            val expected = input.frames[index]
            assertEquals(index, frame.getValue("index").jsonPrimitive.int)
            assertEquals(expected.captureId, frame.getValue("captureId").jsonPrimitive.long)
            assertEquals(expected.sensorTimestampNs, frame.getValue("sensorTimestampNs").jsonPrimitive.long)
            assertEquals(requireNotNull(expected.exposureTimeNs), frame.getValue("exposureTimeNs").jsonPrimitive.long)
            assertEquals(requireNotNull(expected.sensitivityIso), frame.getValue("sensitivityIso").jsonPrimitive.int)
        }
        val image = json.getValue("image").jsonObject
        assertEquals("JPEG", image.getValue("kind").jsonPrimitive.content)
        assertEquals(result.image.uri, image.getValue("uri").jsonPrimitive.content)
        assertEquals(result.image.displayName, image.getValue("displayName").jsonPrimitive.content)
        assertEquals("image/jpeg", image.getValue("mimeType").jsonPrimitive.content)
        assertEquals(3L, image.getValue("bytes").jsonPrimitive.long)
        assertEquals(stillSha256(input.image.bytes), image.getValue("sha256").jsonPrimitive.content)
        assertEquals(640, image.getValue("width").jsonPrimitive.int)
        assertEquals(480, image.getValue("height").jsonPrimitive.int)
        assertEquals(0, image.getValue("orientationDegrees").jsonPrimitive.int)
        assertEquals(93, image.getValue("quality").jsonPrimitive.int)
    }

    @Test fun userCompletionKeepsActualSpanSeparateFromRequestedDurationAndUnknownResultsNull() {
        val store = Store()
        val result = publishAccumulationCapture(capture(AccumulationMode.BULB, user = true, reported = false), store, id)
        val json = relation(store, result)
        assertTrue(json.getValue("completedByUser").jsonPrimitive.boolean)
        assertEquals(500_000_000L, json.getValue("timestampSpanNs").jsonPrimitive.long)
        assertEquals(10_000L, json.getValue("selection").jsonObject.getValue("durationMs").jsonPrimitive.long)
        json.getValue("frames").jsonArray.forEach {
            assertEquals(JsonNull, it.jsonObject["exposureTimeNs"])
            assertEquals(JsonNull, it.jsonObject["sensitivityIso"])
        }
    }

    @Test fun eachInsertFailureCompensatesExactlyTheRowsAlreadyAllocated() {
        (1..2).forEach { at ->
            val store = Store(failAt = "insert:$at")
            assertSame(store.failure, assertThrows(IOException::class.java) { publishAccumulationCapture(capture(), store, id) })
            assertEquals((1 until at).map { "row:$it" }, store.deleted)
            assertTrue(store.rows.isEmpty())
            assertFalse(store.events.any { it.startsWith("publish:") })
        }
    }

    @Test fun bothWriteFailuresCompensateWithoutPublishing() { assertPrepublicationFailures("write") }
    @Test fun bothCloseFailuresCompensateAfterBytesAreWritten() { assertPrepublicationFailures("close") }
    @Test fun bothVerificationFailuresCompensateWithoutPublishing() { assertPrepublicationFailures("verify") }

    private fun assertPrepublicationFailures(operation: String) {
        (1..2).forEach { at ->
            val store = Store(failAt = "$operation:$at")
            assertSame(store.failure, assertThrows(IOException::class.java) { publishAccumulationCapture(capture(), store, id) })
            assertEquals((1..at).map { "row:$it" }, store.deleted)
            assertTrue(store.rows.isEmpty())
            assertFalse(store.events.any { it.startsWith("publish:") })
        }
    }

    @Test fun corruptImageOrRelationReadbackCannotPublish() {
        (1..2).forEach { at ->
            val store = Store(corruptWrite = at)
            assertThrows(IllegalStateException::class.java) { publishAccumulationCapture(capture(), store, id) }
            assertEquals(at, store.deleted.size)
            assertTrue(store.rows.isEmpty())
            assertFalse(store.events.any { it.startsWith("publish:") })
        }
    }

    @Test fun eitherPublishFailureCompensatesIncludingPreviouslyVisibleImage() {
        (1..2).forEach { at ->
            val store = Store(failAt = "publish:$at")
            assertSame(store.failure, assertThrows(IOException::class.java) { publishAccumulationCapture(capture(), store, id) })
            assertEquals(listOf("row:1", "row:2"), store.deleted)
            assertEquals((1 until at).map { "row:$it" }, store.visibleBeforeDelete)
            assertTrue(store.rows.isEmpty())
        }
    }

    @Test fun zeroOrMultiplePublishedRowsCannotReturnSuccess() {
        listOf(0, 2).forEach { count ->
            val store = Store(publishResult = count)
            assertThrows(IllegalStateException::class.java) { publishAccumulationCapture(capture(), store, id) }
            assertEquals(2, store.deleted.size)
            assertTrue(store.rows.isEmpty())
        }
    }

    @Test fun allCleanupFailuresArePreservedWithoutReplacingPrimaryOrSkippingRows() {
        val cleanupA = IOException("delete image")
        val cleanupB = IOException("delete relation")
        val store = Store(failAt = "publish:2", deleteFailures = mapOf("row:1" to cleanupA, "row:2" to cleanupB))
        val error = assertThrows(IOException::class.java) { publishAccumulationCapture(capture(), store, id) }
        assertSame(store.failure, error)
        assertEquals(listOf("row:1", "row:2"), store.deleted)
        assertSame(cleanupA, error.suppressed.single())
        assertSame(cleanupB, cleanupA.suppressed.single())
    }

    @Test fun duplicateProviderIdentityCannotOverwriteVerifiedImageOrDoubleDelete() {
        val store = Store(duplicateInsert = true)
        assertThrows(IllegalStateException::class.java) { publishAccumulationCapture(capture(), store, id) }
        assertEquals(listOf("row:1"), store.deleted)
        assertEquals(1, store.events.count { it.startsWith("write:") })
        assertFalse(store.events.any { it.startsWith("publish:") })
    }

    @Test fun invalidUuidPerformsNoProviderIo() {
        val store = Store()
        assertThrows(IllegalArgumentException::class.java) { publishAccumulationCapture(capture(), store, "../other") }
        assertTrue(store.events.isEmpty())
        assertTrue(store.deleted.isEmpty())
    }

    @Test fun compensationDoesNotDeleteUnrelatedPublishedRows() {
        val store = Store(failAt = "publish:2")
        val prior = Row("old.jpg", "image/jpeg", false).apply {
            bytes = byteArrayOf(9); closed = true; verified = true; published = true
        }
        store.rows["unrelated"] = prior
        assertThrows(IOException::class.java) { publishAccumulationCapture(capture(), store, id) }
        assertSame(prior, store.rows.getValue("unrelated"))
        assertFalse("unrelated" in store.deleted)
    }

    @Test fun callerByteAccessCannotMutateThePublishedHashOrRelationship() {
        val input = capture()
        val bytes = input.image.bytes
        val store = Store()
        val result = publishAccumulationCapture(input, store, id)
        val metadata = store.rows.getValue(result.metadataUri).bytes.copyOf()
        bytes.fill(0)
        input.image.bytes.fill(0)
        assertEquals(stillSha256(byteArrayOf(1, 2, 3)), result.image.sha256)
        assertArrayEquals(byteArrayOf(1, 2, 3), store.rows.getValue(result.image.uri).bytes)
        assertArrayEquals(metadata, store.rows.getValue(result.metadataUri).bytes)
        assertEquals(result.image.sha256, relation(store, result).getValue("image").jsonObject.getValue("sha256").jsonPrimitive.content)
    }

    @Test fun exactlyTwoFramesCanPublishButOneFrameCannotReachProvider() {
        val input = capture()
        fun withFrames(count: Int) = CapturedAccumulation(input.id, input.selection, input.frames.take(count),
            input.image, input.orientationDegrees, input.quality, true)
        val store = Store()
        assertThrows(IllegalArgumentException::class.java) { publishAccumulationCapture(withFrames(1), store, id) }
        assertTrue(store.events.isEmpty())
        val result = publishAccumulationCapture(withFrames(2), store, id)
        assertEquals(2, relation(store, result).getValue("frameCount").jsonPrimitive.int)
        assertEquals(2, relation(store, result).getValue("frames").jsonArray.size)
        assertTrue(store.rows.values.all { it.published })
    }

    @Test fun callerFrameListMutationCannotChangeRelationshipProvenance() {
        val original = capture()
        val callerFrames = original.frames.toMutableList()
        val input = CapturedAccumulation(original.id, original.selection, callerFrames, original.image,
            original.orientationDegrees, original.quality, original.completedByUser)
        callerFrames.clear()
        val store = Store()
        val result = publishAccumulationCapture(input, store, id)
        val frames = relation(store, result).getValue("frames").jsonArray
        assertEquals(3, frames.size)
        assertEquals(original.frames.map { it.sensorTimestampNs },
            frames.map { it.jsonObject.getValue("sensorTimestampNs").jsonPrimitive.long })
    }

    @Test fun finalAccumulationCropDoesNotRewriteTheOriginalFrameProvenance() {
        val original = capture(AccumulationMode.WATER)
        val selection = PhotoAspectSelection(true, 1, 1)
        val report = PhotoAspectReport(selection, PhotoAspectDisposition.APPLIED,
            640, 480, PhotoCropRect(80, 0, 480, 480), 480, 480, 0)
        val input = CapturedAccumulation(original.id, original.selection, original.frames,
            StillImagePayload(StillImageKind.JPEG, original.image.bytes, 480, 480, aspectReport = report),
            0, original.quality, original.completedByUser)
        val store = Store()
        val result = publishAccumulationCapture(input, store, id)
        val json = relation(store, result)
        val image = json.getValue("image").jsonObject
        assertEquals(0, image.getValue("orientationDegrees").jsonPrimitive.int)
        assertEquals(480, image.getValue("width").jsonPrimitive.int)
        assertEquals(480, image.getValue("height").jsonPrimitive.int)
        val aspect = image.getValue("aspect").jsonObject
        assertEquals("APPLIED", aspect.getValue("disposition").jsonPrimitive.content)
        assertEquals(640, aspect.getValue("sourceWidth").jsonPrimitive.int)
        assertEquals(480, aspect.getValue("resultWidth").jsonPrimitive.int)
        assertEquals(80, aspect.getValue("crop").jsonObject.getValue("left").jsonPrimitive.int)
        assertEquals(original.frames.map { it.sensorTimestampNs }, json.getValue("frames").jsonArray.map {
            it.jsonObject.getValue("sensorTimestampNs").jsonPrimitive.long })
        assertEquals("LINEAR_SRGB_TEMPORAL_MEAN", json.getValue("algorithm").jsonPrimitive.content)
        assertEquals(stillSha256(original.image.bytes), result.image.sha256)
        assertArrayEquals(original.image.bytes, store.rows.getValue(result.image.uri).bytes)
    }

    @Test fun disabledAccumulationAspectPreservesLegacyFinalGeometry() {
        val store = Store()
        val result = publishAccumulationCapture(capture(), store, id)
        val image = relation(store, result).getValue("image").jsonObject
        assertEquals(JsonNull, image["aspect"])
        assertEquals(640, image.getValue("width").jsonPrimitive.int)
        assertEquals(480, image.getValue("height").jsonPrimitive.int)
        assertEquals(0, image.getValue("orientationDegrees").jsonPrimitive.int)
    }

    private fun relation(store: Store, result: AccumulationPublication): JsonObject =
        Json.parseToJsonElement(store.rows.getValue(result.metadataUri).bytes.decodeToString()).jsonObject

    @Test fun frozenProductionSlateChangesOnlyExistingRelationshipNotOriginalsOrNames() {
        val baseline = Store()
        val changed = Store()
        val capture = capture()
        val original = publishAccumulationCapture(capture, baseline, id)
        val slate = ProductionSlateSettings(project = "Editorial / \"A\"", scene = "12B", takeNumber = 7, goodTake = true)
        val result = publishAccumulationCapture(capture, changed, id, slate)
        val before = Json.parseToJsonElement(baseline.rows.getValue(original.metadataUri).bytes.decodeToString()).jsonObject
        val after = Json.parseToJsonElement(changed.rows.getValue(result.metadataUri).bytes.decodeToString()).jsonObject
        assertFalse(before.containsKey("productionSlate"))
        assertEquals(slate, parseProductionSlateJson(after.getValue("productionSlate").jsonObject))
        assertEquals(before, JsonObject(after - "productionSlate"))
        assertEquals(baseline.rows.keys, changed.rows.keys)
        baseline.rows.filterValues { !it.metadata }.forEach { (uri, row) ->
            assertArrayEquals(row.bytes, changed.rows.getValue(uri).bytes)
            assertEquals(row.name, changed.rows.getValue(uri).name)
            assertEquals(row.mime, changed.rows.getValue(uri).mime)
        }
        assertTrue(changed.rows.values.all { it.published && it.verified })
        assertEquals(slate, parseProductionSlateJson(after.getValue("productionSlate").jsonObject))
    }

    @Test fun slateRelationshipWriteAndPartialPublicationFailuresCompensateWholeGroup() {
        for (boundary in listOf("write:2", "verify:2", "publish:2")) {
            val store = Store(failAt = boundary)
            assertSame(store.failure, assertThrows(IOException::class.java) {
                publishAccumulationCapture(capture(), store, id, ProductionSlateSettings(project = "Frozen"))
            })
            assertTrue(store.rows.isEmpty())
            assertEquals(2, store.deleted.toSet().size)
        }
    }

    @Test fun configuredNamesPreserveRoleSuffixesBytesAndExactJsonRelationships() {
        val slate = ProductionSlateSettings(project = " A\u0301 cine ", scene = "12\u3000B", takeNumber = 7)
        val enabled = CaptureNameSnapshot(CaptureNamingSettings(true), slate, 0L)
        val baseline = Store()
        val input = capture()
        val original = publishAccumulationCapture(input, baseline, id, slate)
        val before = Json.parseToJsonElement(baseline.rows.getValue(original.metadataUri).bytes.decodeToString())
        for (snapshot in listOf(null, enabled.copy(settings = CaptureNamingSettings(false)), enabled,
            enabled.copy(settings = CaptureNamingSettings(true, "{scene}-{take}")))) {
            val store = Store()
            val result = publishAccumulationCapture(input, store, id, slate, snapshot)
            val stem = when {
                snapshot?.settings?.enabled != true -> "OCC_$id"
                snapshot.settings.template == "{scene}-{take}" -> "12_B-0007_$id"
                else -> "Á_cine__12_B_T0007_$id"
            }
            assertEquals(id, result.id)
            assertEquals(baseline.rows.keys, store.rows.keys)
            baseline.rows.forEach { (uri, old) ->
                val row = store.rows.getValue(uri)
                assertEquals(stem + old.name.removePrefix("OCC_$id"), row.name)
                assertEquals(old.mime, row.mime)
                if (!old.metadata) assertArrayEquals(old.bytes, row.bytes)
                assertTrue(row.closed && row.verified && row.published)
            }
            fun restoreDisplayNames(value: JsonElement): JsonElement = when (value) {
                is JsonArray -> JsonArray(value.map(::restoreDisplayNames))
                is JsonObject -> JsonObject(value.mapValues { (key, child) ->
                    if (key == "displayName" && value["uri"] is JsonPrimitive) {
                        val uri = value.getValue("uri").jsonPrimitive.content
                        assertEquals(store.rows.getValue(uri).name, child.jsonPrimitive.content)
                        JsonPrimitive(baseline.rows.getValue(uri).name)
                    } else restoreDisplayNames(child)
                })
                else -> value
            }
            val after = Json.parseToJsonElement(store.rows.getValue(result.metadataUri).bytes.decodeToString())
            assertEquals(before, restoreDisplayNames(after))
            assertEquals(slate, parseProductionSlateJson(after.jsonObject.getValue("productionSlate").jsonObject))
        }
    }

    @Test fun configuredNameSlateMismatchRejectsBeforeAnyProviderOperationEvenWhenDisabled() {
        val slate = ProductionSlateSettings(project = "Frozen")
        for (enabled in listOf(false, true)) {
            val store = Store()
            val snapshot = CaptureNameSnapshot(CaptureNamingSettings(enabled), slate.copy(scene = "Different"), 0L)
            assertThrows(IllegalArgumentException::class.java) { publishAccumulationCapture(capture(), store, id, slate, snapshot) }
            assertTrue(store.events.isEmpty())
            assertTrue(store.deleted.isEmpty())
        }
    }

    @Test fun configuredNamesKeepVerificationAndPartialPublicationCompensation() {
        val slate = ProductionSlateSettings(project = "Á cine")
        val snapshot = CaptureNameSnapshot(CaptureNamingSettings(true), slate, 0L)
        for (boundary in listOf("write:2", "verify:2", "publish:2")) {
            val store = Store(failAt = boundary)
            assertSame(store.failure, assertThrows(IOException::class.java) {
                publishAccumulationCapture(capture(), store, id, slate, snapshot)
            })
            assertTrue(store.rows.isEmpty())
            assertEquals(2, store.deleted.toSet().size)
        }
    }

    private class Row(val name: String, val mime: String, val metadata: Boolean) {
        var bytes = byteArrayOf()
        var closed = false
        var verified = false
        var published = false
    }
    private class Store(private val failAt: String? = null, private val corruptWrite: Int? = null,
        private val publishResult: Int = 1, private val deleteFailures: Map<String, Throwable> = emptyMap(),
        private val duplicateInsert: Boolean = false) : StillPublicationStore {
        val failure = IOException("Injected $failAt")
        val events = mutableListOf<String>()
        val rows = linkedMapOf<String, Row>()
        val deleted = mutableListOf<String>()
        val visibleBeforeDelete = mutableListOf<String>()
        private var next = 0
        private fun event(value: String) { events += value; if (failAt == value) throw failure }
        override fun insert(displayName: String, mimeType: String, metadata: Boolean): String {
            val index = ++next
            event("insert:$index")
            if (duplicateInsert && index == 2) return "row:1"
            return "row:$index".also { rows[it] = Row(displayName, mimeType, metadata) }
        }
        override fun writeClosed(uri: String, bytes: ByteArray) {
            val index = uri.removePrefix("row:")
            event("write:$index")
            rows.getValue(uri).bytes = bytes.copyOf().also { if (index.toInt() == corruptWrite) it[0] = (it[0] + 1).toByte() }
            event("close:$index")
            rows.getValue(uri).closed = true
        }
        override fun verify(uri: String, displayName: String, mimeType: String, size: Long, sha256: String) {
            event("verify:${uri.removePrefix("row:")}")
            val row = rows.getValue(uri)
            check(row.closed && !row.published && row.name == displayName && row.mime == mimeType)
            check(row.bytes.size.toLong() == size && stillSha256(row.bytes) == sha256)
            row.verified = true
        }
        override fun publish(uri: String): Int {
            check(rows.values.all { it.verified && it.closed })
            event("publish:${uri.removePrefix("row:")}")
            if (publishResult == 1) rows.getValue(uri).published = true
            return publishResult
        }
        override fun delete(uri: String) {
            deleted += uri
            if (rows[uri]?.published == true) visibleBeforeDelete += uri
            deleteFailures[uri]?.let { throw it }
            rows.remove(uri)
        }
    }
}
