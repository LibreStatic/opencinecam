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

class StillPublicationTest {
    private val id = "2b2a7bb1-5061-4379-86c1-cd7cbca97e42"
    private val timestamp = 9_007_199_254_740_993L
    private fun capture(kinds: List<StillImageKind> = listOf(StillImageKind.JPEG, StillImageKind.DNG)): CapturedStill = CapturedStill(
        captureId = 7, sensorTimestampNs = timestamp, orientationDegrees = 90, quality = 93,
        flashReport = PhotoFlashReport(PhotoFlashSelection(PhotoFlashMode.ON, 2), 1, 1, 2, 2, 3, 2, timestamp),
        images = kinds.mapIndexed { index, kind -> StillImagePayload(kind, byteArrayOf(1, 2, (index + 3).toByte()), 640, 480) },
    )

    @Test fun pairWritesClosesAndVerifiesAllImagesAndRelationBeforeAnyPublication() {
        val store = Store()
        val result = publishStillCapture(capture(), store, id)
        assertEquals(listOf("insert:1", "insert:2", "write:1", "close:1", "verify:1", "write:2", "close:2", "verify:2",
            "insert:3", "write:3", "close:3", "verify:3", "publish:1", "publish:2", "publish:3"), store.events)
        assertEquals(id, result.id); assertEquals(timestamp, result.sensorTimestampNs)
        assertEquals(2, result.images.size); assertEquals("row:3", result.metadataUri)
        assertEquals(setOf(StillImageKind.JPEG, StillImageKind.DNG), result.images.map { it.kind }.toSet())
        assertTrue(result.images.all { it.displayName.startsWith("OCC_$id.") && it.bytes == 3L && it.sha256.length == 64 })
        assertTrue(store.rows.values.all { it.published && it.closed && it.verified })
        assertTrue(store.deleted.isEmpty())
    }

    @Test fun eachSingleFormatPublishesOneImagePlusRelationshipWithActualMime() {
        StillImageKind.entries.forEach { kind ->
            val store = Store()
            val result = publishStillCapture(capture(listOf(kind)), store, id)
            assertEquals(kind, result.images.single().kind)
            assertEquals(stillMimeType(kind), store.rows.getValue("row:1").mime)
            assertEquals(2, store.rows.size)
            assertTrue(store.rows.getValue("row:2").metadata)
            assertTrue(store.rows.values.all { it.published })
        }
    }

    @Test fun relationshipPreservesLongIdentityOrientationQualityFlashAndEveryImageHash() {
        val store = Store()
        val input = capture()
        val result = publishStillCapture(input, store, id)
        val json = Json.parseToJsonElement(store.rows.getValue(result.metadataUri).bytes.decodeToString()).jsonObject
        assertEquals(id, json.getValue("bundleId").jsonPrimitive.content)
        assertEquals(timestamp, json.getValue("sensorTimestampNs").jsonPrimitive.long)
        assertEquals(7L, json.getValue("captureId").jsonPrimitive.long)
        assertEquals(90, json.getValue("orientationDegrees").jsonPrimitive.int)
        assertEquals(93, json.getValue("quality").jsonPrimitive.int)
        assertEquals("BEST_EFFORT_COMPENSATED_NOT_CRASH_ATOMIC", json.getValue("publicationSemantics").jsonPrimitive.content)
        val flash = json.getValue("flash").jsonObject
        assertEquals("ON", flash.getValue("requestedMode").jsonPrimitive.content)
        assertEquals(2, flash.getValue("requestedStrength").jsonPrimitive.int)
        assertEquals(1, flash.getValue("submittedAeMode").jsonPrimitive.int)
        assertEquals(1, flash.getValue("submittedFlashMode").jsonPrimitive.int)
        assertEquals(2, flash.getValue("submittedStrength").jsonPrimitive.int)
        assertEquals(3, flash.getValue("reportedFlashState").jsonPrimitive.int)
        assertEquals(2, flash.getValue("reportedStrength").jsonPrimitive.int)
        assertEquals(timestamp, flash.getValue("sensorTimestampNs").jsonPrimitive.long)
        val images = json.getValue("images").jsonArray
        assertEquals(2, images.size)
        images.forEach { value ->
            val image = value.jsonObject
            val saved = result.images.single { it.uri == image.getValue("uri").jsonPrimitive.content }
            assertEquals(saved.kind.name, image.getValue("kind").jsonPrimitive.content)
            assertEquals(saved.displayName, image.getValue("displayName").jsonPrimitive.content)
            assertEquals(saved.sha256, image.getValue("sha256").jsonPrimitive.content)
            assertEquals(saved.bytes, image.getValue("bytes").jsonPrimitive.long)
            assertEquals(timestamp, image.getValue("sensorTimestampNs").jsonPrimitive.long)
            assertEquals(640, image.getValue("width").jsonPrimitive.int)
            assertEquals(480, image.getValue("height").jsonPrimitive.int)
        }
    }

