/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.transfers

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Private, write-once REC enrollments, independent of publication and the transfer queue.
 * All instances in this application process share a lock. Capacity includes unfinished records;
 * nothing is purged or rebound automatically. Damaged/unknown bytes remain available for recovery.
 */
class CaptureTransferEnrollmentFile internal constructor(
    private val directory: File,
    private val capacity: Int = MAX_ENROLLMENTS,
) : CaptureTransferEnrollmentStore {
    constructor(context: Context) : this(File(context.applicationContext.noBackupFilesDir, DIRECTORY_NAME))

    init { require(capacity in 1..MAX_ENROLLMENTS) }

    override fun load(bundleId: String): CaptureTransferEnrollment? = synchronized(processLock) {
        requireEnrollmentUuid(bundleId)
        readEnrollment(bundleId)
    }

    override fun enroll(value: CaptureTransferEnrollment): CaptureTransferEnrollment = synchronized(processLock) {
        readEnrollment(value.bundleId)?.let { previous ->
            if (previous != value) throw CaptureTransferEnrollmentConflict()
            return@synchronized previous
        }
        if (!directory.exists() && !directory.mkdirs()) throw IOException("Enrollment directory is unavailable")
        requireDirectory()
        val ids = enrollmentIds()
        if (value.bundleId !in ids && ids.size >= capacity) throw CaptureTransferEnrollmentCapacity()
        val atomic = AtomicFile(File(directory, "${value.bundleId}.json"))
        val stream = atomic.startWrite()
        var finishAttempted = false
        try {
            stream.write(CaptureTransferEnrollmentCodec.encode(value))
            stream.fd.sync()
            finishAttempted = true
            atomic.finishWrite(stream)
            if (readEnrollment(value.bundleId) != value) throw IOException("Enrollment readback mismatch")
        } catch (failure: Exception) {
            // Before finishWrite, this invocation owns its unfinished write. After it, preserve
            // any evidence of a failed rename/readback instead of silently deleting that evidence.
            if (!finishAttempted) atomic.failWrite(stream)
            throw failure
        }
        value
    }

    private fun requireDirectory() {
        if (!Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) throw IOException("Enrollment directory is unavailable")
    }

    private fun readEnrollment(bundleId: String): CaptureTransferEnrollment? {
        if (!Files.exists(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
        requireDirectory()
        val base = File(directory, "$bundleId.json")
        val paths = listOf(base, File(directory, "$bundleId.json.new"), File(directory, "$bundleId.json.bak"))
        // AtomicFile.openRead may delete .new or restore .bak. A write-once enrollment has no
        // legitimate update to infer: preserve these bytes and demand explicit recovery instead.
        if (paths.drop(1).any { Files.exists(it.toPath(), LinkOption.NOFOLLOW_LINKS) }) throw CaptureTransferEnrollmentCorruptData()
        if (!Files.exists(base.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
        if (!Files.isRegularFile(base.toPath(), LinkOption.NOFOLLOW_LINKS)) throw CaptureTransferEnrollmentCorruptData()
        val bytes = base.inputStream().use { input ->
            val buffer = ByteArray(CaptureTransferEnrollmentCodec.MAX_BYTES + 1)
            var size = 0
            while (size < buffer.size) {
                val count = input.read(buffer, size, buffer.size - size)
                if (count < 0) break
                if (count == 0) throw IOException("Enrollment read made no progress")
                size += count
            }
            if (size > CaptureTransferEnrollmentCodec.MAX_BYTES) throw CaptureTransferEnrollmentCorruptData()
            buffer.copyOf(size)
        }
        return CaptureTransferEnrollmentCodec.decode(bytes).also {
            if (it.bundleId != bundleId) throw CaptureTransferEnrollmentCorruptData()
        }
    }

    private fun enrollmentIds(): Set<String> {
        val ids = linkedSetOf<String>()
        Files.newDirectoryStream(directory.toPath()).use { stream ->
            var entries = 0
            for (path in stream) {
                if (++entries > capacity * 3) throw CaptureTransferEnrollmentCapacity()
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw CaptureTransferEnrollmentCorruptData()
                val name = path.fileName.toString()
                val id = when {
                    name.endsWith(".json.new") -> name.removeSuffix(".json.new")
                    name.endsWith(".json.bak") -> name.removeSuffix(".json.bak")
                    name.endsWith(".json") -> name.removeSuffix(".json")
                    else -> throw CaptureTransferEnrollmentCorruptData()
                }
                try { requireEnrollmentUuid(id) } catch (_: IllegalArgumentException) { throw CaptureTransferEnrollmentCorruptData() }
                ids.add(id)
                if (ids.size > capacity) throw CaptureTransferEnrollmentCapacity()
            }
        }
        return ids
    }

    companion object {
        const val MAX_ENROLLMENTS = 1024
        private const val DIRECTORY_NAME = "capture-transfer-enrollment"
        private val processLock = Any()
    }
}
