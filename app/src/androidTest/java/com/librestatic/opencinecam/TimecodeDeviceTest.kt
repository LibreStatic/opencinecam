/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam
import android.os.SystemClock
import com.librestatic.opencinecam.camera.*
import org.junit.Assert.*
import org.junit.Test

/** Runs the actual platform BigInteger/clock implementation, not only JVM unit-test substitutes. */
class TimecodeDeviceTest {
    @Test fun exactTimecodeArithmeticUsesMinApiCompatiblePlatformMethods() {
        assertEquals(107892L,TimecodeRate(30,true).framesForElapsedNs(3600000000000L))
        assertEquals(215784L,TimecodeRate(60,true).framesForElapsedNs(3600000000000L))
        assertEquals(276701161105L,TimecodeRate(30).framesForElapsedNs(Long.MAX_VALUE))
        assertEquals(107892L,SmpteTimecode(1,0,0,0,true).toTotalFrames(TimecodeRate(30,true)))
    }
    @Test fun freeRunHourCountRunsWithoutTruncatedDurationDriftOnDevice() {
        var now=SystemClock.elapsedRealtimeNanos();val tracker=TimecodeTracker{now}
        tracker.configure(TimecodeRate(30,true),TimecodeMode.FREE_RUN,SmpteTimecode(0,0,0,0,true),true)
        now+=3600000000000L
        assertEquals("01:00:00;00",tracker.currentDisplayTc()!!.format())
        assertEquals("01:00:00;00",tracker.onFrame(3600000000L,107892L)!!.format())
    }

    @Test fun unavailableEncodedProgressSerializesNullRatherThanAnEstimate() {
        val tracker = TimecodeTracker { 0 }
        tracker.configure(TimecodeRate(30), TimecodeMode.RECORD_RUN, SmpteTimecode(1, 0, 0, 0), true)
        tracker.onRecordingStarted()
        val json = recordingTimecodeJson(requireNotNull(tracker.recordingReport()))
        assertEquals("ENCODED_PROGRESS_UNAVAILABLE", json.getString("frameMapping"))
        for (key in listOf("takeId", "encodedFrames", "firstPtsUs", "lastPtsUs", "firstFrameTimecode", "lastFrameTimecode")) {
            assertTrue(key, json.has(key)); assertTrue(key, json.isNull(key))
        }
        assertFalse(json.getBoolean("containerTimecodeTrackWritten"))
    }
    @Test fun freeRunSidecarDoesNotPresentReceiptClockAsSourceFrameTimecode() {
        val tracker = TimecodeTracker { 0 }
        tracker.configure(TimecodeRate(30), TimecodeMode.FREE_RUN, SmpteTimecode(1, 0, 0, 0), true)
        tracker.onRecordingStarted(1); tracker.observeEncodedProgress(EncodedRecordingProgress(1, 3, 0, 66666))
        val json = recordingTimecodeJson(requireNotNull(tracker.recordingReport()))
        assertEquals("RECEIPT_CLOCK_DISPLAY_ONLY_SOURCE_MAPPING_UNQUALIFIED", json.getString("frameMapping"))
        assertEquals(3L, json.getLong("encodedFrames")); assertTrue(json.isNull("firstFrameTimecode")); assertTrue(json.isNull("lastFrameTimecode"))
        assertFalse(json.getBoolean("sourceClockSynchronizationVerified"))
    }
    @Test fun disabledTakeMetadataRemainsDisabledAfterSettingsChange() {
        val tracker = TimecodeTracker { 0 }; tracker.onRecordingStarted(1)
        tracker.configure(TimecodeRate(30), TimecodeMode.REGEN, SmpteTimecode(2, 0, 0, 0), true)
        tracker.observeEncodedProgress(EncodedRecordingProgress(1, 1, 0, 0))
        val json = recordingTimecodeJson(requireNotNull(tracker.recordingReport()))
        assertFalse(json.getBoolean("enabled")); assertEquals("DISABLED", json.getString("frameMapping"))
        assertEquals("01:00:00:00", json.getString("configuredStart")); assertTrue(json.isNull("firstFrameTimecode"))
    }
}
