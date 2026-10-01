/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.playback

import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaFormat
import com.librestatic.opencinecam.storage.OcLogClip
import org.junit.Assert.*
import org.junit.Test

class ClipMetadataLabelsTest {
    @Test fun resolutionClassUsesTheShortEdgeInEitherOrientation() {
        assertEquals("4K 3840×2160", resolutionLabel(3840, 2160))
        assertEquals("4K 2160×3840", resolutionLabel(2160, 3840))
        assertEquals("4K 4096×2160", resolutionLabel(4096, 2160))
        assertEquals("1080p 1920×1080", resolutionLabel(1920, 1080))
        assertEquals("1080p 1080×1920", resolutionLabel(1080, 1920))
        assertEquals("720p 1280×720", resolutionLabel(1280, 720))
        assertEquals("1440×1080", resolutionLabel(1440, 1080))
        assertEquals("640×480", resolutionLabel(640, 480))
    }

    @Test fun frameRatesSnapToIntegerAndNtscRates() {
        assertEquals("23.976 fps", frameRateLabel(24000.0 / 1001.0))
        assertEquals("23.976 fps", frameRateLabel(23.976))
        assertEquals("24 fps", frameRateLabel(24.0))
        assertEquals("24 fps", frameRateLabel(24.004))
        assertEquals("25 fps", frameRateLabel(25.0))
        assertEquals("29.97 fps", frameRateLabel(30000.0 / 1001.0))
        assertEquals("30 fps", frameRateLabel(30.0))
        assertEquals("59.94 fps", frameRateLabel(59.94))
        assertEquals("119.88 fps", frameRateLabel(120000.0 / 1001.0))
        assertEquals("120 fps", frameRateLabel(120.0))
        assertEquals("240 fps", frameRateLabel(239.99))
        assertEquals("12.5 fps", frameRateLabel(12.5))
        assertEquals("0 fps", frameRateLabel(Double.NaN))
    }

    @Test fun frameRateIsEstimatedFromReorderedSampleTimes() {
        val times = (0 until 60).map { it * 1_001_000L / 24 }
        val decodeOrder = times.chunked(3).flatMap { listOf(it[0]) + it.drop(1).reversed() }
        assertEquals(24000.0 / 1001.0, frameRateFromSampleTimes(decodeOrder)!!, 0.001)
        assertNull(frameRateFromSampleTimes(listOf(0L, 0L)))
    }

    @Test fun codecLabelsDeriveBitDepthFromProfiles() {
        val hevc = MediaFormat.MIMETYPE_VIDEO_HEVC
        assertEquals("HEVC 10-bit", codecLabel(hevc, CodecProfileLevel.HEVCProfileMain10, null))
        assertEquals("HEVC 10-bit", codecLabel(hevc, CodecProfileLevel.HEVCProfileMain10HDR10, null))
        assertEquals("HEVC 10-bit", codecLabel(hevc, CodecProfileLevel.HEVCProfileMain10HDR10Plus, null))
        assertEquals("HEVC 8-bit", codecLabel(hevc, CodecProfileLevel.HEVCProfileMain, null))
        assertEquals("AVC 8-bit", codecLabel(MediaFormat.MIMETYPE_VIDEO_AVC, CodecProfileLevel.AVCProfileHigh, null))
        assertEquals("AVC 10-bit", codecLabel(MediaFormat.MIMETYPE_VIDEO_AVC, CodecProfileLevel.AVCProfileHigh10, null))
        assertEquals("AV1 10-bit", codecLabel(MediaFormat.MIMETYPE_VIDEO_AV1, CodecProfileLevel.AV1ProfileMain10, null))
        assertEquals("AV1 8-bit", codecLabel(MediaFormat.MIMETYPE_VIDEO_AV1, CodecProfileLevel.AV1ProfileMain8, null))
        assertEquals("HEVC", codecLabel(hevc, null, null))
        assertEquals("HEVC 10-bit", codecLabel(hevc, null, 10))
        assertEquals("AVC 8-bit", codecLabel("VIDEO/AVC", null, 8))
        assertNull(codecLabel(null, null, null))
        assertEquals(10, profileBitDepth(hevc, CodecProfileLevel.HEVCProfileMain10))
        assertNull(profileBitDepth("video/unknown", 2))
    }

    @Test fun colorLabelsNameTransferAndPrimaries() {
        val log = OcLogClip("OCLog2", "2", "BT.2020", true, "hevc", "Main10")
        assertEquals("OCLog2 · BT.2020", colorLabel(null, MediaFormat.COLOR_STANDARD_BT2020, log))
        assertEquals("OCLog2 · BT.2020", colorLabel(MediaFormat.COLOR_TRANSFER_HLG, null, log))
        assertEquals("HLG · BT.2020", colorLabel(MediaFormat.COLOR_TRANSFER_HLG, MediaFormat.COLOR_STANDARD_BT2020, null))
        assertEquals("HLG · BT.2020", colorLabel(MediaFormat.COLOR_TRANSFER_HLG, null, null))
        assertEquals("PQ · BT.2020", colorLabel(MediaFormat.COLOR_TRANSFER_ST2084, MediaFormat.COLOR_STANDARD_BT2020, null))
        assertEquals("SDR · BT.709", colorLabel(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, MediaFormat.COLOR_STANDARD_BT709, null))
        assertEquals("SDR · BT.709", colorLabel(null, MediaFormat.COLOR_STANDARD_BT709, null))
        assertEquals("SDR · BT.601", colorLabel(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, MediaFormat.COLOR_STANDARD_BT601_NTSC, null))
        assertEquals("SDR · BT.601", colorLabel(null, MediaFormat.COLOR_STANDARD_BT601_PAL, null))
        assertNull(colorLabel(null, null, null))
    }

    @Test fun durationsUseMinutesAndHours() {
        assertEquals("0:00", durationLabel(0))
        assertEquals("0:00", durationLabel(-5))
        assertEquals("0:12", durationLabel(12_000_000))
        assertEquals("0:13", durationLabel(12_600_000))
        assertEquals("1:05", durationLabel(65_000_000))
        assertEquals("59:59", durationLabel(3_599_000_000))
        assertEquals("1:02:03", durationLabel(3_723_000_000))
    }
}
