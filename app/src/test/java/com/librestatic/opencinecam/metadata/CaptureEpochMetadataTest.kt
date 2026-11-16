/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.metadata

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.librestatic.opencinecam.camera.AudioCaptureEpoch
import com.librestatic.opencinecam.camera.CaptureEpochClock
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The take record's A/V timing names the manual sync offset beside the measured anchors (PLAN-074 decision 12). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CaptureEpochMetadataTest {
    private fun json(offsetNs: Long) = CaptureEpochClock(cameraRealtime = true, sampleRateHz = 48_000, audioSyncOffsetNs = offsetNs).run {
        videoInput(2_000_000_000L); audioInput(AudioCaptureEpoch(2_030_000_000L, true))
        captureEpochJson(report(0L, 0L, 1_024))
    }

    @Test fun theRecordCarriesTheSyncOffsetAndKeepsTheMeasuredAnchors() {
        val earlier = json(-100_000_000L)
        assertEquals(-100_000L, earlier.getLong("audioSyncOffsetUs"))
        assertEquals(2_030_000_000L, earlier.getLong("audioFrameZeroNs"))
        // The measured source difference, not the placed one.
        assertEquals(30_000_000L, earlier.getLong("sourceAudioMinusVideoNs"))
        assertEquals(1_930_000_000L, earlier.getLong("sharedOriginNs"))
        assertEquals(0L, json(0L).getLong("audioSyncOffsetUs"))
    }
}
