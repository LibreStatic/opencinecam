/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.os.PowerManager
import org.junit.Assert.*
import org.junit.Test

class SubjectFillLightPolicyTest {
    private fun channels(c: FillLightColor) = listOf(c.red, c.green, c.blue)

    @Test fun everyKelvinIsANormalizedSrgbWhite() {
        for (kelvin in FILL_LIGHT_MIN_KELVIN..FILL_LIGHT_MAX_KELVIN step 100) for (tint in -50..50 step 10) {
            val color = fillLightColor(kelvin, tint)
            assertTrue(channels(color).all { it in 0f..1f })
            assertEquals("max channel at $kelvin K tint $tint", 1f, channels(color).max(), 1e-6f)
        }
    }

    @Test fun warmerKelvinIsMonotonicallyWarmer() {
        var previousBlue = -1f
        var previousGreen = -1f
        for (kelvin in FILL_LIGHT_MIN_KELVIN..FILL_LIGHT_MAX_KELVIN step 100) {
            val color = fillLightColor(kelvin)
            assertEquals(1f, color.red, 1e-6f)
            assertTrue("blue rises with $kelvin K", color.blue >= previousBlue)
            assertTrue("green rises with $kelvin K", color.green >= previousGreen)
            previousBlue = color.blue
            previousGreen = color.green
        }
        val tungsten = fillLightColor(2700)
        assertTrue(tungsten.blue < 0.45f && tungsten.green < 0.75f)
    }

    @Test fun sixtyFiveHundredKelvinIsNearlyNeutral() {
        val daylight = fillLightColor(6500)
        assertTrue(channels(daylight).all { it > 0.97f })
    }

    @Test fun outOfRangeKelvinAndTintAreClamped() {
        assertEquals(fillLightColor(2700), fillLightColor(1000))
        assertEquals(fillLightColor(6500), fillLightColor(20_000))
        assertEquals(fillLightColor(5000, 50), fillLightColor(5000, 400))
        assertEquals(fillLightColor(5000, -50), fillLightColor(5000, -400))
    }

    @Test fun tintMovesBetweenGreenAndMagenta() {
        val neutral = fillLightColor(6500, 0)
        val magenta = fillLightColor(6500, 50)
        val green = fillLightColor(6500, -50)
        assertTrue(magenta.green < neutral.green - 0.2f)
        assertTrue(magenta.red > 0.97f && magenta.blue > 0.95f)
        assertEquals(1f, green.green, 1e-6f)
        assertTrue(green.red < 0.8f && green.blue < 0.8f)
        assertTrue(fillLightColor(6500, 20).green > magenta.green)
    }

    private val settings = SubjectDisplaySettings(mode = SubjectDisplayMode.FILL_LIGHT, brightness = 0.9f)

    @Test fun coolPhoneWithoutTimeoutKeepsTheRequestedBrightness() {
        val output = fillLightOutput(settings, 10_000_000, PowerManager.THERMAL_STATUS_LIGHT)
        assertEquals(FillLightOutput(0.9f, 1f, null), output)
    }

    @Test fun thermalStatusCapsBrightnessAndShowsANotice() {
        assertEquals(FillLightOutput(0.5f, 0.8f, FillLightNotice.THERMAL_WARM), fillLightOutput(settings, 0, PowerManager.THERMAL_STATUS_MODERATE))
        assertEquals(FillLightOutput(0.3f, 0.6f, FillLightNotice.THERMAL_HOT), fillLightOutput(settings, 0, PowerManager.THERMAL_STATUS_SEVERE))
        for (status in listOf(PowerManager.THERMAL_STATUS_CRITICAL, PowerManager.THERMAL_STATUS_EMERGENCY, PowerManager.THERMAL_STATUS_SHUTDOWN)) {
            assertEquals(FillLightOutput(0.1f, 0.3f, FillLightNotice.THERMAL_HOT), fillLightOutput(settings, 0, status))
        }
        // A request already below the cap is never raised.
        assertEquals(0.2f, fillLightOutput(settings.copy(brightness = 0.2f), 0, PowerManager.THERMAL_STATUS_MODERATE).windowBrightness)
    }

