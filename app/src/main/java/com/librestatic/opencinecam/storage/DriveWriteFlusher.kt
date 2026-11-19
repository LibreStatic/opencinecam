/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.storage

import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.util.Log
import java.io.FileDescriptor
import java.util.concurrent.CopyOnWriteArrayList

/**
 * PLAN-072, ADR-0037: flushes the files of a take recording to a USB drive about once a second.
 *
 * The drive is exFAT through the kernel driver and nothing else syncs during a take, so the page cache holds the recent
 * data and the file size: a drive pulled mid-take lost the whole file. An fsync (not fdatasync: exFAT must persist the
 * directory entry with the size) bounds the loss to about the last second. fsync is per inode, so each target syncs its
 * own duplicate of the output descriptor, whichever writer (muxer, MediaRecorder, audio sidecar) owns the original.
 *
 * Each duplicate is registered with [DriveDescriptors], so a leaving drive closes it with the rest (vold kills a holder);
 * a close while the flusher is inside fsync is fine, the descriptor goes away at once. No lock is held across an fsync,
 * which can block for seconds on a stalled drive. A failing target is logged once and dropped; the flusher never fails
 * the take. A phone (MediaStore) output adds nothing.
 */
internal class DriveWriteFlusher(
    private val intervalMs: Long = FLUSH_INTERVAL_MS,
    private val sync: (FileDescriptor) -> Unit = { Os.fsync(it) },
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private val targets = CopyOnWriteArrayList<Target>()
    private val lock = Object()
    private var thread: Thread? = null
    private var lastSlowLogMs = Long.MIN_VALUE

    /** One drive output being flushed. [close] closes its duplicate, unregisters it and stops the flushing; idempotent. */
    inner class Target internal constructor(private val dup: ParcelFileDescriptor) : AutoCloseable {
        private val closer = CloseOnce(dup)
        internal lateinit var handle: DriveDescriptorHandle
        val closed: Boolean get() = closer.closed
        internal fun closer(): AutoCloseable = closer

        internal fun fileDescriptorForTest(): FileDescriptor = dup.fileDescriptor

        internal fun flush() = sync(dup.fileDescriptor)

        override fun close() {
            targets.remove(this)
            try { closer.close() } catch (problem: Throwable) {
                Log.w(LOG_TAG, "A flusher descriptor could not be closed", problem)
            } finally { handle.unregister() }
        }
    }

    /**
     * Starts flushing [descriptor]'s file when [document] is on a drive; null on the phone, or when the duplicate cannot be
     * made (logged: durability is best effort). The caller closes the target before publishing or renaming the file.
     */
    fun add(document: Uri, descriptor: ParcelFileDescriptor): Target? {
        if (driveVolumeOfDocument(document) == null) return null
        val dup = try { descriptor.dup() } catch (problem: Exception) {
            Log.w(LOG_TAG, "Drive output $document is not flushed: descriptor could not be duplicated", problem)
            return null
        }
        val target = Target(dup)
        target.handle = DriveDescriptors.registerDocument(document, target.closer())
        if (target.handle === DriveDescriptorHandle.NONE) {
            target.close()
            return null
        }
        targets += target
        // A release between the registration and the add has closed it already; flushOnce then drops it silently.
        start()
        return target
    }

    /** How many targets are being flushed; for tests. */
    internal val count: Int get() = targets.size

    /** One pass over the targets; the flusher thread runs it every interval. */
    internal fun flushOnce() {
        for (target in targets) {
            if (target.closed) { targets.remove(target); continue }
            val started = clock()
            try {
                target.flush()
            } catch (problem: Throwable) {
                // Closed by a release or a finish meanwhile (EBADF) or a drive failing: its writer meets the same.
                if (!target.closed) Log.w(LOG_TAG, "Flushing a drive output failed; it is no longer flushed", problem)
                targets.remove(target)
                continue
            }
            val tookMs = clock() - started
            if (tookMs >= SLOW_FSYNC_MS && started - lastSlowLogMs >= SLOW_LOG_EVERY_MS) {
                lastSlowLogMs = started
                Log.w(LOG_TAG, "Flushing a drive output took $tookMs ms")
            }
        }
    }

    private fun start() = synchronized(lock) {
        if (thread != null) return
        thread = Thread({ run() }, "drive-write-flusher").apply { isDaemon = true; start() }
    }

    private fun run() {
        while (true) {
            try { Thread.sleep(intervalMs) } catch (_: InterruptedException) { }
            flushOnce()
            synchronized(lock) {
                if (targets.isEmpty()) { thread = null; return }
            }
        }
    }

    companion object {
        private const val LOG_TAG = "DriveWriteFlusher"
        const val FLUSH_INTERVAL_MS = 1000L
        private const val SLOW_FSYNC_MS = 500L
        private const val SLOW_LOG_EVERY_MS = 10_000L

        /** The process-wide flusher the take writers use. */
        val shared = DriveWriteFlusher()
    }
}
