/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.media.video

import android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
import android.media.MediaCodecInfo.CodecProfileLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoCodecChoiceTest {
    private val hevc = "video/hevc"

    @Test
    fun autoAndHevcUseHevcOnlyWhenSupported() {
        assertEquals(VideoCodec.HEVC, resolveVideoCodec(VideoCodecPreference.AUTO, true))
        assertEquals(VideoCodec.HEVC, resolveVideoCodec(VideoCodecPreference.HEVC, true))
        assertEquals(VideoCodec.H264, resolveVideoCodec(VideoCodecPreference.AUTO, false))
        assertEquals(VideoCodec.H264, resolveVideoCodec(VideoCodecPreference.HEVC, false))
    }

    @Test
    fun h264IsNeverUpgraded() {
        assertEquals(VideoCodec.H264, resolveVideoCodec(VideoCodecPreference.H264, true))
        assertEquals(VideoCodec.H264, resolveVideoCodec(VideoCodecPreference.H264, false))
    }

    @Test
    fun codecsNameTheirMimeAndLabel() {
        assertEquals("video/avc", VideoCodec.H264.mime)
        assertEquals("video/hevc", VideoCodec.HEVC.mime)
        assertEquals("H.264", VideoCodec.H264.label)
        assertEquals("HEVC", VideoCodec.HEVC.label)
    }

    @Test
    fun hevcMainPicksHardwareMainProfileWithoutLevel() {
        val list = listOf(
            encoder("c2.android.hevc.encoder", hw = false, profiles = listOf(CodecProfileLevel.HEVCProfileMain)),
            encoder("c2.qti.hevc.encoder", hw = true, profiles = listOf(CodecProfileLevel.HEVCProfileMain10, CodecProfileLevel.HEVCProfileMain)),
        )
        assertEquals(SurfaceEncoderPick("c2.qti.hevc.encoder", CodecProfileLevel.HEVCProfileMain, true, null), select(list, 3840, 2160, 120))
    }

    @Test
    fun hevcMainRejectsSoftwareMain10OnlyAndUnsupportedRates() {
        assertNull(select(listOf(encoder("c2.android.hevc.encoder", hw = false, profiles = listOf(CodecProfileLevel.HEVCProfileMain))), 1920, 1080, 30))
        assertNull(select(listOf(encoder("c2.qti.hevc.encoder", hw = true, profiles = listOf(CodecProfileLevel.HEVCProfileMain10))), 1920, 1080, 30))
        val hw = listOf(encoder("c2.qti.hevc.encoder", hw = true, profiles = listOf(CodecProfileLevel.HEVCProfileMain), maxFps = 120))
        assertEquals("c2.qti.hevc.encoder", select(hw, 3840, 2160, 120)?.codecName)
        assertNull(select(hw, 1920, 1080, 240))
    }

    private fun select(list: List<SurfaceEncoderInfo>, width: Int, height: Int, fps: Int) =
        VideoEncoderSelector().selectSurfaceEncoder(SurfaceEncoderPolicy.HEVC_MAIN_HARDWARE, width, height, fps, list.asSequence())

    private fun encoder(name: String, hw: Boolean, profiles: List<Int>, maxFps: Int = 240) =
        SurfaceEncoderInfo(name, isEncoder = true, isAlias = false, hardwareAccelerated = hw, supportedTypes = listOf(hevc)) { _ ->
            SurfaceEncoderCaps(
                colorFormats = listOf(COLOR_FormatSurface),
                profileLevels = profiles.map { EncoderProfileLevel(it, CodecProfileLevel.HEVCMainTierLevel51) },
                sizeAndRateSupported = { w, h, fps -> w <= 3840 && h <= 2160 && fps <= maxFps },
            )
        }
}