    @Test fun timeoutDimsAtTheDeadlineAndTakesPrecedence() {
        val timed = settings.copy(fillLightTimeoutSeconds = 60)
        assertNull(fillLightOutput(timed, 59_999, PowerManager.THERMAL_STATUS_NONE).notice)
        val expired = fillLightOutput(timed, 60_000, PowerManager.THERMAL_STATUS_NONE)
        assertEquals(FillLightOutput(FILL_LIGHT_TIMEOUT_BRIGHTNESS, FILL_LIGHT_TIMEOUT_COLOR_SCALE, FillLightNotice.TIMED_OUT), expired)
        assertEquals(FillLightNotice.TIMED_OUT, fillLightOutput(timed, 61_000, PowerManager.THERMAL_STATUS_SEVERE).notice)
        assertNull(fillLightOutput(settings, Long.MAX_VALUE, PowerManager.THERMAL_STATUS_NONE).notice)
        // A backwards clock never counts as elapsed time.
        assertNull(fillLightOutput(timed, -5_000_000, PowerManager.THERMAL_STATUS_NONE).notice)
        assertEquals(60_000L, fillLightRemainingMs(timed, -1))
        assertEquals(1L, fillLightRemainingMs(timed, 59_999))
        assertNull(fillLightRemainingMs(timed, 60_000))
        assertNull(fillLightRemainingMs(settings, 0))
    }

    private class FakeThermal(var status: Int = PowerManager.THERMAL_STATUS_NONE) : FillLightThermalSource {
        var listener: ((Int) -> Unit)? = null
        var registrations = 0
        override fun current() = status
        override fun register(listener: (Int) -> Unit) { registrations++; this.listener = listener }
        override fun unregister() { listener = null }
    }

    private class Harness(thermal: FakeThermal) {
        var now = 1_000L
        val tasks = mutableListOf<Pair<Long, () -> Unit>>()
        val outputs = mutableListOf<FillLightOutput?>()
        val monitor = SubjectFillLightMonitor(thermal, { now }, { delay, block ->
            val task = (now + delay) to block
            tasks += task
            { tasks.remove(task) }
        }, { outputs += it })
        fun advanceTo(time: Long) {
            now = time
            tasks.filter { it.first <= time }.forEach { tasks.remove(it); it.second() }
        }
    }

    @Test fun monitorRegistersThermalOnlyWhileActiveAndUnregistersOnStop() {
        val thermal = FakeThermal(PowerManager.THERMAL_STATUS_MODERATE)
        val harness = Harness(thermal)
        harness.monitor.update(false, settings)
        assertEquals(0, thermal.registrations)
        harness.monitor.update(true, settings)
        assertEquals(FillLightNotice.THERMAL_WARM, harness.monitor.output?.notice)
        harness.monitor.update(true, settings.copy(fillLightKelvin = 3200))
        assertEquals(1, thermal.registrations)
        thermal.listener!!(PowerManager.THERMAL_STATUS_SEVERE)
        assertEquals(0.3f, harness.monitor.output?.windowBrightness)
        thermal.listener!!(PowerManager.THERMAL_STATUS_NONE)
        assertEquals(FillLightOutput(0.9f, 1f, null), harness.monitor.output)
        harness.monitor.update(false, settings)
        assertNull(thermal.listener)
        assertNull(harness.monitor.output)
        assertNull(harness.outputs.last())
    }

    @Test fun monitorTimesOutOnTheMonotonicClockAndRestarts() {
        val harness = Harness(FakeThermal())
        val timed = settings.copy(fillLightTimeoutSeconds = 60)
        harness.monitor.update(true, timed)
        assertNull(harness.monitor.output?.notice)
        harness.advanceTo(30_000)
        assertNull(harness.monitor.output?.notice)
        harness.advanceTo(61_000)
        assertEquals(FillLightNotice.TIMED_OUT, harness.monitor.output?.notice)
        assertTrue(harness.tasks.isEmpty())
        harness.monitor.restart()
        assertNull(harness.monitor.output?.notice)
        assertEquals(1, harness.tasks.size)
        // Choosing another timeout restarts it from now.
        harness.advanceTo(100_000)
        harness.monitor.update(true, timed.copy(fillLightTimeoutSeconds = 300))
        harness.advanceTo(399_999)
        assertNull(harness.monitor.output?.notice)
        harness.advanceTo(400_000)
        assertEquals(FillLightNotice.TIMED_OUT, harness.monitor.output?.notice)
        // Leaving and re-entering the mode starts a fresh light.
        harness.monitor.update(false, timed)
        assertTrue(harness.tasks.isEmpty())
        harness.monitor.update(true, timed)
        assertNull(harness.monitor.output?.notice)
    }
}
