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

class BurstPublicationTest {
    private val id = "2b2a7bb1-5061-4379-86c1-cd7cbca97e42"
    private val timestamp = 9_007_199_254_740_993L
    private fun capture(count: Int = 3, cropped: Boolean = false, reported: Boolean = true): CapturedBurst {
        val aspect = PhotoAspectSelection(cropped, 1, 1)
        val frames = (0 until count).map { index ->
            val time = timestamp + index * 1_000_003L
            val report = if (cropped) PhotoAspectReport(aspect, PhotoAspectDisposition.APPLIED,
                640, 480, PhotoCropRect(80, 0, 480, 480), 480, 480, 0) else null
            val still = CapturedStill(index + 1L, time, 90, 93,
                PhotoFlashReport(PhotoFlashSelection(), 1, 0, null, 2, 0, null, time),
                listOf(StillImagePayload(StillImageKind.JPEG, byteArrayOf(1, 2, index.toByte()),
                    if (cropped) 480 else 640, 480, aspectReport = report)), aspectSelection = aspect)
            BurstFrame(index, still, if (reported) 10_000_001L + index else null, if (reported) 100 + index else null)
        }
        return CapturedBurst(timestamp, count, frames, 93, aspect)
    }

    @Test fun allThreeThroughTenFramesAreWrittenClosedVerifiedBeforeAnyPublication() {
        for (count in 3..10) {
            val input = capture(count)
            val store = Store()
            val result = publishBurstCapture(input, store, id)
            val expected = (1..count).map { "insert:$it" }.toMutableList()
            (1..count).forEach { expected += listOf("write:$it", "close:$it", "verify:$it") }
            expected += listOf("insert:${count + 1}", "write:${count + 1}", "close:${count + 1}", "verify:${count + 1}")
            expected += (1..(count + 1)).map { "publish:$it" }
            assertEquals(expected, store.events)
            assertEquals(id, result.id)
            assertEquals(count, result.images.size)
            assertEquals("row:${count + 1}", result.metadataUri)
            assertEquals("OCC_$id.burst.json", store.rows.getValue(result.metadataUri).name)
            assertTrue(store.rows.getValue(result.metadataUri).metadata)
            assertTrue(store.rows.values.all { it.published && it.closed && it.verified })
            result.images.forEachIndexed { index, image ->
                assertEquals("OCC_${id}_${(index + 1).toString().padStart(2, '0')}.jpg", image.displayName)
                assertEquals(StillImageKind.JPEG, image.kind)
                assertEquals("image/jpeg", store.rows.getValue(image.uri).mime)
                assertArrayEquals(input.frames[index].capture.images.single().bytes, store.rows.getValue(image.uri).bytes)
            }
            assertTrue(store.deleted.isEmpty())
        }
    }

