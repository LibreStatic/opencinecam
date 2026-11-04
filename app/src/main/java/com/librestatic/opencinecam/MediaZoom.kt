/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** The review's pinch ceiling. */
internal const val MEDIA_MAX_ZOOM = 8f

/** Double-tap zoom level, matching Google Photos (measured 2.5x in both orientations). */
internal const val MEDIA_DOUBLE_TAP_ZOOM = 2.5f

/** Seconds for a fling's velocity to fall to 1/e; distance travelled is velocity × this. */
internal const val FLING_TIME_CONSTANT_SECONDS = 0.55f

/** Compose's base friction for [exponentialDecay] (velocity ∝ e^(-4.2 · multiplier · t)). */
private const val EXPONENTIAL_DECAY_FRICTION = 4.2f

/**
 * Animated zoom and pan, ported from Lightforge's viewer so both apps move alike.
 *
 * - Pinch and drag frames snap ([applyGesture]), so the content tracks the fingers exactly.
 * - Programmatic moves (double tap, settling back to 1x) run scale and both offsets on one shared
 *   FastOutSlowIn tween ([zoomTo]), Google Photos' measured double-tap curve.
 * - On release, [fling] continues at the finger's velocity and decays it exponentially (τ ≈ 0.55 s,
 *   measured on Google Photos). Each axis stops on its own at the pan bounds; there is no spring.
 *
 * The owner supplies [maxOffsets], so the same state serves fitted photos and plain box scaling.
 */
