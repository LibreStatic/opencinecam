/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

/** Photographic flash selection; independent from a continuous preview/recording torch. */
enum class PhotoFlashMode { OFF, AUTO, ON }

/** Single-flash strength characteristics, not torch-strength or CameraManager flashlight limits. */
data class PhotoFlashCapabilities(
    val available: Boolean = false,
    val aeModes: Set<Int> = emptySet(),
    val singleMax: Int = 1,
    val singleDefault: Int = 1,
) {
    init {
        require(singleMax >= 1)
        require(singleDefault in 1..singleMax)
    }
    val adjustable: Boolean get() = available && singleMax > 1
}

enum class PhotoFlashRejection {
    FLASH_UNAVAILABLE,
    EXPOSURE_UNSUPPORTED,
    AE_MODE_UNSUPPORTED,
    STRENGTH_UNSUPPORTED,
    STRENGTH_OUT_OF_RANGE,
    STRENGTH_WITH_AUTO,
}

sealed interface PhotoFlashResolution {
    /** Null AE leaves the existing exposure request untouched; null strength writes no level. */
    data class Plan(
        val aeMode: Int?,
        val flashMode: Int,
        val strength: Int?,
        val needsPrecapture: Boolean,
    ) : PhotoFlashResolution
    data class Rejected(val reason: PhotoFlashRejection) : PhotoFlashResolution
}

data class PhotoFlashSelection(val mode: PhotoFlashMode = PhotoFlashMode.OFF, val strength: Int? = null) {
    fun resolve(capabilities: PhotoFlashCapabilities, exposureMode: ExposureMode): PhotoFlashResolution {
        // An inactive persisted level is not applied, and OFF never changes AE/ISO/shutter.
        if (mode == PhotoFlashMode.OFF) return PhotoFlashResolution.Plan(null, FLASH_OFF, null, false)
        if (!capabilities.available) return rejected(PhotoFlashRejection.FLASH_UNAVAILABLE)
        if (exposureMode == ExposureMode.ISO_PRIORITY || exposureMode == ExposureMode.SHUTTER_PRIORITY ||
            mode == PhotoFlashMode.AUTO && exposureMode != ExposureMode.AUTO) {
            return rejected(PhotoFlashRejection.EXPOSURE_UNSUPPORTED)
        }
        if (mode == PhotoFlashMode.AUTO) {
            if (strength != null) return rejected(PhotoFlashRejection.STRENGTH_WITH_AUTO)
            if (AE_AUTO_FLASH !in capabilities.aeModes) return rejected(PhotoFlashRejection.AE_MODE_UNSUPPORTED)
            return PhotoFlashResolution.Plan(AE_AUTO_FLASH, FLASH_OFF, null, true)
        }
        if (strength != null) {
            if (!capabilities.adjustable) return rejected(PhotoFlashRejection.STRENGTH_UNSUPPORTED)
            if (strength !in 1..capabilities.singleMax) return rejected(PhotoFlashRejection.STRENGTH_OUT_OF_RANGE)
        }
        val ae = when {
            exposureMode == ExposureMode.MANUAL -> AE_OFF
            strength != null -> AE_ON
            else -> AE_ALWAYS_FLASH
        }
        if (ae !in capabilities.aeModes) return rejected(PhotoFlashRejection.AE_MODE_UNSUPPORTED)
        return PhotoFlashResolution.Plan(
            aeMode = ae,
            flashMode = if (ae == AE_ALWAYS_FLASH) FLASH_OFF else FLASH_SINGLE,
            strength = strength,
            needsPrecapture = exposureMode == ExposureMode.AUTO,
        )
    }

    private fun rejected(reason: PhotoFlashRejection) = PhotoFlashResolution.Rejected(reason)

    private companion object {
        // Stable Camera2 CONTROL_AE_MODE and FLASH_MODE values, without Android dependencies.
        const val AE_OFF = 0
        const val AE_ON = 1
        const val AE_AUTO_FLASH = 2
        const val AE_ALWAYS_FLASH = 3
        const val FLASH_OFF = 0
        const val FLASH_SINGLE = 1
    }
}
