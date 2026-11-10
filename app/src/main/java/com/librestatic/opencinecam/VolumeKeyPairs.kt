/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import com.librestatic.opencinecam.camera.ExposureMode
import com.librestatic.opencinecam.camera.ExposureSelection
import com.librestatic.opencinecam.camera.ZoomMath
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * One step of a volume-key pair. [limit] is true when the value stands at the end it moved toward;
 * [anchor] when a zoom step reached or crossed an optical anchor.
 */
internal data class PairStep<T>(val value: T, val limit: Boolean, val anchor: Boolean = false)

/** A press moves a third of a stop, the finest step the camera dials share. */
private val ThirdStop = 2.0.pow(1.0 / 3.0)
/** Rocker deflection a held key stands for: about one octave per second, gentler than the rocker's extreme. */
internal const val VolumeZoomHoldOffset = 0.75f
/** A focus press is this fraction of the lens' diopter range. */
internal const val VolumeFocusSteps = 40
private const val AnchorEpsilon = 1e-3f

/** Why a pair cannot step in [state], or null when it can. */
fun volumePairUnavailableReason(pair: VolumeKeyPair, state: CameraUiState): OperatorUnavailableReason? {
    if (pair == VolumeKeyPair.NONE) return OperatorUnavailableReason.NOT_ASSIGNABLE
    if (state.captureControlsLocked) return OperatorUnavailableReason.LOCKED
    if (state.phase !in setOf(CameraUiPhase.PREVIEWING, CameraUiPhase.SAVED, CameraUiPhase.RECORDING) ||
        state.whiteBalancePreparing || state.recordingFinalizing) return OperatorUnavailableReason.NOT_READY
    val descriptor = state.descriptor ?: return OperatorUnavailableReason.NOT_READY
    return when (pair) {
        VolumeKeyPair.ZOOM -> if (state.zoomSupported && state.zoomMaxRatio > state.zoomMinRatio) null else OperatorUnavailableReason.NO_ZOOM
        VolumeKeyPair.EXPOSURE_COMPENSATION -> if (state.aeCompensationIndexRange?.let { it.last > it.first } == true) null else OperatorUnavailableReason.NO_EV
        VolumeKeyPair.ISO, VolumeKeyPair.SHUTTER -> {
            val caps = descriptor.exposureCapabilities
            val priority = if (pair == VolumeKeyPair.ISO) ExposureMode.ISO_PRIORITY else ExposureMode.SHUTTER_PRIORITY
            when {
                state.highSpeedSession() -> OperatorUnavailableReason.HIGH_SPEED
                caps.supports(ExposureMode.MANUAL) || caps.supports(priority) -> null
                else -> OperatorUnavailableReason.NO_MANUAL_EXPOSURE
            }
        }
        VolumeKeyPair.FOCUS -> if ((descriptor.minimumFocusDistance ?: 0f) > 0f) null else OperatorUnavailableReason.NO_MANUAL_FOCUS
        VolumeKeyPair.NONE -> OperatorUnavailableReason.NOT_ASSIGNABLE
    }
}

/**
 * A zoom press: a third of a stop, stopping on the first optical anchor it crosses so a press
 * never skips a lens.
 */
internal fun volumeZoomTap(ratio: Float, direction: Int, min: Float, max: Float, anchors: List<Float>): PairStep<Float> {
    val target = ZoomMath.multiply(ratio, ThirdStop.pow(direction.toDouble()).toFloat(), min..max)
    val crossed = if (direction > 0) anchors.filter { it > ratio * (1 + AnchorEpsilon) && it <= target }.minOrNull()
        else anchors.filter { it < ratio * (1 - AnchorEpsilon) && it >= target }.maxOrNull()
    val next = crossed ?: target
    return PairStep(next, atEnd(next, direction, min, max), anchor = crossed != null)
}

