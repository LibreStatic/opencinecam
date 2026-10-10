/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LowLightBoostTest {
    private fun decide(
        supported: Boolean = true,
        enabled: Boolean = true,
        mode: ExposureMode = ExposureMode.AUTO,
        scene: LowLightBoostScene = LowLightBoostScene.PREVIEW_OR_STILL,
        highSpeed: Boolean = false,
        fps: Int = 30,
    ) = resolveLowLightBoost(supported, enabled, mode, scene, highSpeed, fps)

    @Test fun automaticExposureAtThirtyFpsUsesTheBoost() {
        assertEquals(LowLightBoostDecision(true), decide())
        assertEquals(LowLightBoostDecision(true), decide(fps = 24))
    }

    @Test fun offOrUnsupportedStaysSilentlyPlainAe() {
        assertEquals(LowLightBoostDecision(false), decide(enabled = false))
        assertEquals(LowLightBoostDecision(false), decide(supported = false))
        // Unsupported wins over every reason: no explanation for a feature the camera lacks.
        assertNull(decide(supported = false, mode = ExposureMode.MANUAL).inactive)
    }

    @Test fun manualAndPriorityExposureFallBackWithAReason() {
        assertEquals(LowLightBoostDecision(false, LowLightBoostInactive.MANUAL_EXPOSURE), decide(mode = ExposureMode.MANUAL))
        assertEquals(LowLightBoostDecision(false, LowLightBoostInactive.AE_PRIORITY), decide(mode = ExposureMode.ISO_PRIORITY))
        assertEquals(LowLightBoostDecision(false, LowLightBoostInactive.AE_PRIORITY), decide(mode = ExposureMode.SHUTTER_PRIORITY))
    }

    @Test fun videoKeepsItsFixedFrameRate() {
        assertEquals(LowLightBoostDecision(false, LowLightBoostInactive.VIDEO_FIXED_FRAME_RATE), decide(scene = LowLightBoostScene.VIDEO))
    }

    @Test fun highSpeedAndRatesAboveThirtyFallBack() {
        assertEquals(LowLightBoostInactive.HIGH_SPEED, decide(highSpeed = true).inactive)
        assertEquals(LowLightBoostInactive.FRAME_RATE_ABOVE_30, decide(fps = 31).inactive)
        assertEquals(LowLightBoostInactive.FRAME_RATE_ABOVE_30, decide(fps = 60).inactive)
    }

    @Test fun supportNeedsApi35AndTheAdvertisedMode() {
        assertTrue(lowLightBoostSupported(35, setOf(1, 6)))
        assertTrue(lowLightBoostSupported(36, setOf(6)))
        assertFalse(lowLightBoostSupported(34, setOf(1, 6)))
        assertFalse(lowLightBoostSupported(35, setOf(1, 2, 3)))
        assertFalse(lowLightBoostSupported(36, emptySet()))
    }

    @Test fun activeFlagNeedsTheBoostSubmittedAndAStateReported() {
        assertEquals(true, lowLightBoostActive(AE_MODE_ON_LOW_LIGHT_BOOST_BRIGHTNESS_PRIORITY, LOW_LIGHT_BOOST_STATE_ACTIVE))
        assertEquals(false, lowLightBoostActive(AE_MODE_ON_LOW_LIGHT_BOOST_BRIGHTNESS_PRIORITY, 0))
        assertNull(lowLightBoostActive(AE_MODE_ON_LOW_LIGHT_BOOST_BRIGHTNESS_PRIORITY, null))
        assertNull(lowLightBoostActive(1, LOW_LIGHT_BOOST_STATE_ACTIVE))
        assertNull(lowLightBoostActive(null, LOW_LIGHT_BOOST_STATE_ACTIVE))
    }

    @Test fun constantsMatchCamera2() {
        assertEquals(android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_ON_LOW_LIGHT_BOOST_BRIGHTNESS_PRIORITY, AE_MODE_ON_LOW_LIGHT_BOOST_BRIGHTNESS_PRIORITY)
        assertEquals(android.hardware.camera2.CameraMetadata.CONTROL_LOW_LIGHT_BOOST_STATE_ACTIVE, LOW_LIGHT_BOOST_STATE_ACTIVE)
    }
}
