/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

enum class HapticLevel { OFF, SUBTLE, FULL }
enum class HapticCategory { CAPTURE, CONTROLS, FOCUS_EXPOSURE, NAVIGATION }

/** EVENT haptics mark a result; TICK haptics are the continuous, frequent ones only Full allows. */
enum class HapticWeight { EVENT, TICK }

data class HapticPreferences(
    val level: HapticLevel = HapticLevel.SUBTLE,
    val capture: Boolean = true,
    val controls: Boolean = true,
    val focusExposure: Boolean = true,
    val navigation: Boolean = true,
    val muteDuringTake: Boolean = true,
) {
    fun enabled(category: HapticCategory): Boolean = when (category) {
        HapticCategory.CAPTURE -> capture
        HapticCategory.CONTROLS -> controls
        HapticCategory.FOCUS_EXPOSURE -> focusExposure
        HapticCategory.NAVIGATION -> navigation
    }
}

/**
 * Pure gate. OFF and disabled categories never fire; Subtle drops ticks. While recording with
 * [HapticPreferences.muteDuringTake], only capture events (stop, pause) pass.
 */
fun HapticPreferences.allows(category: HapticCategory, weight: HapticWeight, recording: Boolean): Boolean {
    if (level == HapticLevel.OFF || !enabled(category)) return false
    if (level == HapticLevel.SUBTLE && weight == HapticWeight.TICK) return false
    if (recording && muteDuringTake && (category != HapticCategory.CAPTURE || weight == HapticWeight.TICK)) return false
    return true
}

fun HapticPreferences.allows(event: HapticEvent, recording: Boolean): Boolean =
    allows(event.category, event.weight, recording)
