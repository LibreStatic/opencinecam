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

class BracketPublicationTest {
    private val id = "2b2a7bb1-5061-4379-86c1-cd7cbca97e42"
    private val timestamp = 9_007_199_254_740_993L
    private fun capture(count: Int = 3, step: BracketStep = BracketStep.TWO_EV,
        reported: Boolean = true): CapturedBracket {
        val selection = BracketSelection(count, step)
        return CapturedBracket(timestamp, selection, (0 until count).map { index ->
            val time = timestamp + index
            BracketFrame(index, (index - count / 2) * step.ev, index - count / 2,
                if (reported) index - count / 2 else null,
                if (reported) timestamp + 100 + index else null, if (reported) 100 + index else null,
                CapturedStill(index + 1L, time, 90, 93,
                    PhotoFlashReport(PhotoFlashSelection(), 1, 0, null, 2, 0, null, time),
                    listOf(StillImagePayload(StillImageKind.JPEG, byteArrayOf(1, 2, index.toByte()), 640, 480))))
        })
    }

    @Test fun completeSetClosesAndVerifiesBeforeAnyPublication() {
        val store = Store()
        val result = publishBracketCapture(capture(), store, id)
        assertEquals(listOf("insert:1", "insert:2", "insert:3", "write:1", "close:1", "verify:1",
            "write:2", "close:2", "verify:2", "write:3", "close:3", "verify:3", "insert:4",
            "write:4", "close:4", "verify:4", "publish:1", "publish:2", "publish:3", "publish:4"), store.events)
        assertEquals(id, result.id)
        assertEquals("row:4", result.metadataUri)
        assertEquals((1..3).map { "OCC_${id}_${it.toString().padStart(2, '0')}.jpg" }, result.images.map { it.displayName })
        assertEquals("OCC_$id.bracket.json", store.rows.getValue(result.metadataUri).name)
        assertTrue(store.rows.getValue(result.metadataUri).metadata)
        assertTrue(store.rows.values.all { it.published && it.closed && it.verified })
        assertTrue(store.deleted.isEmpty())
    }

    @Test fun allCountsAndStepsPreserveCompleteOrderedSeparateExposures() {
        listOf(3, 5, 7, 9).forEach { count -> BracketStep.entries.forEach { step ->
            val store = Store()
            val input = capture(count, step)
            val result = publishBracketCapture(input, store, id)
            assertEquals(count, result.images.size)
            assertEquals(count + 1, store.rows.size)
            result.images.forEachIndexed { index, image ->
                assertEquals(StillImageKind.JPEG, image.kind)
                assertEquals("row:${index + 1}", image.uri)
                assertEquals("image/jpeg", store.rows.getValue(image.uri).mime)
                assertArrayEquals(input.frames[index].capture.images.single().bytes, store.rows.getValue(image.uri).bytes)
            }
            val json = Json.parseToJsonElement(store.rows.getValue(result.metadataUri).bytes.decodeToString()).jsonObject
            assertEquals(count, json.getValue("selection").jsonObject.getValue("count").jsonPrimitive.int)
            assertEquals(step.name, json.getValue("selection").jsonObject.getValue("step").jsonPrimitive.content)
            assertEquals(count, json.getValue("frames").jsonArray.size)
        } }
    }

