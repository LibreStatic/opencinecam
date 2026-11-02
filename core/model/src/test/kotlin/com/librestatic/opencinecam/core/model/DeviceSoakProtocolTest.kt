// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 OpenCineCam contributors

package com.librestatic.opencinecam.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceSoakProtocolTest {
    private val target = SoakTarget("test/device", 1, "profile-1")

    private fun observations(status: SoakObservationStatus = SoakObservationStatus.PASS) =
        SoakScenario.entries.map {
            SoakObservation(it, status, SoakProtocol.REQUIRED_DURATION_SECONDS, "e-${it.name}", "safe")
        }

    @Test
    fun completeThirtyMinuteMatrixQualifies() {
        val report = SoakProtocol.evaluate(target, SoakProtocol.REQUIRED_DURATION_SECONDS, observations())
        assertEquals(SoakGateStatus.QUALIFIED, report.status)
        assertTrue(report.failures.isEmpty())
    }

    @Test
    fun missingTargetOrScenarioCannotQualify() {
        val report = SoakProtocol.evaluate(null, 10, observations().dropLast(1))
        assertEquals(SoakGateStatus.NOT_RUN, report.status)
        assertTrue(report.missingScenarios.isNotEmpty())
        assertTrue(report.failures.any { it.contains("physical target") })
    }

    @Test
    fun failedObservationIsExplicit() {
        val report = SoakProtocol.evaluate(
            target,
            SoakProtocol.REQUIRED_DURATION_SECONDS,
            observations(SoakObservationStatus.FAIL),
        )
        assertEquals(SoakGateStatus.FAILED, report.status)
        assertEquals(SoakScenario.entries.size, report.failures.size)
    }

    @Test
    fun unknownEvidenceRemainsNotRun() {
        val observations = observations().toMutableList()
        observations[0] = observations[0].copy(status = SoakObservationStatus.UNKNOWN)
        val report = SoakProtocol.evaluate(target, SoakProtocol.REQUIRED_DURATION_SECONDS, observations)
        assertEquals(SoakGateStatus.NOT_RUN, report.status)
    }

    private fun notApplicable(scenario: SoakScenario = SoakScenario.FOLD_TRANSITION) =
        observations().map {
            if (it.scenario == scenario) it.copy(status = SoakObservationStatus.NOT_APPLICABLE, evidenceId = null) else it
        }

    @Test
    fun nonFoldableTargetQualifiesWithFoldNotApplicable() {
        val report = SoakProtocol.evaluate(
            target.copy(foldable = false),
            SoakProtocol.REQUIRED_DURATION_SECONDS,
            notApplicable(),
        )
        assertEquals(SoakGateStatus.QUALIFIED, report.status)
        assertTrue(report.failures.isEmpty())
    }

    @Test
    fun foldableTargetCannotSkipFoldTransition() {
        val report = SoakProtocol.evaluate(
            target.copy(foldable = true),
            SoakProtocol.REQUIRED_DURATION_SECONDS,
            notApplicable(),
        )
        assertEquals(SoakGateStatus.NOT_RUN, report.status)
        assertTrue(report.failures.contains("FOLD_TRANSITION: not applicable requires a non-foldable target"))
    }

    @Test
    fun undeclaredFoldabilityCannotSkipFoldTransition() {
        val report = SoakProtocol.evaluate(target, SoakProtocol.REQUIRED_DURATION_SECONDS, notApplicable())
        assertEquals(SoakGateStatus.NOT_RUN, report.status)
        assertTrue(report.failures.contains("FOLD_TRANSITION: not applicable requires a non-foldable target"))
    }

    @Test
    fun notApplicableIsRejectedForOtherScenarios() {
        val report = SoakProtocol.evaluate(
            target.copy(foldable = false),
            SoakProtocol.REQUIRED_DURATION_SECONDS,
            notApplicable(SoakScenario.THERMAL),
        )
        assertEquals(SoakGateStatus.NOT_RUN, report.status)
        assertTrue(report.failures.contains("THERMAL: not applicable requires a non-foldable target"))
    }
}