internal class ZoomPanState(
    density: Density,
    /** Max absolute offset on each axis for a given scale. A zero component locks that axis. */
    private val maxOffsets: (scale: Float) -> Offset,
) : Density by density {

    val scale = Animatable(1f)
    val offsetX = Animatable(0f)
    val offsetY = Animatable(0f)

    val currentScale: Float get() = scale.value
    val isZoomed: Boolean get() = scale.value > ZOOMED_THRESHOLD

    private var flingJob: Job? = null

    /** Bumped on every interruption so a stale completion skips its final snap. */
    private var transitionSession = 0

    /** Cancels any running transition or fling so a new gesture or animation starts clean. */
    suspend fun stopTransitions() {
        transitionSession++
        flingJob?.cancel()
        flingJob = null
        scale.stop()
        offsetX.stop()
        offsetY.stop()
    }

    /**
     * One transform-gesture frame: multiplicative zoom around [centroid] plus the pan delta, clamped
     * to [minScale]..[maxScale] and the pan bounds of the resulting scale.
     */
    suspend fun applyGesture(
        containerCenter: Offset,
        centroid: Offset,
        pan: Offset,
        zoomFactor: Float,
        minScale: Float,
        maxScale: Float,
    ) {
        stopTransitions()
        val oldScale = scale.value
        val newScale = (oldScale * zoomFactor).coerceIn(minScale, maxScale)
        val currentOffset = Offset(offsetX.value, offsetY.value)
        val focusCorrection = (centroid - containerCenter - currentOffset) * (1f - newScale / oldScale)
        scale.updateBounds(minScale, maxScale.coerceAtLeast(minScale))
        scale.snapTo(newScale)
        snapOffsetTo(currentOffset + pan + focusCorrection, newScale)
    }

    /**
     * Animates scale and offset on one eased tween to [targetScale], converging toward [focus] (the
     * double-tap position) when given. A target of 1f recentres the content.
     */
    suspend fun zoomTo(containerCenter: Offset, targetScale: Float, focus: Offset?, maxScale: Float) {
        stopTransitions()
        val session = transitionSession
        val oldScale = scale.value
        val currentOffset = Offset(offsetX.value, offsetY.value)
        val correction = focus?.let { (it - containerCenter - currentOffset) * (1f - targetScale / oldScale) } ?: Offset.Zero
        val target = if (targetScale == 1f) Offset.Zero else clamp(currentOffset + correction, targetScale)
        scale.updateBounds(1f, maxScale.coerceAtLeast(1f))
        // Stale offset bounds from an earlier scale would trap the tween; the final snap re-clamps.
        offsetX.updateBounds(null, null)
        offsetY.updateBounds(null, null)
        coroutineScope {
            launch { scale.animateTo(targetScale, ZOOM_TWEEN) }
            launch { offsetX.animateTo(target.x, ZOOM_TWEEN) }
            launch { offsetY.animateTo(target.y, ZOOM_TWEEN) }
        }
        // Land exactly on the target unless a newer gesture or transition took over meanwhile.
        if (session == transitionSession) {
            scale.snapTo(targetScale)
            snapOffsetTo(target, targetScale)
        }
    }

    /** An exponential decay per axis from the release [velocity], stopped by that axis' pan bounds. */
    fun fling(velocity: Offset, scope: CoroutineScope) {
        if (!isZoomed) return
        val bounds = maxOffsets(scale.value)
        if (bounds.x <= 0f && bounds.y <= 0f) return
        val minimumFling = 50.dp.toPx()
        if (abs(velocity.x) < minimumFling && abs(velocity.y) < minimumFling) return
        flingJob?.cancel()
        flingJob = scope.launch {
            offsetX.updateBounds(-bounds.x.coerceAtLeast(0f), bounds.x.coerceAtLeast(0f))
            offsetY.updateBounds(-bounds.y.coerceAtLeast(0f), bounds.y.coerceAtLeast(0f))
            coroutineScope {
                launch { offsetX.animateDecay(velocity.x, FLING_DECAY) }
                launch { offsetY.animateDecay(velocity.y, FLING_DECAY) }
            }
        }
    }

    private suspend fun snapOffsetTo(candidate: Offset, scaleValue: Float) {
        val bounds = maxOffsets(scaleValue)
        val maxX = bounds.x.coerceAtLeast(0f)
        val maxY = bounds.y.coerceAtLeast(0f)
        offsetX.updateBounds(-maxX, maxX)
        offsetY.updateBounds(-maxY, maxY)
        offsetX.snapTo(candidate.x.coerceIn(-maxX, maxX))
        offsetY.snapTo(candidate.y.coerceIn(-maxY, maxY))
    }

    private fun clamp(candidate: Offset, scaleValue: Float): Offset {
        val bounds = maxOffsets(scaleValue)
        return Offset(candidate.x.coerceIn(-bounds.x, bounds.x), candidate.y.coerceIn(-bounds.y, bounds.y))
    }

    companion object {
        const val ZOOMED_THRESHOLD = 1.01f

        /** Google Photos' double-tap zoom, measured frame by frame: FastOutSlowIn over ~290 ms, linear in scale. */
        private val ZOOM_TWEEN: TweenSpec<Float> = tween(durationMillis = 290, easing = FastOutSlowInEasing)

        /** Release-velocity decay with Google Photos' measured time constant. */
        val FLING_DECAY: DecayAnimationSpec<Float> =
            exponentialDecay(frictionMultiplier = 1f / (EXPONENTIAL_DECAY_FRICTION * FLING_TIME_CONSTANT_SECONDS))
    }
}

/**
 * Pan bounds for a picture fitted (FIT_CENTER) inside an [areaWidth] x [areaHeight] area and scaled by
 * [scale] around its centre: `max(0, (displayed − area) / 2)` per axis, so the picture's edge never
 * comes inside the area while it is larger, and an axis it does not overflow stays locked.
 */
internal fun mediaZoomBoundsFit(contentWidth: Float, contentHeight: Float, areaWidth: Float, areaHeight: Float,
    scale: Float): Offset {
    if (contentWidth <= 0f || contentHeight <= 0f || areaWidth <= 0f || areaHeight <= 0f) return Offset.Zero
    val fit = min(areaWidth / contentWidth, areaHeight / contentHeight)
    return Offset(max(0f, (contentWidth * fit * scale - areaWidth) / 2f), max(0f, (contentHeight * fit * scale - areaHeight) / 2f))
}

/** Pan bounds for a box scaled by [scale] around its centre inside itself: `size · (scale − 1) / 2` per axis. */
internal fun mediaZoomBoundsBox(width: Float, height: Float, scale: Float): Offset {
    val half = max(0f, scale - 1f) / 2f
    return Offset(max(0f, width) * half, max(0f, height) * half)
}