    @Test fun unknownFlashResultFieldsRemainNullRatherThanInventingFiring() {
        val input = capture(listOf(StillImageKind.JPEG))
        val unknown = CapturedStill(input.captureId, input.sensorTimestampNs, input.orientationDegrees, input.quality,
            PhotoFlashReport(PhotoFlashSelection(), null, 0, null, null, null, null, timestamp), input.images)
        val store = Store()
        val result = publishStillCapture(unknown, store, id)
        val flash = Json.parseToJsonElement(store.rows.getValue(result.metadataUri).bytes.decodeToString()).jsonObject.getValue("flash").jsonObject
        listOf("requestedStrength", "submittedAeMode", "submittedStrength", "reportedAeState", "reportedFlashState", "reportedStrength")
            .forEach { assertEquals(JsonNull, flash[it]) }
    }

    @Test fun everyInsertFailureCompensatesOnlyRowsAlreadyAllocated() {
        (1..3).forEach { at ->
            val store = Store(failAt = "insert:$at")
            assertSame(store.failure, assertThrows(IOException::class.java) { publishStillCapture(capture(), store, id) })
            assertEquals((1 until at).map { "row:$it" }, store.deleted)
            assertTrue(store.rows.isEmpty()); assertFalse(store.events.any { it.startsWith("publish:") })
        }
    }

    @Test fun everyWriteFailureCompensatesEveryOwnedImageAndMetadataWhenAllocated() {
        (1..3).forEach { at ->
            val store = Store(failAt = "write:$at")
            assertSame(store.failure, assertThrows(IOException::class.java) { publishStillCapture(capture(), store, id) })
            assertEquals(if (at == 3) 3 else 2, store.deleted.size)
            assertTrue(store.rows.isEmpty()); assertFalse(store.events.any { it.startsWith("publish:") })
        }
    }

    @Test fun writerCloseFailureCannotPublishEvenIfBytesWereWritten() {
        val store = Store(failAt = "close:2")
        assertSame(store.failure, assertThrows(IOException::class.java) { publishStillCapture(capture(), store, id) })
        assertEquals(listOf("row:1", "row:2"), store.deleted)
        assertFalse(store.events.any { it.startsWith("publish:") })
    }

    @Test fun everyVerificationFailureCompensatesAndNeverPublishes() {
        (1..3).forEach { at ->
            val store = Store(failAt = "verify:$at")
            assertSame(store.failure, assertThrows(IOException::class.java) { publishStillCapture(capture(), store, id) })
            assertTrue(store.rows.isEmpty()); assertFalse(store.events.any { it.startsWith("publish:") })
        }
    }

    @Test fun alteredReadBackBytesFailChecksumBeforePublication() {
        val store = Store(corruptWrite = 2)
        assertThrows(IllegalStateException::class.java) { publishStillCapture(capture(), store, id) }
        assertEquals(listOf("row:1", "row:2"), store.deleted)
        assertFalse(store.events.any { it.startsWith("publish:") })
    }

    @Test fun everyPublicationFailureCompensatesIncludingAlreadyVisibleRows() {
        (1..3).forEach { at ->
            val store = Store(failAt = "publish:$at")
            assertSame(store.failure, assertThrows(IOException::class.java) { publishStillCapture(capture(), store, id) })
            assertEquals(listOf("row:1", "row:2", "row:3"), store.deleted)
            assertEquals((1 until at).map { "row:$it" }, store.visibleBeforeDelete)
            assertTrue(store.rows.isEmpty())
        }
    }

