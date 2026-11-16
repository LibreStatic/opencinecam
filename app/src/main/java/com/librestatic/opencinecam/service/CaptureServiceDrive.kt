/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.service

import android.os.Environment
import android.os.StatFs
import com.librestatic.opencinecam.CameraUiPhase
import com.librestatic.opencinecam.CaptureMode
import com.librestatic.opencinecam.R
import com.librestatic.opencinecam.core.model.StableFailure
import com.librestatic.opencinecam.settings.CaptureNameSnapshot
import com.librestatic.opencinecam.settings.ProductionSlateSettings
import com.librestatic.opencinecam.storage.RawVideoQualificationStore
import com.librestatic.opencinecam.storage.VideoOutput
import com.librestatic.opencinecam.storage.DriveDisconnectPolicy
import com.librestatic.opencinecam.storage.RecordingDestination
import com.librestatic.opencinecam.storage.RecordingDestinationStore
import com.librestatic.opencinecam.storage.RecordingVolumeEvent
import com.librestatic.opencinecam.storage.releaseDriveDescriptors
import com.librestatic.opencinecam.storage.ResolvedRecordingDestination
import com.librestatic.opencinecam.storage.driveFreeBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update
import com.librestatic.opencinecam.capture.RawQualificationStopReasons
import com.librestatic.opencinecam.service.CaptureService.TakeStartRefused
import com.librestatic.opencinecam.service.CaptureService.RecordingLutIntent
import com.librestatic.opencinecam.service.CaptureService.Companion.DRIVE_ADMISSION_WAIT_MS

// Part of CaptureService: the recording destination (phone or USB drive), its free space, and a drive lost or continued mid-take.
// These are extensions so the service's state stays in one object; they keep the threading rules of its members.

/** Free bytes on the primary external volume MediaStore writes takes to (not the app's private files), RAW video's included. */
@Suppress("DEPRECATION")
internal fun CaptureService.phoneVolumeFreeBytes(): Long? = runCatching { StatFs(Environment.getExternalStorageDirectory().absolutePath).availableBytes }.getOrNull()

internal fun CaptureService.cachedDestinationFreeBytes(destination: RecordingDestination): Long? = when (destination) {
    RecordingDestination.Phone -> phoneVolumeFreeBytes()
    is RecordingDestination.UsbFolder -> driveFreeSpace.read(destination)
}

/**
 * Main, a take's admission: free bytes on [destination] read now. A drive's is sampled on its own thread and waited
 * for [DRIVE_ADMISSION_WAIT_MS] at most, so a stalled drive never holds main (and its loss handling): it reads as
 * unread, which the storage policy admits with a warning or refuses.
 */
internal fun CaptureService.destinationFreeBytes(destination: RecordingDestination): Long? = when (destination) {
    RecordingDestination.Phone -> phoneVolumeFreeBytes()
    is RecordingDestination.UsbFolder -> driveFreeSpace.readFresh(destination, DRIVE_ADMISSION_WAIT_MS)
}

/**
 * Main, once an encoded take records: its drive left during the start window (or the event was missed), so it is
 * stopped now through the drive stop path. False when nothing was due.
 */
internal fun CaptureService.stopTakeLostAtStart(): Boolean {
    // A volume read, never a drive read: a drive that answers slowly is not taken for a lost one.
    if (pendingStopReason != null || cameraState.value.recordingFinalizing) return false
    if (!takeDriveLostAtStart(driveLostAtTakeStart, takeDestination?.destination, mountedRecordingVolumes(),
            monitored = recordingVolumeMonitor != null)) return false
    android.util.Log.w("RecordingDestination", "The take's drive is gone (" +
        (if (driveLostAtTakeStart) "it left while the take started" else "not mounted") + "); stopping it for the lost drive")
    driveLostAtTakeStart = false
    (takeDestination?.destination as? RecordingDestination.UsbFolder)?.let { releaseDriveDescriptors(it.volumeId, "the drive left as the take started") }
    stopTakeForLostDrive(writeFailed = false)
    return true
}

/** The take ended or never started: the next take's destination shows again. */
internal fun CaptureService.clearTakeDestination() {
    driveLostAtTakeStart = false
    takeContinuation = null
    if (takeDestination == null) return
    takeDestination = null
    mainHandler.post { refreshRecordingDestination() }
}

