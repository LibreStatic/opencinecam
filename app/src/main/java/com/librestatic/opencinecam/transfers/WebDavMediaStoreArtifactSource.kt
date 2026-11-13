/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.transfers

import android.content.ContentResolver
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import androidx.core.net.toUri
import com.librestatic.opencinecam.storage.CaptureArtifactRole
import com.librestatic.opencinecam.storage.CloseOnce
import com.librestatic.opencinecam.storage.DriveDescriptors
import com.librestatic.opencinecam.storage.PreparedCaptureArtifact
import com.librestatic.opencinecam.storage.heldReadWriteTrees
import com.librestatic.opencinecam.storage.isDriveUri
import com.librestatic.opencinecam.storage.resolveDriveLocation
import com.librestatic.opencinecam.storage.volumeIdOfTree
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
 *
 * A drive artifact keeps the URI it was enrolled under, and is read under the same folder of the same drive mounted
 * under its other volume name (vold's `-01` rename, [resolveDriveLocation]) when the recorded volume is not [mounted]
 * and that tree's grant is held. The snapshot still names the enrolled URI, so the bound row's checks are unchanged.
 */
internal class WebDavMediaStoreArtifactSource(
    private val resolver: ContentResolver,
    private val mounted: (String) -> Boolean = { true },
) : WebDavArtifactSourceFactory {
    override fun source(spec: WebDavArtifactSpec): WebDavClipSource = Source(spec)

    private inner class Source(private val spec: WebDavArtifactSpec) : WebDavClipSource {
        private val role = when (spec.role) {
            WebDavArtifactRole.VIDEO -> CaptureArtifactRole.VIDEO
            WebDavArtifactRole.VIDEO_METADATA -> CaptureArtifactRole.VIDEO_METADATA
            WebDavArtifactRole.AUDIO -> CaptureArtifactRole.AUDIO
            WebDavArtifactRole.AUDIO_METADATA -> CaptureArtifactRole.AUDIO_METADATA
            WebDavArtifactRole.CLIP_METADATA -> CaptureArtifactRole.CLIP_METADATA
        }
        private val probe = CaptureArtifactProbe(resolver)
        private val onDrive = isDriveUri(spec.sourceUri)

        /** Where the artifact is read now: as enrolled, or under the drive's mounted alias tree (see the class). */
        private fun readUri(): String {
            if (!onDrive) return spec.sourceUri
            val document = spec.sourceUri.toUri()
            val tree = runCatching {
                DocumentsContract.buildTreeDocumentUri(document.authority, DocumentsContract.getTreeDocumentId(document))
            }.getOrNull() ?: return spec.sourceUri
            val volume = volumeIdOfTree(tree) ?: return spec.sourceUri
            return resolveDriveLocation(tree, volume, document, heldReadWriteTrees(resolver), mounted)?.folder?.toString() ?: spec.sourceUri
        }

        /** A drive pulled out mid-read fails with a plain I/O error: report it as the transient [WebDavSourceDriveAbsent]. */
        private fun <T> reading(uri: String, block: () -> T): T = try { block() } catch (failure: IOException) {
            if (!onDrive || failure is WebDavSourceDriveAbsent || failure is WebDavArtifactContentChanged) throw failure
            throw absentOr(resolver, uri.toUri(), failure)
        }

        /**
         * Whether [failure] of a descriptor check is the drive leaving: [DriveDescriptors] closed the descriptor, or the
         * drive stopped answering before it did (fstat then fails with an errno, which is no [IOException]). A failure
         * while the drive still answers stays what it is.
         */
        private fun driveLeft(uri: String, failure: Throwable?, descriptorReleased: Boolean): Boolean = onDrive && (descriptorReleased ||
            (failure != null && failure !is WebDavSourceDriveAbsent && !driveAnswers(resolver, uri.toUri())))

        override fun snapshot(): WebDavClipSnapshot = snapshot(readUri())

        /** The artifact inspected at [uri], named by its enrolled URI for the bound row's checks. */
        private fun snapshot(uri: String): WebDavClipSnapshot =
            probe.inspect(PreparedCaptureArtifact(role, uri, spec.sourceName), pending = false)
                .let { if (uri == spec.sourceUri) it else it.copy(identity = spec.sourceUri) }
                .also { requireHashSnapshot(spec, it) }

        override fun open(): InputStream {
            // One location for the whole read: a remount meanwhile fails the checks rather than mixing two trees.
            val uri = readUri()
            val before = snapshot(uri)
            val descriptor = reading(uri) {
                resolver.openFileDescriptor(uri.toUri(), "r") ?: throw FileNotFoundException("Published artifact descriptor is unavailable")
            }
            val closer = CloseOnce(descriptor)
            val driveHandle = DriveDescriptors.registerDocument(uri.toUri(), closer)
            try {
                requireReadOnlyPreparedDescriptor(descriptor)
                val first = Os.fstat(descriptor.fileDescriptor)
                requireStat(descriptor, first, before.sizeBytes)
                if (snapshot(uri) != before || !sameFile(first, Os.fstat(descriptor.fileDescriptor))) throw WebDavArtifactContentChanged()
                val input = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
                return object : InputStream() {
                    private var closed = false
                    /** The drive left and [DriveDescriptors] closed the descriptor: nothing more can be read or checked. */
                    private fun requireHeld() { if (closer.closed) throw WebDavSourceDriveAbsent() }
                    /** A read that fails on a descriptor [DriveDescriptors] closed meanwhile is the drive leaving too. */
                    private fun <T> held(read: () -> T): T {
                        check(!closed)
                        requireHeld()
                        return try { reading(uri, read) } catch (failure: IOException) {
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
                                if (!sameFile(first, last) || snapshot(uri) != before ||
                                    !sameFile(first, Os.fstat(descriptor.fileDescriptor))) throw WebDavArtifactContentChanged()
                            } catch (problem: Throwable) { failure = problem }
                            if (closer.closed) {
                                failure = driveReleasedOr(failure, released = true)
                            } else {
                                try { closer.close() } catch (problem: Throwable) {
                                    if (failure == null) failure = problem else failure.addSuppressed(problem)
                                }
                                failure = driveReleasedOr(failure, driveLeft(uri, failure, descriptorReleased = false))
                            }
                            failure?.let { throw it }
                        } finally {
                            driveHandle.unregister()
                        }
                    }
                }
            } catch (failure: Throwable) {
                // Read before this close: already closed means the drive left during the checks.
                val released = driveLeft(uri, failure, closer.closed)
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
