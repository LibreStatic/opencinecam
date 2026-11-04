/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.librestatic.opencinecam.CaptureNameSnapshot
import com.librestatic.opencinecam.captureFileStem
import com.librestatic.opencinecam.rawvideo.RawVideoTakeOutput
import java.nio.channels.FileChannel
import java.util.UUID

/**
 * The `.ocraw` row of one RAW video take (PLAN-071 decision 7): `Documents/OpenCineCam/RAW/<take>.ocraw`
 * in `MediaStore.Files`, `application/octet-stream`, pending until [publish]. The row is registered
 * with [RawVideoRecovery] while this process owns it, so recovery leaves it alone; after process
 * death recovery finds it pending and repairs, publishes or deletes it.
 *
 * Unlike [VideoOutput], this row is not a member of a [RecordingRecoveryGroup]: those namespaces are
 * per-take folders under DCIM, Music and Download, and the RAW destination is a shared Documents
 * folder. Recovery therefore enumerates this app's pending `.ocraw` rows in that folder instead.
 */
class RawVideoOutput private constructor(
    private val context: Context,
    val uri: Uri,
    override val displayName: String,
    /** The take UUID: the file name stem (without naming) and the clip UUID. */
    val takeId: String,
    private val descriptor: ParcelFileDescriptor,
) : RawVideoTakeOutput {
    override val channel: FileChannel = DuplexFileChannel.open(descriptor.fileDescriptor)

    private var state = State.OPEN

    private enum class State { OPEN, PUBLISHED, DISCARDED }

    /** Closes the file and clears `IS_PENDING`. Idempotent once it succeeded. */
    @Synchronized
    override fun publish(): Uri {
        if (state == State.PUBLISHED) return uri
        check(state == State.OPEN) { "RAW output was discarded" }
        try {
            closeDescriptor()
            val updated = context.contentResolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
            check(updated == 1) { "RAW publication did not update its owned row" }
            state = State.PUBLISHED
            return uri
        } finally {
            // Published or not, this process no longer writes it: a failed publication is retried
            // by the next recovery pass.
            RawVideoRecovery.release(uri)
        }
    }

    /** Closes the file and deletes the row; for a take that holds no frame. Idempotent. */
    @Synchronized
    override fun discard() {
        if (state != State.OPEN) return
        try {
            closeDescriptor()
            check(context.contentResolver.delete(uri, null, null) in 0..1)
            state = State.DISCARDED
        } finally {
            RawVideoRecovery.release(uri)
        }
    }

    private fun closeDescriptor() {
        var failure: Throwable? = null
        try { channel.close() } catch (problem: Throwable) { failure = problem }
        try { descriptor.close() } catch (problem: Throwable) { failure?.addSuppressed(problem) ?: run { failure = problem } }
        failure?.let { throw it }
    }

    companion object {
        const val RELATIVE_PATH = "Documents/OpenCineCam/RAW/"
        const val MIME_TYPE = "application/octet-stream"
        const val EXTENSION = ".ocraw"

        /** Inserts the pending row and opens it for writing. [takeId] must be a UUID string. */
        fun create(
            context: Context,
            captureNames: CaptureNameSnapshot? = null,
            takeId: String = UUID.randomUUID().toString(),
        ): RawVideoOutput {
            val app = context.applicationContext
            val resolver = app.contentResolver
            val requested = captureFileStem(takeId, captureNames) + EXTENSION
            val uri = RawVideoRecovery.insertLive {
                requireNotNull(resolver.insert(collection(), ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, requested)
                    put(MediaStore.MediaColumns.MIME_TYPE, MIME_TYPE)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                })) { "RAW output allocation failed" }
            }
            var descriptor: ParcelFileDescriptor? = null
            return try {
                // MediaStore may rename a duplicate ("name (1).ocraw"): keep the name it chose.
                val name = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: requested
                descriptor = requireNotNull(resolver.openFileDescriptor(uri, "rw")) { "RAW output could not be opened" }
                RawVideoOutput(app, uri, name, takeId, descriptor)
            } catch (failure: Throwable) {
                try { descriptor?.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                try { resolver.delete(uri, null, null) } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                RawVideoRecovery.release(uri)
                throw failure
            }
        }

        internal fun collection(): Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    }
}