    @Test fun relationPreservesLongsEvResultsAndEveryImageIdentity() {
        val input = capture(step = BracketStep.THIRD_EV)
        val store = Store()
        val result = publishBracketCapture(input, store, id)
        val json = Json.parseToJsonElement(store.rows.getValue(result.metadataUri).bytes.decodeToString()).jsonObject
        assertEquals(1, json.getValue("schemaVersion").jsonPrimitive.int)
        assertEquals(id, json.getValue("bundleId").jsonPrimitive.content)
        assertEquals(timestamp, json.getValue("bracketId").jsonPrimitive.long)
        assertEquals("COMPLETE", json.getValue("result").jsonPrimitive.content)
        assertEquals("SEPARATE_EXPOSURES_NOT_HDR", json.getValue("output").jsonPrimitive.content)
        assertEquals("BEST_EFFORT_COMPENSATED_NOT_CRASH_ATOMIC", json.getValue("publicationSemantics").jsonPrimitive.content)
        val selection = json.getValue("selection").jsonObject
        assertEquals(1, selection.getValue("stepNumerator").jsonPrimitive.int)
        assertEquals(3, selection.getValue("stepDenominator").jsonPrimitive.int)
        json.getValue("frames").jsonArray.forEachIndexed { index, value ->
            val frame = value.jsonObject
            val expected = input.frames[index]
            val image = result.images[index]
            assertEquals(index, frame.getValue("index").jsonPrimitive.int)
            assertEquals(expected.requestedEv, frame.getValue("requestedEv").jsonPrimitive.double, 0.0)
            assertEquals(expected.submittedCompensation, frame.getValue("submittedCompensation").jsonPrimitive.int)
            assertEquals(requireNotNull(expected.reportedCompensation), frame.getValue("reportedCompensation").jsonPrimitive.int)
            assertEquals(requireNotNull(expected.exposureTimeNs), frame.getValue("exposureTimeNs").jsonPrimitive.long)
            assertEquals(requireNotNull(expected.sensitivityIso), frame.getValue("sensitivityIso").jsonPrimitive.int)
            assertEquals(expected.capture.captureId, frame.getValue("captureId").jsonPrimitive.long)
            assertEquals(expected.capture.sensorTimestampNs, frame.getValue("sensorTimestampNs").jsonPrimitive.long)
            assertEquals("JPEG", frame.getValue("kind").jsonPrimitive.content)
            assertEquals(image.uri, frame.getValue("uri").jsonPrimitive.content)
            assertEquals(image.displayName, frame.getValue("displayName").jsonPrimitive.content)
            assertEquals("image/jpeg", frame.getValue("mimeType").jsonPrimitive.content)
            assertEquals(3L, frame.getValue("bytes").jsonPrimitive.long)
            assertEquals(stillSha256(expected.capture.images.single().bytes), frame.getValue("sha256").jsonPrimitive.content)
            assertEquals(640, frame.getValue("width").jsonPrimitive.int)
            assertEquals(480, frame.getValue("height").jsonPrimitive.int)
            assertEquals(90, frame.getValue("orientationDegrees").jsonPrimitive.int)
            assertEquals(93, frame.getValue("quality").jsonPrimitive.int)
        }
    }

    @Test fun unknownReportedResultsStayNullNotRequestedValues() {
        val store = Store()
        val result = publishBracketCapture(capture(reported = false), store, id)
        val json = Json.parseToJsonElement(store.rows.getValue(result.metadataUri).bytes.decodeToString()).jsonObject
        json.getValue("frames").jsonArray.forEach { frame ->
            listOf("reportedCompensation", "exposureTimeNs", "sensitivityIso").forEach {
                assertEquals(JsonNull, frame.jsonObject[it])
            }
        }
    }

    @Test fun eachInsertFailureDeletesOnlyAllocatedRows() {
        (1..10).forEach { at ->
            val store = Store(failAt = "insert:$at")
            assertSame(store.failure, assertThrows(IOException::class.java) { publishBracketCapture(capture(9), store, id) })
            assertEquals((1 until at).map { "row:$it" }, store.deleted)
            assertTrue(store.rows.isEmpty())
            assertFalse(store.events.any { it.startsWith("publish:") })
        }
    }

    @Test fun eachWriteFailureCompensatesWithoutPublication() {
        assertPrepublicationFailures("write")
    }

    @Test fun eachCloseFailureCompensatesEvenAfterWritingBytes() {
        assertPrepublicationFailures("close")
    }

    @Test fun eachVerificationFailureCompensatesWithoutPublication() {
        assertPrepublicationFailures("verify")
    }

