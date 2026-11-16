/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.service

import com.librestatic.opencinecam.capture.audioInputFallbackShown
import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import android.os.Build
import com.librestatic.opencinecam.settings.CameraSettings
import com.librestatic.opencinecam.CameraUiPhase
import com.librestatic.opencinecam.CameraUiState
import com.librestatic.opencinecam.CaptureMode
import com.librestatic.opencinecam.settings.normalizedFor
import com.librestatic.opencinecam.R
import com.librestatic.opencinecam.core.model.StableFailure
import com.librestatic.opencinecam.settings.CaptureNameSnapshot
import com.librestatic.opencinecam.capture.RawVideoConfigKeys
import com.librestatic.opencinecam.settings.captureFileStem
import com.librestatic.opencinecam.settings.continuationFileStem
import com.librestatic.opencinecam.capture.RawVideoConfigOption
import com.librestatic.opencinecam.capture.RawVideoLeftOutOption
import com.librestatic.opencinecam.capture.rawConfigLabelLocalized
import com.librestatic.opencinecam.camera.RawVideoConfig
import com.librestatic.opencinecam.camera.RawVideoQualificationSet
import com.librestatic.opencinecam.camera.RawVideoReasons
import com.librestatic.opencinecam.camera.RawVideoSessionConfig
import com.librestatic.opencinecam.rawvideo.AudioRecordRawAudioSource
import com.librestatic.opencinecam.rawvideo.RawAudioSource
import com.librestatic.opencinecam.rawvideo.RawRingBudget
import com.librestatic.opencinecam.rawvideo.RawTakeAudio
import com.librestatic.opencinecam.rawvideo.RawTakeInfo
import com.librestatic.opencinecam.rawvideo.RawTakeResult
import com.librestatic.opencinecam.storage.RawVideoOutput
import com.librestatic.opencinecam.storage.RawVideoQualificationStore
import com.librestatic.opencinecam.storage.RawVideoRecovery
import com.librestatic.opencinecam.storage.RawVideoRecoveryReport
import com.librestatic.opencinecam.storage.RecordingDestination
import com.librestatic.opencinecam.storage.RecordingDestinationStore
import com.librestatic.opencinecam.storage.releaseDriveDescriptors
import com.librestatic.opencinecam.storage.ClipContinuation
import java.util.concurrent.RejectedExecutionException
import java.util.Locale
import com.librestatic.opencinecam.settings.SettingsRepositories
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import com.librestatic.opencinecam.ModeGateState
import com.librestatic.opencinecam.capture.rawQualificationOutcome
import com.librestatic.opencinecam.capture.RawQualificationOutcomeKind
import com.librestatic.opencinecam.capture.rawQualificationShouldPost
import com.librestatic.opencinecam.capture.rawQualificationStorageBusy
import com.librestatic.opencinecam.capture.RawQualificationStopReasons
import com.librestatic.opencinecam.capture.rawQualificationUiOf
import com.librestatic.opencinecam.capture.rawVideoModeFallback
import com.librestatic.opencinecam.capture.RawVideoQualificationInfo
import com.librestatic.opencinecam.capture.RawVideoQualificationUi
import com.librestatic.opencinecam.rawvideo.RawQualificationEstimates
import com.librestatic.opencinecam.rawvideo.RawQualificationPhase
import com.librestatic.opencinecam.rawvideo.RawQualificationPlan
import com.librestatic.opencinecam.rawvideo.RawQualificationProgress
import com.librestatic.opencinecam.rawvideo.RawQualificationRisk
import com.librestatic.opencinecam.rawvideo.RawQualificationTiming
import com.librestatic.opencinecam.rawvideo.RawQualificationSpecs
import com.librestatic.opencinecam.rawvideo.RawQualificationStorageModel
import com.librestatic.opencinecam.rawvideo.RawVideoQualificationOutcome
import com.librestatic.opencinecam.rawvideo.RawVideoQualifier
import com.librestatic.opencinecam.capture.rawQualificationItemsCameraChecked
import com.librestatic.opencinecam.capture.rawQualificationItemsFinished
import com.librestatic.opencinecam.capture.rawQualificationItemsOnProgress
import com.librestatic.opencinecam.capture.rawQualificationItemsWith
import com.librestatic.opencinecam.capture.rawQualificationRunStatus
import com.librestatic.opencinecam.service.CaptureService.RawTakeSession
import com.librestatic.opencinecam.service.CaptureService.RawQualificationSession
import com.librestatic.opencinecam.service.CaptureService.Companion.RAW_QUALIFICATION_CAMERA_KIND

// Part of CaptureService: the RAW video gate, its qualification run and RAW takes.
// These are extensions so the service's state stays in one object; they keep the threading rules of its members.

/**
 * PLAN-072 Delivery 3: the qualification records RAW video is gated by, those of the destination the next RAW take
 * would use ([rawDestinationId]); [rawGateDestinationId] is the one the gate on screen was computed for.
 */
internal fun CaptureService.nextRawDestinationId(): String = rawDestinationId(nextTakeDestination.destination)

/** The destination RAW lookups read: the next take's, but a running RAW take or qualification keeps the gate's. */
internal fun CaptureService.rawLookupDestinationId(): String =
    if (rawTake != null || rawQualificationSession != null) rawGateDestinationId else nextRawDestinationId()

/**
 * Main: the next RAW take's destination changed (a drive mounted or left, a folder adopted or cleared): the RAW gate,
 * the chooser, Automatic and the armed ring follow the new destination's qualification. Never during a RAW take or a
 * qualification, which keep theirs; their end refreshes the destination again.
 */
internal fun CaptureService.refreshRawGateForDestination() {
    if (nextRawDestinationId() != rawGateDestinationId) refreshRawGate()
}

/**
 * Main: recomputes the RAW gate and lets the mode, the session and the armed ring follow a selection that changed.
 * Also when a continuation ended, was refused or was dropped: the gate held the drive take's configuration for it
 * ([rawVideoGateKey]) and goes back to the operator's choice.
 */
internal fun CaptureService.refreshRawGate() {
    if (serviceDestroyed || rawTake != null || rawQualificationSession != null) return
    val before = cameraState.value.rawVideoSelectedKey
    cameraState.value = withRawVideoGate(cameraState.value, cameraState.value.selectedCameraId)
    if (followRawSelection(before)) return
    syncRawVideoArm()
}

/**
 * Main: after a gate recompute, RAW_VIDEO leaves for its fallback mode when nothing is selected, or reopens the
 * session when the selection changed from [before]; neither while structural settings are frozen. True when the mode
 * changed (its reopen arms what it needs).
 */
internal fun CaptureService.followRawSelection(before: String?): Boolean {
    val current = cameraState.value
    if (current.selectedMode != CaptureMode.RAW_VIDEO) return false
    val fallback = rawVideoModeFallback(current.selectedMode, current.modeGates, rawModeFallback, hasConfig = current.rawVideoSelectedKey != null)
    if (fallback != null && !current.structuralSettingsFrozen) { localBinder.selectMode(fallback, reopen = true); return true }
    if (current.rawVideoSelectedKey != before && !current.structuralSettingsFrozen) {
        attachedPreviewSurface?.takeIf { it.isValid }?.let { localBinder.attachPreview(it, attachedPreviewRotationDegrees) }
    }
    return false
}

internal fun CaptureService.rawControlExecute(task: Runnable): Boolean = try {
    rawControl.execute(task)
    true
} catch (_: RejectedExecutionException) {
    false
}

