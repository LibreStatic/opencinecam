/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.storage

import android.content.Context
import android.media.MediaFormat
import android.util.Log
import com.librestatic.opencinecam.camera.EncodedSampleListener
import java.io.File

/**
 * PLAN-072: where the side index of a drive take lives, `filesDir/take-index/<takeId>.ocix`, keyed by the recording
 * journal's take id. The journal owns its life: it is deleted when the entry is resolved (committed, discarded or
 * published incomplete) and kept while the take waits for recovery, which reads it ([Mp4MoovRebuilder]).
 *
 * Next to it, `<takeId>.ocix.rebuilt` is the receipt of a moov the recovery rebuilt: a publication that fails after the
 * rebuild finds the file complete on its retry ([Mp4RebuildResult.NotNeeded]), and the receipt keeps what the rebuild
 * did for the sidecar. A file the muxer finalized has none. It goes with the index.
 */
internal class TakeIndexFiles(context: Context) {
    private val folder = File(context.applicationContext.filesDir, FOLDER)

    fun fileOf(takeId: String): File {
        RecordingCaptureRecovery.requireId(takeId)
        return File(folder, "$takeId$EXTENSION")
    }

    fun receiptOf(takeId: String): File {
        RecordingCaptureRecovery.requireId(takeId)
        return File(folder, "$takeId$EXTENSION$RECEIPT_SUFFIX")
    }

    fun delete(takeId: String) {
        receiptOf(takeId).delete()
        fileOf(takeId).delete()
    }

    /** Keeps the receipt of [result], the moov rebuilt in the take's file [uri]: written to a `.part`, synced and renamed. */
    fun writeReceipt(takeId: String, uri: String, result: Mp4RebuildResult.Rebuilt) {
        ensureFolder()
        val target = receiptOf(takeId)
        val partial = File(folder, target.name + ".part")
        val json = org.json.JSONObject().put("uri", uri).put("videoSamples", result.videoSamples)
            .put("audioSamples", result.audioSamples).put("durationUs", result.durationUs).toString()
        java.io.FileOutputStream(partial).use { it.write(json.toByteArray()); it.fd.sync() }
        check(partial.renameTo(target)) { "Take rebuild receipt could not be kept" }
    }

    /** The rebuild of take [takeId]'s file [uri] a receipt records; null without one, for another file or unreadable. */
    fun readReceipt(takeId: String, uri: String): Mp4RebuildResult.Rebuilt? = try {
        val o = org.json.JSONObject(receiptOf(takeId).readText())
        if (o.getString("uri") != uri) null
        else Mp4RebuildResult.Rebuilt(o.getInt("videoSamples"), o.getInt("audioSamples"), o.getLong("durationUs"))
    } catch (_: java.io.IOException) {
        null
    } catch (_: org.json.JSONException) {
        null
    }

    /**
     * Deletes the index files (and receipts) whose take has no journal entry in [known] and that are older than
     * [maxAgeMs]; returns how many went.
     */
    fun sweepOrphans(known: Set<String>, maxAgeMs: Long, now: Long = System.currentTimeMillis()): Int {
        var deleted = 0
        folder.listFiles()?.forEach { file ->
            val id = file.name.removeSuffix(".part").removeSuffix(RECEIPT_SUFFIX).removeSuffix(EXTENSION)
            val ours = file.name.endsWith(EXTENSION) || file.name.endsWith(EXTENSION + RECEIPT_SUFFIX) ||
                file.name.endsWith("$EXTENSION$RECEIPT_SUFFIX.part")
            if (!ours || id in known || now - file.lastModified() < maxAgeMs) return@forEach
            if (file.delete()) deleted++
        }
        return deleted
    }

    fun ensureFolder() { check(folder.isDirectory || folder.mkdirs() || folder.isDirectory) { "Take index folder unavailable" } }

    companion object {
        const val FOLDER = "take-index"
        const val EXTENSION = ".ocix"
        const val RECEIPT_SUFFIX = ".rebuilt"
        /** An index without a journal entry this old belongs to no take anyone can still recover. */
        const val ORPHAN_AGE_MS = 24L * 60 * 60 * 1000
    }
}

/**
 * Feeds one drive take's [TakeSampleIndexWriter] from the GPU pipeline's drain threads. The file is created with the
 * first track, so a route that never calls it (MediaRecorder) leaves nothing behind. It reaches the kernel about once a
 * second ([flushIntervalNs]), checked from the sample path with no allocation. A writer that cannot be created is logged
 * once and the take records without an index; an I/O failure later propagates to the pipeline's guard, which stops
 * calling it for the take.
 */
internal class TakeIndexListener(
    private val file: File,
    private val ensureFolder: () -> Unit,
    private val flushIntervalNs: Long = 1_000_000_000L,
    private val nanoTime: () -> Long = System::nanoTime,
) : EncodedSampleListener, AutoCloseable {
    @Volatile private var writer: TakeSampleIndexWriter? = null
    private var unavailable = false
    private var closed = false
    private var lastFlushNs = 0L

    @Synchronized
    override fun onTrack(video: Boolean, format: MediaFormat, rotationDegrees: Int) {
        if (closed) return
        val target = writer ?: createWriter() ?: return
        target.writeTrack(kind(video), TakeTrackInfo.fromMediaFormat(format, rotationDegrees))
        // The parameter sets are what a rebuilt moov cannot do without: they reach the kernel at once.
        target.flush()
        lastFlushNs = nanoTime()
    }

    override fun onSample(video: Boolean, size: Int, presentationTimeUs: Long, flags: Int) {
        val target = writer ?: return
        target.writeSample(kind(video), size, presentationTimeUs, flags)
        val now = nanoTime()
        if (now - lastFlushNs >= flushIntervalNs) {
            lastFlushNs = now
            target.flush()
        }
    }

    private fun createWriter(): TakeSampleIndexWriter? {
        if (unavailable || closed) return null
        return try {
            ensureFolder()
            TakeSampleIndexWriter(file).also { writer = it }
        } catch (failure: Exception) {
            unavailable = true
            Log.w("TakeIndex", "The take index could not be created; the take records without one.", failure)
            null
        }
    }

    /** Closes the writer, keeping the file: the journal decides when it goes. Idempotent, and final: a later track creates no file. */
    @Synchronized
    override fun close() {
        closed = true
        val target = writer ?: return
        try { target.close() } catch (failure: Exception) { Log.w("TakeIndex", "The take index could not be closed cleanly.", failure) }
    }

    private fun kind(video: Boolean) = if (video) TrackKind.VIDEO else TrackKind.AUDIO
}