internal fun CaptureService.mountedRecordingVolumes(): Set<String> = recordingVolumeMonitor?.mounted?.value ?: emptySet()

internal fun CaptureService.driveArriving(): Boolean = driveArrivalMonitor?.arriving?.value ?: false

/** Resolves the destination for a take or still starting now (one preferences read and one permission IPC); any thread. */
internal fun CaptureService.resolveTakeDestination(): ResolvedRecordingDestination =
    runCatching {
        val (events, mounted) = recordingVolumeMonitor?.snapshot() ?: (0L to emptySet())
        RecordingDestinationStore(this).current(mounted, driveArriving()).copy(volumeEvents = events)
    }
        .onFailure { android.util.Log.e("RecordingDestination", "Destination unreadable; the take goes to the phone", it) }
        .getOrDefault(ResolvedRecordingDestination(RecordingDestination.Phone, RecordingDestination.Phone, null))

/** Storage executor: where a still being saved now goes; a missing drive falls back to the phone, as for a take. */
internal fun CaptureService.stillDestination(): RecordingDestination = resolveTakeDestination().destination

/**
 * Main: a VideoOutput on the destination resolved now; the take keeps it until it ends. A missing drive falls back to
 * the phone. A continuation (PLAN-072 Delivery 4, carried by the take's [RecordingLutIntent]) always goes to the phone,
 * named after the drive take it continues.
 */
internal fun CaptureService.createTakeOutput(slate: ProductionSlateSettings?, captureNames: CaptureNameSnapshot): VideoOutput {
    val continuation = recordingLutIntent.get()?.takeIf { it.owner === transferCapture.get() }?.continuation
    val resolved = resolveTakeDestination().let { if (continuation != null) continuationDestination(it) else it }
    resolved.fallback?.let { android.util.Log.w("RecordingDestination", "Take falls back to the phone: $it") }
    val output = try {
        VideoOutput.create(this, slate, captureNames, resolved.destination, stem = continuation?.stem)
    } catch (gone: com.librestatic.opencinecam.storage.DriveGrantGone) {
        // The destination went back to the phone since it was resolved, releasing the drive's grant before the take
        // was journalled: nothing was written, so the take goes where the destination is now, admitted for it again.
        val now = resolveTakeDestination()
        if (continuation != null || now.destination !is RecordingDestination.Phone) throw gone
        // Refused for the phone's free space: the refusal is already shown, and the take start is not a failure.
        if (!admitChangedTakeDestination(now.destination)) throw TakeStartRefused(gone)
        android.util.Log.w("RecordingDestination", "The drive's permission went at take start: the take goes to the phone")
        return VideoOutput.create(this, slate, captureNames, now.destination).also { holdTakeDestination(now) }
    }
    holdTakeDestination(resolved, continuation)
    return output
}

/** The take-start message for a storage refusal (ADR-0013): free space unreadable, or under the reserve. */
internal fun CaptureService.storageRefusalText(admission: StorageAdmission.Refuse): String =
    if (admission.availableBytes == null) getString(R.string.storage_unknown_refused)
    else getString(R.string.storage_reserve_insufficient, android.text.format.Formatter.formatFileSize(this, admission.requiredBytes),
        android.text.format.Formatter.formatFileSize(this, admission.availableBytes))

/**
 * Main, before an encoded take's foreground start and output: the take was admitted for the destination resolved
 * then, before the awaited transfer and audio retirement and the white balance convergence. When [resolved] (the one
 * resolved now when null; a caller that already resolved it passes it, so admission and output agree) differs
 * (the drive left or arrived meanwhile), the take is admitted again against its free space; false, with the
 * refusal shown, when it does not fit. A continuation was admitted for the phone, where it always goes.
 */