    @Test fun zeroOrMultipleUpdatedRowsAreFailuresAndRevokePartiallyPublishedImages() {
        listOf(0, 2).forEach { count ->
            val store = Store(publishResult = count)
            assertThrows(IllegalStateException::class.java) { publishStillCapture(capture(), store, id) }
            assertEquals(3, store.deleted.size); assertTrue(store.rows.isEmpty())
        }
    }

    @Test fun cleanupFailuresDoNotReplacePrimaryOrSkipRemainingDeletions() {
        val cleanupA = IOException("delete image")
        val cleanupB = IOException("delete metadata")
        val store = Store(failAt = "publish:2", deleteFailures = mapOf("row:1" to cleanupA, "row:3" to cleanupB))
        val failure = assertThrows(IOException::class.java) { publishStillCapture(capture(), store, id) }
        assertSame(store.failure, failure)
        assertEquals(listOf("row:1", "row:2", "row:3"), store.deleted)
        assertSame(cleanupA, failure.suppressed.single())
        assertSame(cleanupB, cleanupA.suppressed.single())
        assertEquals(setOf("row:1", "row:3"), store.rows.keys)
    }

    @Test fun duplicateProviderIdentityRejectsWithoutDoubleDeletionOrAnyPublication() {
        val store = Store(duplicateInsert = true)
        assertThrows(IllegalStateException::class.java) { publishStillCapture(capture(), store, id) }
        assertEquals(listOf("row:1"), store.deleted)
        assertFalse(store.events.any { it.startsWith("write:") || it.startsWith("publish:") })
    }

    @Test fun invalidBundleIdentityPerformsNoProviderOperations() {
        val store = Store()
        assertThrows(IllegalArgumentException::class.java) { publishStillCapture(capture(), store, "../other") }
        assertTrue(store.events.isEmpty()); assertTrue(store.deleted.isEmpty())
    }

    @Test fun cleanupNeverDeletesAnUnrelatedExistingRow() {
        val store = Store(failAt = "publish:3")
        val existing = Row("previous.jpg", "image/jpeg", false).apply { bytes = byteArrayOf(9); closed = true; verified = true; published = true }
        store.rows["unrelated"] = existing
        assertThrows(IOException::class.java) { publishStillCapture(capture(), store, id) }
        assertSame(existing, store.rows.getValue("unrelated")); assertFalse("unrelated" in store.deleted)
    }

