/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.storage

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import com.librestatic.opencinecam.core.model.BenchmarkResultRepository
import com.librestatic.opencinecam.core.model.BenchmarkSink
import com.librestatic.opencinecam.core.model.BenchmarkPersistenceOutcome
import com.librestatic.opencinecam.core.model.BenchmarkDestination
import com.librestatic.opencinecam.core.model.JsonBenchmarkResultRepository
import com.librestatic.opencinecam.core.model.StorageBenchmarkResult
import java.io.FileOutputStream
import java.nio.ByteBuffer

/** SAF-owned destination; no filesystem paths or implicit locations are accepted. */
class ContentResolverBenchmarkDestination(
    private val resolver: ContentResolver,
    override val id: String,
    private val uri: Uri,
) : BenchmarkDestination {
    /**
     * A `FileChannel` over the document's descriptor when the provider gives one, so the runner's
     * direct buffers reach the file without a copy and syncs force it to flash. Providers that only
     * stream (no descriptor) fall back to the output stream. ADR-0037: a sink on a USB drive is closed the moment the
     * drive leaves ([DriveDescriptors]), so the write in progress fails and the run ends as a failed measurement.
     */
    override fun openSink(): BenchmarkSink {
        runCatching { resolver.openFileDescriptor(uri, "w") }.getOrNull()?.let { descriptor ->
            return runCatching { FileChannelBenchmarkSink(uri, descriptor) }
                .getOrElse { failure ->
                    runCatching { descriptor.close() }
                    throw failure
                }
        }
        return resolver.openOutputStream(uri)?.let { OutputStreamBenchmarkSink(uri, it) }
            ?: error("The selected storage destination could not be opened.")
    }

    /** A SAF document is deleted through its provider; ContentResolver.delete rejects document URIs. */
    override fun cleanup() {
        val deleted = runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrDefault(false) ||
            resolver.delete(uri, null, null) > 0
        check(deleted) { "The benchmark file could not be deleted." }
    }
}

/** Unbuffered channel writes from the runner's direct buffer, with `force(false)` as the durable sync. */
private class FileChannelBenchmarkSink(uri: Uri, descriptor: ParcelFileDescriptor) : BenchmarkSink {
    private val channel = FileOutputStream(descriptor.fileDescriptor).channel
    private val closer = CloseOnce(channel, descriptor)
    private val driveHandle = DriveDescriptors.registerDocument(uri, closer)
    private var count = 0L

    override val supportsByteBuffer: Boolean
        get() = true

    override fun write(bytes: ByteArray) = write(ByteBuffer.wrap(bytes))

    override fun write(buffer: ByteBuffer) {
        while (buffer.hasRemaining()) count += channel.write(buffer)
    }

    /** Channel writes are not buffered in the process; durability is [sync]'s job. */
    override fun flush() = Unit

    override fun sync() = channel.force(false)

    override val bytesWritten: Long
        get() = count

    override fun close() {
        try {
            closer.close()
        } finally {
            driveHandle.unregister()
        }
    }
}

private class OutputStreamBenchmarkSink(uri: Uri, private val output: java.io.OutputStream) : BenchmarkSink {
    private val closer = CloseOnce(output)
    private val driveHandle = DriveDescriptors.registerDocument(uri, closer)
    private var count = 0L

    override fun write(bytes: ByteArray) {
        output.write(bytes)
        count += bytes.size
    }

    override fun flush() = output.flush()

    override val bytesWritten: Long
        get() = count

    override fun close() {
        try {
            closer.close()
        } finally {
            driveHandle.unregister()
        }
    }
}

/** Persists each destination result to the user-selected SAF document. */
class ContentResolverBenchmarkResultRepository(
    private val resolver: ContentResolver,
    private val uri: Uri,
) : BenchmarkResultRepository {
    override fun persist(result: StorageBenchmarkResult): BenchmarkPersistenceOutcome {
        val output = resolver.openOutputStream(uri)
            ?: return BenchmarkPersistenceOutcome.Failed("The result destination could not be opened.")
        output.use { return JsonBenchmarkResultRepository(it).persist(result) }
    }
}
