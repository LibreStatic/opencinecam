/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Drags that start on the thumb at either end of the range must move the slider.
 *
 * With gesture navigation those thumbs sit inside the system back-gesture insets, so the
 * edge-swipe monitor stole the drag ("is stealing input gesture" in InputDispatcher) and the
 * value never changed. Compose's `performTouchInput` dispatches straight into the view and skips
 * that path, so these tests inject through UiAutomation like a real finger. Each case is its own
 * test: a stolen gesture runs Back and takes the activity with it.
 */
class SliderEdgeDragTest {
    @get:Rule val compose = createComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val density get() = instrumentation.targetContext.resources.displayMetrics.density

    private fun inject(action: Int, downTime: Long, x: Float, y: Float) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
        event.recycle()
    }

    private fun press(fromX: Float, toX: Float, y: Float, steps: Int = 20) {
        val downTime = SystemClock.uptimeMillis()
        inject(MotionEvent.ACTION_DOWN, downTime, fromX, y)
        for (i in 1..steps) {
            inject(MotionEvent.ACTION_MOVE, downTime, fromX + (toX - fromX) * i / steps, y)
            SystemClock.sleep(15)
        }
        inject(MotionEvent.ACTION_UP, downTime, toX, y)
    }

    /** Lays the slider out like the settings sheets (16 dp side padding) and returns its value after [gesture]. */
    private fun valueAfter(start: Float, fold: Boolean = false, gesture: (slider: Rect) -> Unit): Float {
        val value = mutableFloatStateOf(start)
        var slider = Rect.Zero
        val origin = IntArray(2)
        compose.setContent {
            val view = LocalView.current
            MaterialTheme {
                Column(Modifier.fillMaxWidth().padding(vertical = 80.dp, horizontal = 16.dp)) {
                    val tracked = Modifier.fillMaxWidth().onGloballyPositioned { coordinates ->
                        view.getLocationOnScreen(origin)
                        slider = coordinates.boundsInWindow().translate(origin[0].toFloat(), origin[1].toFloat())
                    }
                    if (fold) Column(tracked) { FoldSlider("Brightness", value.floatValue, 0f..100f) { value.floatValue = it } }
                    else CineSlider(value.floatValue, { value.floatValue = it }, tracked, valueRange = 0f..100f)
                }
            }
        }
        compose.waitForIdle()
        // The window manager applies exclusion rects after layout; let it catch up.
        SystemClock.sleep(300)
        gesture(slider)
        compose.waitForIdle()
        return value.floatValue
    }

    /** Centre of the thumb's touch slot when it rests at the end of the track. */
    private val thumbInset get() = 10 * density

    @Test fun dragFromThumbAtMaximumMovesTheValue() {
        val after = valueAfter(100f) { s -> press(s.right - thumbInset, s.left + s.width * 0.3f, s.center.y) }
        assertTrue("value stayed at $after", after < 50f)
    }

    @Test fun dragFromThumbAtMinimumMovesTheValue() {
        val after = valueAfter(0f) { s -> press(s.left + thumbInset, s.left + s.width * 0.7f, s.center.y) }
        assertTrue("value stayed at $after", after > 50f)
    }

    @Test fun foldSliderDragFromThumbAtMaximumCommitsTheValue() {
        // FoldSlider stacks its label above the slider; aim at the slider's 48 dp row.
        val after = valueAfter(100f, fold = true) { s -> press(s.right - thumbInset, s.left + s.width * 0.3f, s.bottom - 24 * density) }
        assertTrue("value stayed at $after", after < 50f)
    }

    @Test fun shortTapOnTheTrackJumpsTheValue() {
        val after = valueAfter(50f) { s -> press(s.left + s.width * 0.25f, s.left + s.width * 0.25f, s.center.y, steps = 0) }
        assertEquals(25f, after, 5f)
    }

    @Test fun shortTapAtTheEndOfTheTrackJumpsTheValue() {
        val after = valueAfter(50f) { s -> press(s.right - thumbInset, s.right - thumbInset, s.center.y, steps = 0) }
        assertTrue("value stayed at $after", after > 90f)
    }
}
