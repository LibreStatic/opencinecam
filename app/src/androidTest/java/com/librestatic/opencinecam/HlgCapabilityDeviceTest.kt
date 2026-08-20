/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import android.content.Context
import android.graphics.ColorSpace
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.DynamicRangeProfiles
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.util.Base64
import android.util.Log
import android.util.Size
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.camera.HlgCapabilityReport
import com.librestatic.opencinecam.camera.HlgCapabilityState
import com.librestatic.opencinecam.camera.HlgDecisionTable
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HlgCapabilityDeviceTest {
    @Test
    fun writesPhysicalHlgMain10GateEvidence() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(CameraManager::class.java)
        val cameraEvidence = probeCameras(manager)
        val candidateSizes = cameraEvidence
            .filter { it.hlg10 && it.bt2020Hlg }
            .flatMap { it.privateSizes }
            .distinct()
            .ifEmpty { listOf(Size(1920, 1080)) }
        val codecEvidence = probeHevcEncoders(candidateSizes)
        val cameraHlg = state(cameraEvidence.any { it.hlg10 }, Build.VERSION.SDK_INT >= 33)
        val cameraBt2020 = state(cameraEvidence.any { it.hlg10 && it.bt2020Hlg }, Build.VERSION.SDK_INT >= 34)
        val qualifyingCodecs = codecEvidence.filter { it.qualifying }
        val codecMain10 = state(qualifyingCodecs.isNotEmpty(), true)
        val evidenceId = "occ-plan-034-${Build.FINGERPRINT.hashCode().toUInt().toString(16)}"
        val decision = HlgDecisionTable.evaluate(
            HlgCapabilityReport(Build.VERSION.SDK_INT, cameraHlg, cameraBt2020, codecMain10, evidenceId),
        )
        val output = JSONObject()
            .put("schema", "opencinecam-hlg-gate-v1")
            .put("protocolVersion", 1)
            .put("evidenceId", evidenceId)
            .put("collectedAtEpochMs", System.currentTimeMillis())
            .put("device", deviceEvidence(context))
            .put("cameras", JSONArray().also { array -> cameraEvidence.forEach { array.put(it.toJson()) } })
            .put("hevcEncoders", JSONArray().also { array -> codecEvidence.forEach { array.put(it.toJson()) } })
            .put(
                "capabilityStates",
                JSONObject()
                    .put("cameraHlg10", cameraHlg.name)
                    .put("cameraBt2020Hlg", cameraBt2020.name)
                    .put("hevcMain10Surface", codecMain10.name),
            )
            .put(
                "candidate",
                JSONObject()
                    .put("cameraIds", strings(cameraEvidence.filter { it.hlg10 && it.bt2020Hlg }.map { it.cameraId }))
                    .put("codecNames", strings(qualifyingCodecs.map { it.name })),
            )
            .put(
                "decision",
                JSONObject()
                    .put("status", decision.status.name)
                    .put("reasonCode", decision.reason?.code ?: JSONObject.NULL)
                    .put("safeMessage", decision.reason?.message ?: "HLG10 candidate graph is eligible")
                    .put("graph", decision.graph?.let(::graphJson) ?: JSONObject.NULL),
            )
            .put("capabilityPromotion", "candidate-only; file and effective-precision evidence not evaluated")

        val directory = requireNotNull(context.getExternalFilesDir(null)) { "external files directory unavailable" }
        val evidenceFile = File(directory, OUTPUT_NAME)
        val encodedEvidence = output.toString(2) + "\n"
        evidenceFile.writeText(encodedEvidence, Charsets.UTF_8)
        val encodedLog = Base64.encodeToString(encodedEvidence.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val parts = encodedLog.chunked(LOG_PART_BYTES)
        parts.forEachIndexed { index, part ->
            Log.i(TAG, "json-part=${index + 1}/${parts.size}:$part")
        }
        Log.i(TAG, "decision=${decision.status.name} jsonParts=${parts.size}")
        assertTrue("physical HLG gate evidence was not written", evidenceFile.isFile && evidenceFile.length() > 0)
    }

    private fun probeCameras(manager: CameraManager): List<CameraEvidence> = manager.cameraIdList.sorted().map { cameraId ->
        val characteristics = manager.getCameraCharacteristics(cameraId)
        val dynamicRanges = if (Build.VERSION.SDK_INT >= 33) {
            characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
                ?.supportedProfiles
                ?.toSortedSet()
                .orEmpty()
        } else {
            emptySet()
        }
        val hlg10 = DynamicRangeProfiles.HLG10 in dynamicRanges
        val hlgColorSpaces = if (Build.VERSION.SDK_INT >= 34 && hlg10) {
            characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_COLOR_SPACE_PROFILES)
                ?.getSupportedColorSpacesForDynamicRange(ImageFormat.PRIVATE, DynamicRangeProfiles.HLG10)
                ?.toSortedSet(compareBy(ColorSpace.Named::ordinal))
                .orEmpty()
        } else {
            emptySet()
        }
        val sizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(MediaCodec::class.java)
            ?.filter { it.width > 0 && it.height > 0 }
            ?.sortedWith(compareByDescending<Size> { it.width.toLong() * it.height }.thenByDescending { it.width })
            .orEmpty()
        CameraEvidence(
            cameraId = cameraId,
            lensFacing = lensFacingName(characteristics.get(CameraCharacteristics.LENS_FACING)),
            physicalCameraIds = characteristics.physicalCameraIds.toSortedSet(),
            dynamicRanges = dynamicRanges.map(::dynamicRangeName),
            hlg10 = hlg10,
            hlgColorSpaces = hlgColorSpaces.map(ColorSpace.Named::name),
            bt2020Hlg = ColorSpace.Named.BT2020_HLG in hlgColorSpaces,
            extraHlgLatency = if (Build.VERSION.SDK_INT >= 33 && hlg10) {
                characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
                    ?.isExtraLatencyPresent(DynamicRangeProfiles.HLG10)
            } else {
                null
            },
            privateSizes = sizes,
        )
    }

    private fun probeHevcEncoders(candidateSizes: List<Size>): List<CodecEvidence> =
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .asSequence()
            .filter { it.isEncoder && !it.isAlias && it.supportedTypes.any(MIME::equals) }
            .map { info -> probeHevcEncoder(info, candidateSizes) }
            .sortedBy(CodecEvidence::name)
            .toList()

    private fun probeHevcEncoder(info: MediaCodecInfo, candidateSizes: List<Size>): CodecEvidence {
        val capabilities = info.getCapabilitiesForType(MIME)
        val main10Profiles = capabilities.profileLevels
            .filter { it.profile in MAIN10_PROFILES }
            .sortedWith(compareBy({ MAIN10_PROFILES.indexOf(it.profile) }, { it.level }))
        val surfaceInput = MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface in capabilities.colorFormats
        val selectedProfile = main10Profiles.firstOrNull()?.profile
        val selectedSize = if (selectedProfile != null && surfaceInput) {
            preferredSizes(candidateSizes).firstOrNull { size ->
                runCatching { capabilities.videoCapabilities?.areSizeAndRateSupported(size.width, size.height, FPS) == true }
                    .getOrDefault(false)
            }
        } else {
            null
        }
        var formatSupported = false
        var surfaceConfigured = false
        var configureError: String? = null
        if (selectedProfile != null && selectedSize != null) {
            val format = candidateFormat(selectedSize, selectedProfile)
            formatSupported = runCatching { capabilities.isFormatSupported(format) }.getOrDefault(false)
            if (formatSupported) {
                val configured = configureSurface(info.name, format)
                surfaceConfigured = configured.first
                configureError = configured.second
            }
        }
        return CodecEvidence(
            name = info.name,
            hardwareAccelerated = info.isHardwareAccelerated,
            vendor = info.isVendor,
            surfaceInput = surfaceInput,
            main10Profiles = main10Profiles.map { ProfileLevel(it.profile, it.level) },
            selectedProfile = selectedProfile,
            selectedSize = selectedSize,
            fps = if (selectedSize == null) null else FPS,
            formatSupported = formatSupported,
            surfaceConfigured = surfaceConfigured,
            configureError = configureError,
            qualifying = info.isHardwareAccelerated && surfaceInput && formatSupported && surfaceConfigured,
        )
    }

    private fun configureSurface(codecName: String, format: MediaFormat): Pair<Boolean, String?> {
        var codec: MediaCodec? = null
        return try {
            codec = MediaCodec.createByCodecName(codecName)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.createInputSurface().release()
            true to null
        } catch (error: Exception) {
            false to "${error.javaClass.simpleName}: ${error.message.orEmpty().take(160)}"
        } finally {
            runCatching { codec?.release() }
        }
    }

    private fun candidateFormat(size: Size, profile: Int): MediaFormat =
        MediaFormat.createVideoFormat(MIME, size.width, size.height).apply {
            setInteger(MediaFormat.KEY_PROFILE, profile)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 20_000_000)
            setFloat(MediaFormat.KEY_FRAME_RATE, FPS.toFloat())
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
        }

    private fun preferredSizes(sizes: List<Size>): List<Size> = sizes.distinct().sortedWith(
        compareBy<Size> { if (it.width == 1920 && it.height == 1080) 0 else 1 }
            .thenByDescending { it.width.toLong() * it.height }
            .thenByDescending { it.width },
    )

    @Suppress("DEPRECATION")
    private fun deviceEvidence(context: Context): JSONObject {
        val storage = StatFs(requireNotNull(context.getExternalFilesDir(null)).absolutePath)
        val battery = context.getSystemService(BatteryManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        return JSONObject()
            .put("fingerprint", Build.FINGERPRINT)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("securityPatch", Build.VERSION.SECURITY_PATCH)
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("product", Build.PRODUCT)
            .put("appVersion", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
            .put("batteryPercent", battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            .put("thermalStatus", power.currentThermalStatus)
            .put("availableStorageBytes", storage.availableBytes)
    }

    private fun CameraEvidence.toJson(): JSONObject = JSONObject()
        .put("cameraId", cameraId)
        .put("lensFacing", lensFacing)
        .put("physicalCameraIds", strings(physicalCameraIds.toList()))
        .put("dynamicRangeProfiles", strings(dynamicRanges))
        .put("hlg10", hlg10)
        .put("hlgPrivateColorSpaces", strings(hlgColorSpaces))
        .put("bt2020Hlg", bt2020Hlg)
        .put("extraHlgLatency", extraHlgLatency ?: JSONObject.NULL)
        .put("privateOutputSizes", sizes(privateSizes))

    private fun CodecEvidence.toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("hardwareAccelerated", hardwareAccelerated)
        .put("vendor", vendor)
        .put("surfaceInput", surfaceInput)
        .put(
            "main10Profiles",
            JSONArray().also { array ->
                main10Profiles.forEach {
                    array.put(
                        JSONObject()
                            .put("profile", it.profile)
                            .put("profileName", profileName(it.profile))
                            .put("level", it.level),
                    )
                }
            },
        )
        .put("selectedProfile", selectedProfile ?: JSONObject.NULL)
        .put("selectedSize", selectedSize?.let { "${it.width}x${it.height}" } ?: JSONObject.NULL)
        .put("fps", fps ?: JSONObject.NULL)
        .put("formatSupported", formatSupported)
        .put("surfaceConfigured", surfaceConfigured)
        .put("configureError", configureError ?: JSONObject.NULL)
        .put("qualifying", qualifying)

    private fun graphJson(graph: com.librestatic.opencinecam.camera.HlgCandidateGraph): JSONObject = JSONObject()
        .put("dynamicRange", graph.dynamicRange)
        .put("colorSpace", graph.colorSpace)
        .put("transfer", graph.transfer)
        .put("codecMime", graph.codecMime)
        .put("codecProfile", graph.codecProfile)
        .put("input", graph.input)

    private fun strings(values: Collection<String>): JSONArray = JSONArray().also { array -> values.forEach(array::put) }

    private fun sizes(values: Collection<Size>): JSONArray = JSONArray().also { array ->
        values.forEach { array.put("${it.width}x${it.height}") }
    }

    private fun state(supported: Boolean, evidenceAvailable: Boolean): HlgCapabilityState = when {
        !evidenceAvailable -> HlgCapabilityState.UNKNOWN
        supported -> HlgCapabilityState.SUPPORTED
        else -> HlgCapabilityState.UNSUPPORTED
    }

    private fun dynamicRangeName(profile: Long): String = when (profile) {
        DynamicRangeProfiles.STANDARD -> "SDR"
        DynamicRangeProfiles.HLG10 -> "HLG10"
        DynamicRangeProfiles.HDR10 -> "HDR10"
        DynamicRangeProfiles.HDR10_PLUS -> "HDR10_PLUS"
        DynamicRangeProfiles.DOLBY_VISION_10B_HDR_REF -> "DOLBY_VISION_10B_HDR_REF"
        DynamicRangeProfiles.DOLBY_VISION_8B_HDR_REF -> "DOLBY_VISION_8B_HDR_REF"
        DynamicRangeProfiles.DOLBY_VISION_10B_HDR_REF_PO -> "DOLBY_VISION_10B_HDR_REF_PO"
        DynamicRangeProfiles.DOLBY_VISION_8B_HDR_REF_PO -> "DOLBY_VISION_8B_HDR_REF_PO"
        else -> "unknown-$profile"
    }

    private fun lensFacingName(value: Int?): String = when (value) {
        CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
        CameraCharacteristics.LENS_FACING_BACK -> "BACK"
        CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
        else -> "UNKNOWN"
    }

    private fun profileName(profile: Int): String = when (profile) {
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 -> "HEVCProfileMain10"
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 -> "HEVCProfileMain10HDR10"
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus -> "HEVCProfileMain10HDR10Plus"
        else -> "unknown-$profile"
    }

    private data class CameraEvidence(
        val cameraId: String,
        val lensFacing: String,
        val physicalCameraIds: Set<String>,
        val dynamicRanges: List<String>,
        val hlg10: Boolean,
        val hlgColorSpaces: List<String>,
        val bt2020Hlg: Boolean,
        val extraHlgLatency: Boolean?,
        val privateSizes: List<Size>,
    )

    private data class ProfileLevel(val profile: Int, val level: Int)

    private data class CodecEvidence(
        val name: String,
        val hardwareAccelerated: Boolean,
        val vendor: Boolean,
        val surfaceInput: Boolean,
        val main10Profiles: List<ProfileLevel>,
        val selectedProfile: Int?,
        val selectedSize: Size?,
        val fps: Double?,
        val formatSupported: Boolean,
        val surfaceConfigured: Boolean,
        val configureError: String?,
        val qualifying: Boolean,
    )

    private companion object {
        const val TAG = "OCC_HLG_GATE"
        const val OUTPUT_NAME = "occ-plan-034-hlg-gate.json"
        const val LOG_PART_BYTES = 3000
        const val MIME = MediaFormat.MIMETYPE_VIDEO_HEVC
        const val FPS = 30.0
        val MAIN10_PROFILES = listOf(
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10,
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus,
        )
    }
}
