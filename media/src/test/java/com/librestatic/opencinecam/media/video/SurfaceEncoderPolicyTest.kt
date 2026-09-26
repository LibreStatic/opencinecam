/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.media.video

import android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
import android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
import android.media.MediaCodecInfo.CodecProfileLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

/**
 * Proves that [VideoEncoderSelector.selectSurfaceEncoder] returns exactly what the OpenCineLog GPU
 * pipeline's pre-wiring `findEncoder`/`findAvcEncoder` returned. The oracles below are those two
 * functions verbatim, with MediaCodecInfo accessors replaced by the same fake model.
 */
class SurfaceEncoderPolicyTest {
    private val hevc = "video/hevc"
    private val avc = "video/avc"

    @Test
    fun emulatorListRejectsHevcAndAvcButAdmitsTimelapseSoftwareAvc() {
        val emulator = listOf(
            codec("OMX.google.h264.encoder", hw = false, types = listOf(avc),
                profiles = listOf(pl(CodecProfileLevel.AVCProfileBaseline, CodecProfileLevel.AVCLevel41))),
            codec("c2.android.avc.encoder", hw = false, types = listOf(avc),
                profiles = listOf(pl(CodecProfileLevel.AVCProfileBaseline, CodecProfileLevel.AVCLevel41),
                    pl(CodecProfileLevel.AVCProfileMain, CodecProfileLevel.AVCLevel41))),
            codec("c2.android.hevc.encoder", hw = false, types = listOf(hevc),
                profiles = listOf(pl(CodecProfileLevel.HEVCProfileMain, CodecProfileLevel.HEVCMainTierLevel41))),
            codec("OMX.google.h264.decoder", hw = false, encoder = false, types = listOf(avc)),
        )
        assertNull(selectHevc(emulator))
        assertNull(selectAvc(emulator, allowSoftware = false))
        val timelapse = selectAvc(emulator, allowSoftware = true)
        // Software ties sort by name, and "OMX." < "c2." in code-point order.
        assertEquals(SurfaceEncoderPick("OMX.google.h264.encoder", CodecProfileLevel.AVCProfileBaseline, false, CodecProfileLevel.AVCLevel41), timelapse)
        assertOracleEquivalent(emulator)
    }

    @Test
    fun onlyOmxGoogleSoftwareAvcIsPickedForTimelapseOnly() {
        val list = listOf(codec("OMX.google.h264.encoder", hw = false, types = listOf(avc),
            profiles = listOf(pl(CodecProfileLevel.AVCProfileBaseline, CodecProfileLevel.AVCLevel31))))
        assertNull(selectAvc(list, allowSoftware = false))
        assertEquals("OMX.google.h264.encoder", selectAvc(list, allowSoftware = true)?.codecName)
        assertOracleEquivalent(list)
    }

    @Test
    fun hardwareListPicksMain10AndAvcHighWithHighestLevelAheadOfSoftware() {
        val device = listOf(
            codec("c2.qti.hevc.encoder", hw = true, types = listOf(hevc), profiles = listOf(
                pl(CodecProfileLevel.HEVCProfileMain, CodecProfileLevel.HEVCMainTierLevel51),
                pl(CodecProfileLevel.HEVCProfileMain10HDR10, CodecProfileLevel.HEVCMainTierLevel51),
                pl(CodecProfileLevel.HEVCProfileMain10, CodecProfileLevel.HEVCMainTierLevel51),
            )),
            codec("OMX.qcom.video.encoder.hevc", hw = true, alias = true, types = listOf(hevc), profiles = listOf(
                pl(CodecProfileLevel.HEVCProfileMain10, CodecProfileLevel.HEVCMainTierLevel51))),
            codec("c2.qti.avc.encoder", hw = true, types = listOf(avc), profiles = listOf(
                pl(CodecProfileLevel.AVCProfileBaseline, CodecProfileLevel.AVCLevel52),
                pl(CodecProfileLevel.AVCProfileHigh, CodecProfileLevel.AVCLevel51),
                pl(CodecProfileLevel.AVCProfileHigh, CodecProfileLevel.AVCLevel52),
                pl(CodecProfileLevel.AVCProfileMain, CodecProfileLevel.AVCLevel52),
            )),
            codec("c2.android.avc.encoder", hw = false, types = listOf(avc), profiles = listOf(
                pl(CodecProfileLevel.AVCProfileHigh, CodecProfileLevel.AVCLevel62))),
        )
        assertEquals(SurfaceEncoderPick("c2.qti.hevc.encoder", CodecProfileLevel.HEVCProfileMain10HDR10, true, null), selectHevc(device))
        val sdr = SurfaceEncoderPick("c2.qti.avc.encoder", CodecProfileLevel.AVCProfileHigh, true, CodecProfileLevel.AVCLevel52)
        assertEquals(sdr, selectAvc(device, allowSoftware = false))
        assertEquals(sdr, selectAvc(device, allowSoftware = true))
        assertOracleEquivalent(device)
    }

