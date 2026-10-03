/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.storage.LocalMediaTake
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real AudioRecord → publication → catalog → activity review, not a synthesized PCM file.
 * The separate OperatorServiceTest keeps exact video acceptance, including its color failure. */
class PublishedAudioReviewDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun publishedWavMemberPlaysSeeksAndPausesOnBackgroundWithoutChangingTheTake() = review("WAV")
    @Test fun publishedFlacMemberPlaysSeeksAndPausesOnBackgroundWithoutChangingTheTake() = review("FLAC")

    private fun review(format: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = SettingsRepositories.get(context)
        val before = repository.states.value
        try {
            compose.runOnUiThread {
                repository.set(before.copy(audioEnabled = false, gallery = GallerySettings(),
                    playback = PlaybackSettings(muted = true, loop = true)))
            }
            PreparedCaptureBundleDeviceTest().withPublishedPlaybackTake(format) { take ->
                val existing = Thread.getAllStackTraces().keys.filter { it.name == "media-review-reader" && it.isAlive }.toSet()
                try { reviewTake(take, format) }
                finally {
                    // Dispose the real Activity before the publisher fixture deletes its owned rows,
                    // including assertion failures and the retired previous video member.
                    compose.activityRule.scenario.close()
                    val deadline = System.nanoTime() + 20_000_000_000L
                    fun remaining() = Thread.getAllStackTraces().keys.filter { it.name == "media-review-reader" && it.isAlive && it !in existing }
                    while (remaining().isNotEmpty() && System.nanoTime() < deadline) Thread.sleep(20)
                    assertTrue("Review reader must retire before media cleanup", remaining().isEmpty())
                }
            }
        } finally {
            compose.runOnUiThread { repository.set(before) }
        }
    }

    private fun reviewTake(take: LocalMediaTake, format: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val audio = take.originals.single { it.mimeType.startsWith("audio/") }
        assertTrue(audio.name.endsWith(".${format.lowercase()}"))
        val retriever = MediaMetadataRetriever()
        val durationMs = try {
            retriever.setDataSource(context, Uri.parse(audio.uri))
            requireNotNull(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)).toLong()
        } finally { retriever.release() }
        assertTrue(durationMs > 0)
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("media-action").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("media-action").performClick()
        compose.onNodeWithTag("gallery-search").performTextReplacement(take.id.removePrefix("take:"))
        compose.waitUntil(20_000) {
            runCatching { compose.onNodeWithTag("gallery-list").performScrollToKey(take.id) }.isSuccess
        }
        compose.galleryMenuAction(take.id, "primary")
        fun node(tag: String) = compose.onNodeWithTag("media-playback-$tag", useUnmergedTree = true)
        fun click(tag: String) { node(tag).performScrollTo().assertIsEnabled().performClick() }
        // Navigate to the actual associated audio even when the video decoder reports an error.
        // That video failure remains a separate red acceptance test; audio must stay reachable.
        click("next-member")
        node("member").assertTextContains(audio.name, substring = true)
        fun waitText(tag: String, text: String) {
            try {
                compose.waitUntil(20_000) { runCatching { node(tag).performScrollTo().assertTextEquals(text) }.isSuccess }
            } catch (failure: Throwable) {
                throw AssertionError("Expected $tag: $text\n" + runCatching { node(tag).printToString() }.getOrDefault("Node absent"), failure)
            }
        }
        waitText("status", context.getString(R.string.media_playback_paused))
        node("error-detail").assertDoesNotExist()
        node("exact").assertDoesNotExist()
        node("frame").assertDoesNotExist()
        node("previous-frame").assertDoesNotExist()
        node("next-frame").assertDoesNotExist()
        click("play-pause")
        waitText("status", context.getString(R.string.media_playback_playing))
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitText("status", context.getString(R.string.media_playback_paused))
        click("end")
        waitText("status", context.getString(R.string.media_playback_paused))
        waitText("estimated", context.getString(R.string.media_playback_estimated, durationMs))
        compose.waitUntil(20_000) { runCatching { node("start").assertIsEnabled() }.isSuccess }
        click("start")
        waitText("status", context.getString(R.string.media_playback_paused))
        waitText("estimated", context.getString(R.string.media_playback_estimated, 0L))
        node("exact").assertDoesNotExist()
        node("frame").assertDoesNotExist()
        node("error-detail").assertDoesNotExist()
        node("close").performClick()
        node("dialog").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.capture_tab)).performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("media-action").fetchSemanticsNodes().isNotEmpty() }
        android.util.Log.i("PublishedAudioReviewProbe", "format=$format uri=${audio.uri} durationMs=$durationMs actualAudioRecordPublication=true activityCatalogMemberNavigation=true plays=true backgroundPauses=true returnedToCapture=true")
    }
}
