/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.foundation.basicMarquee
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.librestatic.opencinecam.ui.theme.LocalReducedMotion

/**
 * Whether single-line texts that do not fit scroll as a marquee ([CameraSettings.scrollLongText]).
 * False by default, so a composition without the app root (tests, Compose Driver) keeps the
 * ellipsis: the marquee animates forever and would never let a test clock go idle.
 */
val LocalScrollLongText = staticCompositionLocalOf { false }

/** The marquee runs only when the operator wants it and the system animations are on. */
internal fun marqueeActive(setting: Boolean, reducedMotion: Boolean): Boolean = setting && !reducedMotion

/** How [FitText] shows its text. */
internal enum class FitTextMode {
    /** Wrapped over up to maxLines, which it fits in. */
    WRAP,

    /** One line that scrolls when it does not fit. */
    MARQUEE,

    /** Up to maxLines, cut with an ellipsis when it does not fit (the behaviour with the marquee off). */
    ELLIPSIS,
}

/**
 * The [FitText] decision: with the marquee off, the ellipsis; one line, the marquee (it only moves
 * when the text overflows); more lines, the wrap while the text fits in them, else one marquee line.
 */
internal fun fitTextMode(fitsInMaxLines: Boolean, maxLines: Int, marquee: Boolean): FitTextMode = when {
    !marquee -> FitTextMode.ELLIPSIS
    maxLines <= 1 -> FitTextMode.MARQUEE
    fitsInMaxLines -> FitTextMode.WRAP
    else -> FitTextMode.MARQUEE
}

/** Pause before the first pass and between passes, so a reading starts at the beginning. */
private const val MARQUEE_DELAY_MILLIS = 1_500

/** Slow enough to read on a moving camera. */
private val MARQUEE_VELOCITY = 30.dp

/** Whether the marquee runs in this composition: [LocalScrollLongText] on and motion not reduced. */
@Composable
internal fun rememberMarqueeActive(): Boolean = marqueeActive(LocalScrollLongText.current, LocalReducedMotion.current)

/**
 * Scrolls the content horizontally when it is wider than the space it gets, after a short pause,
 * while [LocalScrollLongText] is on and the system animations are not off; otherwise nothing.
 * The content must be one unwrapped line (for a [Text], `softWrap = false`). The content keeps the
 * minimum width it is given, so a centred text in a `fillMaxWidth` slot stays centred while it fits.
 * [enabled] false keeps it still, e.g. for the rows of a list other than the selected one.
 */
@Composable
fun Modifier.cineMarquee(enabled: Boolean = true): Modifier = if (rememberMarqueeActive() && enabled) {
    basicMarquee(
        iterations = Int.MAX_VALUE,
        initialDelayMillis = MARQUEE_DELAY_MILLIS,
        repeatDelayMillis = MARQUEE_DELAY_MILLIS,
        velocity = MARQUEE_VELOCITY,
    )
} else this

/** [FitText] for a plain string. */
@Composable
fun FitText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    lineHeight: TextUnit = TextUnit.Unspecified,
    textAlign: TextAlign? = null,
    maxLines: Int = 1,
    style: TextStyle = LocalTextStyle.current,
    scroll: Boolean = true,
) = FitText(AnnotatedString(text), modifier, color, fontSize, fontWeight, fontFamily, letterSpacing, lineHeight, textAlign, maxLines, style, scroll)

/**
 * A [Text] for a slot of up to [maxLines] lines that never ends in an ellipsis while the marquee
 * is on ([rememberMarqueeActive]): one line scrolls when it overflows; more lines wrap while the
 * text fits in them and otherwise become one scrolling line. With the marquee off it is the plain
 * `Text(maxLines = maxLines, overflow = Ellipsis)`, and so is it with [scroll] false: in a list only
 * the selected row may move, so the others pass false.
 */
@Composable
fun FitText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    lineHeight: TextUnit = TextUnit.Unspecified,
    textAlign: TextAlign? = null,
    maxLines: Int = 1,
    style: TextStyle = LocalTextStyle.current,
    scroll: Boolean = true,
) {
    val marquee = rememberMarqueeActive() && scroll
    @Composable
    fun Shown(mode: FitTextMode, textModifier: Modifier) = when (mode) {
        FitTextMode.ELLIPSIS -> Text(text, textModifier, color = color, fontSize = fontSize, fontWeight = fontWeight,
            fontFamily = fontFamily, letterSpacing = letterSpacing, lineHeight = lineHeight, textAlign = textAlign,
            maxLines = maxLines, overflow = TextOverflow.Ellipsis, style = style)
        FitTextMode.WRAP -> Text(text, textModifier, color = color, fontSize = fontSize, fontWeight = fontWeight,
            fontFamily = fontFamily, letterSpacing = letterSpacing, lineHeight = lineHeight, textAlign = textAlign,
            maxLines = maxLines, overflow = TextOverflow.Clip, style = style)
        FitTextMode.MARQUEE -> Text(text, textModifier.cineMarquee(), color = color, fontSize = fontSize,
            fontWeight = fontWeight, fontFamily = fontFamily, letterSpacing = letterSpacing, lineHeight = lineHeight,
            textAlign = textAlign, softWrap = false, maxLines = 1, overflow = TextOverflow.Clip, style = style)
    }
    if (!marquee || maxLines <= 1) {
        // Nothing to measure: the ellipsis as today, or a single marquee line.
        Shown(fitTextMode(fitsInMaxLines = false, maxLines = maxLines, marquee = marquee), modifier)
        return
    }
    val measurer = rememberTextMeasurer()
    val measuredStyle = style.merge(TextStyle(color = color, fontSize = fontSize, fontWeight = fontWeight,
        fontFamily = fontFamily, letterSpacing = letterSpacing, lineHeight = lineHeight,
        textAlign = textAlign ?: TextAlign.Unspecified))
    // The width the text gets, read at layout by a probe right after the caller's modifier. No
    // wrapper box: the caller's tag and semantics stay on the text node itself. Until the first
    // layout (and whenever the width is unbounded) the text counts as fitting, so it wraps.
    var availableWidth by remember { mutableIntStateOf(Constraints.Infinity) }
    val fits = availableWidth == Constraints.Infinity || !measurer.measure(text, measuredStyle,
        overflow = TextOverflow.Clip, softWrap = true, maxLines = maxLines,
        constraints = Constraints(maxWidth = availableWidth)).hasVisualOverflow
    val probe = Modifier.layout { measurable, constraints ->
        if (constraints.maxWidth != availableWidth) availableWidth = constraints.maxWidth
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    Shown(fitTextMode(fits, maxLines, marquee), modifier.then(probe))
}
