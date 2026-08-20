/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import com.librestatic.opencinecam.core.model.FailureCode
import com.librestatic.opencinecam.core.model.FailureSeverity
import com.librestatic.opencinecam.core.model.Knowledge
import com.librestatic.opencinecam.core.model.Recoverability
import com.librestatic.opencinecam.core.model.StableFailure

data class CameraMetadata(
    val cameraId: String,
    val lensFacing: Int?,
    val hardwareLevel: Int?,
    val activeArray: Rect?,
    val focalLengths: List<Float>?,
    val physicalIds: Set<String>?,
    val capabilities: Set<Int>?,
)

interface CameraMetadataSource {
    fun cameraIds(): List<String>
    fun metadata(cameraId: String): CameraMetadata
}

class Camera2MetadataSource(private val manager: CameraManager) : CameraMetadataSource {
    override fun cameraIds(): List<String> = manager.cameraIdList.sorted()

    override fun metadata(cameraId: String): CameraMetadata {
        val characteristics = manager.getCameraCharacteristics(cameraId)
        return CameraMetadata(
            cameraId = cameraId,
            lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING),
            hardwareLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL),
            activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE),
            focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList(),
            physicalIds = characteristics.physicalCameraIds,
            capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet(),
        )
    }
}

data class CameraIdentity(
    val cameraId: String,
    val lensFacing: Knowledge<String>,
    val hardwareLevel: Knowledge<String>,
    val activeArray: Knowledge<String>,
    val focalLengthsMillimeters: Knowledge<List<Float>>,
    val publicPhysicalIds: Set<String>,
    val capabilities: Set<String>,
)

data class CameraProbeReport(
    val cameras: List<CameraIdentity>,
    val failures: List<StableFailure>,
)

class CameraCharacteristicsProbe(
    private val source: CameraMetadataSource,
    private val correlationId: String = "camera-probe",
) {
    fun probe(): CameraProbeReport {
        val ids = try {
            source.cameraIds()
        } catch (error: SecurityException) {
            return CameraProbeReport(emptyList(), listOf(failure(FailureCode.CAPTURE_OPEN_FAILED, "Camera permission is unavailable.")))
        } catch (error: RuntimeException) {
            return CameraProbeReport(emptyList(), listOf(failure(FailureCode.CAPTURE_OPEN_FAILED, "Camera enumeration failed.")))
        }
        val cameras = mutableListOf<CameraIdentity>()
        val failures = mutableListOf<StableFailure>()
        ids.distinct().sorted().forEach { id ->
            try {
                cameras += source.metadata(id).toIdentity()
            } catch (error: RuntimeException) {
                failures += failure(FailureCode.CAPTURE_OPEN_FAILED, "Camera $id could not be inspected.", id)
            }
        }
        return CameraProbeReport(cameras, failures)
    }

    private fun failure(code: FailureCode, message: String, cameraId: String? = null) = StableFailure(
        component = "camera-probe",
        code = code,
        severity = FailureSeverity.ERROR,
        recoverability = Recoverability.RETRYABLE,
        correlationId = correlationId,
        userMessage = message,
        details = cameraId?.let { mapOf("cameraId" to it) } ?: emptyMap(),
    )
}

private fun CameraMetadata.toIdentity(): CameraIdentity = CameraIdentity(
    cameraId = cameraId,
    lensFacing = lensFacing?.let { Knowledge.Known(lensFacingName(it)) } ?: Knowledge.Unknown,
    hardwareLevel = hardwareLevel?.let { Knowledge.Known(hardwareLevelName(it)) } ?: Knowledge.Unknown,
    activeArray = activeArray?.let { Knowledge.Known("${it.left},${it.top},${it.right},${it.bottom}") } ?: Knowledge.Unknown,
    focalLengthsMillimeters = focalLengths?.let { Knowledge.Known(it) } ?: Knowledge.Unknown,
    publicPhysicalIds = physicalIds.orEmpty().filter { it.isNotBlank() }.toSortedSet(),
    capabilities = capabilities.orEmpty().map { capabilityName(it) }.toSortedSet(),
)

private fun lensFacingName(value: Int): String = when (value) {
    CameraCharacteristics.LENS_FACING_FRONT -> "front"
    CameraCharacteristics.LENS_FACING_BACK -> "back"
    CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
    else -> "unknown"
}

private fun hardwareLevelName(value: Int): String = when (value) {
    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "legacy"
    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "limited"
    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "full"
    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "level3"
    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "external"
    else -> "unknown"
}

private fun capabilityName(value: Int): String = when (value) {
    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE -> "backward-compatible"
    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR -> "manual-sensor"
    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING -> "manual-post-processing"
    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW -> "raw"
    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING -> "private-reprocessing"
    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS -> "read-sensor-settings"
    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE -> "burst-capture"
    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING -> "yuv-reprocessing"
    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT -> "depth-output"
    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO -> "constrained-high-speed-video"
    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MOTION_TRACKING -> "motion-tracking"
    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA -> "logical-multi-camera"
    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME -> "monochrome"
    else -> "unknown-$value"
}
