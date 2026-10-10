/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.RoundedCornerShape
import com.librestatic.opencinecam.ui.theme.LocalReducedMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Stable
class PredictiveBackState {
    var progress by mutableFloatStateOf(0f)
        internal set
    var edge by mutableIntStateOf(BackEventCompat.EDGE_LEFT)
        internal set
    val gesturing: Boolean get() = progress > 0f
}

/** Material 3 full-screen back motion for one frame. */
internal data class PredictiveBackMotion(val scale: Float, val translationX: Float, val cornerRadius: Float)

internal fun predictiveBackMotion(progress: Float, edge: Int, widthPx: Float, density: Float): PredictiveBackMotion {
    val p = progress.coerceIn(0f, 1f)
    val maxShift = (widthPx / 20f - 8f * density).coerceAtLeast(0f)
    // Content moves away from the edge the swipe started on.
    val sign = if (edge == BackEventCompat.EDGE_RIGHT) -1f else 1f
    return PredictiveBackMotion(1f - 0.1f * p, sign * maxShift * p, 28f * density * p)
}

/**
 * Like BackHandler, plus a live [PredictiveBackState] while the system back gesture is in flight.
 * Without [animate] (or with reduced motion) the progress stays 0 and only the commit fires.
 */
@Composable
fun rememberPredictiveBack(enabled: Boolean, animate: Boolean = true, onBack: () -> Unit): PredictiveBackState {
    val state = remember { PredictiveBackState() }
    val scope = rememberCoroutineScope()
    val current = rememberUpdatedState(onBack)
    val animated = animate && !LocalReducedMotion.current
    val settle = remember { arrayOfNulls<Job>(1) }
    PredictiveBackHandler(enabled = enabled) { events ->
        settle[0]?.cancel()
        try {
            events.collect {
                if (animated) {
                    state.edge = it.swipeEdge
                    state.progress = it.progress.coerceIn(0f, 1f)
                }
            }
        } catch (e: CancellationException) {
            // This coroutine is cancelled with the gesture, so the release runs in the composition's scope.
            val from = state.progress
            if (from > 0f) settle[0] = scope.launch {
                animate(from, 0f, animationSpec = tween(180)) { v, _ -> state.progress = v }
            }
            throw e
        }
        // The destination replaces this screen, so the preview snaps instead of animating out.
        current.value()
        state.progress = 0f
    }
    LaunchedEffect(enabled) {
        if (!enabled) { settle[0]?.cancel(); state.progress = 0f }
    }
    return state
}

fun Modifier.predictiveBackPreview(state: PredictiveBackState): Modifier = graphicsLayer {
    val p = state.progress
    if (p > 0f) {
        val m = predictiveBackMotion(p, state.edge, size.width, density)
        scaleX = m.scale
        scaleY = m.scale
        translationX = m.translationX
        shape = RoundedCornerShape(m.cornerRadius.toDp())
        clip = true
    }
}
