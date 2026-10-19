/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.service

import android.os.PowerManager
import com.librestatic.opencinecam.camera.PcmSourceTimingReport
import com.librestatic.opencinecam.core.model.FailureCode
import com.librestatic.opencinecam.core.model.PolicyMode
import com.librestatic.opencinecam.media.audio.AvClockDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RuntimePolicyDecisionTest {
    private val perMinute = 150_000_000L
    private val roomy = 100_000_000_000L

    @Test fun aHealthyTakeContinuesInBothPolicies() {
        for (policy in PolicyMode.entries) {
            assertEquals(RuntimeVerdict(null, null), evaluateRuntimePolicy(policy, PowerManager.THERMAL_STATUS_NONE, roomy, perMinute))
            assertEquals(RuntimeVerdict(null, null), evaluateRuntimePolicy(policy, PowerManager.THERMAL_STATUS_MODERATE, roomy, perMinute))
        }
    }

    @Test fun criticalHeatStopsInBothPolicies() {
        for (policy in PolicyMode.entries) for (status in listOf(PowerManager.THERMAL_STATUS_CRITICAL, PowerManager.THERMAL_STATUS_EMERGENCY)) {
            val verdict = evaluateRuntimePolicy(policy, status, roomy, perMinute)
            assertEquals(FailureCode.THERMAL_CRITICAL, verdict.stop?.code)
            assertEquals(TakeStopReason.THERMAL, takeStopReason(verdict.stop!!))
        }
    }

    @Test fun severeHeatAndLowSpaceReduceMonitoringWithoutStopping() {
        for (policy in PolicyMode.entries) {
            assertEquals(RuntimeVerdict(null, RuntimeNotice.MONITORING_REDUCED),
                evaluateRuntimePolicy(policy, PowerManager.THERMAL_STATUS_SEVERE, roomy, perMinute))
            // Between one minute and one minute plus the 1 GiB reserve.
            assertEquals(RuntimeVerdict(null, RuntimeNotice.MONITORING_REDUCED),
                evaluateRuntimePolicy(policy, PowerManager.THERMAL_STATUS_NONE, perMinute + (1L shl 30) - 1, perMinute))
        }
    }

    @Test fun lessThanAMinuteOfSpaceStopsInBothPolicies() {
        for (policy in PolicyMode.entries) {
            val verdict = evaluateRuntimePolicy(policy, PowerManager.THERMAL_STATUS_NONE, perMinute - 1, perMinute)
            assertEquals(FailureCode.STORAGE_WRITE_FAILED, verdict.stop?.code)
            assertEquals(TakeStopReason.STORAGE, takeStopReason(verdict.stop!!))
        }
    }

    @Test fun anUnreadableStateStopsStrictAndOnlyWarnsAdaptive() {
        for ((thermal, free) in listOf(null to roomy, PowerManager.THERMAL_STATUS_NONE to null)) {
            val strict = evaluateRuntimePolicy(PolicyMode.STRICT, thermal, free, perMinute)
            assertEquals(TakeStopReason.STATE_UNKNOWN, takeStopReason(strict.stop!!))
            assertEquals(RuntimeVerdict(null, RuntimeNotice.STATE_UNKNOWN), evaluateRuntimePolicy(PolicyMode.ADAPTIVE, thermal, free, perMinute))
        }
    }

    @Test fun stopReasonsMapFromTheirFailures() {
        assertEquals(TakeStopReason.AUDIO_INPUT_LOST, takeStopReason(audioInputLostFailure()))
        assertEquals(TakeStopReason.CADENCE, takeStopReason(cadenceStopFailure("window")))
    }

    private fun drift(policy: PolicyMode): RuntimeVerdict {
        val diagnostics = AvClockDiagnostics(policy)
        diagnostics.observe(0, 0, 0)
        diagnostics.observe(1_000_000, 900_000, 1_000_000)
        // 100 ms of drift held for six seconds.
        return avDriftVerdict(diagnostics.observe(7_000_000, 6_900_000, 7_000_000))
    }

    @Test fun sustainedDriftStopsStrictAndWarnsAdaptive() {
        val strict = drift(PolicyMode.STRICT)
        assertEquals(TakeStopReason.AV_DRIFT, takeStopReason(strict.stop!!))
        assertEquals(RuntimeVerdict(null, RuntimeNotice.AV_DRIFT), drift(PolicyMode.ADAPTIVE))
    }

    @Test fun briefDriftDoesNothing() {
        val diagnostics = AvClockDiagnostics(PolicyMode.STRICT)
        diagnostics.observe(0, 0, 0)
        assertEquals(RuntimeVerdict(null, null), avDriftVerdict(diagnostics.observe(2_000_000, 1_900_000, 2_000_000)))
    }

    private fun timing(observations: Long = 5, firstFrame: Long? = 0, firstNs: Long? = 1_000_000_000, lastFrame: Long? = 480_000, lastNs: Long? = 11_000_000_000) =
        PcmSourceTimingReport(48_000, 2, 480_000, 480_000, 10_000_000_000, null, observations, 0, firstFrame, firstNs, lastFrame, lastNs)

    @Test fun liveClockSampleComparesBoottimeElapsedWithPcmFrames() {
        assertEquals(10_000_000L to 10_000_000L, avClockSample(true, timing()))
        assertEquals(10_000_000L to 9_900_000L, avClockSample(true, timing(lastFrame = 475_200)))
    }

    @Test fun liveClockSampleNeedsRealtimeCameraAndTwoDistinctTimestamps() {
        assertNull(avClockSample(false, timing()))
        assertNull(avClockSample(true, null))
        assertNull(avClockSample(true, timing(observations = 1)))
        assertNull(avClockSample(true, timing(lastFrame = 0)))
        assertNotNull(avClockSample(true, timing()))
    }
}