    @Test fun croppedJpegAndUntouchedRawKeepDistinctGeometryOrientationAndByteHashes() {
        val selection = PhotoAspectSelection(true, 1, 1)
        val jpegReport = PhotoAspectReport(selection, PhotoAspectDisposition.APPLIED,
            640, 480, PhotoCropRect(80, 0, 480, 480), 480, 480, 0)
        val rawReport = PhotoAspectReport(selection, PhotoAspectDisposition.RAW_UNCHANGED,
            640, 480, PhotoCropRect(0, 0, 640, 480), 640, 480, 90)
        val jpegBytes = byteArrayOf(1, 2, 3)
        val rawBytes = byteArrayOf(4, 5, 6, 7)
        val original = capture()
        val input = CapturedStill(original.captureId, original.sensorTimestampNs, 90, original.quality,
            original.flashReport, listOf(
                StillImagePayload(StillImageKind.JPEG, jpegBytes, 480, 480, aspectReport = jpegReport),
                StillImagePayload(StillImageKind.DNG, rawBytes, 640, 480, aspectReport = rawReport)),
            aspectSelection = selection)
        val store = Store()
        val result = publishStillCapture(input, store, id)
        val json = Json.parseToJsonElement(store.rows.getValue(result.metadataUri).bytes.decodeToString()).jsonObject
        val images = json.getValue("images").jsonArray.map { it.jsonObject }
        val jpeg = images.single { it.getValue("kind").jsonPrimitive.content == "JPEG" }
        val raw = images.single { it.getValue("kind").jsonPrimitive.content == "DNG" }
        assertEquals(90, json.getValue("orientationDegrees").jsonPrimitive.int)
        assertEquals(0, jpeg.getValue("orientationDegrees").jsonPrimitive.int)
        assertEquals(90, raw.getValue("orientationDegrees").jsonPrimitive.int)
        assertEquals(480, jpeg.getValue("width").jsonPrimitive.int)
        assertEquals(640, raw.getValue("width").jsonPrimitive.int)
        val cropped = jpeg.getValue("aspect").jsonObject
        val untouched = raw.getValue("aspect").jsonObject
        assertEquals("APPLIED", cropped.getValue("disposition").jsonPrimitive.content)
        assertEquals("RAW_UNCHANGED", untouched.getValue("disposition").jsonPrimitive.content)
        assertEquals("ORIENTED_IMAGE_PIXELS", cropped.getValue("coordinateSpace").jsonPrimitive.content)
        assertEquals("NATIVE_IMAGE_PIXELS", untouched.getValue("coordinateSpace").jsonPrimitive.content)
        assertTrue(cropped.getValue("requested").jsonObject.getValue("enabled").jsonPrimitive.boolean)
        assertEquals(1, cropped.getValue("requested").jsonObject.getValue("width").jsonPrimitive.int)
        assertEquals(1, cropped.getValue("requested").jsonObject.getValue("height").jsonPrimitive.int)
        assertEquals(640, cropped.getValue("sourceWidth").jsonPrimitive.int)
        assertEquals(480, cropped.getValue("sourceHeight").jsonPrimitive.int)
        assertEquals(480, cropped.getValue("resultWidth").jsonPrimitive.int)
        assertEquals(480, cropped.getValue("resultHeight").jsonPrimitive.int)
        assertEquals(0, cropped.getValue("outputOrientationDegrees").jsonPrimitive.int)
        assertEquals(80, cropped.getValue("crop").jsonObject.getValue("left").jsonPrimitive.int)
        assertEquals(0, cropped.getValue("crop").jsonObject.getValue("top").jsonPrimitive.int)
        assertEquals(480, cropped.getValue("crop").jsonObject.getValue("width").jsonPrimitive.int)
        assertEquals(480, cropped.getValue("crop").jsonObject.getValue("height").jsonPrimitive.int)
        assertEquals(0, untouched.getValue("crop").jsonObject.getValue("left").jsonPrimitive.int)
        assertEquals(640, untouched.getValue("crop").jsonObject.getValue("width").jsonPrimitive.int)
        assertEquals(stillSha256(jpegBytes), jpeg.getValue("sha256").jsonPrimitive.content)
        assertEquals(stillSha256(rawBytes), raw.getValue("sha256").jsonPrimitive.content)
        assertArrayEquals(jpegBytes, store.rows.getValue(result.images.single { it.kind == StillImageKind.JPEG }.uri).bytes)
        assertArrayEquals(rawBytes, store.rows.getValue(result.images.single { it.kind == StillImageKind.DNG }.uri).bytes)
    }