    @Test
    fun sizeRateSurfaceAndCapabilityFailuresFallThroughByName() {
        val list = listOf(
            codec("a.hevc.small", hw = true, types = listOf(hevc), maxWidth = 1920,
                profiles = listOf(pl(CodecProfileLevel.HEVCProfileMain10, 1))),
            codec("b.hevc.bytebuffer", hw = true, types = listOf(hevc), surface = false,
                profiles = listOf(pl(CodecProfileLevel.HEVCProfileMain10, 1))),
            codec("c.hevc.throws", hw = true, types = listOf(hevc), throws = true),
            codec("d.hevc.novideo", hw = true, types = listOf(hevc), videoCaps = false,
                profiles = listOf(pl(CodecProfileLevel.HEVCProfileMain10, 1))),
            codec("e.HEVC.ok", hw = true, types = listOf("VIDEO/HEVC"),
                profiles = listOf(pl(CodecProfileLevel.HEVCProfileMain10HDR10Plus, 1))),
        )
        assertEquals("e.HEVC.ok", VideoEncoderSelector().selectSurfaceEncoder(
            SurfaceEncoderPolicy.HEVC_MAIN10_HARDWARE, 3840, 2160, 30, list.asSequence())?.codecName)
        assertEquals("a.hevc.small", selectHevc(list)?.codecName)
        assertOracleEquivalent(list)
    }

    @Test
    fun randomizedCodecListsMatchThePipelineOracles() {
        val random = Random(20260926)
        val names = listOf("c2.qti", "c2.exynos", "OMX.MTK", "c2.android", "OMX.google", "c2.mtk", "a", "z")
        val hevcProfiles = listOf(CodecProfileLevel.HEVCProfileMain, CodecProfileLevel.HEVCProfileMain10,
            CodecProfileLevel.HEVCProfileMain10HDR10, CodecProfileLevel.HEVCProfileMain10HDR10Plus, CodecProfileLevel.HEVCProfileMainStill)
        val avcProfiles = listOf(CodecProfileLevel.AVCProfileBaseline, CodecProfileLevel.AVCProfileMain,
            CodecProfileLevel.AVCProfileHigh, CodecProfileLevel.AVCProfileHigh10, CodecProfileLevel.AVCProfileConstrainedHigh)
        repeat(2_000) {
            val list = List(random.nextInt(0, 8)) { index ->
                val types = listOf(listOf(avc), listOf(hevc), listOf(avc, hevc), listOf("video/x-vnd.on2.vp8")).random(random)
                val pool = types.flatMap { if (it == avc) avcProfiles else if (it == hevc) hevcProfiles else emptyList() }
                codec(
                    name = names.random(random) + ".enc$index" + (if (random.nextBoolean()) ".avc" else ".hevc"),
                    hw = random.nextBoolean(), encoder = random.nextInt(8) != 0, alias = random.nextInt(6) == 0,
                    types = types, surface = random.nextInt(6) != 0, throws = random.nextInt(10) == 0,
                    videoCaps = random.nextInt(10) != 0, maxWidth = listOf(1280, 1920, 3840, 4096).random(random),
                    profiles = if (pool.isEmpty()) emptyList() else List(random.nextInt(0, 5)) { pl(pool.random(random), 1 shl random.nextInt(0, 16)) },
                )
            }.shuffled(random)
            assertOracleEquivalent(list)
        }
    }

    private fun assertOracleEquivalent(list: List<SurfaceEncoderInfo>) {
        for ((width, height) in listOf(1920 to 1080, 3840 to 2160, 1080 to 1920)) {
            for (fps in listOf(24, 30, 60, 120)) {
                assertEquals(oracleFindEncoder(list, width, height, fps),
                    VideoEncoderSelector().selectSurfaceEncoder(SurfaceEncoderPolicy.HEVC_MAIN10_HARDWARE, width, height, fps, list.asSequence()))
                for (software in listOf(false, true)) {
                    assertEquals(oracleFindAvcEncoder(list, width, height, fps, software),
                        VideoEncoderSelector().selectSurfaceEncoder(SurfaceEncoderPolicy.avcHigh(software), width, height, fps, list.asSequence()))
                }
            }
        }
    }

