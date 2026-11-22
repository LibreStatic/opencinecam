/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.diagnostics

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionSensorFactsTest {
    private val gyroscope = MotionSensorFact(
        MotionSensorKind.GYROSCOPE, present = true, name = "LSM6DSO Gyroscope", vendor = "STMicro", minDelayUs = 2000,
        maxRateHz = 500.0, resolution = 0.0012217305f, maxRange = 34.906586f, fifoMaxEventCount = 3000, highSamplingRateCapped = false,
    )

    @Test fun rateComesFromTheMinimumDelay() {
        assertEquals(500.0, maxRateHzFromMinDelay(2000)!!, 1e-9)
        assertEquals(100.0, maxRateHzFromMinDelay(10_000)!!, 1e-9)
        // 0 is an on-change or one-shot sensor, negative is invalid: neither declares a streaming rate.
        assertNull(maxRateHzFromMinDelay(0))
        assertNull(maxRateHzFromMinDelay(-1))
    }

    @Test fun theCapAppliesFromAndroid12WithoutThePermissionAboveTwoHundredHertz() {
        assertEquals(true, highSamplingRateCapped(31, permissionGranted = false, maxRateHz = 500.0))
        assertEquals(false, highSamplingRateCapped(31, permissionGranted = true, maxRateHz = 500.0))
        assertEquals(false, highSamplingRateCapped(31, permissionGranted = false, maxRateHz = 200.0))
        assertEquals(false, highSamplingRateCapped(30, permissionGranted = false, maxRateHz = 500.0))
        assertNull(highSamplingRateCapped(34, permissionGranted = false, maxRateHz = null))
    }

    @Test fun ratesAndValuesFormatWithoutFloatNoise() {
        assertEquals("500", formatRateHz(500.0))
        assertEquals("2404", formatRateHz(1_000_000.0 / 416))
        assertEquals("5.0", formatRateHz(5.0))
        assertEquals("0.0012217305 rad/s", formatSensorValue(0.0012217305f, "rad/s"))
        assertEquals("34.906586 rad/s", formatSensorValue(34.906586f, "rad/s"))
        assertEquals("0.0625 µT", formatSensorValue(0.0625f, "µT"))
        assertEquals("4912 µT", formatSensorValue(4912f, "µT"))
        assertEquals("0.0000001 µT", formatSensorValue(1.0E-7f, "µT"))
    }

    @Test fun jsonKeysEachSensorByKindAndOmitsWhatAnAbsentSensorCannotSay() {
        val json = motionSensorsJson(listOf(gyroscope, MotionSensorFact.absent(MotionSensorKind.MAGNETOMETER)))
        val gyro = json["gyroscope"]!!.jsonObject
        assertTrue(gyro["present"]!!.jsonPrimitive.boolean)
        assertEquals(500.0, gyro["maxRateHz"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(2000, gyro["minDelayUs"]!!.jsonPrimitive.int)
        assertEquals(3000, gyro["fifoMaxEventCount"]!!.jsonPrimitive.int)
        assertEquals(0.0012217305, gyro["resolution"]!!.jsonPrimitive.double, 1e-7)
        assertEquals(34.906586, gyro["maxRange"]!!.jsonPrimitive.double, 1e-5)
        assertEquals("STMicro", gyro["vendor"]!!.jsonPrimitive.content)
        assertEquals("rad/s", gyro["unit"]!!.jsonPrimitive.content)
        assertFalse(gyro["highSamplingRateCapped"]!!.jsonPrimitive.boolean)
        assertEquals(JsonObject(mapOf("present" to JsonPrimitive(false))), json["magnetometer"])
        assertNull(json["accelerometer"])
    }

    @Test fun textLineNamesTheRateResolutionAndPart() {
        assertEquals(
            "gyroscope: 500 Hz, resolution 0.0012217305 rad/s, range 34.906586 rad/s, fifo 3000 (LSM6DSO Gyroscope, STMicro)",
            motionSensorLine(gyroscope, "Not available"),
        )
        assertEquals("magnetometer: Not available", motionSensorLine(MotionSensorFact.absent(MotionSensorKind.MAGNETOMETER), "Not available"))
        assertEquals(
            "accelerometer: rate n/a, capped at 200 Hz",
            motionSensorLine(MotionSensorFact(MotionSensorKind.ACCELEROMETER, present = true, highSamplingRateCapped = true), "-"),
        )
    }
}
