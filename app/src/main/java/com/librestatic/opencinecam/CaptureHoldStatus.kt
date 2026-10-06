/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.librestatic.opencinecam.ui.theme.LocalReducedMotion
import com.librestatic.opencinecam.ui.viewfinder.chromePanel
import kotlinx.coroutines.delay

/** Why the capture controls are held: the camera is still working on a still, or the operator locked them for the take. */
internal enum class CaptureHold { BUSY, OPERATOR }

private val BusyStillModes = setOf(CaptureMode.PHOTO, CaptureMode.RAW_PHOTO, CaptureMode.BURST, CaptureMode.BRACKET, CaptureMode.LIGHT_TRAIL)

internal val CameraUiState.captureHold: CaptureHold?
    get() = when {
        !captureControlsLocked -> null
        stillCapturePending || (phase == CameraUiPhase.CAPTURING && selectedMode in BusyStillModes) -> CaptureHold.BUSY
        else -> CaptureHold.OPERATOR
    }

private val HoldShape = RoundedCornerShape(10.dp)

/** How the reason is drawn: a two-line strip in place of the slot strip, or a pill in place of the mode button. */
internal enum class HoldStyle { STRIP, PILL }

/** The hold to show: a save shorter than [BUSY_REVEAL_DELAY_MS] never shows one, so quick stills do not flicker. */
@Composable
private fun rememberShownHold(state: CameraUiState): CaptureHold? {
    val hold = state.captureHold
    var shown by remember { mutableStateOf(hold) }
    LaunchedEffect(hold) {
        if (hold == CaptureHold.BUSY && shown == null) delay(BUSY_REVEAL_DELAY_MS)
        shown = hold
    }
    return shown
}

/**
 * [content], or in its place, while the controls are held, a card that says why. The content stays
 * laid out underneath, only faded and hidden from touch and accessibility, so the card has exactly
 * its size and nothing around it moves.
 */
@Composable
internal fun CaptureHeldSlots(
    state: CameraUiState,
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
    style: HoldStyle = HoldStyle.STRIP,
    content: @Composable () -> Unit,
) {
    val shown = rememberShownHold(state)
    val reducedMotion = LocalReducedMotion.current
    val contentAlpha by animateFloatAsState(if (shown == null) 1f else 0f, tween(if (reducedMotion) 0 else 160), label = "heldAlpha")
    val title = stringResource(holdTitle(state, style))
    Box(modifier) {
        Box(
            Modifier
                .graphicsLayer { alpha = contentAlpha }
                .then(if (shown != null) Modifier.clearAndSetSemantics { } else Modifier),
        ) { content() }
        AnimatedContent(
            targetState = shown,
            modifier = Modifier.matchParentSize(),
            transitionSpec = {
                if (reducedMotion) fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                else fadeIn(tween(220, delayMillis = 60)) togetherWith fadeOut(tween(140))
            },
            label = "captureHold",
        ) { target ->
            when {
                target == null -> Unit
                style == HoldStyle.PILL -> CaptureHoldPill(target, title, onUnlock, Modifier.fillMaxSize())
                else -> CaptureHoldStrip(target, title, onUnlock, Modifier.fillMaxSize())
            }
        }
    }
}

/**
 * The side rails' slot column while held: still readable, since the values are worth seeing during a
 * save, but dimmed and inert. The reason sits in the mode button's place ([HoldStyle.PILL]).
 */
@Composable
internal fun CaptureDimmedWhileHeld(state: CameraUiState, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val held = rememberShownHold(state) != null
    val alpha by animateFloatAsState(if (held) .38f else 1f, tween(if (LocalReducedMotion.current) 0 else 160), label = "dimmedAlpha")
    Box(
        modifier
            .graphicsLayer { this.alpha = alpha }
            .then(
                if (held) Modifier
                    .pointerInput(Unit) {
                        awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() } }
                    }
                    .clearAndSetSemantics { }
                else Modifier,
            ),
    ) { content() }
}

private const val BUSY_REVEAL_DELAY_MS = 150L

