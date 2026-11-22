/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import com.librestatic.opencinecam.settings.CaptureNameSnapshot
import com.librestatic.opencinecam.settings.captureFileStem
import com.librestatic.opencinecam.settings.ProductionSlateSettings
import com.librestatic.opencinecam.metadata.productionSlateJson
import kotlinx.serialization.json.*
import org.json.JSONObject
import com.librestatic.opencinecam.settings.CaptureLocationSnapshot
import com.librestatic.opencinecam.settings.captureLocationJson
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.librestatic.opencinecam.camera.EncodedSampleListener
import com.librestatic.opencinecam.camera.RecordingGeometry
import com.librestatic.opencinecam.media.storage.ClipFinalizationResult
import com.librestatic.opencinecam.media.storage.ClipRecoveryDecision
import com.librestatic.opencinecam.media.storage.decideClipRecovery
import com.librestatic.opencinecam.media.storage.finalizeClip
import com.librestatic.opencinecam.motion.MotionTakeRecorder
import com.librestatic.opencinecam.motion.MotionTakeSummary
import com.librestatic.opencinecam.motion.MotionTakeTiming

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
    private var clipSidecarUri: Uri? = null
    private var writtenClipSidecarJson: String? = null
    private var geometryFailure: String? = null
    private var motionRecorder: MotionTakeRecorder? = null
    private var motionSealed = false
    private var motionGcsv: java.io.File? = null
    private var motionSummary: MotionTakeSummary? = null
    private var motionLogUri: Uri? = null
    private var motionLogName: String? = null
    private var preparedOutput: PreparedVideoOutput? = null
    private var descriptorRetired = false
    private var incompleteReasons: List<String>? = null
    private var incompleteMuxerFinalized = false
    private var incompleteBytes = 0L
    private var retainedBytes = false
    private var neverRecorded = false
    private var writtenSidecarJson: String? = null
    // ADR-0037: a take on a drive registers its descriptor, so a leaving drive closes it at once (vold kills a holder).
    // The muxer writes through its own duplicate; after an early release the close below does nothing.
    private val descriptorCloser = CloseOnce(descriptor)
    private val driveHandle = DriveDescriptors.registerDocument(uri, descriptorCloser)
    // The flusher syncs its own duplicate about once a second (a pulled drive loses the last second, not the file);
    // it is dropped before the file is published or renamed.
    private val driveFlush = DriveWriteFlusher.shared.add(uri, descriptor)
    // PLAN-072: a GPU-pipeline take on a drive keeps a side index so a pull that loses the moov can be repaired. The
    // writer closes with the descriptor; the file stays until the journal resolves the take (RecordingCaptureRecovery).
    private var takeIndex: TakeIndexListener? = null
    private val transaction = PendingOutputTransaction(uri, {
        try { takeIndex?.close() } finally {
            try { driveFlush?.close() } finally {
                try { descriptorCloser.close() } finally { driveHandle.unregister() }
            }
        }
        descriptorRetired = true
    }, ::deleteOwned)

    /**
     * What the GPU pipeline reports its muxer samples to, or null off a drive (a phone take has no use for an index)
     * and once the descriptor is retired. The index file is created with the first track, so a route that ignores the
     * listener (MediaRecorder) leaves none.
     */
    @Synchronized
    internal fun sampleListener(): EncodedSampleListener? {
        if (driveFlush == null || descriptorRetired) return null
        return takeIndex ?: TakeIndexFiles(context).let { files ->
            TakeIndexListener(files.fileOf(recoveryGroup.id), files::ensureFolder)
        }.also { takeIndex = it }
    }

    /** The optional motion data of this take. Best effort: it never fails or delays the take. */
    @Synchronized
    internal fun attachMotionRecorder(recorder: MotionTakeRecorder) { motionRecorder = recorder }

    @Synchronized
    internal fun motionRecorder(): MotionTakeRecorder? = motionRecorder

    /** True while the take has motion data [sealMotion] has not sealed yet. */
    @Synchronized
    internal fun motionPending(): Boolean = !motionSealed && motionRecorder != null

    /** What the take's motion data came to; null without motion data or before [sealMotion] rendered it. */
    @Synchronized
    internal fun motionSummary(): MotionTakeSummary? = motionSummary

    /**
     * Stops the sensors and renders the motion data against [timing] (null drops it), adding a CAMM track to the file
     * when [appendCamm]. Must run while the descriptor is open and no writer is live. Only the first call counts; a CAMM
     * failure costs only the track.
     */
    @Synchronized
    internal fun sealMotion(timing: MotionTakeTiming?, appendCamm: Boolean) {
        if (motionSealed) return
        motionSealed = true
        val recorder = motionRecorder ?: return
        val outputs = timing?.let { recorder.render(it, withCamm = appendCamm) }
        recorder.stop()
        // Only the log file is kept: the CAMM series can be tens of megabytes and is not needed after the append.
        motionGcsv = outputs?.gcsv
        if (outputs == null) return
        var packets = 0
        outputs.camm?.takeIf { appendCamm && !descriptorRetired }?.let { camm ->
            packets = try {
                appendCammTrack(descriptor.fileDescriptor, camm)
            } catch (problem: Exception) {
                android.util.Log.w("VideoOutput", "CAMM track not added; the take keeps its Gyroflow log", problem)
                0
            }
        }
        motionSummary = MotionTakeSummary(recorder.info, outputs.result, outputs.synced, outputs.estimated,
            cammAppended = packets > 0, cammPackets = packets, cammBinUs = outputs.cammBinUs)
    }

    @Synchronized
    private fun discardMotionLog() {
        sealMotion(null, appendCamm = false)
        motionGcsv = null
        motionRecorder?.discard()
        motionRecorder = null
    }

    /** Freeze all owned identities after closing/writing; every row remains pending. */
    @Synchronized
    fun prepareCompletion(
        sidecarJson: String? = null,
        expectedGeometry: RecordingGeometry? = null,
        timingSidecar: Boolean = false,
        clipSidecar: ClipSidecarProvider? = null,
    ): PreparedVideoOutput? = transaction.prepare {
        prepareOwned(sidecarJson, expectedGeometry, timingSidecar, clipSidecar = clipSidecar)
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
        clipSidecar: ClipSidecarProvider? = null,
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
            prepareOwned(sidecarJson, null, timingSidecar, reasons, clipSidecar)
        }?.let { requireNotNull(preparedOutput) }
    }

    private fun markPreparedSidecarIncomplete(reasons: List<String>) {
        markPreparedClipSidecarIncomplete(reasons)
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

    /** Best effort, like the timing sidecar: the clip sidecar must never fail a take that is being kept. */
    private fun markPreparedClipSidecarIncomplete(reasons: List<String>) {
        val clip = clipSidecarUri ?: return
        try {
            val marked = IncompleteTake.mark(writtenClipSidecarJson, reasons)
            requireNotNull(context.contentResolver.openOutputStream(clip, "wt")).use { it.write(marked.toByteArray()) }
            writtenClipSidecarJson = marked
        } catch (failure: Exception) {
            android.util.Log.w("VideoOutput", "Incomplete marker could not be written to the clip sidecar; the take is still kept", failure)
        }
    }

    /** Publication never creates another row or writes new metadata. */
    @Synchronized
    fun publishPrepared(): Uri? = transaction.publish(::publishOwned)

    /** The legacy first-completion contract remains atomic across preparation/publication. */
    @Synchronized
    fun finish(success: Boolean, sidecarJson: String? = null, expectedGeometry: RecordingGeometry? = null, timingSidecar: Boolean = false,
        clipSidecar: ClipSidecarProvider? = null): Uri? =
        transaction.finish(success, prepare = {
            prepareOwned(sidecarJson, expectedGeometry, timingSidecar, clipSidecar = clipSidecar)
        }, publish = ::publishOwned)

    private fun prepareOwned(
        sidecarJson: String?,
        expectedGeometry: RecordingGeometry?,
        timingSidecar: Boolean,
        incomplete: List<String>? = null,
        clipSidecar: ClipSidecarProvider? = null,
    ) {
        // A take the stop did not seal (an early failure) keeps no motion data.
        sealMotion(null, appendCamm = false)
        var fileVerified = ClipStepStatus.NOT_RUN
        var fileVerifiedDetail: String? = geometryFailure?.also { fileVerified = ClipStepStatus.FAIL }
        expectedGeometry?.takeIf { incomplete == null }?.let { expected ->
            val validation = VideoFileGeometryValidator(context).validate(uri, expected)
            if (!validation.valid) geometryFailure = validation.message
            check(validation.valid) { validation.message }
            fileVerified = ClipStepStatus.PASS
            fileVerifiedDetail = null
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
        // Before the clip sidecar, so its motion block knows whether the log was attached.
        attachMotionLogRow(videoName)
        writePendingClipSidecar(clipSidecar, videoName, sidecarUri?.let(::ownedDisplayName), fileVerified, fileVerifiedDetail, incomplete)
        val artifacts = mutableListOf(PreparedCaptureArtifact(CaptureArtifactRole.VIDEO, uri.toString(), videoName))
        sidecarUri?.let { sidecar ->
            artifacts += PreparedCaptureArtifact(CaptureArtifactRole.VIDEO_METADATA, sidecar.toString(), ownedDisplayName(sidecar))
        }
        clipSidecarUri?.let { clip ->
            artifacts += PreparedCaptureArtifact(CaptureArtifactRole.CLIP_METADATA, clip.toString(), ownedDisplayName(clip))
        }
        check(descriptorRetired)
        recoveryMember.prepared(artifacts)
        // The log goes to the publication observers only (the WebDAV upload); under the name Gyroflow pairs, or not at all.
        val logName = motionLogName
        motionLogUri?.takeIf { logName != null && logName.endsWith(".${GyroflowLog.EXTENSION}", ignoreCase = true) }?.let { log ->
            artifacts += PreparedCaptureArtifact(CaptureArtifactRole.MOTION_LOG, log.toString(), requireNotNull(logName))
        }
        preparedOutput = PreparedVideoOutput(uri, java.util.Collections.unmodifiableList(artifacts.toList()))
    }

    /** The log is an attachment, not a prepared artifact: a provider refusing it costs only the log. */
    private fun attachMotionLogRow(videoName: String) {
        val recorder = motionRecorder ?: return
        val gcsv = motionGcsv
        motionRecorder = null
        motionGcsv = null
        try {
            val file = gcsv ?: return
            if (!file.isFile || file.length() == 0L) return
            val logName = motionLogName(videoName)
            val uri = recoveryMember.insertAuxiliary(logName, GyroflowLog.MIME_TYPE)
            try {
                writeMotionLogRow(context.contentResolver, uri, file)
                motionLogUri = uri
                motionLogName = runCatching { ownedDisplayName(uri) }.getOrNull() ?: logName
                motionSummary = motionSummary?.copy(gcsvAttached = true)
            } catch (failure: Throwable) {
                recoveryMember.removeAuxiliary()
                throw failure
            }
        } catch (failure: Exception) {
            android.util.Log.w("VideoOutput", "Gyroflow log skipped", failure)
        } finally {
            // The take's files now hold everything the motion data could give: the spool is not needed again.
            recorder.discard()
            motionSummary?.let { android.util.Log.i("VideoOutput", it.logLine()) }
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
            recoveryMember.clipRows, uri, sidecarUri,
            muxerFinalized = incompleteMuxerFinalized, bytesWritten = incompleteBytes,
            sidecarBytes = null, correlationId = recoveryGroup.id, attachments = listOfNotNull(motionLogUri, clipSidecarUri),
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
        // The take's backend publishes: MediaStore clears IS_PENDING, a drive take checks the document (its marker goes at commit).
        motionLogUri?.let { log -> recoveryMember.publish(log, "Motion log publication did not update its owned row") }
        sidecarUri?.let { sidecar -> recoveryMember.publish(sidecar, "Sidecar publication did not update its owned row") }
        clipSidecarUri?.let { clip -> recoveryMember.publish(clip, "Clip sidecar publication did not update its owned row") }
        recoveryMember.publish(uri, "Video publication did not update its owned row")
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
        // The sensors stop whatever happens to the output; its motion files go only with it.
        synchronized(this) { motionRecorder?.stop() }
        // ADR-0011: only a zero-length file is deleted. A file with bytes stays pending, owned by the
        // recovery journal, which publishes it as incomplete if the process dies before a retry.
        if (!neverRecorded && (retainedBytes || context.contentResolver.ownedRowBytes(uri) != 0L)) {
            android.util.Log.w("VideoOutput", "Keeping $displayName: it holds bytes")
            recoveryMember.keepBytes()
            // The spool, its description and any rendered log stay: a salvage in this process attaches the log, and
            // after a process death the journal's recovery renders the spool for the kept take.
            return
        }
        try {
            // The group waits for the separate native engine guard and any audio worker.
            recoveryMember.abort()
        } finally {
            // A deleted (or never recorded) output has no use for its motion data.
            discardMotionLog()
        }
    }

    /**
     * The canonical `<stem>.clip.json`. Best effort by decision: a provider or write failure is logged and the take
     * publishes without it, with its row withdrawn so artifact and row accounting stay consistent.
     */
    private fun writePendingClipSidecar(
        provider: ClipSidecarProvider?, videoName: String, metadataName: String?,
        fileVerified: ClipStepStatus, fileVerifiedDetail: String?, incomplete: List<String>?,
    ) {
        if (provider == null) {
            // A row left by an earlier attempt of this take keeps travelling with it, marked like the rest.
            if (incomplete != null) markPreparedClipSidecarIncomplete(incomplete)
            return
        }
        try {
            val json = provider.build(ClipSidecarPrepareContext(
                recordingId = recoveryGroup.id, videoName = videoName, metadataName = metadataName,
                fileVerified = fileVerified, fileVerifiedDetail = fileVerifiedDetail, incompleteReasons = incomplete,
                motion = motionSummary?.let { clipMotion(it, motionLogName) },
            )) ?: return
            val clip = clipSidecarUri ?: recoveryMember.insertClipSidecar(clipSidecarName(videoName))
            clipSidecarUri = clip
            try {
                requireNotNull(context.contentResolver.openOutputStream(clip, "wt")).use { it.write(json.toByteArray()) }
            } catch (failure: Throwable) {
                clipSidecarUri = null
                try { recoveryMember.removeClipSidecar() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                throw failure
            }
            writtenClipSidecarJson = json
        } catch (failure: Exception) {
            android.util.Log.w("VideoOutput", "Clip sidecar skipped; the take publishes without it", failure)
        }
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
            captureNames: CaptureNameSnapshot? = null): VideoOutput =
            create(context, productionSlate, captureNames, destination = RecordingDestination.Phone)

        /**
         * PLAN-072: the take, its sidecars and any audio member that joins its group are written to [destination].
         * [stem] replaces the usual `captureFileStem(group.id, captureNames)`: a continuation on the phone is named
         * after the drive take it continues ([com.librestatic.opencinecam.settings.continuationFileStem]), not its own bundle id.
         */
        internal fun create(context: Context, productionSlate: ProductionSlateSettings?,
            captureNames: CaptureNameSnapshot?, destination: RecordingDestination, stem: String? = null): VideoOutput {
            require(captureNames == null || productionSlate == null || captureNames.slate == productionSlate)
            val group = RecordingCaptureRecovery.begin(context, destination)
            val member = group.member(RecordingMemberKind.VIDEO)
            val name = (stem ?: captureFileStem(group.id, captureNames)) + ".mp4"
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