    private fun assertPrepublicationFailures(operation: String) {
        (1..10).forEach { at ->
            val store = Store(failAt = "$operation:$at")
            assertSame(store.failure, assertThrows(IOException::class.java) { publishBracketCapture(capture(9), store, id) })
            assertEquals(if (at == 10) 10 else 9, store.deleted.size)
            assertTrue(store.rows.isEmpty())
            assertFalse(store.events.any { it.startsWith("publish:") })
        }
    }

    @Test fun changedImageOrMetadataReadbackRejectsBeforePublication() {
        (1..4).forEach { at ->
            val store = Store(corruptWrite = at)
            assertThrows(IllegalStateException::class.java) { publishBracketCapture(capture(), store, id) }
            assertTrue(store.rows.isEmpty())
            assertFalse(store.events.any { it.startsWith("publish:") })
        }
    }

    @Test fun eachPublicationFailureCompensatesAlreadyVisibleRowsToo() {
        (1..10).forEach { at ->
            val store = Store(failAt = "publish:$at")
            assertSame(store.failure, assertThrows(IOException::class.java) { publishBracketCapture(capture(9), store, id) })
            assertEquals((1..10).map { "row:$it" }, store.deleted)
            assertEquals((1 until at).map { "row:$it" }, store.visibleBeforeDelete)
            assertTrue(store.rows.isEmpty())
        }
    }

    @Test fun zeroOrMultipleUpdatedRowsCannotReturnSuccess() {
        listOf(0, 2).forEach { count ->
            val store = Store(publishResult = count)
            assertThrows(IllegalStateException::class.java) { publishBracketCapture(capture(), store, id) }
            assertEquals(4, store.deleted.size)
            assertTrue(store.rows.isEmpty())
        }
    }

    @Test fun cleanupFailuresRemainSuppressedWithoutSkippingOtherRows() {
        val cleanupA = IOException("delete image")
        val cleanupB = IOException("delete metadata")
        val store = Store(failAt = "publish:2", deleteFailures = mapOf("row:1" to cleanupA, "row:4" to cleanupB))
        val error = assertThrows(IOException::class.java) { publishBracketCapture(capture(), store, id) }
        assertSame(store.failure, error)
        assertEquals((1..4).map { "row:$it" }, store.deleted)
        assertSame(cleanupA, error.suppressed.single())
        assertSame(cleanupB, cleanupA.suppressed.single())
        assertEquals(setOf("row:1", "row:4"), store.rows.keys)
    }

    @Test fun duplicateProviderRowIsOwnedOnceAndRejectsBeforeWrite() {
        val store = Store(duplicateInsert = true)
        assertThrows(IllegalStateException::class.java) { publishBracketCapture(capture(), store, id) }
        assertEquals(listOf("row:1"), store.deleted)
        assertFalse(store.events.any { it.startsWith("write:") || it.startsWith("publish:") })
    }

    @Test fun invalidBundleUuidPerformsNoIo() {
        val store = Store()
        assertThrows(IllegalArgumentException::class.java) { publishBracketCapture(capture(), store, "../other") }
        assertTrue(store.events.isEmpty())
        assertTrue(store.deleted.isEmpty())
    }

    @Test fun compensationLeavesPreexistingUnrelatedRowsUntouched() {
        val store = Store(failAt = "publish:3")
        val prior = Row("old.jpg", "image/jpeg", false).apply {
            bytes = byteArrayOf(9); closed = true; verified = true; published = true
        }
        store.rows["unrelated"] = prior
        assertThrows(IOException::class.java) { publishBracketCapture(capture(), store, id) }
        assertSame(prior, store.rows.getValue("unrelated"))
        assertFalse("unrelated" in store.deleted)
    }

    @Test fun returnedImageListCannotBeMutatedAfterPublication() {
        val result = publishBracketCapture(capture(), Store(), id)
        assertThrows(UnsupportedOperationException::class.java) {
            (result.images as MutableList<StillPublishedImage>).clear()
        }
        assertEquals(3, result.images.size)
    }

