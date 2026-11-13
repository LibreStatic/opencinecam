/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureSlotsTest {

    @Test fun everyModeHasSixSlotsFromResolutionToWhiteBalanceAndFocus() {
        CaptureMode.entries.forEach { mode ->
            val slots = captureSlots(mode)
            assertEquals("$mode", 6, slots.size)
            assertEquals("$mode", CaptureSlot.RESOLUTION, slots.first())
            assertEquals("$mode", listOf(CaptureSlot.WB, CaptureSlot.FOCUS), slots.takeLast(2))
            assertEquals("$mode", slots.size, slots.toSet().size)
            // High-speed slow motion gives FOCUS, which that session holds, to the take length.
            val highSpeed = captureSlots(mode, highSpeed = true)
            assertEquals("$mode", 6, highSpeed.size)
            assertEquals("$mode", highSpeed.size, highSpeed.toSet().size)
            if (mode == CaptureMode.SLOW_MOTION) assertEquals("$mode", CaptureSlot.WB, highSpeed.last()) else assertEquals("$mode", slots, highSpeed)
        }
    }

    @Test fun videoModesLeadWithTheFrameRate() {
        val video = listOf(CaptureSlot.RESOLUTION, CaptureSlot.FPS, CaptureSlot.SHUTTER, CaptureSlot.ISO, CaptureSlot.WB, CaptureSlot.FOCUS)
        assertEquals(video, captureSlots(CaptureMode.VIDEO))
        assertEquals(video, captureSlots(CaptureMode.LOG))
        assertEquals(video, captureSlots(CaptureMode.RAW_VIDEO))
    }

    @Test fun highSpeedSlowMotionSwapsFocusForTheTakeLength() {
        assertEquals(
            listOf(CaptureSlot.RESOLUTION, CaptureSlot.FPS, CaptureSlot.LENGTH, CaptureSlot.SHUTTER, CaptureSlot.ISO, CaptureSlot.WB),
            captureSlots(CaptureMode.SLOW_MOTION, highSpeed = true),
        )
        // A regular session can pull focus, so its slow motion keeps the video row.
        assertEquals(captureSlots(CaptureMode.VIDEO), captureSlots(CaptureMode.SLOW_MOTION, highSpeed = false))
        CaptureMode.entries.filter { it != CaptureMode.SLOW_MOTION }.forEach {
            assertFalse("$it", CaptureSlot.LENGTH in captureSlots(it, highSpeed = true))
        }
    }

    @Test fun timeLapseSwapsTheFrameRateForTheInterval() {
        assertEquals(
            listOf(CaptureSlot.RESOLUTION, CaptureSlot.INTERVAL, CaptureSlot.SHUTTER, CaptureSlot.ISO, CaptureSlot.WB, CaptureSlot.FOCUS),
            captureSlots(CaptureMode.TIME_LAPSE),
        )
    }

    @Test fun stillModesCarryExposureCompensation() {
        val stills = listOf(CaptureSlot.RESOLUTION, CaptureSlot.SHUTTER, CaptureSlot.ISO, CaptureSlot.EV, CaptureSlot.WB, CaptureSlot.FOCUS)
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
        resolutions: Int = 4,
    ) = captureSlotUnavailableReason(slot, ready, highSpeed, manualIso, manualShutter, ev, resolutions)

    @Test fun everySlotWaitsForTheCamera() {
        CaptureSlot.entries.forEach { assertEquals("$it", SlotUnavailableReason.NOT_READY, reason(it, ready = false, highSpeed = true)) }
    }

    @Test fun aHighSpeedSessionLeavesOnlyTheSizeAndRateAdjustable() {
        assertNull(reason(CaptureSlot.RESOLUTION, highSpeed = true))
        assertNull(reason(CaptureSlot.FPS, highSpeed = true))
        assertNull(reason(CaptureSlot.INTERVAL, highSpeed = true))
        assertNull(reason(CaptureSlot.LENGTH, highSpeed = true))
        assertEquals(SlotUnavailableReason.NOT_READY, reason(CaptureSlot.LENGTH, ready = false))
        listOf(CaptureSlot.SHUTTER, CaptureSlot.ISO, CaptureSlot.EV, CaptureSlot.WB, CaptureSlot.FOCUS).forEach {
            assertEquals("$it", SlotUnavailableReason.HIGH_SPEED, reason(it, highSpeed = true))
        }
    }

    @Test fun aSingleResolutionIsAPlaceholder() {
        assertEquals(SlotUnavailableReason.SINGLE_RESOLUTION, reason(CaptureSlot.RESOLUTION, resolutions = 1))
        assertEquals(SlotUnavailableReason.SINGLE_RESOLUTION, reason(CaptureSlot.RESOLUTION, resolutions = 0))
        assertNull(reason(CaptureSlot.RESOLUTION, resolutions = 2))
        assertNull(reason(CaptureSlot.FPS, resolutions = 1))
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