internal fun CaptureService.admitChangedTakeDestination(resolved: RecordingDestination? = null): Boolean {
    val intent = recordingLutIntent.get()?.takeIf { it.owner === transferCapture.get() } ?: return true
    if (intent.continuation != null) return true
    val destination = resolved ?: resolveTakeDestination().destination
    if (destination == intent.admittedDestination) return true
    android.util.Log.w("RecordingDestination", "The take's destination changed since its admission; admitting it again")
    when (val admission = admitRecordingStorage(settings.recordingPolicy, takeProjectedBytesPerMinute, destinationFreeBytes(destination))) {
        StorageAdmission.Admit -> admissionWarning = null
        StorageAdmission.AdmitUnread -> admissionWarning = getString(R.string.storage_unknown_warning)
        is StorageAdmission.Refuse -> {
            refuseTakeStart(admission.errorCode, storageRefusalText(admission))
            return false
        }
    }
    return true
}

/** Main: the take starting now writes to [resolved] until it ends; the HUD describes it, and says when it is a [continuation]. */
internal fun CaptureService.holdTakeDestination(resolved: ResolvedRecordingDestination, continuation: ContinuationRequest? = null) {
    takeDestination = resolved
    takeVolumeEvents = resolved.volumeEvents
    nextTakeDestination = resolved
    takeContinuation = continuation
    driveLostAtTakeStart = false
    cameraState.update { it.copy(recordingDestination = recordingDestinationStatus(resolved, it.recordingDestination.driveLabel,
        it.recordingDestination.driveFreeBytes, continuedOnPhone = continuation != null)) }
}

/**
 * Re-reads the configured destination, the drive's label and its free space off the main thread, then publishes the
 * status. A running take keeps its own destination; the next take's follows. Settings call it after adopting or
 * clearing a folder ([LocalBinder.refreshRecordingDestination]).
 */
internal fun CaptureService.refreshRecordingDestination() {
    if (serviceDestroyed) return
    val mounted = mountedRecordingVolumes()
    val arriving = driveArriving()
    destinationRefreshJob?.cancel()
    destinationRefreshJob = settingsScope.launch {
        val (resolved, status, phoneFree) = try {
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                val store = RecordingDestinationStore(this@refreshRecordingDestination)
                val resolved = store.current(mounted, arriving)
                val free = (resolved.destination as? RecordingDestination.UsbFolder)?.let { driveFreeBytes(contentResolver, it.treeUri) }
                Triple(resolved, recordingDestinationStatus(resolved, store.label(), free), phoneVolumeFreeBytes())
            }
        } catch (failure: kotlinx.coroutines.CancellationException) { throw failure }
        catch (failure: Exception) {
            android.util.Log.e("RecordingDestination", "Destination unreadable", failure)
            return@launch
        }
        if (serviceDestroyed) return@launch
        // Only once the read was not cancelled: a refresh overtaken by a volume event must not seed its drive's sample.
        (resolved.destination as? RecordingDestination.UsbFolder)?.let { driveFreeSpace.store(it, status.driveFreeBytes) }
        nextTakeDestination = resolved
        // While a take runs the HUD keeps describing that take; the next take's state is published when it ends.
        // With a drive configured (or just cleared), an idle free figure left by the last drive take would mislead: the
        // phone's replaces it when the next take goes there; the HUD reads the drive's own figure when it goes to the drive.
        // No take holds a destination here (every take does while it runs), so a drive take still finalizing in the
        // RECORDING phase must not keep its drive's figure: the next take goes to the phone.
        if (takeDestination == null) cameraState.update {
            val phoneNext = status.configuredDrive && !status.onDrive || it.recordingDestination.configuredDrive && !status.configuredDrive
            it.copy(recordingDestination = status, availableStorageBytes = if (phoneNext) phoneFree else it.availableStorageBytes)
        }
        else cameraState.update { it.copy(recordingDestination = it.recordingDestination.copy(driveLabel = status.driveLabel)) }
        // A drive configured or cleared, or its label read, can leave the gate's destination id as it was: the profile
        // line follows here, still from that id; a changed id recomputes the whole gate below.
        cameraState.update { it.copy(rawVideoProfile = rawVideoProfile(rawGateDestinationId, nextTakeDestination, status.driveLabel)) }
        refreshRawGateForDestination()
    }
}

