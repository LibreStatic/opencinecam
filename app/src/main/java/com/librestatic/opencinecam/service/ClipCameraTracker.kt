/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.service

import com.librestatic.opencinecam.AF_MODES
import com.librestatic.opencinecam.AWB_MODES
import com.librestatic.opencinecam.CameraUiState
import com.librestatic.opencinecam.EDGE_MODES
import com.librestatic.opencinecam.OPTICAL_MODES
import com.librestatic.opencinecam.PROCESSING_MODES
import com.librestatic.opencinecam.TONEMAP_MODES
import com.librestatic.opencinecam.VIDEO_MODES
import com.librestatic.opencinecam.camera.WhiteBalanceSelection
import com.librestatic.opencinecam.storage.ClipCameraSettings
import com.librestatic.opencinecam.storage.ClipSampledRange

/**
 * Folds the capture state sampled while a take records into the clip sidecar's camera block. What the
 * operator asked for is read from the first sample; what the camera reported, from the last one that
 * carried it, since the first can still hold the preview's values. A null reading is skipped, so it
 * never becomes 0 nor widens a range. [sample] and [result] may run on different threads.
 */
internal class ClipCameraTracker {
    private class Range<T : Comparable<T>> {
        var value: ClipSampledRange<T>? = null

        fun add(reading: T?) {
            reading ?: return
            val current = value
            value = if (current == null) ClipSampledRange(reading, reading, reading, reading) else current.copy(
                end = reading, min = minOf(current.min, reading), max = maxOf(current.max, reading),
            )
        }
    }

    private var first: CameraUiState? = null
    private val iso = Range<Int>()
    private val exposure = Range<Long>()
    private val zoom = Range<Float>()
    private var cctStart: Int? = null
    private var cctEnd: Int? = null
    private var reportedExposureMode: String? = null
    private var reportedTint: Int? = null
    private var afMode: Int? = null
    private var focusDiopters: Float? = null
    private var ois: Int? = null
    private var eis: Int? = null
    private var noiseReduction: Int? = null
    private var edge: Int? = null
    private var toneMap: Int? = null

    @Synchronized
    fun sample(state: CameraUiState) {
        if (first == null) first = state
        iso.add(state.sensitivityIso)
        exposure.add(state.exposureTimeNs)
        zoom.add(state.zoomEffectiveRatio ?: state.zoomRatio)
        state.reportedColorTemperatureK?.let { if (cctStart == null) cctStart = it; cctEnd = it }
        state.reportedExposureMode?.let { reportedExposureMode = it.name }
        state.reportedColorTint?.let { reportedTint = it }
        state.reportedAfMode?.let { afMode = it }
        state.focusDistanceDiopters?.let { focusDiopters = it }
        state.reportedOpticalStabilization?.let { ois = it }
        state.reportedVideoStabilization?.let { eis = it }
        state.reportedNoiseReduction?.let { noiseReduction = it }
        state.reportedEdgeEnhancement?.let { edge = it }
        state.reportedToneMapMode?.let { toneMap = it }
    }

    /** The settings sampled so far; every field is null when nothing was sampled. */
    @Synchronized
    fun result(): ClipCameraSettings {
        val start = first ?: return ClipCameraSettings()
        val descriptor = start.descriptor
        val wb = start.requestedWhiteBalance
        return ClipCameraSettings(
            cameraId = start.selectedCameraId,
            focalLengthMm = descriptor?.focalLengthsMm?.firstOrNull(),
            focalLength35mm = descriptor?.equivalentFocalLengthMm,
            aperture = descriptor?.aperturesFNumber?.firstOrNull(),
            requestedExposureMode = start.requestedExposureMode.name,
            reportedExposureMode = reportedExposureMode,
            iso = iso.value,
            exposureTimeNs = exposure.value,
            whiteBalanceMode = when (wb) {
                WhiteBalanceSelection.Auto -> "AUTO"
                is WhiteBalanceSelection.Kelvin -> "KELVIN"
                is WhiteBalanceSelection.Preset -> name(wb.awbMode, AWB_MODES)
            },
            whiteBalanceKelvin = (wb as? WhiteBalanceSelection.Kelvin)?.kelvin,
            reportedCctStartK = cctStart,
            reportedCctEndK = cctEnd,
            // A Kelvin request carries its own tint; otherwise the tint is what the camera reported.
            whiteBalanceTint = (wb as? WhiteBalanceSelection.Kelvin)?.tint ?: reportedTint,
            afMode = afMode?.let { name(it, AF_MODES) },
            focusDistanceDiopters = focusDiopters,
            zoom = zoom.value,
            opticalStabilization = ois?.let { name(it, OPTICAL_MODES) },
            videoStabilization = eis?.let { name(it, VIDEO_MODES) },
            noiseReduction = noiseReduction?.let { name(it, PROCESSING_MODES) },
            edge = edge?.let { name(it, EDGE_MODES) },
            toneMap = toneMap?.let { name(it, TONEMAP_MODES) },
        )
    }

    private fun name(value: Int, names: Map<Int, String>): String = names[value] ?: value.toString()
}
