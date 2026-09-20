/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import org.junit.Assert.*
import org.junit.Test

class CaptureCountdownTest {
    @Test fun secondsUseCeilingAndFireExactlyOnceAtDeadline() {
        val timer = CaptureCountdown<String>()
        val ticket = timer.start(100, 3, "camera1-photo")
        assertEquals(3, timer.tick(ticket, 100, ticket.context, true).seconds)
        assertEquals(3, timer.tick(ticket, 1_099, ticket.context, true).seconds)
        assertEquals(2, timer.tick(ticket, 1_100, ticket.context, true).seconds)
        assertEquals(1, timer.tick(ticket, 3_099, ticket.context, true).seconds)
        assertTrue(timer.tick(ticket, 3_100, ticket.context, true).fire)
        assertNull(timer.active)
        assertFalse(timer.tick(ticket, 3_101, ticket.context, true).fire)
    }
    @Test fun cancellationAndLateRunnableCannotReviveOrConsumeNewTimer() {
        val timer = CaptureCountdown<Int>()
        val old = timer.start(0, 3, 1)
        timer.cancel()
        val newer = timer.start(200, 5, 1)
        assertFalse(timer.tick(old, 3_000, 1, true).fire)
        assertEquals(newer, timer.active)
        assertTrue(timer.tick(newer, 5_200, 1, true).fire)
    }
    @Test fun changedCameraModeOrProfileCancelsInsteadOfCapturingDifferentSetup() {
        val timer = CaptureCountdown<List<Any>>()
        val ticket = timer.start(0, 3, listOf("camera1", "VIDEO", 30))
        assertFalse(timer.tick(ticket, 3_000, listOf("camera2", "VIDEO", 30), true).fire)
        assertNull(timer.active)
    }
    @Test fun lostRoleOrUnavailablePreviewCancelsAtDeadline() {
        val timer = CaptureCountdown<String>()
        val ticket = timer.start(0, 3, "same")
        assertFalse(timer.tick(ticket, 3_000, "same", false).fire)
        assertNull(timer.active)
        assertFalse(timer.tick(ticket, 3_100, "same", true).fire)
    }
    @Test fun suspendedOrBackwardsClockNeverFiresAnUnexpectedLateCapture() {
        val timer = CaptureCountdown<Int>()
        val old = timer.start(500, 3, 1)
        assertFalse(timer.tick(old, 4_501, 1, true).fire)
        val current = timer.start(5_000, 3, 1)
        assertFalse(timer.tick(current, 4_999, 1, true).fire)
    }
    @Test fun supportedDurationsAndLongClockDoNotOverflow() {
        val timer = CaptureCountdown<Unit>()
        for (seconds in listOf(3, 5, 10)) {
            val start = Long.MAX_VALUE - 10_000
            val ticket = timer.start(start, seconds, Unit)
            assertEquals(seconds, timer.tick(ticket, start, Unit, true).seconds)
            assertTrue(timer.tick(ticket, start + seconds * 1_000L, Unit, true).fire)
        }
        for (seconds in listOf(-1, 0, 1, 60)) assertTrue(runCatching { timer.start(0, seconds, Unit) }.isFailure)
    }
    @Test fun permissionIntentCannotAuthorizeAReplacementRoleOrChangedSetup() {
        val permission = CaptureActionTicket(selfRole = true, generation = 4)
        assertTrue(permission.isCurrent(true, 4))
        assertFalse(permission.isCurrent(false, 4))
        assertFalse(permission.isCurrent(true, 5))
        // Leaving and returning to the same role still creates a different authorization context.
        assertFalse(permission.isCurrent(true, 6))
    }
    @Test fun settingsRejectInvalidDelaysAndKeepOriginalDefaults() {
        assertEquals(0, SubjectDisplaySettings().selfTimerSeconds)
        assertTrue(SubjectDisplaySettings().selfMinimalControls)
        assertTrue(runCatching { SubjectDisplaySettings(selfTimerSeconds = 2) }.isFailure)
    }
}
