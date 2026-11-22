/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.capture

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.librestatic.opencinecam.CameraUiPhase
import com.librestatic.opencinecam.CameraUiState
import com.librestatic.opencinecam.CaptureMode
import com.librestatic.opencinecam.ModeGateState
import com.librestatic.opencinecam.rawvideo.RawVideoCrop
import com.librestatic.opencinecam.storage.RawSavedCrop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PLAN-076 delivery 3b: the crop editor's start, the saved crops' rate choice and the corner grab. */
class RawCropEditorLogicTest {
    private val saved = RawSavedCrop("a", "Slow-mo 240p", 4080, 3060, 1828, 1410, 428, 240)

    private fun state(selected: String?, crop: RawVideoCropUi?, rates: List<Int> = listOf(15, 24, 30, 60), phase: CameraUiPhase = CameraUiPhase.PREVIEWING,
        cameraId: String = "0") =
        CameraUiState(
            selectedCameraId = cameraId,
            selectedMode = CaptureMode.RAW_VIDEO,
            phase = phase,
            modeGates = CameraUiState().modeGates + (CaptureMode.RAW_VIDEO to ModeGateState.AVAILABLE),
            rawVideoSelectedKey = selected,
            rawVideoCrop = crop,
            rawVideoCustomRates = rates,
            rawVideoOptions = listOf(RawVideoConfigOption("full@30", "4080×3060 · 30 fps", 468_180_000L, isCrop = false)),
        )

    @Test fun theEditorStartsOnTheSelectedWindowAndRate() {
        val edit = rawCropEditStateFor(state("1920x816+200+300@24", RawVideoCropUi(4080, 3060, 200, 300, 1920, 816)))!!
        assertEquals(RawVideoCrop(200, 300, 1920, 816), edit.window)
        assertEquals(24, edit.fps)
        assertFalse(edit.compressed)
        assertEquals("1920x816+200+300@24", edit.key)
        // New crop starts again from the default window, at the selected rate.
        val fresh = rawCropEditStateFor(state("1920x816+200+300@24", RawVideoCropUi(4080, 3060, 200, 300, 1920, 816)), fresh = true)!!
        assertEquals(rawCropDefaultWindow(4080, 3060), fresh.window)
        assertEquals(24, fresh.fps)
    }

    @Test fun onTheFullSensorTheEditorStartsOnAHalfWidthSixteenNineWindow() {
        val edit = rawCropEditStateFor(state("full@30", RawVideoCropUi(4080, 3060, 0, 0, 4080, 3060)))!!
        assertEquals(rawCropDefaultWindow(4080, 3060), edit.window)
        assertEquals(30, edit.fps)
    }

    @Test fun theEditorDoesNotOpenDuringATakeOrWithoutAWindow() {
        assertNull(rawCropEditStateFor(state("full@30", RawVideoCropUi(4080, 3060, 0, 0, 4080, 3060), phase = CameraUiPhase.CAPTURING)))
        assertNull(rawCropEditStateFor(state(null, null)))
    }

    @Test fun theEditorClosesWhenAnotherCameraWithTheSameOutputIsSelected() {
        val crop = RawVideoCropUi(4080, 3060, 200, 300, 1920, 816)
        val edit = rawCropEditStateFor(state("1920x816+200+300@24", crop))!!
        assertEquals("0", edit.cameraId)
        assertTrue(rawCropEditorKeeps(state("1920x816+200+300@24", crop), edit, paneOpen = true))
        assertFalse(rawCropEditorKeeps(state("1920x816+200+300@24", crop, cameraId = "2"), edit, paneOpen = true))
        assertFalse(rawCropEditorKeeps(state("1920x816+200+300@24", crop), edit, paneOpen = false))
    }

    @Test fun anAspectLockReshapesTheWindow() {
        val edit = RawCropEditState(4080, 3060, RawVideoCrop(200, 300, 1920, 816), 24, false, "0")
        edit.chooseAspect(RawCropAspect.WIDE)
        assertEquals(1920, edit.window.width)
        assertEquals(1080, edit.window.height)
        edit.chooseAspect(RawCropAspect.FREE)
        assertEquals(1080, edit.window.height)
    }

