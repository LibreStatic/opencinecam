/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.storage

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.librestatic.opencinecam.CaptureNameSnapshot
import com.librestatic.opencinecam.captureFileStem
import com.librestatic.opencinecam.rawvideo.RawVideoTakeOutput
import java.nio.channels.FileChannel
import java.util.UUID

/**
 * PLAN-072 Delivery 3: the `.ocraw` of one RAW video take on a USB drive, `<tree>/OpenCineCam/<stem>/<stem>.ocraw`,
 * beside the folder's `.occ-in-progress` marker (the drive's IS_PENDING, ADR-0037). The document is created as
 * `application/octet-stream` under its final name, which [SafDocuments.newFile] checks the provider kept exactly, and
 * is never renamed. It is opened "rw" and wrapped in a [DuplexFileChannel], because the recorder's repair of an unclean
 * take reads, truncates and appends through one channel.
 *
 * The take is journalled in `recording-recovery.sqlite` ([SafRawTakeJournal]) and its document registered with
 * [RawVideoRecovery]'s live set while this process writes it, so the CinemaDNG export guard and recovery leave it
 * alone. [publish] deletes the marker; [discard] removes the folder. When either fails (the drive was pulled) the take
 * is relinquished: it stays on the drive, journalled, and is repaired and published when the drive is mounted again.
 */
internal class SafRawVideoOutput private constructor(
    private val journal: SafRawTakeJournal,
    val uri: Uri,
    override val displayName: String,
    /** The take UUID: the file name stem (without naming) and the clip UUID. */
    val takeId: String,
    private val descriptor: ParcelFileDescriptor,
) : RawVideoTakeOutput {
    override val channel: FileChannel = DuplexFileChannel.open(descriptor.fileDescriptor)

    // ADR-0037: a leaving or failing drive closes the channel and descriptor at once (vold kills a process that still
    // holds one). The writer thread's next write then throws ClosedChannelException (or AsynchronousCloseException
    // mid-write), which it reports as WRITER_ERROR, the drive-loss path. [publish] then relinquishes the take.
    private val fileCloser = CloseOnce(channel, descriptor)
    @Volatile private var released = false
    private val driveHandle = DriveDescriptors.registerDocument(uri, AutoCloseable {
        released = true
        fileCloser.close()
    })

    private var state = State.OPEN

    private enum class State { OPEN, PUBLISHED, DISCARDED }

    /** Closes the file and commits the take (its marker goes). Idempotent once it succeeded. */
    @Synchronized
    override fun publish(): Uri {
        if (state == State.PUBLISHED) return uri
        check(state == State.OPEN) { "RAW output was discarded" }
        try {
            closeDescriptor()
            // Its file was closed under the writer: the take is left journalled for recovery to repair, not committed as is.
            check(!released) { "The drive's descriptors were released: the take is left for recovery" }
            journal.commit()
            state = State.PUBLISHED
            return uri
        } catch (failure: Throwable) {
            journal.relinquish()
            throw failure
        } finally {
            RawVideoRecovery.release(uri)
        }
    }

    /** Closes the file and removes the take's folder; for a take that holds no frame. Idempotent. */
    @Synchronized
    override fun discard() {
        if (state != State.OPEN) return
        try {
            closeDescriptor()
            journal.discard()
            state = State.DISCARDED
        } catch (failure: Throwable) {
            journal.relinquish()
            throw failure
        } finally {
            RawVideoRecovery.release(uri)
        }
    }

    /** Closes the channel, then the descriptor, once; harmless after a [DriveDescriptors] release closed them. */
    private fun closeDescriptor() {
        try { fileCloser.close() } finally { driveHandle.unregister() }
    }

    companion object {
        /**
         * Journals the take, creates its folder, marker and `.ocraw` on [destination], and opens the file for writing.
         * Blocks on the provider; never on the main thread. [takeId] must be a UUID string.
         */
        fun create(
            context: Context,
            destination: RecordingDestination.UsbFolder,
            captureNames: CaptureNameSnapshot? = null,
            takeId: String = UUID.randomUUID().toString(),
            recovery: RecordingCaptureRecovery = RecordingCaptureRecovery(context),
        ): SafRawVideoOutput {
            val resolver = context.applicationContext.contentResolver
            val name = captureFileStem(takeId, captureNames) + RawVideoOutput.EXTENSION
            val journal = recovery.startRaw(destination)
            var uri: Uri? = null
            var descriptor: ParcelFileDescriptor? = null
            return try {
                val created = RawVideoRecovery.insertLive { journal.create(name) }
                uri = created
                descriptor = requireNotNull(resolver.openFileDescriptor(created, "rw")) { "RAW output could not be opened" }
                SafRawVideoOutput(journal, created, name, takeId, descriptor)
            } catch (failure: Throwable) {
                try { descriptor?.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                try { journal.discard() } catch (cleanup: Throwable) {
                    failure.addSuppressed(cleanup)
                    journal.relinquish()
                }
                uri?.let(RawVideoRecovery::release)
                throw failure
            }
        }
    }
}
