/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.storage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.Executor
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** A removable volume arriving or going; [volumeId] is its UUID in upper case. */
internal sealed interface RecordingVolumeEvent {
    val volumeId: String

    data class Mounted(override val volumeId: String) : RecordingVolumeEvent
    /**
     * [registeredBy] is the [DriveDescriptors.registrationMark] when the event was emitted: a descriptor registered
     * later may be on a newer mount of the volume, so a release for this event spares it. [emission] counts the monitor's
     * events up to this one ([RecordingVolumeMonitor.snapshot]): a take whose destination was resolved from a later
     * snapshot is on a newer mount, and this event is not about it.
     */
    data class Leaving(override val volumeId: String, val registeredBy: Long = Long.MAX_VALUE, val emission: Long = 0) : RecordingVolumeEvent
}

internal enum class VolumeStateKind { MOUNTED, LEAVING, IGNORE }

/**
 * Maps a `StorageVolume.getState()` string. A drive being pulled reports `ejecting`, then `unmounted`
 * or `bad_removal`; a read-only remount (`mounted_ro`, e.g. after a file system error) cannot take a
 * recording either, so it counts as leaving. Transitional or unusable states (`checking`, `nofs`,
 * `unmountable`, `shared`, `unknown`) change nothing.
 */
internal fun volumeStateKind(state: String?): VolumeStateKind = when (state) {
    Environment.MEDIA_MOUNTED -> VolumeStateKind.MOUNTED
    Environment.MEDIA_EJECTING, Environment.MEDIA_UNMOUNTED, Environment.MEDIA_BAD_REMOVAL,
    Environment.MEDIA_REMOVED, Environment.MEDIA_MOUNTED_READ_ONLY -> VolumeStateKind.LEAVING
    else -> VolumeStateKind.IGNORE
}

/**
 * The mounted set and the dedupe of state reports: an event comes out only when a volume's kind
 * changes. A volume never seen counts as absent, so only its mounting is news. Not thread-safe.
 */
internal class VolumeEventTracker(initiallyMounted: Set<String> = emptySet()) {
    private val mounted = initiallyMounted.mapTo(LinkedHashSet()) { it.uppercase() }

    val mountedVolumes: Set<String> get() = mounted.toSet()

    /** Applies [state] for [volumeId] and returns the event it causes, if any. */
    fun update(volumeId: String, state: String?): RecordingVolumeEvent? = apply(volumeId, volumeStateKind(state))

    fun apply(volumeId: String, kind: VolumeStateKind): RecordingVolumeEvent? {
        val id = volumeId.uppercase()
        return when (kind) {
            VolumeStateKind.MOUNTED -> if (mounted.add(id)) RecordingVolumeEvent.Mounted(id) else null
            VolumeStateKind.LEAVING -> if (mounted.remove(id)) RecordingVolumeEvent.Leaving(id) else null
            VolumeStateKind.IGNORE -> null
        }
    }
}

/**
 * What a rescan applies, in order: the state read for each of [volumes] (UUID upper case, state), then leaving for each
 * of [mounted] absent from the read. A volume the listener reported after the read started (its [lastReported] counter
 * above [started], the counter when the read began) is left out: the read may be older than that report, which stands.
 */
internal fun rescanChanges(volumes: List<Pair<String, String?>>, mounted: Set<String>, lastReported: Map<String, Long>,
    started: Long): List<Pair<String, VolumeStateKind>> {
    val present = volumes.mapTo(HashSet()) { it.first }
    val read = volumes.map { (id, state) -> id to volumeStateKind(state) } +
        mounted.filterNot { it in present }.map { it to VolumeStateKind.LEAVING }
    return read.filter { (id, _) -> (lastReported[id] ?: Long.MIN_VALUE) <= started }
}

/**
 * PLAN-072: watches removable volumes for the recording destination. [mounted] holds the UUIDs (upper
 * case) of removable volumes mounted now, seeded from `StorageManager.storageVolumes`; [events] says
 * when one leaves or mounts, once per change. API 30+ uses `registerStorageVolumeCallback`; API 29 a
 * receiver for the `MEDIA_*` broadcasts that re-reads the volumes, where `MEDIA_EJECT` counts as
 * leaving before the volume's state changes. A leaving volume's write descriptors ([DriveDescriptors]) are closed
 * on the callback's own thread (a binder thread on API 30+), before the event is emitted. App-scoped: [close] unregisters.
 */
internal class RecordingVolumeMonitor(context: Context) : AutoCloseable {
    private val context = context.applicationContext
    private val storage = requireNotNull(this.context.getSystemService(StorageManager::class.java))
    private val tracker = VolumeEventTracker(removableVolumes().orEmpty().filter { it.second == Environment.MEDIA_MOUNTED }.mapTo(HashSet()) { it.first })
    private val mountedState = MutableStateFlow(tracker.mountedVolumes)
    private val eventFlow = MutableSharedFlow<RecordingVolumeEvent>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val unregister: () -> Unit
    /** Counts the states the listener reported, under [tracker]'s lock. */
    private var reported = 0L
    /** The value of [reported] at each volume's (upper case) last report, under [tracker]'s lock; see [rescanChanges]. */
    private val lastReported = HashMap<String, Long>()
    /** Counts the events emitted, under [tracker]'s lock; see [RecordingVolumeEvent.Leaving.emission]. */
    private var emitted = 0L

