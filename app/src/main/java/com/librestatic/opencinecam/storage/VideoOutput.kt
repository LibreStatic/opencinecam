/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import com.librestatic.opencinecam.CaptureNameSnapshot
import com.librestatic.opencinecam.captureFileStem
import com.librestatic.opencinecam.ProductionSlateSettings
import com.librestatic.opencinecam.productionSlateJson
import kotlinx.serialization.json.*
import org.json.JSONObject
import com.librestatic.opencinecam.CaptureLocationSnapshot
import com.librestatic.opencinecam.captureLocationJson
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.librestatic.opencinecam.camera.RecordingGeometry

class VideoOutput private constructor(
    private val context: Context,
    val uri: Uri,
    val descriptor: ParcelFileDescriptor,
    val displayName: String,
    val recoveryGroup: RecordingRecoveryGroup,
    val productionSlate: ProductionSlateSettings?,
    val captureLocation: CaptureLocationSnapshot?,
    private val recoveryMember: RecordingRecoveryMember,
) : AutoCloseable {
    private var sidecarUri: Uri? = null
    private var preparedOutput: PreparedVideoOutput? = null
    private var descriptorRetired = false
    private val transaction = PendingOutputTransaction(uri, {
        descriptor.close()
        descriptorRetired = true
    }, ::deleteOwned)

    /** Freeze all owned identities after closing/writing; every row remains pending. */
    @Synchronized
    fun prepareCompletion(
        sidecarJson: String? = null,
        expectedGeometry: RecordingGeometry? = null,
        timingSidecar: Boolean = false,
    ): PreparedVideoOutput? = transaction.prepare {
        prepareOwned(sidecarJson, expectedGeometry, timingSidecar)
    }?.let { requireNotNull(preparedOutput) }

    /** Publication never creates another row or writes new metadata. */
    @Synchronized
    fun publishPrepared(): Uri? = transaction.publish(::publishOwned)

    /** The legacy first-completion contract remains atomic across preparation/publication. */
    @Synchronized
    fun finish(success: Boolean, sidecarJson: String? = null, expectedGeometry: RecordingGeometry? = null, timingSidecar: Boolean = false): Uri? =
        transaction.finish(success, prepare = {
            prepareOwned(sidecarJson, expectedGeometry, timingSidecar)
        }, publish = ::publishOwned)

    private fun prepareOwned(sidecarJson: String?, expectedGeometry: RecordingGeometry?, timingSidecar: Boolean) {
        expectedGeometry?.let { expected ->
            val validation = VideoFileGeometryValidator(context).validate(uri, expected)
            check(validation.valid) { validation.message }
        }
        val videoName = ownedDisplayName(uri)
        val metadata = if (captureLocation != null) recordingPublicationMetadata(
            sidecarJson, uri.toString(), recoveryGroup.id, productionSlate, captureLocation,
        ) else productionSlate?.let { slate ->
            // Preserve the pre-geotag serialization and null/default behavior byte-for-byte.
            (sidecarJson?.let(::JSONObject) ?: JSONObject().put("schema", "opencinecam.recording.v1"))
                .put("productionSlate", JSONObject(productionSlateJson(slate).toString()))
                .put("videoUri", uri.toString()).put("bundleId", recoveryGroup.id).toString(2)
        } ?: sidecarJson
        metadata?.let { writePendingSidecar(it, timingSidecar || sidecarJson == null, videoName) }
        val artifacts = mutableListOf(PreparedCaptureArtifact(CaptureArtifactRole.VIDEO, uri.toString(), videoName))
        sidecarUri?.let { sidecar ->
            artifacts += PreparedCaptureArtifact(CaptureArtifactRole.VIDEO_METADATA, sidecar.toString(), ownedDisplayName(sidecar))
        }
        check(descriptorRetired)
        recoveryMember.prepared(artifacts)
        preparedOutput = PreparedVideoOutput(uri, java.util.Collections.unmodifiableList(artifacts.toList()))
    }

    private fun ownedDisplayName(owned: Uri): String = requireNotNull(context.contentResolver.query(
        owned, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null,
    )) { "Owned output row is unavailable" }.use { cursor ->
        check(cursor.moveToFirst()) { "Owned output row disappeared before preparation" }
        requireNotNull(cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME))) {
            "Owned output row has no display name"
        }.also { check(it.isNotBlank()) { "Owned output row has an empty display name" } }
    }

    private fun publishOwned() {
        check(preparedOutput != null) { "Output identities must be prepared before publication" }
        val resolver = context.contentResolver
        sidecarUri?.let { sidecar ->
            check(resolver.update(sidecar, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) == 1) {
                "Sidecar publication did not update its owned row"
            }
        }
        check(resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null) == 1) {
            "Video publication did not update its owned row"
        }
        recoveryMember.published()
    }

    override fun close() { finish(false) }

    private fun deleteOwned() {
        check(descriptorRetired) { "Video descriptor retirement is incomplete" }
        // The group waits for the separate native engine guard and any audio worker.
        recoveryMember.abort()
    }

    private fun writePendingSidecar(json: String, timing: Boolean, videoName: String) {
        val sidecarName = videoName.removeSuffix(".mp4") + if (timing) ".timing.json" else ".oclog.json"
        val resolver = context.contentResolver
        val sidecar = recoveryMember.insert(sidecarName, "application/json", metadata = true)
        sidecarUri = sidecar
        requireNotNull(resolver.openOutputStream(sidecar, "w")).use { it.write(json.toByteArray()) }
    }

    companion object {
        fun create(context: Context, productionSlate: ProductionSlateSettings? = null,
            captureNames: CaptureNameSnapshot? = null): VideoOutput {
            require(captureNames == null || productionSlate == null || captureNames.slate == productionSlate)
            val group = RecordingCaptureRecovery.begin(context)
            val member = group.member(RecordingMemberKind.VIDEO)
            val name = captureFileStem(group.id, captureNames) + ".mp4"
            var descriptor: ParcelFileDescriptor? = null
            return try {
                val uri = member.insert(name, "video/mp4", metadata = false)
                val pfd = requireNotNull(context.contentResolver.openFileDescriptor(uri, "rw")).also { descriptor = it }
                VideoOutput(context.applicationContext, uri, pfd, name, group, productionSlate, captureNames?.captureLocation, member)
            } catch (failure: Throwable) {
                var retired = true
                try { descriptor?.close() } catch (cleanup: Throwable) {
                    retired = false
                    if (cleanup !== failure) failure.addSuppressed(cleanup)
                }
                if (retired) try { member.abort() } catch (cleanup: Throwable) {
                    if (cleanup !== failure) failure.addSuppressed(cleanup)
                }
                throw failure
            }
        }
    }
}

/** Serialize only the frozen admitted snapshot; default-null callers retain their exact sidecar.
 * The primary video metadata owns take location. Related audio metadata does not invent a new fix. */
internal fun recordingPublicationMetadata(sidecarJson: String?, videoUri: String, bundleId: String,
    productionSlate: ProductionSlateSettings?, captureLocation: CaptureLocationSnapshot?): String? {
    if (productionSlate == null && captureLocation == null) return sidecarJson
    val original = sidecarJson?.let { requireNotNull(Json.parseToJsonElement(it) as? JsonObject) }
    return buildJsonObject {
        original?.forEach { (key, value) -> put(key, value) } ?: put("schema", "opencinecam.recording.v1")
        productionSlate?.let { put("productionSlate", productionSlateJson(it)) }
        captureLocation?.let { put("captureLocation", captureLocationJson(it)) }
        put("videoUri", videoUri); put("bundleId", bundleId)
    }.toString()
}