/** Main: a removable volume left or mounted. A leaving drive ends the take on it (Stop and save); a mounted one is recovered. */
internal fun CaptureService.onRecordingVolumeEvent(event: RecordingVolumeEvent) {
    if (serviceDestroyed) return
    // A drive that left, or mounted again, has no free-space sample until its next one.
    driveFreeSpace.invalidate(event.volumeId)
    when (event) {
        is RecordingVolumeEvent.Leaving -> {
            // ADR-0037: the monitor already closed the drive's descriptors on its own thread; this is a cheap backstop,
            // bounded to those registered before the event: the drive may have mounted again while it waited here. As a
            // backstop it leaves the release mark alone, so an open on the new mount is not refused.
            releaseDriveDescriptors(event.volumeId, "the drive left", event.registeredBy, backstop = true)
            // A qualification of that drive has nothing left to measure; it ends as cancelled, so nothing is stored.
            if (rawQualificationSession != null && rawGateDestinationId == RawVideoQualificationStore.destinationIdForDrive(event.volumeId)) {
                localBinder.cancelRawVideoQualification(RawQualificationStopReasons.DRIVE_LOST)
            }
            if (driveLeavingStopsTake(takeDestination?.destination, event.volumeId, event.emission, takeVolumeEvents)) {
                // An encoded take still starting cannot be stopped yet: it is stopped for its drive once it records.
                if (rawTake == null && cameraState.value.phase == CameraUiPhase.CAPTURING) driveLostAtTakeStart = true
                else stopTakeForLostDrive(writeFailed = false)
            }
        }
        is RecordingVolumeEvent.Mounted -> recoverRecordingsOnMount(event.volumeId)
    }
    refreshRecordingDestination()
}

/**
 * Main: ends the running drive take through the policy stop path; what reached the drive is recovered when it returns.
 * Whether a continuation follows on the phone is decided where every drive stop converges, once per take:
 * [decideDriveContinuation] from the encoded take's `onRecordingStopped` or from [finishRawTake].
 */
internal fun CaptureService.stopTakeForLostDrive(writeFailed: Boolean) {
    localBinder.stopRecording(driveLostFailure(writeFailed))
}

/**
 * PLAN-072 Delivery 4, main: the take is being stopped for another reason than its drive, or the operator pressed record
 * in the gap before the continuation: drop any pending continuation and keep the take from arming one. True when one
 * was pending (the press is consumed as the stop, and the message says the take was not continued).
 */
internal fun CaptureService.cancelPendingContinuation(): Boolean {
    val dropped = driveContinuation.veto() ?: return false
    android.util.Log.i("RecordingDestination", "Continuation ${dropped.stem} cancelled")
    releaseContinuationForeground()
    if (dropped.mode == CaptureMode.RAW_VIDEO) refreshRawGate()
    cameraState.update { it.copy(message = listOfNotNull(it.message, getString(R.string.recording_drive_continue_cancelled))
        .joinToString(" · "), messageTransient = false) }
    return true
}

/** The operator's Drive-disconnect choice; Stop and save when unreadable. Any thread (one preferences read). */
internal fun CaptureService.driveDisconnectPolicy(): DriveDisconnectPolicy =
    runCatching { RecordingDestinationStore(this).policy() }.getOrDefault(DriveDisconnectPolicy.DEFAULT)

/**
 * PLAN-072 Delivery 4, any thread: a drive take stopped because its drive was lost ([stopReason] in
 * [RECORDING_DRIVE_COMPONENT]). Decides with [driveLossAction] and, on Continue, arms the continuation [request] builds;
 * a vetoed take (the operator or another reason stopped it first) or a second trigger arms nothing. Returns the text
 * the end message appends: why continuing is not available, or null.
 */
