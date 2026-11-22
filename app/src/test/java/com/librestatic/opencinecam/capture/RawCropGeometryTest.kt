/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.capture

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.librestatic.opencinecam.rawvideo.RawVideoCrop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** PLAN-076 delivery 3b: the crop editor's sensor ↔ screen map and its editing operations. */
class RawCropGeometryTest {
    private val sensorW = 4080
    private val sensorH = 3060
    private val window = RawVideoCrop(1080, 1122, 1920, 816)

    private fun mapping(sensorOrientation: Int, displayDegrees: Int, front: Boolean, image: Rect = Rect(0f, 100f, 1080f, 1540f)) =
        RawCropMapping(image, sensorW, sensorH, sensorOrientation, displayDegrees, front)

    private fun aligned(crop: RawVideoCrop) {
        assertEquals(0, crop.left % 4)
        assertEquals(0, crop.width % 4)
        assertEquals(0, crop.top % 2)
        assertEquals(0, crop.height % 2)
        assertTrue(crop.left >= 0 && crop.top >= 0)
        assertTrue(crop.left + crop.width <= sensorW && crop.top + crop.height <= sensorH)
        assertTrue(crop.width >= RawVideoCrop.MIN_WIDTH && crop.height >= RawVideoCrop.MIN_HEIGHT)
    }

    private fun near(expected: Float, actual: Float, tolerance: Float = 0.5f) =
        assertTrue("expected $expected, got $actual", abs(expected - actual) <= tolerance)

    @Test fun pointsRoundTripInEveryRotationAndMirrored() {
        for (orientation in listOf(0, 90, 180, 270)) for (display in listOf(0, 90, 180, 270)) for (front in listOf(false, true)) {
            val map = mapping(orientation, display, front)
            for ((x, y) in listOf(0f to 0f, 1080f to 1122f, 3000f to 1938f, 4080f to 3060f)) {
                val back = map.sensorPoint(map.screenPoint(x, y))
                near(x, back.x, 1f)
                near(y, back.y, 1f)
            }
        }
    }

    @Test fun aBackCameraAt90DegreesTurnsTheSensorClockwiseOntoAPortraitScreen() {
        // Sensor top-left lands at the screen's top-right, as the analysis grid does for a 90° back sensor.
        val map = mapping(90, 0, false, Rect(0f, 0f, 1000f, 2000f))
        val corner = map.screenPoint(0f, 0f)
        near(1000f, corner.x)
        near(0f, corner.y)
        val rect = map.screenRect(window)
        // Sensor x maps to screen y, sensor y to screen x from the right.
        near(1000f * (1f - (1122f + 816f) / sensorH), rect.left, 1f)
        near(2000f * 1080f / sensorW, rect.top, 1f)
        near(1000f * 816f / sensorH, rect.width, 1f)
        near(2000f * 1920f / sensorW, rect.height, 1f)
    }

    @Test fun aFrontCameraMirrorsTheWindowLeftToRight() {
        val image = Rect(0f, 0f, 1000f, 2000f)
        val back = mapping(90, 0, false, image).screenRect(window)
        val front = mapping(90, 0, true, image).screenRect(window)
        near(1000f - back.right, front.left, 1f)
        near(back.top, front.top, 1f)
        near(back.width, front.width, 1f)
        near(back.height, front.height, 1f)
    }

    @Test fun screenRectRoundTripsThroughSensorPoints() {
        for (orientation in listOf(0, 90, 180, 270)) for (front in listOf(false, true)) {
            val map = mapping(orientation, 90, front)
            val rect = map.screenRect(window)
            val a = map.sensorPoint(rect.topLeft)
            val b = map.sensorPoint(rect.bottomRight)
            near(window.left.toFloat(), minOf(a.x, b.x), 2f)
            near(window.top.toFloat(), minOf(a.y, b.y), 2f)
            near((window.left + window.width).toFloat(), maxOf(a.x, b.x), 2f)
            near((window.top + window.height).toFloat(), maxOf(a.y, b.y), 2f)
        }
    }