private fun holdTitle(state: CameraUiState, style: HoldStyle): Int = when (state.captureHold) {
    CaptureHold.OPERATOR -> if (style == HoldStyle.PILL) R.string.capture_unlock else R.string.capture_hold_operator_title
    else -> when (state.selectedMode) {
        CaptureMode.BURST -> if (style == HoldStyle.PILL) R.string.capture_hold_short_burst else R.string.capture_hold_busy_burst
        CaptureMode.BRACKET -> if (style == HoldStyle.PILL) R.string.capture_hold_short_bracket else R.string.capture_hold_busy_bracket
        CaptureMode.LIGHT_TRAIL -> if (style == HoldStyle.PILL) R.string.capture_hold_short_light_trail else R.string.capture_hold_busy_light_trail
        else -> if (style == HoldStyle.PILL) R.string.capture_hold_short_saving else R.string.capture_hold_busy_photo
    }
}

/** The faded content underneath must not take a tap meant for nothing. */
private fun Modifier.swallowTouches(): Modifier =
    pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } }

@Composable
private fun HoldIndicator(busy: Boolean, size: androidx.compose.ui.unit.Dp) {
    val accent = MaterialTheme.colorScheme.primary
    if (!busy) CineGlyph(CineIcon.LOCK, accent, Modifier.size(size))
    else if (LocalReducedMotion.current) Box(Modifier.size(size * .55f).clip(RoundedCornerShape(50)).background(accent))
    else CircularProgressIndicator(color = accent, trackColor = Color.Transparent, strokeWidth = 2.dp, modifier = Modifier.size(size))
}

@Composable
private fun CaptureHoldStrip(hold: CaptureHold, title: String, onUnlock: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val busy = hold == CaptureHold.BUSY
    val hint = stringResource(if (busy) R.string.capture_hold_busy_hint else R.string.capture_hold_operator_hint)
    val accent = colors.primary
    Box(
        modifier
            .clip(HoldShape)
            .background(colors.surfaceContainerHigh.chromePanel())
            .border(1.dp, accent.copy(alpha = .35f), HoldShape)
            .swallowTouches()
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
            .testTag(if (busy) "capture-hold-busy" else "capture-hold-operator"),
    ) {
        Row(
            Modifier.fillMaxSize().padding(start = 14.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HoldIndicator(busy, 18.dp)
            Column(Modifier.weight(1f)) {
                Text(title, color = colors.onSurface, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(hint, color = colors.onSurfaceVariant, fontSize = 12.sp, lineHeight = 15.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!busy) FilledTonalButton(
                onClick = onUnlock,
                contentPadding = PaddingValues(horizontal = 12.dp),
                modifier = Modifier.heightIn(min = 40.dp).testTag("operator-unlock"),
            ) { Text(stringResource(R.string.capture_unlock), fontSize = 13.sp, maxLines = 1) }
        }
        if (busy && !LocalReducedMotion.current) LinearProgressIndicator(
            color = accent,
            trackColor = Color.Transparent,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp),
        )
    }
}

/**
 * The reason in the mode button's own shape: a small caption over one short value, the way the
 * button reads "MODE / Photo". Locked by the operator, the whole pill is the Unlock button.
 */
@Composable
private fun CaptureHoldPill(hold: CaptureHold, title: String, onUnlock: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val busy = hold == CaptureHold.BUSY
    val accent = colors.primary
    val shape = RoundedCornerShape(12.dp)
    val caption = stringResource(if (busy) R.string.capture_hold_caption_busy else R.string.capture_hold_caption_locked)
    // TalkBack reads the full sentence, not the abbreviated pill.
    val spoken = stringResource(if (busy) R.string.capture_hold_busy_hint else R.string.operator_locked)
    Column(
        modifier
            .clip(shape)
            .background(colors.surfaceContainerHigh.chromePanel())
            .border(1.dp, accent.copy(alpha = if (busy) .35f else .7f), shape)
            .then(
                if (busy) Modifier.swallowTouches().semantics(mergeDescendants = true) {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = "$title $spoken"
                } else Modifier.clickable(role = Role.Button, onClick = onUnlock).semantics(mergeDescendants = true) {
                    contentDescription = "$spoken $title"
                },
            )
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .testTag(if (busy) "capture-hold-busy" else "operator-unlock"),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            HoldIndicator(busy, 11.dp)
            Text(caption.uppercase(), color = colors.onSurfaceVariant, fontSize = 11.sp, lineHeight = 12.sp,
                fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(title, color = accent, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
