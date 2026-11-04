/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import com.librestatic.opencinecam.core.model.FailureCode
import com.librestatic.opencinecam.service.RecordingPermissionState
import com.librestatic.opencinecam.service.validateRecordingPermissions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingForegroundControllerTest {
    private val text: (Int) -> String = { "text-$it" }

    @Test
    fun cameraAndAudioPermissionStatesGateVisibleRecording() {
        val camera = validateRecordingPermissions(RecordingPermissionState(false, true, true), true, text = text)
        assertEquals(FailureCode.CAPTURE_OPEN_FAILED, camera?.code)
        assertEquals(text(R.string.recording_camera_permission_required), camera?.userMessage)
        val microphone = validateRecordingPermissions(RecordingPermissionState(true, false, true), true, text = text)
        assertEquals(FailureCode.AUDIO_INITIALIZATION_FAILED, microphone?.code)
        assertEquals(text(R.string.recording_microphone_permission_required), microphone?.userMessage)
        assertNull(validateRecordingPermissions(RecordingPermissionState(true, false, false), false, text = text))
    }
}
