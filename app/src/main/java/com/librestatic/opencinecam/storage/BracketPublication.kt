/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import com.librestatic.opencinecam.CaptureNameSnapshot
import com.librestatic.opencinecam.CaptureLocationSnapshot
import com.librestatic.opencinecam.captureLocationJson
import com.librestatic.opencinecam.captureFileStem
import com.librestatic.opencinecam.ProductionSlateSettings
import com.librestatic.opencinecam.productionSlateJson

import com.librestatic.opencinecam.camera.CapturedBracket
import com.librestatic.opencinecam.camera.StillImageKind
import java.util.Collections
import java.util.UUID
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Ordered separate exposures and their relation; this is not a merged HDR image. */
class BracketPublication(val id: String, images: List<StillPublishedImage>, val metadataUri: String) {
    val images: List<StillPublishedImage> = Collections.unmodifiableList(images.toList())
}

/** All rows remain pending until every image and the relationship have been closed and verified.
 * Compensation includes partially published rows; process/provider crashes are not cross-row atomic. */
internal fun publishBracketCapture(capture: CapturedBracket, store: StillPublicationStore,
    bundleId: String = UUID.randomUUID().toString(), productionSlate: ProductionSlateSettings? = null,
    captureNames: CaptureNameSnapshot? = null): BracketPublication {
    require(captureNames == null || productionSlate == null || captureNames.slate == productionSlate) { "Capture name slate differs from publication slate" }
    val stem = captureFileStem(bundleId, captureNames)
    val frames = capture.frames.toList()
    require(frames.size == capture.selection.count && frames.map { it.index } == frames.indices.toList())
    var encodedBytes = 0L
    val originals = frames.map { frame ->
        val payload = frame.capture.images.single()
        require(payload.kind == StillImageKind.JPEG)
        payload.bytes.also { bytes ->
            require(bytes.isNotEmpty())
            encodedBytes = Math.addExact(encodedBytes, bytes.size.toLong())
            require(encodedBytes <= CapturedBracket.MAX_ENCODED_BYTES)
        }
    }
    val owned = OwnedOutputRows<String>(store::delete)
    val identities = linkedSetOf<String>()
    fun insert(name: String, mime: String, metadata: Boolean): String {
        val uri = store.insert(name, mime, metadata)
        owned.add(uri)
        check(uri.isNotBlank() && identities.add(uri)) { "Provider did not allocate a unique bracket row" }
        return uri
    }
    try {
        val images = frames.mapIndexed { index, _ ->
            val name = "${stem}_${(index + 1).toString().padStart(2, '0')}.jpg"
            val uri = insert(name, "image/jpeg", false)
            StillPublishedImage(StillImageKind.JPEG, uri, name, stillSha256(originals[index]), originals[index].size.toLong())
        }
        images.forEachIndexed { index, image ->
            store.writeClosed(image.uri, originals[index])
            store.verify(image.uri, image.displayName, "image/jpeg", image.bytes, image.sha256)
        }
        val relation = bracketRelationshipJson(bundleId, capture, images, productionSlate, captureNames?.captureLocation).toByteArray(Charsets.UTF_8)
        val relationName = "$stem.bracket.json"
        val relationUri = insert(relationName, "application/json", true)
        store.writeClosed(relationUri, relation)
        store.verify(relationUri, relationName, "application/json", relation.size.toLong(), stillSha256(relation))
        identities.forEach { check(store.publish(it) == 1) { "Provider did not publish exactly one bracket row" } }
        return BracketPublication(bundleId, images, relationUri)
    } catch (failure: Throwable) {
        try { owned.deleteAll() } catch (cleanup: Throwable) {
            if (cleanup !== failure) failure.addSuppressed(cleanup)
        }
        throw failure
    }
}

internal fun bracketRelationshipJson(bundleId: String, capture: CapturedBracket,
    images: List<StillPublishedImage>, productionSlate: ProductionSlateSettings? = null, captureLocation: CaptureLocationSnapshot? = null): String = buildJsonObject {
    require(images.size == capture.frames.size)
    put("schemaVersion", 1)
    productionSlate?.let { put("productionSlate", productionSlateJson(it)) }
    captureLocation?.let { put("captureLocation", captureLocationJson(it)) }
    put("bundleId", bundleId)
    put("bracketId", capture.id)
    put("result", "COMPLETE")
    put("output", "SEPARATE_EXPOSURES_NOT_HDR")
    put("publicationSemantics", "BEST_EFFORT_COMPENSATED_NOT_CRASH_ATOMIC")
    put("selection", buildJsonObject {
        put("count", capture.selection.count)
        put("step", capture.selection.step.name)
        put("stepNumerator", capture.selection.step.numerator)
        put("stepDenominator", capture.selection.step.denominator)
    })
    put("frames", buildJsonArray {
        capture.frames.forEachIndexed { index, frame ->
            val image = images[index]
            val still = frame.capture
            val payload = still.images.single()
            add(buildJsonObject {
                put("index", frame.index)
                put("requestedEv", frame.requestedEv)
                put("submittedCompensation", frame.submittedCompensation)
                put("reportedCompensation", frame.reportedCompensation?.let(::JsonPrimitive) ?: JsonNull)
                put("exposureTimeNs", frame.exposureTimeNs?.let(::JsonPrimitive) ?: JsonNull)
                put("sensitivityIso", frame.sensitivityIso?.let(::JsonPrimitive) ?: JsonNull)
                put("captureId", still.captureId)
                put("sensorTimestampNs", still.sensorTimestampNs)
                put("kind", image.kind.name)
                put("uri", image.uri)
                put("displayName", image.displayName)
                put("mimeType", "image/jpeg")
                put("bytes", image.bytes)
                put("sha256", image.sha256)
                put("width", payload.width)
                put("height", payload.height)
                put("orientationDegrees", payload.aspectReport?.outputOrientationDegrees ?: still.orientationDegrees)
                put("aspect", stillAspectJson(payload.aspectReport))
                put("quality", still.quality)
            })
        }
    })
}.toString()