    @Test fun everyScreenCornerNamesADistinctSensorCorner() {
        for (orientation in listOf(0, 90, 180, 270)) for (front in listOf(false, true)) {
            val map = mapping(orientation, 0, front)
            val corners = RawCropCorner.entries.map { map.sensorCorner(window, it) }.toSet()
            assertEquals(4, corners.size)
        }
    }

    @Test fun movingClampsInsideTheSensorAndKeepsAlignment() {
        val moved = rawCropMoved(window, 5003f, -9999f, sensorW, sensorH)
        aligned(moved)
        assertEquals(sensorW - window.width, moved.left)
        assertEquals(0, moved.top)
        assertEquals(window.width, moved.width)
        assertEquals(window.height, moved.height)
        val nudged = rawCropMoved(window, 3f, 3f, sensorW, sensorH)
        aligned(nudged)
    }

    @Test fun resizingAnchorsTheOppositeCorner() {
        val grown = rawCropResized(window, movingLeft = false, movingTop = false, dx = 401f, dy = 101f, aspect = null,
            sensorWidth = sensorW, sensorHeight = sensorH)
        aligned(grown)
        assertEquals(window.left, grown.left)
        assertEquals(window.top, grown.top)
        assertEquals(2320, grown.width)
        assertEquals(916, grown.height)
        val fromTopLeft = rawCropResized(window, movingLeft = true, movingTop = true, dx = -400f, dy = -100f, aspect = null,
            sensorWidth = sensorW, sensorHeight = sensorH)
        aligned(fromTopLeft)
        assertEquals(window.left + window.width, fromTopLeft.left + fromTopLeft.width)
        assertEquals(window.top + window.height, fromTopLeft.top + fromTopLeft.height)
    }

    @Test fun resizingStopsAtTheSensorEdgeAndTheMinimumSize() {
        val huge = rawCropResized(window, false, false, 99999f, 99999f, null, sensorW, sensorH)
        aligned(huge)
        assertEquals(sensorW, huge.left + huge.width)
        assertEquals(sensorH, huge.top + huge.height)
        val tiny = rawCropResized(window, false, false, -99999f, -99999f, null, sensorW, sensorH)
        aligned(tiny)
        assertEquals(window.left, tiny.left)
        assertEquals(window.top, tiny.top)
        assertEquals(RawVideoCrop.MIN_WIDTH, tiny.width)
        assertEquals(RawVideoCrop.MIN_HEIGHT, tiny.height)
        val crossed = rawCropResized(window, true, true, 99999f, 99999f, null, sensorW, sensorH)
        aligned(crossed)
        assertTrue(crossed.left + crossed.width <= window.left + window.width)
    }

    @Test fun anAspectLockKeepsTheRatioWithinRounding() {
        for (aspect in RawCropAspect.entries.mapNotNull { it.ratio }) {
            for ((dx, dy) in listOf(300f to 0f, 0f to 300f, -600f to -100f, 9999f to 9999f, -9999f to -9999f)) {
                val resized = rawCropResized(window, false, true, dx, dy, aspect, sensorW, sensorH)
                aligned(resized)
                val ratio = resized.width.toFloat() / resized.height
                assertTrue("$aspect $dx $dy gave ${resized.width}x${resized.height}", abs(ratio - aspect) / aspect < 0.04f)
            }
        }
    }

    @Test fun fittingAnAspectKeepsTheWidthAndCentre() {
        val fitted = rawCropFitAspect(window, 16f / 9f, sensorW, sensorH)
        aligned(fitted)
        assertEquals(1920, fitted.width)
        assertEquals(1080, fitted.height)
        near(window.left + window.width / 2f, fitted.left + fitted.width / 2f, 4f)
        near(window.top + window.height / 2f, fitted.top + fitted.height / 2f, 2f)
        // A square from the full width shrinks to the sensor's height.
        val square = rawCropFitAspect(RawVideoCrop(0, 0, sensorW, sensorH), 1f, sensorW, sensorH)
        aligned(square)
        assertEquals(sensorH, square.height)
        assertEquals(3060, square.width)
    }

