/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import com.librestatic.opencinecam.core.model.PolicyMode
import org.junit.Assert.*
import org.junit.Test

class ExposureGateTest {
    private val caps = ExposureCapabilities(true, 100..3200, 100_000L..1_000_000_000L, setOf(ExposureMode.ISO_PRIORITY), Antibanding.entries.toSet())
    private val rate = CaptureFrameRate(30)
    private val inRange = ExposureSelection(ExposureMode.MANUAL, 800, timeNs = 10_000_000L)
    private val tooSensitive = inRange.copy(iso = 6400)

    @Test fun inRangeIsAppliedUntouchedUnderBothPolicies() {
        for (p in PolicyMode.entries) {
            val g = inRange.gate(caps, rate, p)
            assertNull(g.code); assertFalse(g.rejected)
            assertEquals(ExposureMode.MANUAL, g.exposure.mode); assertEquals(800, g.exposure.iso)
        }
    }
    @Test fun strictRejectsAClampedValueAndKeepsExposureAutomatic() {
        val g = tooSensitive.gate(caps, rate, PolicyMode.STRICT)
        assertEquals(ExposureGateCode.OUT_OF_RANGE, g.code); assertTrue(g.rejected)
        assertEquals(ExposureMode.AUTO, g.exposure.mode)
        assertNull(g.exposure.iso); assertNull(g.exposure.timeNs); assertNull(g.exposure.frameDurationNs)
        assertTrue(g.message!!.contains("ISO 6400") && g.message!!.contains("100-3200"))
    }
    @Test fun strictKeepsAntibandingWhenRejecting() {
        val g = tooSensitive.copy(antibanding = Antibanding.HZ50).gate(caps, rate, PolicyMode.STRICT)
        assertEquals(Antibanding.HZ50, g.exposure.antibanding)
    }
    @Test fun adaptiveAppliesTheClampedValueAndDisclosesIt() {
        val g = tooSensitive.gate(caps, rate, PolicyMode.ADAPTIVE)
        assertEquals(ExposureGateCode.OUT_OF_RANGE, g.code); assertFalse(g.rejected)
        assertEquals(3200, g.exposure.iso); assertEquals(ExposureMode.MANUAL, g.exposure.mode)
        assertEquals("ISO 6400 clamped to 3200 on this lens", g.message)
    }
    @Test fun anExposureTimeAboveTheFrameDurationIsAClamp() {
        val long = inRange.copy(timeNs = 100_000_000L)
        assertEquals(ExposureGateCode.OUT_OF_RANGE, long.gate(caps, rate, PolicyMode.STRICT).code)
        val adaptive = long.gate(caps, rate, PolicyMode.ADAPTIVE)
        assertTrue(adaptive.message!!.startsWith("Exposure time 1/10 s clamped to"))
        assertTrue(adaptive.exposure.timeNs!! < 33_333_333L)
    }
    @Test fun aFullFrameShutterIsHonouredDespiteTheReadoutMargin() {
        val fullFrame = inRange.copy(timeNs = rate.frameDurationNs)
        for (p in PolicyMode.entries) {
            val g = fullFrame.gate(caps, rate, p)
            assertNull(g.code); assertFalse(g.rejected)
            assertEquals(ExposureMode.MANUAL, g.exposure.mode); assertEquals(rate.frameDurationNs - 100_000L, g.exposure.timeNs)
        }
        val shortLens = caps.copy(timeRangeNs = 100_000L..20_000_000L)
        assertEquals(ExposureGateCode.OUT_OF_RANGE, fullFrame.gate(shortLens, rate, PolicyMode.STRICT).code)
    }
    @Test fun unsupportedModeIsRejectedInStrictAndFallsBackInAdaptive() {
        val shutter = ExposureSelection(ExposureMode.SHUTTER_PRIORITY)
        val strict = shutter.gate(caps, rate, PolicyMode.STRICT)
        assertEquals(ExposureGateCode.MODE_UNSUPPORTED, strict.code); assertTrue(strict.rejected)
        val adaptive = shutter.gate(caps, rate, PolicyMode.ADAPTIVE)
        assertEquals(ExposureGateCode.MODE_UNSUPPORTED, adaptive.code); assertFalse(adaptive.rejected)
        assertEquals(ExposureMode.AUTO, adaptive.exposure.mode)
    }
    @Test fun missingRangesAreUnknownNotUnsupported() {
        for (p in PolicyMode.entries) {
            val g = inRange.gate(ExposureCapabilities(), rate, p)
            assertEquals(ExposureGateCode.CAPABILITY_UNKNOWN, g.code)
            assertEquals(ExposureMode.AUTO, g.exposure.mode)
        }
        assertEquals(ExposureGateCode.CAPABILITY_UNKNOWN, ExposureSelection(ExposureMode.ISO_PRIORITY).gate(ExposureCapabilities(priorities = setOf(ExposureMode.ISO_PRIORITY)), rate, PolicyMode.STRICT).code)
    }
    @Test fun autoNeverReports() {
        for (p in PolicyMode.entries) assertNull(ExposureSelection().gate(ExposureCapabilities(), rate, p).code)
    }
}