internal fun CaptureService.rawQualifiedConfigs(cameraId: String): List<RawVideoConfig> = runCatching {
    val set = rawQualifications ?: rawQualificationStore.load().also { rawQualifications = it }
    set.qualifiedConfigs(rawQualificationStore.fingerprint, rawQualificationStore.protocolVersion,
        rawLookupDestinationId(), cameraId)
}.getOrElse { failure ->
    android.util.Log.w("CaptureService", "RAW video qualifications unavailable", failure)
    emptyList()
}

/** Configurations of [cameraId] the storage is too slow for, still choosable by hand ([RawQualificationRisk]). */
internal fun CaptureService.rawRiskyRecordedConfigs(cameraId: String): List<RawVideoConfig> = runCatching {
    val set = rawQualifications ?: rawQualificationStore.load().also { rawQualifications = it }
    RawQualificationRisk.riskyConfigs(set, rawQualificationStore.fingerprint, rawQualificationStore.protocolVersion,
        rawLookupDestinationId(), cameraId)
}.getOrElse { emptyList() }

/** RAW10 output sizes of [cameraId]; cached once read, an unreadable camera reads as none. */
internal fun CaptureService.raw10Sizes(cameraId: String): List<Pair<Int, Int>> = rawRaw10Sizes[cameraId] ?: runCatching {
    getSystemService(android.hardware.camera2.CameraManager::class.java).getCameraCharacteristics(cameraId)
        .get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        ?.getOutputSizes(android.graphics.ImageFormat.RAW10)?.map { it.width to it.height }.orEmpty()
}.getOrNull()?.also { rawRaw10Sizes[cameraId] = it }.orEmpty()

/** Configurations of [cameraId] whose newest record failed for the camera, not the storage: their compressed twins stay out. */
internal fun CaptureService.rawCameraFailedConfigs(cameraId: String): List<RawVideoConfig> = runCatching {
    val set = rawQualifications ?: rawQualificationStore.load().also { rawQualifications = it }
    RawQualificationRisk.cameraFailedConfigs(set, rawQualificationStore.fingerprint, rawQualificationStore.protocolVersion,
        rawLookupDestinationId(), cameraId)
}.getOrElse { emptyList() }

/**
 * PLAN-072 Delivery 4: [config] is qualified for the phone (`mediastore-documents`), and not only as a risky rate, so a
 * RAW take on a drive that was lost may continue on the phone with it. Reads the store directly: during the take the
 * gate still describes the drive.
 */
internal fun CaptureService.rawQualifiedOnPhone(config: RawVideoConfig): Boolean = runCatching {
    val set = rawQualifications ?: rawQualificationStore.load().also { rawQualifications = it }
    val phone = RawVideoQualificationStore.DESTINATION_MEDIASTORE_DOCUMENTS
    val key = RawVideoConfigKeys.key(config)
    val fingerprint = rawQualificationStore.fingerprint
    val protocol = rawQualificationStore.protocolVersion
    set.qualifiedConfigs(fingerprint, protocol, phone, config.cameraId).any { RawVideoConfigKeys.key(it) == key } &&
        RawQualificationRisk.riskyConfigs(set, fingerprint, protocol, phone, config.cameraId).none { RawVideoConfigKeys.key(it) == key }
}.getOrDefault(false)

/** The configurations a RAW_VIDEO session can run on [cameraId]: the qualified ones and the risky ones. */
internal fun CaptureService.rawUsableConfigs(cameraId: String): List<RawVideoConfig> {
    val sizes = raw10Sizes(cameraId)
    return if (sizes.isEmpty()) emptyList() else rawVideoUsableConfigs(cameraId, sizes,
        rawQualifiedConfigs(cameraId) + rawRiskyRecordedConfigs(cameraId), cameraFailed = rawCameraFailedConfigs(cameraId))
}

/**
 * The armed configuration of [cameraId] when its ring is smaller than its newest qualification
 * needed ([rawVideoRingTooSmall]): shown as risky while that ring stays armed, but never
 * steering Automatic, which keeps choosing among the passed configurations.
 */
internal fun CaptureService.rawRuntimeRiskyConfig(cameraId: String, usable: List<RawVideoConfig>): RawVideoConfig? {
    val armed = rawArmedRing ?: return null
    val config = armed.config.takeIf { it.cameraId == cameraId && it in usable } ?: return null
    val current = runCatching {
        (rawQualifications ?: rawQualificationStore.load().also { rawQualifications = it })
            .current(rawQualificationStore.fingerprint, rawQualificationStore.protocolVersion)
    }.getOrElse { return null }
    val record = rawVideoNewestRecord(current, rawLookupDestinationId(), config)
    return config.takeIf { rawVideoRingTooSmall(record, armed.ringBytes) }
}

/**
 * The burst estimate of each [risky] configuration whose newest record has one: how long a take
 * lasts before the write cache fills and frames may drop ([RawVideoQualificationRecord.burstSeconds]).
 */
internal fun CaptureService.rawRiskyBurstSeconds(risky: Set<RawVideoConfig>): Map<RawVideoConfig, Long> {
    if (risky.isEmpty()) return emptyMap()
    val current = runCatching {
        (rawQualifications ?: rawQualificationStore.load().also { rawQualifications = it })
            .current(rawQualificationStore.fingerprint, rawQualificationStore.protocolVersion)
    }.getOrElse { return emptyMap() }
    return risky.mapNotNull { config ->
        rawVideoNewestRecord(current, rawLookupDestinationId(), config)?.burstSeconds?.let { config to it }
    }.toMap()
}

/**
 * The bundled RAW noise profile a block-pack [config] records near-lossless with: its camera's,
 * measured at its sensor size, with the native codec loaded and, unless [ignoreSetting], near-lossless
 * chosen ([CameraSettings.rawVideoNearLossless]). Null means lossless block-pack.
 */
internal fun CaptureService.rawNearLosslessProfile(config: RawVideoConfig, ignoreSetting: Boolean = false): com.librestatic.opencinecam.rawvideo.RawNoiseProfile? =
    if ((!ignoreSetting && !settings.rawVideoNearLossless) || config.encoding != RawVideoConfig.ENCODING_BLOCK_PACK ||
        !com.librestatic.opencinecam.media.raw.NativeRawCodec.available) null
    else com.librestatic.opencinecam.rawvideo.RawNoiseProfiles.forCamera(this, config.cameraId, config.sensorWidth, config.sensorHeight)

/** The risky ones among [usable] configurations of [cameraId]. */
internal fun CaptureService.rawRiskyConfigs(cameraId: String, usable: List<RawVideoConfig>): Set<RawVideoConfig> =
    if (usable.isEmpty()) emptySet() else rawVideoRiskyConfigs(usable, rawRiskyRecordedConfigs(cameraId))

/** The reduced-rate ones among [usable] configurations, with the rate each achieved ([rawVideoReducedRates]). */
internal fun CaptureService.rawReducedRates(usable: List<RawVideoConfig>): Map<RawVideoConfig, Double?> {
    if (usable.isEmpty()) return emptyMap()
    val current = runCatching {
        (rawQualifications ?: rawQualificationStore.load().also { rawQualifications = it })
            .current(rawQualificationStore.fingerprint, rawQualificationStore.protocolVersion)
    }.getOrElse { return emptyMap() }
    return rawVideoReducedRates(usable, current, rawLookupDestinationId())
}

