/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.service

import com.librestatic.opencinecam.CameraSettings
import com.librestatic.opencinecam.CaptureMode
import com.librestatic.opencinecam.core.model.PolicyMode
import com.librestatic.opencinecam.media.audio.AudioBitDepth
import com.librestatic.opencinecam.media.audio.AudioOutputFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingStoragePlanTest {
    private val gib = 1L shl 30

    @Test fun admissionMatrixForEveryPolicy() {
        val perMinute = 1_500_000_000L // 200 Mbps, the encoder clamp: the reserve stays at 1 GiB
        for (policy in PolicyMode.entries) {
            assertEquals(StorageAdmission.Admit, admitRecordingStorage(policy, perMinute, gib))
            assertEquals(StorageAdmission.Refuse(STORAGE_RESERVE_INSUFFICIENT, gib, gib - 1),
                admitRecordingStorage(policy, perMinute, gib - 1))
        }
        assertEquals(StorageAdmission.Refuse(STORAGE_UNKNOWN, gib, null), admitRecordingStorage(PolicyMode.STRICT, perMinute, null))
        assertEquals(StorageAdmission.AdmitUnread, admitRecordingStorage(PolicyMode.ADAPTIVE, perMinute, null))
    }

    @Test fun theReserveNeverDropsBelowOneGibibyte() {
        assertEquals(StorageAdmission.Refuse(STORAGE_RESERVE_INSUFFICIENT, gib, gib - 1), admitRecordingStorage(PolicyMode.ADAPTIVE, 1L, gib - 1))
        assertEquals(StorageAdmission.Admit, admitRecordingStorage(PolicyMode.STRICT, 1L, gib))
    }

    @Test fun aBoundedSlowMotionTakeNeedsOnlyItsOwnLengthWithMargin() {
        val perMinute = 1_200_000_000L // 240 fps slow motion at 160 Mbps
        val free = 800_000_000L
        // 10 s is 200 MB: 512 MiB of headroom plus 250 MB is 786.9 MB, under the 1 GiB of an unbounded take.
        for (policy in PolicyMode.entries) {
            assertEquals(StorageAdmission.Admit, admitRecordingStorage(policy, perMinute, free, takeLimitMs = 10_000L))
            assertEquals(StorageAdmission.Refuse(STORAGE_RESERVE_INSUFFICIENT, gib, free), admitRecordingStorage(policy, perMinute, free))
        }
        assertEquals(StorageAdmission.Refuse(STORAGE_RESERVE_INSUFFICIENT, 786_870_912L, 786_870_911L),
            admitRecordingStorage(PolicyMode.STRICT, perMinute, 786_870_911L, takeLimitMs = 10_000L))
    }

    @Test fun boundedTakeBytesRoundsUpAndIsNullWithoutALimit() {
        assertNull(boundedTakeBytes(1_200_000_000L, null))
        assertEquals(200_000_000L, boundedTakeBytes(1_200_000_000L, 10_000L))
        assertEquals(1L, boundedTakeBytes(1L, 1L))
        assertEquals(1_001L, boundedTakeBytes(60_001L, 1_000L))
        assertEquals(0L, boundedTakeBytes(1_200_000_000L, 0L))
        assertEquals(Long.MAX_VALUE, boundedTakeBytes(Long.MAX_VALUE, 10_000L))
    }

    @Test fun audioRateFollowsTheFormat() {
        val aac = CameraSettings(audioOutputFormat = AudioOutputFormat.AAC_MP4, audioBitrateKbps = 192)
        assertEquals(192_000L, audioBitsPerSecond(aac, true))
        assertEquals(0L, audioBitsPerSecond(aac, false))
        val wav = CameraSettings(audioOutputFormat = AudioOutputFormat.WAV_PCM, audioSampleRateHz = 48_000, audioChannels = 2, audioBitDepth = AudioBitDepth.PCM_24)
        assertEquals(48_000L * 2 * 24, audioBitsPerSecond(wav, true))
    }

    @Test fun videoProjectionUsesTheScaledEncoderBitrate() {
        val settings = CameraSettings(videoBitrateMbps = 20, audioOutputFormat = AudioOutputFormat.AAC_MP4, audioBitrateKbps = 128)
        // 60 fps on the GPU path doubles the quoted 30 fps bitrate.
        assertEquals((40_000_000L + 128_000L) / 8 * 60, projectedTakeBytesPerMinute(CaptureMode.LOG, settings, 60, false, true))
        assertEquals(40_000_000L / 8 * 60, projectedTakeBytesPerMinute(CaptureMode.VIDEO, settings, 60, false, false))
        // 120 fps constrained high speed: 80 Mbps either way.
        assertEquals(80_000_000L / 8 * 60, projectedTakeBytesPerMinute(CaptureMode.VIDEO, settings, 120, true, false))
    }

    @Test fun constrainedHighSpeedOffSpeedProjectsTheCaptureRateRecorderBitrate() {
        val settings = CameraSettings(videoBitrateMbps = 20, videoOffSpeed = true)
        val perMinute = projectedTakeBytesPerMinute(CaptureMode.VIDEO, settings, 240, constrainedHighSpeed = true, audioRequested = false)
        assertEquals(1_200_000_000.0, perMinute.toDouble(), 1_200_000_000.0 * 0.02) // 160 Mbps
        // A short slow-motion take starts with 1 GiB free, not the 2.4 GB two minutes of it would take.
        assertEquals(StorageAdmission.Admit, admitRecordingStorage(PolicyMode.STRICT, perMinute, gib))
    }

    @Test fun timelapseAndOffSpeedTakesCarryNoAudio() {
        val settings = CameraSettings(videoBitrateMbps = 20, videoOffSpeed = true)
        assertEquals(projectedTakeBytesPerMinute(CaptureMode.TIME_LAPSE, settings, 30, false, false),
            projectedTakeBytesPerMinute(CaptureMode.TIME_LAPSE, settings, 30, false, true))
        assertEquals(projectedTakeBytesPerMinute(CaptureMode.VIDEO, settings, 30, false, false),
            projectedTakeBytesPerMinute(CaptureMode.VIDEO, settings, 30, false, true))
        assertTrue(projectedTakeBytesPerMinute(CaptureMode.TIME_LAPSE, settings, 30, false, false) > 0)
    }
}
