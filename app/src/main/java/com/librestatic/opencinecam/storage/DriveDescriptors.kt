/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.storage

import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log

/**
 * PLAN-072, ADR-0037: the long-held write descriptors this process keeps open on a USB drive, by volume.
 *
 * When a drive is pulled, vold kills every process that still holds a descriptor on it (SIGINT about 1.3 s after its
 * scan). So a take's writers register their descriptors here, and [releaseVolume] closes them all as soon as the drive
 * leaves or fails, before publication or any other cleanup. The bytes cannot be finalised on a vanished drive anyway;
 * recovery handles the take's folder when the drive returns. Each writer meets the closed descriptor as a write failure.
 *
 * Process-wide and thread-safe; [releaseVolume] is cheap when nothing is registered.
 */
internal object DriveDescriptors {
    private const val LOG_TAG = "RecordingDestination"

    private class Entry(val volumeId: String, val closeable: AutoCloseable)

    private val entries = ArrayList<Entry>()
    @Volatile private var size = 0

    /** Registers [closeable] as holding a descriptor on [volumeId] (matched ignoring case) until its handle unregisters it. */
    fun register(volumeId: String, closeable: AutoCloseable): DriveDescriptorHandle {
        val entry = Entry(volumeId.uppercase(), closeable)
        synchronized(entries) {
            entries += entry
            size = entries.size
        }
        return DriveDescriptorHandle(entry)
    }

    /**
     * Registers [closeable] when [document] is a document of the external storage provider on a removable volume;
     * otherwise (a MediaStore row on the phone) nothing is registered and the handle does nothing.
     */
    fun registerDocument(document: Uri, closeable: AutoCloseable): DriveDescriptorHandle =
        driveVolumeOfDocument(document)?.let { register(it, closeable) } ?: DriveDescriptorHandle.NONE

    /**
     * Closes every closeable registered for [volumeId] (matched ignoring case) and forgets them. A close that fails is
     * logged and does not stop the others. Returns how many were closed. Safe from any thread.
     */
    fun releaseVolume(volumeId: String): Int {
        if (size == 0) return 0
        val volume = volumeId.uppercase()
        val released = synchronized(entries) {
            val matching = entries.filter { it.volumeId == volume }
            if (matching.isNotEmpty()) {
                entries.removeAll { it.volumeId == volume }
                size = entries.size
            }
            matching
        }
        released.forEach { entry ->
            try {
                entry.closeable.close()
            } catch (failure: Throwable) {
                Log.w(LOG_TAG, "A descriptor on the drive $volume could not be closed", failure)
            }
        }
        return released.size
    }

    /** How many closeables are registered for [volumeId]; for tests. */
    internal fun registered(volumeId: String): Int {
        val volume = volumeId.uppercase()
        return synchronized(entries) { entries.count { it.volumeId == volume } }
    }

    internal fun unregister(entry: Any) {
        synchronized(entries) {
            if (entries.removeAll { it === entry }) size = entries.size
        }
    }
}

/** Unregisters one closeable from [DriveDescriptors] on its normal close. [unregister] is idempotent. */
internal class DriveDescriptorHandle internal constructor(private val entry: Any?) {
    fun unregister() {
        entry?.let(DriveDescriptors::unregister)
    }

    companion object {
        /** Nothing registered: the output is on the phone. */
        val NONE = DriveDescriptorHandle(null)
    }
}

/**
 * Releases the descriptors on [volumeId] and logs how many were closed, with [cause]. Called on the earliest signal that
 * the drive is leaving or failing: the volume monitor's callback, a drive write failure.
 */
internal fun releaseDriveDescriptors(volumeId: String, cause: String): Int {
    val closed = DriveDescriptors.releaseVolume(volumeId)
    if (closed > 0) Log.w("RecordingDestination", "Closed $closed descriptor(s) on the drive $volumeId: $cause")
    return closed
}

/** The volume UUID (upper case) of a document of the external storage provider, or null for any other URI. */
internal fun driveVolumeOfDocument(document: Uri): String? {
    if (document.authority != EXTERNAL_STORAGE_AUTHORITY) return null
    val id = runCatching { DocumentsContract.getDocumentId(document) }.getOrNull() ?: treeDocumentIdOf(document)
    return volumeIdOfDocumentId(id)?.takeUnless { it.equals("primary", ignoreCase = true) || it.equals("home", ignoreCase = true) }
}

/**
 * Closes [parts] once, in order, whichever thread comes first: the owner's normal close or a [DriveDescriptors]
 * release. Later calls do nothing, so the owner's own close stays harmless after an early release.
 */
internal class CloseOnce(private vararg val parts: AutoCloseable) : AutoCloseable {
    @Volatile var closed = false
        private set

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        parts.forEach { part ->
            try { part.close() } catch (problem: Throwable) {
                val first = failure
                if (first == null) failure = problem else if (first !== problem) first.addSuppressed(problem)
            }
        }
        failure?.let { throw it }
    }
}