    @Test fun metadataPreservesLongIdentityActualTimingQualityAndEveryImageWithoutAssumingFps() {
        val input = capture(10)
        val store = Store()
        val result = publishBurstCapture(input, store, id)
        val json = relation(store, result)
        assertEquals(1, json.getValue("schemaVersion").jsonPrimitive.int)
        assertEquals(id, json.getValue("bundleId").jsonPrimitive.content)
        assertEquals(timestamp, json.getValue("burstId").jsonPrimitive.long)
        assertEquals("COMPLETE", json.getValue("result").jsonPrimitive.content)
        assertEquals("SEQUENTIAL_JPEG_BURST", json.getValue("output").jsonPrimitive.content)
        assertEquals("ACTUAL_SENSOR_TIMESTAMPS_NO_FIXED_FPS_GUARANTEE", json.getValue("timing").jsonPrimitive.content)
        assertEquals("BEST_EFFORT_COMPENSATED_NOT_CRASH_ATOMIC", json.getValue("publicationSemantics").jsonPrimitive.content)
        assertEquals(10, json.getValue("requestedCount").jsonPrimitive.int)
        assertEquals(10, json.getValue("frameCount").jsonPrimitive.int)
        assertEquals(93, json.getValue("quality").jsonPrimitive.int)
        assertFalse(json.getValue("aspectSelection").jsonObject.getValue("enabled").jsonPrimitive.boolean)
        val frames = json.getValue("frames").jsonArray
        assertEquals(10, frames.size)
        frames.forEachIndexed { index, value ->
            val frame = value.jsonObject
            val expected = input.frames[index]
            val image = result.images[index]
            assertEquals(index, frame.getValue("index").jsonPrimitive.int)
            assertEquals(expected.capture.captureId, frame.getValue("captureId").jsonPrimitive.long)
            assertEquals(expected.capture.sensorTimestampNs, frame.getValue("sensorTimestampNs").jsonPrimitive.long)
            assertEquals(requireNotNull(expected.exposureTimeNs), frame.getValue("exposureTimeNs").jsonPrimitive.long)
            assertEquals(requireNotNull(expected.sensitivityIso), frame.getValue("sensitivityIso").jsonPrimitive.int)
            assertEquals(image.uri, frame.getValue("uri").jsonPrimitive.content)
            assertEquals(image.displayName, frame.getValue("displayName").jsonPrimitive.content)
            assertEquals(image.bytes, frame.getValue("bytes").jsonPrimitive.long)
            assertEquals(image.sha256, frame.getValue("sha256").jsonPrimitive.content)
            assertEquals(stillSha256(expected.capture.images.single().bytes), image.sha256)
            assertEquals(640, frame.getValue("width").jsonPrimitive.int)
            assertEquals(480, frame.getValue("height").jsonPrimitive.int)
            assertEquals(90, frame.getValue("orientationDegrees").jsonPrimitive.int)
            assertEquals(93, frame.getValue("quality").jsonPrimitive.int)
            assertEquals(JsonNull, frame["aspect"])
        }
    }

    @Test fun unknownCaptureResultsRemainNullInsteadOfRequestedValues() {
        val store = Store()
        val result = publishBurstCapture(capture(reported = false), store, id)
        relation(store, result).getValue("frames").jsonArray.forEach {
            assertEquals(JsonNull, it.jsonObject["exposureTimeNs"])
            assertEquals(JsonNull, it.jsonObject["sensitivityIso"])
        }
    }

    @Test fun croppedBurstStoresActualPerFrameGeometryOrientationAndByteHash() {
        val input = capture(3, cropped = true)
        val store = Store()
        val result = publishBurstCapture(input, store, id)
        val json = relation(store, result)
        assertTrue(json.getValue("aspectSelection").jsonObject.getValue("enabled").jsonPrimitive.boolean)
        json.getValue("frames").jsonArray.forEachIndexed { index, value ->
            val frame = value.jsonObject
            assertEquals(480, frame.getValue("width").jsonPrimitive.int)
            assertEquals(480, frame.getValue("height").jsonPrimitive.int)
            assertEquals(0, frame.getValue("orientationDegrees").jsonPrimitive.int)
            val aspect = frame.getValue("aspect").jsonObject
            assertEquals("APPLIED", aspect.getValue("disposition").jsonPrimitive.content)
            assertEquals("ORIENTED_IMAGE_PIXELS", aspect.getValue("coordinateSpace").jsonPrimitive.content)
            assertEquals(640, aspect.getValue("sourceWidth").jsonPrimitive.int)
            assertEquals(480, aspect.getValue("sourceHeight").jsonPrimitive.int)
            assertEquals(80, aspect.getValue("crop").jsonObject.getValue("left").jsonPrimitive.int)
            assertEquals(0, aspect.getValue("crop").jsonObject.getValue("top").jsonPrimitive.int)
            assertEquals(480, aspect.getValue("resultWidth").jsonPrimitive.int)
            assertEquals(480, aspect.getValue("resultHeight").jsonPrimitive.int)
            assertEquals(stillSha256(input.frames[index].capture.images.single().bytes), frame.getValue("sha256").jsonPrimitive.content)
            assertArrayEquals(input.frames[index].capture.images.single().bytes, store.rows.getValue(result.images[index].uri).bytes)
        }
    }

