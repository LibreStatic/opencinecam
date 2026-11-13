/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.service

import com.librestatic.opencinecam.CaptureMode
import com.librestatic.opencinecam.CaptureNameSnapshot
import com.librestatic.opencinecam.RawQualificationTarget
import com.librestatic.opencinecam.RecordingDestinationStatus
import com.librestatic.opencinecam.core.model.FailureCode
import com.librestatic.opencinecam.core.model.FailureSeverity
import com.librestatic.opencinecam.core.model.Recoverability
import com.librestatic.opencinecam.core.model.StableFailure
import com.librestatic.opencinecam.storage.ClipContinuation
import com.librestatic.opencinecam.storage.DestinationFallback
import com.librestatic.opencinecam.storage.DriveDisconnectPolicy
import com.librestatic.opencinecam.storage.RawVideoQualificationStore
import com.librestatic.opencinecam.storage.RecordingDestination
import com.librestatic.opencinecam.storage.RecordingRecoveryReport
import com.librestatic.opencinecam.storage.ResolvedRecordingDestination

/** PLAN-072: the component of the failures that end a take whose USB drive went away. */
internal const val RECORDING_DRIVE_COMPONENT = "recording-drive"

/**
 * The destination the storage policy measures: the running take's, encoded or RAW, or the next take's when idle.
 */
internal fun freeSpaceDestination(takeDestination: RecordingDestination?, nextDestination: RecordingDestination): RecordingDestination =
    takeDestination ?: nextDestination

/**
 * PLAN-072: the last free-space sample of one drive, so the main thread never asks the provider. [read] returns it and
 * starts a [sample] on [executor], at most one at a time; [store] keeps a sample taken elsewhere. The sample belongs to
 * one drive: asking for another, or [invalidate] (its volume left or mounted again), forgets it, so a value is never
 * reported for another drive or for a re-plugged one before its own first sample. A sample that gives null is kept as
 * null, as a direct read would give it. Thread-safe.
 */
