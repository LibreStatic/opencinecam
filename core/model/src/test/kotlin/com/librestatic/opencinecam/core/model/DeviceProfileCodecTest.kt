/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceProfileCodecTest {
    private fun document(
        device: String = "blanc",
        quirks: String = """[{"quirkId":"OCC-QUIRK-0001","effect":"high-speed-prefer-physical-camera","enabled":true}]""",
        schemaVersion: String = "1.0.0",
        extra: String = "",
    ) = """
        {
          "schemaVersion": "$schemaVersion",
          "profileId": "5d2b1f60-3c1e-4a8f-9a1b-0d6f6f0c7e11",
          "createdAt": "2026-10-19T00:00:00Z",
          "source": "release",
          "trust": "BUNDLED_TRUSTED",
          "scope": {"device": "$device", "buildFingerprint": "fp", "cameraId": "0"},
          "validity": {"protocolVersion": "1"},
          "configurations": [],
          "quirks": $quirks
          $extra
        }
    """.trimIndent()

    @Test
    fun decodesTheModelFieldsAndIgnoresTheRest() {
        val decoded = DeviceProfileCodec.decode(document(extra = ""","futureField": 1""")) as DeviceProfileDecoding.Decoded
        val profile = decoded.profile
        assertEquals("blanc", profile.scope.device)
        assertEquals("0", profile.scope.cameraId)
        assertNull(profile.scope.physicalCameraId)
        assertNull(profile.scope.characteristicsDigest)
        assertEquals(ProfileTrust.BUNDLED_TRUSTED, profile.trust)
        assertEquals(QuirkEffect.HIGH_SPEED_PREFER_PHYSICAL_CAMERA, profile.quirks.single().knownEffect)
        assertNull(profile.signature)
    }

    @Test
    fun decodesTheSignatureBlock() {
        val signed = document(extra = ""","signature":{"algorithm":"SHA-256","keyId":"release","digest":"${"a".repeat(64)}"}""")
        val profile = (DeviceProfileCodec.decode(signed) as DeviceProfileDecoding.Decoded).profile
        assertEquals("release", profile.signature?.keyId)
    }

    @Test
    fun aBlankDeviceCodenameIsInvalid() {
        val invalid = DeviceProfileCodec.decode(document(device = "")) as DeviceProfileDecoding.Invalid
        assertTrue(invalid.reason.contains("codename"))
    }

    @Test
    fun malformedUnsupportedAndIncompleteDocumentsAreInvalid() {
        assertTrue(DeviceProfileCodec.decode("{") is DeviceProfileDecoding.Invalid)
        assertTrue(DeviceProfileCodec.decode("[]") is DeviceProfileDecoding.Invalid)
        assertTrue(DeviceProfileCodec.decode(document(schemaVersion = "2.0.0")) is DeviceProfileDecoding.Invalid)
        assertTrue(DeviceProfileCodec.decode("""{"schemaVersion":"1.0.0"}""") is DeviceProfileDecoding.Invalid)
        assertTrue(DeviceProfileCodec.decode(document(quirks = """[{"quirkId":"q","effect":"x"}]""")) is DeviceProfileDecoding.Invalid)
    }

    @Test
    fun anUnknownEffectDecodesAndIsLeftToThePolicyToIgnore() {
        val profile = (DeviceProfileCodec.decode(document(quirks = """[{"quirkId":"q","effect":"from-the-future","enabled":true}]""")) as DeviceProfileDecoding.Decoded).profile
        assertNull(profile.quirks.single().knownEffect)
    }
}
