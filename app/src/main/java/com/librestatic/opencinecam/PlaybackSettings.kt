/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

/** Presentation preferences only: no autoplay, saved playback position or capture-audio control. */
data class PlaybackSettings(
    val muted: Boolean = false,
    val loop: Boolean = false,
    val showFramePosition: Boolean = true,
)
