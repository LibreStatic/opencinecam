/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.storage

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.Closeable
import java.io.IOException

/** The outcome of probing a picked folder. */
internal enum class DriveProbeResult { OK, NOT_WRITABLE, FILE_SIZE_LIMIT, NOT_ENOUGH_SPACE }

/** The rejection a failed probe maps to; null for [DriveProbeResult.OK]. */
internal fun DriveProbeResult.rejection(): RecordingFolderRejection? = when (this) {
    DriveProbeResult.OK -> null
    DriveProbeResult.NOT_WRITABLE -> RecordingFolderRejection.NOT_WRITABLE
    DriveProbeResult.FILE_SIZE_LIMIT -> RecordingFolderRejection.FILE_SIZE_LIMIT
    DriveProbeResult.NOT_ENOUGH_SPACE -> RecordingFolderRejection.NOT_ENOUGH_SPACE
}

/** The probe file's lifecycle, injectable so [runDriveProbe] runs on the host. */
internal interface DriveProbeTarget {
    /** Creates the probe file; false or a throw when the provider refuses. */
    fun create(): Boolean
    /** Opens the created file for reading and writing; throws when it cannot. */
    fun open(): DriveProbeFile
    /** Deletes the created file; failures are the target's to log. */
    fun delete()
}

internal interface DriveProbeFile : Closeable {
    /** Writes [bytes] at offset 0 and returns what reading them back gives. */
    fun writeAndReadBack(bytes: ByteArray): ByteArray
    /** Sets the file's length; throws [ErrnoException] when the file system refuses. */
    fun truncate(length: Long)
}

/**
 * The exFAT check: create a scratch file, write and read back a few bytes, then grow it past 4 GiB,
 * which FAT32 cannot hold. The scratch file is deleted whenever it was created, whatever happened.
 */
internal fun runDriveProbe(target: DriveProbeTarget): DriveProbeResult {
    val created = try { target.create() } catch (failure: Exception) { Log.i(LOG_TAG, "The probe file could not be created", failure); false }
    if (!created) return DriveProbeResult.NOT_WRITABLE
    try {
        val file = try { target.open() } catch (failure: Exception) {
            Log.i(LOG_TAG, "The probe file could not be opened", failure)
            return DriveProbeResult.NOT_WRITABLE
        }
        return file.use {
            val written = try { it.writeAndReadBack(PROBE_BYTES) } catch (failure: Exception) {
                Log.i(LOG_TAG, "The probe file could not be written", failure)
                return DriveProbeResult.NOT_WRITABLE
            }
            if (!written.contentEquals(PROBE_BYTES)) return DriveProbeResult.NOT_WRITABLE
            try {
                it.truncate(PROBE_LENGTH)
                DriveProbeResult.OK
            } catch (failure: ErrnoException) {
                Log.i(LOG_TAG, "The drive refused a ${PROBE_LENGTH}-byte file", failure)
                if (failure.errno == OsConstants.ENOSPC) DriveProbeResult.NOT_ENOUGH_SPACE else DriveProbeResult.FILE_SIZE_LIMIT
            } catch (failure: Exception) {
                Log.i(LOG_TAG, "The probe file could not be grown", failure)
                DriveProbeResult.NOT_WRITABLE
            }
        }
    } finally {
        try { target.delete() } catch (failure: Exception) { Log.w(LOG_TAG, "The probe file could not be deleted", failure) }
    }
}

/** 4 GiB and 1 MiB: past the largest file FAT32 holds. */
internal const val PROBE_LENGTH: Long = (4L shl 30) + (1L shl 20)

private val PROBE_BYTES = byteArrayOf(0x4F, 0x43, 0x43, 0x2D, 0x70, 0x72, 0x6F, 0x62, 0x65, 0x0A)
private const val LOG_TAG = "RecordingDriveProbe"

/**
 * Probes a picked tree through `DocumentsContract`: `.occ-probe` is created as
 * `application/octet-stream` so the provider adds no extension. Blocking, call it off the main thread.
 */
internal class RecordingDriveProbe(private val resolver: ContentResolver) {
    /** Probes of one tree run one at a time: they share the probe's name, so one would delete the other's file. */
    fun run(tree: Uri): DriveProbeResult =
        synchronized(treeLocks.computeIfAbsent(tree.toString()) { Any() }) { runDriveProbe(SafProbeTarget(tree)) }

    private inner class SafProbeTarget(private val tree: Uri) : DriveProbeTarget {
        private var document: Uri? = null

        override fun create(): Boolean {
            // A probe file an earlier probe could not delete would make the provider name this one `.occ-probe (1)`. Only
            // a file is ours: a folder of that name is the operator's and is never deleted.
            runCatching {
                val documents = SafDocuments(resolver, tree)
                documents.child(documents.root, PROBE_NAME)?.takeIf { it.mime != DocumentsContract.Document.MIME_TYPE_DIR }
                    ?.let { leftover -> documents.delete(leftover.uri) }
            }.onFailure { Log.w(LOG_TAG, "A leftover probe file could not be removed", it) }
            document = DocumentsContract.createDocument(resolver, treeDocumentUri(tree), PROBE_MIME_TYPE, PROBE_NAME)
            return document != null
        }

        override fun open(): DriveProbeFile {
            val uri = checkNotNull(document)
            val mark = DriveDescriptors.releaseMark(uri)
            val descriptor = resolver.openFileDescriptor(uri, "rw") ?: throw IOException("the probe file could not be opened")
            return DescriptorProbeFile(uri, descriptor, mark)
        }

        override fun delete() {
            document?.let { if (!DocumentsContract.deleteDocument(resolver, it)) Log.w(LOG_TAG, "The provider kept the probe file") }
        }
    }

    /** ADR-0037: registered with [DriveDescriptors] while open, so a drive pulled mid-probe has the descriptor closed. */
    private class DescriptorProbeFile(uri: Uri, private val descriptor: ParcelFileDescriptor, releaseMark: Long) : DriveProbeFile {
        private val closer = CloseOnce(descriptor)
        // Opened across a release of the drive: closed at once, so the probe's first write fails as on a pulled drive.
        private val driveHandle = DriveDescriptors.registerDocument(uri, closer, openedAfter = releaseMark)

        override fun writeAndReadBack(bytes: ByteArray): ByteArray {
            val fd = descriptor.fileDescriptor
            var written = 0
            while (written < bytes.size) written += Os.pwrite(fd, bytes, written, bytes.size - written, written.toLong())
            Os.fsync(fd)
            val back = ByteArray(bytes.size)
            var read = 0
            while (read < back.size) {
                val count = Os.pread(fd, back, read, back.size - read, read.toLong())
                if (count <= 0) break
                read += count
            }
            return back.copyOf(read)
        }

        override fun truncate(length: Long) {
            val fd = descriptor.fileDescriptor
            Os.ftruncate(fd, length)
            // A file system that clamps the length silently is as unfit as one that refuses.
            if (Os.fstat(fd).st_size != length) throw ErrnoException("ftruncate", OsConstants.EFBIG)
        }

        override fun close() {
            try { closer.close() } finally { driveHandle.unregister() }
        }
    }

    private companion object {
        /** One per tree probed in this process; picked folders are few, so they are kept. */
        val treeLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()
        const val PROBE_NAME = ".occ-probe"
        const val PROBE_MIME_TYPE = "application/octet-stream"
    }
}
