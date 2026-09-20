/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import org.junit.Assert.*
import org.junit.Test

class FoldDisplayTest {
    @Test fun unavailableAndUnknownCapabilitiesNeverStartASession() {
        for (capability in DisplayCapability.entries.filter { it != DisplayCapability.AVAILABLE }) {
            val machine = FoldSessionStateMachine()
            machine.capabilities(capability, capability)
            assertNull(machine.begin(DisplayOperation.PRESENT))
            assertNull(machine.begin(DisplayOperation.TRANSFER))
        }
    }

    @Test fun presentationAndTransferHaveIndependentCapabilityGates() {
        val machine = FoldSessionStateMachine()
        machine.capabilities(DisplayCapability.UNSUPPORTED, DisplayCapability.AVAILABLE)
        assertNull(machine.begin(DisplayOperation.PRESENT))
        assertNotNull(machine.begin(DisplayOperation.TRANSFER))
    }

    @Test fun duplicateStartDoesNotCreateASecondSession() {
        val machine = available()
        val token = requireNotNull(machine.begin(DisplayOperation.PRESENT))
        assertNull(machine.begin(DisplayOperation.PRESENT))
        assertNull(machine.begin(DisplayOperation.TRANSFER))
        assertTrue(machine.started(token))
        assertFalse(machine.started(token))
    }

    @Test fun closingPendingSessionInvalidatesALateStart() {
        val machine = available()
        val token = requireNotNull(machine.begin(DisplayOperation.PRESENT))
        machine.close()
        assertFalse(machine.started(token))
        assertEquals(DisplaySessionPhase.IDLE, machine.state.phase)
    }

    @Test fun oldEndAndVisibilityCannotClobberANewSession() {
        val machine = available()
        val old = requireNotNull(machine.begin(DisplayOperation.PRESENT))
        machine.close()
        val current = requireNotNull(machine.begin(DisplayOperation.TRANSFER))
        assertTrue(machine.started(current))
        machine.visibility(current, true)
        assertFalse(machine.ended(old, "late error"))
        machine.visibility(old, false)
        assertTrue(machine.state.visible)
        assertNull(machine.state.failure)
        assertEquals(DisplayOperation.TRANSFER, machine.state.operation)
    }

    @Test fun systemDismissalClosesDisplayAndPreservesItsFailure() {
        val machine = available()
        val token = requireNotNull(machine.begin(DisplayOperation.PRESENT))
        machine.started(token)
        assertTrue(machine.ended(token, "display removed"))
        assertEquals(DisplaySessionPhase.IDLE, machine.state.phase)
        assertEquals("display removed", machine.state.failure)
    }

    @Test fun hingeCloseRequiresAnObservedOpenAndRejectsNoise() {
        val detector = FoldCloseDetector()
        assertFalse(detector.sample(0f))
        assertFalse(detector.sample(Float.NaN))
        assertFalse(detector.sample(181f))
        assertFalse(detector.sample(180f))
        assertTrue(detector.sample(3f))
        for (value in listOf(0f, 6f, 2f, 12f, 3f)) assertFalse(detector.sample(value))
        assertFalse(detector.sample(30f))
        assertTrue(detector.sample(0f))
    }

    @Test fun missingHingeDoesNotImplyFoldOrSplit() {
        assertNull(foldPanes(800, 1000, 0, 0, null, 16, 180, false))
    }

    @Test fun tabletopPanesAvoidTheHingeAndHonorWindowOffset() {
        val panes = requireNotNull(foldPanes(800, 1000, 0, 100, FoldHinge(0, 600, 800, 620, true), 16, 180, false))
        assertEquals(FoldPane(0, 0, 800, 492), panes.preview)
        assertEquals(FoldPane(0, 528, 800, 472), panes.controls)
    }

    @Test fun bookPanesAndSwapDoNotOverlap() {
        val hinge = FoldHinge(500, 0, 500, 900, false)
        val normal = requireNotNull(foldPanes(1000, 900, 0, 0, hinge, 16, 180, false))
        val swapped = requireNotNull(foldPanes(1000, 900, 0, 0, hinge, 16, 180, true))
        assertEquals(normal.preview, swapped.controls)
        assertEquals(normal.controls, swapped.preview)
        assertTrue(normal.preview.left + normal.preview.width < normal.controls.left)
    }

    @Test fun smallOrOffscreenPanesFallBackToNormalLayout() {
        assertNull(foldPanes(360, 400, 0, 0, FoldHinge(0, 50, 360, 50, true), 16, 180, false))
        assertNull(foldPanes(360, 800, 0, 0, FoldHinge(400, 400, 700, 400, true), 16, 180, false))
    }

    @Test fun closePolicyIsFrozenDuringRecordingWhilePrompterUpdatesLive() {
        val old = CameraSettings()
        val next = old.copy(subjectDisplay = old.subjectDisplay.copy(continueRecordingOnFold = false, prompterText = "Next line"))
        val effective = old.withLivePreferencesFrom(next)
        assertTrue(effective.subjectDisplay.continueRecordingOnFold)
        assertEquals("Next line", effective.subjectDisplay.prompterText)
        assertNotEquals(next, effective)
    }

    @Test(expected = IllegalArgumentException::class)
    fun scriptsHaveAnExplicitMemoryBound() { SubjectDisplaySettings(prompterText = "x".repeat(20_001)) }

    private fun available() = FoldSessionStateMachine().apply { capabilities(DisplayCapability.AVAILABLE, DisplayCapability.AVAILABLE) }
}
