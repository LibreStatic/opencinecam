/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import com.librestatic.opencinecam.core.model.FailureCode
import com.librestatic.opencinecam.core.model.Knowledge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureRequestCompositionTest {
    @Test
    fun composesAutoRequestWithExplicitIspAndStabilization() {
        val result = CaptureRequestComposer().compose(CaptureIntent(ispMode = IspMode.HIGH_QUALITY), capabilities()) as CaptureRequestComposition.Accepted
        assertEquals(RequestValue.TextValue("ON"), result.request.values["CONTROL_AE_MODE"])
        assertEquals(RequestValue.TextValue("CONTINUOUS_VIDEO"), result.request.values["CONTROL_AF_MODE"])
        assertEquals(RequestValue.TextValue("AUTO"), result.request.values["CONTROL_AWB_MODE"])
        assertEquals(RequestValue.TextValue("HIGH_QUALITY"), result.request.values["EDGE_MODE"])
    }

    @Test
    fun manualExposureAndWhiteBalanceAreExactAndPrecedenceIsDeterministic() {
        val intent = CaptureIntent(
            exposureMode = ExposureMode.MANUAL,
            sensitivityIso = 800,
            exposureTimeNs = 10_000_000,
            frameDurationNs = 33_333_333,
            focusMode = FocusMode.MANUAL,
            focusDistanceDiopters = 2f,
            whiteBalanceMode = WhiteBalanceMode.MANUAL,
            manualGains = listOf(1f, 1.1f, 1.2f),
            manualColorTransform = List(9) { if (it % 4 == 0) 1 else 0 },
            stabilization = StabilizationMode.VIDEO,
        )
        val result = CaptureRequestComposer().compose(intent, capabilities()) as CaptureRequestComposition.Accepted
        assertEquals(RequestValue.TextValue("OFF"), result.request.values["CONTROL_AE_MODE"])
        assertEquals(RequestValue.IntValue(800), result.request.values["SENSOR_SENSITIVITY"])
        assertEquals(RequestValue.LongValue(10_000_000), result.request.values["SENSOR_EXPOSURE_TIME"])
        assertEquals(RequestValue.TextValue("OFF"), result.request.values["CONTROL_AWB_MODE"])
        assertEquals(RequestValue.TextValue("VIDEO"), result.request.values["CONTROL_VIDEO_STABILIZATION_MODE"])
    }

    @Test
    fun strictRejectsConflictsUnknownAndOutOfRangeValues() {
        val conflict = CaptureRequestComposer().compose(CaptureIntent(sensitivityIso = 100), capabilities()) as CaptureRequestComposition.Rejected
        assertEquals(FailureCode.INVALID_COMMAND, conflict.failure.code)

        val unknown = CaptureRequestComposer().compose(
            CaptureIntent(exposureMode = ExposureMode.MANUAL, sensitivityIso = 800, exposureTimeNs = 10_000_000, frameDurationNs = 33_333_333),
            capabilities().copy(sensitivityIso = Knowledge.Unknown),
        ) as CaptureRequestComposition.Rejected
        assertEquals(FailureCode.UNKNOWN_CAPABILITY, unknown.failure.code)

        val unsupported = CaptureRequestComposer().compose(
            CaptureIntent(exposureMode = ExposureMode.MANUAL, sensitivityIso = 800, exposureTimeNs = 10_000_000, frameDurationNs = 33_333_333),
            capabilities().copy(manualSensor = Knowledge.Known(false)),
        ) as CaptureRequestComposition.Rejected
        assertEquals(FailureCode.UNSUPPORTED_CAPABILITY, unsupported.failure.code)
    }

    @Test
    fun adaptiveClampsOnlyAdvertisedRangesAndDisclosesEveryChange() {
        val intent = CaptureIntent(
            exposureMode = ExposureMode.MANUAL,
            sensitivityIso = 4_000,
            exposureTimeNs = 100_000_000,
            frameDurationNs = 100_000_000,
            stabilization = StabilizationMode.VIDEO,
        )
        val result = CaptureRequestComposer(CompositionPolicy.ADAPTIVE).compose(intent, capabilities().copy(videoStabilization = Knowledge.Known(false))) as CaptureRequestComposition.Accepted
        assertEquals(RequestValue.IntValue(1600), result.request.values["SENSOR_SENSITIVITY"])
        assertEquals(RequestValue.LongValue(33_333_333), result.request.values["SENSOR_EXPOSURE_TIME"])
        assertTrue(result.request.disclosures.size >= 3)
    }

    @Test fun nativePriorityCompositionPreservesTheAutomaticParameter() {
        val caps = capabilities().copy(aePriorityModes = Knowledge.Known(setOf(ExposureMode.ISO_PRIORITY, ExposureMode.SHUTTER_PRIORITY)))
        val iso = CaptureRequestComposer().compose(CaptureIntent(exposureMode = ExposureMode.ISO_PRIORITY, sensitivityIso = 400), caps) as CaptureRequestComposition.Accepted
        assertEquals(RequestValue.TextValue("SENSOR_SENSITIVITY_PRIORITY"), iso.request.values["CONTROL_AE_PRIORITY_MODE"])
        assertTrue("SENSOR_EXPOSURE_TIME" !in iso.request.values && "SENSOR_FRAME_DURATION" !in iso.request.values)
        val shutter = CaptureRequestComposer().compose(CaptureIntent(exposureMode = ExposureMode.SHUTTER_PRIORITY, exposureTimeNs = 10_000_000L), caps) as CaptureRequestComposition.Accepted
        assertTrue("SENSOR_SENSITIVITY" !in shutter.request.values)
        assertEquals(RequestValue.TextValue("ON"), shutter.request.values["CONTROL_AE_MODE"])
    }
    @Test fun nativePriorityCompositionRejectsUnknownSupportAndConflictingParameters() {
        val intent = CaptureIntent(exposureMode = ExposureMode.ISO_PRIORITY, sensitivityIso = 400)
        val unknown = CaptureRequestComposer().compose(intent, capabilities()) as CaptureRequestComposition.Rejected
        assertEquals(FailureCode.UNKNOWN_CAPABILITY, unknown.failure.code)
        val caps = capabilities().copy(aePriorityModes = Knowledge.Known(setOf(ExposureMode.ISO_PRIORITY)))
        val conflict = CaptureRequestComposer().compose(intent.copy(exposureTimeNs = 10_000_000L), caps) as CaptureRequestComposition.Rejected
        assertEquals(FailureCode.INVALID_COMMAND, conflict.failure.code)
        val unsupported = CaptureRequestComposer().compose(intent, caps.copy(aePriorityModes = Knowledge.Known(emptySet()))) as CaptureRequestComposition.Rejected
        assertEquals(FailureCode.UNSUPPORTED_CAPABILITY, unsupported.failure.code)
    }

    @Test fun independentIspAndOpticalStabilizationUseCapabilityBackedFields() {
        val caps = capabilities().copy(opticalStabilization = Knowledge.Known(true), noiseReductionModes = Knowledge.Known(setOf(IspMode.HIGH_QUALITY)), edgeModes = Knowledge.Known(setOf(IspMode.OFF)))
        val result = CaptureRequestComposer().compose(CaptureIntent(stabilization = StabilizationMode.OPTICAL, noiseReductionMode = IspMode.HIGH_QUALITY, edgeMode = IspMode.OFF), caps) as CaptureRequestComposition.Accepted
        assertEquals(RequestValue.TextValue("ON"), result.request.values["LENS_OPTICAL_STABILIZATION_MODE"])
        assertEquals(RequestValue.TextValue("OFF"), result.request.values["CONTROL_VIDEO_STABILIZATION_MODE"])
        assertEquals(RequestValue.TextValue("HIGH_QUALITY"), result.request.values["NOISE_REDUCTION_MODE"])
        assertEquals(RequestValue.TextValue("OFF"), result.request.values["EDGE_MODE"])
    }
    @Test fun independentIspRejectsUnknownOrUnadvertisedModes() {
        val unknown = CaptureRequestComposer().compose(CaptureIntent(edgeMode = IspMode.OFF), capabilities()) as CaptureRequestComposition.Rejected
        assertEquals(FailureCode.UNKNOWN_CAPABILITY, unknown.failure.code)
        val unsupported = CaptureRequestComposer().compose(CaptureIntent(edgeMode = IspMode.OFF), capabilities().copy(edgeModes = Knowledge.Known(setOf(IspMode.FAST)))) as CaptureRequestComposition.Rejected
        assertEquals(FailureCode.UNSUPPORTED_CAPABILITY, unsupported.failure.code)
    }

    private fun capabilities() = CaptureRequestCapabilities(
        manualSensor = Knowledge.Known(true),
        manualPostProcessing = Knowledge.Known(true),
        sensitivityIso = Knowledge.Known(100..1600),
        exposureTimeNs = Knowledge.Known(1_000L..33_333_333L),
        frameDurationNs = Knowledge.Known(1_000_000L..100_000_000L),
        focusDistanceDiopters = Knowledge.Known(0f..10f),
        videoStabilization = Knowledge.Known(true),
    )
}
class LockStateEnumTest {
    @Test
    fun lockStatesAreOrderedOffPendingLocked() {
        assertEquals(0, LockState.OFF.ordinal)
        assertEquals(1, LockState.PENDING.ordinal)
        assertEquals(2, LockState.LOCKED.ordinal)
    }

    @Test
    fun afLockBehaviorHasTwoOptions() {
        assertEquals(2, AfLockBehavior.entries.size)
        assertTrue(AfLockBehavior.FREEZE_CURRENT in AfLockBehavior.entries)
        assertTrue(AfLockBehavior.FOCUS_AND_LOCK in AfLockBehavior.entries)
    }
}
