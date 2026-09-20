/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.transfers

import android.content.ContentResolver
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import androidx.core.net.toUri
import com.librestatic.opencinecam.storage.CaptureArtifactRole
import com.librestatic.opencinecam.storage.PreparedCaptureArtifact
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * Strict source for all published take roles. Each source owns only its independently opened
 * read-only descriptor; the provider's rows and the capture writer are never modified or closed.
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
            }, spec.sourceUri, spec.sourceName,
        )
        private val probe = MediaStorePreparedArtifactProbe(resolver)

        override fun snapshot(): WebDavClipSnapshot = probe.inspect(prepared, pending = false).also {
            requireHashSnapshot(spec, it)
        }

        override fun open(): InputStream {
            val before = snapshot()
            val descriptor = resolver.openFileDescriptor(spec.sourceUri.toUri(), "r")
                ?: throw FileNotFoundException("Published artifact descriptor is unavailable")
            try {
                requireReadOnlyPreparedDescriptor(descriptor)
                val first = Os.fstat(descriptor.fileDescriptor)
                requireStat(descriptor, first, before.sizeBytes)
                if (snapshot() != before || !sameFile(first, Os.fstat(descriptor.fileDescriptor))) throw WebDavArtifactContentChanged()
                val input = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
                return object : InputStream() {
                    private var closed = false
                    override fun read(): Int { check(!closed); return input.read() }
                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                        check(!closed)
                        return input.read(bytes, offset, length)
                    }
                    override fun close() {
                        if (closed) return
                        closed = true
                        var failure: Throwable? = null
                        try {
                            requireReadOnlyPreparedDescriptor(descriptor)
                            val last = Os.fstat(descriptor.fileDescriptor)
                            requireStat(descriptor, last, before.sizeBytes)
                            if (!sameFile(first, last) || snapshot() != before ||
                                !sameFile(first, Os.fstat(descriptor.fileDescriptor))) throw WebDavArtifactContentChanged()
                        } catch (problem: Throwable) { failure = problem }
                        try { input.close() } catch (problem: Throwable) {
                            if (failure == null) failure = problem else failure.addSuppressed(problem)
                        }
                        failure?.let { throw it }
                    }
                }
            } catch (failure: Throwable) {
                try { descriptor.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                throw failure
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
