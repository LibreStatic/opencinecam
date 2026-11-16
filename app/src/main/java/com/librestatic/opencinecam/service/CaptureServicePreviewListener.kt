/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.service

import com.librestatic.opencinecam.camera.CapturedAccumulation
import com.librestatic.opencinecam.camera.CapturedBurst
import com.librestatic.opencinecam.camera.CapturedBracket
import com.librestatic.opencinecam.camera.CapturedStill
import com.librestatic.opencinecam.camera.StillImageKind
import com.librestatic.opencinecam.camera.TimelapseProgress
import com.librestatic.opencinecam.recordingTimecodeJson
import com.librestatic.opencinecam.captureEpochJson
import com.librestatic.opencinecam.camera.SubjectPreviewStatus
import android.os.Looper
import com.librestatic.opencinecam.timelapseProjectRate
import com.librestatic.opencinecam.projectLabel
import com.librestatic.opencinecam.CameraUiPhase
import com.librestatic.opencinecam.HardwareTruthHudState
import com.librestatic.opencinecam.hardwareTruthTake
import com.librestatic.opencinecam.TakeVideoEncoder
import com.librestatic.opencinecam.withHardwareTruth
import com.librestatic.opencinecam.RouteTruth
import com.librestatic.opencinecam.routeTruthOf
import com.librestatic.opencinecam.readbackSamples
import com.librestatic.opencinecam.CaptureMode
import com.librestatic.opencinecam.usesLogGraph
import com.librestatic.opencinecam.TimeLapseLimitMode
import com.librestatic.opencinecam.R
import com.librestatic.opencinecam.camera.Camera2CameraDescriptor
import com.librestatic.opencinecam.camera.Camera2PreviewListener
import com.librestatic.opencinecam.camera.Camera2PreviewMetadata
import com.librestatic.opencinecam.camera.RecordingWhiteBalanceStatus
import com.librestatic.opencinecam.camera.Camera2Analysis
import com.librestatic.opencinecam.recordingGainJson
import com.librestatic.opencinecam.camera.LockState
import com.librestatic.opencinecam.camera.HdrVideoFormat
import com.librestatic.opencinecam.camera.OpenCineLogSourcePath
import com.librestatic.opencinecam.camera.TapFocusState
import com.librestatic.opencinecam.camera.ZoomAnchor
import com.librestatic.opencinecam.media.audio.AvClockDiagnostics
import com.librestatic.opencinecam.media.video.softwareVideoEncoderDisclosure
import com.librestatic.opencinecam.CaptureNameSnapshot
import com.librestatic.opencinecam.continuationFileStem
import com.librestatic.opencinecam.storage.RecordingDestination
import com.librestatic.opencinecam.storage.releaseDriveDescriptors
import com.librestatic.opencinecam.storage.IncompleteTake
import com.librestatic.opencinecam.storage.ClipContinuation
import com.librestatic.opencinecam.storage.ClipSidecarProvider
import com.librestatic.opencinecam.storage.clipSidecarProvider
import com.librestatic.opencinecam.storage.finalizePreparedRecordingTake
import com.librestatic.opencinecam.transfers.CapturePublicationJournal
import com.librestatic.opencinecam.storage.AudioSidecarRecorder
import com.librestatic.opencinecam.storage.WavAudioSidecarRecorder
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.RejectedExecutionException
import com.librestatic.opencinecam.camera.monitorLutIdentity
import com.librestatic.opencinecam.camera.BakedLutEvidence
import com.librestatic.opencinecam.recordingLutJson
import com.librestatic.opencinecam.camera.OperatorLutStatus
import com.librestatic.opencinecam.camera.OperatorLutState
import com.librestatic.opencinecam.SettingsRepositories
import kotlinx.coroutines.flow.update
import org.json.JSONObject

// Part of CaptureService: the engine listener that turns camera callbacks into CameraUiState and take events.
// These are extensions so the service's state stays in one object; they keep the threading rules of its members.