/** A held zoom key, like the rocker held at [VolumeZoomHoldOffset]; [PairStep.limit] only when it just reached the end. */
internal fun volumeZoomHold(ratio: Float, direction: Int, deltaSeconds: Float, min: Float, max: Float, anchors: List<Float>): PairStep<Float> {
    val speed = ZoomMath.rockerSpeedOctavesPerSecond(direction * VolumeZoomHoldOffset)
    val next = ZoomMath.multiply(ratio, ZoomMath.rockerFactor(speed, deltaSeconds), min..max)
    val low = minOf(ratio, next); val high = maxOf(ratio, next)
    val crossed = next != ratio && anchors.any { it in low..high && abs(it - ratio) > ratio * AnchorEpsilon }
    return PairStep(next, atEnd(next, direction, min, max) && !atEnd(ratio, direction, min, max), anchor = crossed)
}

/** One AE compensation index, within the camera's range. */
internal fun volumeEvStep(state: CameraUiState, direction: Int): PairStep<Int>? {
    val range = state.aeCompensationIndexRange?.takeIf { it.last > it.first } ?: return null
    val next = (state.requestedAeCompensationIndex.coerceIn(range.first, range.last) + direction).coerceIn(range.first, range.last)
    return PairStep(next, if (direction > 0) next == range.last else next == range.first)
}

/** A third of a stop of ISO, through the same intent the ISO slider sets; from auto it starts at the ISO the camera reports. */
internal fun volumeIsoStep(state: CameraUiState, settings: CameraSettings, direction: Int): PairStep<ExposureSelection>? {
    val descriptor = state.descriptor ?: return null
    val ranges = exposureControlRanges(descriptor)
    val current = exposureControlValue(true, state, settings).iso ?: ranges.isoMin.toInt()
    val min = ranges.isoMin.toInt(); val max = ranges.isoMax.toInt()
    val next = thirdStop(current, direction, min, max)
    val manual = descriptor.exposureCapabilities.supports(ExposureMode.MANUAL)
    return PairStep(exposureWithIso(settings, state, manual, next), if (direction > 0) next >= max else next <= min)
}

/**
 * A third of a stop of shutter: up is a longer time or a wider angle, so a brighter picture. Time
 * steps from what the camera reports in auto; an angle steps from the angle set, as the slider does.
 */
internal fun volumeShutterStep(state: CameraUiState, settings: CameraSettings, direction: Int): PairStep<ExposureSelection>? {
    val descriptor = state.descriptor ?: return null
    val ranges = exposureControlRanges(descriptor)
    val control = exposureControlValue(false, state, settings)
    val manual = descriptor.exposureCapabilities.supports(ExposureMode.MANUAL)
    if (control.angle) {
        val next = thirdStop(settings.exposure.angleTenths, direction, 1, 3600)
        return PairStep(exposureWithShutter(settings, state, manual, angleTenths = next), if (direction > 0) next >= 3600 else next <= 1)
    }
    val current = control.timeNs ?: 16_666_667L
    val min = ranges.timeMin.toLong(); val max = ranges.timeMax.toLong()
    var next = (current * ThirdStop.pow(direction.toDouble())).toLong().coerceIn(min, max)
    if (next == current && current != (if (direction > 0) max else min)) next = (current + direction).coerceIn(min, max)
    return PairStep(exposureWithShutter(settings, state, manual, timeNs = next), if (direction > 0) next >= max else next <= min)
}

/**
 * A fortieth of the focus range: up focuses farther (fewer diopters). With autofocus running the
 * first press starts from the distance the lens reports.
 */
internal fun volumeFocusStep(state: CameraUiState, direction: Int): PairStep<Float>? {
    val near = state.descriptor?.minimumFocusDistance?.takeIf { it > 0f } ?: return null
    val current = (state.requestedFocusDiopters ?: state.focusDistanceDiopters ?: 0f).coerceIn(0f, near)
    val next = (current - direction * near / VolumeFocusSteps).coerceIn(0f, near)
    return PairStep(next, if (direction > 0) next <= 0f else next >= near)
}

private fun thirdStop(current: Int, direction: Int, min: Int, max: Int): Int {
    val scaled = (current * ThirdStop.pow(direction.toDouble())).roundToInt().coerceIn(min, max)
    // Small values round back to themselves; a press always moves.
    return if (scaled == current) (current + direction).coerceIn(min, max) else scaled
}

private fun atEnd(value: Float, direction: Int, min: Float, max: Float): Boolean =
    if (direction > 0) value >= max * (1 - 1e-6f) else value <= min * (1 + 1e-6f)
