/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.storage

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import com.librestatic.opencinecam.core.model.BenchmarkResultRepository
import com.librestatic.opencinecam.core.model.BenchmarkSink
import com.librestatic.opencinecam.core.model.BenchmarkPersistenceOutcome
import com.librestatic.opencinecam.core.model.BenchmarkDestination
import com.librestatic.opencinecam.core.model.JsonBenchmarkResultRepository
import com.librestatic.opencinecam.core.model.StorageBenchmarkResult

/** SAF-owned destination; no filesystem paths or implicit locations are accepted. */
class ContentResolverBenchmarkDestination(
    private val resolver: ContentResolver,
    override val id: String,
    private val uri: Uri,
) : BenchmarkDestination {
    override fun openSink(): BenchmarkSink = resolver.openOutputStream(uri)?.let(::OutputStreamBenchmarkSink)
        ?: error("The selected storage destination could not be opened.")

    /** A SAF document is deleted through its provider; ContentResolver.delete rejects document URIs. */
    override fun cleanup() {
        val deleted = runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrDefault(false) ||
            resolver.delete(uri, null, null) > 0
        check(deleted) { "The benchmark file could not be deleted." }
    }
}

private class OutputStreamBenchmarkSink(private val output: java.io.OutputStream) : BenchmarkSink {
    private var count = 0L

    override fun write(bytes: ByteArray) {
        output.write(bytes)
        count += bytes.size
    }

    override fun flush() = output.flush()

    override val bytesWritten: Long
        get() = count

    override fun close() {
        output.close()
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
