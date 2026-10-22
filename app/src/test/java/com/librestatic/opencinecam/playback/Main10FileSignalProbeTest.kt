/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.playback

import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaFormat
import com.librestatic.opencinecam.camera.Main10FileChecks
import com.librestatic.opencinecam.camera.Main10FileSignal
import com.librestatic.opencinecam.camera.Main10TakeKind
import com.librestatic.opencinecam.camera.QualificationStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class Main10FileSignalProbeTest {
    @Test fun anHlgMain10TrackMapsToThePassingSignal() {
        val signal = main10SignalOf("video/hevc", CodecProfileLevel.HEVCProfileMain10, MediaFormat.COLOR_STANDARD_BT2020, MediaFormat.COLOR_TRANSFER_HLG)
        assertEquals(Main10FileSignal("video/hevc", "Main10", "BT.2020", "HLG", 10), signal)
        assertEquals(QualificationStatus.PASS, Main10FileChecks.evaluate(Main10TakeKind.HLG, signal, null, 0L).fileSignal.status)
    }

    @Test fun hdr10ProfilesCountAsMain10() {
        assertEquals("Main10", main10SignalOf("video/hevc", CodecProfileLevel.HEVCProfileMain10HDR10, null, null).profile)
    }

    @Test fun anSdrMainTrackFailsAndMissingTagsStayUnknown() {
        val sdr = main10SignalOf("video/hevc", CodecProfileLevel.HEVCProfileMain, MediaFormat.COLOR_STANDARD_BT709, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        assertEquals("profile-not-main10", Main10FileChecks.evaluate(Main10TakeKind.HLG, sdr, null, 0L).fileSignal.reasonCode)
        val untagged = main10SignalOf("video/hevc", CodecProfileLevel.HEVCProfileMain10, null, null)
        assertEquals(QualificationStatus.UNKNOWN, Main10FileChecks.evaluate(Main10TakeKind.HLG, untagged, null, 0L).fileSignal.status)
        assertEquals(QualificationStatus.PASS, Main10FileChecks.evaluate(Main10TakeKind.LOG, untagged, null, 0L).fileSignal.status)
    }

    @Test fun anAvcTrackNeverPassesAsMain10() {
        val signal = main10SignalOf("video/avc", CodecProfileLevel.AVCProfileHigh, MediaFormat.COLOR_STANDARD_BT709, null)
        assertEquals(QualificationStatus.FAIL, Main10FileChecks.evaluate(Main10TakeKind.LOG, signal, null, 0L).fileSignal.status)
    }
}
