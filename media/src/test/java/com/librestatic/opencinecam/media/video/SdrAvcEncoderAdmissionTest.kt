/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.media.video

import com.librestatic.opencinecam.core.model.PolicyMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SdrAvcEncoderAdmissionTest {
    private val hardware = SurfaceEncoderPick("c2.vendor.avc.encoder", 8, true, 512)
    private val software = SurfaceEncoderPick("c2.android.avc.encoder", 8, false, 512)

    @Test
    fun hardwareIsAlwaysAdmittedWithoutDisclosure() {
        for (policy in PolicyMode.entries) for (kind in SdrAvcCaptureKind.entries) {
            assertEquals(SdrAvcEncoderDecision.Hardware(hardware), decideSdrAvcEncoder(hardware, policy, kind))
        }
    }

    @Test
    fun strictRefusesSoftwareForVideoWithTheStableCode() {
        val decision = decideSdrAvcEncoder(software, PolicyMode.STRICT, SdrAvcCaptureKind.VIDEO)
        assertEquals(SdrAvcEncoderDecision.Refused("video-hardware-encoder-unavailable", "c2.android.avc.encoder"), decision)
    }

    @Test
    fun adaptiveRecordsWithSoftwareForVideoAndDiscloses() {
        assertEquals(SdrAvcEncoderDecision.Software(software, disclose = true),
            decideSdrAvcEncoder(software, PolicyMode.ADAPTIVE, SdrAvcCaptureKind.VIDEO))
    }

    @Test
    fun timelapseKeepsAcceptingSoftwareInBothPolicies() {
        for (policy in PolicyMode.entries) {
            assertEquals(SdrAvcEncoderDecision.Software(software, disclose = false),
                decideSdrAvcEncoder(software, policy, SdrAvcCaptureKind.TIMELAPSE))
        }
    }

    @Test
    fun noPickIsUnavailable() {
        for (policy in PolicyMode.entries) for (kind in SdrAvcCaptureKind.entries) {
            assertTrue(decideSdrAvcEncoder(null, policy, kind) is SdrAvcEncoderDecision.Unavailable)
        }
    }

    @Test
    fun disclosureNamesTheCodec() {
        assertEquals("Software video encoder: c2.android.avc.encoder; frames may drop", softwareVideoEncoderDisclosure("c2.android.avc.encoder"))
    }
}
