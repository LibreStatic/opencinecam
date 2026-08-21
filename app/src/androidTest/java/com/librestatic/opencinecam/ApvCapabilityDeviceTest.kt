/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Public API 36+ APV enumeration/configuration gate. It makes no file/decode claim. */
@RunWith(AndroidJUnit4::class)
class ApvCapabilityDeviceTest {
    @Test
    fun writesPhysicalApvCapabilityEvidence() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val codecs = if (Build.VERSION.SDK_INT >= 36) probeEncoders() else emptyList()
        val surfaceCandidates = codecs.filter { it.surfaceInput && it.configureSucceeded }
        val qualifying = surfaceCandidates.filter { it.hardwareAccelerated }
        val status = when {
            Build.VERSION.SDK_INT < 36 -> "UNSUPPORTED"
            codecs.isEmpty() -> "UNSUPPORTED"
            qualifying.isEmpty() -> "UNSUPPORTED"
            else -> "CANDIDATE"
        }
        val reasonCode = when {
            Build.VERSION.SDK_INT < 36 -> "api-level-below-36"
            codecs.isEmpty() -> "apv-mime-unavailable"
            surfaceCandidates.isEmpty() -> "apv-surface-configuration-failed"
            qualifying.isEmpty() -> "apv-hardware-encoder-unavailable"
            else -> "apv-surface-candidate"
        }
        val evidence = JSONObject()
            .put("schema", "opencinecam-apv-capability-v1")
            .put("protocolVersion", 1)
            .put("collectedAtEpochMs", System.currentTimeMillis())
            .put("apiLevel", Build.VERSION.SDK_INT)
            .put("fingerprint", Build.FINGERPRINT)
            .put("model", Build.MODEL)
            .put("status", status)
            .put("reasonCode", reasonCode)
            .put("profileRequirement", "APV 4:2:2 10-bit")
            .put("encoders", JSONArray().also { array -> codecs.forEach { array.put(it.toJson()) } })
            .put("capabilityPromotion", "candidate-only; MP4, storage, extraction, decode, and sustained evidence not evaluated")

        val encoded = evidence.toString(2) + "\n"
        val directory = requireNotNull(context.getExternalFilesDir(null))
        val file = File(directory, OUTPUT_NAME)
        file.writeText(encoded, Charsets.UTF_8)
        val base64 = Base64.encodeToString(encoded.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val parts = base64.chunked(LOG_PART_BYTES)
        parts.forEachIndexed { index, part -> Log.i(TAG, "json-part=${index + 1}/${parts.size}:$part") }
        Log.i(TAG, "status=$status reasonCode=$reasonCode jsonParts=${parts.size}")
        assertTrue(file.isFile && file.length() > 0)
    }

    private fun probeEncoders(): List<EncoderEvidence> = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
        .asSequence()
        .filter { it.isEncoder && !it.isAlias && it.supportedTypes.any(MIME::equals) }
        .map { info -> probe(info) }
        .sortedBy { it.name }
        .toList()

    private fun probe(info: MediaCodecInfo): EncoderEvidence {
        val capabilities = info.getCapabilitiesForType(MIME)
        val surface = MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface in capabilities.colorFormats
        val profiles = capabilities.profileLevels.map { profileName(it.profile) }.distinct().sorted()
        val format = MediaFormat.createVideoFormat(MIME, WIDTH, HEIGHT).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val supported = runCatching { capabilities.isFormatSupported(format) }.getOrDefault(false)
        var configured = false
        var error: String? = null
        if (surface && supported) {
            var codec: MediaCodec? = null
            try {
                codec = MediaCodec.createByCodecName(info.name)
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.createInputSurface().release()
                configured = true
            } catch (failure: Exception) {
                error = "${failure.javaClass.simpleName}: ${failure.message.orEmpty().take(160)}"
            } finally {
                runCatching { codec?.release() }
            }
        }
        return EncoderEvidence(
            info.name,
            info.isHardwareAccelerated,
            info.isVendor,
            surface,
            profiles,
            supported,
            configured,
            error,
        )
    }

    private fun profileName(profile: Int): String = when (profile) {
        MediaCodecInfo.CodecProfileLevel.APVProfile422_10 -> "APVProfile422_10"
        MediaCodecInfo.CodecProfileLevel.APVProfile422_10HDR10 -> "APVProfile422_10HDR10"
        MediaCodecInfo.CodecProfileLevel.APVProfile422_10HDR10Plus -> "APVProfile422_10HDR10Plus"
        else -> "profile-$profile"
    }

    private data class EncoderEvidence(
        val name: String,
        val hardwareAccelerated: Boolean,
        val vendor: Boolean,
        val surfaceInput: Boolean,
        val profiles: List<String>,
        val formatSupported: Boolean,
        val configureSucceeded: Boolean,
        val configureError: String?,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("name", name)
            .put("hardwareAccelerated", hardwareAccelerated)
            .put("vendor", vendor)
            .put("surfaceInput", surfaceInput)
            .put("profiles", JSONArray(profiles))
            .put("formatSupported1080p30", formatSupported)
            .put("configureSucceeded", configureSucceeded)
            .put("configureError", configureError ?: JSONObject.NULL)
    }

    companion object {
        private const val MIME = "video/apv"
        private const val WIDTH = 1920
        private const val HEIGHT = 1080
        private const val FPS = 30
        private const val BIT_RATE = 120_000_000
        private const val OUTPUT_NAME = "occ-plan-051-apv-capability.json"
        private const val TAG = "OCC_APV_EVIDENCE"
        private const val LOG_PART_BYTES = 3000
    }
}
