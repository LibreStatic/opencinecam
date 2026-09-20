/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import com.librestatic.opencinecam.CaptureNameSnapshot
import com.librestatic.opencinecam.CaptureLocationSnapshot
import com.librestatic.opencinecam.captureLocationJson
import com.librestatic.opencinecam.captureFileStem
import com.librestatic.opencinecam.ProductionSlateSettings
import com.librestatic.opencinecam.productionSlateJson

import com.librestatic.opencinecam.camera.CapturedStill
import com.librestatic.opencinecam.camera.PhotoAspectDisposition
import com.librestatic.opencinecam.camera.PhotoAspectReport
import com.librestatic.opencinecam.camera.StillImageKind
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A completed in-process publication, not a promise of cross-row crash atomicity. */
data class StillPublishedImage(val kind: StillImageKind, val uri: String, val displayName: String,
    val sha256: String, val bytes: Long)
data class StillPublication(val id: String, val sensorTimestampNs: Long,
    val images: List<StillPublishedImage>, val metadataUri: String)

/** writeClosed must close its writer before returning; verify must read back actual stored bytes. */
internal interface StillPublicationStore {
    fun insert(displayName: String, mimeType: String, metadata: Boolean): String
    fun writeClosed(uri: String, bytes: ByteArray)
    fun verify(uri: String, displayName: String, mimeType: String, size: Long, sha256: String)
    fun publish(uri: String): Int
    fun delete(uri: String)
}

/** Prepare every image and relationship byte before publishing any row. On any failure, attempt
 * all owned deletions, including already published rows. Provider/process crashes are not atomic. */
internal fun publishStillCapture(capture: CapturedStill, store: StillPublicationStore,
    bundleId: String = UUID.randomUUID().toString(), productionSlate: ProductionSlateSettings? = null,
    captureNames: CaptureNameSnapshot? = null): StillPublication {
    require(captureNames == null || productionSlate == null || captureNames.slate == productionSlate) { "Capture name slate differs from publication slate" }
    val stem = captureFileStem(bundleId, captureNames)
    require(capture.captureId > 0 && capture.sensorTimestampNs > 0)
    require(capture.orientationDegrees in setOf(0, 90, 180, 270) && capture.quality in 1..100)
    require(capture.flashReport.sensorTimestampNs == capture.sensorTimestampNs)
    val payloads = capture.images.toList()
    val kinds = payloads.map { it.kind }.toSet()
    require(payloads.size in 1..2 && kinds.size == payloads.size)
    require(payloads.size == 1 || kinds == setOf(StillImageKind.JPEG, StillImageKind.DNG))
    val originals = payloads.map { payload ->
        require(payload.width > 0 && payload.height > 0)
        payload.bytes.also { require(it.isNotEmpty()) }
    }
    val owned = OwnedOutputRows<String>(store::delete)
    val identities = linkedSetOf<String>()
    fun insert(name: String, mime: String, metadata: Boolean): String {
        val uri = store.insert(name, mime, metadata)
        owned.add(uri)
        check(uri.isNotBlank() && identities.add(uri)) { "Provider did not allocate a unique still row" }
        return uri
    }
    try {
        val pending = payloads.mapIndexed { index, payload ->
            val extension = when (payload.kind) { StillImageKind.JPEG -> "jpg"; StillImageKind.HEIC -> "heic"; StillImageKind.DNG -> "dng" }
            val mime = stillMimeType(payload.kind)
            val name = "$stem.$extension"
            val uri = insert(name, mime, false)
            StillPublishedImage(payload.kind, uri, name, stillSha256(originals[index]), originals[index].size.toLong())
        }
        pending.forEachIndexed { index, image ->
            store.writeClosed(image.uri, originals[index])
            store.verify(image.uri, image.displayName, stillMimeType(image.kind), image.bytes, image.sha256)
        }
        val relation = stillRelationshipJson(bundleId, capture, pending, productionSlate, captureNames?.captureLocation).toByteArray(Charsets.UTF_8)
        val relationName = "$stem.still.json"
        val relationUri = insert(relationName, "application/json", true)
        store.writeClosed(relationUri, relation)
        store.verify(relationUri, relationName, "application/json", relation.size.toLong(), stillSha256(relation))
        // Metadata is last, but earlier images may be briefly visible if a later update fails.
        identities.forEach { check(store.publish(it) == 1) { "Provider did not publish exactly one still row" } }
        return StillPublication(bundleId, capture.sensorTimestampNs, pending.toList(), relationUri)
    } catch (failure: Throwable) {
        try { owned.deleteAll() } catch (cleanup: Throwable) {
            if (cleanup !== failure) failure.addSuppressed(cleanup)
        }
        throw failure
    }
}

