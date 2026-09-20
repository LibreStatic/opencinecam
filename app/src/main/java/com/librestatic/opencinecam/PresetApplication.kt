/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import com.librestatic.opencinecam.camera.*

/** Review is recomputed against the currently selected lens; no imported hardware identifier is used. */
fun CameraPreset.compatibilityIssues(state: CameraUiState): List<String> = buildList {
    val d = state.descriptor
    if (d == null) { add("camera"); return@buildList }
    if (state.modeGates[mode] != ModeGateState.AVAILABLE) add("mode")
    val hfr = when (mode) {
        CaptureMode.LOG -> d.logProfiles.firstOrNull { it.size.width == settings.logWidth && it.size.height == settings.logHeight && it.fps == settings.logFps }?.constrainedHighSpeed
        CaptureMode.VIDEO -> d.videoProfiles.firstOrNull { it.size.width == settings.videoWidth && it.size.height == settings.videoHeight && it.fps == settings.videoFps }?.constrainedHighSpeed
        CaptureMode.TIME_LAPSE -> d.videoProfiles.firstOrNull { it.size.width == settings.timelapseWidth && it.size.height == settings.timelapseHeight && it.fps == settings.timelapseFps }?.constrainedHighSpeed
        else -> false
    }
    if (hfr == null) add("format")
    val caps = if (hfr == true) ExposureCapabilities() else d.exposureCapabilities
    val fps = when (mode) { CaptureMode.LOG -> settings.logFps; CaptureMode.TIME_LAPSE -> settings.timelapseFps; else -> settings.videoFps }
    val exposure = settings.exposure.resolve(caps, CaptureFrameRate(fps))
    if (!caps.supports(settings.exposure.mode) || exposure.clamped) add("exposure")
    val wb = settings.whiteBalance.adaptTo(d.kelvinRange.takeUnless { hfr == true }, d.tintSupported, if (hfr == true) emptySet() else d.availableAwbModes)
    if (wb != settings.whiteBalance) add("white-balance")
    if (settings.recordingWhiteBalance == RecordingWhiteBalancePolicy.LOCK_ON_RECORD && (hfr == true || ((wb == WhiteBalanceSelection.Auto || wb == WhiteBalanceSelection.Preset(1)) && !d.awbLockSupported))) add("wb-lock")
    val isp = settings.imageProcessing.resolve(d.imageProcessingCapabilities, ImageProcessingDefaults(null, null, null, null), hfr == true)
    if (isp.unavailable.isNotEmpty()) add("image-processing")
    if (settings.flashEnabled && (!d.flashAvailable || hfr == true || (settings.torchStrengthLevel?.let { it > d.torchCapabilities.maxLevel } == true))) add("torch")
    if (mode == CaptureMode.PHOTO && settings.photoFlash.resolve(d.photoFlashCapabilities, settings.exposure.mode) is PhotoFlashResolution.Rejected) add("photo-flash")
    val focusLimit = d.minimumFocusDistance
    if (focusDiopters != null && (hfr == true || focusLimit == null || focusDiopters > focusLimit)) add("focus")
    val zoom = d.effectiveZoomRange
    if (zoomRatio != 1f && (zoom == null || zoomRatio !in zoom || (hfr == true && !d.supportsHfrZoom))) add("zoom")
    state.audioCapabilities?.let { if (settings.normalizedFor(it) != settings) add("audio") }
}
