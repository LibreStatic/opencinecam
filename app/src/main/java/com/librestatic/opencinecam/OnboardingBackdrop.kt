/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.librestatic.opencinecam.ui.theme.LocalCineColors
import com.librestatic.opencinecam.ui.theme.LocalReducedMotion
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sign
import kotlin.math.sin
import kotlin.random.Random

/** Backdrop widths the shapes travel per wizard step, scaled by their depth (parallax). */
private const val MoveLength = 0.55f

/** Horizontal band the shapes wrap around in, in backdrop widths; wider than the screen so none pop in. */
private const val WrapSpan = 1.5f

/** Degrees a shape rolls per step, as if it rolled along the rail. */
private const val MoveRoll = 90f

/** Opacity of the nearest shapes; the farthest get half of the extra, so the range is 0.12 to 0.24. */
private const val BaseAlpha = 0.12f

private fun phase(value: Float, start: Float, end: Float): Float = ((value - start) / (end - start)).coerceIn(0f, 1f)

/** Photo and video motifs: lens, iris, diaphragm, REC, play, frame, exposure, flash, flare, sensor. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private val BackdropShapes = listOf(
    MaterialShapes.Circle,
    MaterialShapes.Cookie6Sided,
    MaterialShapes.Pentagon,
    MaterialShapes.Pill,
    MaterialShapes.Triangle,
    MaterialShapes.Square,
    MaterialShapes.Sunny,
    MaterialShapes.Burst,
    MaterialShapes.Oval,
    MaterialShapes.PixelCircle,
)

private class BackdropShape(
    val outline: Outline,
    val side: Float,
    val depth: Float,
    val x: Float,
    val y: Float,
    val spin: Float,
    val phase: Float,
    val wobble: Float,
    val role: Int,
)

/**
 * The wizard background: photo and video shapes drifting on one Canvas, with nothing drawn over
 * them. Every animated value is read inside the draw lambda only, so the motion never recomposes.
 *
 * - [progress] is the rail position in steps: the shapes slide with parallax and roll towards it.
 * - [beat] changes on every step: each change gives the shapes a small radial push.
 * - [intro] runs 0→1 once after the splash: the splash logo, drawn at [logoBounds] (window pixels),
 *   takes the theme colours and scatters while the shapes emerge from the centre.
 * - [dispersed] sends the shapes outwards and fades them; [onDispersed] runs when that ends.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun OnboardingBackdrop(
    progress: () -> Float,
    beat: () -> Int,
    intro: () -> Float,
    logoBounds: Rect?,
    dispersed: Boolean,
    onDispersed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reducedMotion = LocalReducedMotion.current
    val colors = MaterialTheme.colorScheme
    val semantic = LocalCineColors.current
    val palette = listOf(colors.primaryContainer, colors.secondaryContainer, colors.tertiaryContainer)
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val seed = rememberSaveable { Random.nextInt() }

    val currentProgress by rememberUpdatedState(progress)
    val currentBeat by rememberUpdatedState(beat)
    val finished by rememberUpdatedState(onDispersed)

    // A threshold far below the default: the rail is multiplied by several screen widths, and the
    // default 0.01 left a last jump of a few pixels when the spring settled.
    val rail = remember { Animatable(currentProgress(), visibilityThreshold = 0.0001f) }
    val burst = remember { Animatable(0f, visibilityThreshold = 0.0005f) }
    val exit = remember { Animatable(0f, visibilityThreshold = 0.0005f) }
    val time = remember { mutableFloatStateOf(0f) }
    val origin = remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(reducedMotion) {
        // collectLatest retargets the running spring and keeps its velocity, so quick taps chain smoothly.
        snapshotFlow { currentProgress() }.collectLatest { target ->
            if (reducedMotion) {
                rail.snapTo(target)
            } else {
                rail.animateTo(target, spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessLow, visibilityThreshold = 0.0001f))
            }
        }
    }
    LaunchedEffect(reducedMotion) {
        if (reducedMotion) return@LaunchedEffect
        // An impulse from rest: it swells, overshoots a little and settles, never jumps.
        snapshotFlow { currentBeat() }.distinctUntilChanged().drop(1).collectLatest {
            burst.animateTo(0f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessLow, visibilityThreshold = 0.0005f), initialVelocity = 4f)
        }
    }
    LaunchedEffect(reducedMotion) {
        if (reducedMotion) return@LaunchedEffect
        val start = withFrameNanos { it }
        while (true) withFrameNanos { time.floatValue = (it - start) / 1_000_000_000f }
    }
    LaunchedEffect(dispersed, reducedMotion) {
        val target = if (dispersed) 1f else 0f
        if (reducedMotion) exit.snapTo(target) else exit.animateTo(target, tween(800, easing = FastOutSlowInEasing))
        if (dispersed) finished()
    }

    BoxWithConstraints(modifier.clearAndSetSemantics {}.onGloballyPositioned { origin.value = it.positionInWindow() }) {
        val count = when (windowWidthClass(maxWidth.value)) {
            WindowWidthClass.COMPACT -> 6
            WindowWidthClass.MEDIUM -> 12
            else -> 16
        }
        // toShape is composable (it remembers its own conversion); the outlines are built from these once.
        val catalogue = BackdropShapes.map { it.toShape() }
        val shapes = remember(seed, count, density, layoutDirection) {
            val random = Random(seed)
            List(count) { index ->
                val depth = 0.4f + random.nextFloat() * 0.6f
                val side = with(density) { (40.dp + 120.dp * depth).toPx() }
                // createOutline is costly and the size never changes, so each outline is built once.
                val outline = catalogue[(index + random.nextInt(catalogue.size)) % catalogue.size]
                    .createOutline(Size(side, side), layoutDirection, density)
                BackdropShape(
                    outline = outline,
                    side = side,
                    depth = depth,
                    x = random.nextFloat() * WrapSpan,
                    y = random.nextFloat(),
                    spin = (if (random.nextBoolean()) 1f else -1f) * (6f + random.nextFloat() * 14f),
                    phase = random.nextFloat() * 2f * PI.toFloat(),
                    wobble = with(density) { (8.dp + 16.dp * random.nextFloat()).toPx() },
                    role = index % 3,
                )
            }
        }
        val burstPush = with(density) { 40.dp.toPx() }
        // The splash icon view without an icon background, used until the real bounds arrive.
        val fallbackLogo = with(density) { 192.dp.toPx() }
        Canvas(Modifier.fillMaxSize()) {
            val railValue = rail.value
            val b = burst.value
            val e = exit.value
            val t = time.floatValue
            val i = intro()
            val emerge = FastOutSlowInEasing.transform(phase(i, 0.3f, 1f))
            val center = Offset(size.width / 2f, size.height / 2f)
            val diagonal = hypot(size.width, size.height)
            shapes.forEach { s ->
                val along = ((s.x - railValue * MoveLength * s.depth) % WrapSpan + WrapSpan) % WrapSpan
                var c = Offset(
                    (along - (WrapSpan - 1f) / 2f) * size.width + sin(t * 0.35f + s.phase) * s.wobble,
                    s.y * size.height + cos(t * 0.27f + s.phase * 1.3f) * s.wobble,
                )
                val away = c - center
                val distance = hypot(away.x, away.y).coerceAtLeast(1f)
                c += away / distance * (b * burstPush * s.depth + e * diagonal * (0.6f + s.depth))
                if (emerge < 1f) c = center + (c - center) * emerge
                val scale = (1f + 0.15f * b + 0.4f * e) * emerge
                if (scale <= 0f) return@forEach
                val roll = railValue * MoveRoll * s.depth * sign(s.spin)
                val angle = s.phase * 57.3f + t * s.spin + roll + e * s.spin * 12f + (1f - emerge) * s.spin * 10f
                val alpha = (BaseAlpha + BaseAlpha * s.depth) * (1f - e) * emerge
                val pivot = Offset(s.side / 2f, s.side / 2f)
                translate(c.x - s.side / 2f, c.y - s.side / 2f) {
                    scale(scale, pivot) {
                        rotate(angle, pivot) { drawOutline(s.outline, palette[s.role], alpha = alpha) }
                    }
                }
            }
            if (i < 1f) {
                val anchor = logoBounds?.translate(-origin.value)
                val logoCenter = anchor?.center ?: center
                val side = (anchor?.width ?: fallbackLogo) * SplashIconViewportScale
                drawLogo(i, logoCenter, side, diagonal, colors.primary, colors.secondary, colors.outline, semantic.record, colors.background)
            }
        }
    }
}

/**
 * The splash logo at the same place and size, from [intro] 0 to 1: a short pop, the brand colours
 * turning into the theme roles, then every piece leaving along its own direction while it fades.
 */