internal fun stillMimeType(kind: StillImageKind): String = when (kind) {
    StillImageKind.JPEG -> "image/jpeg"
    StillImageKind.HEIC -> "image/heic"
    StillImageKind.DNG -> "image/x-adobe-dng"
}
internal fun stillSha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

internal fun stillRelationshipJson(bundleId: String, capture: CapturedStill, images: List<StillPublishedImage>, productionSlate: ProductionSlateSettings? = null, captureLocation: CaptureLocationSnapshot? = null): String = buildJsonObject {
    put("schemaVersion", 1)
    productionSlate?.let { put("productionSlate", productionSlateJson(it)) }
    captureLocation?.let { put("captureLocation", captureLocationJson(it)) }
    put("bundleId", bundleId)
    put("captureId", capture.captureId)
    put("sensorTimestampNs", capture.sensorTimestampNs)
    put("orientationDegrees", capture.orientationDegrees)
    put("quality", capture.quality)
    put("publicationSemantics", "BEST_EFFORT_COMPENSATED_NOT_CRASH_ATOMIC")
    val report = capture.flashReport
    put("flash", buildJsonObject {
        put("requestedMode", report.requested.mode.name)
        put("requestedStrength", report.requested.strength?.let(::JsonPrimitive) ?: JsonNull)
        put("submittedAeMode", report.submittedAeMode?.let(::JsonPrimitive) ?: JsonNull)
        put("submittedFlashMode", report.submittedFlashMode)
        put("submittedStrength", report.requestedStrength?.let(::JsonPrimitive) ?: JsonNull)
        put("reportedAeState", report.reportedAeState?.let(::JsonPrimitive) ?: JsonNull)
        put("reportedFlashState", report.reportedFlashState?.let(::JsonPrimitive) ?: JsonNull)
        put("reportedStrength", report.reportedStrength?.let(::JsonPrimitive) ?: JsonNull)
        put("sensorTimestampNs", report.sensorTimestampNs?.let(::JsonPrimitive) ?: JsonNull)
    })
    put("images", buildJsonArray {
        images.forEach { image ->
            val payload = capture.images.single { it.kind == image.kind }
            add(buildJsonObject {
                put("kind", image.kind.name); put("uri", image.uri); put("displayName", image.displayName)
                put("mimeType", stillMimeType(image.kind)); put("bytes", image.bytes); put("sha256", image.sha256)
                put("width", payload.width); put("height", payload.height)
                put("sensorTimestampNs", capture.sensorTimestampNs)
                put("orientationDegrees", payload.aspectReport?.outputOrientationDegrees ?: capture.orientationDegrees)
                put("aspect", stillAspectJson(payload.aspectReport))
            })
        }
    })
}.toString()

/** Geometry describes the actual encoded image. RAW preserves its native full frame and can
 * retain a different orientation from a physically oriented/cropped JPEG in the same capture. */
