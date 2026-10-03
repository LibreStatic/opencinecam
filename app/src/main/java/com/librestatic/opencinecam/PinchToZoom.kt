package com.librestatic.opencinecam

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged

/**
 * Two-finger pinch: [onPinch] gets each step's scale change, with `first` set on the first step of
 * a pinch. Keyed only on [enabled]: a key that changed with the zoom itself would restart the
 * detector on every step and drop the pinch under the fingers. The pinch's moves are consumed, so
 * it never reaches tap-focus. For the same reason the first [onPinch] stays in use while enabled,
 * so it must read what changes through updated state (`rememberUpdatedState`), not capture values.
 */
internal fun Modifier.pinchToZoom(enabled: Boolean, onPinch: (first: Boolean, zoom: Float) -> Unit): Modifier =
    if (!enabled) this else pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var first = true
            do {
                val event = awaitPointerEvent()
                val zoom = event.calculateZoom()
                if (event.changes.count { it.pressed } >= 2 && zoom != 1f) {
                    onPinch(first, zoom)
                    first = false
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            } while (event.changes.any { it.pressed })
        }
    }