    @Test fun croppedHeicPreservesFormatAndEffectiveAspectOrientationHashMetadata() {
        val input = croppedHeicCapture()
        val store = Store()
        val result = publishStillCapture(input, store, id)
        val saved = result.images.single()
        assertEquals(StillImageKind.HEIC, saved.kind)
        assertEquals("OCC_$id.heic", saved.displayName)
        assertEquals("image/heic", store.rows.getValue(saved.uri).mime)
        assertArrayEquals(input.images.single().bytes, store.rows.getValue(saved.uri).bytes)
        assertEquals(stillSha256(input.images.single().bytes), saved.sha256)
        assertEquals(input.images.single().byteCount.toLong(), saved.bytes)
        assertEquals(listOf("insert:1", "write:1", "close:1", "verify:1", "insert:2", "write:2", "close:2",
            "verify:2", "publish:1", "publish:2"), store.events)
        val json = Json.parseToJsonElement(store.rows.getValue(result.metadataUri).bytes.decodeToString()).jsonObject
        assertEquals(timestamp, json.getValue("sensorTimestampNs").jsonPrimitive.long)
        assertEquals(90, json.getValue("orientationDegrees").jsonPrimitive.int)
        val image = json.getValue("images").jsonArray.single().jsonObject
        assertEquals("HEIC", image.getValue("kind").jsonPrimitive.content)
        assertEquals("image/heic", image.getValue("mimeType").jsonPrimitive.content)
        assertEquals(saved.uri, image.getValue("uri").jsonPrimitive.content)
        assertEquals(saved.displayName, image.getValue("displayName").jsonPrimitive.content)
        assertEquals(saved.sha256, image.getValue("sha256").jsonPrimitive.content)
        assertEquals(saved.bytes, image.getValue("bytes").jsonPrimitive.long)
        assertEquals(0, image.getValue("orientationDegrees").jsonPrimitive.int)
        assertEquals(240, image.getValue("width").jsonPrimitive.int)
        assertEquals(240, image.getValue("height").jsonPrimitive.int)
        val aspect = image.getValue("aspect").jsonObject
        assertEquals("APPLIED", aspect.getValue("disposition").jsonPrimitive.content)
        assertEquals("ORIENTED_IMAGE_PIXELS", aspect.getValue("coordinateSpace").jsonPrimitive.content)
        assertEquals(320, aspect.getValue("sourceWidth").jsonPrimitive.int)
        assertEquals(240, aspect.getValue("sourceHeight").jsonPrimitive.int)
        assertEquals(240, aspect.getValue("resultWidth").jsonPrimitive.int)
        assertEquals(240, aspect.getValue("resultHeight").jsonPrimitive.int)
        assertEquals(0, aspect.getValue("outputOrientationDegrees").jsonPrimitive.int)
        assertTrue(aspect.getValue("requested").jsonObject.getValue("enabled").jsonPrimitive.boolean)
        assertEquals(1, aspect.getValue("requested").jsonObject.getValue("width").jsonPrimitive.int)
        assertEquals(1, aspect.getValue("requested").jsonObject.getValue("height").jsonPrimitive.int)
        val crop = aspect.getValue("crop").jsonObject
        assertEquals(40, crop.getValue("left").jsonPrimitive.int)
        assertEquals(0, crop.getValue("top").jsonPrimitive.int)
        assertEquals(240, crop.getValue("width").jsonPrimitive.int)
        assertEquals(240, crop.getValue("height").jsonPrimitive.int)
        assertTrue(store.rows.values.all { it.closed && it.verified && it.published })
    }

    @Test fun croppedHeicImageOrMetadataCorruptionRejectsWithoutPartialPublication() {
        for (row in 1..2) {
            val store = Store(corruptWrite = row)
            assertThrows(IllegalStateException::class.java) { publishStillCapture(croppedHeicCapture(), store, id) }
            assertEquals((1..row).map { "row:$it" }, store.deleted)
            assertTrue(store.rows.isEmpty())
            assertFalse(store.events.any { it.startsWith("publish:") })
        }
    }

    // Storage contract fixture only; real HEIC decoding is covered by the native HEIC assets.
    private fun croppedHeicCapture(): CapturedStill {
        val selection = PhotoAspectSelection(true, 1, 1)
        val report = PhotoAspectReport(selection, PhotoAspectDisposition.APPLIED,
            320, 240, PhotoCropRect(40, 0, 240, 240), 240, 240, 0)
        val original = capture(listOf(StillImageKind.HEIC))
        return CapturedStill(original.captureId, original.sensorTimestampNs, 90, original.quality,
            original.flashReport, listOf(StillImagePayload(StillImageKind.HEIC, byteArrayOf(1, 4, 7, 9),
                240, 240, aspectReport = report)), aspectSelection = selection)
    }

    @Test fun defaultAspectIsExplicitlyUnknownAndRetainsLegacyImageOrientationAndBytes() {
        val input = capture()
        val store = Store()
        val result = publishStillCapture(input, store, id)
        val images = Json.parseToJsonElement(store.rows.getValue(result.metadataUri).bytes.decodeToString())
            .jsonObject.getValue("images").jsonArray
        images.forEach { value ->
            val image = value.jsonObject
            assertEquals(JsonNull, image["aspect"])
            assertEquals(90, image.getValue("orientationDegrees").jsonPrimitive.int)
            val saved = result.images.single { it.uri == image.getValue("uri").jsonPrimitive.content }
            assertArrayEquals(input.images.single { it.kind == saved.kind }.bytes, store.rows.getValue(saved.uri).bytes)
        }
    }

