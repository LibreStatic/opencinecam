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
import java.io.File
import com.librestatic.opencinecam.camera.RecordingGeometry
import com.librestatic.opencinecam.media.storage.ClipFinalizationResult
import com.librestatic.opencinecam.media.storage.ClipRecoveryDecision
import com.librestatic.opencinecam.media.storage.ContentResolverClipRows
import com.librestatic.opencinecam.media.storage.decideClipRecovery
import com.librestatic.opencinecam.media.storage.finalizeClip

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
    private var motionRecorder: MotionLogRecorder? = null
    private var motionLog: File? = null
    private var motionLogUri: Uri? = null
    private var preparedOutput: PreparedVideoOutput? = null
    private var descriptorRetired = false
    private var incompleteReasons: List<String>? = null
    private var incompleteMuxerFinalized = false
    private var incompleteBytes = 0L
    private var retainedBytes = false
    private var neverRecorded = false
    private var writtenSidecarJson: String? = null
    private val transaction = PendingOutputTransaction(uri, {
        descriptor.close()
        descriptorRetired = true
    }, ::deleteOwned)

    /** The optional Gyroflow log of this take. Best effort: it never fails or delays the take. */
    @Synchronized
    internal fun attachMotionRecorder(recorder: MotionLogRecorder) { motionRecorder = recorder }

    @Synchronized
    internal fun motionRecorder(): MotionLogRecorder? = motionRecorder

    /** Stops the sensors and closes the local file; [keep] false (or an empty log) drops it. Idempotent. */
    @Synchronized
    internal fun sealMotionLog(keep: Boolean) {
        val recorder = motionRecorder ?: return
        motionRecorder = null
        motionLog = try { recorder.finish(keep) } catch (problem: Exception) {
            android.util.Log.w("VideoOutput", "Gyroflow log could not be closed", problem)
            null
        }
    }

    @Synchronized
    private fun discardMotionLog() {
        sealMotionLog(false)
        motionLog?.delete()
        motionLog = null
    }

    /** Freeze all owned identities after closing/writing; every row remains pending. */
    @Synchronized
    fun prepareCompletion(
        sidecarJson: String? = null,
        expectedGeometry: RecordingGeometry? = null,
        timingSidecar: Boolean = false,
    ): PreparedVideoOutput? = transaction.prepare {
        prepareOwned(sidecarJson, expectedGeometry, timingSidecar)
    }?.let { requireNotNull(preparedOutput) }

    /**
     * ADR-0011 for a take that is not fully successful: decide with [decideClipRecovery] whether the
     * file holds bytes. An empty file is deleted (null). Otherwise it is sealed without geometry
     * validation, its sidecar is forced and carries the incomplete marker, and it is never deleted
     * again by this output; [publishPrepared] then publishes it as incomplete. Callable after a
     * normal [prepareCompletion] too (publication failed): the sidecar is then re-marked.
     */
    @Synchronized
    fun prepareIncomplete(
        reasons: List<String>,
        sidecarJson: String? = null,
        timingSidecar: Boolean = false,
        muxerFinalized: Boolean = false,
        encodedFrames: Long? = null,
    ): PreparedVideoOutput? {
        recoveryMember.beginSalvage()
        val measured = runCatching { descriptor.statSize }.getOrNull()?.takeIf { it >= 0L }
            ?: context.contentResolver.ownedRowBytes(uri)
        val bytes = bytesForDecision(measured)
        if (decideIncompleteVideo(muxerFinalized, bytes, encodedFrames) == ClipRecoveryDecision.DELETE) {
            neverRecorded = true
            transaction.finish(false)
            return null
        }
        retainedBytes = true
        recoveryMember.keepBytes()
        incompleteReasons = reasons
        incompleteMuxerFinalized = muxerFinalized
        incompleteBytes = bytes
        transaction.resumeAfterRetainedAbort(preparedOutput != null)
        preparedOutput?.let { prepared ->
            markPreparedSidecarIncomplete(reasons)
            return prepared
        }
        return transaction.prepare {
            prepareOwned(sidecarJson, null, timingSidecar, reasons)
        }?.let { requireNotNull(preparedOutput) }
    }

    private fun markPreparedSidecarIncomplete(reasons: List<String>) {
        val sidecar = sidecarUri ?: run {
            android.util.Log.w("VideoOutput", "Prepared take has no sidecar to carry the incomplete marker")
            return
        }
        try {
            val marked = IncompleteTake.mark(writtenSidecarJson, reasons)
            requireNotNull(context.contentResolver.openOutputStream(sidecar, "wt")).use { it.write(marked.toByteArray()) }
            writtenSidecarJson = marked
        } catch (failure: Exception) {
            android.util.Log.w("VideoOutput", "Incomplete marker could not be written; the take is still kept", failure)
        }
    }

    /** Publication never creates another row or writes new metadata. */
    @Synchronized
    fun publishPrepared(): Uri? = transaction.publish(::publishOwned)

    /** The legacy first-completion contract remains atomic across preparation/publication. */
    @Synchronized
    fun finish(success: Boolean, sidecarJson: String? = null, expectedGeometry: RecordingGeometry? = null, timingSidecar: Boolean = false): Uri? =
        transaction.finish(success, prepare = {
            prepareOwned(sidecarJson, expectedGeometry, timingSidecar)
        }, publish = ::publishOwned)

    private fun prepareOwned(
        sidecarJson: String?,
        expectedGeometry: RecordingGeometry?,
        timingSidecar: Boolean,
        incomplete: List<String>? = null,
    ) {
        sealMotionLog(true)
        expectedGeometry?.takeIf { incomplete == null }?.let { expected ->
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
        val published = if (incomplete != null) IncompleteTake.mark(metadata, incomplete) else metadata
        published?.let { writePendingSidecar(it, timingSidecar || sidecarJson == null, videoName) }
        attachMotionLogRow(videoName)
        val artifacts = mutableListOf(PreparedCaptureArtifact(CaptureArtifactRole.VIDEO, uri.toString(), videoName))
        sidecarUri?.let { sidecar ->
            artifacts += PreparedCaptureArtifact(CaptureArtifactRole.VIDEO_METADATA, sidecar.toString(), ownedDisplayName(sidecar))
        }
        check(descriptorRetired)
        recoveryMember.prepared(artifacts)
        preparedOutput = PreparedVideoOutput(uri, java.util.Collections.unmodifiableList(artifacts.toList()))
    }

    /** The log is an attachment, not a prepared artifact: a provider refusing it costs only the log. */
    private fun attachMotionLogRow(videoName: String) {
        val file = motionLog ?: return
        motionLog = null
        try {
            if (!file.isFile || file.length() == 0L) return
            val uri = recoveryMember.insertAuxiliary(videoName.removeSuffix(".mp4") + "." + GyroflowLog.EXTENSION, GyroflowLog.MIME_TYPE)
            try {
                requireNotNull(context.contentResolver.openOutputStream(uri, "w")).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
                motionLogUri = uri
            } catch (failure: Throwable) {
                recoveryMember.removeAuxiliary()
                throw failure
            }
        } catch (failure: Exception) {
            android.util.Log.w("VideoOutput", "Gyroflow log skipped", failure)
        } finally {
            file.delete()
        }
    }

    private fun ownedDisplayName(owned: Uri): String = requireNotNull(context.contentResolver.query(
        owned, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null,
    )) { "Owned output row is unavailable" }.use { cursor ->
        check(cursor.moveToFirst()) { "Owned output row disappeared before preparation" }
        requireNotNull(cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME))) {
            "Owned output row has no display name"
        }.also { check(it.isNotBlank()) { "Owned output row has an empty display name" } }
    }

    private fun publishIncompleteOwned() {
        val result = finalizeClip(
            ContentResolverClipRows(context.contentResolver), uri, sidecarUri,
            muxerFinalized = incompleteMuxerFinalized, bytesWritten = incompleteBytes,
            sidecarBytes = null, correlationId = recoveryGroup.id, attachments = listOfNotNull(motionLogUri),
        )
        when (result) {
            is ClipFinalizationResult.Published -> recoveryMember.published()
            ClipFinalizationResult.Deleted -> error("A clip that holds bytes was deleted during incomplete publication")
            is ClipFinalizationResult.Failed -> throw (result.cause ?: IllegalStateException(result.failure.userMessage))
        }
    }

    private fun publishOwned() {
        check(preparedOutput != null) { "Output identities must be prepared before publication" }
        if (incompleteReasons != null) return publishIncompleteOwned()
        val resolver = context.contentResolver
        motionLogUri?.let { log ->
            check(resolver.update(log, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) == 1) {
                "Motion log publication did not update its owned row"
            }
        }
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

    /**
     * The take never encoded a sample (a refused or failed start): whatever the muxer left behind,
     * even a bare container header, is deleted rather than kept as an incomplete take.
     */
    @Synchronized
    fun discardNeverRecorded() {
        neverRecorded = true
        finish(false)
    }

    private fun deleteOwned() {
        check(descriptorRetired) { "Video descriptor retirement is incomplete" }
        discardMotionLog()
        // ADR-0011: only a zero-length file is deleted. A file with bytes stays pending, owned by the
        // recovery journal, which publishes it as incomplete if the process dies before a retry.
        if (!neverRecorded && (retainedBytes || context.contentResolver.ownedRowBytes(uri) != 0L)) {
            android.util.Log.w("VideoOutput", "Keeping $displayName: it holds bytes")
            recoveryMember.keepBytes()
            return
        }
        // The group waits for the separate native engine guard and any audio worker.
        recoveryMember.abort()
    }

    private fun writePendingSidecar(json: String, timing: Boolean, videoName: String) {
        val sidecarName = videoName.removeSuffix(".mp4") + if (timing) ".timing.json" else ".oclog.json"
        val resolver = context.contentResolver
        val sidecar = recoveryMember.insert(sidecarName, "application/json", metadata = true)
        sidecarUri = sidecar
        requireNotNull(resolver.openOutputStream(sidecar, "w")).use { it.write(json.toByteArray()) }
        writtenSidecarJson = json
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
