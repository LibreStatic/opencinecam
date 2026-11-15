/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.media.audio

import android.media.AudioRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PcmInputTest {
    private val razrStereo = AAudioStreamInfo(
        sampleRateHz = 48_000,
        channels = 2,
        format = AAudioNative.AAUDIO_FORMAT_PCM_I16,
        performanceMode = AAudioNative.AAUDIO_PERFORMANCE_MODE_LOW_LATENCY,
        deviceId = 23,
        framesPerBurst = 240,
        sessionId = -1,
        bufferCapacityFrames = 9_600,
    )

    @Test
    fun acceptsOnlyTheStreamThatWasAsked() {
        assertNull(aaudioStreamRefusal(48_000, 2, razrStereo))
        // Moved off the fast input (mode none, 960-frame burst), the Razr's stream is one microphone twice.
        assertEquals(PcmInputUnavailableException.REASON_NOT_LOW_LATENCY,
            aaudioStreamRefusal(48_000, 2, razrStereo.copy(performanceMode = 10, framesPerBurst = 960)))
        assertEquals(PcmInputUnavailableException.REASON_SAMPLE_RATE_MISMATCH,
            aaudioStreamRefusal(48_000, 2, razrStereo.copy(sampleRateHz = 44_100)))
        assertEquals(PcmInputUnavailableException.REASON_CHANNEL_MISMATCH,
            aaudioStreamRefusal(48_000, 2, razrStereo.copy(channels = 1)))
        assertEquals(PcmInputUnavailableException.REASON_FORMAT_MISMATCH,
            aaudioStreamRefusal(48_000, 2, razrStereo.copy(format = 2)))
    }

    @Test
    fun refusesAStreamRoutedAwayFromTheRequestedDevice() {
        assertNull(aaudioStreamRefusal(48_000, 2, razrStereo, requestedDeviceId = 23))
        assertNull(aaudioStreamRefusal(48_000, 2, razrStereo, requestedDeviceId = null))
        assertEquals(PcmInputUnavailableException.REASON_DEVICE_MISMATCH,
            aaudioStreamRefusal(48_000, 2, razrStereo, requestedDeviceId = 31))
        // An unspecified routed device is not known to be wrong.
        assertNull(aaudioStreamRefusal(48_000, 2, razrStereo.copy(deviceId = 0), requestedDeviceId = 31))
    }

    @Test
    fun mapsAAudioReadResultsToPcmInputCodes() {
        assertEquals(960, aaudioReadResult(240, 4))
        assertEquals(0, aaudioReadResult(0, 4))
        assertEquals(0, aaudioReadResult(AAudioNative.AAUDIO_ERROR_TIMEOUT, 4))
        assertEquals(PcmInput.ERROR_DEAD, aaudioReadResult(AAudioNative.AAUDIO_ERROR_DISCONNECTED, 4))
        assertEquals(PcmInput.ERROR_INVALID, aaudioReadResult(-895, 4)) // AAUDIO_ERROR_INVALID_STATE
        assertEquals(PcmInput.ERROR_INVALID, aaudioReadResult(-898, 4)) // AAUDIO_ERROR_ILLEGAL_ARGUMENT
    }

    @Test
    fun mapsAudioRecordReadCodesToPcmInputCodes() {
        assertEquals(3_840, audioRecordReadResult(3_840))
        assertEquals(0, audioRecordReadResult(0))
        assertEquals(PcmInput.ERROR_DEAD, audioRecordReadResult(AudioRecord.ERROR_DEAD_OBJECT))
        assertEquals(PcmInput.ERROR_INVALID, audioRecordReadResult(AudioRecord.ERROR_INVALID_OPERATION))
        assertEquals(PcmInput.ERROR_INVALID, audioRecordReadResult(AudioRecord.ERROR_BAD_VALUE))
        assertEquals(PcmInput.ERROR_INVALID, audioRecordReadResult(AudioRecord.ERROR))
    }

    @Test
    fun refusalReasonsAreStable() {
        // Take records and logs store these strings.
        assertEquals("native-library", PcmInputUnavailableException.REASON_NATIVE_LIBRARY)
        assertEquals("not-low-latency", PcmInputUnavailableException.REASON_NOT_LOW_LATENCY)
        assertEquals("format-mismatch", PcmInputUnavailableException.REASON_FORMAT_MISMATCH)
        assertEquals("device-mismatch", PcmInputUnavailableException.REASON_DEVICE_MISMATCH)
        assertEquals("open-failed:-889", PcmInputUnavailableException.REASON_OPEN_FAILED_PREFIX + -889)
    }
}