    @Test fun incompleteOrInvalidCountNeverReachesProvider() {
        val original = capture()
        val store = Store()
        for (count in listOf(2, 4, 11)) {
            assertThrows(IllegalArgumentException::class.java) {
                publishBurstCapture(CapturedBurst(original.id, count, original.frames, original.quality, original.aspectSelection), store, id)
            }
        }
        assertTrue(store.events.isEmpty())
        assertTrue(store.deleted.isEmpty())
    }

    @Test fun everyInsertFailureDeletesExactlyTheRowsAlreadyCreated() {
        for (at in 1..11) {
            val store = Store(failAt = "insert:$at")
            assertSame(store.failure, assertThrows(IOException::class.java) { publishBurstCapture(capture(10), store, id) })
            assertEquals((1 until at).map { "row:$it" }, store.deleted)
            assertTrue(store.rows.isEmpty())
            assertFalse(store.events.any { it.startsWith("publish:") })
        }
    }
    @Test fun everyWriteFailureCompensatesWithoutReturningPartialBurst() { assertPrepublicationFailures("write") }
    @Test fun everyCloseFailureCompensatesWithoutPublishingWrittenBytes() { assertPrepublicationFailures("close") }
    @Test fun everyVerificationFailureCompensatesWithoutPublishing() { assertPrepublicationFailures("verify") }
    private fun assertPrepublicationFailures(operation: String) {
        for (at in 1..11) {
            val store = Store(failAt = "$operation:$at")
            assertSame(store.failure, assertThrows(IOException::class.java) { publishBurstCapture(capture(10), store, id) })
            assertEquals(if (at == 11) 11 else 10, store.deleted.size)
            assertTrue(store.rows.isEmpty())
            assertFalse(store.events.any { it.startsWith("publish:") })
        }
    }

    @Test fun corruptImageOrRelationshipBytesRejectBeforePublication() {
        for (at in 1..4) {
            val store = Store(corruptWrite = at)
            assertThrows(IllegalStateException::class.java) { publishBurstCapture(capture(), store, id) }
            assertTrue(store.rows.isEmpty())
            assertFalse(store.events.any { it.startsWith("publish:") })
        }
    }

    @Test fun everyPublishFailureDeletesAllRowsIncludingAlreadyVisibleImages() {
        for (at in 1..11) {
            val store = Store(failAt = "publish:$at")
            assertSame(store.failure, assertThrows(IOException::class.java) { publishBurstCapture(capture(10), store, id) })
            assertEquals((1..11).map { "row:$it" }, store.deleted)
            assertEquals((1 until at).map { "row:$it" }, store.visibleBeforeDelete)
            assertTrue(store.rows.isEmpty())
        }
    }

    @Test fun zeroOrMultiplePublishedRowsRejectAndCompensate() {
        for (count in listOf(0, 2)) {
            val store = Store(publishResult = count)
            assertThrows(IllegalStateException::class.java) { publishBurstCapture(capture(), store, id) }
            assertEquals(4, store.deleted.size)
            assertTrue(store.rows.isEmpty())
        }
    }

    @Test fun cleanupFailuresAreSuppressedAndNeverSkipAnotherOwnedRow() {
        val first = IOException("image cleanup")
        val second = IOException("relationship cleanup")
        val store = Store(failAt = "publish:3", deleteFailures = mapOf("row:1" to first, "row:4" to second))
        val failure = assertThrows(IOException::class.java) { publishBurstCapture(capture(), store, id) }
        assertSame(store.failure, failure)
        assertSame(first, failure.suppressed.single())
        assertSame(second, first.suppressed.single())
        assertEquals((1..4).map { "row:$it" }, store.deleted)
    }

    @Test fun duplicateProviderUriIsDeletedOnlyOnceAndNeverWritten() {
        val store = Store(duplicateInsert = true)
        assertThrows(IllegalStateException::class.java) { publishBurstCapture(capture(), store, id) }
        assertEquals(listOf("row:1"), store.deleted)
        assertFalse(store.events.any { it.startsWith("write:") || it.startsWith("publish:") })
    }