    private fun selectHevc(list: List<SurfaceEncoderInfo>) =
        VideoEncoderSelector().selectSurfaceEncoder(SurfaceEncoderPolicy.HEVC_MAIN10_HARDWARE, 1920, 1080, 30, list.asSequence())

    private fun selectAvc(list: List<SurfaceEncoderInfo>, allowSoftware: Boolean) =
        VideoEncoderSelector().selectSurfaceEncoder(SurfaceEncoderPolicy.avcHigh(allowSoftware), 1920, 1080, 30, list.asSequence())

    // Oracle: OpenCineLogGpuPipeline.findEncoder as of a32e0fe, over the fake model.
    private fun oracleFindEncoder(codecs: List<SurfaceEncoderInfo>, width: Int, height: Int, targetFps: Int): SurfaceEncoderPick? =
        codecs.asSequence()
            .filter { it.isEncoder && !it.isAlias && it.hardwareAccelerated }
            .filter { it.supportedTypes.any { type -> type.equals(hevc, true) } }
            .sortedBy { it.name }
            .mapNotNull { info ->
                val caps = runCatching { info.capabilities(hevc) }.getOrNull() ?: return@mapNotNull null
                if (COLOR_FormatSurface !in caps.colorFormats) return@mapNotNull null
                val profile = caps.profileLevels.map { it.profile }.firstOrNull {
                    it == CodecProfileLevel.HEVCProfileMain10 ||
                        it == CodecProfileLevel.HEVCProfileMain10HDR10 ||
                        it == CodecProfileLevel.HEVCProfileMain10HDR10Plus
                } ?: return@mapNotNull null
                if (caps.sizeAndRateSupported?.invoke(width, height, targetFps.toDouble()) != true) return@mapNotNull null
                // OpenCineLogEncoderCandidate(info.name, profile, size): hardwareAccelerated=true, level=null.
                SurfaceEncoderPick(info.name, profile, true, null)
            }
            .firstOrNull()

    // Oracle: OpenCineLogGpuPipeline.findAvcEncoder as of a32e0fe, over the fake model.
    private fun oracleFindAvcEncoder(codecs: List<SurfaceEncoderInfo>, width: Int, height: Int, targetFps: Int, allowSoftware: Boolean): SurfaceEncoderPick? =
        codecs.asSequence()
            .filter { it.isEncoder && !it.isAlias && (allowSoftware || it.hardwareAccelerated) }
            .filter { it.supportedTypes.any { type -> type.equals(avc, true) } }
            .sortedWith(compareByDescending<SurfaceEncoderInfo> { it.hardwareAccelerated }.thenBy { it.name })
            .mapNotNull { info ->
                val caps = runCatching { info.capabilities(avc) }.getOrNull() ?: return@mapNotNull null
                if (COLOR_FormatSurface !in caps.colorFormats) return@mapNotNull null
                val profile = caps.profileLevels.map { it.profile }.firstOrNull {
                    it == CodecProfileLevel.AVCProfileHigh
                } ?: caps.profileLevels.maxOfOrNull { it.profile } ?: return@mapNotNull null
                if (caps.sizeAndRateSupported?.invoke(width, height, targetFps.toDouble()) != true) return@mapNotNull null
                SurfaceEncoderPick(info.name, profile, info.hardwareAccelerated,
                    caps.profileLevels.filter { it.profile == profile }.maxOf { it.level })
            }
            .firstOrNull()

    private fun pl(profile: Int, level: Int) = EncoderProfileLevel(profile, level)

    private fun codec(
        name: String,
        hw: Boolean,
        types: List<String>,
        profiles: List<EncoderProfileLevel> = emptyList(),
        encoder: Boolean = true,
        alias: Boolean = false,
        surface: Boolean = true,
        throws: Boolean = false,
        videoCaps: Boolean = true,
        maxWidth: Int = 4096,
    ) = SurfaceEncoderInfo(name, encoder, alias, hw, types) { _ ->
        if (throws) throw IllegalArgumentException("unsupported type")
        SurfaceEncoderCaps(
            colorFormats = if (surface) listOf(COLOR_FormatYUV420Flexible, COLOR_FormatSurface) else listOf(COLOR_FormatYUV420Flexible),
            profileLevels = profiles,
            sizeAndRateSupported = if (videoCaps) { w, h, fps -> maxOf(w, h) <= maxWidth && fps <= (if (maxWidth >= 3840) 60.0 else 120.0) } else null,
        )
    }
}
