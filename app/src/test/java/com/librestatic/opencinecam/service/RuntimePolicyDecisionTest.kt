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

    @Test fun thermalEmergencyStopsInBothPolicies() {
        for (policy in PolicyMode.entries) for (status in listOf(PowerManager.THERMAL_STATUS_EMERGENCY, PowerManager.THERMAL_STATUS_SHUTDOWN)) {
            val verdict = evaluateRuntimePolicy(policy, status, roomy, perMinute)
            assertEquals(FailureCode.THERMAL_CRITICAL, verdict.stop?.code)
            assertEquals(TakeStopReason.THERMAL, takeStopReason(verdict.stop!!))
        }
    }

    @Test fun criticalHeatStopsStrictWhileAdaptiveKeepsRecording() {
        val strict = evaluateRuntimePolicy(PolicyMode.STRICT, PowerManager.THERMAL_STATUS_CRITICAL, roomy, perMinute)
        assertEquals(FailureCode.THERMAL_CRITICAL, strict.stop?.code)
        assertEquals(TakeStopReason.THERMAL, takeStopReason(strict.stop!!))
        assertEquals(RuntimeVerdict(null, RuntimeNotice.MONITORING_REDUCED),
            evaluateRuntimePolicy(PolicyMode.ADAPTIVE, PowerManager.THERMAL_STATUS_CRITICAL, roomy, perMinute))
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

    @Test fun aHighSpeedTakeStopsAtTheHeadroomCapNotAtAMinuteOfData() {
        val highSpeed = 1_500_000_000L // 200 Mbps
        val cap = 512L shl 20
        for (policy in PolicyMode.entries) {
            assertNull(evaluateRuntimePolicy(policy, PowerManager.THERMAL_STATUS_NONE, cap, highSpeed).stop)
            assertEquals(TakeStopReason.STORAGE,
                takeStopReason(evaluateRuntimePolicy(policy, PowerManager.THERMAL_STATUS_NONE, cap - 1, highSpeed).stop!!))
        }
    }

    @Test fun aRawTakeStopsAtItsOwnHeadroomNotAtTheCap() {
        val rawRoom = 8_000_000_000L // the room a RAW take was admitted with
        for (policy in PolicyMode.entries) {
            assertEquals(RuntimeVerdict(null, null),
                evaluateRuntimePolicy(policy, PowerManager.THERMAL_STATUS_NONE, rawRoom * 3, rawRoom, stopHeadroomBytes = rawRoom))
            assertEquals(RuntimeVerdict(null, RuntimeNotice.MONITORING_REDUCED),
                evaluateRuntimePolicy(policy, PowerManager.THERMAL_STATUS_NONE, rawRoom * 3 - 1, rawRoom, stopHeadroomBytes = rawRoom))
            assertNull(evaluateRuntimePolicy(policy, PowerManager.THERMAL_STATUS_NONE, rawRoom, rawRoom, stopHeadroomBytes = rawRoom).stop)
            assertEquals(TakeStopReason.STORAGE,
                takeStopReason(evaluateRuntimePolicy(policy, PowerManager.THERMAL_STATUS_NONE, rawRoom - 1, rawRoom, stopHeadroomBytes = rawRoom).stop!!))
        }
    }

    @Test fun aBoundedTakeGetsNoLowSpaceNoticeDownToItsStopHeadroom() {
        val slowMotion = 1_200_000_000L // 240 fps at 160 Mbps
        val headroom = 512L shl 20
        // 10 s is 200 MB: admitted with 512 MiB plus 250 MB free.
        val free = headroom + 250_000_000L
        for (policy in PolicyMode.entries) {
            assertEquals(RuntimeVerdict(null, null), evaluateRuntimePolicy(policy, PowerManager.THERMAL_STATUS_NONE, free, slowMotion, takeLimitMs = 10_000L))
            assertEquals(RuntimeVerdict(null, null),
                evaluateRuntimePolicy(policy, PowerManager.THERMAL_STATUS_NONE, headroom + 1, slowMotion, takeLimitMs = 10_000L))
            assertEquals(RuntimeVerdict(null, RuntimeNotice.MONITORING_REDUCED), evaluateRuntimePolicy(policy, PowerManager.THERMAL_STATUS_NONE, free, slowMotion))
            // The stop threshold is still the headroom.
            assertNull(evaluateRuntimePolicy(policy, PowerManager.THERMAL_STATUS_NONE, headroom, slowMotion, takeLimitMs = 10_000L).stop)
            assertEquals(TakeStopReason.STORAGE,
                takeStopReason(evaluateRuntimePolicy(policy, PowerManager.THERMAL_STATUS_NONE, headroom - 1, slowMotion, takeLimitMs = 10_000L).stop!!))
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