private fun DrawScope.drawLogo(
    intro: Float,
    logoCenter: Offset,
    side: Float,
    diagonal: Float,
    primary: Color,
    secondary: Color,
    outline: Color,
    record: Color,
    separator: Color,
) {
    val pop = 1f + 0.08f * sin(PI.toFloat() * phase(intro, 0f, 0.3f))
    val tint = FastOutSlowInEasing.transform(phase(intro, 0.06f, 0.3f))
    val scatter = FastOutLinearInEasing.transform(phase(intro, 0.25f, 0.85f))
    val fade = 1f - phase(intro, 0.35f, 0.8f)
    if (fade <= 0f) return
    val travel = diagonal * 0.3f
    val unit = side / LogoViewport * LogoSplashScale
    val mid = LogoViewport / 2f
    LogoArt.pieces.forEach { piece ->
        val target = when (piece.role) {
            LogoRole.PRIMARY -> primary
            LogoRole.SECONDARY -> secondary
            LogoRole.OUTLINE -> outline
            LogoRole.RECORD -> record
        }
        val color = lerp(piece.brand, target, tint)
        val offset = logoCenter + piece.direction * travel * scatter
        withTransform({
            translate(offset.x, offset.y)
            scale(pop * (1f - 0.3f * scatter) * unit, pop * (1f - 0.3f * scatter) * unit, Offset.Zero)
            rotate(piece.spin * scatter, Offset.Zero)
            translate(-mid, -mid)
        }) {
            if (piece.stroke != null) {
                drawPath(piece.path, color, alpha = fade, style = Stroke(piece.stroke, cap = StrokeCap.Butt))
            } else {
                drawPath(piece.path, color, alpha = fade)
                if (piece.blade != null && piece.aperture != null) {
                    clipPath(piece.aperture) {
                        drawPath(
                            piece.blade, lerp(LogoArt.BrandSeparator, separator, tint), alpha = fade,
                            style = Stroke(LogoArt.SeparatorStroke, join = StrokeJoin.Miter),
                        )
                    }
                }
            }
        }
    }
}