internal fun CaptureService.newPreviewListener(): Camera2PreviewListener = object : Camera2PreviewListener {
    override fun onProfessionalControlsRejected(message: String) {
        mainHandler.post { cameraState.value = cameraState.value.copy(message = getString(com.librestatic.opencinecam.R.string.pro_controls_rejected, message)) }
    }
    override fun onPreviewSurfaceLost(message: String) {
        calibrationCameraLost()
        mainHandler.post {
            cancelCountdown()
            cameraState.value = cameraState.value.copy(message = getString(R.string.operator_preview_lost))
        }
    }
    override fun onOpening(descriptor: Camera2CameraDescriptor) {
        openingCount.incrementAndGet()
        if (calibrationRun?.sessionLocked == true) calibrationCameraLost()
        lensLockFallback = false
        // An unlocked start: a night session never reports a zoom range, so the logical one returns now.
        if (currentLensLockBinding(descriptor.cameraId) == null) clearLensLockRoute()
        refreshLensLockState(descriptor.cameraId)
        refreshLensCorrection(descriptor.cameraId)
        refreshSavedCalibration(descriptor)
        subjectPreviewEpoch.incrementAndGet()
        mainHandler.post { cancelCountdown(); subjectStatus.value = SubjectPreviewStatus() }
        activePreviewKey = null
        mainHandler.post { updateSubjectTarget() }
        stopPreviewAudioMonitor(clearLevels = true)
        cameraState.update { it.copy(
            phase = CameraUiPhase.OPENING,
            hardwareTruth = HardwareTruthHudState(),
            gpuViewfinder = desiredGpuViewfinder(),
            nightExtensionRunning = false,
            nightProgressPercent = null,
            torchEnabled = false,
            torchReported = null,
            photoFlashReport = null,
            torchStrengthReported = null,
            selectedCameraId = descriptor.cameraId,
            previewGraphLoss = com.librestatic.opencinecam.camera.PreviewGraphLoss.NONE,
            histogram = emptyList(),
            redHistogram = emptyList(),
            greenHistogram = emptyList(),
            blueHistogram = emptyList(),
            histogramSource = com.librestatic.opencinecam.camera.HistogramSource.FRAME,
            analysisUpdatedAtMs = 0L,
            monitoringScopes = null,
            focusPeakingMask = null,
            operatorLutStatus = currentOperatorLut?.let { OperatorLutStatus(it.cube.sha256, OperatorLutState.WAITING_FOR_GPU, selectionId = monitorLutIdentity(it)) } ?: OperatorLutStatus(),
            subjectLutStatus = waitingSubjectLutStatus(),
            analysisIntervalMs = 0L,
            tapFocusState = TapFocusState.IDLE,
            focusPullActive = false,
            focusPullTargetDiopters = null,
            focusMarks = emptyMap(),
            errorCode = null,
            message = null,
        ) }
    }

    override fun onPreviewStarted(descriptor: Camera2CameraDescriptor) {
        CaptureStartup.previewStarted()
        activePreviewKey = previewKey()
        // A preview (re)started outside CAPTURING means a never-started take already failed
        // (fail() left CAPTURING) and the engine drops its stale-generation start silently.
        // Re-observe retirement so a receipt that failed while preparation was retiring
        // cannot keep the admission after the operator changed settings instead of retrying.
        dispatchedTake.pendingStart()?.takeIf { cameraState.value.phase != CameraUiPhase.CAPTURING }?.let { dispatch ->
            releaseFailedTakeStart(previewEngine.recordingOutputRetirement(), dispatch)
        }
        mainHandler.post { updateSubjectTarget() }
        if (pendingSwitchTarget == descriptor.cameraId) {
            pendingSwitchTarget = null
            pendingSwitchPrevious = null
            cameraSwitchGeneration++
        }
        submitCaptureState(stateCommands.previewStarted())
        val pendingMessage = pendingStatusMessage.also { pendingStatusMessage = null }
        val gpuPath = previewEngine.usesGpuViewfinder()
        cameraState.update { it.copy(
            phase = CameraUiPhase.PREVIEWING,
            gpuViewfinder = gpuPath,
            nightExtensionRunning = previewEngine.nightExtensionActive(),
            selectedCameraId = descriptor.cameraId,
            aeLockSupported = descriptor.aeLockSupported,
            afLockSupported = descriptor.afLockSupported,
            focusMarks = previewEngine.getFocusMarks(),
            errorCode = null,
            message = pendingMessage,
        ) }
        startPreviewAudioMonitorIfEligible()
        // Restore the remembered zoom for this camera after the session starts.
        val remembered = perCameraZoom[descriptor.cameraId]
        if (remembered != null && remembered != 1f && descriptor.zoomSupported) {
            previewEngine.setZoomRatio(remembered)
        }
        mainHandler.post {
            applyProfessionalSettings()
            pendingPresetFocus?.let { preset ->
                pendingPresetFocus = null
                val limit = cameraState.value.descriptor?.minimumFocusDistance
                localBinder.setManualFocus(preset.focusDiopters?.let { value -> limit?.takeIf { it > 0 }?.let { value.coerceIn(0f, it) } })
                localBinder.setZoomRatio(preset.zoomRatio)
            }
        }
    }

    override fun onRecordingFirstFrame(sensorTimestampNs: Long) {
        synchronized(takeOwnershipLock) { videoOutput?.motionRecorder() }?.onFirstFrame(sensorTimestampNs)
    }

    override fun onMetadata(metadata: Camera2PreviewMetadata) {
        metadata.awbGains?.let { noteLiveIlluminant(it) }
        metadata.rollingShutterSkewNs?.let { skew ->
            latestRollingShutterSkewNs = skew
            synchronized(takeOwnershipLock) { videoOutput?.motionRecorder() }?.updateRollingShutterSkew(skew)
        }
        // Judge the scene only from regular results: the extension session reports none, so the
        // verdict (and the quick toggle with it) stays put while Night is engaged.
        val nightSuggestedNow = if (cameraState.value.selectedMode == CaptureMode.PHOTO && !nightExtensionActive() &&
            cameraState.value.descriptor?.nightExtension != null) {
            nightSceneDetector.update(System.nanoTime(), metadata.sensitivityIso, metadata.exposureTimeNs)
        } else nightSceneDetector.dark
        cameraState.update { it.copy(
            nightSuggested = nightSuggestedNow,
            sensitivityIso = metadata.sensitivityIso,
            exposureTimeNs = metadata.exposureTimeNs,
            focusDistanceDiopters = metadata.focusDistanceDiopters,
            afState = metadata.afState,
            reportedAfMode = metadata.afMode,
            submittedAfMode = metadata.submittedAfMode,
            awbState = metadata.awbState,
            reportedAwbLocked = metadata.awbLocked,
            reportedColorTemperatureK = metadata.colorTemperatureK,
            reportedColorTint = metadata.colorTint,
            reportedOpticalStabilization = metadata.opticalStabilization,
            reportedVideoStabilization = metadata.videoStabilization,
            reportedNoiseReduction = metadata.noiseReduction,
            reportedEdgeEnhancement = metadata.edgeEnhancement,
            reportedToneMapMode = metadata.toneMapMode,
            submittedToneMapMode = metadata.submittedToneMapMode,
            reportedCropRegion = metadata.cropRegion,
            submittedImageProcessing = metadata.submittedImageProcessing,
            pictureControlReadback = metadata.pictureControls,
            reportedExposureMode = metadata.exposureMode,
            reportedAntibanding = metadata.antibanding,
            reportedLowLightBoost = metadata.lowLightBoostActive,
            reportedMeteringMode = metadata.meteringMode,
            torchEnabled = metadata.torchEnabled == true,
            torchReported = metadata.torchEnabled,
            torchStrengthReported = metadata.torchStrengthLevel,
            effectiveFps = metadata.effectiveFps,
        ).let { next -> next.copy(hardwareTruth = next.hardwareTruth.withReadback(readbackSamples(next))) } }
    }

    override fun onPhotoFlashResult(report: com.librestatic.opencinecam.camera.PhotoFlashReport) {
        mainHandler.post { cameraState.value = cameraState.value.copy(photoFlashReport = report) }
    }

    override fun onJpegCaptured(bytes: ByteArray, width: Int, height: Int) {
        val captureNames = stillOwner.get()?.captureNames ?: settings.let {
            CaptureNameSnapshot(it.captureNaming, it.productionSlate, System.currentTimeMillis())
        }
        val productionSlate = captureNames.slate
        try {
            storageExecutor.execute {
                try {
                    val uri = stillSaver.saveJpeg(bytes, productionSlate, captureNames, stillDestination())
                    incrementSavedSlate(productionSlate)
                    mainHandler.post {
                        if (serviceDestroyed) return@post
                        cameraState.update { it.copy(phase = CameraUiPhase.SAVED, lastSavedUri = uri.toString(),
                            message = getString(R.string.capture_jpeg_saved, width, height), errorCode = null) }
                        localBinder.applySettings(SettingsRepositories.get(this@newPreviewListener).states.value)
                    }
                } catch (failure: Throwable) {
                    fail("jpeg-save-failed", failureText(R.string.capture_jpeg_save_failed, failure))
                }
            }
        } catch (_: RejectedExecutionException) {
            fail("jpeg-save-busy", getString(R.string.capture_still_writer_busy))
        }
    }

    override fun onStillCaptured(capture: CapturedStill) {
        val owner = stillOwner.get() ?: return
        if (serviceDestroyed || !owner.publishing.compareAndSet(false, true)) return
        try {
            storageExecutor.execute {
                try {
                    val publication = stillSaver.saveCapture(capture, owner.productionSlate, owner.captureNames, stillDestination())
                    incrementSavedSlate(owner.productionSlate)
                    val preview = publication.images.firstOrNull { it.kind != StillImageKind.DNG } ?: publication.images.first()
                    mainHandler.post {
                        if (serviceDestroyed || !stillOwner.compareAndSet(owner, null)) return@post
                        cameraState.update { it.copy(phase = CameraUiPhase.SAVED, stillCapturePending = false, nightProgressPercent = null,
                            lastSavedUri = preview.uri, lastStillPublication = publication,
                            photoFlashReport = capture.flashReport, errorCode = null,
                            message = getString(R.string.photo_capture_saved, publication.images.joinToString(" + ") { image -> image.kind.name })) }
                        localBinder.applySettings(SettingsRepositories.get(this@newPreviewListener).states.value)
                        if (activePreviewKey == null && cameraState.value.phase != CameraUiPhase.OPENING) attachedPreviewSurface?.takeIf { it.isValid }?.let {
                            localBinder.attachPreview(it, attachedPreviewRotationDegrees)
                        }
                    }
                } catch (failure: Throwable) {
                    mainHandler.post { finishStillFailure(owner, "still-save-failed", failureText(R.string.capture_still_save_failed, failure)) }
                }
            }
        } catch (_: RejectedExecutionException) {
            mainHandler.post { finishStillFailure(owner, "still-save-busy", getString(R.string.capture_still_writer_busy)) }
        }
    }

    override fun onNightCaptureProgress(percent: Int) {
        mainHandler.post {
            if (serviceDestroyed || stillOwner.get() == null) return@post
            cameraState.update { it.copy(nightProgressPercent = percent) }
        }
    }

    override fun onBurstProgress(completed: Int, total: Int) {
        val owner = stillOwner.get() ?: return
        mainHandler.post {
            if (serviceDestroyed || stillOwner.get() !== owner) return@post
            cameraState.update { it.copy(burstCaptured = completed, burstExpected = total,
                message = getString(R.string.burst_progress, completed, total)) }
        }
    }

    override fun onBurstCaptured(capture: CapturedBurst) {
        val owner = stillOwner.get() ?: return
        if (serviceDestroyed || !owner.publishing.compareAndSet(false, true)) return
        mainHandler.post {
            if (!serviceDestroyed && stillOwner.get() === owner) cameraState.update {
                it.copy(burstSaving = true, message = getString(R.string.burst_capture_saving))
            }
        }
        try {
            storageExecutor.execute {
                try {
                    val publication = stillSaver.saveBurst(capture, owner.productionSlate, owner.captureNames, stillDestination())
                    incrementSavedSlate(owner.productionSlate)
                    mainHandler.post {
                        if (serviceDestroyed || !stillOwner.compareAndSet(owner, null)) return@post
                        cameraState.update { it.copy(phase = CameraUiPhase.SAVED, stillCapturePending = false,
                            burstSaving = false, burstCaptured = publication.images.size,
                            lastSavedUri = publication.images.first().uri,
                            lastBurstPublication = publication, photoFlashReport = null, errorCode = null,
                            message = getString(R.string.burst_saved, publication.images.size)) }
                        localBinder.applySettings(SettingsRepositories.get(this@newPreviewListener).states.value)
                        if (activePreviewKey == null && cameraState.value.phase != CameraUiPhase.OPENING) attachedPreviewSurface?.takeIf { it.isValid }?.let {
                            localBinder.attachPreview(it, attachedPreviewRotationDegrees)
                        }
                    }
                } catch (failure: Throwable) {
                    mainHandler.post { finishStillFailure(owner, "burst-save-failed", failureText(R.string.capture_burst_save_failed, failure)) }
                }
            }
        } catch (_: RejectedExecutionException) {
            mainHandler.post { finishStillFailure(owner, "burst-save-busy", getString(R.string.capture_still_writer_busy)) }
        }
    }

    override fun onBracketProgress(completed: Int, total: Int) {
        val owner = stillOwner.get() ?: return
        mainHandler.post {
            if (serviceDestroyed || stillOwner.get() !== owner) return@post
            cameraState.update { it.copy(bracketCaptured = completed, bracketExpected = total,
                message = getString(R.string.bracket_capture_progress, completed, total)) }
        }
    }

    override fun onBracketCaptured(capture: CapturedBracket) {
        val owner = stillOwner.get() ?: return
        if (serviceDestroyed || !owner.publishing.compareAndSet(false, true)) return
        mainHandler.post {
            if (!serviceDestroyed && stillOwner.get() === owner) cameraState.update {
                it.copy(bracketSaving = true, message = getString(R.string.bracket_capture_saving))
            }
        }
        try {
            storageExecutor.execute {
                try {
                    val publication = stillSaver.saveBracket(capture, owner.productionSlate, owner.captureNames, stillDestination())
                    incrementSavedSlate(owner.productionSlate)
                    mainHandler.post {
                        if (serviceDestroyed || !stillOwner.compareAndSet(owner, null)) return@post
                        cameraState.update { it.copy(phase = CameraUiPhase.SAVED, stillCapturePending = false,
                            bracketSaving = false, bracketCaptured = publication.images.size,
                            lastSavedUri = publication.images[publication.images.size / 2].uri,
                            lastBracketPublication = publication, photoFlashReport = null, errorCode = null,
                            message = getString(R.string.bracket_capture_saved, publication.images.size)) }
                        localBinder.applySettings(SettingsRepositories.get(this@newPreviewListener).states.value)
                        if (activePreviewKey == null && cameraState.value.phase != CameraUiPhase.OPENING) attachedPreviewSurface?.takeIf { it.isValid }?.let {
                            localBinder.attachPreview(it, attachedPreviewRotationDegrees)
                        }
                    }
                } catch (failure: Throwable) {
                    mainHandler.post { finishStillFailure(owner, "bracket-save-failed", failureText(R.string.capture_bracket_save_failed, failure)) }
                }
            }
        } catch (_: RejectedExecutionException) {
            mainHandler.post { finishStillFailure(owner, "bracket-save-busy", getString(R.string.capture_still_writer_busy)) }
        }
    }

    override fun onAccumulationProgress(completed: Int, elapsedMs: Long, targetMs: Long) {
        val owner = stillOwner.get() ?: return
        mainHandler.post {
            if (serviceDestroyed || stillOwner.get() !== owner) return@post
            cameraState.update { it.copy(accumulationFrames = completed, accumulationElapsedMs = elapsedMs, accumulationTargetMs = targetMs,
                message = getString(R.string.accumulation_capture_progress, completed, elapsedMs / 1000, targetMs / 1000)) }
        }
    }

    override fun onAccumulationCaptured(capture: CapturedAccumulation) {
        val owner = stillOwner.get() ?: return
        if (serviceDestroyed || !owner.publishing.compareAndSet(false, true)) return
        mainHandler.post {
            if (!serviceDestroyed && stillOwner.get() === owner) cameraState.update {
                it.copy(accumulationSaving = true, message = getString(R.string.accumulation_capture_saving))
            }
        }
        try {
            storageExecutor.execute {
                try {
                    val publication = stillSaver.saveAccumulation(capture, owner.productionSlate, owner.captureNames, stillDestination())
                    incrementSavedSlate(owner.productionSlate)
                    mainHandler.post {
                        if (serviceDestroyed || !stillOwner.compareAndSet(owner, null)) return@post
                        cameraState.update { it.copy(phase = CameraUiPhase.SAVED, stillCapturePending = false,
                            accumulationSaving = false, accumulationFrames = capture.frames.size,
                            lastSavedUri = publication.image.uri,
                            lastAccumulationPublication = publication, photoFlashReport = null, errorCode = null,
                            message = getString(R.string.accumulation_capture_saved, capture.frames.size)) }
                        localBinder.applySettings(SettingsRepositories.get(this@newPreviewListener).states.value)
                        if (activePreviewKey == null && cameraState.value.phase != CameraUiPhase.OPENING) attachedPreviewSurface?.takeIf { it.isValid }?.let {
                            localBinder.attachPreview(it, attachedPreviewRotationDegrees)
                        }
                    }
                } catch (failure: Throwable) {
                    mainHandler.post { finishStillFailure(owner, "accumulation-save-failed", failureText(R.string.capture_accumulation_save_failed, failure)) }
                }
            }
        } catch (_: RejectedExecutionException) {
            mainHandler.post { finishStillFailure(owner, "accumulation-save-busy", getString(R.string.capture_still_writer_busy)) }
        }
    }

    override fun onDngCaptured(bytes: ByteArray, width: Int, height: Int) {
        val captureNames = stillOwner.get()?.captureNames ?: settings.let {
            CaptureNameSnapshot(it.captureNaming, it.productionSlate, System.currentTimeMillis())
        }
        val productionSlate = captureNames.slate
        try {
            storageExecutor.execute {
                try {
                    val uri = stillSaver.saveDng(bytes, productionSlate, captureNames, stillDestination())
                    incrementSavedSlate(productionSlate)
                    mainHandler.post {
                        if (serviceDestroyed) return@post
                        cameraState.update { it.copy(phase = CameraUiPhase.SAVED, lastSavedUri = uri.toString(),
                            message = getString(R.string.capture_dng_saved, width, height), errorCode = null) }
                        localBinder.applySettings(SettingsRepositories.get(this@newPreviewListener).states.value)
                    }
                } catch (failure: Throwable) {
                    fail("dng-save-failed", failureText(R.string.capture_dng_save_failed, failure))
                }
            }
        } catch (_: RejectedExecutionException) {
            fail("dng-save-busy", getString(R.string.capture_still_writer_busy))
        }
    }

    override fun onOperatorLutStatus(status: OperatorLutStatus) {
        // Engine delivery is already fenced and serialized with onOpening on cameraExecutor.
        cameraState.update {
            if (!serviceDestroyed && status.selectionId == monitorLutIdentity(currentOperatorLut))
                it.copy(operatorLutStatus = status) else it
        }
    }

    override fun onAnalysis(analysis: Camera2Analysis) {
        if (serviceDestroyed) return
        cameraState.update { it.copy(
            histogram = analysis.histogram,
            redHistogram = analysis.redHistogram,
            greenHistogram = analysis.greenHistogram,
            blueHistogram = analysis.blueHistogram,
            histogramSource = analysis.histogramSource,
            analysisIntervalMs = if (it.analysisUpdatedAtMs > 0) (analysis.capturedAtElapsedRealtimeMs - it.analysisUpdatedAtMs).coerceAtLeast(0) else 0,
            analysisUpdatedAtMs = analysis.capturedAtElapsedRealtimeMs,
            monitoringScopes = analysis.scopes,
            zebraCells = analysis.zebraCells,
            focusPeakingMask = analysis.focusPeaking,
        ) }
    }

    override fun onAnalysisSuspended(reason: com.librestatic.opencinecam.camera.AnalysisSuspension) {
        // cameraExecutor, on change only. The UI hides stale scopes and marks the scope
        // buttons unavailable from this field; preview and any take are unaffected.
        if (serviceDestroyed) return
        cameraState.update { it.copy(analysisSuspension = reason) }
    }

    override fun onTimelapseProgress(progress: TimelapseProgress) {
        mainHandler.post {
            val current = cameraState.value
            if (current.selectedMode != CaptureMode.TIME_LAPSE || current.phase !in setOf(CameraUiPhase.CAPTURING, CameraUiPhase.RECORDING) || current.recordingFinalizing) return@post
            cameraState.value = current.copy(timelapseFramesCaptured = progress.submittedFrames.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                timelapseMissedIntervals = progress.missedIntervals, timelapseEncoder = progress.codecName, timelapseHardwareEncoder = progress.hardwareAccelerated)
            if (current.timelapseLimitMode == TimeLapseLimitMode.FRAME_COUNT && progress.submittedFrames >= current.timelapseFrameCount) localBinder.stopRecording()
        }
    }

    override fun onTimelapsePauseChanged(status: com.librestatic.opencinecam.camera.TimelapsePauseStatus) {
        mainHandler.post {
            val current = cameraState.value
            if (current.phase != CameraUiPhase.RECORDING || current.recordingFinalizing ||
                current.recordingPauseStatus?.takeId?.let { it != status.takeId } == true) return@post
            cameraState.value = current.copy(recordingPauseStatus = status, recordingPausePending = false,
                recordingElapsedMs = status.activeElapsedAt(android.os.SystemClock.elapsedRealtime()))
            scheduleTimelapseAutoStop()
            // A bounded take counts active time: no stop while paused, the rest of its length on resume.
            scheduleTakeLengthAutoStop()
        }
    }

    override fun onRecordingLutApplied(evidence: BakedLutEvidence) {
        cameraState.update { state ->
            val intent = recordingLutIntent.get()
            if (!serviceDestroyed && intent != null && transferCapture.get() === intent.owner &&
                evidence.selectionId == monitorLutIdentity(intent.lut))
                state.copy(recordingLutStatus = OperatorLutStatus(evidence.hash, OperatorLutState.ACTIVE, selectionId = evidence.selectionId)) else state
        }
    }

    override fun onRecordingStarted(width: Int, height: Int) {
        dispatchedTake.markStarted()
        driveContinuation.takeStarted()
        submitCaptureState(stateCommands.takeStarted())
        timecodeTracker.onRecordingStarted(previewEngine.encodedRecordingProgress()?.takeId)
        previewEngine.encodedRecordingProgress()?.let(timecodeTracker::observeEncodedProgress)
        previewEngine.consumeTimelapsePauseStatus()
        previewEngine.consumeCaptureEpochReport()
        recordingStartedAtMs = android.os.SystemClock.elapsedRealtime()
        takeClipStartedAt = java.time.Instant.now()
        takeClipIntent = runCatching { clipIntentSnapshot() }.getOrNull()
        takeClipCodecMime = previewEngine.activeVideoCodec()?.mime
        takeClipMediaRecorder = previewEngine.recordsThroughMediaRecorder()
        takeCameraTracker = ClipCameraTracker().also { it.sample(cameraState.value) }
        subjectSyncMarkers.begin(android.os.SystemClock.elapsedRealtimeNanos())
        audioSidecarRecorder?.let { sidecar ->
            try {
                sidecar.start()
            } catch (failure: Throwable) {
                synchronized(takeOwnershipLock) {
                    // A concurrent failure/recovery may already own (and finish) this sidecar.
                    if (audioSidecarRecorder === sidecar) {
                        audioStartFailure = failureText(R.string.capture_lossless_audio_start_failed, failure)
                        audioSidecarRecorder = null
                    }
                }
                sidecar.finish(false)
                previewEngine.stopVideo()
                return
            }
        }
        mainHandler.removeCallbacks(recordingTicker)
        cameraState.value = cameraState.value.copy(
            phase = CameraUiPhase.RECORDING,
            message = if (cameraState.value.selectedMode == CaptureMode.HLG) {
                "REC ${width}×$height · ${hdrTakeFormat(cameraState.value).label} BT.2020 · HEVC Main10 · ${cameraState.value.targetFps} fps · $activeRecordingAudioLabel"
            } else if (cameraState.value.selectedMode.usesLogGraph) {
                val source = if (cameraState.value.activeLogProfile?.sourcePath == OpenCineLogSourcePath.SDR_BT709_ISP) {
                    getString(R.string.capture_source_hfr_isp_derived) + " · " + getString(R.string.capture_source_depth_not_claimed)
                } else getString(R.string.capture_source_hlg_derived)
                "REC ${width}×$height · ${if (recordingLutIntent.get()?.lut != null) "LUT → SDR BT.709" else "OCLog2"} · HEVC Main10 · $source · ${cameraState.value.targetFps} fps · $activeRecordingAudioLabel"
            } else if (cameraState.value.selectedMode == CaptureMode.TIME_LAPSE) {
                val intervalLabel = formatTimelapseInterval(cameraState.value.timelapseIntervalMs)
                getString(R.string.timelapse_recording, intervalLabel, cameraState.value.recordingProjectRate?.projectLabel() ?: settings.timelapseProjectRate.projectLabel())
            } else {
                "REC ${width}×$height · ${previewEngine.activeVideoCodec()?.label ?: "H.264"} · $activeRecordingAudioLabel" +
                    cameraState.value.recordingProjectRate?.let { " · " + getString(R.string.project_active_rate, it.projectLabel()) }.orEmpty()
            },
            recordingElapsedMs = 0L,
            takeLengthLimitMs = takeLengthLimitMs,
            recordingPauseStatus = null,
            recordingAvTiming = null,
            recordingPausePending = false,
            recordingWidth = width,
            recordingHeight = height,
            availableStorageBytes = recordingVolumeFreeBytes(),
            recordingWarning = recordingWarningText(),
            errorCode = null,
        )
        activeRecordingPolicy = settings.recordingPolicy
        takeStartHardwareTruth = cameraState.value.hardwareTruth
        lastKnownFreeBytes = cameraState.value.availableStorageBytes
        runtimeNotice = null
        avNotice = null
        cadenceNotice = null
        previewEngine.setRecordingCadencePolicy(activeRecordingPolicy)
        avDiagnostics = if (audioSidecarRecorder != null) AvClockDiagnostics(activeRecordingPolicy, correlationId = "recording-av-drift") else null
        pendingStopReason = null
        scheduleTimelapseAutoStop()
        scheduleTakeLengthAutoStop()
        mainHandler.post(recordingTicker)
        // PLAN-072: a drive that left while this take was starting ends it now, through the drive stop path. This
        // callback runs on the camera thread; the stop path is main's.
        mainHandler.post { if (!serviceDestroyed && rawTake == null) stopTakeLostAtStart() }
    }

    override fun onRecordingStopped(success: Boolean) {
        android.util.Log.i("CaptureService", "Recording stopped: success=$success mode=${cameraState.value.selectedMode} fps=${cameraState.value.targetFps}")
        retireAudioLevelConsumer()
        // A drive take whose muxer failed on ENODEV ends here before the volume event; name the drive as the reason.
        val takeDrive = takeDestination?.destination as? RecordingDestination.UsbFolder
        val lostDrive = takeDrive?.takeIf { drive -> !success && mountedRecordingVolumes().none { it.equals(drive.volumeId, ignoreCase = true) } }
        val stopReason = pendingStopReason ?: lostDrive?.let { driveLostFailure(writeFailed = true) }
        pendingStopReason = null
        // ADR-0037: a take ended by its drive closes every descriptor on that drive before finishing or publishing.
        if (takeDrive != null && stopReason?.component == RECORDING_DRIVE_COMPONENT) releaseDriveDescriptors(takeDrive.volumeId, "the take lost its drive")
        // PLAN-072 Delivery 4: every way a drive take ends because the drive went (the volume event, a sidecar writer's
        // ENODEV, the muxer's) converges here once per take; the continuation, if any, is armed now and started once this
        // stop has fully finished. The take's own continuation link is read before detachTake() clears it.
        val continuedFrom = takeContinuation
        val continuationNote = run {
            val parent = videoOutput ?: return@run null
            val mode = cameraState.value.selectedMode
            val intent = recordingLutIntent.get()
            decideDriveContinuation(stopReason, onDrive = takeDrive != null, mode = mode,
                highSpeed = cameraState.value.activeVideoProfile?.constrainedHighSpeed == true || takeClipMediaRecorder == true,
                rawQualifiedOnPhone = false,
                phoneRoom = admitRecordingStorage(activeRecordingPolicy, takeProjectedBytesPerMinute, phoneVolumeFreeBytes()) !is StorageAdmission.Refuse,
            ) {
                val names = intent?.captureNames ?: return@decideDriveContinuation null
                // A continuation records on the phone and cannot lose a drive, so index is 1 in practice; kept general.
                val index = (continuedFrom?.link?.index ?: 0) + 1
                val rootStem = continuedFrom?.let { it.stem.removeSuffix("_cont${it.link.index}") } ?: parent.displayName.removeSuffix(".mp4")
                ContinuationRequest(mode, continuationFileStem(rootStem, index),
                    ClipContinuation(parent.recoveryGroup.id, parent.displayName, index, requireNotNull(stopReason).correlationId),
                    names, audio = activeRecordingAudioLabel != getString(R.string.capture_no_audio))
            }
        }
        previewEngine.setRecordingCadencePolicy(null)
        // The main-thread ticker may be mid-observe on this instance: close it on main, after that tick.
        val finishedDiagnostics = avDiagnostics
        avDiagnostics = null
        if (finishedDiagnostics != null) mainHandler.post { finishedDiagnostics.close() }
        val recordingGain = activeRecordingGain
        val hardwareTruth = hardwareTruthTake(takeStartHardwareTruth, cameraState.value.hardwareTruth, takeSoftwareEncoder?.let { TakeVideoEncoder(it) })
        // Only the exact dispatched admission; a failed start may already have released it.
        val transferReservation = dispatchedTake.claimStopped()
        submitCaptureState(stateCommands.takeEnded())
        val frozenLut = recordingLutIntent.get()?.takeIf { it.owner === transferReservation }?.lut
        previewEngine.releaseRecordingWhiteBalance()
        cameraState.value = cameraState.value.copy(recordingFinalizing = true, takeLengthLimitMs = null)
        mainHandler.removeCallbacks(recordingTicker)
        cancelTimelapseAutoStop()
        cancelTakeLengthAutoStop()
        takeLengthLimitMs = null
        val take = detachTake()
        val output = take.video
        // A failed content callback alone never proves native retirement. The exact file
        // receipt can succeed even when encoding failed, but release/close failures keep it fenced.
        val retirement = previewEngine.stoppedRecordingOutputRetirement()
        val nativeRetired = retirement.isDone && !retirement.isCompletedExceptionally && !retirement.isCancelled
        if (nativeRetired) releaseRecordingRecoveryGuard(output)
        else {
            val guard = output?.let(recordingRecoveryGuards::get)
            retirement.whenComplete { _, failure ->
                if (failure == null && output != null && guard != null && recordingRecoveryGuards.remove(output, guard)) {
                    runCatching { guard.close() }.onFailure {
                        android.util.Log.e("RecordingRecovery", "Retired take cleanup retained for retry", it)
                    }
                }
            }
        }
        val transferPublication = take.publication
        var avTiming = previewEngine.consumeCaptureEpochReport()
        lastSeparateAudioAvDrift = null
        val timing = previewEngine.consumeTimelapsePauseStatus()
        val durationMs = timing?.activeElapsedMs ?: activeRecordingElapsedMs()
        val encodedProgress = previewEngine.encodedRecordingProgress()
        encodedProgress?.let(timecodeTracker::observeEncodedProgress)
        // Read now: a later take resets the engine's progress before a deferred salvage can look at it.
        val encodedFrames = encodedProgress?.frameCount
        val projectRateAtStop = cameraState.value.recordingProjectRate
        var startDeferredSalvage: (() -> Unit)? = null
        val timecodeReport = timecodeTracker.recordingReport()
        val subjectSyncMarker = subjectSyncMarkers.consume()
        // Everything the engine or the next take can overwrite is read here, on the camera thread.
        val graphEvidence = previewEngine.consumeLastOpenCineLogEvidence()
        val bakedEvidence = previewEngine.consumeLastRecordingLutEvidence()
        val takeUsedLogGraph = cameraState.value.selectedMode.usesLogGraph
        val takeAudioLabel = activeRecordingAudioLabel
        // The clip sidecar's inputs, read here for the same reason: the next take overwrites them.
        val clipStartedAt = takeClipStartedAt
        val clipIntent = takeClipIntent?.copy(timecodeEnabled = timecodeReport?.config?.enabled == true)
        val clipCodecMime = takeClipCodecMime
        val clipMediaRecorder = takeClipMediaRecorder
        val clipAudioInput = takeClipAudioInput
        val clipState = cameraState.value
        val clipCamera = takeCameraTracker?.also { it.sample(clipState) }?.result()
        // PLAN-074 decision 10: the frame anchors of a take whose audio is appended below.
        val appendBitrateBps = takeAppendedAudioBitrateBps
        val appendSyncOffsetNs = takeAppendedAudioSyncOffsetNs
        val directTiming = previewEngine.consumeDirectRecorderTiming()
        val sensorRealtime = clipState.descriptor?.timestampSourceRealtime == true
        takeAppendedAudioBitrateBps = null
        takeAppendedAudioSyncOffsetNs = 0
        takeClipMediaRecorder = null
        takeClipAudioInput = null
        takeClipStartedAt = null
        takeClipIntent = null
        takeClipCodecMime = null
        takeCameraTracker = null
        val clipDeviceProfile = clipDeviceProfile(clipState.selectedCameraId)
        val clipNoticeCodes = listOfNotNull(runtimeNotice, avNotice, cadenceNotice).map { it.name.lowercase().replace('_', '-') }
        val clipStopReason = stopReason?.let { takeStopReason(it).name.lowercase().replace('_', '-') }
        val clipSoftwareEncoder = takeSoftwareEncoder
        // PLAN-072 Delivery 4: the continuation starts after both the reservation release and the end of [finishTake].
        val continuationGate = java.util.concurrent.atomic.AtomicInteger(2)
        // Set when the reservation release left the preview audio monitor to the continuation: a continuation vetoed
        // or expired before the gate opens must not leave the monitor off.
        val monitorDeferred = java.util.concurrent.atomic.AtomicBoolean(false)
        fun continuationStep() {
            if (continuationGate.decrementAndGet() != 0) return
            if (driveContinuation.isPending) mainHandler.post { startDriveContinuation() }
            else if (monitorDeferred.get()) startPreviewAudioMonitorIfEligible()
        }
        // Publication reads the MP4 back, writes MediaStore and the sidecars. It runs on the storage
        // thread so the engine can bring the preview back at once; recordingFinalizing keeps the next
        // take out until it is done.
        val finishTake = Runnable {
            try {
                val requestedAudioFailure = take.audioStartFailure
                // HLG takes run through the same graph but are standard HDR video: no OCLog sidecar.
                val hlgEvidence = graphEvidence?.takeIf { HdrVideoFormat.fromCurve(it.curve) != null }
                val logEvidence = graphEvidence?.takeUnless { HdrVideoFormat.fromCurve(it.curve) != null }
                val bakingVerified = bakedEvidence?.selectionId == monitorLutIdentity(frozenLut)
                // ADR-0011: stable reasons a take is not fully successful; any of them makes it incomplete.
                val muxerFinalized = success && nativeRetired
                val incompleteReasons = buildList {
                    if (requestedAudioFailure != null) add(IncompleteTake.AUDIO_START_FAILED)
                    if (!success) add(IncompleteTake.ENCODER_FAILED)
                    if (!nativeRetired) add(IncompleteTake.NATIVE_RETIREMENT_PENDING)
                    if (!muxerFinalized) add(IncompleteTake.MUXER_NOT_FINALIZED)
                    if (!bakingVerified) add(IncompleteTake.RECORDING_LUT_UNVERIFIED)
                    if (takeUsedLogGraph && graphEvidence == null) add(IncompleteTake.LOG_EVIDENCE_MISSING)
                }
                val finalizedSuccess = incompleteReasons.isEmpty()
                val geometry = take.geometry
                // PLAN-074 decision 10: a complete take's WAV becomes the MP4's AAC track and leaves the take. Any
                // failure leaves the video as it was and the WAV to be published beside it by the steps below.
                var appendedAudioInput: com.librestatic.opencinecam.camera.PcmInputEvidence? = null
                var audioAppended = false
                var audioWithdrawn = false
                val takeWav = take.audio as? WavAudioSidecarRecorder
                if (appendBitrateBps != null && takeWav != null && output != null && finalizedSuccess) try {
                    val result = takeWav.prepareCompletion()?.result
                    if (result != null) {
                        var placement: com.librestatic.opencinecam.camera.AppendedAudioPlacement? = null
                        val anchors = directTiming
                            ?: com.librestatic.opencinecam.camera.DirectRecorderTiming(result.startedAtElapsedNs, null, null, 0, drained = false)
                        val appended = takeWav.readPrepared { wav ->
                            com.librestatic.opencinecam.storage.appendWavAsAac(output.descriptor.fileDescriptor, wav, appendBitrateBps, { video ->
                                com.librestatic.opencinecam.camera.placeAppendedAudio(anchors, sensorRealtime, video.lastSampleTimeUs,
                                    result.captureTiming?.epoch, result.readEstimatedFrameZeroNs ?: result.startedAtElapsedNs,
                                    audioSyncOffsetNs = appendSyncOffsetNs)
                                    .also { placement = it }.startOffsetUs
                            })
                        }
                        // From here the file has the audio, whatever becomes of the WAV.
                        audioAppended = true
                        appendedAudioInput = result.inputEvidence
                        avTiming = appendedAudioEpochReport(requireNotNull(placement), sensorRealtime, result.captureTiming?.epoch,
                            appended.primingFrames, appended.pcmFrames, appended.accessUnits)
                        android.util.Log.i("CaptureService", "Take audio appended: ${appended.pcmFrames} frames, ${appended.accessUnits} units, " +
                            "offset ${appended.startOffsetUs} us" +
                            (placement?.audioSyncOffsetUs?.takeIf { it != 0L }?.let { " (manual sync offset $it us)" } ?: "") +
                            ", ${avTiming?.policy}, ${appended.encoderName}, first read " +
                            "${result.readEstimatedFrameZeroNs?.let { (it - result.startedAtElapsedNs) / 1_000L }} us after the input started")
                        // An overrun WAV stays beside the video: its record is the only one that says the audio has gaps.
                        // Otherwise false means the WAV could not be removed, so it is published beside the video as well.
                        audioWithdrawn = result.inputOverruns == 0 && takeWav.withdrawPrepared()
                    }
                } catch (failure: Throwable) {
                    android.util.Log.e("CaptureService", "Take audio not appended; the WAV stays beside the video", failure)
                }
                val audioOutput = take.audio.takeUnless { audioWithdrawn }
                var registrationCreationFailed = transferPublication == null || transferPublication.registrationFailed
                val publicationObserver = transferPublication?.observer ?: try { CapturePublicationJournal(this@newPreviewListener) }
                    catch (problem: Throwable) {
                        registrationCreationFailed = true
                        android.util.Log.e("CapturePublication", "Publication journal creation failed; capture remains independent", problem)
                        null
                    }
                var clipSeparateAudio: ClipSeparateAudio? = null
                fun noteAudioPrepared(sidecar: AudioSidecarRecorder, prepared: com.librestatic.opencinecam.storage.PreparedAudioSidecar?):
                    com.librestatic.opencinecam.storage.PreparedAudioSidecar? {
                    val result = prepared?.result
                    if (prepared != null && result != null) clipSeparateAudio = ClipSeparateAudio(
                        result.container, result.sampleRateHz, result.bitDepth.bits, prepared.artifacts.map { it.displayName })
                    if (result != null) avTiming = sidecar.captureClock?.report(avTiming?.videoEncoderFirstPtsUs, null, null)
                        ?.copy(submittedPcmFrames = result.frames, audioStorage = "SEPARATE_${result.container}") ?: avTiming
                    lastSeparateAudioAvDrift = result?.let {
                        separateAudioAvDrift(sidecar.captureClock?.cameraRealtime == true, it.captureTiming, it.displayName)
                    }?.also { android.util.Log.i("AvClockDiagnostics", separateAudioAvDriftLog(it)) }
                    return prepared
                }
                fun buildSidecarJson(): String? {
                    val metadata = logEvidence?.copy(avTiming = avTiming)?.let { logSidecarJson(it, timecodeReport) }
                        ?: hlgEvidence?.let { hlgSidecarJson(it, avTiming) }
                        ?: avTiming?.let { JSONObject().put("schema", "opencinecam.av-timing.v1").put("avTiming", captureEpochJson(it)).toString(2) }
                        ?: timing?.let { timelapseTimingJson(it, requireNotNull(projectRateAtStop)) }
                    val withBaking = if (bakedEvidence != null) {
                        (metadata?.let(::JSONObject) ?: JSONObject().put("schema", "opencinecam.recording-color.v1"))
                            .put("recordingLut", recordingLutJson(bakedEvidence)).toString(2)
                    } else metadata
                    val withGain = if (recordingGain?.enabled == true) {
                        (withBaking?.let(::JSONObject) ?: JSONObject().put("schema", "opencinecam.recording-audio.v1"))
                            .put("recordingGain", recordingGainJson(recordingGain)).toString(2)
                    } else withBaking
                    val withTimecode = if (timecodeReport?.config?.enabled == true) {
                        (withGain?.let(::JSONObject) ?: JSONObject().put("schema", "opencinecam.recording-timing.v1"))
                            .put("timecode", recordingTimecodeJson(timecodeReport)).toString(2)
                    } else withGain
                    val withMarker = subjectSyncMarker?.let { marker ->
                        (withTimecode?.let(::JSONObject) ?: JSONObject().put("schema", "opencinecam.recording-timing.v1"))
                            .put(com.librestatic.opencinecam.SUBJECT_SYNC_MARKER_KEY,
                                JSONObject(com.librestatic.opencinecam.subjectSyncMarkerJson(marker).toString())).toString(2)
                    } ?: withTimecode
                    val withLens = graphEvidence?.lensCorrection?.let { lens ->
                        (withMarker?.let(::JSONObject) ?: JSONObject().put("schema", "opencinecam.recording-color.v1"))
                            .put("lensCorrection", lensCorrectionJson(lens)).toString(2)
                    } ?: withMarker
                    val withExposure = graphEvidence?.exposureCompensation?.let { compensation ->
                        (withLens?.let(::JSONObject) ?: JSONObject().put("schema", "opencinecam.recording-color.v1"))
                            .put("exposureCompensation", exposureCompensationJson(compensation)).toString(2)
                    } ?: withLens
                    return withHardwareTruth(withExposure, hardwareTruth)
                }
                fun timingSidecarFlag() = logEvidence == null && (graphEvidence?.lensCorrection != null || graphEvidence?.exposureCompensation != null || hlgEvidence != null || bakedEvidence != null || recordingGain?.enabled == true || timing != null || avTiming != null || timecodeReport?.config?.enabled == true || subjectSyncMarker != null || hardwareTruth != null)
                // The canonical clip sidecar; evaluated when the video is prepared, after the audio is, so it sees both.
                val clipSidecar: ClipSidecarProvider? = clipStartedAt?.let { started ->
                    clipSidecarProvider {
                        assembleClipFacts(ClipFactsInput(
                            startedAt = started, intent = clipIntent, cameraId = clipState.selectedCameraId,
                            hardware = clipState.hardwareTruth, recordingWidth = clipState.recordingWidth,
                            recordingHeight = clipState.recordingHeight, targetFps = clipState.targetFps,
                            graphEvidence = graphEvidence, hdr = hlgEvidence != null,
                            lutBaked = bakedEvidence != null, timelapse = timing != null,
                            mediaRecorder = clipMediaRecorder, recorderCodecMime = clipCodecMime, softwareEncoderName = clipSoftwareEncoder,
                            sharedPause = avTiming?.sharedPause, separateAudio = clipSeparateAudio,
                            embeddedAudio = audioAppended || clipIntent?.audioEnabled == true && audioOutput == null &&
                                requestedAudioFailure == null && takeAudioLabel != "no audio",
                            deviceProfile = clipDeviceProfile, noticeCodes = clipNoticeCodes,
                            hardwareWarnings = hardwareTruth?.warnings.orEmpty(), stopReason = clipStopReason,
                            durationMs = durationMs, encodedFrames = encodedFrames,
                            success = success, nativeRetired = nativeRetired, camera = clipCamera,
                            embeddedAudioInput = clipAudioInput ?: appendedAudioInput,
                        )).copy(continuation = continuedFrom?.link)
                    }
                }
                fun salvagePlan(reasons: List<String>) = com.librestatic.opencinecam.storage.IncompleteTakeSalvage<
                    com.librestatic.opencinecam.storage.PreparedVideoOutput, com.librestatic.opencinecam.storage.PreparedAudioSidecar>(
                    reasons = reasons,
                    prepareAudio = audioOutput?.let { sidecar -> { r -> noteAudioPrepared(sidecar, sidecar.prepareIncompleteCompletion(r)) } },
                    prepareVideo = { r ->
                        output?.sealMotionLog(keep = false)
                        output?.prepareIncomplete(r, buildSidecarJson(), timingSidecarFlag(), muxerFinalized, encodedFrames, clipSidecar)
                    },
                )
                val finalization = if (!nativeRetired) {
                    // Never publish, and never delete, under a possibly live native writer. Rows stay pending and
                    // journalled; once retirement is proven they are published as incomplete (ADR-0011), and a
                    // process death before that is covered by crash recovery.
                    runCatching { publicationObserver?.onAborted() }
                    val published = incompleteReasons.filter { it != IncompleteTake.NATIVE_RETIREMENT_PENDING }
                    startDeferredSalvage = {
                        val expected = cameraState.value.message
                        salvageAfterRetirement(output, audioOutput, retirement, incompleteReasons, run = {
                            finalizePreparedRecordingTake<
                                com.librestatic.opencinecam.storage.PreparedVideoOutput,
                                com.librestatic.opencinecam.storage.PreparedAudioSidecar,
                                android.net.Uri,
                                com.librestatic.opencinecam.storage.AudioSidecarRecordingResult,
                            >(
                                success = false,
                                prepareAudio = null,
                                prepareVideo = { null },
                                publishAudio = { _ -> audioOutput?.publishPrepared() },
                                publishVideo = { _ -> output?.publishPrepared() },
                                discardAudio = { audioOutput?.finish(false); Unit },
                                discardVideo = { output?.finish(false); Unit },
                                salvage = salvagePlan(published),
                            )
                        }, onSaved = { announceIncompleteSaved(expected, it) })
                    }
                    com.librestatic.opencinecam.storage.PreparedTakeResult<android.net.Uri, com.librestatic.opencinecam.storage.AudioSidecarRecordingResult>(null, emptyList())
                } else finalizePreparedRecordingTake<
                    com.librestatic.opencinecam.storage.PreparedVideoOutput,
                    com.librestatic.opencinecam.storage.PreparedAudioSidecar,
                    android.net.Uri,
                    com.librestatic.opencinecam.storage.AudioSidecarRecordingResult,
                >(
                    success = finalizedSuccess,
                    prepareAudio = audioOutput?.let { sidecar -> { noteAudioPrepared(sidecar, sidecar.prepareCompletion()) } },
                    discardAudio = { audioOutput?.finish(false); Unit },
                    discardVideo = { output?.finish(false); Unit },
                    publishAudio = { _ -> audioOutput?.publishPrepared() },
                    publishVideo = { _ -> output?.publishPrepared() },
                    prepareVideo = {
                        // A pause removes spans from the file timeline, so sensor time can no longer be mapped to it.
                        output?.sealMotionLog(keep = finalizedSuccess && avTiming?.sharedPause?.windows.isNullOrEmpty())
                        output?.prepareCompletion(
                        buildSidecarJson(),
                        expectedGeometry = geometry,
                        timingSidecar = timingSidecarFlag(),
                        clipSidecar = clipSidecar,
                    ) },
                    onPrepared = { video, audio -> publicationObserver?.onPrepared(video.artifacts + audio?.artifacts.orEmpty()); Unit },
                    onPublished = { video, audio -> publicationObserver?.onPublished(video.artifacts + audio?.artifacts.orEmpty()); Unit },
                    onAborted = { publicationObserver?.onAborted(); Unit },
                    salvage = salvagePlan(incompleteReasons),
                )
                val completedTake = finalization.outputs
                finalization.incompleteFailures.forEach { problem ->
                    android.util.Log.e("RecordingRecovery", "Incomplete publication step failed; affected outputs stay pending for recovery", problem)
                }
                val savedIncomplete = finalization.incomplete
                finalization.registrationFailures.forEach { problem ->
                    android.util.Log.e("CapturePublication", "Publication journal update failed; originals retained", problem)
                }
                val registrationFailed = registrationCreationFailed || finalization.registrationFailures.isNotEmpty() || transferPublication?.registrationFailed == true
                timecodeReport?.let { timecodeTracker.onRecordingStopped(completedTake != null, it.lifecycleToken) }
                val uri = completedTake?.first
                val sidecarResult = completedTake?.second
                val registrationNotice = if (completedTake == null || finalization.incomplete) "" else buildString {
                    if (transferPublication?.registered == true) append(" · " + getString(R.string.webdav_queue_registered))
                    if (registrationFailed) append(" · " + getString(if (transferPublication?.registrationFailed == true)
                        R.string.webdav_queue_registration_failed else R.string.capture_publication_registration_failed))
                }
                if (uri != null) output?.productionSlate?.let(::incrementSavedSlate)
                // PLAN-036: judge the published Main10 file's signalling off every service thread; evidence only.
                if (uri != null && !finalization.incomplete) {
                    val checkKind = if (hlgEvidence != null) com.librestatic.opencinecam.camera.Main10TakeKind.HLG
                        else if (logEvidence != null) com.librestatic.opencinecam.camera.Main10TakeKind.LOG else null
                    if (checkKind != null) com.librestatic.opencinecam.Main10FileCheckScheduler.schedule(this@newPreviewListener, uri.toString(), checkKind, output?.displayName)
                }
                // A SAVED observer must see the registration outcome in the same state emission.
                cameraState.update { current -> current.copy(
                    // A drive part about to continue on the phone is not an error to recover from: the preview is back.
                    phase = if (finalizedSuccess) CameraUiPhase.SAVED
                        else if (driveContinuation.isPending) CameraUiPhase.PREVIEWING else CameraUiPhase.ERROR,
                    recordingFinalizing = false,
                    recordingWarning = null,
                    recordingPauseStatus = timing,
                    recordingAvTiming = avTiming,
                    recordingPausePending = false,
                    recordingWhiteBalanceStatus = RecordingWhiteBalanceStatus.IDLE,
                    lastSavedUri = uri?.toString(),
                    message = ((if (finalizedSuccess) {
                        val audioSaved = savedAudioSuffix(
                            sidecarResult?.let { SavedSidecarAudio(it.bitDepth.bits, it.container, it.sampleRateHz) },
                            takeAudioLabel,
                            messageLocale,
                        )
                        val seconds = durationMs / 1000.0
                        if (hlgEvidence != null) {
                            getString(R.string.capture_hdr_video_saved, HdrVideoFormat.fromCurve(hlgEvidence.curve)?.label ?: "HLG", audioSaved, seconds) + captureEpochLabel(avTiming)
                        } else if (logEvidence != null) {
                            val source = getString(if (logEvidence.sourcePath == OpenCineLogSourcePath.SDR_BT709_ISP) {
                                R.string.capture_source_hfr_isp_derived
                            } else R.string.capture_source_hlg_derived)
                            val outputColor = if (bakedEvidence != null) "LUT → SDR BT.709" else "OCLog2"
                            getString(R.string.capture_log_video_saved, outputColor, source, audioSaved, seconds) + captureEpochLabel(avTiming)
                        }
                        else getString(R.string.capture_video_saved, audioSaved, seconds) + captureEpochLabel(avTiming)
                    } else if (savedIncomplete) {
                        getString(R.string.take_saved_incomplete,
                            requestedAudioFailure ?: (finalization.incompleteReasons.ifEmpty { incompleteReasons }).joinToString(", "))
                    } else requestedAudioFailure ?: getString(R.string.capture_video_finalize_failed)) + registrationNotice)
                        .let { outcome -> if (stopReason == null) outcome else getString(R.string.take_stopped_reason, stopReasonText(stopReason), outcome) }
                        .let { message -> continuationNote?.let { "$message · $it" } ?: message },
                    errorCode = if (finalizedSuccess) null else incompleteReasons.first(),
                    recordingElapsedMs = durationMs,
                ) }
                // After the state above, so a publication that completes at once finds the message to replace.
                startDeferredSalvage?.invoke()
            } catch (failure: Throwable) {
                // ADR-0011: nothing that holds bytes is deleted here; it stays pending for crash recovery.
                runCatching { output?.recoveryGroup?.beginSalvage() }
                runCatching { take.audio?.prepareIncompleteCompletion(listOf(com.librestatic.opencinecam.storage.PUBLICATION_FAILED_REASON)) }
                runCatching { output?.finish(false) }
                // PLAN-072: a drive that has gone cannot be published to (`No root for <VOLUME>`). That is the expected
                // end of a drive take, not an error: its folder stays pending for recovery when the drive returns, and
                // a continuation, if armed, must find the preview back rather than an error phase.
                val driveGone = takeDrive != null && mountedRecordingVolumes().none { it.equals(takeDrive.volumeId, ignoreCase = true) }
                if (driveGone) {
                    android.util.Log.w("RecordingDestination", "Drive take left for recovery: ${failure.message}")
                    val outcome = getString(R.string.capture_video_not_finalised_drive)
                    cameraState.update { current -> current.copy(
                        phase = CameraUiPhase.SAVED,
                        recordingFinalizing = false,
                        recordingWarning = null,
                        recordingPausePending = false,
                        recordingWhiteBalanceStatus = RecordingWhiteBalanceStatus.IDLE,
                        message = (if (stopReason == null) outcome else getString(R.string.take_stopped_reason, stopReasonText(stopReason), outcome))
                            .let { message -> continuationNote?.let { "$message · $it" } ?: message },
                        errorCode = stopReason?.correlationId ?: "drive-disconnected",
                    ) }
                } else fail("video-save-failed", failureText(R.string.capture_video_save_failed, failure))
            } finally {
                // A take left pending (a drive pulled mid-take) becomes recoverable now, not only in a new process;
                // a deferred salvage relinquishes it itself once it has run.
                if (startDeferredSalvage == null) relinquishTake(output?.recoveryGroup)
                try { transferPublication?.close() } catch (problem: Exception) {
                    android.util.Log.e("CapturePublication", "Optional queue handle cleanup failed", problem)
                }
                timecodeReport?.let { timecodeTracker.onRecordingStopped(false, it.lifecycleToken) }
                // The preview may already be back; the monitor waited for this reservation. A continuation (PLAN-072
                // Delivery 4) starts instead, once the reservation is released and this runnable has stopped the
                // foreground service, so that stop can never land on the continuation's own.
                if (transferReservation == null) continuationStep()
                releaseTransferCapture(transferReservation) {
                    if (driveContinuation.isPending) { monitorDeferred.set(true); continuationStep() } else startPreviewAudioMonitorIfEligible()
                }
            }
            if (timecodeTracker.continuationStorageFailed) cameraState.value = cameraState.value.copy(
                message = cameraState.value.message.orEmpty() + " · " + getString(R.string.timecode_continuity_failed))
            // A pending continuation keeps the foreground service through the gap: Android may refuse a camera one
            // started again from the background (screen off), and the continuation's start updates this one.
            if (!driveContinuation.isPending) {
                foreground.stop()
                // Drop only the started-service lifetime; a bound operator/exterior keeps the service.
                stopSelf()
            } else mainHandler.postDelayed({
                // A reservation release that never called back leaves the token to expire: drop the service with it.
                if (!driveContinuation.isPending) releaseContinuationForeground()
            }, DriveContinuation.DEFAULT_TTL_MS + 1_000L)
            mainHandler.post { localBinder.applySettings(SettingsRepositories.get(this@newPreviewListener).states.value) }
            continuationStep()
        }
        try {
            storageExecutor.execute(finishTake)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // A full queue or a destroyed service: finish here, as before, rather than lose the take.
            finishTake.run()
        }
    }

    override fun onTapFocusState(state: TapFocusState) {
        cameraState.value = cameraState.value.copy(tapFocusState = state)
    }

    override fun onPhysicalRoute(requestedPhysicalId: String?, reportedPhysicalId: String?) {
        val truth = routeTruthOf(requestedPhysicalId, reportedPhysicalId)
        // Reported once per still, so a mismatch is logged once per still, never per preview frame.
        if (truth == RouteTruth.MISMATCH) {
            android.util.Log.w("PhysicalRoute", "evidence=physical-route-mismatch requested=$requestedPhysicalId reported=$reportedPhysicalId")
        }
        cameraState.update { it.copy(hardwareTruth = it.hardwareTruth.withRoute(requestedPhysicalId, reportedPhysicalId)) }
    }

    override fun onAuditWarning(message: String) {
        cameraState.update { it.copy(hardwareTruth = it.hardwareTruth.withWarning(message)) }
    }

    override fun onActiveLens(physicalId: String?) {
        cameraState.update { it.copy(hardwareTruth = it.hardwareTruth.withActiveLens(physicalId)) }
    }

    override fun onSustainedCadenceLoss(action: com.librestatic.opencinecam.camera.CaptureAuditAction, detail: String) {
        mainHandler.post {
            if (serviceDestroyed || cameraState.value.phase != CameraUiPhase.RECORDING) return@post
            if (action == com.librestatic.opencinecam.camera.CaptureAuditAction.STOP) localBinder.stopRecording(cadenceStopFailure(detail))
            else { cadenceNotice = RuntimeNotice.CADENCE; publishRecordingWarning() }
        }
    }

    override fun onCadenceGuardChanged(engaged: Boolean) {
        if (cameraState.value.fpsLockHolding != engaged) cameraState.value = cameraState.value.copy(fpsLockHolding = engaged)
    }

    override fun onAeLockChanged(active: Boolean) {
        cameraState.value = cameraState.value.copy(aeLockActive = active)
    }

    override fun onAfLockChanged(state: LockState) {
        cameraState.value = cameraState.value.copy(afLockState = state)
    }

    override fun onFocusSelectionChanged(diopters: Float?) {
        cameraState.value = cameraState.value.copy(requestedFocusDiopters = diopters)
    }

    override fun onFocusPullStarted(targetDiopters: Float) {
        cameraState.value = cameraState.value.copy(
            focusPullActive = true,
            focusPullTargetDiopters = targetDiopters,
            requestedFocusDiopters = targetDiopters,
            tapFocusState = TapFocusState.IDLE,
        )
    }

    override fun onFocusPullFinished() {
        cameraState.value = cameraState.value.copy(
            focusPullActive = false,
            focusPullTargetDiopters = null,
            focusMarks = previewEngine.getFocusMarks(),
        )
    }

    override fun onFocusPullCancelled() {
        cameraState.value = cameraState.value.copy(
            focusPullActive = false,
            focusPullTargetDiopters = null,
        )
    }

    override fun onZoomRangeAvailable(min: Float, max: Float, anchors: List<ZoomAnchor>) {
        val current = cameraState.value
        cameraState.value = current.copy(
            zoomMinRatio = min,
            zoomMaxRatio = max,
            opticalAnchors = anchors,
            zoomSupported = min < max,
            zoomHfrSupported = current.descriptor?.supportsHfrZoom == true,
            zoomRatio = (perCameraZoom[current.selectedCameraId] ?: 1f).coerceIn(min, max),
        )
    }

    override fun onLensLock(physicalId: String?, uiZoomRange: ClosedFloatingPointRange<Float>?) {
        if (physicalId == null || uiZoomRange == null) {
            clearLensLockRoute()
            return
        }
        serveLensLock(physicalId, uiZoomRange, com.librestatic.opencinecam.LensLockRoute.DIRECT_PHYSICAL)
    }

    override fun onLensLockCrop(physicalId: String, uiZoomRange: ClosedFloatingPointRange<Float>) {
        android.util.Log.i("CaptureService", "Lens lock on $physicalId served by the GPU crop over $uiZoomRange")
        // The lock is served, so this is not a fallback: the logical calibration stays off.
        lensLockFallback = false
        serveLensLock(physicalId, uiZoomRange, com.librestatic.opencinecam.LensLockRoute.GPU_CROP)
        // Photo previews straight to the window by default; the crop needs the GPU viewfinder.
        mainHandler.post {
            if (cameraState.value.lensLock.route != com.librestatic.opencinecam.LensLockRoute.GPU_CROP ||
                cameraState.value.structuralSettingsFrozen || previewEngine.usesGpuViewfinder() || !desiredGpuViewfinder()) return@post
            attachedPreviewSurface?.takeIf { it.isValid }?.let { localBinder.attachPreview(it, attachedPreviewRotationDegrees) }
        }
    }

    override fun onPreviewGraphLoss(loss: com.librestatic.opencinecam.camera.PreviewGraphLoss) {
        cameraState.update { if (it.previewGraphLoss == loss) it else it.copy(previewGraphLoss = loss) }
    }

    override fun onVideoCodecFallback(reason: String) {
        android.util.Log.w("CaptureService", "Take records H.264: $reason")
        takeCodecFallback = true
        publishRecordingWarning()
    }

    override fun onLensLockSwitched(switched: Boolean) {
        cameraState.update { state ->
            val warning = lensLockSwitchedWarning(switched, state.lensLock.route)
            if (state.lensLock.lensSwitchedWarning == warning) state
            else state.copy(lensLock = state.lensLock.copy(lensSwitchedWarning = warning))
        }
    }

    /** Publishes a served lock: the locked lens' own range on the logical scale; the anchors are the lock options. */
    private fun serveLensLock(physicalId: String, uiZoomRange: ClosedFloatingPointRange<Float>, route: com.librestatic.opencinecam.LensLockRoute) {
        refreshLensLockState()
        cameraState.update { state ->
            val anchors = state.lensLock.options.map { ZoomAnchor(it.anchorRatio, it.focalLengthMm, it.physicalId) }
            state.copy(
                lensLock = state.lensLock.copy(lockedPhysicalId = physicalId, route = route, lensSwitchedWarning = false),
                zoomMinRatio = uiZoomRange.start,
                zoomMaxRatio = uiZoomRange.endInclusive,
                opticalAnchors = anchors,
                zoomSupported = uiZoomRange.start < uiZoomRange.endInclusive,
                zoomHfrSupported = false,
                zoomRatio = (perCameraZoom[state.selectedCameraId] ?: 1f).coerceIn(uiZoomRange.start, uiZoomRange.endInclusive),
            )
        }
    }

    override fun onLensLockUnavailable(physicalId: String, reason: String) {
        android.util.Log.i("CaptureService", "Lens lock on $physicalId unavailable ($reason); the logical camera is open")
        // The session now runs on the logical camera, so its calibration applies again. The
        // saved lock is kept and tried again by the next start.
        lensLockFallback = true
        refreshLensCorrection()
        pendingStatusMessage = getString(R.string.lens_lock_unavailable)
        clearLensLockRoute()
        cameraState.update { it.copy(messageTransient = true) }
    }

    override fun onZoomEffective(ratio: Float) {
        cameraState.update { it.copy(zoomEffectiveRatio = ratio) }
    }

    override fun onSubjectFramingChanged(status: com.librestatic.opencinecam.camera.SubjectFramingStatus) {
        cameraState.update { it.copy(subjectFraming = status) }
    }

    override fun onZoomRejected(requested: Float, accepted: Float) {
        cameraState.value = cameraState.value.copy(
            zoomRatio = accepted,
            zoomEffectiveRatio = accepted,
            message = getString(R.string.capture_zoom_rejected, ratioText(requested, messageLocale), ratioText(accepted, messageLocale)),
            messageTransient = true,
        )
    }

    override fun onControlRejected(code: String, message: String) {
        // The session kept its previous request: a refused control must never end or discard a take.
        android.util.Log.w("CaptureService", "Control rejected ($code): $message")
        // A Strict exposure gate falls back to auto exposure instead of keeping the previous request.
        val text = if (com.librestatic.opencinecam.camera.ExposureGateCode.entries.any { it.code == code }) R.string.control_rejected_auto else R.string.control_rejected
        cameraState.update { it.copy(message = getString(text, message), messageTransient = true) }
    }

    override fun onControlAdapted(code: String, message: String) {
        android.util.Log.i("CaptureService", "Control adapted ($code): $message")
        // Recorded as a hardware-truth warning too, so the take sidecar keeps the disclosure.
        cameraState.update { it.copy(message = getString(R.string.control_adapted, message), messageTransient = true, hardwareTruth = it.hardwareTruth.withWarning(message)) }
    }

    override fun onSoftwareVideoEncoder(codecName: String) {
        android.util.Log.w("CaptureService", "Software video encoder in use: $codecName")
        takeSoftwareEncoder = codecName
        // Recorded as a hardware-truth warning, so the take sidecar keeps the disclosure; never hardware evidence.
        cameraState.update { it.copy(hardwareTruth = it.hardwareTruth.withWarning(softwareVideoEncoderDisclosure(codecName))) }
    }

    override fun onRecordingStartRefused(code: String, message: String) {
        android.util.Log.w("CaptureService", "Take start refused ($code): $message")
        pendingStartRefusal = code
    }

    override fun onTorchRejected(message: String) {
        // A rejected optional light request must never finalize or discard a running clip.
        cameraState.value = cameraState.value.copy(
            message = getString(R.string.settings_torch_rejected, message),
            messageTransient = true,
            torchReported = null,
            photoFlashReport = null,
            torchStrengthReported = null,
        )
    }

    // OCC-PLAN-071: a refused RAW graph is not a camera failure. RAW is never dropped from the
    // session, so the mode turns FAILED for this camera and the operator goes back to the previous one.
    override fun onRawVideoUnavailable(reason: String, message: String) {
        mainHandler.post { if (!serviceDestroyed) rawVideoGraphRejected(reason, message) }
    }

    override fun onRawVideoStopped(reason: String?) = rawVideoTakeStopped(reason)

    override fun onFailure(code: String, message: String, recoverable: Boolean) {
        calibrationCameraLost()
        retireAudioLevelConsumer()
        submitCaptureState(stateCommands.engineFailed(code, message, recoverable))
        stillOwner.get()?.takeUnless { it.publishing.get() }?.let { owner ->
            if (stillOwner.compareAndSet(owner, null)) cameraState.update { it.copy(stillCapturePending = false, burstSaving = false, bracketSaving = false, accumulationSaving = false) }
        }
        // Observed on the failing engine thread so the receipt queues behind its cleanup.
        val retirement = previewEngine.recordingOutputRetirement()
        releaseFailedTakeStart(retirement)
        val cleanup = Runnable {
            // Take-owned and main-only state (transferWaiting, WB, timers) is mutated on main.
            stopPreviewAudioMonitor(clearLevels = false)
            mainHandler.removeCallbacks(recordingTicker)
            cancelTimelapseAutoStop()
            cancelTakeLengthAutoStop()
            takeLengthLimitMs = null
            cameraState.update { it.copy(takeLengthLimitMs = null) }
            val take = detachTake()
            val encodedFrames = previewEngine.encodedRecordingProgress()?.frameCount
            abortTransferPublication(take.publication)
            foreground.stop()
            fail(code, engineFailureText(code, message) { getString(it) })
            // ADR-0011: what the take recorded is published as incomplete once native retirement is proven.
            salvageInterruptedTake(take, retirement, listOf(failureReason(code)), encodedFrames, cameraState.value.message)
            retireRecordingRecoveryGuards(retirement)
            stopSelf()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) cleanup.run()
        else mainHandler.post { if (!serviceDestroyed) cleanup.run() }
    }
}
