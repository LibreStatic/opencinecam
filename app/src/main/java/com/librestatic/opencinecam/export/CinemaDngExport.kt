/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.export

import com.librestatic.opencinecam.media.ocraw.CinemaDngWriter
import com.librestatic.opencinecam.media.ocraw.OcrawDngException
import com.librestatic.opencinecam.media.ocraw.OcrawFrameHeader
import com.librestatic.opencinecam.media.ocraw.OcrawFrameIndex
import com.librestatic.opencinecam.media.ocraw.OcrawStreamReader
import com.librestatic.opencinecam.media.ocraw.OcrawWavWriter
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.WritableByteChannel
import kotlin.math.ceil

/**
 * One take folder being written at a destination (OCC-PLAN-071 §8, route 2). Files stay
 * invisible to other apps where the destination can do that (MediaStore pending rows) until
 * [publish]; [discard] removes the folder and everything in it.
 */
internal interface CinemaDngTarget {
    /** Where the folder is, for the operator: `Documents/OpenCineCam/CinemaDNG/A001_C001`. */
    val location: String

    /** Places a `.nomedia` in the folder; false when the destination refused or renamed it. */
    fun writeNoMedia(): Boolean

    /** Creates [name] and lets [body] fill it. When [body] throws, the file is removed and the failure rethrown. */
    fun write(name: String, mimeType: String, body: (WritableByteChannel) -> Unit)

    /** Makes every file written visible. */
    fun publish()

    /** Removes the folder and every file written; true when nothing is left behind. */
    fun discard(): Boolean
}

/** Where take folders go: the MediaStore Documents folder or a folder the operator picked. */
internal interface CinemaDngDestination {
    /** Free bytes where the folder would go, or null when the destination cannot tell. */
    fun freeBytes(): Long?

    /** Creates the take's folder: `<take>`, or `<take>_2`, `<take>_3`… when that name is taken. */
    fun open(takeName: String): CinemaDngTarget
}

/**
 * What exporting a take would write, from the index alone: frames, how many convert, and the
 * bytes. Sizes are exact except for RAW10 and block-pack frames re-encoded to LJ92, whose size is
 * known only once encoded; those count at [LJ92_ESTIMATE] of their uncompressed DNG (LJ92 on 10-bit
 * sensor data roughly halves it; 0.6 leaves headroom). The export fails cleanly if the estimate falls short.
 */
internal data class CinemaDngExportPlan(
    val takeName: String,
    val frames: Int,
    /** Frames that will become a DNG; the others are skipped as damaged. */
    val convertible: Int,
    /** Exact DNG bytes of the convertible frames the LJ92 option leaves as they are (LJ92 tiles). */
    val fixedBytes: Long,
    /** Uncompressed DNG bytes of the RAW10 and block-pack frames, the ones the LJ92 option re-encodes. */
    val reencodedBytes: Long,
    val reencodedFrames: Int,
    /** The WAV's exact size, or null when the take has no sound. */
    val wavBytes: Long?,
) {
    val skipped: Int get() = frames - convertible
    val hasAudio: Boolean get() = wavBytes != null

    /** The bytes the export needs; an estimate when [compress] re-encodes frames (see [isEstimate]). */
    fun bytes(compress: Boolean): Long {
        val reencoded = if (compress) ceil(reencodedBytes * LJ92_ESTIMATE).toLong() else reencodedBytes
        return fixedBytes + reencoded + (wavBytes ?: 0L)
    }

    fun isEstimate(compress: Boolean): Boolean = compress && reencodedFrames > 0

    /** True when [freeBytes] holds [bytes] plus [SPACE_RESERVE_BYTES]; an unknown free space passes. */
    fun fits(compress: Boolean, freeBytes: Long?): Boolean = freeBytes == null || freeBytes >= bytes(compress) + SPACE_RESERVE_BYTES

    companion object {
        /** Share of the uncompressed DNG size a re-encoded RAW10 or block-pack frame is assumed to take. */
        const val LJ92_ESTIMATE = 0.6

        /** Left free on the destination after the export, so the phone and the next take still have room. */
        const val SPACE_RESERVE_BYTES = 64L * 1024 * 1024
    }
}

internal data class CinemaDngExportProgress(val done: Int, val total: Int, val skipped: Int, val bytes: Long) {
    val fraction: Float get() = if (total <= 0) 0f else done.toFloat() / total
}

