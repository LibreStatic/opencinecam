/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import android.net.Uri

/** Complete identities allocated and written before any row is published. */
enum class CaptureArtifactRole {
    VIDEO, VIDEO_METADATA, AUDIO, AUDIO_METADATA,
    /** The canonical `<videoStem>.clip.json` sidecar (ClipSidecarDocument); a video take may or may not have one. */
    CLIP_METADATA,
    /**
     * The take's Gyroflow `<videoStem>.gcsv` log. Only [PreparedVideoOutput.artifacts] carries it, for the publication
     * observers (the WebDAV outbox); the recording journal keeps it as the take's auxiliary row, never as a prepared one.
     */
    MOTION_LOG,
}

data class PreparedCaptureArtifact(
    val role: CaptureArtifactRole,
    val uri: String,
    val displayName: String,
)

data class PreparedVideoOutput(val uri: Uri, val artifacts: List<PreparedCaptureArtifact>)
data class PreparedAudioSidecar(val result: AudioSidecarRecordingResult, val artifacts: List<PreparedCaptureArtifact>)

/** Optional durable bookkeeping; its failure must never revoke a valid local capture. */
interface CapturePublicationObserver {
    fun onPrepared(artifacts: List<PreparedCaptureArtifact>)
    fun onPublished(artifacts: List<PreparedCaptureArtifact>)
    fun onAborted()
}
