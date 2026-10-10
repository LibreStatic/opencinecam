/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/** Every haptic the UI can play; the category and weight feed [allows], the type is the platform effect. */
enum class HapticEvent(val category: HapticCategory, val weight: HapticWeight, val type: HapticFeedbackType) {
    RecordStart(HapticCategory.CAPTURE, HapticWeight.EVENT, HapticFeedbackType.Confirm),
    RecordStop(HapticCategory.CAPTURE, HapticWeight.EVENT, HapticFeedbackType.Confirm),
    Shutter(HapticCategory.CAPTURE, HapticWeight.EVENT, HapticFeedbackType.Confirm),
    PauseToggle(HapticCategory.CAPTURE, HapticWeight.EVENT, HapticFeedbackType.ToggleOn),
    CountdownTick(HapticCategory.CAPTURE, HapticWeight.TICK, HapticFeedbackType.SegmentTick),
    Rejected(HapticCategory.CAPTURE, HapticWeight.EVENT, HapticFeedbackType.Reject),
    SliderStep(HapticCategory.CONTROLS, HapticWeight.TICK, HapticFeedbackType.SegmentFrequentTick),
    Limit(HapticCategory.CONTROLS, HapticWeight.EVENT, HapticFeedbackType.GestureThresholdActivate),
    ZoomAnchor(HapticCategory.CONTROLS, HapticWeight.TICK, HapticFeedbackType.SegmentTick),
    LockOn(HapticCategory.FOCUS_EXPOSURE, HapticWeight.EVENT, HapticFeedbackType.ToggleOn),
    LockOff(HapticCategory.FOCUS_EXPOSURE, HapticWeight.EVENT, HapticFeedbackType.ToggleOff),
    FocusConfirmed(HapticCategory.FOCUS_EXPOSURE, HapticWeight.EVENT, HapticFeedbackType.Confirm),
    FocusFailed(HapticCategory.FOCUS_EXPOSURE, HapticWeight.EVENT, HapticFeedbackType.Reject),
    FocusMark(HapticCategory.FOCUS_EXPOSURE, HapticWeight.EVENT, HapticFeedbackType.LongPress),
    // The mode wheel's detent: the softest effect, kept at Subtle because it is the dial's whole feel.
    ModeTick(HapticCategory.NAVIGATION, HapticWeight.EVENT, HapticFeedbackType.SegmentFrequentTick),
    Toggle(HapticCategory.NAVIGATION, HapticWeight.TICK, HapticFeedbackType.ToggleOn),
    Select(HapticCategory.NAVIGATION, HapticWeight.TICK, HapticFeedbackType.ContextClick),
}

class AppHaptics(
    private val preferences: HapticPreferences,
    private val recording: Boolean,
    private val platform: HapticFeedback?,
) {
    fun perform(event: HapticEvent) {
        if (preferences.allows(event, recording)) platform?.performHapticFeedback(event.type)
    }

    /** Settings sample: ignores the category and mute gates, only the master level applies. */
    fun preview(level: HapticLevel = preferences.level) {
        if (level != HapticLevel.OFF) platform?.performHapticFeedback(HapticFeedbackType.Confirm)
    }

    companion object {
        val Disabled = AppHaptics(HapticPreferences(level = HapticLevel.OFF), recording = false, platform = null)
    }
}

val LocalAppHaptics = staticCompositionLocalOf { AppHaptics.Disabled }

/** [onClick] preceded by [event]; for call sites that only need a click wrapper. */
@Composable
fun hapticClick(event: HapticEvent, onClick: () -> Unit): () -> Unit {
    val haptics = LocalAppHaptics.current
    return { haptics.perform(event); onClick() }
}

@Composable
fun rememberAppHaptics(preferences: HapticPreferences, recording: Boolean): AppHaptics {
    val platform = LocalHapticFeedback.current
    return remember(preferences, recording, platform) { AppHaptics(preferences, recording, platform) }
}