internal fun stillAspectJson(report: PhotoAspectReport?): JsonElement = report?.let {
    buildJsonObject {
        put("requested", buildJsonObject {
            put("enabled", it.requested.enabled)
            put("width", it.requested.width)
            put("height", it.requested.height)
        })
        put("disposition", it.disposition.name)
        put("coordinateSpace", if (it.disposition == PhotoAspectDisposition.RAW_UNCHANGED)
            "NATIVE_IMAGE_PIXELS" else "ORIENTED_IMAGE_PIXELS")
        put("sourceWidth", it.sourceWidth)
        put("sourceHeight", it.sourceHeight)
        put("crop", buildJsonObject {
            put("left", it.crop.left)
            put("top", it.crop.top)
            put("width", it.crop.width)
            put("height", it.crop.height)
        })
        put("resultWidth", it.resultWidth)
        put("resultHeight", it.resultHeight)
        put("outputOrientationDegrees", it.outputOrientationDegrees)
    }
} ?: JsonNull

/** Legacy byte callbacks have no trustworthy capture timestamp, orientation or exposure result.
 * Preserve their single-row default; an explicitly supplied slate adds only an honest relationship
 * in the caller's existing recovery namespace. Encoded image bytes and the UUID namespace remain unchanged. */
internal fun publishLegacyStill(bytes: ByteArray, extension: String, mimeType: String,
    store: StillPublicationStore, bundleId: String, productionSlate: ProductionSlateSettings? = null,
    captureNames: CaptureNameSnapshot? = null): String {
    require(bytes.isNotEmpty())
    require(captureNames == null || productionSlate == null || captureNames.slate == productionSlate) { "Capture name slate differs from publication slate" }
    val stem = captureFileStem(bundleId, captureNames)
    require(extension == "jpg" && mimeType == "image/jpeg" || extension == "dng" && mimeType == "image/x-adobe-dng")
    val owned = OwnedOutputRows<String>(store::delete)
    val identities = linkedSetOf<String>()
    fun insert(name: String, mime: String, metadata: Boolean): String {
        val uri = store.insert(name, mime, metadata)
        owned.add(uri)
        check(uri.isNotBlank() && identities.add(uri)) { "Provider did not allocate a unique legacy still row" }
        return uri
    }
    try {
        val name = "$stem.$extension"
        val uri = insert(name, mimeType, false)
        val hash = stillSha256(bytes)
        store.writeClosed(uri, bytes)
        store.verify(uri, name, mimeType, bytes.size.toLong(), hash)
        if (productionSlate != null || captureNames?.captureLocation != null) {
            val relation = buildJsonObject {
                put("schemaVersion", 1)
                put("bundleId", bundleId)
                put("captureEvidence", "LEGACY_ENCODED_BYTES_ONLY")
                put("publicationSemantics", "BEST_EFFORT_COMPENSATED_NOT_CRASH_ATOMIC")
                productionSlate?.let { put("productionSlate", productionSlateJson(it)) }
                captureNames?.captureLocation?.let { put("captureLocation", captureLocationJson(it)) }
                put("images", buildJsonArray {
                    add(buildJsonObject {
                        put("kind", if (extension == "jpg") "JPEG" else "DNG")
                        put("uri", uri); put("displayName", name); put("mimeType", mimeType)
                        put("bytes", bytes.size.toLong()); put("sha256", hash)
                    })
                })
            }.toString().toByteArray(Charsets.UTF_8)
            val relationName = "$stem.still.json"
            val relationUri = insert(relationName, "application/json", true)
            store.writeClosed(relationUri, relation)
            store.verify(relationUri, relationName, "application/json", relation.size.toLong(), stillSha256(relation))
        }
        identities.forEach { check(store.publish(it) == 1) { "MediaStore did not publish exactly one legacy still artifact" } }
        return uri
    } catch (failure: Throwable) {
        try { owned.deleteAll() } catch (cleanup: Throwable) {
            if (cleanup !== failure) failure.addSuppressed(cleanup)
        }
        throw failure
    }
}