/**
 * The review stage's zoom: [pan] plus the sizes its bounds come from. [area] is the gesture area (whose
 * centre is the zoom's pivot); [content] is the picture's own pixel size when [fitContent] (a photo fitted
 * to the area), otherwise the box the picture fills (a video's fitted surface rectangle). The sizes are
 * plain fields: the owner keeps them current, and only gestures and animations read them.
 */
internal class MediaZoomState(density: Density) {
    var area: IntSize = IntSize.Zero
    var content: Size = Size.Zero
    var fitContent: Boolean = false
    val pan = ZoomPanState(density) { maxOffsets(it) }

    val center: Offset get() = Offset(area.width / 2f, area.height / 2f)

    fun maxOffsets(scale: Float): Offset =
        if (fitContent) mediaZoomBoundsFit(content.width, content.height, area.width.toFloat(), area.height.toFloat(), scale)
        else mediaZoomBoundsBox(content.width, content.height, scale)

    /** True while away from 1x or off centre, however slightly. */
    val moved: Boolean get() = pan.scale.value != 1f || pan.offsetX.value != 0f || pan.offsetY.value != 0f

    /** Animates back to 1x, centred; nothing when already there. */
    suspend fun reset(maxScale: Float = MEDIA_MAX_ZOOM) {
        if (moved) pan.zoomTo(center, 1f, null, maxScale)
    }

    /** Double tap: back to 1x when zoomed, else to [MEDIA_DOUBLE_TAP_ZOOM] (capped) toward [position]. */
    suspend fun toggle(position: Offset, maxScale: Float = MEDIA_MAX_ZOOM) {
        if (pan.isZoomed) pan.zoomTo(center, 1f, null, maxScale)
        else pan.zoomTo(center, min(MEDIA_DOUBLE_TAP_ZOOM, maxScale), position, maxScale)
    }
}

/** Draws the content with the zoom; the layer pivots on its own centre, which must be the area's. */
internal fun Modifier.mediaZoomLayer(state: MediaZoomState): Modifier = graphicsLayer {
    scaleX = state.pan.scale.value
    scaleY = state.pan.scale.value
    translationX = state.pan.offsetX.value
    translationY = state.pan.offsetY.value
}

/**
 * Pinch, pan and double tap for the review stage, plus [onTap] for a single tap. Put it on the parent of
 * the zoomed layers, so positions and velocities are in area pixels rather than zoomed ones.
 *
 * Two fingers transform at once; one finger pans only while zoomed, and only past the touch slop, so a
 * one-finger drag at 1x reaches an enclosing scroll. Everything is consumed while transforming. On
 * release a scale left within (1, 1.01] settles to 1x; otherwise the pan flings.
 *
 * Keyed only on [state], [scope], [enabled], [maxScale] and whether [onTap] is null: a key that changed
 * with the zoom itself would restart the detector and drop the pinch under the fingers (see
 * [pinchToZoom]). For the same reason the first [onTap] stays in use, so it must read what changes
 * through updated state (`rememberUpdatedState`). Without [enabled] only [onTap] remains.
 */
internal fun Modifier.mediaZoomGestures(state: MediaZoomState, scope: CoroutineScope, enabled: Boolean,
    onTap: (() -> Unit)?, maxScale: Float = MEDIA_MAX_ZOOM): Modifier {
    val taps = if (!enabled && onTap == null) this else pointerInput(state, scope, enabled, maxScale, onTap != null) {
        detectTapGestures(
            onTap = onTap?.let { tap -> { tap() } },
            onDoubleTap = if (enabled) { position -> scope.launch { state.toggle(position, maxScale) } } else null,
        )
    }
    // After the tap detector, so it is the inner one: it sees each Main pass first and its consumption cancels the tap.
    return if (!enabled) taps else taps.pointerInput(state, scope, maxScale) {
        val zoom = state.pan
        detectMediaTransformGestures(
            isZoomed = { zoom.isZoomed },
            onGestureStart = { scope.launch { zoom.stopTransitions() } },
            onGesture = { centroid, pan, factor ->
                scope.launch { zoom.applyGesture(state.center, centroid, pan, factor, 1f, maxScale) }
            },
            onGestureEnd = { velocity ->
                // Ended just above identity: settle to 1x instead of freezing at a sub-threshold zoom.
                if (!zoom.isZoomed && zoom.currentScale > 1f) scope.launch { zoom.zoomTo(state.center, 1f, null, maxScale) }
                else zoom.fling(velocity, scope)
            },
        )
    }
}