/** The configurations of [cameraId] the gate's destination judged that are not [usable], with why ([rawVideoLeftOutConfigs]). */
internal fun CaptureService.rawLeftOutOptions(cameraId: String, sizes: List<Pair<Int, Int>>, usable: List<RawVideoConfig>): List<RawVideoLeftOutOption> {
    val current = runCatching {
        (rawQualifications ?: rawQualificationStore.load().also { rawQualifications = it })
            .current(rawQualificationStore.fingerprint, rawQualificationStore.protocolVersion)
    }.getOrElse { return emptyList() }
    return rawVideoLeftOutOptions(rawVideoLeftOutConfigs(cameraId, sizes, current, rawGateDestinationId, usable))
}

/**
 * [state] with the RAW_VIDEO gate, the qualified configurations and the selected one for
 * [cameraId]. In RAW_VIDEO the take geometry and rate follow the selected configuration.
 */
internal fun CaptureService.withRawVideoGate(state: CameraUiState, cameraId: String?): CameraUiState {
    rawGateDestinationId = rawLookupDestinationId()
    val sizes = cameraId?.let(::raw10Sizes).orEmpty()
    val usable = if (cameraId == null || sizes.isEmpty()) emptyList() else rawUsableConfigs(cameraId)
    val risky = if (cameraId == null) emptySet() else rawRiskyConfigs(cameraId, usable)
    val runtimeRisky = cameraId?.let { rawRuntimeRiskyConfig(it, usable) }
    val burst = rawRiskyBurstSeconds(risky)
    val reduced = rawReducedRates(usable)
    val gate = rawVideoGate(sizes, usable, cameraId in rawVideoFailedCameras)
    val available = gate == ModeGateState.AVAILABLE
    // A risky or reduced-rate configuration runs only when chosen by hand: Automatic never resolves
    // to one, so with only such ones and Automatic chosen nothing is selected and a take is refused.
    val manualOnly = risky + reduced.keys
    // A drive take's continuation on the phone keeps that take's configuration, whatever Automatic would choose here.
    val wantedKey = rawVideoGateKey(settings.rawVideoConfig, continuationStarting ?: rawTake?.continuation ?: driveContinuation.pendingRequest)
    val selected = selectRawVideoConfig(usable, wantedKey, manualOnly).takeIf { available }
    val next = state.copy(
        modeGates = state.modeGates + (CaptureMode.RAW_VIDEO to gate),
        // The runtime risk only warns (option and capture notice); selection above ignores it.
        rawVideoOptions = if (available) usable.map {
            RawVideoConfigOption.of(it, risky = it in risky || it == runtimeRisky, burstSeconds = burst[it], achievedFps = reduced[it])
        } else emptyList(),
        rawVideoSelectedKey = selected?.let(RawVideoConfigKeys::key),
        rawVideoAutoKey = selectRawVideoConfig(usable, "", manualOnly).takeIf { available }?.let(RawVideoConfigKeys::key),
        rawVideoNearLosslessAvailable = available && usable.any { rawNearLosslessProfile(it, ignoreSetting = true) != null },
        // Display only: why judged configurations are missing, and whose records these are (the gate's own destination id).
        rawVideoLeftOut = if (cameraId == null || (gate != ModeGateState.AVAILABLE && gate != ModeGateState.CANDIDATE)) emptyList()
            else rawLeftOutOptions(cameraId, sizes, usable),
        rawVideoProfile = rawVideoProfile(rawGateDestinationId, nextTakeDestination, state.recordingDestination.driveLabel),
    )
    return if (next.selectedMode == CaptureMode.RAW_VIDEO && selected != null) {
        // The status line shows the RAW time left before a take too, so the free space is read here, not only by the REC ticker.
        // Idle, this is the phone's figure; with the next take on a drive the HUD reads the drive's own (captureStatusFreeBytes).
        next.copy(targetFps = selected.fps, targetVideoWidth = selected.cropWidth, targetVideoHeight = selected.cropHeight,
            availableStorageBytes = if (next.phase == CameraUiPhase.RECORDING) next.availableStorageBytes else phoneVolumeFreeBytes())
    } else next
}

/** The configuration a RAW_VIDEO session runs, or null outside RAW_VIDEO or without a qualified one. */
internal fun CaptureService.currentRawVideoConfig(state: CameraUiState = cameraState.value): RawVideoConfig? {
    if (state.selectedMode != CaptureMode.RAW_VIDEO || state.modeGates[CaptureMode.RAW_VIDEO] != ModeGateState.AVAILABLE) return null
    val cameraId = state.selectedCameraId ?: return null
    return rawUsableConfigs(cameraId).firstOrNull { RawVideoConfigKeys.key(it) == state.rawVideoSelectedKey }
}

/** The RAW10 stream of the session: the full sensor readout at the selected rate (the recorder crops). */
internal fun CaptureService.rawVideoSessionConfig(): RawVideoSessionConfig? =
    currentRawVideoConfig()?.let { RawVideoSessionConfig(it.sensorWidth, it.sensorHeight, it.fps) }

/**
 * Keeps the RAM ring armed for the current configuration while RAW_VIDEO is selected with a
 * viewfinder, and frees it otherwise. Never during a take. The work runs on [rawControl].
 */
internal fun CaptureService.syncRawVideoArm() {
    if (serviceDestroyed || rawTake != null) return
    // A qualification allocates its own ring: the two never coexist.
    val wanted = currentRawVideoConfig().takeIf { attachedPreviewSurface != null && rawQualificationSession == null }
    // A block-pack configuration records near-lossless when its camera has a noise profile and the setting is on.
    val noiseProfile = wanted?.let { rawNearLosslessProfile(it) }
    val target = wanted?.let { it to noiseProfile }
    if (target == rawArmTarget) return
    rawArmTarget = target
    rawControlExecute {
        if (rawRecorder.isRecording) return@rawControlExecute
        runCatching { rawRecorder.disarm() }
        val wasArmed = rawArmedRing != null
        rawArmedRing = null
        if (wanted == null) {
            rawArmNoMemory = null
            if (wasArmed) postRawVideoGate()
            return@rawControlExecute
        }
        val plan = runCatching { RawRingBudget.plan(this, wanted.bytesPerFrame.toInt()) }.getOrNull()
        try {
            checkNotNull(plan) { "no RAM budget for a ring of ${RawRingBudget.MIN_SLOTS} frames" }
            val armed = rawRecorder.arm(wanted, plan.ringBytes, noiseProfile)
            rawArmNoMemory = null
            // Re-evaluate the runtime risk against the ring actually armed.
            rawArmedRing = armed
            postRawVideoGate()
            android.util.Log.i("CaptureService", "RAW ring armed: ${armed.slotCount} slots of ${armed.slotBytes} bytes (${plan.limitedBy})" +
                if (armed.nearLossless) ", near-lossless (${noiseProfile?.id})" else "")
        } catch (failure: Exception) {
            rawArmFailure = if (failure is com.librestatic.opencinecam.rawvideo.RawVideoArmException) RawVideoServiceReasons.CODEC else RawVideoServiceReasons.MEMORY
            rawArmNoMemory = wanted
            android.util.Log.w("CaptureService", "RAW ring could not be armed", failure)
            if (wasArmed) postRawVideoGate()
        } catch (failure: OutOfMemoryError) {
            rawArmFailure = RawVideoServiceReasons.MEMORY
            rawArmNoMemory = wanted
            android.util.Log.w("CaptureService", "RAW ring could not be allocated", failure)
            if (wasArmed) postRawVideoGate()
        }
    }
}

