/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.annotation.IntRange
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/**
 * The only Material3 [Slider] the app uses.
 *
 * With gesture navigation, a slider's ends sit inside the system back-gesture insets (30 dp on
 * the Pixel 9 Pro and Razr Fold). When the value is at the minimum or maximum, the thumb is
 * there too. A drag that starts on it is stolen by the edge-swipe gesture monitor, so the slider
 * looks stuck and the activity may go back. Excluding the slider's bounds keeps the drag in the
 * app. Android honours at most 200 dp of exclusions per edge, so this covers ~4 full-width
 * sliders on screen at once; anything beyond falls back to the old behaviour, never worse.
 * Guarded by `SliderEdgeDragTest` and `CineSliderSourceTest`.
 */
@Composable
internal fun CineSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
    colors: SliderColors = SliderDefaults.colors(),
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    @IntRange(from = 0) steps: Int = 0,
    thumb: @Composable (SliderState) -> Unit = {
        SliderDefaults.Thumb(interactionSource = interactionSource, colors = colors, enabled = enabled)
    },
    track: @Composable (SliderState) -> Unit = { sliderState ->
        SliderDefaults.Track(colors = colors, enabled = enabled, sliderState = sliderState)
    },
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
) = Slider(
    value = value,
    onValueChange = onValueChange,
    modifier = modifier.systemGestureExclusion(),
    enabled = enabled,
    onValueChangeFinished = onValueChangeFinished,
    colors = colors,
    interactionSource = interactionSource,
    steps = steps,
    thumb = thumb,
    track = track,
    valueRange = valueRange,
)