enum class CinemaDngRefusal {
    /** A take is recording; the export never competes with it for storage. */
    RECORDING,

    /** The `.ocraw` has no readable header, or a CFA layout DNG cannot describe. */
    UNREADABLE,

    /** No frame of the take can become a DNG. */
    NO_FRAMES,

    /** The destination lacks the space the plan needs. */
    NO_SPACE,
}

enum class CinemaDngStopReason { CANCELLED, RECORDING, FAILED }

internal sealed interface CinemaDngExportOutcome {
    data class Exported(
        val location: String,
        val frames: Int,
        /** Frames left out because they were not convertible (damaged, unknown encoding). */
        val skipped: Int,
        val audio: Boolean,
        /** The take had sound but it does not fit a WAV (4 GiB), so the folder holds none. */
        val audioOmitted: Boolean,
        val bytes: Long,
        /** False when the destination refused the `.nomedia`. */
        val noMedia: Boolean,
    ) : CinemaDngExportOutcome

    data class Refused(val reason: CinemaDngRefusal, val neededBytes: Long = 0, val freeBytes: Long? = null) : CinemaDngExportOutcome

    /** Stopped part-way; [cleaned] says whether the partial folder at [location] (null: none made) is gone. */
    data class Stopped(
        val reason: CinemaDngStopReason,
        val cleaned: Boolean,
        val detail: String? = null,
        val location: String? = null,
    ) : CinemaDngExportOutcome
}

/**
 * Exports one indexed `.ocraw` take as a CinemaDNG folder: `<take>_000000.dng`… (numbered by the
 * frame's place in the file, so skipped frames leave gaps, as the desktop converter does) and
 * `<take>.wav` when the take has sound. The bytes come from [CinemaDngWriter] and [OcrawWavWriter]
 * unchanged. Pure JVM: the destination, the cancel signal and the capture state are passed in.
 *
 * - A frame that is not convertible, or proves damaged while it is read, is skipped and counted.
 * - Cancelling, a take starting to record, or a write failing stops between frames and discards
 *   the partial folder.
 * - A corrupt audio chunk becomes silence of the same length, so the sound stays in sync.
 *
 * [lj92] re-encodes RAW10 and block-pack frames to lossless JPEG tiles. The caller owns [channel].
 */
