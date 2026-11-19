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
 */
internal class TakeIndexFiles(context: Context) {
    private val folder = File(context.applicationContext.filesDir, FOLDER)

    fun fileOf(takeId: String): File {
        RecordingCaptureRecovery.requireId(takeId)
        return File(folder, "$takeId$EXTENSION")
    }

    fun delete(takeId: String) { fileOf(takeId).delete() }

    /** Deletes the index files whose take has no journal entry in [known] and that are older than [maxAgeMs]; returns how many went. */
    fun sweepOrphans(known: Set<String>, maxAgeMs: Long, now: Long = System.currentTimeMillis()): Int {
        var deleted = 0
        folder.listFiles()?.forEach { file ->
            val id = file.name.removeSuffix(EXTENSION)
            if (!file.name.endsWith(EXTENSION) || id in known || now - file.lastModified() < maxAgeMs) return@forEach
            if (file.delete()) deleted++
        }
        return deleted
    }

    fun ensureFolder() { check(folder.isDirectory || folder.mkdirs() || folder.isDirectory) { "Take index folder unavailable" } }

    companion object {
        const val FOLDER = "take-index"
        const val EXTENSION = ".ocix"
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
    private var lastFlushNs = 0L

    @Synchronized
    override fun onTrack(video: Boolean, format: MediaFormat, rotationDegrees: Int) {
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
        if (unavailable) return null
        return try {
            ensureFolder()
            TakeSampleIndexWriter(file).also { writer = it }
        } catch (failure: Exception) {
            unavailable = true
            Log.w("TakeIndex", "The take index could not be created; the take records without one.", failure)
            null
        }
    }

    /** Closes the writer, keeping the file: the journal decides when it goes. Idempotent. */
    @Synchronized
    override fun close() {
        val target = writer ?: return
        try { target.close() } catch (failure: Exception) { Log.w("TakeIndex", "The take index could not be closed cleanly.", failure) }
    }

    private fun kind(video: Boolean) = if (video) TrackKind.VIDEO else TrackKind.AUDIO
}
