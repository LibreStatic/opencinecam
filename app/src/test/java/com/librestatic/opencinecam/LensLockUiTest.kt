/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import com.librestatic.opencinecam.camera.ZoomAnchor
import com.librestatic.opencinecam.ui.viewfinder.zoomAnchorLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LensLockUiTest {
    private val ultraWide = LensLockOption("3", 0.55f, 2.2f)
    private val wide = LensLockOption("2", 1.0f, 6.9f)
    private val tele = LensLockOption("5", 3.0f, 20.6f)
    private val options = listOf(ultraWide, wide, tele)

    private fun state(zoom: Float = 1f, activeLens: String? = null, locked: String? = null, phase: CameraUiPhase = CameraUiPhase.PREVIEWING) =
        CameraUiState(
            phase = phase,
            zoomRatio = zoom,
            lensLock = LensLockUiState(options, locked),
            hardwareTruth = HardwareTruthHudState().withActiveLens(activeLens),
        )

    @Test fun namesEachLensByItsAnchor() {
        assertEquals(LensLockKind.ULTRA_WIDE, lensLockKind(0.55f))
        assertEquals(LensLockKind.ULTRA_WIDE, lensLockKind(0.89f))
        assertEquals(LensLockKind.WIDE, lensLockKind(0.9f))
        assertEquals(LensLockKind.WIDE, lensLockKind(1.0f))
        assertEquals(LensLockKind.WIDE, lensLockKind(1.5f))
        assertEquals(LensLockKind.TELE, lensLockKind(1.51f))
        assertEquals(LensLockKind.TELE, lensLockKind(3.0f))
    }

    @Test fun settingsRatioTextDropsTrailingZeros() {
        assertEquals("0.55×", lensLockRatioText(0.55f))
        assertEquals("1×", lensLockRatioText(1.0f))
        assertEquals("3×", lensLockRatioText(3.0f))
        assertEquals("2.5×", lensLockRatioText(2.5f))
        assertEquals("0.6×", lensLockRatioText(0.6f))
    }

    @Test fun anchorBarLabelsMatchTheFreeBar() {
        assertEquals("0.6", zoomAnchorLabel(0.55f))
        assertEquals("1.0", zoomAnchorLabel(1.0f))
        assertEquals("3.0", zoomAnchorLabel(3.0f))
    }

    @Test fun togglePrefersTheActiveLens() {
        assertEquals("5", lensLockToggleTarget(state(zoom = 1f, activeLens = "5")))
        assertEquals("3", lensLockToggleTarget(state(zoom = 2f, activeLens = "3")))
    }

    @Test fun toggleFallsBackToTheLongestReachedAnchorThenTheWidest() {
        // An active lens that is not an option (e.g. a hidden fourth sensor) is ignored.
        assertEquals("2", lensLockToggleTarget(state(zoom = 2.9f, activeLens = "9")))
        assertEquals("5", lensLockToggleTarget(state(zoom = 3.0f)))
        assertEquals("5", lensLockToggleTarget(state(zoom = 10f)))
        assertEquals("3", lensLockToggleTarget(state(zoom = 0.7f)))
        assertEquals("3", lensLockToggleTarget(state(zoom = 0.5f)))
        assertNull(lensLockToggleTarget(CameraUiState()))
    }

    @Test fun keyNeedsTwoLenses() {
        assertTrue(lensLockKeyVisible(state()))
        assertFalse(lensLockKeyVisible(CameraUiState(lensLock = LensLockUiState(listOf(wide)))))
        assertFalse(lensLockKeyVisible(CameraUiState()))
    }

    @Test fun changeRefusedWhileRecordingOrCalibrating() {
        assertTrue(lensLockChangeAllowed(state()))
        assertFalse(lensLockChangeAllowed(state(phase = CameraUiPhase.RECORDING)))
        assertFalse(lensLockChangeAllowed(state().copy(lensCalibrationRunning = true)))
        assertFalse(lensLockChangeAllowed(state().copy(stillCapturePending = true)))
    }

    @Test fun lockedZoomHasNoSectors() {
        val anchors = options.map { ZoomAnchor(it.anchorRatio, it.focalLengthMm, it.physicalId) }
        assertEquals(anchors, zoomSectorAnchors(state().copy(opticalAnchors = anchors)))
        assertTrue(zoomSectorAnchors(state(locked = "2").copy(opticalAnchors = anchors)).isEmpty())
    }

    @Test fun noticeNamesTheCropRouteAndTheLensSwitch() {
        assertNull(lensLockNoticeText(LensLockUiState(options)))
        assertNull(lensLockNoticeText(LensLockUiState(options, "5", LensLockRoute.DIRECT_PHYSICAL)))
        assertEquals(R.string.lens_lock_notice_crop, lensLockNoticeText(LensLockUiState(options, "5", LensLockRoute.GPU_CROP)))
        assertEquals(R.string.lens_lock_notice_switched,
            lensLockNoticeText(LensLockUiState(options, "5", LensLockRoute.GPU_CROP, lensSwitchedWarning = true)))
    }

    @Test fun catalogFindsTheRowUnderCapture() {
        listOf("lens lock", "zoom", "digital zoom", "switch lens").forEach { query ->
            assertTrue(query, "lens-lock" in SettingsCatalog.search(query, null) { "" })
        }
        assertTrue("lens-lock" in SettingsCatalog.search("", SettingsCategory.CAPTURE) { "" })
    }
}