    @Test fun enabledAspectAlreadyMatchingFullFrameRecordsTheFullEffectiveRectangle() {
        val selection = PhotoAspectSelection(true, 4, 3)
        val report = PhotoAspectReport(selection, PhotoAspectDisposition.APPLIED,
            640, 480, PhotoCropRect(0, 0, 640, 480), 640, 480, 0)
        val original = capture(listOf(StillImageKind.JPEG))
        val input = CapturedStill(original.captureId, original.sensorTimestampNs, 90, original.quality,
            original.flashReport, listOf(StillImagePayload(StillImageKind.JPEG, original.images.single().bytes,
                640, 480, aspectReport = report)), aspectSelection = selection)
        val store = Store()
        val result = publishStillCapture(input, store, id)
        val image = Json.parseToJsonElement(store.rows.getValue(result.metadataUri).bytes.decodeToString())
            .jsonObject.getValue("images").jsonArray.single().jsonObject
        assertEquals("APPLIED", image.getValue("aspect").jsonObject.getValue("disposition").jsonPrimitive.content)
        val crop = image.getValue("aspect").jsonObject.getValue("crop").jsonObject
        assertEquals(0, crop.getValue("left").jsonPrimitive.int)
        assertEquals(0, crop.getValue("top").jsonPrimitive.int)
        assertEquals(640, crop.getValue("width").jsonPrimitive.int)
        assertEquals(480, crop.getValue("height").jsonPrimitive.int)
        assertEquals(0, image.getValue("orientationDegrees").jsonPrimitive.int)
        assertEquals(stillSha256(original.images.single().bytes), result.images.single().sha256)
    }

    @Test fun invalidAspectSelectionOrOutOfBoundsReportCannotReachPublication() {
        val store = Store()
        assertThrows(IllegalArgumentException::class.java) { PhotoAspectSelection(true, 0, 1) }
        assertThrows(IllegalArgumentException::class.java) { PhotoAspectSelection(true, 10001, 1) }
        assertThrows(IllegalArgumentException::class.java) { PhotoAspectSelection(true, 2, 2) }
        assertThrows(IllegalArgumentException::class.java) {
            val report = PhotoAspectReport(PhotoAspectSelection(true, 1, 1), PhotoAspectDisposition.APPLIED,
                640, 480, PhotoCropRect(161, 0, 480, 480), 480, 480, 0)
            val original = capture(listOf(StillImageKind.JPEG))
            publishStillCapture(CapturedStill(original.captureId, original.sensorTimestampNs, 90, original.quality,
                original.flashReport, listOf(StillImagePayload(StillImageKind.JPEG, byteArrayOf(1), 480, 480,
                    aspectReport = report)), aspectSelection = report.requested), store, id)
        }
        assertTrue(store.events.isEmpty())
    }

    @Test fun inconsistentCropPayloadOrMissingRequestedReportCannotCreateRows() {
        val store = Store()
        val selection = PhotoAspectSelection(true, 1, 1)
        val report = PhotoAspectReport(selection, PhotoAspectDisposition.APPLIED,
            640, 480, PhotoCropRect(80, 0, 480, 480), 480, 480, 0)
        val original = capture(listOf(StillImageKind.JPEG))
        fun publish(payload: StillImagePayload) = publishStillCapture(CapturedStill(original.captureId,
            original.sensorTimestampNs, original.orientationDegrees, original.quality, original.flashReport,
            listOf(payload), aspectSelection = selection), store, id)
        assertThrows(IllegalArgumentException::class.java) {
            publish(StillImagePayload(StillImageKind.JPEG, byteArrayOf(1), 640, 480, aspectReport = report))
        }
        assertThrows(IllegalArgumentException::class.java) {
            publish(StillImagePayload(StillImageKind.DNG, byteArrayOf(1), 480, 480, aspectReport = report))
        }
        assertThrows(IllegalArgumentException::class.java) {
            publish(StillImagePayload(StillImageKind.JPEG, byteArrayOf(1), 480, 480))
        }
        assertTrue(store.events.isEmpty())
        assertTrue(store.deleted.isEmpty())
    }

