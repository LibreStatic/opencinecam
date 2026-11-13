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
 * for another attempt (publishing is idempotent). [readText] gives null only for a sidecar recovery ignores (too large,
 * or unreadable on the phone); a read that fails on a drive throws, so the sidecar is never rewritten as a minimal one.
 */
internal fun <U> recoverIncompleteTake(
    bundleId: String,
    rows: List<RecoveredRow<U>>,
    clipRows: ClipRows<U>,
    insertSidecar: (name: String) -> U,
    readText: (U) -> String?,
    backups: RecoveredSidecarBackups = RecoveredSidecarBackups.NONE,
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
        // The canonical clip sidecar is not the timing or OCLog sidecar: it is never rewritten as one.
        val clipSidecar = if (video) json.firstOrNull { it.handle !in consumed && it.name == stem + CLIP_SIDECAR_SUFFIX } else null
        clipSidecar?.let { consumed += it.handle }
        val keep = decideClipRecovery(muxerFinalized = false, bytesWritten = bytes) != ClipRecoveryDecision.DELETE
        // Read before anything is written: a read that fails throws here, so the take's sidecar is never replaced (or,
        // through finalizeClip's own failure path, deleted) on a read that did not answer, and the entry stays for a retry.
        // A rewrite opens the take's sidecar truncated, so its text is kept aside first and preferred on a retry: a rewrite
        // that failed or a process that died part way leaves the sidecar empty or partial, never the source of the next.
        val backupKey = existing?.takeIf { keep }?.let { "$bundleId/${it.name}" }
        val saved = backupKey?.let(backups::read)
        val existingText = saved ?: if (keep) existing?.handle?.let(readText) else null
        // Only a document worth keeping: anything else is replaced by a minimal one whatever happens.
        if (saved == null && backupKey != null && existingText != null && isSidecarDocument(existingText)) backups.write(backupKey, existingText)
        // The clip sidecar too: a drive read that fails stops here, rather than publishing it without the marker.
        val markable = clipSidecar?.takeIf { keep && it.bytes != 0L }
        val clipText = markable?.handle?.let(readText)
        if (markable != null && clipText != null) markRecoveredClipSidecar(bundleId, markable, clipText, clipRows)
        val sidecar = existing?.handle ?: if (keep) insertSidecar(if (video) "$stem.incomplete.json" else "$stem.audio.incomplete.json") else null
        val attachments = if (video) (others.filter { it.handle !in consumed } + listOfNotNull(clipSidecar)).also { list -> list.forEach { consumed += it.handle } } else emptyList()
        // A motion log without bytes is deleted below like any other empty row, not published with the clip.
        val publishable = attachments.filter { it.bytes != 0L }.map { it.handle }
        val result = finalizeClip(
            clipRows, clip.handle, sidecar, muxerFinalized = false, bytesWritten = bytes,
            sidecarBytes = { recoveredSidecar(bundleId, clip, video, existingText).toByteArray() },
            correlationId = bundleId, attachments = if (keep) publishable else attachments.map { it.handle },
            // The take's own sidecar is rewritten in place: a failed rewrite keeps it for the retry.
            keepSidecarOnFailure = existing != null,
        )
        when (result) {
            is ClipFinalizationResult.Published -> { published++; backupKey?.let(backups::delete) }
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
    if (!name.startsWith("$stem.") || name.endsWith(CLIP_SIDECAR_SUFFIX)) return false
    val audioSidecar = name.endsWith(".audio.json") || name.endsWith(".audio.incomplete.json")
    return audioSidecar != video
}

/** Whether [text] is a sidecar recovery builds on: a JSON object within [RECOVERED_SIDECAR_LIMIT]. */
private fun isSidecarDocument(text: String): Boolean = text.length <= RECOVERED_SIDECAR_LIMIT &&
    runCatching { Json.parseToJsonElement(text) is JsonObject }.getOrDefault(false)

/** The pending sidecar of the take with the marker, or a minimal document the catalog accepts. */
internal fun <U> recoveredSidecar(bundleId: String, clip: RecoveredRow<U>, video: Boolean, existing: String?): String {
    val base = existing?.takeIf(::isSidecarDocument)
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

/**
 * ADR-0011 for the clip sidecar of an interrupted take: it was written pending as if the take would
 * complete, so it gets the same incomplete marker as the timing sidecar. [text] is the sidecar as read (the caller
 * reads it first, so a drive read that fails stops the recovery). Best effort past that: a sidecar that is not a JSON
 * object or too large is published as it is, and a failed rewrite never stops the take from being kept.
 */
private fun <U> markRecoveredClipSidecar(bundleId: String, clip: RecoveredRow<U>, text: String, clipRows: ClipRows<U>) {
    try {
        if (text.length > RECOVERED_SIDECAR_LIMIT) return
        if (runCatching { Json.parseToJsonElement(text) is JsonObject }.getOrDefault(false).not()) return
        if (IncompleteTake.reasonsOf(text) != null) return // already says why (the take was kept after a failure)
        val marked = IncompleteTake.mark(
            text, listOf(IncompleteTake.PROCESS_DEATH),
            note = "Recording was interrupted before the file was finalized; the container may need repair.",
        )
        clipRows.writeSidecar(clip.handle, marked.toByteArray())
    } catch (failure: Exception) {
        runCatching { android.util.Log.w("IncompleteTakeRecovery", "Clip sidecar of take $bundleId could not be marked incomplete; it is published as it is", failure) }
    }
}

/**
 * Durable copies of the sidecar texts [recoverIncompleteTake] is about to rewrite, by take and sidecar name (the key is
 * `<bundleId>/<name>`): written before the rewrite, read in place of the sidecar while present, deleted once the take
 * is published, and every copy of a take ([deleteTake]) once its journal entry is discarded.
 */
internal interface RecoveredSidecarBackups {
    fun read(key: String): String?
    fun write(key: String, text: String)
    fun delete(key: String)
    fun deleteTake(bundleId: String) = Unit

    companion object {
        /** No copies: the tests' rows and any caller that cannot lose a sidecar this way. */
        val NONE = object : RecoveredSidecarBackups {
            override fun read(key: String): String? = null
            override fun write(key: String, text: String) = Unit
            override fun delete(key: String) = Unit
        }
    }
}

/** [RecoveredSidecarBackups] as files in [folder], each written to a `.part`, synced and renamed into place. */
internal class FileRecoveredSidecarBackups(private val folder: java.io.File) : RecoveredSidecarBackups {
    /** Named `<bundleId>-<hash>.json`, so a take's copies are found by its id ([deleteTake]). */
    private fun file(key: String) = java.io.File(folder, prefix(key.substringBefore('/')) + java.security.MessageDigest
        .getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }.take(40) + ".json")

    private fun prefix(bundleId: String) = bundleId.filter { it.isLetterOrDigit() || it == '-' } + "-"

    override fun read(key: String): String? = file(key).takeIf { it.isFile }?.readText()

    override fun write(key: String, text: String) {
        check(folder.isDirectory || folder.mkdirs()) { "Sidecar backup folder unavailable" }
        val target = file(key)
        val partial = java.io.File(folder, target.name + ".part")
        java.io.FileOutputStream(partial).use { it.write(text.toByteArray()); it.fd.sync() }
        check(partial.renameTo(target)) { "Sidecar backup could not be kept" }
    }

    override fun delete(key: String) {
        file(key).delete()
    }

    override fun deleteTake(bundleId: String) {
        val prefix = prefix(bundleId)
        folder.listFiles()?.filter { it.name.startsWith(prefix) }?.forEach { it.delete() }
    }
}
