/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.transfers

import android.content.Context
import android.util.AtomicFile
import com.librestatic.opencinecam.storage.CapturePublicationObserver
import com.librestatic.opencinecam.storage.PreparedCaptureArtifact
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID

/**
 * Private publication receipt, independent of SQLite and networking. The finalizer isolates these
 * callbacks' errors so a journal failure never deletes a valid capture. One application process
 * owns this directory; the process-wide lock serializes observer instances and capacity checks.
 * This journal does not finish pending MediaStore rows, infer publication, upload, or purge history.
 */
class CapturePublicationJournal internal constructor(
    private val directory: File,
    val bundleId: String,
    private val capacity: Int = MAX_RECEIPTS,
) : CapturePublicationObserver {
    constructor(context: Context, bundleId: String = UUID.randomUUID().toString()) :
        this(File(context.applicationContext.noBackupFilesDir, DIRECTORY_NAME), bundleId)

    init { requirePublicationUuid(bundleId); require(capacity in 1..MAX_RECEIPTS) }
    private val atomic = AtomicFile(File(directory, "$bundleId.json"))

    fun load(): CapturePublicationReceipt? = synchronized(processLock) { readReceipt(directory, bundleId) }

    override fun onPrepared(artifacts: List<PreparedCaptureArtifact>) = update { previous ->
        CapturePublicationTransitions.prepared(bundleId, previous, artifacts.toList())
    }

    override fun onPublished(artifacts: List<PreparedCaptureArtifact>) = update { previous ->
        CapturePublicationTransitions.published(bundleId, previous, artifacts.toList())
    }

    override fun onAborted() = update { previous -> CapturePublicationTransitions.aborted(bundleId, previous) }

    private fun update(transition: (CapturePublicationReceipt?) -> CapturePublicationReceipt) = synchronized(processLock) {
        val previous = readReceipt(directory, bundleId)
        val next = transition(previous)
        if (previous == next) return@synchronized
        if (!directory.exists() && !directory.mkdirs()) throw IOException("Publication journal directory is unavailable")
        if (!directory.isDirectory) throw IOException("Publication journal directory is unavailable")
        val existing = receiptIds(directory, capacity)
        if (bundleId !in existing && existing.size >= capacity) throw CapturePublicationJournalCapacity()
        val bytes = CapturePublicationJournalCodec.encode(next)
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            atomic.finishWrite(stream)
            if (readReceipt(directory, bundleId) != next) throw IOException("Publication journal readback mismatch")
        } catch (failure: Exception) {
            atomic.failWrite(stream)
            throw failure
        }
    }

    companion object {
        const val MAX_RECEIPTS = 1024
        private const val DIRECTORY_NAME = "capture-publication"
        private val processLock = Any()

        fun load(context: Context, bundleId: String): CapturePublicationReceipt? =
            CapturePublicationJournal(context, bundleId).load()

        /** Bounded page ordered by opaque UUID. No entry is silently dropped on corruption. */
        fun list(context: Context, limit: Int = 100, afterBundleId: String? = null): List<CapturePublicationReceipt> =
            listDirectory(File(context.applicationContext.noBackupFilesDir, DIRECTORY_NAME), limit, afterBundleId)

        internal fun listDirectory(directory: File, limit: Int = 100, afterBundleId: String? = null,
            capacity: Int = MAX_RECEIPTS): List<CapturePublicationReceipt> = synchronized(processLock) {
            require(limit in 1..MAX_RECEIPTS && capacity in 1..MAX_RECEIPTS)
            afterBundleId?.let(::requirePublicationUuid)
            receiptIds(directory, capacity).sorted().asSequence()
                .filter { afterBundleId == null || it > afterBundleId }.take(limit)
                .map { readReceipt(directory, it) ?: throw CapturePublicationJournalCorruptData() }.toList()
        }

        private fun readReceipt(directory: File, id: String): CapturePublicationReceipt? {
            if (!directory.exists()) return null
            if (!directory.isDirectory) throw IOException("Publication journal directory is unavailable")
            val base = File(directory, "$id.json")
            val backup = File(directory, "$id.json.bak")
            val pending = File(directory, "$id.json.new")
            if (!base.exists() && !backup.exists()) {
                if (pending.exists()) throw CapturePublicationJournalCorruptData()
                return null
            }
            val bytes = AtomicFile(base).openRead().use { input ->
                val buffer = ByteArray(CapturePublicationJournalCodec.MAX_BYTES + 1)
                var size = 0
                while (size < buffer.size) {
                    val count = input.read(buffer, size, buffer.size - size)
                    if (count < 0) break
                    if (count == 0) throw IOException("Publication journal read made no progress")
                    size += count
                }
                if (size > CapturePublicationJournalCodec.MAX_BYTES) throw CapturePublicationJournalCorruptData()
                buffer.copyOf(size)
            }
            return CapturePublicationJournalCodec.decode(bytes).also { if (it.bundleId != id) throw CapturePublicationJournalCorruptData() }
        }

        private fun receiptIds(directory: File, capacity: Int): Set<String> {
            if (!directory.exists()) return emptySet()
            if (!directory.isDirectory) throw IOException("Publication journal directory is unavailable")
            val ids = linkedSetOf<String>()
            Files.newDirectoryStream(directory.toPath()).use { stream ->
                var entries = 0
                for (path in stream) {
                    if (++entries > capacity * 3) throw CapturePublicationJournalCapacity()
                    if (!Files.isRegularFile(path)) throw CapturePublicationJournalCorruptData()
                    val name = path.fileName.toString()
                    val base = when {
                        name.endsWith(".json.bak") -> name.removeSuffix(".json.bak")
                        name.endsWith(".json.new") -> name.removeSuffix(".json.new")
                        name.endsWith(".json") -> name.removeSuffix(".json")
                        else -> throw CapturePublicationJournalCorruptData()
                    }
                    try { requirePublicationUuid(base) } catch (_: IllegalArgumentException) { throw CapturePublicationJournalCorruptData() }
                    ids.add(base)
                    if (ids.size > capacity) throw CapturePublicationJournalCapacity()
                }
            }
            return ids
        }
    }
}