    val mounted: StateFlow<Set<String>> = mountedState.asStateFlow()

    /** The events emitted so far and the volumes mounted after them, read together. */
    fun snapshot(): Pair<Long, Set<String>> = synchronized(tracker) { emitted to tracker.mountedVolumes }
    val events: SharedFlow<RecordingVolumeEvent> = eventFlow.asSharedFlow()

    init {
        unregister = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) registerCallback() else registerReceiver()
        // The listener reports changes only: a volume the seed read missed, or one that changed before it registered, is read here.
        rescan()
    }

    override fun close() = unregister()

    private fun report(volumeId: String, kind: VolumeStateKind) {
        // ADR-0037: vold kills a process that still holds a descriptor on a leaving volume, so the take's write
        // descriptors close here, on the callback's thread, before the event waits behind the main queue.
        if (kind == VolumeStateKind.LEAVING) releaseDriveDescriptors(volumeId, "the drive is leaving")
        synchronized(tracker) {
            lastReported[volumeId.uppercase()] = ++reported
            publish(volumeId, kind)
        }
    }

    /**
     * Under [tracker]'s lock. A Leaving event carries [registeredBy]: the mark its own release used ([rescan]), or, from
     * the listener, the registrations so far (its release closed everything registered before it).
     */
    private fun publish(volumeId: String, kind: VolumeStateKind, registeredBy: Long = DriveDescriptors.registrationMark()) {
        tracker.apply(volumeId, kind)?.let { event ->
            mountedState.value = tracker.mountedVolumes
            // Taken under the lock that orders the reports: a remount is reported, and so emitted, only after this.
            emitted++
            eventFlow.tryEmit(if (event is RecordingVolumeEvent.Leaving) event.copy(registeredBy = registeredBy, emission = emitted) else event)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun registerCallback(): () -> Unit {
        val callback = object : StorageManager.StorageVolumeCallback() {
            override fun onStateChanged(volume: StorageVolume) {
                val id = volume.uuid ?: return
                if (volume.isRemovable) report(id, volumeStateKind(volume.state))
            }
        }
        storage.registerStorageVolumeCallback(DIRECT, callback)
        return { runCatching { storage.unregisterStorageVolumeCallback(callback) } }
    }

    private fun registerReceiver(): () -> Unit {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                // The volume may still read mounted at MEDIA_EJECT; a rescan then would bring it back.
                val ejecting = if (intent.action == Intent.ACTION_MEDIA_EJECT) ejectingVolume(intent) else null
                if (ejecting != null) report(ejecting, VolumeStateKind.LEAVING) else rescan()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addDataScheme("file")
        }
        // Protected system broadcasts, and this path only runs on API 29, where the flag is not checked.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        return { runCatching { context.unregisterReceiver(receiver) } }
    }

    /**
     * Re-reads every removable volume; one missing from the list has been removed. A failed read changes nothing. The
     * listener may report a volume while the read runs (API 30+, where it runs on a binder thread), and the read may be
     * older than that report: such a volume keeps the listener's state, and the read applies to every other one
     * ([rescanChanges]), so one read always applies.
     */
    private fun rescan() {
        val started = synchronized(tracker) { reported }
        // Only descriptors registered before the read are on the mount it saw gone: the listener may report the volume
        // mounted again meanwhile, and one opened on that mount must stay open.
        val registeredBy = DriveDescriptors.registrationMark()
        val volumes = removableVolumes() ?: return
        val changes = synchronized(tracker) { rescanChanges(volumes, tracker.mountedVolumes, lastReported, started) }
        // As in report(): the descriptors close before the event is emitted, and outside the lock.
        changes.filter { it.second == VolumeStateKind.LEAVING }
            .forEach { (id, _) -> releaseDriveDescriptors(id, "the drive is leaving", registeredBy) }
        synchronized(tracker) {
            // A report that came in meanwhile is newer than this read, and stands.
            changes.filter { (id, _) -> (lastReported[id] ?: Long.MIN_VALUE) <= started }.forEach { (id, kind) -> publish(id, kind, registeredBy) }
        }
    }

    private fun ejectingVolume(intent: Intent): String? = runCatching {
        intent.data?.path?.let { storage.getStorageVolume(File(it)) }?.uuid
    }.getOrNull()

    /** Every removable volume with a UUID, upper case, and its state; null when they could not be read. */
    private fun removableVolumes(): List<Pair<String, String>>? = runCatching {
        storage.storageVolumes.mapNotNull { volume -> volume.uuid?.takeIf { volume.isRemovable }?.let { it.uppercase() to volume.state } }
    }.onFailure { Log.w(LOG_TAG, "The storage volumes could not be read", it) }.getOrNull()

    private companion object {
        const val LOG_TAG = "RecordingVolumeMonitor"
        val DIRECT = Executor { it.run() }
    }
}