internal class DriveFreeSpaceCache(
    private val executor: java.util.concurrent.Executor,
    private val sample: (RecordingDestination.UsbFolder) -> Long?,
) {
    private var drive: RecordingDestination.UsbFolder? = null
    private var bytes: Long? = null
    /** Bumped whenever the sample is forgotten or replaced, so an older sample still running is dropped. */
    private var generation = 0L
    /** The [generation] the sample in flight was started for; a sample of an earlier one never holds back a new one. */
    private var sampling: Long? = null

    /** The last sample of [drive], null when there is none yet; starts a new sample unless one is running for it. */
    fun read(drive: RecordingDestination.UsbFolder): Long? {
        val (value, started) = synchronized(this) {
            if (drive != this.drive) forget(drive)
            if (sampling == generation) return bytes
            sampling = generation
            bytes to generation
        }
        try {
            executor.execute {
                val sampled = runCatching { sample(drive) }.getOrNull()
                synchronized(this) {
                    if (sampling == started) sampling = null
                    if (generation == started) bytes = sampled
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            synchronized(this) { if (sampling == started) sampling = null }
        }
        return value
    }

    /**
     * A sample of [drive] taken now on [executor] for a take's admission on main, waited for [timeoutMs] at most: a drive
     * that stalls reads as unread (null) instead of holding main, and so does a busy executor (both threads stuck on a
     * failing drive). A sample that comes in time is kept, as [store] keeps one; a late one is dropped, and so is one
     * the volume left or mounted under (an [invalidate] or a newer sample meanwhile): it could be the drive before.
     */
    fun readFresh(drive: RecordingDestination.UsbFolder, timeoutMs: Long): Long? {
        val started = synchronized(this) {
            if (drive != this.drive) forget(drive)
            generation
        }
        val task = java.util.concurrent.FutureTask { sample(drive) }
        try {
            executor.execute(task)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            return null
        }
        val sampled = try {
            task.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: java.util.concurrent.TimeoutException) {
            task.cancel(false)
            return null
        } catch (_: java.util.concurrent.ExecutionException) {
            null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        }
        synchronized(this) {
            if (generation != started) return null
            store(drive, sampled)
        }
        return sampled
    }

    /** A sample of [drive] read directly now (the destination refresh). */
    @Synchronized fun store(drive: RecordingDestination.UsbFolder, freeBytes: Long?) {
        forget(drive)
        bytes = freeBytes
    }

    /** The volume [volumeId] left or mounted: when it holds the sampled drive, no sample until the next one. */
    @Synchronized fun invalidate(volumeId: String) {
        if (drive?.volumeId.equals(volumeId, ignoreCase = true)) forget(drive)
    }

    private fun forget(next: RecordingDestination.UsbFolder?) {
        drive = next
        bytes = null
        generation++
    }
}

/**
 * PLAN-072 Delivery 3: the qualification records a RAW take on [destination] is gated by. A drive has its own
 * (`saf:<volume>`); the phone keeps `mediastore-documents`. Every RAW lookup (the mode gate, the RES/FPS chooser,
 * Automatic, the settings row, the qualification prompt and run) passes the destination the next RAW take would use.
 */
internal fun rawDestinationId(destination: RecordingDestination): String = when (destination) {
    RecordingDestination.Phone -> RawVideoQualificationStore.DESTINATION_MEDIASTORE_DOCUMENTS
    is RecordingDestination.UsbFolder -> RawVideoQualificationStore.destinationIdForDrive(destination.volumeId)
}

/**
 * What the qualification prompt and step say they measure: null with no drive configured (the phone, as before), the
 * drive (with its [driveLabel]) when the next RAW take would go there, the phone when a configured drive is not usable.
 */
internal fun rawQualificationTarget(resolved: ResolvedRecordingDestination, driveLabel: String?): RawQualificationTarget? = when {
    resolved.configured !is RecordingDestination.UsbFolder -> null
    resolved.destination is RecordingDestination.UsbFolder -> RawQualificationTarget(drive = true, driveLabel = driveLabel?.takeIf { it.isNotBlank() })
    else -> RawQualificationTarget(drive = false)
}

/**
 * A volume leaving ends only a running take on that same drive; a phone take, or one on another drive, carries on. A
 * Leaving emitted ([emission]) before the take's destination was resolved ([takeVolumeEvents] events emitted then) is
 * about an older mount: the drive mounted again before the take saw it, so the take carries on too.
 */
internal fun driveLeavingStopsTake(takeDestination: RecordingDestination?, leavingVolumeId: String,
    emission: Long = Long.MAX_VALUE, takeVolumeEvents: Long = 0): Boolean =
    takeDestination is RecordingDestination.UsbFolder && takeDestination.volumeId.equals(leavingVolumeId, ignoreCase = true) &&
        emission > takeVolumeEvents

/**
 * PLAN-072: an encoded take that now records must be stopped for its lost drive: its drive left while it was still
 * starting ([lostAtStart], when the stop path refuses anything but RECORDING), or the take writes to a drive that is not
 * among the [mounted] volumes (a volume event missed). [monitored] false (no volume monitor) reads nothing as missing.
 */
internal fun takeDriveLostAtStart(lostAtStart: Boolean, takeDestination: RecordingDestination?, mounted: Set<String>, monitored: Boolean): Boolean {
    val drive = takeDestination as? RecordingDestination.UsbFolder ?: return false
    return lostAtStart || monitored && mounted.none { it.equals(drive.volumeId, ignoreCase = true) }
}

/**
 * PLAN-072: one recovery pass at a time per kind on a bounded single-thread executor: a [request] while a pass waits
 * only adds its item, so the queue holds at most one pass of each kind and a burst is never refused. The pass clears
 * its "queued" mark before it drains the items, so a request arriving while it runs queues exactly one follow-up and
 * every item reaches a pass. A refused execute leaves the items for the next request. Thread-safe.
 */
internal class CoalescedPass<T>(private val executor: java.util.concurrent.Executor, private val pass: (List<T>) -> Unit) {
    private val queued = java.util.concurrent.atomic.AtomicBoolean(false)
    private val due = java.util.concurrent.ConcurrentLinkedQueue<T>()

    /** Adds [item] to the next pass and queues it unless one waits; false when the executor refused it. */
    fun request(item: T): Boolean {
        due += item
        if (!queued.compareAndSet(false, true)) return true
        return try {
            executor.execute {
                queued.set(false)
                val items = generateSequence { due.poll() }.toList()
                if (items.isNotEmpty()) pass(items)
            }
            true
        } catch (failure: java.util.concurrent.RejectedExecutionException) {
            queued.set(false)
            false
        }
    }
}

/**
 * The failure a drive take is stopped with: the drive left ([writeFailed] false, a volume event) or a write to it
 * failed ([writeFailed] true, ENODEV before any event). Stop and save keeps what reached the drive.
 */
internal fun driveLostFailure(writeFailed: Boolean): StableFailure = StableFailure(
    RECORDING_DRIVE_COMPONENT, FailureCode.STORAGE_WRITE_FAILED, FailureSeverity.ERROR, Recoverability.USER_ACTION,
    if (writeFailed) "drive-write-failed" else "drive-disconnected",
    "USB drive disconnected: the take was stopped and saved")

/** PLAN-072 Delivery 4: why a drive take whose drive was lost cannot continue on the phone. */
internal enum class ContinuationUnavailable {
    /** Time-lapse: a restart would break the interval cadence and the frame count. */
    TIME_LAPSE,
    /** Constrained high-speed, which records through the direct (MediaRecorder) route or a high-speed session. */
    HIGH_SPEED,
    /** RAW video whose configuration is not qualified (or only risky) for the phone. */
    RAW_NOT_QUALIFIED,
    /** The phone has no room for the take under its admission rule. */
    PHONE_FULL,
    /** Any other mode: nothing that records a take. */
    MODE,
}

/** PLAN-072 Delivery 4: what the service does once a drive take stopped because its drive was lost. */
internal sealed interface DriveLossAction {
    /** The operator chose Stop and save: the take is kept on the drive, as before Delivery 4. */
    data object StopAndSave : DriveLossAction
    /** A continuation take starts on the phone once the drive part has fully finished. */
    data object Continue : DriveLossAction
    /** Continue on the phone was chosen but cannot apply: stop and save, and the end message says why. */
    data class StopUnavailable(val reason: ContinuationUnavailable) : DriveLossAction
}

/**
 * PLAN-072 Delivery 4: the decision taken once per drive take that lost its drive. VIDEO, LOG and HLG continue unless
 * high-speed ([highSpeed]); RAW video continues only when the configuration being recorded is qualified, and not risky,
 * for the phone ([rawQualifiedOnPhone]); time-lapse never does. [phoneRoom] is the phone's admission for the same take.
 */
internal fun driveLossAction(policy: DriveDisconnectPolicy, mode: CaptureMode, highSpeed: Boolean, rawQualifiedOnPhone: Boolean,
    phoneRoom: Boolean): DriveLossAction = when {
    policy == DriveDisconnectPolicy.STOP_AND_SAVE -> DriveLossAction.StopAndSave
    mode == CaptureMode.TIME_LAPSE -> DriveLossAction.StopUnavailable(ContinuationUnavailable.TIME_LAPSE)
    mode !in CONTINUABLE_MODES -> DriveLossAction.StopUnavailable(ContinuationUnavailable.MODE)
    mode != CaptureMode.RAW_VIDEO && highSpeed -> DriveLossAction.StopUnavailable(ContinuationUnavailable.HIGH_SPEED)
    mode == CaptureMode.RAW_VIDEO && !rawQualifiedOnPhone -> DriveLossAction.StopUnavailable(ContinuationUnavailable.RAW_NOT_QUALIFIED)
    !phoneRoom -> DriveLossAction.StopUnavailable(ContinuationUnavailable.PHONE_FULL)
    else -> DriveLossAction.Continue
}

private val CONTINUABLE_MODES = setOf(CaptureMode.VIDEO, CaptureMode.LOG, CaptureMode.HLG, CaptureMode.RAW_VIDEO)

/**
 * PLAN-072 Delivery 4: what a continuation take restarts with. [stem] is the file stem (`<parent stem>_cont<N>`), [link]
 * what its clip sidecar (or `.ocraw` metadata) records, [captureNames] the parent's frozen names and slate (same take
 * number, no new instant), [audio] whether the parent recorded sound, [rawConfigKey] the RAW configuration it recorded.
 */
internal data class ContinuationRequest(
    val mode: CaptureMode,
    val stem: String,
    val link: ClipContinuation,
    val captureNames: CaptureNameSnapshot,
    val audio: Boolean,
    val rawConfigKey: String? = null,
)

/**
 * The RAW configuration key the gate selects: the operator's [settingKey] (empty for Automatic), except through a drive
 * take's continuation on the phone ([continuation]: pending, starting or recording), which records the drive take's
 * configuration. The phone's own Automatic choice may differ, and switching to it would refuse the continuation.
 */
internal fun rawVideoGateKey(settingKey: String, continuation: ContinuationRequest?): String =
    continuation?.takeIf { it.mode == CaptureMode.RAW_VIDEO }?.rawConfigKey ?: settingKey

/**
 * PLAN-072 Delivery 4: the single "continuation pending" token. It is armed once per take, where the drive take's stop
 * converges ([arm]; a second trigger for the same take finds it armed, or the take vetoed, and is ignored), and consumed
 * once that stop has fully finished ([consume]). An operator stop or any other stop of the take ([veto]) drops it and
 * keeps it from being armed until the next take starts ([takeStarted]). A token older than [ttlMs] is treated as gone,
 * so a restart that never came (a callback lost) cannot swallow a later record press. Thread-safe: the encoded take's
 * stop arrives on the camera thread, everything else on main.
 */
internal class DriveContinuation(private val clock: () -> Long, private val ttlMs: Long = DEFAULT_TTL_MS) {
    private var pending: ContinuationRequest? = null
    private var armedAtMs = 0L
    private var vetoed = false

    /** A take started (a continuation included): an earlier veto no longer applies. */
    @Synchronized fun takeStarted() { vetoed = false }

    /** Arms [request]; false when the take was vetoed or a continuation is already pending. */
    @Synchronized fun arm(request: ContinuationRequest): Boolean {
        if (vetoed || live() != null) return false
        pending = request
        armedAtMs = clock()
        return true
    }

    /** The operator (or another reason) stopped the take: drop any pending continuation and refuse new ones for it. */
    @Synchronized fun veto(): ContinuationRequest? {
        vetoed = true
        return live().also { pending = null }
    }

    /** Takes the pending continuation, once; null when none is pending or it expired. */
    @Synchronized fun consume(): ContinuationRequest? = live().also { pending = null }

    @get:Synchronized val isPending: Boolean get() = live() != null

    /** The pending continuation, left pending; null when none is or it expired. */
    @get:Synchronized val pendingRequest: ContinuationRequest? get() = live()

    private fun live(): ContinuationRequest? = pending?.takeIf { clock() - armedAtMs <= ttlMs } ?: run { pending = null; null }

    companion object {
        /** Far longer than the drive part's finalization and the release of its reservation (about a second). */
        const val DEFAULT_TTL_MS = 15_000L
    }
}

/**
 * The warning-line notice of a take that went to the phone because its drive could not be used; null when it did not.
 * [starting] is for a take that began while the drive was still spinning up, [renamed] for one whose drive is mounted under
 * another volume name ([DestinationFallback.DRIVE_RENAMED]).
 */
internal fun destinationFallbackNotice(fallback: DestinationFallback?, notConnected: String, permissionLost: String, starting: String,
    renamed: String): String? =
    when (fallback) {
        null -> null
        DestinationFallback.DRIVE_NOT_CONNECTED -> notConnected
        DestinationFallback.DRIVE_STARTING -> starting
        DestinationFallback.DRIVE_RENAMED -> renamed
        DestinationFallback.PERMISSION_LOST -> permissionLost
    }

/** The HUD's destination state for [resolved]; the drive's label and free space only matter when a drive is chosen or used. */
internal fun recordingDestinationStatus(resolved: ResolvedRecordingDestination, driveLabel: String?, driveFreeBytes: Long?,
    continuedOnPhone: Boolean = false): RecordingDestinationStatus {
    val configured = resolved.configured is RecordingDestination.UsbFolder
    val onDrive = resolved.destination is RecordingDestination.UsbFolder
    return RecordingDestinationStatus(configured, onDrive, resolved.fallback, driveLabel.takeIf { configured }, driveFreeBytes.takeIf { onDrive },
        continuedOnPhone = continuedOnPhone && !onDrive)
}

/**
 * PLAN-072 Delivery 4: where a continuation records. Always the phone, even if the drive came back meanwhile (a flaky
 * cable): the take that lost it must not go back to it. No fallback reason, since the HUD says "continuing" instead.
 */
internal fun continuationDestination(resolved: ResolvedRecordingDestination): ResolvedRecordingDestination =
    ResolvedRecordingDestination(RecordingDestination.Phone, resolved.configured, null)

/**
 * A later recovery pass (a drive mounted) merged into the state's report: what each pass recovered or discarded adds
 * up, while the takes still pending (unresolved or deferred) are the newer pass's, since it saw the journal last and
 * each pass counts the same pending entry again. A pass that did not read the journal ([journalPass] false, the RAW
 * pass before a RAW take) leaves the pending counts as they were.
 */
internal fun mergeRecordingRecovery(current: RecordingRecoveryReport?, later: RecordingRecoveryReport,
    journalPass: Boolean = true): RecordingRecoveryReport {
    val base = current ?: return later
    return base.copy(discardedGroups = base.discardedGroups + later.discardedGroups,
        unresolvedGroups = if (journalPass) later.unresolvedGroups else base.unresolvedGroups,
        incompleteGroups = base.incompleteGroups + later.incompleteGroups, rawTakes = base.rawTakes + later.rawTakes,
        deferredGroups = if (journalPass) later.deferredGroups else base.deferredGroups)
}
