/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import android.net.Uri

/** Complete identities allocated and written before any row is published. */
enum class CaptureArtifactRole { VIDEO, VIDEO_METADATA, AUDIO, AUDIO_METADATA }

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
