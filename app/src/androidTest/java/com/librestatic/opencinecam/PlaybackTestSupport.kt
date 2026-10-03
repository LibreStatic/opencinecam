/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.content.Context
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.librestatic.opencinecam.playback.playbackSeconds

/*
 * The review keeps only the picture, timeline and transport on the player; the take facts, signal notes and
 * playback settings live in the details (a sheet on portrait phones, a column on landscape phones, an
 * inspector on large windows). These helpers open and close them the way an operator does.
 */

private fun SemanticsNodeInteractionsProvider.present(tag: String) =
    onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

/** Opens the take details unless they are already on screen (the inspector keeps them open). */
fun SemanticsNodeInteractionsProvider.openPlaybackDetails() {
    if (!present("media-playback-details")) onNodeWithTag("media-playback-info", useUnmergedTree = true).performScrollTo().performClick()
}

/** Closes the details sheet or column so the controls beneath it take touches again. */
fun SemanticsNodeInteractionsProvider.closePlaybackDetails() {
    if (present("media-playback-details-close")) onNodeWithTag("media-playback-details-close", useUnmergedTree = true).performClick()
}

/** The short counter on the player, e.g. "2 / 4". */
fun exactFrameCounter(context: Context, number: Int, total: Int): String =
    context.getString(R.string.playback_screen_frame_counter, number, total)

/** The full sentence the counter speaks and the details show, with the frame's own time in seconds. */
fun exactFrameSentence(context: Context, number: Int, total: Int, ptsUs: Long): String =
    context.getString(R.string.media_playback_exact, number, total, playbackSeconds(ptsUs, context.resources.configuration.locales[0]))

/** The counter shows [number] of [total] and names the stored presentation time [ptsUs] of that frame. */
fun SemanticsNodeInteraction.assertExactFrame(context: Context, number: Int, total: Int, ptsUs: Long): SemanticsNodeInteraction =
    assertTextEquals(exactFrameCounter(context, number, total)).assert(hasContentDescription(exactFrameSentence(context, number, total, ptsUs)))
