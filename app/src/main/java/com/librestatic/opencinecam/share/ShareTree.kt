/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.share

import com.librestatic.opencinecam.media.ocraw.CinemaDngWriter
import com.librestatic.opencinecam.media.ocraw.OcrawFrameIndex
import com.librestatic.opencinecam.media.ocraw.OcrawStreamReader
import com.librestatic.opencinecam.media.ocraw.OcrawWavWriter
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.WritableByteChannel
import java.text.Normalizer

/** An open, read-only `.ocraw` file; [close] releases its descriptor. */
internal interface ShareOpenFile : Closeable {
    val channel: FileChannel
}

/** One RAW take as the catalog lists it. [key] identifies the row (its content URI). */
internal class ShareTake(
    val key: String,
    /** The `.ocraw` display name, e.g. `A001_C001.ocraw`. */
    val fileName: String,
    val size: Long,
    val modifiedMs: Long,
    private val opener: () -> ShareOpenFile,
) {
    /** The folder name: the file name without `.ocraw`. */
    val stem: String get() = fileName.substring(0, fileName.length - ShareTree.OCRAW_EXTENSION.length)
    fun open(): ShareOpenFile = opener()
}

/** A take could not be indexed (now, or within the last [ShareTree.FAILURE_TTL_MS]). */
internal class ShareIndexException(message: String, cause: Throwable?) : IOException(message, cause)

/** Lists the RAW takes the share offers; the app's implementation reads MediaStore. */
internal fun interface ShareTakeSource {
    fun takes(): List<ShareTake>
}

/** A node of the read-only virtual tree. */
internal sealed interface ShareNode {
    val name: String
    val modifiedMs: Long
}

/** `/` (empty [name]) or `/<take>/`. */
internal class ShareCollection(override val name: String, override val modifiedMs: Long, val take: ShareTake?) : ShareNode

/** A file of a take folder: a DNG frame, the WAV or (when shown) the original `.ocraw`. */
internal class ShareFile(
    override val name: String,
    override val modifiedMs: Long,
    val size: Long,
    val contentType: String,
    val etag: String,
    private val content: ShareContent,
) : ShareNode {
    /** Writes bytes `[offset, offset + length)` to [out]; opens and closes its own descriptor. */
    fun writeRange(offset: Long, length: Long, out: WritableByteChannel) {
        require(offset >= 0 && length >= 0 && offset + length <= size) { "range outside the file" }
        if (length == 0L) return
        content.writeRange(offset, length, out)
    }
}

internal fun interface ShareContent {
    fun writeRange(offset: Long, length: Long, out: WritableByteChannel)
}

/**
 * The share's read-only virtual tree (OCC-PLAN-071 §8, route 3):
 *
 * - `/` lists one folder per RAW take, named by the `.ocraw` stem;
 * - `/<take>/` holds `<take>_000000.dng …` (convertible frames only, generated on the fly by
 *   [CinemaDngWriter]; RAW10 becomes uncompressed and LJ92 passes through), `<take>.wav`
 *   when the take has sound ([OcrawWavWriter], a corrupt chunk as silence so the size holds),
 *   and the original `<take>.ocraw` only while [includeOriginal] says so.
 *
 * [includeOriginal] is read on every listing and lookup, so the operator's choice applies to the
 * next request without a restart; the cached index always holds the original and only the view
 * filters it. Hidden, the original is a plain miss (404), GET and HEAD included. It is hidden
 * by default because it is as large as the take and Windows Explorer cannot copy past 4 GB.
 *
 * Names resolve by exact lookup in maps built from the catalog (NFC-normalised); a request
 * never builds a filesystem path. The take list is cached for [listTtlMs]; per-take indexes
 * and frame sizes live in an LRU of [indexCacheSize] takes, keyed by the row and its
 * size/modification so a changed file is indexed again. Every read opens its own descriptor.
 */
