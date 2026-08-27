/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import android.util.Size
import com.librestatic.opencinecam.camera.Camera2LogProfile
import com.librestatic.opencinecam.camera.OpenCineLogQualificationStage
import com.librestatic.opencinecam.camera.OpenCineLogSourcePath
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenCineLogUiClaimTest {
    @Test
    fun missingAndExperimentalEvidenceNeverRenderAsVerified() {
        assertEquals("EXPERIMENTAL", ocLogQualificationLabel(null))
        assertEquals("EXPERIMENTAL", ocLogQualificationLabel(profile()))
    }

    @Test
    fun exactAcceptedEvidenceRendersAsVerified() {
        assertEquals(
            "VERIFIED",
            ocLogQualificationLabel(profile(
                stage = OpenCineLogQualificationStage.VERIFIED,
                evidenceId = "plan-061/device-0/1920x1080-30-hlg10",
            )),
        )
    }

    private fun profile(
        stage: OpenCineLogQualificationStage = OpenCineLogQualificationStage.EXPERIMENTAL,
        evidenceId: String? = null,
    ) = Camera2LogProfile(
        size = Size(1920, 1080),
        fps = 30,
        sourcePath = OpenCineLogSourcePath.HLG10_BT2020,
        constrainedHighSpeed = false,
        qualificationStage = stage,
        qualificationEvidenceId = evidenceId,
    )
}
