/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.librestatic.opencinecam.camera.EqCurve
import com.librestatic.opencinecam.camera.EqFilterType
import com.librestatic.opencinecam.camera.EqPoint
import com.librestatic.opencinecam.camera.PcmInputEvidence
import com.librestatic.opencinecam.camera.decodeEqCurve
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AudioInputMetadataTest {
    private val curve = EqCurve(points = listOf(EqPoint(EqFilterType.BELL, 8_100.0, -9.5, 1.8)))

    @Test
    fun anAudioSidecarNamesTheEqualiserProfileOnlyWhenItHasOne() {
        val named = JSONObject().putPcmInputEvidence(
            PcmInputEvidence("LOW_LATENCY", "AAUDIO_LOW_LATENCY", null, 16, curve, "bundled-motorola-blanc"))
        assertEquals("bundled-motorola-blanc", named.getString("equaliserProfileId"))
        assertEquals(curve, decodeEqCurve(named.getJSONObject("equaliser").toString()))

        val custom = JSONObject().putPcmInputEvidence(PcmInputEvidence("CAMCORDER", "AUDIO_RECORD", null, 16, curve))
        assertFalse(custom.has("equaliserProfileId"))
        val flat = JSONObject().putPcmInputEvidence(PcmInputEvidence("CAMCORDER", "AUDIO_RECORD", null, 16, null))
        assertTrue(flat.isNull("equaliser"))
        assertFalse(flat.has("equaliserProfileId"))
        // Unknown evidence adds nothing.
        assertEquals(0, JSONObject().putPcmInputEvidence(null).length())
    }
}