internal class ShareTree(
    private val source: ShareTakeSource,
    private val clock: ShareClock = ShareClock.MONOTONIC,
    private val listTtlMs: Long = 3_000,
    private val indexCacheSize: Int = 4,
    private val failureTtlMs: Long = FAILURE_TTL_MS,
    private val includeOriginal: () -> Boolean = { false },
) {
    private class Listing(val atMs: Long, val byName: Map<String, ShareTake>, val ordered: List<ShareTake>)

    /**
     * A take's indexed contents; [files] (DNG frames and WAV) in listing order, by NFC name in
     * [byName]; the [original] apart, so the view can leave it out.
     */
    private class TakeContents(val files: List<ShareFile>, val byName: Map<String, ShareFile>, val original: ShareFile, val originalName: String)

    private val lock = Any()
    private var listing: Listing? = null
    private val contents = object : LinkedHashMap<String, TakeContents>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TakeContents>?) = size > indexCacheSize
    }
    /** Takes whose indexing failed, by cache key, with the time: not retried for [failureTtlMs]. */
    private val failures = object : LinkedHashMap<String, Long>(8, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 64
    }
    /** One builder at a time per take, so parallel PROPFINDs do not index the same file twice. */
    private val building = HashMap<String, Any>()

    fun root(): ShareCollection = ShareCollection("", listing().ordered.maxOfOrNull { it.modifiedMs } ?: 0L, null)

    /** The take folders, newest first as the catalog orders them. */
    fun takeFolders(): List<ShareCollection> = listing().ordered.map { ShareCollection(it.stem, it.modifiedMs, it) }

    /** The files of [take]'s folder; indexes the take on first use. */
    fun files(take: ShareTake): List<ShareFile> {
        val contents = contentsOf(take)
        return if (includeOriginal()) contents.files + contents.original else contents.files
    }

    /** The node at [path], or null when the tree has no such name. */
    fun resolve(path: SharePath): ShareNode? {
        val segments = path.segments
        if (segments.isEmpty()) return root()
        val take = listing().byName[segments[0]] ?: return null
        if (segments.size == 1) return ShareCollection(take.stem, take.modifiedMs, take)
        if (segments.size > 2) return null
        // Probes for desktop.ini, .DS_Store, ._* and the like miss here without indexing anything.
        if (!looksLikeTakeFile(take.stem, segments[1])) return null
        val showOriginal = includeOriginal()
        // A hidden original misses without indexing the take.
        if (!showOriginal && segments[1].endsWith(OCRAW_EXTENSION, ignoreCase = true)) return null
        val contents = contentsOf(take)
        if (segments[1] == contents.originalName) return if (showOriginal) contents.original else null
        return contents.byName[segments[1]]
    }

    private fun looksLikeTakeFile(stem: String, name: String): Boolean {
        val normalized = Normalizer.normalize(stem, Normalizer.Form.NFC)
        return name.startsWith(normalized) && (name.endsWith(".dng") || name.endsWith(".wav") || name.endsWith(OCRAW_EXTENSION, ignoreCase = true))
    }

    private fun listing(): Listing {
        val now = clock.nowMs()
        synchronized(lock) { listing?.takeIf { now - it.atMs < listTtlMs }?.let { return it } }
        val takes = source.takes()
        val byName = LinkedHashMap<String, ShareTake>()
        for (take in takes) {
            if (!take.fileName.endsWith(OCRAW_EXTENSION, ignoreCase = true) || take.fileName.length <= OCRAW_EXTENSION.length) continue
            val stem = take.stem
            if (stem.any { it == '/' || it == '\\' || it.code < 0x20 || it.code == 0x7f } || stem == "." || stem == "..") continue
            byName.putIfAbsent(Normalizer.normalize(stem, Normalizer.Form.NFC), take) // the first (newest) of a duplicate wins
        }
        val fresh = Listing(now, byName, byName.values.toList())
        synchronized(lock) { listing = fresh }
        return fresh
    }

    private fun cacheKey(take: ShareTake) = "${take.key}|${take.size}|${take.modifiedMs}"

    /**
     * The take's indexed contents. A failure (unreadable or corrupt file) throws
     * [ShareIndexException] and is remembered for [failureTtlMs], so a client retrying a broken
     * take does not make the phone read it again and again.
     */
    private fun contentsOf(take: ShareTake): TakeContents {
        val key = cacheKey(take)
        synchronized(lock) {
            contents[key]?.let { return it }
            checkNotFailed(key)
        }
        val gate = synchronized(lock) { building.getOrPut(key) { Any() } }
        try {
            synchronized(gate) {
                synchronized(lock) {
                    contents[key]?.let { return it }
                    checkNotFailed(key)
                }
                val built = try {
                    index(take)
                } catch (error: Exception) {
                    if (error is InterruptedException) throw error
                    synchronized(lock) { failures[key] = clock.nowMs() }
                    throw ShareIndexException("indexing failed", error)
                }
                synchronized(lock) { contents[key] = built }
                return built
            }
        } finally {
            synchronized(lock) { building.remove(key) }
        }
    }

    /** Under [lock]. */
    private fun checkNotFailed(key: String) {
        val at = failures[key] ?: return
        if (clock.nowMs() - at < failureTtlMs) throw ShareIndexException("indexing failed recently", null)
        failures.remove(key)
    }

    private fun index(take: ShareTake): TakeContents {
        val stem = take.stem
        val files = ArrayList<ShareFile>()
        val tag = take.key.hashCode().toUInt().toString(16)
        take.open().use { file ->
            val index = OcrawStreamReader(file.channel, scratchBytes = INDEX_SCRATCH_BYTES).indexFrames()
            CinemaDngWriter(file.channel, index, stem).use { writer ->
                for (frame in 0 until writer.frameCount) {
                    if (!writer.isConvertible(frame)) continue
                    // A frame whose strip table is invalid is left out, as the converter leaves it.
                    val size = try { writer.sizeOf(frame) } catch (_: IOException) { continue } catch (_: RuntimeException) { continue }
                    val name = writer.fileName(frame)
                    files += ShareFile(name, take.modifiedMs, size, DNG_TYPE, etag(tag, take, frame.toLong(), size),
                        DngContent(take, index, stem, frame))
                }
            }
            if (OcrawWavWriter.hasAudio(index)) {
                val wavSize = try { OcrawWavWriter(file.channel, index, corruptChunkAsSilence = true).size } catch (_: IllegalArgumentException) { null }
                if (wavSize != null) files += ShareFile("$stem.wav", take.modifiedMs, wavSize, WAV_TYPE, etag(tag, take, -1, wavSize),
                    WavContent(take, index))
            }
        }
        val original = ShareFile(take.fileName, take.modifiedMs, take.size, OCRAW_TYPE, etag(tag, take, -2, take.size), OriginalContent(take))
        val byName = files.associateBy { Normalizer.normalize(it.name, Normalizer.Form.NFC) }
        return TakeContents(files, byName, original, Normalizer.normalize(take.fileName, Normalizer.Form.NFC))
    }

    private fun etag(tag: String, take: ShareTake, part: Long, size: Long) =
        "\"$tag-${take.modifiedMs.toString(16)}-${part.toString(16)}-${size.toString(16)}\""

    private class DngContent(val take: ShareTake, val index: OcrawFrameIndex, val stem: String, val frame: Int) : ShareContent {
        override fun writeRange(offset: Long, length: Long, out: WritableByteChannel) {
            take.open().use { file ->
                CinemaDngWriter(file.channel, index, stem).use { it.writeRange(frame, offset, length, out) }
            }
        }
    }

    private class WavContent(val take: ShareTake, val index: OcrawFrameIndex) : ShareContent {
        override fun writeRange(offset: Long, length: Long, out: WritableByteChannel) {
            take.open().use { file ->
                OcrawWavWriter(file.channel, index, corruptChunkAsSilence = true).writeRange(offset, length, out)
            }
        }
    }

    private class OriginalContent(val take: ShareTake) : ShareContent {
        override fun writeRange(offset: Long, length: Long, out: WritableByteChannel) {
            take.open().use { file ->
                val buffer = ByteBuffer.allocate(COPY_BUFFER_BYTES)
                var position = offset
                val end = offset + length
                while (position < end) {
                    buffer.clear().limit(minOf(buffer.capacity().toLong(), end - position).toInt())
                    val n = file.channel.read(buffer, position)
                    if (n < 0) throw IOException("the take ended at $position, before ${take.size}")
                    buffer.flip()
                    while (buffer.hasRemaining()) out.write(buffer)
                    position += n
                }
            }
        }
    }

    companion object {
        const val OCRAW_EXTENSION = ".ocraw"
        const val DNG_TYPE = "image/x-adobe-dng"
        const val WAV_TYPE = "audio/wav"
        const val OCRAW_TYPE = "application/octet-stream"
        const val FAILURE_TTL_MS = 30_000L
        private const val INDEX_SCRATCH_BYTES = 256 * 1024
        private const val COPY_BUFFER_BYTES = 256 * 1024
    }
}
