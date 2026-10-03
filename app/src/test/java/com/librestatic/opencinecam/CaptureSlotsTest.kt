/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureSlotsTest {

    @Test fun everyModeHasFiveSlotsEndingInWhiteBalanceAndFocus() {
        CaptureMode.entries.forEach { mode ->
            val slots = captureSlots(mode)
            assertEquals("$mode", 5, slots.size)
            assertEquals("$mode", listOf(CaptureSlot.WB, CaptureSlot.FOCUS), slots.takeLast(2))
            assertEquals("$mode", slots.size, slots.toSet().size)
        }
    }

    @Test fun videoModesLeadWithTheFrameRate() {
        val video = listOf(CaptureSlot.FPS, CaptureSlot.SHUTTER, CaptureSlot.ISO, CaptureSlot.WB, CaptureSlot.FOCUS)
        assertEquals(video, captureSlots(CaptureMode.VIDEO))
        assertEquals(video, captureSlots(CaptureMode.LOG))
        assertEquals(video, captureSlots(CaptureMode.SLOW_MOTION))
    }

    @Test fun timeLapseSwapsTheFrameRateForTheInterval() {
        assertEquals(
            listOf(CaptureSlot.INTERVAL, CaptureSlot.SHUTTER, CaptureSlot.ISO, CaptureSlot.WB, CaptureSlot.FOCUS),
            captureSlots(CaptureMode.TIME_LAPSE),
        )
    }

    @Test fun stillModesCarryExposureCompensation() {
        val stills = listOf(CaptureSlot.SHUTTER, CaptureSlot.ISO, CaptureSlot.EV, CaptureSlot.WB, CaptureSlot.FOCUS)
        listOf(CaptureMode.PHOTO, CaptureMode.RAW_PHOTO, CaptureMode.BURST, CaptureMode.BRACKET, CaptureMode.LIGHT_TRAIL).forEach {
            assertEquals("$it", stills, captureSlots(it))
        }
    }

    private fun reason(
        slot: CaptureSlot,
        ready: Boolean = true,
        highSpeed: Boolean = false,
        manualIso: Boolean = true,
        manualShutter: Boolean = true,
        ev: Boolean = true,
    ) = captureSlotUnavailableReason(slot, ready, highSpeed, manualIso, manualShutter, ev)

    @Test fun everySlotWaitsForTheCamera() {
        CaptureSlot.entries.forEach { assertEquals("$it", SlotUnavailableReason.NOT_READY, reason(it, ready = false, highSpeed = true)) }
    }

    @Test fun aHighSpeedSessionLeavesOnlyTheRateAdjustable() {
        assertNull(reason(CaptureSlot.FPS, highSpeed = true))
        assertNull(reason(CaptureSlot.INTERVAL, highSpeed = true))
        listOf(CaptureSlot.SHUTTER, CaptureSlot.ISO, CaptureSlot.EV, CaptureSlot.WB, CaptureSlot.FOCUS).forEach {
            assertEquals("$it", SlotUnavailableReason.HIGH_SPEED, reason(it, highSpeed = true))
        }
    }

    @Test fun exposureSlotsNeedTheMatchingManualControl() {
        assertEquals(SlotUnavailableReason.NO_MANUAL_EXPOSURE, reason(CaptureSlot.ISO, manualIso = false))
        assertNull(reason(CaptureSlot.SHUTTER, manualIso = false))
        assertEquals(SlotUnavailableReason.NO_MANUAL_EXPOSURE, reason(CaptureSlot.SHUTTER, manualShutter = false))
        assertNull(reason(CaptureSlot.ISO, manualShutter = false))
        assertEquals(SlotUnavailableReason.NO_EV, reason(CaptureSlot.EV, ev = false))
        assertNull(reason(CaptureSlot.WB, manualIso = false, manualShutter = false, ev = false))
        assertNull(reason(CaptureSlot.FOCUS, manualIso = false, manualShutter = false, ev = false))
    }

    @Test fun focusReadsDioptresThenDistance() {
        assertEquals("2.0 D", formatFocusDiopters(2f))
        assertEquals("0.50 m", formatFocusDistance(2f))
        assertEquals("2.0 D · 0.50 m", formatFocus(2f))
        assertEquals("10.0 D · 0.10 m", formatFocus(10f))
    }

    @Test fun focusAtInfinityIsOnlyTheSymbol() {
        assertEquals("∞", formatFocusDiopters(0f))
        assertNull(formatFocusDistance(0f))
        assertEquals("∞", formatFocus(0f))
    }

    @Test fun captureControlFollowsThePhase() {
        assertTrue(captureControlEnabled(CameraUiState(phase = CameraUiPhase.PREVIEWING)))
        assertTrue(captureControlEnabled(CameraUiState(phase = CameraUiPhase.SAVED)))
        assertTrue(captureControlEnabled(CameraUiState(phase = CameraUiPhase.RECORDING)))
        assertFalse(captureControlEnabled(CameraUiState(phase = CameraUiPhase.RECORDING, recordingFinalizing = true)))
        assertFalse(captureControlEnabled(CameraUiState(phase = CameraUiPhase.PREPARING)))
        assertFalse(captureControlEnabled(CameraUiState(phase = CameraUiPhase.CAPTURING)))
        // A pending capture can still be cancelled with the same control.
        assertTrue(captureControlEnabled(CameraUiState(phase = CameraUiPhase.CAPTURING, audioRetirementPending = true)))
    }
}