internal class CinemaDngExporter(
    private val channel: FileChannel,
    private val takeName: String,
    private val lj92: CinemaDngWriter.Lj92Encoder? = null,
    private val decoderThreads: Int = 1,
) {
    private val index: OcrawFrameIndex by lazy { OcrawStreamReader(channel, scratchBytes = OcrawFrameHeader.BYTES).indexFrames() }

    /** The plan, or null when the take is unreadable (no header, or a CFA DNG cannot describe). */
    fun plan(): CinemaDngExportPlan? {
        if (index.header == null) return null
        val writer = try {
            CinemaDngWriter(channel, index, takeName)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return writer.use {
            var convertible = 0
            var fixed = 0L
            var reencoded = 0L
            var reencodedFrames = 0
            for (frame in 0 until writer.frameCount) {
                if (!writer.isConvertible(frame)) continue
                val size = try {
                    writer.sizeOf(frame)
                } catch (_: OcrawDngException) {
                    continue
                }
                convertible++
                // Without an encoder a block-pack frame is sized as its decoded, uncompressed DNG.
                if (index.frames[frame].header?.encoding != OcrawFrameHeader.ENCODING_LJ92) {
                    reencoded += size
                    reencodedFrames++
                } else {
                    fixed += size
                }
            }
            val wav = if (OcrawWavWriter.hasAudio(index)) {
                try { OcrawWavWriter(channel, index, corruptChunkAsSilence = true).size } catch (_: IllegalArgumentException) { null }
            } else {
                null
            }
            CinemaDngExportPlan(takeName, writer.frameCount, convertible, fixed, reencoded, reencodedFrames, wav)
        }
    }

    /** Runs the export to [destination]; never throws for an expected failure, see [CinemaDngExportOutcome]. */
    fun run(
        destination: CinemaDngDestination,
        isCancelled: () -> Boolean = { false },
        isRecording: () -> Boolean = { false },
        onProgress: (CinemaDngExportProgress) -> Unit = {},
    ): CinemaDngExportOutcome {
        if (isRecording()) return CinemaDngExportOutcome.Refused(CinemaDngRefusal.RECORDING)
        val plan = plan() ?: return CinemaDngExportOutcome.Refused(CinemaDngRefusal.UNREADABLE)
        if (plan.convertible == 0) return CinemaDngExportOutcome.Refused(CinemaDngRefusal.NO_FRAMES)
        val compress = lj92 != null
        val free = try { destination.freeBytes() } catch (_: Exception) { null }
        if (!plan.fits(compress, free)) return CinemaDngExportOutcome.Refused(CinemaDngRefusal.NO_SPACE, plan.bytes(compress), free)
        if (isCancelled()) return CinemaDngExportOutcome.Stopped(CinemaDngStopReason.CANCELLED, cleaned = true)

        val target = try {
            destination.open(takeName)
        } catch (failure: Exception) {
            return CinemaDngExportOutcome.Stopped(CinemaDngStopReason.FAILED, cleaned = true, failure.describe())
        }
        fun stop(reason: CinemaDngStopReason, detail: String? = null): CinemaDngExportOutcome {
            val cleaned = try { target.discard() } catch (_: Exception) { false }
            return CinemaDngExportOutcome.Stopped(reason, cleaned, detail, target.location)
        }
        fun interruption(): CinemaDngStopReason? = when {
            isCancelled() -> CinemaDngStopReason.CANCELLED
            isRecording() -> CinemaDngStopReason.RECORDING
            else -> null
        }

        try {
            val noMedia = try { target.writeNoMedia() } catch (_: Exception) { false }
            var written = 0
            var skipped = 0
            var bytes = 0L
            CinemaDngWriter(channel, index, takeName, lj92, decoderThreads).use { writer ->
                val total = writer.frameCount
                onProgress(CinemaDngExportProgress(0, total, 0, 0))
                for (frame in 0 until total) {
                    interruption()?.let { return stop(it) }
                    if (!writer.isConvertible(frame)) {
                        skipped++
                    } else {
                        try {
                            target.write(writer.fileName(frame), DNG_MIME_TYPE) { out -> bytes += writer.write(frame, out) }
                            written++
                        } catch (_: OcrawDngException) {
                            // The source frame is damaged; the target already removed the partial file.
                            skipped++
                        }
                    }
                    onProgress(CinemaDngExportProgress(frame + 1, total, skipped, bytes))
                }
            }
            if (written == 0) {
                stop(CinemaDngStopReason.FAILED)
                return CinemaDngExportOutcome.Refused(CinemaDngRefusal.NO_FRAMES)
            }
            var audio = false
            var audioOmitted = false
            if (OcrawWavWriter.hasAudio(index)) {
                interruption()?.let { return stop(it) }
                val wav = try { OcrawWavWriter(channel, index, corruptChunkAsSilence = true) } catch (_: IllegalArgumentException) { null }
                if (wav == null) {
                    audioOmitted = true
                } else {
                    target.write("$takeName.wav", WAV_MIME_TYPE) { out -> bytes += wav.write(out) }
                    audio = true
                }
            }
            interruption()?.let { return stop(it) }
            target.publish()
            return CinemaDngExportOutcome.Exported(target.location, written, skipped, audio, audioOmitted, bytes, noMedia)
        } catch (failure: Exception) {
            return stop(CinemaDngStopReason.FAILED, failure.describe())
        }
    }

    companion object {
        const val DNG_MIME_TYPE = "image/x-adobe-dng"
        const val WAV_MIME_TYPE = "audio/x-wav"
        const val NOMEDIA_MIME_TYPE = "application/octet-stream"
        const val NOMEDIA = ".nomedia"

        /** The take name a `.ocraw` file exports under: its stem. */
        fun takeName(fileName: String): String {
            val stem = fileName.substringAfterLast('/')
            return if (stem.endsWith(".ocraw", ignoreCase = true) && stem.length > ".ocraw".length) stem.dropLast(".ocraw".length) else stem
        }

        /** `<take>`, then `<take>_2`… : the first folder name [taken] does not claim. */
        fun folderName(takeName: String, taken: (String) -> Boolean): String {
            if (!taken(takeName)) return takeName
            for (n in 2..MAX_FOLDER_SUFFIX) {
                val candidate = "${takeName}_$n"
                if (!taken(candidate)) return candidate
            }
            throw IOException("every folder name for $takeName is taken")
        }

        private const val MAX_FOLDER_SUFFIX = 999
    }
}

private fun Exception.describe(): String = message ?: javaClass.simpleName
