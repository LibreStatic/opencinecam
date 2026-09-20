/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

/** Null stabilization and DEFAULT ISP mean the exact capture-request template, not a guessed OFF. */
data class ImageProcessingSelection(
    val stabilization: StabilizationMode? = null,
    val noiseReduction: IspMode = IspMode.DEFAULT,
    val edge: IspMode = IspMode.DEFAULT,
)
enum class ImageProcessingControl { STABILIZATION, NOISE_REDUCTION, EDGE }

/** Raw Camera2 values are retained: 0=OFF, 1=ON for OIS/EIS; 0/1/2=OFF/FAST/HQ for ISP. */
data class ImageProcessingCapabilities(
    val opticalModes: Set<Int> = emptySet(),
    val videoModes: Set<Int> = emptySet(),
    val noiseModes: Set<Int> = emptySet(),
    val edgeModes: Set<Int> = emptySet(),
    val sessionControls: Set<ImageProcessingControl> = emptySet(),
) {
    fun supports(mode: StabilizationMode?): Boolean {
        if (mode == null) return true
        val canDisableOptical = opticalModes.isEmpty() || 0 in opticalModes
        val canDisableVideo = videoModes.isEmpty() || 0 in videoModes
        return when (mode) {
            StabilizationMode.OFF -> (opticalModes.isNotEmpty() || videoModes.isNotEmpty()) && canDisableOptical && canDisableVideo
            StabilizationMode.OPTICAL -> 1 in opticalModes && canDisableVideo
            StabilizationMode.VIDEO -> 1 in videoModes && canDisableOptical
        }
    }
    fun supports(mode: IspMode, control: ImageProcessingControl): Boolean = mode == IspMode.DEFAULT || mode.camera2Value() in when (control) {
        ImageProcessingControl.NOISE_REDUCTION -> noiseModes
        ImageProcessingControl.EDGE -> edgeModes
        ImageProcessingControl.STABILIZATION -> emptySet()
    }
}
fun IspMode.camera2Value(): Int? = when (this) { IspMode.DEFAULT -> null; IspMode.OFF -> 0; IspMode.FAST -> 1; IspMode.HIGH_QUALITY -> 2 }

data class ImageProcessingDefaults(val optical: Int? = null, val video: Int? = null, val noise: Int? = null, val edge: Int? = null)
data class ResolvedImageProcessing(val values: ImageProcessingDefaults, val unavailable: Set<ImageProcessingControl> = emptySet())

fun ImageProcessingSelection.resolve(caps: ImageProcessingCapabilities, defaults: ImageProcessingDefaults, constrainedHfr: Boolean = false): ResolvedImageProcessing {
    val unavailable = mutableSetOf<ImageProcessingControl>()
    var optical = defaults.optical
    var video = defaults.video
    if (stabilization != null) {
        val unknownCounterpartOn = (stabilization == StabilizationMode.OPTICAL && caps.videoModes.isEmpty() && defaults.video != null && defaults.video != 0) ||
            (stabilization == StabilizationMode.VIDEO && caps.opticalModes.isEmpty() && defaults.optical != null && defaults.optical != 0)
        if (constrainedHfr || unknownCounterpartOn || !caps.supports(stabilization)) unavailable += ImageProcessingControl.STABILIZATION
        else {
            if (caps.opticalModes.isNotEmpty()) optical = if (stabilization == StabilizationMode.OPTICAL) 1 else 0
            if (caps.videoModes.isNotEmpty()) video = if (stabilization == StabilizationMode.VIDEO) 1 else 0
        }
    }
    fun resolveIsp(mode: IspMode, control: ImageProcessingControl, default: Int?): Int? {
        if (mode == IspMode.DEFAULT) return default
        if (constrainedHfr || !caps.supports(mode, control)) { unavailable += control; return default }
        return mode.camera2Value()
    }
    return ResolvedImageProcessing(ImageProcessingDefaults(optical, video,
        resolveIsp(noiseReduction, ImageProcessingControl.NOISE_REDUCTION, defaults.noise),
        resolveIsp(edge, ImageProcessingControl.EDGE, defaults.edge)), unavailable)
}