    @Test fun frozenProductionSlateChangesOnlyExistingRelationshipNotOriginalsOrNames() {
        val baseline = Store()
        val changed = Store()
        val capture = capture()
        val original = publishStillCapture(capture, baseline, id)
        val slate = ProductionSlateSettings(project = "Editorial / \"A\"", scene = "12B", takeNumber = 7, goodTake = true)
        val result = publishStillCapture(capture, changed, id, slate)
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
        for (boundary in listOf("write:3", "verify:3", "publish:3")) {
            val store = Store(failAt = boundary)
            assertSame(store.failure, assertThrows(IOException::class.java) {
                publishStillCapture(capture(), store, id, ProductionSlateSettings(project = "Frozen"))
            })
            assertTrue(store.rows.isEmpty())
            assertEquals(3, store.deleted.toSet().size)
        }
    }

    @Test fun legacyNullSlateKeepsSingleRowAndByteIdentityForJpegAndDng() {
        for ((extension, mime) in listOf("jpg" to "image/jpeg", "dng" to "image/x-adobe-dng")) {
            val store = Store()
            val bytes = byteArrayOf(1, 2, 3)
            val uri = publishLegacyStill(bytes, extension, mime, store, id)
            assertEquals("row:1", uri); assertEquals(1, store.rows.size)
            assertArrayEquals(bytes, store.rows.getValue(uri).bytes)
            assertEquals(listOf("insert:1", "write:1", "close:1", "verify:1", "publish:1"), store.events)
        }
    }

    @Test fun legacySlateRelationshipHasActualIdentityAndNoInventedCaptureEvidence() {
        val slate = ProductionSlateSettings(project = "../Editorial", takeNumber = 42)
        for ((extension, mime) in listOf("jpg" to "image/jpeg", "dng" to "image/x-adobe-dng")) {
            val store = Store()
            val bytes = byteArrayOf(1, 2, 3)
            val uri = publishLegacyStill(bytes, extension, mime, store, id, slate)
            assertArrayEquals(bytes, store.rows.getValue(uri).bytes)
            val relation = Json.parseToJsonElement(store.rows.getValue("row:2").bytes.decodeToString()).jsonObject
            assertEquals(slate, parseProductionSlateJson(relation.getValue("productionSlate").jsonObject))
            val image = relation.getValue("images").jsonArray.single().jsonObject
            assertEquals(uri, image.getValue("uri").jsonPrimitive.content)
            assertEquals("OCC_$id.$extension", image.getValue("displayName").jsonPrimitive.content)
            assertEquals(mime, image.getValue("mimeType").jsonPrimitive.content)
            assertEquals(stillSha256(bytes), image.getValue("sha256").jsonPrimitive.content)
            assertEquals(3L, image.getValue("bytes").jsonPrimitive.long)
            for (key in listOf("sensorTimestampNs", "captureId", "orientationDegrees", "width", "height", "exposureTimeNs")) {
                assertFalse(relation.containsKey(key)); assertFalse(image.containsKey(key))
            }
            assertEquals("OCC_$id.still.json", store.rows.getValue("row:2").name)
            assertTrue(store.rows.values.all { it.published && it.verified && it.closed })
        }
    }

    @Test fun legacySlateFailureDeletesBothRowsIncludingAlreadyPublishedOriginal() {
        for (boundary in listOf("write:2", "close:2", "verify:2", "publish:2")) {
            val store = Store(failAt = boundary)
            assertSame(store.failure, assertThrows(IOException::class.java) {
                publishLegacyStill(byteArrayOf(1), "jpg", "image/jpeg", store, id, ProductionSlateSettings())
            })
            assertTrue(store.rows.isEmpty()); assertEquals(setOf("row:1", "row:2"), store.deleted.toSet())
            if (boundary == "publish:2") assertEquals(listOf("row:1"), store.visibleBeforeDelete)
        }
    }

