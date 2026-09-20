/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import com.librestatic.opencinecam.camera.CaptureFrameRate

val fractionalProjectRates = listOf(CaptureFrameRate(24000, 1001), CaptureFrameRate(30000, 1001), CaptureFrameRate(60000, 1001))
fun validProjectRate(numerator: Int, denominator: Int): Boolean =
    (denominator == 1 && numerator in 1..120) || (denominator == 1001 && numerator in setOf(24000, 30000, 60000))
fun CaptureFrameRate.projectLabel(): String = if (denominator == 1) "$numerator" else "$numerator/$denominator"
val CameraSettings.timelapseProjectRate: CaptureFrameRate get() = CaptureFrameRate(timelapseFps, timelapseFpsDenominator)
val CameraSettings.videoProjectRate: CaptureFrameRate get() = CaptureFrameRate(videoProjectNumerator, videoProjectDenominator)
/** Off-speed explicitly selects silent SDR VIDEO, without changing the saved audio preference. */
fun CameraSettings.captureWantsAudio(mode: CaptureMode): Boolean = audioEnabled &&
    (mode == CaptureMode.LOG || (mode == CaptureMode.VIDEO && !videoOffSpeed))