    @Test fun ratesIncludeTheCurrentOneInOrder() {
        assertEquals(listOf(15, 24, 25, 30), rawCropEditorRates(listOf(30, 15, 24), 25))
        assertEquals(listOf(24, 30), rawCropEditorRates(listOf(24, 30), 24))
    }

    @Test fun savedCropCandidatesTryTheCurrentRateThenTheHighestThenUncompressed() {
        assertEquals(listOf(24 to false, 60 to false, 30 to false, 15 to false), rawSavedCropCandidates(24, false, listOf(15, 30, 60)))
        assertEquals(listOf(30 to true, 60 to true, 60 to false, 30 to false), rawSavedCropCandidates(30, true, listOf(30, 60)))
        assertEquals(listOf(60 to false, 30 to false), rawSavedCropCandidates(null, false, listOf(30, 60)))
    }

    private fun preview(key: String, status: RawVideoCropStatus, reason: RawVideoLeftOutReason? = null) =
        RawVideoCropPreview(key, 1L, 1L, status, refusedReason = reason)

    @Test fun aSavedCropRecordsAtTheFirstRateTheGateAccepts() {
        val candidates = rawSavedCropCandidates(240, false, listOf(30, 60, 120, 240))
        val choice = rawSavedCropChoice(saved, candidates) { key ->
            if (key.contains("@240")) preview(key, RawVideoCropStatus.REFUSED, RawVideoLeftOutReason.CAMERA_LIMIT)
            else preview(key, RawVideoCropStatus.PASSED)
        }!!
        assertEquals(120, choice.fps)
        assertEquals("428x240+1828+1410@120", choice.key)
        assertFalse(choice.refused)
    }

    @Test fun aSavedCropEveryRateRefusesIsRefusedWithTheFirstReason() {
        val choice = rawSavedCropChoice(saved, rawSavedCropCandidates(24, false, listOf(24, 30))) { key ->
            preview(key, RawVideoCropStatus.REFUSED, if (key.contains("@24")) RawVideoLeftOutReason.HEAT else RawVideoLeftOutReason.STORAGE)
        }!!
        assertTrue(choice.refused)
        assertEquals(24, choice.fps)
        assertEquals(RawVideoLeftOutReason.HEAT, choice.preview?.refusedReason)
    }

    @Test fun aSavedCropTheGateCannotJudgeTakesTheFirstRate() {
        val choice = rawSavedCropChoice(saved, rawSavedCropCandidates(24, false, listOf(24, 30))) { null }!!
        assertEquals(24, choice.fps)
        assertFalse(choice.refused)
        assertNull(rawSavedCropChoice(saved, emptyList()) { null })
    }

    @Test fun theSelectedSavedCropMatchesByWindowOnly() {
        val ui = RawVideoCropUi(4080, 3060, 1828, 1410, 428, 240)
        assertTrue(saved.matches(ui))
        assertFalse(saved.matches(ui.copy(left = 1832)))
        val selected = state("428x240+1828+1410@60", ui).copy(rawVideoSavedCrops = listOf(saved))
        assertEquals(saved, rawSavedCropSelected(selected))
    }

    @Test fun aTouchNearACornerGrabsItUnlessItIsWellInside() {
        val rect = Rect(100f, 100f, 500f, 300f)
        assertEquals(RawCropCorner.TOP_LEFT, rawCropGrabbedCorner(rect, Offset(90f, 90f), 80f))
        assertEquals(RawCropCorner.BOTTOM_RIGHT, rawCropGrabbedCorner(rect, Offset(510f, 310f), 80f))
        assertNull(rawCropGrabbedCorner(rect, Offset(300f, 200f), 80f))
        // Inside but beyond the corner zone (30 % of the short side, 60 px here) moves the window.
        assertNull(rawCropGrabbedCorner(rect, Offset(160f, 160f), 100f))
        assertEquals(RawCropCorner.TOP_LEFT, rawCropGrabbedCorner(rect, Offset(120f, 120f), 100f))
    }
}
