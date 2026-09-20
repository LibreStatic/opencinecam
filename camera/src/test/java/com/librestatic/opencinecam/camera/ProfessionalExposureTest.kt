/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import org.junit.Assert.*
import org.junit.Test

class ProfessionalExposureTest {
    private val caps = ExposureCapabilities(true, 100..6400, 100_000L..1_000_000_000L,
        setOf(ExposureMode.ISO_PRIORITY, ExposureMode.SHUTTER_PRIORITY), Antibanding.entries.toSet())
    @Test fun rationalCaptureAngleUsesCaptureRateAndRetainsSelectedAngle() {
        val selection = ExposureSelection(mode = ExposureMode.MANUAL, shutterUnit = ShutterUnit.ANGLE)
        assertEquals(16_683_333L, selection.requestedTimeNs(CaptureFrameRate(30_000, 1001)))
        assertEquals(20_854_166L, selection.requestedTimeNs(CaptureFrameRate(24_000, 1001)))
        assertEquals(8_341_666L, selection.requestedTimeNs(CaptureFrameRate(60_000, 1001)))
        assertEquals(1800, selection.angleTenths)
    }
    @Test fun manualAlwaysSuppliesBothParametersAndDisclosesClamping() {
        val resolved = ExposureSelection(ExposureMode.MANUAL, 20_000, shutterUnit = ShutterUnit.ANGLE, angleTenths = 3600).resolve(caps, CaptureFrameRate(30))
        assertEquals(6400, resolved.iso)
        assertEquals(33_233_333L, resolved.timeNs)
        assertEquals(33_333_333L, resolved.frameDurationNs)
        assertTrue(resolved.clamped)
        assertFalse(resolved.unavailable)
    }
    @Test fun nativeIsoPriorityDoesNotOverrideTheAutomaticTimeOrFrameDuration() {
        val resolved = ExposureSelection(ExposureMode.ISO_PRIORITY, 400).resolve(caps, CaptureFrameRate(30))
        assertEquals(ExposureMode.ISO_PRIORITY, resolved.mode)
        assertEquals(400, resolved.iso)
        assertNull(resolved.timeNs)
        assertNull(resolved.frameDurationNs)
    }
    @Test fun nativeShutterPriorityDoesNotOverrideTheAutomaticIsoOrFrameDuration() {
        val resolved = ExposureSelection(ExposureMode.SHUTTER_PRIORITY, timeNs = 10_000_000L).resolve(caps, CaptureFrameRate(60))
        assertEquals(10_000_000L, resolved.timeNs)
        assertNull(resolved.iso)
        assertNull(resolved.frameDurationNs)
    }
    @Test fun missingNativePriorityNeverPretendsToImplementSemiAutomaticExposure() {
        val requested = ExposureSelection(ExposureMode.ISO_PRIORITY, 800)
        val resolved = requested.resolve(caps.copy(priorities = emptySet()), CaptureFrameRate(30))
        assertEquals(ExposureMode.AUTO, resolved.mode)
        assertTrue(resolved.unavailable)
        assertNull(resolved.iso)
        assertEquals(ExposureMode.ISO_PRIORITY, requested.mode)
    }
    @Test fun impossibleFrameBoundsAndMalformedRangesResolveToExplicitAuto() {
        val requested = ExposureSelection(ExposureMode.MANUAL)
        assertTrue(requested.resolve(caps.copy(timeRangeNs = 50_000_000L..100_000_000L), CaptureFrameRate(30)).unavailable)
        assertTrue(requested.resolve(caps.copy(isoRange = 0..0), CaptureFrameRate(30)).unavailable)
        assertTrue(requested.resolve(ExposureCapabilities(), CaptureFrameRate(30)).unavailable)
    }
    @Test fun antibandingSelectionIsIndependentFromShutterSuggestionsAndMustBeAdvertised() {
        val requested = ExposureSelection(antibanding = Antibanding.HZ50)
        assertEquals(Antibanding.HZ50, requested.resolve(caps, CaptureFrameRate(30)).antibanding)
        assertNull(requested.resolve(caps.copy(antibanding = emptySet()), CaptureFrameRate(30)).antibanding)
        assertEquals(16_666_667L, requested.timeNs)
    }
    @Test fun angleBoundsAndLargestRateComponentsAvoidOverflow() {
        val selection = ExposureSelection(shutterUnit = ShutterUnit.ANGLE, angleTenths = 3600)
        assertEquals(1_000_000_000_000_000L, selection.requestedTimeNs(CaptureFrameRate(1, 1_000_000)))
        assertEquals(1000L, selection.requestedTimeNs(CaptureFrameRate(1_000_000)))
        assertTrue(runCatching { selection.copy(angleTenths = 0) }.isFailure)
        assertTrue(runCatching { CaptureFrameRate(0) }.isFailure)
    }
    @Test fun tintAndPresetsAdaptWithoutClaimingUnavailableColorControl() {
        val wb = WhiteBalanceSelection.Kelvin(5600, 17)
        assertEquals(wb, wb.adaptTo(2000..8000, true))
        assertEquals(WhiteBalanceSelection.Kelvin(5600, 0), wb.adaptTo(2000..8000, false))
        assertEquals(WhiteBalanceSelection.Auto, wb.adaptTo(null))
        assertEquals(WhiteBalanceSelection.Auto, WhiteBalanceSelection.Preset(5).adaptTo(null, false, setOf(1)))
        assertTrue(runCatching { wb.copy(tint = 51) }.isFailure)
    }
}