/** Any thread: recompute the RAW_VIDEO gate and options on main (the armed ring changed). */
internal fun CaptureService.postRawVideoGate() {
    mainHandler.post {
        if (!serviceDestroyed) cameraState.value = withRawVideoGate(cameraState.value, cameraState.value.selectedCameraId)
    }
}

/** The camera a qualification runs on: the selected one, or before the cameras are prepared (onboarding) the first back camera with RAW10. */
internal fun CaptureService.rawQualificationCameraId(): String? = cameraState.value.selectedCameraId ?: runCatching {
    val manager = getSystemService(android.hardware.camera2.CameraManager::class.java)
    manager.cameraIdList.firstOrNull { id ->
        manager.getCameraCharacteristics(id).get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) ==
            android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK && raw10Sizes(id).isNotEmpty()
    }
}.getOrNull()

/** The largest RAW10 size the qualifier reads, as [RawVideoQualifier] picks it. */
internal fun CaptureService.rawQualificationSensorSize(cameraId: String): Pair<Int, Int>? =
    raw10Sizes(cameraId).filter { (width, height) -> width > 0 && height > 0 && width % 4 == 0 }.maxByOrNull { (width, height) -> width.toLong() * height }

/**
 * Main: what the qualification prompt and step show; a few small reads (cached characteristics, the store, StatFs).
 * PLAN-072: a drive's free space is its last sample ([cachedDestinationFreeBytes]), never a provider call on main;
 * with no sample yet there is no space estimate. The qualifier reads the drive afresh on its own thread.
 */
internal fun CaptureService.rawQualificationInfo(): RawVideoQualificationInfo {
    val cameraId = rawQualificationCameraId()
        ?: return RawVideoQualificationInfo(null, ModeGateState.UNSUPPORTED, null)
    val usable = rawUsableConfigs(cameraId)
    val gate = rawVideoGate(raw10Sizes(cameraId), usable, cameraId in rawVideoFailedCameras)
    val sensor = rawQualificationSensorSize(cameraId)
    val destination = nextTakeDestination
    val free = cachedDestinationFreeBytes(destination.destination)
    val storage = rawQualificationStorageModel(cameraId, destination.destination)
    val space = if (sensor != null && free != null) {
        RawQualificationEstimates.forFreeSpace(
            RawQualificationPlan.DEFAULT, cameraId, sensor.first, sensor.second,
            com.librestatic.opencinecam.media.raw.NativeRawCodec.available, free, storage = storage,
        )
    } else null
    val previousFailure = usable.isEmpty() && runCatching {
        (rawQualifications ?: rawQualificationStore.load().also { rawQualifications = it })
            .current(rawQualificationStore.fingerprint, rawQualificationStore.protocolVersion)
            .any { it.config.cameraId == cameraId && it.destinationId == rawDestinationId(destination.destination) }
    }.getOrDefault(false)
    return RawVideoQualificationInfo(cameraId, gate, space, previousFailure,
        rawQualificationTarget(destination, cameraState.value.recordingDestination.driveLabel), storage)
}

/**
 * Main: what is known of [destination]'s storage before a qualification of [cameraId] tests it, so the estimate
 * plans the long runs' drain and read-back. The write speed is the sweep median of the destination's newest record on this
 * build, else its rest reference, else a drive's speed measured at adoption; the ring is the newest record's of the camera.
 * Null when no speed is known (the nominal timings).
 */
internal fun CaptureService.rawQualificationStorageModel(cameraId: String, destination: RecordingDestination): RawQualificationStorageModel? {
    val destinationId = rawDestinationId(destination)
    val records = runCatching {
        (rawQualifications ?: rawQualificationStore.load().also { rawQualifications = it })
            .current(rawQualificationStore.fingerprint, rawQualificationStore.protocolVersion)
    }.getOrDefault(emptyList())
    val driveSpeed = (destination as? RecordingDestination.UsbFolder)?.let { drive ->
        runCatching { RecordingDestinationStore(this).speed(drive.volumeId)?.medianBytesPerSecond }.getOrNull()
    }
    // The last sweep on this destination wrote the way the run will; the speed measured at adoption is a shorter, kinder test.
    val speed = records.filter { it.destinationId == destinationId && (it.qualification.sweepMedianBytesPerSecond ?: 0L) > 0 }
        .maxByOrNull { it.measuredAtEpochMs }?.qualification?.sweepMedianBytesPerSecond
        ?: runCatching { rawQualificationStore.restReference(destinationId) }.getOrNull()?.takeIf { it > 0 }
        ?: driveSpeed?.takeIf { it > 0 }
    val ringBytes = records.filter { it.config.cameraId == cameraId && it.ringBytes > 0 }.maxByOrNull { it.measuredAtEpochMs }?.ringBytes
    return RawQualificationStorageModel.of(speed, ringBytes)
}

/** Qualification thread: one progress report, posted to main at most about four times a second (and on every new step). */
internal fun CaptureService.postRawQualificationProgress(session: RawQualificationSession, progress: RawQualificationProgress) {
    if (progress.phase == RawQualificationPhase.DONE) return
    val config = progress.spec?.let { spec ->
        RawQualificationSpecs.parse(spec, session.cameraId, session.sensorWidth, session.sensorHeight)?.config
    }
    val label = config?.let { RawVideoConfigOption.of(it).label }
    session.items = rawQualificationItemsOnProgress(session.items, progress, label,
        config?.let { RawQualificationTiming().neededBytes(it) } ?: 0L)
    val ui = rawQualificationUiOf(progress, label).copy(items = session.items)
    val now = android.os.SystemClock.elapsedRealtime()
    if (!rawQualificationShouldPost(session.lastPosted, ui, now - session.lastPostAtMs)) return
    postRawQualificationUi(session, ui, now)
}

/** Qualification thread: one run's result row; its configuration takes its status at once. */
internal fun CaptureService.recordRawQualificationResult(session: RawQualificationSession, result: org.json.JSONObject) {
    val kind = result.optString("kind")
    if (kind != "live" && kind != RAW_QUALIFICATION_CAMERA_KIND) return
    val spec = result.optString("spec").takeIf { it.isNotEmpty() } ?: return
    val config = RawQualificationSpecs.parse(spec, session.cameraId, session.sensorWidth, session.sensorHeight)?.config
    if (kind == RAW_QUALIFICATION_CAMERA_KIND) {
        // A camera check's verdict comes at the end; until then its configuration reads as measured.
        session.items = rawQualificationItemsCameraChecked(session.items, spec, config?.let { RawVideoConfigOption.of(it).label } ?: spec,
            config?.let { RawQualificationTiming().neededBytes(it) } ?: 0L, result.optDouble("achievedFps").takeIf { !it.isNaN() })
        val last = session.lastPosted ?: return
        postRawQualificationUi(session, last.copy(items = session.items), android.os.SystemClock.elapsedRealtime())
        return
    }
    val codes = result.optJSONArray("reasonCodes")?.let { array -> (0 until array.length()).map(array::optString) }
    val burstSeconds = if (result.isNull("burstSeconds")) null else result.optLong("burstSeconds")
    // Classified on the effective burst (the measured one, else the estimate from the stored figures), as the stored rows are.
    val effectiveBurst = if (result.has("effectiveBurstSeconds") && !result.isNull("effectiveBurstSeconds")) result.optLong("effectiveBurstSeconds") else burstSeconds
    val status = rawQualificationRunStatus(result.optString("status"), result.optString("reasonCode").ifEmpty { null },
        codes ?: listOfNotNull(result.optString("reasonCode").ifEmpty { null }), effectiveBurst)
    // The skip row carries the figure the qualifier decided on; otherwise the same timing gives it.
    val needed = result.optLong("neededBytes", config?.let { RawQualificationTiming().neededBytes(it) } ?: 0L)
    val achievedFps = result.optDouble("achievedFps").takeIf { !it.isNaN() }
    session.items = rawQualificationItemsWith(session.items, spec, status,
        config?.let { RawVideoConfigOption.of(it).label } ?: spec, needed, burstSeconds, achievedFps)
    val last = session.lastPosted ?: return
    postRawQualificationUi(session, last.copy(items = session.items), android.os.SystemClock.elapsedRealtime())
}

