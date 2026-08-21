/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import android.graphics.ColorSpace
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.DynamicRangeProfiles
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Base64
import android.util.Log
import android.util.Size
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only physical probe for the resolution/FPS matrix that OCLog can truthfully expose. */
@RunWith(AndroidJUnit4::class)
class LogFormatMatrixDeviceTest {
    @Test
    fun writesLogFormatMatrixEvidence() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(CameraManager::class.java)
        val publicIds = manager.cameraIdList.toSet()
        val ids = buildSet {
            addAll(publicIds)
            publicIds.forEach { id ->
                addAll(manager.getCameraCharacteristics(id).physicalCameraIds)
            }
        }.sorted()
        val cameras = JSONArray().also { array ->
            ids.forEach { id ->
                runCatching { cameraEvidence(manager, id, id in publicIds) }
                    .onSuccess(array::put)
                    .onFailure { error ->
                        array.put(
                            JSONObject()
                                .put("cameraId", id)
                                .put("public", id in publicIds)
                                .put("error", "${error.javaClass.simpleName}: ${error.message.orEmpty().take(160)}"),
                        )
                    }
            }
        }
        val encoders = JSONArray().also { array ->
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.asSequence()
                .filter { it.isEncoder && !it.isAlias && it.isHardwareAccelerated }
                .filter { it.supportedTypes.any(MIME::equals) }
                .sortedBy { it.name }
                .forEach { array.put(encoderEvidence(it)) }
        }
        val output = JSONObject()
            .put("schema", "opencinecam-log-format-matrix-v1")
            .put("fingerprint", Build.FINGERPRINT)
            .put("publicCameraIds", strings(publicIds.sorted()))
            .put("cameras", cameras)
            .put("main10Encoders", encoders)
        val directory = requireNotNull(context.getExternalFilesDir(null))
        val file = File(directory, OUTPUT_NAME)
        val text = output.toString(2) + "\n"
        file.writeText(text, Charsets.UTF_8)
        Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            .chunked(LOG_PART_BYTES)
            .forEachIndexed { index, part -> Log.i(TAG, "json-part=${index + 1}:$part") }
        assertTrue(file.isFile && file.length() > 0)
    }

    private fun cameraEvidence(manager: CameraManager, cameraId: String, public: Boolean): JSONObject {
        val characteristics = manager.getCameraCharacteristics(cameraId)
        val map = requireNotNull(characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP))
        val ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
        val dynamicRanges = characteristics
            .get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
            ?.supportedProfiles.orEmpty()
        val hlgColors = if (Build.VERSION.SDK_INT >= 34 && DynamicRangeProfiles.HLG10 in dynamicRanges) {
            characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_COLOR_SPACE_PROFILES)
                ?.getSupportedColorSpacesForDynamicRange(ImageFormat.PRIVATE, DynamicRangeProfiles.HLG10)
                .orEmpty()
        } else emptySet()
        val privateSizes = map.getOutputSizes(MediaCodec::class.java).orEmpty().toList()
        return JSONObject()
            .put("cameraId", cameraId)
            .put("public", public)
            .put("physicalIds", strings(characteristics.physicalCameraIds.sorted()))
            .put("hlg10", DynamicRangeProfiles.HLG10 in dynamicRanges)
            .put("bt2020HlgPrivate", ColorSpace.Named.BT2020_HLG in hlgColors)
            .put(
                "regularCandidates",
                JSONArray().also { candidates ->
                    SIZES.forEach { size ->
                        if (privateSizes.any { it == size }) {
                            FPS.forEach { fps ->
                                val duration = runCatching { map.getOutputMinFrameDuration(MediaCodec::class.java, size) }
                                    .getOrDefault(0L)
                                val durationReachable = duration <= 0L || duration <= 1_000_000_000L / fps
                                val aeReachable = ranges.any { it.lower <= fps && it.upper >= fps }
                                candidates.put(
                                    JSONObject()
                                        .put("size", size.toString())
                                        .put("fps", fps)
                                        .put("minFrameDurationNs", duration)
                                        .put("durationReachable", durationReachable)
                                        .put("aeReachable", aeReachable),
                                )
                            }
                        }
                    }
                },
            )
            .put(
                "highSpeedProfiles",
                JSONArray().also { profiles ->
                    runCatching { map.highSpeedVideoSizes.toList() }.getOrDefault(emptyList()).forEach { size ->
                        runCatching { map.getHighSpeedVideoFpsRangesFor(size).toList() }.getOrDefault(emptyList())
                            .filter { it.lower == it.upper }
                            .forEach { range ->
                                profiles.put(JSONObject().put("size", size.toString()).put("fps", range.upper))
                            }
                    }
                },
            )
    }

    private fun encoderEvidence(info: MediaCodecInfo): JSONObject {
        val caps = info.getCapabilitiesForType(MIME)
        val profiles = caps.profileLevels.map { it.profile }.filter { it in MAIN10_PROFILES }.distinct()
        val surface = MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface in caps.colorFormats
        return JSONObject()
            .put("name", info.name)
            .put("main10Profiles", ints(profiles))
            .put("surfaceInput", surface)
            .put(
                "formats",
                JSONArray().also { formats ->
                    if (profiles.isNotEmpty() && surface) SIZES.forEach { size ->
                        FPS.forEach { fps ->
                            val rateSupported = runCatching {
                                caps.videoCapabilities?.areSizeAndRateSupported(size.width, size.height, fps.toDouble()) == true
                            }.getOrDefault(false)
                            val configured = if (rateSupported) configure(info.name, size, fps, profiles.first()) else false
                            formats.put(
                                JSONObject()
                                    .put("size", size.toString())
                                    .put("fps", fps)
                                    .put("rateSupported", rateSupported)
                                    .put("surfaceConfigured", configured),
                            )
                        }
                    }
                },
            )
    }

    private fun configure(name: String, size: Size, fps: Int, profile: Int): Boolean {
        var codec: MediaCodec? = null
        return try {
            val format = MediaFormat.createVideoFormat(MIME, size.width, size.height).apply {
                setInteger(MediaFormat.KEY_PROFILE, profile)
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_BIT_RATE, (size.width.toLong() * size.height * fps / 4).coerceIn(8_000_000, 160_000_000).toInt())
            }
            codec = MediaCodec.createByCodecName(name)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.createInputSurface().release()
            true
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { codec?.release() }
        }
    }

    private fun strings(values: Collection<String>) = JSONArray().also { array -> values.forEach(array::put) }
    private fun ints(values: Collection<Int>) = JSONArray().also { array -> values.forEach(array::put) }

    private companion object {
        const val TAG = "OCC_LOG_FORMAT_MATRIX"
        const val OUTPUT_NAME = "occ-log-format-matrix.json"
        const val LOG_PART_BYTES = 3000
        const val MIME = MediaFormat.MIMETYPE_VIDEO_HEVC
        val SIZES = listOf(Size(1280, 720), Size(1920, 1080), Size(3840, 2160))
        val FPS = listOf(10, 15, 24, 30, 60, 120, 240)
        val MAIN10_PROFILES = setOf(
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10,
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus,
        )
    }
}
