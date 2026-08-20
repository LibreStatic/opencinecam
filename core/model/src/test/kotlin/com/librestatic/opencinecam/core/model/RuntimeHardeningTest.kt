/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeHardeningTest {
    @Test
    fun pressureAndDegradationOrderAreExplicit() {
        assertEquals(StoragePressure.SUFFICIENT, StorageRuntimeObservation(2_000_000, 1_000_000, 100_000).pressure)
        assertEquals(StoragePressure.LOW, StorageRuntimeObservation(1_050_000, 1_000_000, 100_000).pressure)
        assertEquals(StoragePressure.INSUFFICIENT, StorageRuntimeObservation(999_999, 1_000_000, 100_000).pressure)
        assertEquals(StoragePressure.UNKNOWN, StorageRuntimeObservation(null, 1_000_000, 100_000).pressure)

        val policy = RuntimeHardeningPolicy(PolicyMode.ADAPTIVE)
        assertEquals(DegradationStep.UI_ANIMATION, policy.nextDegradation(null))
        assertEquals(DegradationStep.VECTORSCOPE_WAVEFORM, policy.nextDegradation(DegradationStep.UI_ANIMATION))
        assertEquals(DegradationStep.PREVIEW_RESOLUTION_FPS, policy.nextDegradation(DegradationStep.ANALYSIS_STREAM))
        assertEquals(null, policy.nextDegradation(DegradationStep.PREVIEW_RESOLUTION_FPS))
    }

    @Test
    fun strictStopsCriticalThermalStorageUnknownAndFaultsAdaptiveWarnsAudio() {
        val strict = RuntimeHardeningPolicy(PolicyMode.STRICT)
        assertEquals(FailureCode.THERMAL_CRITICAL, (strict.evaluate(ThermalStatus.CRITICAL, StoragePressure.SUFFICIENT) as RuntimeHardeningDecision.Stopped).failure.code)
        assertEquals(FailureCode.STORAGE_WRITE_FAILED, (strict.evaluate(ThermalStatus.NORMAL, StoragePressure.INSUFFICIENT) as RuntimeHardeningDecision.Stopped).failure.code)
        assertEquals(FailureCode.UNKNOWN_CAPABILITY, (strict.evaluate(ThermalStatus.UNKNOWN, StoragePressure.SUFFICIENT) as RuntimeHardeningDecision.Stopped).failure.code)

        val adaptive = RuntimeHardeningPolicy(PolicyMode.ADAPTIVE)
        val warning = adaptive.evaluate(ThermalStatus.SEVERE, StoragePressure.LOW) as RuntimeHardeningDecision.Continue
        assertEquals(RuntimeAction.DEGRADE_MONITORING, warning.action)
        val audio = adaptive.evaluate(ThermalStatus.NORMAL, StoragePressure.SUFFICIENT, RuntimeFault("audio", FailureCode.AUDIO_RUNTIME_FAILED, 10)) as RuntimeHardeningDecision.Continue
        assertEquals(RuntimeAction.WARNING, audio.action)
    }

    @Test
    fun faultInjectionAndSoakTelemetryAreBoundedMonotonicAndClosable() {
        val injector = RuntimeFaultInjector(1)
        assertTrue(injector.inject(RuntimeFault("thermal", FailureCode.THERMAL_CRITICAL, 1)))
        assertFalse(injector.inject(RuntimeFault("storage", FailureCode.STORAGE_WRITE_FAILED, 2)))
        assertEquals("thermal", injector.poll()!!.id)

        val telemetry = SoakTelemetryRecorder(2)
        assertTrue(telemetry.record(SoakTelemetrySample(1, ThermalStatus.NORMAL, StoragePressure.SUFFICIENT, 0, 0, 0)))
        assertFalse(telemetry.record(SoakTelemetrySample(0, ThermalStatus.NORMAL, StoragePressure.SUFFICIENT, 0, 0, 0)))
        telemetry.record(SoakTelemetrySample(2, ThermalStatus.MODERATE, StoragePressure.LOW, 1, 2, 0))
        telemetry.record(SoakTelemetrySample(3, ThermalStatus.SEVERE, StoragePressure.LOW, 2, 3, 1))
        assertEquals(2, telemetry.snapshot().size)
        telemetry.close()
    }
}