internal fun CaptureService.postRawQualificationUi(session: RawQualificationSession, ui: RawVideoQualificationUi, now: Long) {
    session.lastPosted = ui
    session.lastPostAtMs = now
    mainHandler.post {
        if (rawQualificationSession === session && !serviceDestroyed) cameraState.update { it.copy(rawVideoQualification = ui) }
    }
}

/**
 * Main: the run ended. Reloads the records, updates the gate, selects RAW_VIDEO when a configuration
 * passed (or once the cameras are prepared), and reopens the viewfinder when a surface is attached.
 */
internal fun CaptureService.finishRawQualification(
    session: RawQualificationSession,
    outcome: RawVideoQualificationOutcome,
    records: RawVideoQualificationSet,
) {
    if (rawQualificationSession !== session) return
    session.batteryReceiver?.let { receiver -> runCatching { unregisterReceiver(receiver) } }
    session.batteryReceiver = null
    if (serviceDestroyed) {
        rawQualificationSession = null
        return
    }
    rawQualifications = records
    // Read while the session still holds the lookups on the run's destination: a drive that left during the run
    // must not be judged by the phone's records (PLAN-072).
    val usable = rawUsableConfigs(session.cameraId).let { all -> all - rawRiskyConfigs(session.cameraId, all) }
    val previousKept = rawQualificationStorageBusy(outcome.reasonCode) && rawUsableConfigs(session.cameraId).isNotEmpty()
    rawQualificationSession = null
    val kind = rawQualificationOutcome(outcome.status, usable.size, outcome.reasonCode)
    val reason = when (kind) {
        // The drive's descriptors can be released (cancelling the run) before the volume event reaches main.
        RawQualificationOutcomeKind.CANCELLED -> session.stopReason
            ?: if (session.qualifier.driveLost) RawQualificationStopReasons.DRIVE_LOST else RawQualificationStopReasons.USER
        RawQualificationOutcomeKind.FAILED_TO_RUN -> outcome.reasonCode
        else -> null
    }
    val result = RawVideoQualificationUi(
        phase = RawQualificationPhase.DONE,
        fraction = cameraState.value.rawVideoQualification?.fraction ?: 0f,
        outcome = kind,
        passedLabels = if (kind == RawQualificationOutcomeKind.PASSED) usable.map { RawVideoConfigOption.of(it).label } else emptyList(),
        reason = reason,
        remainingMs = 0L,
        items = rawQualificationItemsFinished(session.items),
        // The run stored nothing: whatever the camera was qualified for before still holds.
        previousKept = previousKept,
    )
    cameraState.value = withRawVideoGate(cameraState.value.copy(rawVideoQualification = result), cameraState.value.selectedCameraId)
    // A requalification may fail every configuration that had passed: RAW video, if selected, gives way.
    val fallback = cameraState.value.let { rawVideoModeFallback(it.selectedMode, it.modeGates, rawModeFallback, hasConfig = it.rawVideoSelectedKey != null) }
    if (fallback != null) {
        localBinder.selectMode(fallback, reopen = false)
    } else if (kind == RawQualificationOutcomeKind.PASSED && session.selectOnPass) {
        val current = cameraState.value
        if (current.cameras.isNotEmpty() && current.selectedCameraId == session.cameraId &&
            CameraUiState.isModeSelectable(current.modeGates[CaptureMode.RAW_VIDEO])) {
            localBinder.selectMode(CaptureMode.RAW_VIDEO, reopen = false)
        } else if (current.cameras.isEmpty()) {
            rawQualificationSelectPending = true
        }
    }
    val message = when (kind) {
        RawQualificationOutcomeKind.PASSED ->
            resources.getQuantityString(R.plurals.raw_qualification_message_ready, usable.size, usable.size)
        RawQualificationOutcomeKind.NO_CONFIG ->
            getString(R.string.raw_qualification_message_none, modeDisplayName(cameraState.value.selectedMode))
        else -> null
    }
    if (message != null) cameraState.update { it.copy(message = message, messageTransient = true) }
    val surface = attachedPreviewSurface?.takeIf { it.isValid }
    if (surface == null || cameraState.value.descriptor == null || !localBinder.attachPreview(surface, attachedPreviewRotationDegrees)) syncRawVideoArm()
}

/** One RAW recovery pass; at service start and before each RAW take, never on main. */
internal fun CaptureService.recoverRawTakes(): RawVideoRecoveryReport = try {
    RawVideoRecovery(this).recover()
} catch (failure: Exception) {
    android.util.Log.e("RawVideoRecovery", "Interrupted RAW takes could not be recovered", failure)
    RawVideoRecoveryReport(unresolved = 1)
}

/** Main: the camera refused the RAW graph. RAW_VIDEO becomes FAILED for this camera and the operator returns to the previous mode. */
internal fun CaptureService.rawVideoGraphRejected(reason: String, message: String) {
    val cameraId = cameraState.value.selectedCameraId
    if (reason == RawVideoReasons.GRAPH_REJECTED && cameraId != null) rawVideoFailedCameras += cameraId
    cameraState.value = withRawVideoGate(cameraState.value, cameraId)
    android.util.Log.w("CaptureService", "RAW video unavailable on camera $cameraId ($reason): $message")
    syncRawVideoArm()
    leaveFailedRawVideo()
}

/** Leaves RAW_VIDEO for the mode it was entered from (or VIDEO) once its gate is no longer AVAILABLE. */
internal fun CaptureService.leaveFailedRawVideo() {
    val current = cameraState.value
    if (current.selectedMode != CaptureMode.RAW_VIDEO || rawTake != null || current.structuralSettingsFrozen ||
        current.modeGates[CaptureMode.RAW_VIDEO] != ModeGateState.FAILED) return
    val fallback = rawModeFallback?.takeIf { it != CaptureMode.RAW_VIDEO && CameraUiState.isModeSelectable(current.modeGates[it]) }
        ?: CaptureMode.VIDEO
    val text = getString(R.string.raw_video_unavailable, modeDisplayName(fallback))
    pendingStatusMessage = text
    localBinder.selectMode(fallback)
    cameraState.update { it.copy(message = text, errorCode = RawVideoServiceReasons.FAILED) }
}

internal fun CaptureService.formatBytes(bytes: Long): String = android.text.format.Formatter.formatFileSize(this, bytes)

internal fun CaptureService.rawRefusalText(reason: String, config: RawVideoConfig?, freeBytes: Long?): String = when (reason) {
    RawVideoServiceReasons.UNSUPPORTED -> getString(R.string.raw_video_unsupported)
    RawVideoServiceReasons.FAILED -> getString(R.string.raw_video_failed)
    RawVideoServiceReasons.MEMORY -> getString(R.string.raw_video_memory)
    RawVideoServiceReasons.CODEC -> getString(R.string.raw_video_codec_unavailable)
    RawVideoServiceReasons.STORAGE -> if (config == null || freeBytes == null) getString(R.string.raw_video_storage_unknown)
        else getString(R.string.raw_video_storage, formatBytes(rawTakeMinimumFreeBytes(config)), formatBytes(freeBytes))
    RawVideoServiceReasons.START_FAILED -> getString(R.string.raw_video_start_failed)
    else -> getString(R.string.raw_video_not_qualified)
}