    @Test fun configuredNamesPreserveRoleSuffixesBytesAndExactJsonRelationships() {
        val slate = ProductionSlateSettings(project = " A\u0301 cine ", scene = "12\u3000B", takeNumber = 7)
        val enabled = CaptureNameSnapshot(CaptureNamingSettings(true), slate, 0L)
        val baseline = Store()
        val input = capture()
        val original = publishStillCapture(input, baseline, id, slate)
        val before = Json.parseToJsonElement(baseline.rows.getValue(original.metadataUri).bytes.decodeToString())
        for (snapshot in listOf(null, enabled.copy(settings = CaptureNamingSettings(false)), enabled,
            enabled.copy(settings = CaptureNamingSettings(true, "{scene}-{take}")))) {
            val store = Store()
            val result = publishStillCapture(input, store, id, slate, snapshot)
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
            assertThrows(IllegalArgumentException::class.java) { publishStillCapture(capture(), store, id, slate, snapshot) }
            assertTrue(store.events.isEmpty())
            assertTrue(store.deleted.isEmpty())
        }
    }

    @Test fun configuredNamesKeepVerificationAndPartialPublicationCompensation() {
        val slate = ProductionSlateSettings(project = "Á cine")
        val snapshot = CaptureNameSnapshot(CaptureNamingSettings(true), slate, 0L)
        for (boundary in listOf("write:3", "verify:3", "publish:3")) {
            val store = Store(failAt = boundary)
            assertSame(store.failure, assertThrows(IOException::class.java) {
                publishStillCapture(capture(), store, id, slate, snapshot)
            })
            assertTrue(store.rows.isEmpty())
            assertEquals(3, store.deleted.toSet().size)
        }
    }

    @Test fun legacyConfiguredJpegAndDngKeepOptionalSlateAndActualFilenameReferences() {
        val slate = ProductionSlateSettings(project = "夜 cine", scene = "12", takeNumber = 3)
        val bytes = byteArrayOf(5, 8, 13)
        for ((extension, mime) in listOf("jpg" to "image/jpeg", "dng" to "image/x-adobe-dng")) {
            for (editorial in listOf(null, slate)) for (enabled in listOf(false, true)) {
                val store = Store()
                val snapshot = CaptureNameSnapshot(CaptureNamingSettings(enabled), slate, 0L)
                val uri = publishLegacyStill(bytes, extension, mime, store, id, editorial, snapshot)
                val stem = if (enabled) "夜_cine_12_T0003_$id" else "OCC_$id"
                assertEquals("$stem.$extension", store.rows.getValue(uri).name)
                assertArrayEquals(bytes, store.rows.getValue(uri).bytes)
                assertEquals(if (editorial == null) 1 else 2, store.rows.size)
                if (editorial != null) {
                    val metadata = store.rows.values.single { it.metadata }
                    assertEquals("$stem.still.json", metadata.name)
                    val json = Json.parseToJsonElement(metadata.bytes.decodeToString()).jsonObject
                    assertEquals(id, json.getValue("bundleId").jsonPrimitive.content)
                    assertEquals(slate, parseProductionSlateJson(json.getValue("productionSlate").jsonObject))
                    val image = json.getValue("images").jsonArray.single().jsonObject
                    assertEquals(uri, image.getValue("uri").jsonPrimitive.content)
                    assertEquals("$stem.$extension", image.getValue("displayName").jsonPrimitive.content)
                    assertEquals(stillSha256(bytes), image.getValue("sha256").jsonPrimitive.content)
                }
                assertTrue(store.rows.values.all { it.verified && it.published })
            }
        }
    }

    @Test fun legacyConfiguredNamesRejectMismatchedSlateAndCompensatePartialPublication() {
        val slate = ProductionSlateSettings(project = "Frozen")
        val snapshot = CaptureNameSnapshot(CaptureNamingSettings(true), slate, 0L)
        val rejected = Store()
        assertThrows(IllegalArgumentException::class.java) {
            publishLegacyStill(byteArrayOf(1), "jpg", "image/jpeg", rejected, id, slate.copy(scene = "Other"), snapshot)
        }
        assertTrue(rejected.events.isEmpty())
        val failed = Store(failAt = "publish:2")
        assertSame(failed.failure, assertThrows(IOException::class.java) {
            publishLegacyStill(byteArrayOf(1), "jpg", "image/jpeg", failed, id, slate, snapshot)
        })
        assertTrue(failed.rows.isEmpty())
        assertEquals(listOf("row:1", "row:2"), failed.deleted)
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