    @Test fun growingAndShrinkingStepAboutTheCentreWithinBounds() {
        val grown = rawCropScaled(window, grow = true, aspect = null, sensorWidth = sensorW, sensorHeight = sensorH)
        aligned(grown)
        assertTrue(grown.width > window.width && grown.height > window.height)
        val shrunk = rawCropScaled(window, grow = false, aspect = null, sensorWidth = sensorW, sensorHeight = sensorH)
        aligned(shrunk)
        assertTrue(shrunk.width < window.width && shrunk.height < window.height)
        var crop = window
        repeat(60) { crop = rawCropScaled(crop, grow = true, aspect = null, sensorWidth = sensorW, sensorHeight = sensorH) }
        aligned(crop)
        repeat(120) { crop = rawCropScaled(crop, grow = false, aspect = null, sensorWidth = sensorW, sensorHeight = sensorH) }
        aligned(crop)
    }

    @Test fun theDefaultWindowIsHalfWidthSixteenNineCentred() {
        val crop = rawCropDefaultWindow(sensorW, sensorH)
        aligned(crop)
        assertEquals(2040, crop.width)
        assertEquals(1148, crop.height)
        assertEquals(1020, crop.left)
        assertFalse(rawCropIsFullSensor(crop, sensorW, sensorH))
        assertTrue(rawCropIsFullSensor(RawVideoCrop(0, 0, sensorW, sensorH), sensorW, sensorH))
    }

    @Test fun keysNameTheOriginOnlyWhenOffCentre() {
        val placed = RawVideoCrop(200, 300, 1920, 816)
        assertEquals("1920x816+200+300@24", rawCropKey(placed, sensorW, sensorH, 24, compressed = false))
        assertEquals("1920x816+200+300@24+bp", rawCropKey(placed, sensorW, sensorH, 24, compressed = true))
        // 1920 x 816 at +1080 +1122 is the centre of a 4080 x 3060 output, so its key names no origin.
        assertEquals("1920x816@24", rawCropKey(window, sensorW, sensorH, 24, compressed = false))
        assertEquals("1920x1080@30", rawCropKey(RawVideoCrop(1080, 990, 1920, 1080), sensorW, sensorH, 30, compressed = false))
        assertEquals(24, rawKeyFps("1920x816+1080+1122@24+bp"))
        assertEquals(30, rawKeyFps("full@30"))
        assertEquals(null, rawKeyFps(""))
        assertTrue(rawKeyBlockPack("full@30+bp"))
        assertFalse(rawKeyBlockPack("full@30+lj92"))
    }

    @Test fun savingIsTheShareOfTheFullSensorRateLeftOut() {
        val full = rawFullSensorBytesPerSecond(sensorW, sensorH, 24)
        val crop = com.librestatic.opencinecam.camera.RawVideoConfig("0", sensorW, sensorH, 1920, 816, 24).streamBytesPerSecond
        assertEquals(87, rawCropSavingPercent(crop, full))
        assertEquals(0, rawCropSavingPercent(full, full))
        assertEquals(0, rawCropSavingPercent(1L, 0L))
    }

    @Test fun mappingInverseSurvivesAnEmptyImage() {
        val map = mapping(90, 0, false, Rect(0f, 0f, 0f, 0f))
        val p = map.sensorPoint(Offset(10f, 10f))
        assertTrue(p.x.isFinite() && p.y.isFinite())
    }

    private fun imageOf(viewport: PreviewViewport) = Rect(viewport.left, viewport.top, viewport.right, viewport.bottom)

    private fun rectNear(expected: Rect, actual: Rect, tolerance: Float = 0.5f) {
        near(expected.left, actual.left, tolerance)
        near(expected.top, actual.top, tolerance)
        near(expected.right, actual.right, tolerance)
        near(expected.bottom, actual.bottom, tolerance)
    }

    /** The window's rect under [viewport], as the guide and the viewfinder place it. */
    private fun windowUnder(viewport: PreviewViewport, orientation: Int = 90, display: Int = 0, front: Boolean = false) =
        RawCropMapping(imageOf(viewport), sensorW, sensorH, orientation, display, front).screenRect(window.left, window.top, window.width, window.height)

