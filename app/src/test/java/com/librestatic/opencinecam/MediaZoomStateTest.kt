/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import androidx.compose.animation.core.calculateTargetValue
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import com.librestatic.opencinecam.playback.RawDevelop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from Lightforge's ZoomPanStateTest, plus the review's bounds and GL quad maths. */
@OptIn(ExperimentalCoroutinesApi::class)
class MediaZoomStateTest {

    private val density = Density(1f, 1f)

    // Container 100x100; content displayed size equals container at scale 1 (fit = 1),
    // so max offsets at scale s are (100*s - 100)/2 on each axis.
    private fun state() = ZoomPanState(density) { candidateScale ->
        val span = (100f * candidateScale - 100f) / 2f
        Offset(span, span)
    }

    /**
     * runTest plus a frame clock that answers every withFrameNanos at once, so animations converge
     * deterministically. Each frame also pumps the virtual scheduler.
     */
    private fun runZoomPanTest(block: suspend CoroutineScope.() -> Unit) = runTest {
        withContext(EagerFrameClock(testScheduler)) { block() }
    }

    private class EagerFrameClock(private val scheduler: TestCoroutineScheduler) : MonotonicFrameClock {
        private var frame = 0L

        private companion object {
            // withFrameNanos expects nanoseconds: one 60 Hz frame per call.
            const val FRAME_MILLIS = 16L
            const val FRAME_NANOS = FRAME_MILLIS * 1_000_000L
        }

        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R =
            onFrame(++frame * FRAME_NANOS).also { scheduler.advanceTimeBy(FRAME_MILLIS) }
    }

    @Test
    fun pinchGestureClampsPanWithinBoundsAndTracksScale() = runZoomPanTest {
        val zoom = state()
        zoom.applyGesture(Offset(50f, 50f), Offset(50f, 50f), Offset(10_000f, -10_000f), 2f, 1f, 8f)
        assertEquals(2f, zoom.currentScale, 0.001f)
        assertEquals(50f, zoom.offsetX.value, 0.5f)
        assertEquals(-50f, zoom.offsetY.value, 0.5f)
        assertTrue(zoom.isZoomed)
    }

    @Test
    fun pinchIsClampedToTheMaximumScale() = runZoomPanTest {
        val zoom = state()
        zoom.applyGesture(Offset(50f, 50f), Offset(50f, 50f), Offset.Zero, 20f, 1f, MEDIA_MAX_ZOOM)
        assertEquals(MEDIA_MAX_ZOOM, zoom.currentScale, 0.001f)
    }

    @Test
    fun zoomBelowThresholdIsNotReportedAsZoomed() = runZoomPanTest {
        val zoom = state()
        zoom.applyGesture(Offset(50f, 50f), Offset(50f, 50f), Offset.Zero, 1.005f, 1f, 8f)
        assertFalse(zoom.isZoomed)
        zoom.applyGesture(Offset(50f, 50f), Offset(50f, 50f), Offset.Zero, 1.01f, 1f, 8f)
        assertTrue(zoom.isZoomed)
    }

    @Test
    fun zoomToOneRecentersContentAfterAGesture() = runZoomPanTest {
        val zoom = state()
        zoom.applyGesture(Offset(50f, 50f), Offset(20f, 20f), Offset(30f, 30f), 3f, 1f, 8f)
        assertTrue(zoom.isZoomed)
        zoom.zoomTo(Offset(50f, 50f), 1f, null, 8f)
        assertEquals(1f, zoom.currentScale, 0.0001f)
        assertEquals(0f, zoom.offsetX.value, 0.0001f)
        assertEquals(0f, zoom.offsetY.value, 0.0001f)
    }

    @Test
    fun doubleTapZoomConvergesTowardTapFocusEvenAfterPriorGestures() = runZoomPanTest {
        val zoom = state()
        // A prior gesture installs offset bounds for its own scale; the next double tap must still
        // converge to the tap focus instead of being trapped by stale bounds.
        zoom.applyGesture(Offset(50f, 50f), Offset(50f, 50f), Offset(40f, 40f), 1.4f, 1f, 8f)
        zoom.zoomTo(Offset(50f, 50f), 2f, Offset(80f, 80f), 8f)
        assertEquals(2f, zoom.currentScale, 0.001f)
        assertTrue(zoom.offsetX.value in -50f..50f)
        assertTrue(zoom.offsetY.value in -50f..50f)
        assertTrue(zoom.isZoomed)
    }

    @Test
    fun flingDecayTravelsReleaseVelocityTimesTimeConstant() {
        val travel = ZoomPanState.FLING_DECAY.calculateTargetValue(0f, 1_000f)
        assertEquals(1_000f * FLING_TIME_CONSTANT_SECONDS, travel, 1f)
        // Exponential: travel scales linearly with release velocity, unlike constant deceleration.
        assertEquals(travel * 10f, ZoomPanState.FLING_DECAY.calculateTargetValue(0f, 10_000f), 10f)
    }

    @Test
    fun doubleTapTogglesBetweenOneAndTheDoubleTapZoomTowardTheTap() = runZoomPanTest {
        val zoom = MediaZoomState(density).apply {
            area = IntSize(100, 100)
            content = Size(100f, 100f)
        }
        zoom.toggle(Offset(80f, 50f))
        assertEquals(MEDIA_DOUBLE_TAP_ZOOM, zoom.pan.currentScale, 0.001f)
        // The tap point stays put: (80 - 50) * (1 - 2.5) = -45, inside the ±75 bounds.
        assertEquals(-45f, zoom.pan.offsetX.value, 0.01f)
        assertEquals(0f, zoom.pan.offsetY.value, 0.01f)
        zoom.toggle(Offset(10f, 10f))
        assertEquals(1f, zoom.pan.currentScale, 0.0001f)
        assertFalse(zoom.moved)
    }

