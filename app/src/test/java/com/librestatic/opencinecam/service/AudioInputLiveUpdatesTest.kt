/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.service

import com.librestatic.opencinecam.CameraSettings
import com.librestatic.opencinecam.camera.EqCurve
import com.librestatic.opencinecam.camera.EqFilterType
import com.librestatic.opencinecam.camera.EqPoint
import com.librestatic.opencinecam.media.audio.AudioSourceSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioInputLiveUpdatesTest {
    private val base = CameraSettings()
    private val curve = EqCurve(listOf(EqPoint(EqFilterType.BELL, 1_000.0, -6.0, 1.0)))

    @Test fun displayOnlyChangesLeaveTheMonitorAlone() {
        assertEquals(PreviewAudioUpdate.NONE, previewAudioUpdate(base, base))
        assertEquals(PreviewAudioUpdate.NONE, previewAudioUpdate(base,
            base.copy(audioMeter = base.audioMeter.copy(visible = !base.audioMeter.visible))))
    }

    @Test fun anEqualiserChangeAloneUpdatesTheRunningMonitor() {
        assertEquals(PreviewAudioUpdate.EQ_CURVE, previewAudioUpdate(base, base.copy(audioEqCurve = curve)))
        assertEquals(PreviewAudioUpdate.EQ_CURVE, previewAudioUpdate(base, base.copy(audioEqCurve = curve, audioEqProfileId = "my-voice")))
        // With a display change on the side it is still only the curve.
        assertEquals(PreviewAudioUpdate.EQ_CURVE, previewAudioUpdate(base, base.copy(audioEqCurve = curve,
            audioMeter = base.audioMeter.copy(visible = !base.audioMeter.visible))))
    }

    @Test fun anyOtherAudioChangeRestartsTheMonitor() {
        val other = AudioSourceSelection.entries.first { it != base.audioSource }
        assertEquals(PreviewAudioUpdate.RESTART, previewAudioUpdate(base, base.copy(audioSource = other)))
        assertEquals(PreviewAudioUpdate.RESTART, previewAudioUpdate(base, base.copy(audioSource = other, audioEqCurve = curve)))
        assertEquals(PreviewAudioUpdate.RESTART, previewAudioUpdate(base, base.copy(audioChannels = 3 - base.audioChannels)))
    }

    @Test fun overloadShowsAtOnceAndHoldsFiveSecondsAfterTheLastFlag() {
        val hold = AudioOverloadHold()
        assertFalse(hold.observe(false, 1_000L))
        assertNull(hold.clearsInMs(1_000L))
        assertTrue(hold.observe(true, 2_000L))
        assertEquals(AUDIO_OVERLOAD_HOLD_MS, hold.clearsInMs(2_000L))
        assertTrue(hold.observe(false, 6_999L))
        // A later flag extends the hold.
        assertTrue(hold.observe(true, 6_000L))
        assertTrue(hold.observe(false, 10_999L))
        assertEquals(1L, hold.clearsInMs(10_999L))
        assertFalse(hold.observe(false, 11_000L))
        assertNull(hold.clearsInMs(11_000L))
    }

    @Test fun overloadNeverClearsWhileStillFlaggedAndResets() {
        val hold = AudioOverloadHold(holdMs = 100L)
        for (t in 0L..1_000L step 66L) assertTrue(hold.observe(true, t))
        hold.reset()
        assertFalse(hold.shown(1_000L))
        assertNull(hold.clearsInMs(1_000L))
    }

    @Test fun aClockThatGoesBackKeepsTheNoticeWithinTheHold() {
        val hold = AudioOverloadHold()
        hold.observe(true, 10_000L)
        assertTrue(hold.shown(9_000L))
        assertEquals(AUDIO_OVERLOAD_HOLD_MS, hold.clearsInMs(9_000L))
    }
}
