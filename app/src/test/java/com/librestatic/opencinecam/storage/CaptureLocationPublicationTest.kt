/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import com.librestatic.opencinecam.*
import com.librestatic.opencinecam.camera.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CaptureLocationPublicationTest {
    private val id = "2b2a7bb1-5061-4379-86c1-cd7cbca97e42"
    private val frozen = CaptureLocationSnapshot(CaptureLocationStatus.AVAILABLE,
        CaptureLocationFix(-34.5, -58.4, 12.5f, 1_700_000_000_000, LocationPermissionPrecision.APPROXIMATE), 1750)
    private fun names(location: CaptureLocationSnapshot?) = CaptureNameSnapshot(CaptureNamingSettings(), ProductionSlateSettings(), 1000, location)
    private fun still(index: Int = 0) = CapturedStill(index + 1L, 1000L + index, 90, 90,
        PhotoFlashReport(PhotoFlashSelection(), 1, 0, null, 2, 0, null, 1000L + index),
        listOf(StillImagePayload(StillImageKind.JPEG, byteArrayOf(10, 20, index.toByte()), 640, 480)))
    private fun publish(kind: Int, store: Store, names: CaptureNameSnapshot?): String = when (kind) {
        0 -> publishStillCapture(still(), store, id, captureNames = names).metadataUri
        1 -> publishBurstCapture(CapturedBurst(100, 3, (0..2).map { BurstFrame(it, still(it), 10_000L, 100) }, 90, PhotoAspectSelection()), store, id, captureNames = names).metadataUri
        2 -> publishBracketCapture(CapturedBracket(100, BracketSelection(3, BracketStep.TWO_EV), (0..2).map {
            BracketFrame(it, (it - 1) * 2.0, it - 1, it - 1, 10_000L, 100, still(it))
        }), store, id, captureNames = names).metadataUri
        else -> publishAccumulationCapture(CapturedAccumulation(100, AccumulationSelection(),
            (0..2).map { AccumulationFrame(it, it + 1L, 1000L + it, 10_000L, 100) },
            StillImagePayload(StillImageKind.JPEG, byteArrayOf(10, 20, 0), 640, 480), 0, 90, false), store, id, captureNames = names).metadataUri
    }

    @Test fun allPhotoPublicationFamiliesUseTheAdmittedFixDespiteLaterLocationChanges() {
        for (kind in 0..3) {
            var current = frozen
            val admitted = names(current)
            val store = Store { current = CaptureLocationSnapshot(CaptureLocationStatus.NO_PERMISSION) }
            val relation = publish(kind, store, admitted)
            assertEquals(CaptureLocationStatus.NO_PERMISSION, current.status)
            val document = Json.parseToJsonElement(store.rows.getValue(relation).bytes.decodeToString()).jsonObject
            assertEquals(captureLocationJson(frozen), document.getValue("captureLocation"))
            assertEquals(frozen, admitted.captureLocation)
            store.rows.values.filterNot { it.metadata }.forEach { row ->
                assertEquals(3, row.bytes.size); assertEquals(10, row.bytes[0].toInt()); assertEquals(20, row.bytes[1].toInt())
            }
            assertTrue(store.rows.values.all { it.verified && it.published })
        }
    }

    @Test fun nullLocationPreservesAllPreviousRelationshipBytesAndMediaBytes() {
        for (kind in 0..3) {
            val before = Store(); val after = Store()
            val original = publish(kind, before, null)
            val disabled = publish(kind, after, names(null))
            assertArrayEquals(before.rows.getValue(original).bytes, after.rows.getValue(disabled).bytes)
            assertFalse(Json.parseToJsonElement(after.rows.getValue(disabled).bytes.decodeToString()).jsonObject.containsKey("captureLocation"))
            assertEquals(before.rows.keys, after.rows.keys)
            before.rows.forEach { (uri, row) -> assertArrayEquals(row.bytes, after.rows.getValue(uri).bytes) }
        }
    }

    @Test fun unavailableStatusesPublishOnlySchemaAndStatusAcrossEveryPhotoFamily() {
        for (status in CaptureLocationStatus.entries.filter { it != CaptureLocationStatus.AVAILABLE }) for (kind in 0..3) {
            val store = Store()
            val uri = publish(kind, store, names(CaptureLocationSnapshot(status)))
            val location = Json.parseToJsonElement(store.rows.getValue(uri).bytes.decodeToString()).jsonObject.getValue("captureLocation").jsonObject
            assertEquals(setOf("schema", "status"), location.keys)
            assertEquals(status.name, location.getValue("status").jsonPrimitive.content)
        }
    }

    @Test fun legacyLocationCreatesHonestRelationshipWithoutSlateOrChangingEncodedBytes() {
        val bytes = byteArrayOf(1, 3, 5, 7)
        for (location in listOf(frozen, CaptureLocationSnapshot(CaptureLocationStatus.UNAVAILABLE))) {
            val store = Store()
            val uri = publishLegacyStill(bytes, "jpg", "image/jpeg", store, id, captureNames = names(location))
            assertArrayEquals(bytes, store.rows.getValue(uri).bytes)
            val relation = store.rows.values.single { it.metadata }
            val doc = Json.parseToJsonElement(relation.bytes.decodeToString()).jsonObject
            assertEquals(captureLocationJson(location), doc["captureLocation"])
            assertFalse(doc.containsKey("productionSlate"))
            assertEquals("LEGACY_ENCODED_BYTES_ONLY", doc.getValue("captureEvidence").jsonPrimitive.content)
        }
        val disabled = Store()
        publishLegacyStill(bytes, "jpg", "image/jpeg", disabled, id, captureNames = names(null))
        assertEquals(1, disabled.rows.size)
        assertFalse(disabled.rows.values.single().metadata)
    }

    @Test fun primaryVideoSerializationUsesFrozenLocationAndPreservesTechnicalEvidence() {
        val previous = captureLocationJson(CaptureLocationSnapshot(CaptureLocationStatus.STALE))
        val technical = buildJsonObject { put("sensorTimestampNs", 9_007_199_254_740_993L) }
        val input = buildJsonObject { put("schema", "opencinecam.recording.v1"); put("captureLocation", previous); put("technical", technical) }.toString()
        val admitted = names(frozen)
        val changed = admitted.copy(captureLocation = CaptureLocationSnapshot(CaptureLocationStatus.NO_PERMISSION))
        val output = Json.parseToJsonElement(requireNotNull(recordingPublicationMetadata(input, "content://media/external_primary/video/media/12", id, admitted.slate, admitted.captureLocation))).jsonObject
        assertEquals(captureLocationJson(frozen), output["captureLocation"])
        assertNotEquals(captureLocationJson(requireNotNull(changed.captureLocation)), output["captureLocation"])
        assertEquals(technical, output["technical"])
        assertEquals(productionSlateJson(admitted.slate), output["productionSlate"])
        assertEquals(id, output.getValue("bundleId").jsonPrimitive.content)
        assertEquals("content://media/external_primary/video/media/12", output.getValue("videoUri").jsonPrimitive.content)
        assertEquals(previous, Json.parseToJsonElement(input).jsonObject["captureLocation"])
    }

    @Test fun videoLocationOnlyAndUnavailableSnapshotsCreateMetadataButDisabledKeepsExactDefault() {
        for (location in listOf(frozen) + CaptureLocationStatus.entries.filter { it != CaptureLocationStatus.AVAILABLE }.map { CaptureLocationSnapshot(it) }) {
            val doc = Json.parseToJsonElement(requireNotNull(recordingPublicationMetadata(null, "video-uri", id, null, location))).jsonObject
            assertEquals("opencinecam.recording.v1", doc.getValue("schema").jsonPrimitive.content)
            assertEquals(captureLocationJson(location), doc["captureLocation"])
            assertFalse(doc.containsKey("productionSlate"))
        }
        val old = " { \"schema\": \"opencinecam.recording.v1\", \"technical\": 1 } "
        assertEquals(old, recordingPublicationMetadata(old, "video-uri", id, null, null))
        assertNull(recordingPublicationMetadata(null, "video-uri", id, null, null))
        for (malformed in listOf("[]", "broken", "null")) assertThrows(IllegalArgumentException::class.java) {
            recordingPublicationMetadata(malformed, "video-uri", id, null, frozen)
        }
    }

    private class Row(val name: String, val mime: String, val metadata: Boolean) {
        var bytes = byteArrayOf(); var verified = false; var published = false
    }
    private class Store(private val onFirstWrite: () -> Unit = {}) : StillPublicationStore {
        val rows = linkedMapOf<String, Row>()
        private var first = true
        override fun insert(displayName: String, mimeType: String, metadata: Boolean): String = "row:${rows.size + 1}".also { rows[it] = Row(displayName, mimeType, metadata) }
        override fun writeClosed(uri: String, bytes: ByteArray) {
            if (first) { first = false; onFirstWrite() }
            rows.getValue(uri).bytes = bytes.copyOf()
        }
        override fun verify(uri: String, displayName: String, mimeType: String, size: Long, sha256: String) {
            val row = rows.getValue(uri)
            assertEquals(row.name, displayName); assertEquals(row.mime, mimeType)
            assertEquals(size, row.bytes.size.toLong()); assertEquals(sha256, stillSha256(row.bytes))
            row.verified = true
        }
        override fun publish(uri: String): Int { assertTrue(rows.values.all { it.verified }); rows.getValue(uri).published = true; return 1 }
        override fun delete(uri: String) { rows.remove(uri) }
    }
}