internal fun CaptureService.rawEndText(reason: String): String = getString(when (rawTakeEndKind(reason)) {
    RawTakeEndKind.RING_FULL -> R.string.raw_video_end_ring_full
    RawTakeEndKind.WRITER -> R.string.raw_video_end_writer
    RawTakeEndKind.STREAM -> R.string.raw_video_end_stream
    RawTakeEndKind.FRAME -> R.string.raw_video_end_frame
    RawTakeEndKind.STALLED -> R.string.raw_video_end_stalled
    RawTakeEndKind.OTHER -> R.string.raw_video_end_other
})

/**
 * Main: the record button in RAW_VIDEO. Starts a take (admission, foreground service, then the
 * control thread opens the file, starts the recorder and only then the engine) or stops the
 * running one. Never MediaRecorder, never an encoded fallback. Sound follows the video take's
 * settings and permission check (PLAN-071 §7.1) and goes into the `.ocraw` as PCM16.
 */
internal fun CaptureService.toggleRawTake(audioForThisTake: Boolean? = null): Boolean {
    rawTake?.let { cancelPendingContinuation(); return stopRawTake(it, null) }
    val current = cameraState.value
    if (current.phase !in setOf(CameraUiPhase.PREVIEWING, CameraUiPhase.SAVED) || current.recordingFinalizing ||
        transferCapture.get() != null) return false
    // PLAN-072 Delivery 3: the take goes where an encoded take would, gated by that destination's qualification and free
    // space. A destination that changed since the gate was computed (a refresh still on its way) recomputes it first.
    // Delivery 4: a continuation always goes to the phone, gated by the phone's qualification and free space.
    val continuation = continuationStarting?.takeIf { it.mode == CaptureMode.RAW_VIDEO }
    val destination = resolveTakeDestination().let { if (continuation != null) continuationDestination(it) else it }
    destination.fallback?.let { android.util.Log.w("RecordingDestination", "RAW take falls back to the phone: $it") }
    // The volume event count is not part of where the take goes.
    val destinationChanged = destination.copy(volumeEvents = 0) != nextTakeDestination.copy(volumeEvents = 0)
    if (destinationChanged) nextTakeDestination = destination
    // A gate computed before the drive take armed its continuation may hold another selection: the same recompute.
    val staleSelection = continuation != null && cameraState.value.rawVideoSelectedKey != continuation.rawConfigKey
    if ((destinationChanged && rawDestinationId(destination.destination) != rawGateDestinationId) || staleSelection) {
        val selectedBefore = cameraState.value.rawVideoSelectedKey
        cameraState.value = withRawVideoGate(cameraState.value, cameraState.value.selectedCameraId)
        if (cameraState.value.rawVideoSelectedKey != selectedBefore) {
            // The live session still streams the configuration selected before, at its size and rate: reopen it for the
            // new one, as refreshRawGate does, and refuse this press; the next one records the new configuration. A
            // continuation is refused too, and ends with its "could not continue" outcome (startDriveContinuation).
            if (!followRawSelection(selectedBefore)) syncRawVideoArm()
            android.util.Log.w("CaptureService", "RAW take refused: the selection changed from $selectedBefore to " +
                "${cameraState.value.rawVideoSelectedKey}; the session reopens")
            val reason = if (cameraState.value.rawVideoSelectedKey == null) RawVideoServiceReasons.NOT_QUALIFIED
                else RawVideoServiceReasons.START_FAILED
            cameraState.update { it.copy(message = rawRefusalText(reason, null, null), errorCode = reason, messageTransient = false) }
            return false
        }
        syncRawVideoArm()
    }
    val gated = cameraState.value
    val config = currentRawVideoConfig(gated)
    // The continuation records the drive take's configuration or nothing. The gate keeps that configuration selected
    // while the continuation is pending ([rawVideoGateKey]), so this only fails when the phone cannot use it.
    if (continuation != null && config?.let(RawVideoConfigKeys::key) != continuation.rawConfigKey) {
        android.util.Log.w("RecordingDestination", "RAW continuation refused: the phone selects ${config?.let(RawVideoConfigKeys::key)}, not ${continuation.rawConfigKey}")
        return false
    }
    val free = destinationFreeBytes(destination.destination)
    val admission = admitRawTake(gated.modeGates[CaptureMode.RAW_VIDEO], config, config != null && rawArmNoMemory == config, free)
    if (admission is RawTakeAdmission.Refuse) {
        val reason = if (admission.reason == RawVideoServiceReasons.MEMORY) rawArmFailure else admission.reason
        cameraState.update { it.copy(message = rawRefusalText(reason, config, free), errorCode = reason, messageTransient = false) }
        return false
    }
    val admitted = checkNotNull(config)
    val audioRequested = when (RawTakeAudio.decide(audioForThisTake ?: settings.audioEnabled,
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)) {
        RawTakeAudio.NONE -> false
        RawTakeAudio.RECORD -> true
        RawTakeAudio.REFUSE_PERMISSION -> {
            cameraState.update { it.copy(message = getString(R.string.capture_microphone_permission_required),
                errorCode = "microphone-permission-required", messageTransient = false) }
            return false
        }
    }
    // A new take starts with no clip latched and no overload held, as a video take does.
    resetAudioOverload()
    cameraState.update { it.copy(audioClipLatched = false, audioInputClipLatched = false, audioRecordedClipLatched = false,
        audioInputOverloaded = false) }
    val audio: RawAudioSource? = if (audioRequested) {
        val takeInput = takeAudioInput(cameraState.value.audioCapabilities?.let(settings::normalizedFor) ?: settings, true) ?: return false
        stopPreviewAudioMonitor(clearLevels = false)
        AudioRecordRawAudioSource(this, takeInput.settings, current.descriptor?.timestampSourceRealtime == true,
            takeInput.guard, newAudioLevelConsumer(), newAudioListeningSink())
    } else null
    when (val start = foreground.start(audioRequested)) {
        is ForegroundRecordingStart.Rejected -> {
            cameraState.update { it.copy(message = start.failure.userMessage, errorCode = "recording-foreground-failed", messageTransient = false) }
            return false
        }
        is ForegroundRecordingStart.Started -> startService(Intent(this, CaptureService::class.java))
    }
    // A continuation keeps the drive take's names and slate (the same take number).
    val captureNames = continuation?.captureNames ?: admittedCaptureNames()
    val session = RawTakeSession(java.util.UUID.randomUUID().toString(), admitted, destination, captureNames, audioRequested, continuation)
    rawTake = session
    holdTakeDestination(destination, continuation)
    pendingStopReason = null
    cameraState.update { it.copy(phase = CameraUiPhase.CAPTURING, message = null, errorCode = null) }
    submitCaptureState(stateCommands.takeDispatched())
    if (!rawControlExecute { startRawTakeOnControl(session, captureNames, audio) }) rawTakeRefused(session, RawVideoServiceReasons.START_FAILED)
    return true
}