    @Test fun eachBracketFrameKeepsItsActualCropAndEncodedImageHash() {
        val original = capture()
        val selection = PhotoAspectSelection(true, 1, 1)
        val frames = original.frames.map { frame ->
            val still = frame.capture
            val report = PhotoAspectReport(selection, PhotoAspectDisposition.APPLIED,
                640, 480, PhotoCropRect(80, 0, 480, 480), 480, 480, 0)
            frame.copy(capture = CapturedStill(still.captureId, still.sensorTimestampNs, still.orientationDegrees,
                still.quality, still.flashReport, listOf(StillImagePayload(StillImageKind.JPEG,
                    still.images.single().bytes, 480, 480, aspectReport = report)), aspectSelection = selection))
        }
        val input = CapturedBracket(original.id, original.selection, frames)
        val store = Store()
        val result = publishBracketCapture(input, store, id)
        val json = Json.parseToJsonElement(store.rows.getValue(result.metadataUri).bytes.decodeToString()).jsonObject
        json.getValue("frames").jsonArray.forEachIndexed { index, value ->
            val frame = value.jsonObject
            assertEquals(0, frame.getValue("orientationDegrees").jsonPrimitive.int)
            assertEquals(480, frame.getValue("width").jsonPrimitive.int)
            assertEquals(480, frame.getValue("height").jsonPrimitive.int)
            assertEquals(input.frames[index].capture.sensorTimestampNs, frame.getValue("sensorTimestampNs").jsonPrimitive.long)
            val aspect = frame.getValue("aspect").jsonObject
            assertEquals("APPLIED", aspect.getValue("disposition").jsonPrimitive.content)
            assertEquals(640, aspect.getValue("sourceWidth").jsonPrimitive.int)
            assertEquals(480, aspect.getValue("resultWidth").jsonPrimitive.int)
            assertEquals(80, aspect.getValue("crop").jsonObject.getValue("left").jsonPrimitive.int)
            assertEquals(stillSha256(frames[index].capture.images.single().bytes), frame.getValue("sha256").jsonPrimitive.content)
            assertArrayEquals(frames[index].capture.images.single().bytes, store.rows.getValue(result.images[index].uri).bytes)
        }
        assertTrue(store.rows.values.all { it.published })
    }

    @Test fun legacyBracketLeavesAspectNullAndKeepsOriginalOrientation() {
        val store = Store()
        val result = publishBracketCapture(capture(), store, id)
        val frames = Json.parseToJsonElement(store.rows.getValue(result.metadataUri).bytes.decodeToString())
            .jsonObject.getValue("frames").jsonArray
        frames.forEach {
            assertEquals(JsonNull, it.jsonObject["aspect"])
            assertEquals(90, it.jsonObject.getValue("orientationDegrees").jsonPrimitive.int)
            assertEquals(640, it.jsonObject.getValue("width").jsonPrimitive.int)
        }
    }

    @Test fun frozenProductionSlateChangesOnlyExistingRelationshipNotOriginalsOrNames() {
        val baseline = Store()
        val changed = Store()
        val capture = capture()
        val original = publishBracketCapture(capture, baseline, id)
        val slate = ProductionSlateSettings(project = "Editorial / \"A\"", scene = "12B", takeNumber = 7, goodTake = true)
        val result = publishBracketCapture(capture, changed, id, slate)
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
                publishBracketCapture(capture(), store, id, ProductionSlateSettings(project = "Frozen"))
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
        val original = publishBracketCapture(input, baseline, id, slate)
        val before = Json.parseToJsonElement(baseline.rows.getValue(original.metadataUri).bytes.decodeToString())
        for (snapshot in listOf(null, enabled.copy(settings = CaptureNamingSettings(false)), enabled,
            enabled.copy(settings = CaptureNamingSettings(true, "{scene}-{take}")))) {
            val store = Store()
            val result = publishBracketCapture(input, store, id, slate, snapshot)
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
            assertThrows(IllegalArgumentException::class.java) { publishBracketCapture(capture(), store, id, slate, snapshot) }
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
                publishBracketCapture(capture(), store, id, slate, snapshot)
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