    @Test
    fun doubleTapRespectsALowerCeiling() = runZoomPanTest {
        val zoom = MediaZoomState(density).apply { area = IntSize(100, 100); content = Size(100f, 100f) }
        zoom.toggle(Offset(50f, 50f), maxScale = 2f)
        assertEquals(2f, zoom.pan.currentScale, 0.001f)
    }

    @Test
    fun fitBoundsLockTheAxisThePhotoDoesNotOverflow() {
        // A 4:3 photo fitted to a 400x400 area shows 400x300: at 2x it is 800x600.
        val bounds = mediaZoomBoundsFit(4000f, 3000f, 400f, 400f, 2f)
        assertEquals(200f, bounds.x, 0.001f)
        assertEquals(100f, bounds.y, 0.001f)
        // At 1.2x the height (360) still fits the area, so the vertical axis stays locked.
        val slight = mediaZoomBoundsFit(4000f, 3000f, 400f, 400f, 1.2f)
        assertEquals(40f, slight.x, 0.001f)
        assertEquals(0f, slight.y, 0.0f)
        assertEquals(Offset.Zero, mediaZoomBoundsFit(4000f, 3000f, 400f, 400f, 1f))
        assertEquals(Offset.Zero, mediaZoomBoundsFit(0f, 3000f, 400f, 400f, 4f))
    }

    @Test
    fun boxBoundsAreHalfTheGrowthOnEachAxis() {
        assertEquals(Offset(160f, 90f), mediaZoomBoundsBox(320f, 180f, 2f))
        assertEquals(Offset.Zero, mediaZoomBoundsBox(320f, 180f, 1f))
        assertEquals(Offset.Zero, mediaZoomBoundsBox(320f, 180f, 0.5f))
    }

    @Test
    fun stateUsesFitOrBoxBoundsByContent() {
        val zoom = MediaZoomState(density).apply { area = IntSize(400, 400); content = Size(4000f, 3000f); fitContent = true }
        assertEquals(Offset(200f, 100f), zoom.maxOffsets(2f))
        zoom.fitContent = false
        zoom.content = Size(400f, 300f)
        assertEquals(Offset(200f, 150f), zoom.maxOffsets(2f))
        assertEquals(Offset(200f, 200f), zoom.center)
    }

    @Test
    fun quadAtIdentityIsTheFullSurface() {
        assertArrayEquals(floatArrayOf(1f, 1f, 0f, 0f), zoomedQuad(intArrayOf(0, 0, 1920, 1080), 1920, 1080, 1f, 0f, 0f), 1e-6f)
    }

    @Test
    fun quadScalesAboutTheCentreAndFlipsTheVerticalPan() {
        // 2x, panned a quarter of the width right and a quarter of the height down (Compose y down).
        val quad = zoomedQuad(intArrayOf(0, 0, 1000, 500), 1000, 500, 2f, 0.25f, 0.25f)
        assertArrayEquals(floatArrayOf(2f, 2f, 0.5f, -0.5f), quad, 1e-6f)
        // The quad's right edge (NDC 1) lands at 2.5: off the surface, cropped by the clip volume.
        assertEquals(2.5f, 1f * quad[0] + quad[2], 1e-6f)
    }

    @Test
    fun quadMatchesAGraphicsLayerOnTheSurface() {
        // A point at surface pixel (300, 100) of a 1000x500 surface (y down), zoomed 3x and panned (+40, -20) px,
        // must land where graphicsLayer puts it: centre + (p - centre) * 3 + pan.
        val (scale, panX, panY) = Triple(3f, 40f, -20f)
        val quad = zoomedQuad(intArrayOf(0, 0, 1000, 500), 1000, 500, scale, panX / 1000f, panY / 500f)
        val ndcX = 300f / 1000f * 2f - 1f
        val ndcY = 1f - 100f / 500f * 2f
        val outX = (ndcX * quad[0] + quad[2] + 1f) / 2f * 1000f
        val outY = (1f - (ndcY * quad[1] + quad[3])) / 2f * 500f
        assertEquals(500f + (300f - 500f) * scale + panX, outX, 0.01f)
        assertEquals(250f + (100f - 250f) * scale + panY, outY, 0.01f)
    }

    @Test
    fun quadCombinesTheLetterboxWithTheZoom() {
        // A 4:3 frame in a 1920x1080 surface: pillarboxed to 1440x1080 at x = 240.
        val box = RawDevelop.letterbox(4080, 3060, 1920, 1080)
        val identity = zoomedQuad(box, 1920, 1080, 1f, 0f, 0f)
        assertArrayEquals(floatArrayOf(0.75f, 1f, 0f, 0f), identity, 1e-6f)
        val zoomed = zoomedQuad(box, 1920, 1080, 2f, 0.1f, 0f)
        assertArrayEquals(floatArrayOf(1.5f, 2f, 0.2f, 0f), zoomed, 1e-6f)
        // An off-centre box (letterboxed at the top-left) moves away from the centre as it scales.
        val corner = zoomedQuad(intArrayOf(0, 540, 960, 540), 1920, 1080, 2f, 0f, 0f)
        assertArrayEquals(floatArrayOf(1f, 1f, -1f, 1f), corner, 1e-6f)
    }

    @Test
    fun quadOfAnEmptySurfaceDrawsNothing() {
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 0f), zoomedQuad(intArrayOf(0, 0, 0, 0), 0, 1080, 2f, 0f, 0f), 0f)
    }
}