/** Control thread: recovery pass, output, recorder (and its audio), then the engine (PLAN-071 order). */
internal fun CaptureService.startRawTakeOnControl(session: RawTakeSession, captureNames: CaptureNameSnapshot, audio: RawAudioSource?) {
    fun refuse(reason: String) = mainHandler.post { if (!serviceDestroyed) rawTakeRefused(session, reason) }
    val recovered = recoverRawTakes()
    // Interrupted takes on a drive wait in the take journal for its next mount or the service's next start: scanning
    // a large RAW take here would hold this one in CAPTURING, and reading the drive meanwhile competes with its writes.
    val pass = com.librestatic.opencinecam.storage.RecordingRecoveryReport(rawTakes = recovered)
    if (pass != com.librestatic.opencinecam.storage.RecordingRecoveryReport()) mainHandler.post {
        if (!serviceDestroyed) cameraState.update { state ->
            state.copy(recordingRecovery = mergeRecordingRecovery(state.recordingRecovery, pass, journalPass = false))
        }
    }
    if (serviceDestroyed) { refuse(RawVideoServiceReasons.START_FAILED); return }
    if (rawRecorder.armState?.config != session.config) {
        refuse(if (rawArmNoMemory == session.config) rawArmFailure else RawVideoServiceReasons.START_FAILED)
        return
    }
    var output: com.librestatic.opencinecam.rawvideo.RawVideoTakeOutput? = null
    try {
        output = when (val destination = session.destination.destination) {
            RecordingDestination.Phone -> RawVideoOutput.create(this, captureNames, session.takeId, stem = session.continuation?.stem)
            is RecordingDestination.UsbFolder -> com.librestatic.opencinecam.storage.SafRawVideoOutput.create(this, destination, captureNames, session.takeId)
        }
        val appVersion = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty()
        // PLAN-072 Delivery 4: a continuation's `.ocraw` names the drive take it continues in static metadata keys.
        val link = session.continuation?.link?.let { mapOf("continuationOf" to it.of, "continuationPreviousName" to it.previousName,
            "continuationIndex" to it.index.toString(), "continuationReason" to it.reason) }.orEmpty()
        rawRecorder.start(output, RawTakeInfo(session.takeId, Build.MODEL, appVersion, mapOf("fingerprint" to Build.FINGERPRINT) + link), audio) { reason ->
            // The recorder cannot keep up or failed: end the take; its reason comes back from stop().
            android.util.Log.w("CaptureService", "RAW take must end: $reason")
            // A write that failed on a drive (ENODEV can come before any volume event) is the drive going: Stop and save.
            val rawDrive = session.destination.destination as? RecordingDestination.UsbFolder
            if (reason == com.librestatic.opencinecam.rawvideo.RawTakeReasons.WRITER_ERROR && rawDrive != null) {
                // On the writer's thread, before the main hop: the drive is failing, so close every descriptor on it (ADR-0037).
                releaseDriveDescriptors(rawDrive.volumeId, "a RAW write failed")
                mainHandler.post { if (rawTake === session && pendingStopReason == null) pendingStopReason = driveLostFailure(writeFailed = true) }
            }
            previewEngine.stopRawVideo()
        }
    } catch (failure: Exception) {
        android.util.Log.e("CaptureService", "RAW take could not start", failure)
        if (!rawRecorder.isRecording) runCatching { output?.discard() }
        refuse(RawVideoServiceReasons.START_FAILED)
        return
    }
    if (!previewEngine.startRawVideo(rawRecorder)) {
        // Nothing reached the file: stop() discards the empty output.
        runCatching { rawRecorder.stop() }.onFailure { android.util.Log.e("CaptureService", "Refused RAW take did not close", it) }
        refuse(RawVideoServiceReasons.START_FAILED)
        return
    }
    mainHandler.post { if (!serviceDestroyed) rawTakeStarted(session) }
}

/** Main: the engine accepted the take; the REC state, timer and live policy are the video take's. */
internal fun CaptureService.rawTakeStarted(session: RawTakeSession) {
    if (rawTake !== session) return
    session.started = true
    driveContinuation.takeStarted()
    if (cameraState.value.phase != CameraUiPhase.CAPTURING) {
        // The camera failed or reopened meanwhile; the engine's stop callback finishes the take.
        stopRawTake(session, null)
        return
    }
    submitCaptureState(stateCommands.takeStarted())
    val config = session.config
    recordingStartedAtMs = android.os.SystemClock.elapsedRealtime()
    activeRecordingPolicy = settings.recordingPolicy
    // The live storage policy stops a take below its stop headroom. A RAW take is admitted with
    // RAW_TAKE_MIN_SECONDS of room, so that is its headroom, not the encoded takes' capped minute.
    takeProjectedBytesPerMinute = rawTakeMinimumFreeBytes(config)
    takeStopHeadroomBytes = takeProjectedBytesPerMinute
    takeLengthLimitMs = null
    admissionWarning = null
    takeSoftwareEncoder = null
    takeCodecFallback = false
    runtimeNotice = null
    avNotice = null
    cadenceNotice = null
    avDiagnostics = null
    val free = recordingVolumeFreeBytes()
    lastKnownFreeBytes = free
    val option = RawVideoConfigOption.of(config)
    mainHandler.removeCallbacks(recordingTicker)
    cameraState.update { it.copy(
        phase = CameraUiPhase.RECORDING,
        message = getString(R.string.raw_video_recording, rawConfigLabelLocalized(option.label,
            getString(if (rawArmedRing?.nearLossless == true) R.string.raw_video_label_near_lossless else R.string.raw_video_label_compressed)),
            kotlin.math.round(option.megabytesPerSecond).toLong()) +
            " · " + rawTakeAudioLabel(),
        audioInputFallback = audioInputFallbackShown(rawRecorder.liveAudioInputFallbackReason()),
        recordingElapsedMs = 0L,
        recordingPauseStatus = null,
        recordingAvTiming = null,
        recordingPausePending = false,
        recordingWidth = config.cropWidth,
        recordingHeight = config.cropHeight,
        availableStorageBytes = free,
        recordingWarning = null,
        errorCode = null,
        rawVideoRingPercent = 0,
        rawVideoFramesWritten = 0L,
    ) }
    mainHandler.post(recordingTicker)
    if (session.stopRequested) stopRawTake(session, null)
}

/** The REC line's audio part, as a video take shows it: the PCM format, or "no audio". */
internal fun CaptureService.rawTakeAudioLabel(): String {
    val sampleRate = rawRecorder.liveAudioSampleRate()
    val channels = rawRecorder.liveAudioChannels()
    return if (sampleRate == null || channels == null) getString(R.string.capture_no_audio)
        else "PCM 16-bit · ${kiloHertzText(sampleRate, messageLocale)} kHz · $channels ch"
}

/** The saved notice's audio part: the PCM format, and a note when the sound did not start or has gaps. */
internal fun CaptureService.rawSavedAudioSuffix(result: RawTakeResult): String {
    val format = result.audioSampleRate?.let { savedAudioSuffix(SavedSidecarAudio(16, "PCM", it), "", messageLocale) }.orEmpty()
    val note = when {
        result.audioError != null && result.audioSampleRate == null -> getString(R.string.capture_lossless_audio_start_failed)
        result.audioSampleRate != null && (result.audioDroppedFrames > 0 || result.audioError != null) ->
            getString(R.string.raw_video_audio_incomplete)
        else -> null
    }
    return format + note?.let { " · $it" }.orEmpty()
}

