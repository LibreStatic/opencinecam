/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.transfers

import android.content.ContentResolver
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import androidx.core.net.toUri
import com.librestatic.opencinecam.storage.CaptureArtifactRole
import com.librestatic.opencinecam.storage.CloseOnce
import com.librestatic.opencinecam.storage.DriveDescriptors
import com.librestatic.opencinecam.storage.PreparedCaptureArtifact
import com.librestatic.opencinecam.storage.isDriveUri
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

/**
 * Strict source for all published take roles, on the phone or (PLAN-072) on the USB drive: [CaptureArtifactProbe]
 * picks the probe by URI, and the descriptor checks below hold for either.
 * Each source owns only its independently opened
 * read-only descriptor; the provider's rows and the capture writer are never modified or closed. A descriptor on the
 * drive is registered with [DriveDescriptors] while it is open, so a drive that leaves mid-upload has it closed (ADR-0037)
 * even while the upload is blocked on the network, and the stream then fails as [WebDavSourceDriveAbsent].
 */
internal class WebDavMediaStoreArtifactSource(
    private val resolver: ContentResolver,
) : WebDavArtifactSourceFactory {
    override fun source(spec: WebDavArtifactSpec): WebDavClipSource = Source(spec)

    private inner class Source(private val spec: WebDavArtifactSpec) : WebDavClipSource {
        private val prepared = PreparedCaptureArtifact(
            when (spec.role) {
                WebDavArtifactRole.VIDEO -> CaptureArtifactRole.VIDEO
                WebDavArtifactRole.VIDEO_METADATA -> CaptureArtifactRole.VIDEO_METADATA
                WebDavArtifactRole.AUDIO -> CaptureArtifactRole.AUDIO
                WebDavArtifactRole.AUDIO_METADATA -> CaptureArtifactRole.AUDIO_METADATA
                WebDavArtifactRole.CLIP_METADATA -> CaptureArtifactRole.CLIP_METADATA
            }, spec.sourceUri, spec.sourceName,
        )
        private val probe = CaptureArtifactProbe(resolver)
        private val onDrive = isDriveUri(spec.sourceUri)

        /** A drive pulled out mid-read fails with a plain I/O error: report it as the transient [WebDavSourceDriveAbsent]. */
        private fun <T> reading(block: () -> T): T = try { block() } catch (failure: IOException) {
            if (!onDrive || failure is WebDavSourceDriveAbsent || failure is WebDavArtifactContentChanged) throw failure
            throw absentOr(resolver, spec.sourceUri.toUri(), failure)
        }

        override fun snapshot(): WebDavClipSnapshot = probe.inspect(prepared, pending = false).also {
            requireHashSnapshot(spec, it)
        }

        override fun open(): InputStream {
            val before = snapshot()
            val descriptor = reading {
                resolver.openFileDescriptor(spec.sourceUri.toUri(), "r") ?: throw FileNotFoundException("Published artifact descriptor is unavailable")
            }
            val closer = CloseOnce(descriptor)
            val driveHandle = DriveDescriptors.registerDocument(spec.sourceUri.toUri(), closer)
            try {
                requireReadOnlyPreparedDescriptor(descriptor)
                val first = Os.fstat(descriptor.fileDescriptor)
                requireStat(descriptor, first, before.sizeBytes)
                if (snapshot() != before || !sameFile(first, Os.fstat(descriptor.fileDescriptor))) throw WebDavArtifactContentChanged()
                val input = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
                return object : InputStream() {
                    private var closed = false
                    /** The drive left and [DriveDescriptors] closed the descriptor: nothing more can be read or checked. */
                    private fun requireHeld() { if (closer.closed) throw WebDavSourceDriveAbsent() }
                    /** A read that fails on a descriptor [DriveDescriptors] closed meanwhile is the drive leaving too. */
                    private fun <T> held(read: () -> T): T {
                        check(!closed)
                        requireHeld()
                        return try { reading(read) } catch (failure: IOException) {
                            if (closer.closed && failure !is WebDavSourceDriveAbsent) throw WebDavSourceDriveAbsent(failure) else throw failure
                        }
                    }
                    override fun read(): Int = held { input.read() }
                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int = held { input.read(bytes, offset, length) }
                    override fun close() {
                        if (closed) return
                        closed = true
                        // Registered until the descriptor is closed: the checks below still query the drive.
                        try {
                            if (closer.closed) throw WebDavSourceDriveAbsent()
                            var failure: Throwable? = null
                            try {
                                requireReadOnlyPreparedDescriptor(descriptor)
                                val last = Os.fstat(descriptor.fileDescriptor)
                                requireStat(descriptor, last, before.sizeBytes)
                                if (!sameFile(first, last) || snapshot() != before ||
                                    !sameFile(first, Os.fstat(descriptor.fileDescriptor))) throw WebDavArtifactContentChanged()
                            } catch (problem: Throwable) { failure = problem }
                            if (closer.closed) {
                                failure = driveReleasedOr(failure, released = true)
                            } else {
                                try { closer.close() } catch (problem: Throwable) {
                                    if (failure == null) failure = problem else failure.addSuppressed(problem)
                                }
                            }
                            failure?.let { throw it }
                        } finally {
                            driveHandle.unregister()
                        }
                    }
                }
            } catch (failure: Throwable) {
                // Read before this close: already closed means the drive left during the checks.
                val released = onDrive && closer.closed
                try {
                    try { closer.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                } finally {
                    driveHandle.unregister()
                }
                throw driveReleasedOr(failure, released) ?: failure
            }
        }
    }

    private fun requireStat(descriptor: ParcelFileDescriptor, stat: StructStat, expectedSize: Long) {
        if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_size != expectedSize ||
            descriptor.statSize != expectedSize || stat.st_nlink <= 0) throw WebDavArtifactContentChanged()
    }

    private fun sameFile(first: StructStat, last: StructStat): Boolean =
        first.st_dev == last.st_dev && first.st_ino == last.st_ino && first.st_mode == last.st_mode &&
            first.st_nlink == last.st_nlink && first.st_size == last.st_size &&
            first.st_mtim.tv_sec == last.st_mtim.tv_sec && first.st_mtim.tv_nsec == last.st_mtim.tv_nsec &&
            first.st_ctim.tv_sec == last.st_ctim.tv_sec && first.st_ctim.tv_nsec == last.st_ctim.tv_nsec
}
