/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import com.librestatic.opencinecam.media.storage.ClipFinalizationResult
import com.librestatic.opencinecam.media.storage.ClipRecoveryDecision
import com.librestatic.opencinecam.media.storage.ClipRows
import com.librestatic.opencinecam.media.storage.decideClipRecovery
import com.librestatic.opencinecam.media.storage.finalizeClip
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One MediaStore row an interrupted take owns. [bytes] is the file size, negative when it could not be read. */
internal data class RecoveredRow<U>(
    val root: String,
    val handle: U,
    val name: String,
    val mime: String,
    val bytes: Long,
    val uri: String,
)

internal const val RECOVERED_SIDECAR_LIMIT = 1 shl 20

/**
 * ADR-0011 crash recovery of one uncommitted take that no process owns any more. Each media row is
 * decided by [decideClipRecovery]: an empty one is deleted with its sidecar, one with bytes is published
 * (IS_PENDING=0) with a sidecar that says `takeStatus: incomplete`, reason `process-death`. The sidecar
 * is the take's pending one when it exists, else a new `<stem>.incomplete.json`. Motion logs travel with
 * their video. Returns how many rows were published; failures propagate so the journal entry survives
 * for another attempt (publishing is idempotent).
 */
internal fun <U> recoverIncompleteTake(
    bundleId: String,
    rows: List<RecoveredRow<U>>,
    clipRows: ClipRows<U>,
    insertSidecar: (name: String) -> U,
    readText: (U) -> String?,
): Int {
    val media = rows.filter { it.root != "Download" }
    val json = rows.filter { it.root == "Download" && it.mime == "application/json" }
    val others = rows.filter { it.root == "Download" && it.mime != "application/json" }
    val consumed = mutableSetOf<U>()
    var published = 0
    for (clip in media) {
        consumed += clip.handle
        val video = clip.root == "DCIM"
        val stem = clip.name.substringBeforeLast('.')
        val bytes = bytesForDecision(clip.bytes)
        val existing = json.firstOrNull { it.handle !in consumed && ownsSidecar(it.name, stem, video) }
        existing?.let { consumed += it.handle }
        val keep = decideClipRecovery(muxerFinalized = false, bytesWritten = bytes) != ClipRecoveryDecision.DELETE
        val sidecar = existing?.handle ?: if (keep) insertSidecar(if (video) "$stem.incomplete.json" else "$stem.audio.incomplete.json") else null
        val attachments = if (video) others.filter { it.handle !in consumed }.also { list -> list.forEach { consumed += it.handle } } else emptyList()
        // A motion log without bytes is deleted below like any other empty row, not published with the clip.
        val publishable = attachments.filter { it.bytes != 0L }.map { it.handle }
        val result = finalizeClip(
            clipRows, clip.handle, sidecar, muxerFinalized = false, bytesWritten = bytes,
            sidecarBytes = { recoveredSidecar(bundleId, clip, video, existing?.handle?.let(readText)).toByteArray() },
            correlationId = bundleId, attachments = if (keep) publishable else attachments.map { it.handle },
        )
        when (result) {
            is ClipFinalizationResult.Published -> published++
            ClipFinalizationResult.Deleted -> Unit
            is ClipFinalizationResult.Failed -> throw (result.cause ?: IllegalStateException(result.failure.userMessage))
        }
        if (keep) attachments.filter { it.bytes == 0L }.forEach { clipRows.delete(it.handle) }
    }
    // Rows no media row claimed: keep what has bytes, drop what is empty.
    for (row in rows.filter { it.handle !in consumed }) {
        if (row.bytes == 0L) clipRows.delete(row.handle) else { clipRows.publish(row.handle); published++ }
    }
    return published
}

private fun ownsSidecar(name: String, stem: String, video: Boolean): Boolean {
    if (!name.startsWith("$stem.")) return false
    val audioSidecar = name.endsWith(".audio.json") || name.endsWith(".audio.incomplete.json")
    return audioSidecar != video
}

/** The pending sidecar of the take with the marker, or a minimal document the catalog accepts. */
internal fun <U> recoveredSidecar(bundleId: String, clip: RecoveredRow<U>, video: Boolean, existing: String?): String {
    val base = existing?.takeIf { it.length <= RECOVERED_SIDECAR_LIMIT }
        ?.takeIf { text -> runCatching { Json.parseToJsonElement(text) is JsonObject }.getOrDefault(false) }
        ?: buildJsonObject {
            put("schema", if (video) "opencinecam.recording.v1" else "opencinecam-audio-sidecar-v1")
            put(if (video) "videoUri" else "audioUri", clip.uri)
            put("bundleId", bundleId)
        }.toString()
    return IncompleteTake.mark(
        base, listOf(IncompleteTake.PROCESS_DEATH),
        note = "Recording was interrupted before the file was finalized; the container may need repair.",
    )
}