/** Main: stops [session]; before the engine accepted it the stop is remembered and applied then. */
internal fun CaptureService.stopRawTake(session: RawTakeSession, reason: StableFailure?): Boolean {
    if (reason != null && pendingStopReason == null) {
        android.util.Log.w("CaptureService", "Stopping RAW take: ${reason.code} (${reason.component}) ${reason.userMessage}")
        pendingStopReason = reason
    }
    if (!session.started) {
        session.stopRequested = true
        return true
    }
    if (cameraState.value.recordingFinalizing) return false
    cameraState.value = cameraState.value.copy(recordingFinalizing = true)
    previewEngine.stopRawVideo()
    submitCaptureState(stateCommands.takeStopRequested())
    return true
}

/** RAW thread: the engine ended the take. The recorder drains and closes on the control thread. */
internal fun CaptureService.rawVideoTakeStopped(engineReason: String?) {
    val finish = Runnable {
        val result = if (rawRecorder.isRecording) {
            try { rawRecorder.stop() } catch (failure: Exception) {
                android.util.Log.e("CaptureService", "RAW take did not finish", failure)
                null
            }
        } else null
        mainHandler.post { if (!serviceDestroyed) finishRawTake(result, engineReason) }
    }
    if (!rawControlExecute(finish)) finish.run()
}

/** Main: publishes the end of the take, with its reason when it ended early. */
internal fun CaptureService.finishRawTake(result: RawTakeResult?, engineReason: String?) {
    val session = rawTake
    if (session == null) {
        android.util.Log.w("CaptureService", "RAW take ended without a session: ${result?.reason ?: engineReason}")
        return
    }
    rawTake = null
    mainHandler.removeCallbacks(recordingTicker)
    val policy = pendingStopReason
    pendingStopReason = null
    submitCaptureState(stateCommands.takeEnded())
    val reason = rawTakeEndReason(result?.reason, engineReason, null)
    val onDrive = session.destination.destination is RecordingDestination.UsbFolder
    // A drive take its output relinquished stays journalled; a pass of a drive that mounted again meanwhile skipped
    // it while it was live, so queue one now instead of waiting for the next mount, as relinquishTake does.
    if (onDrive && result != null && result.uri == null && result.outputError != null) {
        queueRecordingRecovery("a RAW take on a drive was left pending")
    }
    // PLAN-072 Delivery 4: the volume event and a writer's ENODEV both end here with the drive's reason, once per take.
    val continuationNote = decideDriveContinuation(policy, onDrive, CaptureMode.RAW_VIDEO, highSpeed = false,
        rawQualifiedOnPhone = rawQualifiedOnPhone(session.config),
        phoneRoom = phoneVolumeFreeBytes()?.let { it >= rawTakeMinimumFreeBytes(session.config) } == true) {
        val parentName = result?.displayName ?: (captureFileStem(session.takeId, session.captureNames) + RawVideoOutput.EXTENSION)
        ContinuationRequest(CaptureMode.RAW_VIDEO, continuationFileStem(parentName.removeSuffix(RawVideoOutput.EXTENSION), 1),
            ClipContinuation(session.takeId, parentName, 1, requireNotNull(policy).correlationId), session.captureNames, session.audio,
            rawConfigKey = RawVideoConfigKeys.key(session.config))
    }
    val outcome = when {
        result?.uri != null -> getString(R.string.raw_video_saved, result.framesWritten,
            String.format(java.util.Locale.getDefault(), "%.1f", result.mediaDurationNs / 1e9)) + rawSavedAudioSuffix(result)
        // A drive take left unfinished stays journalled on the drive and is repaired when the drive is mounted again.
        result == null || result.framesWritten > 0 || result.outputError != null ->
            getString(if (onDrive) R.string.raw_video_not_finalised_drive else R.string.raw_video_not_finalised)
        else -> getString(R.string.raw_video_empty)
    }
    val message = when {
        // The drive going is the root cause of a writer failure on it, so its reason wins.
        policy?.component == RECORDING_DRIVE_COMPONENT -> getString(R.string.take_stopped_reason, stopReasonText(policy), outcome)
        reason != null -> getString(R.string.take_stopped_reason, rawEndText(reason), outcome)
        policy != null -> getString(R.string.take_stopped_reason, stopReasonText(policy), outcome)
        else -> outcome
    }.let { text -> continuationNote?.let { "$text · $it" } ?: text }
    android.util.Log.i("CaptureService", "RAW take ${session.takeId} ended: reason=$reason policy=${policy?.code} " +
        "frames=${result?.framesWritten} captured=${result?.framesCaptured} peakRing=${result?.peakRingFraction} error=${result?.outputError} " +
        "audio=${result?.audioSampleRate} audioFrames=${result?.audioFramesWritten} audioDropped=${result?.audioDroppedFrames} audioError=${result?.audioError}")
    retireAudioLevelConsumer(clearLevels = true)
    runtimeNotice = null
    avNotice = null
    cadenceNotice = null
    cameraState.update { it.copy(
        phase = if (it.phase in setOf(CameraUiPhase.RECORDING, CameraUiPhase.CAPTURING)) {
            if (result?.uri != null) CameraUiPhase.SAVED else CameraUiPhase.PREVIEWING
        } else it.phase,
        recordingFinalizing = false,
        lastSavedUri = result?.uri?.toString() ?: it.lastSavedUri,
        message = message,
        messageTransient = false,
        errorCode = reason,
        recordingWarning = null,
        rawVideoRingPercent = null,
        rawVideoFramesWritten = null,
    ) }
    clearTakeDestination()
    // As for an encoded take, a pending continuation keeps the foreground service through the gap.
    if (!driveContinuation.isPending) foreground.stop()
    if (session.continuation != null) refreshRawGate()
    syncRawVideoArm()
    leaveFailedRawVideo()
    mainHandler.post { if (!serviceDestroyed) localBinder.applySettings(SettingsRepositories.get(this).states.value) }
    // After the foreground stop above and the settings: the continuation's own start must come last.
    if (driveContinuation.isPending) mainHandler.post { startDriveContinuation() }
    if (rawTakePreviewStale) {
        // A screen lock mid-take destroyed the SurfaceView; the session kept rendering to it and
        // nothing could swap it. The camera is free again, so reopen the viewfinder on the live surface.
        rawTakePreviewStale = false
        mainHandler.post {
            if (!serviceDestroyed) attachedPreviewSurface?.takeIf { it.isValid }?.let { localBinder.attachPreview(it, attachedPreviewRotationDegrees) }
        }
    }
}

/** Main: the take never started; back to previewing with the reason. */
internal fun CaptureService.rawTakeRefused(session: RawTakeSession, reason: String) {
    if (rawTake !== session) return
    rawTake = null
    pendingStopReason = null
    retireAudioLevelConsumer(clearLevels = true)
    foreground.stop()
    submitCaptureState(stateCommands.takeEnded())
    val text = rawRefusalText(reason, session.config, cachedDestinationFreeBytes(session.destination.destination))
    if (cameraState.value.phase == CameraUiPhase.CAPTURING) refuseTakeStart(reason, text)
    else cameraState.update { it.copy(message = text, errorCode = reason) }
    clearTakeDestination()
    if (session.continuation != null) refreshRawGate()
    syncRawVideoArm()
    leaveFailedRawVideo()
}

/** Main, from the recording ticker: the ring fill and the frames on storage of the running take. */
internal fun CaptureService.publishRawVideoStats() {
    if (rawTake?.started != true) return
    val stats = rawRecorder.liveStats()
    if (!stats.recording) return
    val percent = (stats.ringUsedFraction * 100).toInt().coerceIn(0, 100)
    cameraState.update { it.copy(rawVideoRingPercent = percent, rawVideoFramesWritten = stats.framesWritten) }
}
