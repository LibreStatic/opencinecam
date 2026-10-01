/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import com.librestatic.opencinecam.storage.PreciseLogView

/** Presentation preferences only: no autoplay, saved playback position or capture-audio control. */
data class PlaybackSettings(
    val muted: Boolean = false,
    val loop: Boolean = false,
    val showFramePosition: Boolean = true,
    /** OCLog2 clips review as the flat monitor unless the operator asks for the Rec.709 view assist. */
    val logView: PreciseLogView = PreciseLogView.FLAT_LOG,
)
