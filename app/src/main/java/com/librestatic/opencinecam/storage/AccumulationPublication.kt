/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import com.librestatic.opencinecam.CaptureNameSnapshot
import com.librestatic.opencinecam.CaptureLocationSnapshot
import com.librestatic.opencinecam.captureLocationJson
import com.librestatic.opencinecam.captureFileStem
import com.librestatic.opencinecam.ProductionSlateSettings
import com.librestatic.opencinecam.productionSlateJson

import com.librestatic.opencinecam.camera.AccumulationMode
import com.librestatic.opencinecam.camera.CapturedAccumulation
import com.librestatic.opencinecam.camera.StillImageKind
import java.util.UUID
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Completed computational image and its provenance, not a continuous physical exposure. */
data class AccumulationPublication(val id: String, val image: StillPublishedImage, val metadataUri: String)

/** Both rows are closed and verified before publication. Compensation attempts every owned row,
 * including one already published. Provider/process crashes do not have cross-row atomicity. */
internal fun publishAccumulationCapture(capture: CapturedAccumulation, store: StillPublicationStore,
    bundleId: String = UUID.randomUUID().toString(), productionSlate: ProductionSlateSettings? = null,
    captureNames: CaptureNameSnapshot? = null): AccumulationPublication {
    require(captureNames == null || productionSlate == null || captureNames.slate == productionSlate) { "Capture name slate differs from publication slate" }
    val stem = captureFileStem(bundleId, captureNames)
    require(capture.id > 0 && capture.frames.size in 2..CapturedAccumulation.MAX_FRAMES)
    require(capture.frames.map { it.index } == capture.frames.indices.toList())
    require(capture.frames.all { it.sensorTimestampNs > 0 })
    require(capture.frames.zipWithNext().all { (a, b) -> a.sensorTimestampNs < b.sensorTimestampNs })
    require(capture.image.kind == StillImageKind.JPEG)
    require(capture.orientationDegrees == 0 && capture.quality in 1..100)
    require(capture.image.byteCount <= CapturedAccumulation.MAX_ENCODED_BYTES)
    val bytes = capture.image.bytes
    require(bytes.isNotEmpty())
    val owned = OwnedOutputRows<String>(store::delete)
    val identities = linkedSetOf<String>()
    fun insert(name: String, mime: String, metadata: Boolean): String {
        val uri = store.insert(name, mime, metadata)
        owned.add(uri)
        check(uri.isNotBlank() && identities.add(uri)) { "Provider did not allocate a unique accumulation row" }
        return uri
    }
    try {
        val name = "$stem.jpg"
        val uri = insert(name, "image/jpeg", false)
        val image = StillPublishedImage(StillImageKind.JPEG, uri, name, stillSha256(bytes), bytes.size.toLong())
        store.writeClosed(uri, bytes)
        store.verify(uri, name, "image/jpeg", image.bytes, image.sha256)
        val relation = accumulationRelationshipJson(bundleId, capture, image, productionSlate, captureNames?.captureLocation).toByteArray(Charsets.UTF_8)
        val relationName = "$stem.accumulation.json"
        val relationUri = insert(relationName, "application/json", true)
        store.writeClosed(relationUri, relation)
        store.verify(relationUri, relationName, "application/json", relation.size.toLong(), stillSha256(relation))
        identities.forEach { check(store.publish(it) == 1) { "Provider did not publish exactly one accumulation row" } }
        return AccumulationPublication(bundleId, image, relationUri)
    } catch (failure: Throwable) {
        try { owned.deleteAll() } catch (cleanup: Throwable) {
            if (cleanup !== failure) failure.addSuppressed(cleanup)
        }
        throw failure
    }
}

internal fun accumulationRelationshipJson(bundleId: String, capture: CapturedAccumulation,
    image: StillPublishedImage, productionSlate: ProductionSlateSettings? = null, captureLocation: CaptureLocationSnapshot? = null): String = buildJsonObject {
    put("schemaVersion", 1)
    productionSlate?.let { put("productionSlate", productionSlateJson(it)) }
    captureLocation?.let { put("captureLocation", captureLocationJson(it)) }
    put("bundleId", bundleId)
    put("accumulationId", capture.id)
    put("mode", capture.selection.mode.name)
    put("algorithm", when (capture.selection.mode) {
        AccumulationMode.LIGHT -> "LINEAR_SRGB_CHANNEL_MAX"
        AccumulationMode.WATER -> "LINEAR_SRGB_TEMPORAL_MEAN"
        AccumulationMode.STARS -> "LINEAR_SRGB_MAX_LUMINANCE_WHOLE_RGB"
        AccumulationMode.BULB -> "LINEAR_SRGB_ADDITIVE_CLIPPED_SIMULATION"
    })
    put("result", "COMPLETE")
    put("output", "COMPUTATIONAL_ACCUMULATION_NOT_CONTINUOUS_EXPOSURE")
    put("publicationSemantics", "BEST_EFFORT_COMPENSATED_NOT_CRASH_ATOMIC")
    put("completedByUser", capture.completedByUser)
    put("frameCount", capture.frames.size)
    // The observed timestamp span is not shutter-open time or guaranteed requested duration.
    put("timestampSpanNs", capture.frames.last().sensorTimestampNs - capture.frames.first().sensorTimestampNs)
    put("selection", buildJsonObject {
        put("mode", capture.selection.mode.name)
        put("durationMs", capture.selection.durationMs)
        put("intervalMs", capture.selection.intervalMs)
        put("maxEdge", capture.selection.maxEdge)
        put("starsThreshold", capture.selection.starsThreshold)
    })
    put("frames", buildJsonArray {
        capture.frames.forEach { frame ->
            add(buildJsonObject {
                put("index", frame.index)
                put("captureId", frame.captureId)
                put("sensorTimestampNs", frame.sensorTimestampNs)
                put("exposureTimeNs", frame.exposureTimeNs?.let(::JsonPrimitive) ?: JsonNull)
                put("sensitivityIso", frame.sensitivityIso?.let(::JsonPrimitive) ?: JsonNull)
            })
        }
    })
    put("image", buildJsonObject {
        put("kind", image.kind.name)
        put("uri", image.uri)
        put("displayName", image.displayName)
        put("mimeType", "image/jpeg")
        put("bytes", image.bytes)
        put("sha256", image.sha256)
        put("width", capture.image.width)
        put("height", capture.image.height)
        put("orientationDegrees", capture.image.aspectReport?.outputOrientationDegrees ?: capture.orientationDegrees)
        put("aspect", stillAspectJson(capture.image.aspectReport))
        put("quality", capture.quality)
    })
}.toString()
