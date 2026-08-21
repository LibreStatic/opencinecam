/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusMeteringTest {
    private val sensor = SensorBounds(0, 0, 4000, 3000)

    @Test
    fun centerTapMapsToCenterOfSixteenByNineSensorCrop() {
        val area = FocusMeteringMapper.map(.5f, .5f, sensor, 1920, 1080, 0, 0, false)

        assertEquals(2000, (area.left + area.right) / 2)
        assertEquals(1500, (area.top + area.bottom) / 2)
        assertEquals(225, area.right - area.left)
    }

    @Test
    fun quarterTurnsMapDisplayCornersBackToSensorCoordinates() {
        val rotations = listOf(
            0 to (true to true),
            90 to (true to false),
            180 to (false to false),
            270 to (false to true),
        )
        rotations.forEach { (sensorRotation, expected) ->
            val area = FocusMeteringMapper.map(0f, 0f, sensor, 4, 3, sensorRotation, 0, false)
            val centerX = (area.left + area.right) / 2
            val centerY = (area.top + area.bottom) / 2
            assertEquals(expected.first, centerX < sensor.width / 2)
            assertEquals(expected.second, centerY < sensor.height / 2)
        }
    }

    @Test
    fun frontMirrorReversesHorizontalTapBeforeSensorMapping() {
        val rear = FocusMeteringMapper.map(.1f, .5f, sensor, 4, 3, 0, 0, false)
        val front = FocusMeteringMapper.map(.1f, .5f, sensor, 4, 3, 0, 0, true)

        assertTrue(rear.left < sensor.width / 2)
        assertTrue(front.left > sensor.width / 2)
    }

    @Test
    fun edgeRegionsStayInsideTheCroppedActiveArray() {
        val topLeft = FocusMeteringMapper.map(-1f, -1f, sensor, 16, 9, 0, 0, false)
        val bottomRight = FocusMeteringMapper.map(2f, 2f, sensor, 16, 9, 0, 0, false)

        assertTrue(topLeft.left >= 0)
        assertTrue(topLeft.top >= 375)
        assertTrue(bottomRight.right <= 4000)
        assertTrue(bottomRight.bottom <= 2625)
    }
}
