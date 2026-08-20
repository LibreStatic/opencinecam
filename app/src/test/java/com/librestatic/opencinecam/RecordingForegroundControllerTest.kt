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
    @Test
    fun cameraAndAudioPermissionStatesGateVisibleRecording() {
        assertEquals(FailureCode.CAPTURE_OPEN_FAILED, validateRecordingPermissions(RecordingPermissionState(false, true, true), true)?.code)
        assertEquals(FailureCode.AUDIO_INITIALIZATION_FAILED, validateRecordingPermissions(RecordingPermissionState(true, false, true), true)?.code)
        assertNull(validateRecordingPermissions(RecordingPermissionState(true, false, false), false))
    }
}