    @Test fun theEnlargedViewportLetterboxesTheWindowInTheFrameArea() {
        // A phone in portrait: the 4:3 sensor turned upright fills a 1080 × 1440 frame under the top bar.
        val frame = PreviewViewport(0f, 120f, 1080f, 1440f)
        val area = imageOf(frame)
        for (orientation in listOf(90, 270)) for (front in listOf(false, true)) {
            val before = windowUnder(frame, orientation, 0, front)
            val enlarged = rawCropEnlargedViewport(frame, before, area)
            val after = windowUnder(enlarged, orientation, 0, front)
            // 1920 × 816 turned upright is 816 wide and 1920 tall on screen: the height fills, the width is letterboxed.
            val scale = minOf(area.width / before.width, area.height / before.height)
            near(area.height, after.height)
            near(before.width * scale, after.width)
            near(area.center.x, after.center.x)
            near(area.center.y, after.center.y)
            assertTrue(after.width <= area.width + 0.5f)
            // The picture scales uniformly: the frame's aspect is kept.
            near(frame.width / frame.height, enlarged.width / enlarged.height, 0.001f)
        }
    }

    @Test fun inLandscapeTheWideWindowFillsTheWidth() {
        val frame = PreviewViewport(160f, 0f, 1440f, 1080f)
        val area = imageOf(frame)
        val enlarged = rawCropEnlargedViewport(frame, windowUnder(frame, 90, 90), area)
        val after = windowUnder(enlarged, 90, 90)
        // 1920 × 816 is wider than 4:3, so it fills the width and is centred in height.
        rectNear(Rect(area.left, area.center.y - area.width * 816f / 1920f / 2f, area.right, area.center.y + area.width * 816f / 1920f / 2f), after)
    }

    @Test fun aFilledFrameEnlargesIntoItsVisiblePart() {
        // A filled overlay viewfinder overflows a 1080 × 2400 window; only its part inside the window counts.
        val frame = PreviewViewport(-360f, 0f, 1800f, 2400f)
        val area = previewVisibleArea(frame, Rect(0f, 0f, 1080f, 2400f))
        rectNear(Rect(0f, 0f, 1080f, 2400f), area)
        val after = windowUnder(rawCropEnlargedViewport(frame, windowUnder(frame), area))
        assertTrue(after.left >= -0.5f && after.right <= 1080.5f && after.top >= -0.5f && after.bottom <= 2400.5f)
        near(area.center.x, after.center.x)
        near(area.center.y, after.center.y)
    }

    @Test fun tapToFocusReadsTheSamePictureSpotWhenEnlarged() {
        val frame = PreviewViewport(0f, 120f, 1080f, 1440f)
        val before = windowUnder(frame)
        val enlarged = rawCropEnlargedViewport(frame, before, imageOf(frame))
        val after = windowUnder(enlarged)
        // A tap on a spot of the window, shown enlarged, sends the fraction that spot has in the whole picture...
        val spot = Offset(after.left + after.width * 0.25f, after.top + after.height * 0.75f)
        val sent = rawCropTapPoint(enlarged, spot)
        val same = Offset(before.left + before.width * 0.25f, before.top + before.height * 0.75f)
        val expected = rawCropTapPoint(frame, same)
        near(expected.x, sent.x, 0.001f)
        near(expected.y, sent.y, 0.001f)
        // ...and so the same sensor point.
        val map = RawCropMapping(imageOf(enlarged), sensorW, sensorH, 90, 0, false)
        val sensor = map.sensorPoint(spot)
        val reference = RawCropMapping(imageOf(frame), sensorW, sensorH, 90, 0, false).sensorPoint(same)
        near(reference.x, sensor.x, 1f)
        near(reference.y, sensor.y, 1f)
    }

    @Test fun anEmptyWindowOrAreaLeavesTheViewportAlone() {
        val frame = PreviewViewport(0f, 0f, 1080f, 1440f)
        assertEquals(frame, rawCropEnlargedViewport(frame, Rect.Zero, imageOf(frame)))
        assertEquals(frame, rawCropEnlargedViewport(frame, windowUnder(frame), Rect.Zero))
    }
}
