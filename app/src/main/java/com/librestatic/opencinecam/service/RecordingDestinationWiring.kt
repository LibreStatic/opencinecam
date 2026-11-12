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

/** A volume leaving ends only a running take on that same drive; a phone take, or one on another drive, carries on. */
internal fun driveLeavingStopsTake(takeDestination: RecordingDestination?, leavingVolumeId: String): Boolean =
    takeDestination is RecordingDestination.UsbFolder && takeDestination.volumeId.equals(leavingVolumeId, ignoreCase = true)

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
 * A later recovery pass (a drive mounted) merged into the state's report: the counts add up, and the drive takes still
 * deferred are the newer pass's, since it saw the journal last.
 */
internal fun mergeRecordingRecovery(current: RecordingRecoveryReport?, later: RecordingRecoveryReport): RecordingRecoveryReport {
    val base = current ?: return later
    return base.copy(discardedGroups = base.discardedGroups + later.discardedGroups, unresolvedGroups = base.unresolvedGroups + later.unresolvedGroups,
        incompleteGroups = base.incompleteGroups + later.incompleteGroups, rawTakes = base.rawTakes + later.rawTakes, deferredGroups = later.deferredGroups)
}
