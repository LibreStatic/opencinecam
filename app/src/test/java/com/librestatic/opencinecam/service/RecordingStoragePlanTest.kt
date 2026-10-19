/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.service

import com.librestatic.opencinecam.CameraSettings
import com.librestatic.opencinecam.CaptureMode
import com.librestatic.opencinecam.core.model.PolicyMode
import com.librestatic.opencinecam.media.audio.AudioBitDepth
import com.librestatic.opencinecam.media.audio.AudioOutputFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingStoragePlanTest {
    private val gib = 1L shl 30

    @Test fun admissionMatrixForEveryPolicy() {
        val perMinute = 10L * 1_000_000_000L // 20 GB reserve: far above the 1 GiB floor
        for (policy in PolicyMode.entries) {
            assertEquals(StorageAdmission.Admit, admitRecordingStorage(policy, perMinute, 25_000_000_000L))
            assertEquals(StorageAdmission.Refuse(STORAGE_RESERVE_INSUFFICIENT, 20_000_000_000L, 19_000_000_000L),
                admitRecordingStorage(policy, perMinute, 19_000_000_000L))
        }
        assertEquals(StorageAdmission.Refuse(STORAGE_UNKNOWN, 20_000_000_000L, null), admitRecordingStorage(PolicyMode.STRICT, perMinute, null))
        assertEquals(StorageAdmission.AdmitUnread, admitRecordingStorage(PolicyMode.ADAPTIVE, perMinute, null))
    }

    @Test fun theReserveNeverDropsBelowOneGibibyte() {
        assertEquals(StorageAdmission.Refuse(STORAGE_RESERVE_INSUFFICIENT, gib, gib - 1), admitRecordingStorage(PolicyMode.ADAPTIVE, 1L, gib - 1))
        assertEquals(StorageAdmission.Admit, admitRecordingStorage(PolicyMode.STRICT, 1L, gib))
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

    @Test fun timelapseAndOffSpeedTakesCarryNoAudio() {
        val settings = CameraSettings(videoBitrateMbps = 20, videoOffSpeed = true)
        assertEquals(projectedTakeBytesPerMinute(CaptureMode.TIME_LAPSE, settings, 30, false, false),
            projectedTakeBytesPerMinute(CaptureMode.TIME_LAPSE, settings, 30, false, true))
        assertEquals(projectedTakeBytesPerMinute(CaptureMode.VIDEO, settings, 30, false, false),
            projectedTakeBytesPerMinute(CaptureMode.VIDEO, settings, 30, false, true))
        assertTrue(projectedTakeBytesPerMinute(CaptureMode.TIME_LAPSE, settings, 30, false, false) > 0)
    }
}
