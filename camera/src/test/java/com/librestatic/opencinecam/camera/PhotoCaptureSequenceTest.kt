/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import org.junit.Assert.*
import org.junit.Test

class PhotoCaptureSequenceTest {
    @Test fun offOrManualCanCaptureWithoutInventingAnAeConvergence() {
        val sequence = PhotoCaptureSequence(false)
        assertEquals(PhotoCaptureSequence.Stage.CAPTURING, sequence.stage)
        assertFalse(sequence.observe(1, PhotoCaptureSequence.Ae.CONVERGED, false))
        assertTrue(sequence.result(100))
        assertTrue(sequence.complete(100))
    }
    @Test fun previewConvergenceBeforeTriggerAcknowledgmentCannotCapture() {
        val sequence = PhotoCaptureSequence(true)
        assertFalse(sequence.observe(100, PhotoCaptureSequence.Ae.CONVERGED, false))
        assertFalse(sequence.result(100))
        assertEquals(PhotoCaptureSequence.Stage.TRIGGER_PENDING, sequence.stage)
    }
    @Test fun olderPreviewResultAfterTriggerAcknowledgmentCannotCapture() {
        val sequence = PhotoCaptureSequence(true)
        sequence.triggerCompleted(10)
        assertFalse(sequence.observe(9, PhotoCaptureSequence.Ae.CONVERGED, false))
        assertEquals(PhotoCaptureSequence.Stage.METERING, sequence.stage)
    }
    @Test fun completedTriggerMayAlreadyReportConvergenceWithoutAPrecaptureState() {
        val sequence = PhotoCaptureSequence(true)
        sequence.triggerCompleted(10)
        assertTrue(sequence.observe(10, PhotoCaptureSequence.Ae.CONVERGED, false))
        assertEquals(PhotoCaptureSequence.Stage.CAPTURING, sequence.stage)
    }
    @Test fun precaptureAndUnknownAeNeverTimeOutIntoAStill() {
        val sequence = PhotoCaptureSequence(true)
        sequence.triggerCompleted(1)
        repeat(100) { frame ->
            assertFalse(sequence.observe(frame.toLong() + 1, PhotoCaptureSequence.Ae.PRECAPTURE, false))
            assertFalse(sequence.observe(frame.toLong() + 1, PhotoCaptureSequence.Ae.OTHER, false))
        }
        sequence.cancel()
        assertEquals(PhotoCaptureSequence.Stage.CANCELLED, sequence.stage)
        assertFalse(sequence.observe(200, PhotoCaptureSequence.Ae.CONVERGED, false))
        assertFalse(sequence.result(300))
    }
    @Test fun chargingPreventsCaptureEvenAfterAeConvergence() {
        val sequence = PhotoCaptureSequence(true)
        sequence.triggerCompleted(10)
        assertFalse(sequence.observe(11, PhotoCaptureSequence.Ae.CONVERGED, true))
        assertTrue(sequence.observe(12, PhotoCaptureSequence.Ae.CONVERGED, false))
    }
    @Test fun flashRequiredAfterMeteringCanSelectExactlyOneStill() {
        val sequence = PhotoCaptureSequence(true)
        sequence.triggerCompleted(10)
        assertTrue(sequence.observe(11, PhotoCaptureSequence.Ae.FLASH_REQUIRED, false))
        assertFalse(sequence.observe(12, PhotoCaptureSequence.Ae.FLASH_REQUIRED, false))
        sequence.triggerCompleted(13)
        assertFalse(sequence.observe(14, PhotoCaptureSequence.Ae.CONVERGED, false))
    }
    @Test fun invalidTriggerCannotAdvanceAndRepeatedTriggerDoesNotMoveBoundary() {
        val sequence = PhotoCaptureSequence(true)
        sequence.triggerCompleted(-1)
        assertEquals(PhotoCaptureSequence.Stage.TRIGGER_PENDING, sequence.stage)
        sequence.triggerCompleted(10)
        sequence.triggerCompleted(100)
        assertTrue(sequence.observe(11, PhotoCaptureSequence.Ae.CONVERGED, false))
    }
    @Test fun zeroNegativeAndDuplicateResultTimestampsAreRejected() {
        val sequence = PhotoCaptureSequence(false)
        assertFalse(sequence.result(0))
        assertFalse(sequence.result(-1))
        assertTrue(sequence.result(100))
        assertFalse(sequence.result(100))
        assertFalse(sequence.result(101))
        assertEquals(100L, sequence.sensorTimestampNs)
    }
    @Test fun jpegNeedsTheExactFinalResultTimestamp() {
        val sequence = PhotoCaptureSequence(false)
        assertFalse(sequence.complete(100))
        assertTrue(sequence.result(100))
        assertFalse(sequence.complete(99))
        assertFalse(sequence.complete(101))
        assertTrue(sequence.complete(100))
        assertFalse(sequence.complete(100))
    }
    @Test fun cancellationBetweenResultAndImageNeverCompletes() {
        val sequence = PhotoCaptureSequence(false)
        assertTrue(sequence.result(100))
        sequence.cancel()
        assertFalse(sequence.complete(100))
        assertFalse(sequence.result(101))
    }
    @Test fun completedCaptureCannotBeReopenedByLateTriggerResultOrCancellation() {
        val sequence = PhotoCaptureSequence(false)
        assertTrue(sequence.result(100))
        assertTrue(sequence.complete(100))
        sequence.triggerCompleted(200)
        sequence.cancel()
        assertEquals(PhotoCaptureSequence.Stage.COMPLETE, sequence.stage)
        assertFalse(sequence.observe(201, PhotoCaptureSequence.Ae.CONVERGED, false))
        assertFalse(sequence.result(202))
    }
    @Test fun offRetainsOnlyTheActuallyAppliedContinuousTorchAndItsStrength() {
        val selection = PhotoFlashSelection(PhotoFlashMode.OFF, strength = 9)
        val resolved = PhotoFlashResolution.Plan(null, 0, null, false)
        assertEquals(PhotoFlashResolution.Plan(null, 2, 3, false), photoStillPlan(selection, resolved, 2, 3))
        assertEquals(9, selection.strength)
        assertEquals(PhotoFlashResolution.Plan(null, 2, null, false), photoStillPlan(selection, resolved, 2, null))
    }
    @Test fun offDoesNotInventATorchFromInactiveOrUnreportedControl() {
        val selection = PhotoFlashSelection(PhotoFlashMode.OFF, strength = 9)
        val resolved = PhotoFlashResolution.Plan(null, 0, null, false)
        for (mode in listOf(null, 0, 1)) assertSame(resolved, photoStillPlan(selection, resolved, mode, 3))
    }
    @Test fun onAndAutoStillOverrideContinuousTorchWithTheirPhotographicPolicy() {
        val on = PhotoFlashResolution.Plan(1, 1, 4, true)
        val auto = PhotoFlashResolution.Plan(2, 0, null, true)
        assertSame(on, photoStillPlan(PhotoFlashSelection(PhotoFlashMode.ON, 4), on, 2, 3))
        assertSame(auto, photoStillPlan(PhotoFlashSelection(PhotoFlashMode.AUTO), auto, 2, 3))
    }
    @Test fun meteringRepeatingNeverPulsesOrCarriesSingleStrengthButTriggerPlanRemainsExact() {
        val triggerAndStill = PhotoFlashResolution.Plan(1, 1, 4, true)
        val repeat = photoMeteringRepeatPlan(triggerAndStill)
        assertEquals(PhotoFlashResolution.Plan(1, 0, null, true), repeat)
        assertEquals(PhotoFlashResolution.Plan(1, 1, 4, true), triggerAndStill)
    }
    @Test fun nonSingleMeteringPlansKeepTheirAePolicy() {
        for (plan in listOf(PhotoFlashResolution.Plan(2, 0, null, true), PhotoFlashResolution.Plan(3, 0, null, true))) {
            assertSame(plan, photoMeteringRepeatPlan(plan))
        }
    }

    @Test fun effectiveAeOffRecognizesLegacyManualWithoutProfessionalSelection() {
        assertEquals(ExposureMode.MANUAL, photoExposureMode(null, 0))
        assertEquals(ExposureMode.AUTO, photoExposureMode(null, 1))
        assertEquals(ExposureMode.MANUAL, photoExposureMode(ExposureMode.MANUAL, 0))
    }
    @Test fun unavailableRequestedManualCannotBecomeAnAutomaticFlashPolicy() {
        assertNull(photoExposureMode(ExposureMode.MANUAL, 1))
        assertNull(photoExposureMode(ExposureMode.MANUAL, null))
    }
    @Test fun requestedPrioritiesRemainVisibleForExplicitPolicyRejection() {
        for (priority in listOf(ExposureMode.ISO_PRIORITY, ExposureMode.SHUTTER_PRIORITY)) {
            assertEquals(priority, photoExposureMode(priority, 1))
            assertTrue(PhotoFlashSelection(PhotoFlashMode.ON).resolve(
                PhotoFlashCapabilities(true, setOf(0, 1, 2, 3)), requireNotNull(photoExposureMode(priority, 1))) is PhotoFlashResolution.Rejected)
        }
    }

}
