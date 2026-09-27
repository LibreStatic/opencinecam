/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureRecoveryTest {
    private val working = CameraSettings(videoWidth = 1920, videoHeight = 1080, videoFps = 30)
    private val broken = working.copy(videoWidth = 3840, videoHeight = 2160, videoFps = 120)

    @Test fun restoringReturnsTheGeometryThatLastPreviewed() {
        val snapshot = KnownGoodCapture.of(working, CaptureMode.VIDEO, "0")
        assertTrue(snapshot.differsFrom(broken, CaptureMode.VIDEO))
        val restored = snapshot.applyTo(broken)
        assertEquals(1920, restored.videoWidth)
        assertEquals(1080, restored.videoHeight)
        assertEquals(30, restored.videoFps)
        assertFalse(snapshot.differsFrom(restored, CaptureMode.VIDEO))
    }

    @Test fun aDifferentModeAloneIsWorthRestoring() {
        val snapshot = KnownGoodCapture.of(working, CaptureMode.VIDEO, "0")
        assertTrue(snapshot.differsFrom(working, CaptureMode.LOG))
    }

    @Test fun restoringKeepsEveryNonGeometryPreference() {
        val tuned = broken.copy(audioEnabled = !broken.audioEnabled, zebraEnabled = !broken.zebraEnabled)
        val restored = KnownGoodCapture.of(working, CaptureMode.VIDEO, null).applyTo(tuned)
        assertEquals(tuned.audioEnabled, restored.audioEnabled)
        assertEquals(tuned.zebraEnabled, restored.zebraEnabled)
    }

    @Test fun snapshotSurvivesPersistence() {
        val snapshot = KnownGoodCapture.of(working.copy(logFps = 24), CaptureMode.LOG, "2")
        assertEquals(snapshot, KnownGoodCapture.decode(snapshot.encode()))
        val noCamera = KnownGoodCapture.of(working, CaptureMode.PHOTO, null)
        assertEquals(noCamera, KnownGoodCapture.decode(noCamera.encode()))
    }

    @Test fun corruptOrForeignValuesAreIgnored() {
        listOf(null, "", "VIDEO|0|1920", "NOPE|0|1|1|1|1|1|1|1|1", "VIDEO|0|x|1|1|1|1|1|1|1", "VIDEO|0|0|1|1|1|1|1|1|1")
            .forEach { assertNull(it, KnownGoodCapture.decode(it)) }
    }

    @Test fun safeDefaultNeverLandsInAnExperimentalMode() {
        assertEquals(CaptureMode.VIDEO, KnownGoodCapture.safeDefault(CaptureMode.LOG).mode)
        assertEquals(CaptureMode.PHOTO, KnownGoodCapture.safeDefault(CaptureMode.PHOTO).mode)
        assertEquals(1920, KnownGoodCapture.safeDefault(CaptureMode.VIDEO).videoWidth)
    }

    @Test fun geometryFollowsTheModeThatOwnsIt() {
        val snapshot = KnownGoodCapture.of(working.copy(logWidth = 3840, logHeight = 2160, logFps = 24), CaptureMode.LOG, null)
        assertEquals(Triple(3840, 2160, 24), snapshot.geometry())
        assertEquals(Triple(1920, 1080, 30), snapshot.geometry(CaptureMode.VIDEO))
        assertNull(snapshot.geometry(CaptureMode.PHOTO))
    }
}