    @Test fun invalidUuidDoesNotTouchProvider() {
        val store = Store()
        assertThrows(IllegalArgumentException::class.java) { publishBurstCapture(capture(), store, "../other") }
        assertTrue(store.events.isEmpty())
        assertTrue(store.deleted.isEmpty())
    }

    @Test fun compensationNeverTouchesAnUnrelatedExistingCapture() {
        val store = Store(failAt = "publish:3")
        val prior = Row("previous.jpg", "image/jpeg", false).apply {
            bytes = byteArrayOf(9); closed = true; verified = true; published = true
        }
        store.rows["unrelated"] = prior
        assertThrows(IOException::class.java) { publishBurstCapture(capture(), store, id) }
        assertSame(prior, store.rows.getValue("unrelated"))
        assertFalse("unrelated" in store.deleted)
    }

    @Test fun publishedImageListCannotBeChangedByItsCaller() {
        val result = publishBurstCapture(capture(), Store(), id)
        assertThrows(UnsupportedOperationException::class.java) { (result.images as MutableList<StillPublishedImage>).clear() }
        assertEquals(3, result.images.size)
    }

    private fun relation(store: Store, result: BurstPublication): JsonObject =
        Json.parseToJsonElement(store.rows.getValue(result.metadataUri).bytes.decodeToString()).jsonObject

    @Test fun frozenProductionSlateChangesOnlyExistingRelationshipNotOriginalsOrNames() {
        val baseline = Store()
        val changed = Store()
        val capture = capture()
        val original = publishBurstCapture(capture, baseline, id)
        val slate = ProductionSlateSettings(project = "Editorial / \"A\"", scene = "12B", takeNumber = 7, goodTake = true)
        val result = publishBurstCapture(capture, changed, id, slate)
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
        for (boundary in listOf("write:4", "verify:4", "publish:4")) {
            val store = Store(failAt = boundary)
            assertSame(store.failure, assertThrows(IOException::class.java) {
                publishBurstCapture(capture(), store, id, ProductionSlateSettings(project = "Frozen"))
            })
            assertTrue(store.rows.isEmpty())
            assertEquals(4, store.deleted.toSet().size)
        }
    }

    @Test fun configuredNamesPreserveRoleSuffixesBytesAndExactJsonRelationships() {
        val slate = ProductionSlateSettings(project = " A\u0301 cine ", scene = "12\u3000B", takeNumber = 7)
        val enabled = CaptureNameSnapshot(CaptureNamingSettings(true), slate, 0L)
        val baseline = Store()
        val input = capture()
        val original = publishBurstCapture(input, baseline, id, slate)
        val before = Json.parseToJsonElement(baseline.rows.getValue(original.metadataUri).bytes.decodeToString())
        for (snapshot in listOf(null, enabled.copy(settings = CaptureNamingSettings(false)), enabled,
            enabled.copy(settings = CaptureNamingSettings(true, "{scene}-{take}")))) {
            val store = Store()
            val result = publishBurstCapture(input, store, id, slate, snapshot)
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
            assertThrows(IllegalArgumentException::class.java) { publishBurstCapture(capture(), store, id, slate, snapshot) }
            assertTrue(store.events.isEmpty())
            assertTrue(store.deleted.isEmpty())
        }
    }

    @Test fun configuredNamesKeepVerificationAndPartialPublicationCompensation() {
        val slate = ProductionSlateSettings(project = "Á cine")
        val snapshot = CaptureNameSnapshot(CaptureNamingSettings(true), slate, 0L)
        for (boundary in listOf("write:4", "verify:4", "publish:4")) {
            val store = Store(failAt = boundary)
            assertSame(store.failure, assertThrows(IOException::class.java) {
                publishBurstCapture(capture(), store, id, slate, snapshot)
            })
            assertTrue(store.rows.isEmpty())
            assertEquals(4, store.deleted.toSet().size)
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