/**
 * Lightforge's viewer transform detector. A one-finger pan starts only when its down reached this node
 * unconsumed, so dragging a control drawn over the picture (the full-screen seek bar) never pans it.
 */
private suspend fun PointerInputScope.detectMediaTransformGestures(
    isZoomed: () -> Boolean,
    onGestureStart: () -> Unit,
    onGesture: (centroid: Offset, pan: Offset, zoom: Float) -> Unit,
    onGestureEnd: (velocity: Offset) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val childOwned = down.isConsumed
        var transforming = false
        var started = false
        var pendingSinglePointerPan = Offset.Zero
        var trackedPointerId: PointerId? = null
        val velocityTracker = VelocityTracker()
        do {
            val event = awaitPointerEvent()
            val pressedPointers = event.changes.count { it.pressed }
            val rawZoom = event.calculateZoom()
            val pan = event.calculatePan()
            var gesturePan = pan
            if (!transforming) {
                when {
                    pressedPointers >= 2 -> transforming = true
                    !childOwned && isZoomed() && pressedPointers == 1 -> {
                        pendingSinglePointerPan += pan
                        if (pendingSinglePointerPan.getDistance() > viewConfiguration.touchSlop) {
                            transforming = true
                            gesturePan = pendingSinglePointerPan
                        }
                    }
                }
            }
            if (transforming) {
                if (!started) {
                    started = true
                    onGestureStart()
                }
                // Feed the tracker from ONE stable pointer: when it lifts, stop feeding rather than jump
                // to another finger's position, which would fabricate a velocity spike.
                if (trackedPointerId == null) trackedPointerId = event.changes.firstOrNull { it.pressed }?.id
                event.changes.firstOrNull { it.id == trackedPointerId && it.pressed }
                    ?.let { change -> velocityTracker.addPosition(change.uptimeMillis, change.position) }
                if (rawZoom != 1f || gesturePan != Offset.Zero) onGesture(event.calculateCentroid(), gesturePan, rawZoom)
                event.changes.forEach { it.consume() }
            }
        } while (event.changes.any { it.pressed })
        if (started) {
            val velocity = velocityTracker.calculateVelocity()
            onGestureEnd(Offset(velocity.x, velocity.y))
        }
    }
}

/**
 * The quad a GL stage draws so its picture moves as a Compose `graphicsLayer` would move the whole
 * surface: [box] is the picture's viewport inside the surface (x, y, width, height, GL bottom-left
 * origin, as [com.librestatic.opencinecam.playback.RawDevelop.letterbox] gives it), [scale] pivots on
 * the surface centre and [offsetX]/[offsetY] are the pan as fractions of the surface's width and
 * height, y down as in Compose. Returns `(scaleX, scaleY, offsetX, offsetY)` in NDC for
 * `gl_Position = aPosition * q.xy + q.zw`, with the viewport covering the whole surface: the clip
 * volume crops what leaves it, so no viewport ever exceeds `GL_MAX_VIEWPORT_DIMS`.
 */
internal fun zoomedQuad(box: IntArray, surfaceWidth: Int, surfaceHeight: Int, scale: Float, offsetX: Float,
    offsetY: Float): FloatArray {
    if (surfaceWidth <= 0 || surfaceHeight <= 0) return floatArrayOf(0f, 0f, 0f, 0f)
    val width = surfaceWidth.toFloat()
    val height = surfaceHeight.toFloat()
    val boxScaleX = box[2] / width
    val boxScaleY = box[3] / height
    val boxCentreX = (box[0] + box[2] / 2f) / width * 2f - 1f
    val boxCentreY = (box[1] + box[3] / 2f) / height * 2f - 1f
    return floatArrayOf(boxScaleX * scale, boxScaleY * scale, boxCentreX * scale + 2f * offsetX, boxCentreY * scale - 2f * offsetY)
}
