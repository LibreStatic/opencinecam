/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.storage

import android.content.Context
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
 * Process-wide and thread-safe; [releaseVolume] is cheap when nothing is registered. The first registration starts
 * [DriveDescriptorWatch], so a descriptor held by background work (an upload worker, an export, the network drive) is
 * released with the drive even when neither the capture service nor the gallery is watching the volumes.
 */
internal object DriveDescriptors {
    private const val LOG_TAG = "RecordingDestination"

    private class Entry(val volumeId: String, val closeable: AutoCloseable, val sequence: Long)

    private val entries = ArrayList<Entry>()
    @Volatile private var size = 0
    /** Counts the registrations, under [entries]'s lock; an entry keeps its count as [Entry.sequence]. */
    private var registrations = 0L

    /** How many releases each volume (upper case) has had, under [entries]'s lock; see [releaseMark]. */
    private val releases = HashMap<String, Long>()

    /**
     * The release count of [document]'s drive, taken before opening it and passed to [registerDocument]: a release that
     * ran while the open was in flight cannot have closed the descriptor, so the registration closes it instead. 0 off a drive.
     */
    fun releaseMark(document: Uri): Long =
        driveVolumeOfDocument(document)?.let { volume -> synchronized(entries) { releases[volume.uppercase()] ?: 0L } } ?: 0L

    /** The count of registrations so far: a release bounded by it ([releaseVolume]) spares every later one. */
    fun registrationMark(): Long = synchronized(entries) { registrations }

    /** Registers [closeable] as holding a descriptor on [volumeId] (matched ignoring case) until its handle unregisters it. */
    fun register(volumeId: String, closeable: AutoCloseable): DriveDescriptorHandle {
        DriveDescriptorWatch.ensure()
        val entry = synchronized(entries) {
            Entry(volumeId.uppercase(), closeable, ++registrations).also {
                entries += it
                size = entries.size
            }
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
     * [registerDocument] for a descriptor opened after [releaseMark] gave [openedAfter]: when the drive has been released
     * since, [closeable] is closed at once (the open may have reached the leaving mount after the release closed the
     * rest) and nothing is registered, so its owner meets a closed descriptor, as after any release.
     */
    fun registerDocument(document: Uri, closeable: AutoCloseable, openedAfter: Long): DriveDescriptorHandle {
        val volume = driveVolumeOfDocument(document)?.uppercase() ?: return DriveDescriptorHandle.NONE
        DriveDescriptorWatch.ensure()
        val entry = synchronized(entries) {
            if ((releases[volume] ?: 0L) > openedAfter) null
            else Entry(volume, closeable, ++registrations).also {
                entries += it
                size = entries.size
            }
        }
        if (entry != null) return DriveDescriptorHandle(entry)
        try {
            closeable.close()
        } catch (failure: Throwable) {
            Log.w(LOG_TAG, "A descriptor opened across a release of the drive $volume could not be closed", failure)
        }
        return DriveDescriptorHandle.NONE
    }

    /**
     * Closes every closeable registered for [volumeId] (matched ignoring case) and forgets them. A close that fails is
     * logged and does not stop the others. Returns how many were closed. Safe from any thread. With [registeredBy] (a
     * [registrationMark]), only those registered up to it: one registered later may hold a newer mount of the volume.
     */
    fun releaseVolume(volumeId: String, registeredBy: Long = Long.MAX_VALUE): Int {
        val volume = volumeId.uppercase()
        val released = synchronized(entries) {
            releases[volume] = (releases[volume] ?: 0L) + 1
            val matching = entries.filter { it.volumeId == volume && it.sequence <= registeredBy }
            if (matching.isNotEmpty()) {
                entries.removeAll { it.volumeId == volume && it.sequence <= registeredBy }
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

/**
 * The process's own volume watch for [DriveDescriptors]: a [RecordingVolumeMonitor] that lives as long as the process
 * and closes a leaving drive's descriptors on its callback. [install] only remembers the application context (it runs
 * at process start and costs nothing); the monitor is created by the first registration.
 */
internal object DriveDescriptorWatch {
    private var context: Context? = null
    private var monitor: RecordingVolumeMonitor? = null
    @Volatile private var started = false

    @Synchronized fun install(application: Context) {
        val applicationContext = application.applicationContext ?: application
        if (context === applicationContext) return
        monitor?.close()
        monitor = null
        started = false
        context = applicationContext
    }

    /** Starts the watch if it is installed and not running. A monitor that cannot start is logged and retried next time. */
    fun ensure() {
        if (started) return
        synchronized(this) {
            if (started) return
            val context = context ?: return
            try {
                monitor = RecordingVolumeMonitor(context)
                started = true
            } catch (failure: Exception) {
                Log.w("RecordingDestination", "The drive descriptor watch could not start", failure)
            }
        }
    }

    /** Whether the watch is running; for tests. */
    internal val active: Boolean get() = started
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
 * Runs [block] over this stream or descriptor of [document], closing it afterwards. On a drive it is registered for the
 * duration, so a drive that leaves mid-transfer has it closed (ADR-0037) and [block] fails with an I/O error instead of
 * the process holding a descriptor on the volume. On the phone it is a plain `use`.
 */
internal inline fun <S : AutoCloseable, T> S.useOnDrive(document: Uri, block: (S) -> T): T {
    val closer = CloseOnce(this)
    val handle = DriveDescriptors.registerDocument(document, closer)
    try {
        return block(this)
    } finally {
        try { closer.close() } finally { handle.unregister() }
    }
}

/**
 * Releases the descriptors on [volumeId] and logs how many were closed, with [cause]. Called on the earliest signal that
 * the drive is leaving or failing: the volume monitor's callback, a drive write failure.
 */
internal fun releaseDriveDescriptors(volumeId: String, cause: String, registeredBy: Long = Long.MAX_VALUE): Int {
    val closed = DriveDescriptors.releaseVolume(volumeId, registeredBy)
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