internal fun CaptureService.decideDriveContinuation(stopReason: StableFailure?, onDrive: Boolean, mode: CaptureMode, highSpeed: Boolean,
    rawQualifiedOnPhone: Boolean, phoneRoom: Boolean, request: () -> ContinuationRequest?): String? {
    if (!onDrive || stopReason?.component != RECORDING_DRIVE_COMPONENT) return null
    return when (val action = driveLossAction(driveDisconnectPolicy(), mode, highSpeed, rawQualifiedOnPhone, phoneRoom)) {
        DriveLossAction.StopAndSave -> null
        DriveLossAction.Continue -> {
            val next = runCatching(request).onFailure { android.util.Log.e("RecordingDestination", "Continuation not prepared", it) }.getOrNull()
            when {
                next == null -> getString(R.string.recording_drive_continue_failed)
                driveContinuation.arm(next) -> { android.util.Log.i("RecordingDestination", "Continuing on the phone as ${next.stem}"); null }
                else -> { android.util.Log.i("RecordingDestination", "Continuation not armed: the take was stopped or already continues"); null }
            }
        }
        is DriveLossAction.StopUnavailable -> getString(when (action.reason) {
            ContinuationUnavailable.TIME_LAPSE -> R.string.recording_drive_continue_unavailable_timelapse
            ContinuationUnavailable.HIGH_SPEED -> R.string.recording_drive_continue_unavailable_high_speed
            ContinuationUnavailable.RAW_NOT_QUALIFIED -> R.string.recording_drive_continue_unavailable_raw
            ContinuationUnavailable.PHONE_FULL -> R.string.recording_drive_continue_unavailable_space
            ContinuationUnavailable.MODE -> R.string.recording_drive_continue_failed
        }).also { android.util.Log.w("RecordingDestination", "Continue on the phone unavailable: ${action.reason}") }
    }
}

/**
 * PLAN-072 Delivery 4, main: starts the armed continuation once the drive part has fully finished (its publication
 * attempt done, its reservation released, `recordingFinalizing` cleared). It goes through the ordinary start path
 * ([LocalBinder.capturePrimary] or [toggleRawTake]) with [continuationStarting] set, so admission, the foreground
 * service, the encoder policy and the RAW gate all apply; any refusal ends as a clean stop and save with the reason
 * the start path shows, plus a line saying the take could not continue. Never while another take or a qualification runs.
 */
internal fun CaptureService.startDriveContinuation() {
    val request = driveContinuation.consume() ?: run {
        // Vetoed or expired in the gap: the preview audio monitor the drive part's end deferred to it starts now.
        refreshRawGate()
        releaseContinuationForeground()
        return startPreviewAudioMonitorIfEligible()
    }
    if (serviceDestroyed) return
    val current = cameraState.value
    val busy = current.selectedMode != request.mode || current.phase !in setOf(CameraUiPhase.PREVIEWING, CameraUiPhase.SAVED) ||
        current.recordingFinalizing || rawTake != null || rawQualificationSession != null || transferCapture.get() != null ||
        stillOwner.get() != null || captureCountdown.active != null || recordingWbPreparing
    val accepted = !busy && try {
        continuationStarting = request
        if (request.mode == CaptureMode.RAW_VIDEO) toggleRawTake(request.audio) else localBinder.capturePrimary(request.audio)
    } finally {
        continuationStarting = null
    }
    android.util.Log.i("RecordingDestination", "Continuation ${request.stem}: ${if (accepted) "starting" else "refused (busy=$busy)"}")
    if (!accepted) {
        cameraState.update { it.copy(message = listOfNotNull(it.message, getString(R.string.recording_drive_continue_failed))
            .joinToString(" · "), messageTransient = false) }
        if (request.mode == CaptureMode.RAW_VIDEO) {
            // toggleRawTake made the continuation's phone destination the next take's: the gate must follow the
            // configured one again, the drive's when it is back.
            nextTakeDestination = resolveTakeDestination()
            refreshRawGate()
        }
        releaseContinuationForeground()
        // No take follows: the preview audio monitor the drive part's end deferred to the continuation starts now.
        startPreviewAudioMonitorIfEligible()
    }
}

/**
 * Main: the foreground service the drive part kept for its continuation is dropped once no continuation will start
 * (cancelled, expired or refused). Not while a take records or the drive part still finalizes: that path stops it.
 * The started-service lifetime the drive part's end kept for the continuation goes with it, as at any take's end; a
 * bound operator or exterior keeps the service, and settings frozen (a qualification) keep it as [onUnbind] does.
 */
internal fun CaptureService.releaseContinuationForeground() {
    val current = cameraState.value
    if (serviceDestroyed || rawTake != null || current.recordingFinalizing ||
        current.phase in setOf(CameraUiPhase.RECORDING, CameraUiPhase.CAPTURING)) return
    foreground.stop()
    if (!current.structuralSettingsFrozen) stopSelf()
}
