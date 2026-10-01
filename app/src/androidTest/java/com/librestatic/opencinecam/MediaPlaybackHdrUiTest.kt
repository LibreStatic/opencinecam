/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.net.Uri
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.storage.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MediaPlaybackHdrUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun node(tag: String) = compose.onNodeWithTag("media-playback-$tag", useUnmergedTree = true)
    private fun click(tag: String) { node(tag).performScrollTo().performClick() }
    private fun exact(index: Int, pts: Long) {
        compose.waitUntil(30_000) {
            runCatching { node("exact").assertTextEquals(context.getString(R.string.media_playback_exact, index, 4, pts)) }.isSuccess ||
                compose.onAllNodesWithTag("media-playback-error-detail", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        node("error-detail").assertDoesNotExist()
        node("exact").performScrollTo().assertTextEquals(context.getString(R.string.media_playback_exact, index, 4, pts))
        node("status").performScrollTo().assertTextEquals(context.getString(R.string.media_playback_paused))
    }
    @Test fun pqHlgExactReviewLabelsSdrPreviewAndRetiresBeforeLeavingOriginalsUnchanged() {
        assertTrue("Positive P010 UI acceptance requires API33+", Build.VERSION.SDK_INT >= 33)
        org.junit.Assume.assumeTrue("No HEVC decoder outputs P010 on this device",
            android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                !info.isEncoder && "video/hevc" in info.supportedTypes.map { it.lowercase() } &&
                    android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010 in
                    info.getCapabilitiesForType("video/hevc").colorFormats
            })
        val files = mutableListOf<File>()
        val visible = mutableStateOf(true)
        val settings = mutableStateOf(PlaybackSettings(muted = true))
        val existingReaders = Thread.getAllStackTraces().keys.filter { it.name == "media-review-reader" }.toSet()
        try {
            files += createPreciseHdrPlaybackFixture(context, PreciseHdrTransfer.PQ)
            files += createPreciseHdrPlaybackFixture(context, PreciseHdrTransfer.HLG)
            files += createPrecisePlaybackFixture(context)
            val bytes = files.map { it.readBytes() }
            val artifacts = files.map { LocalMediaArtifact(Uri.fromFile(it).toString(), it.name, "video/mp4", it.length(), it.lastModified() / 1000) }
            val take = LocalMediaTake("hdr-review", artifacts.first(), artifacts, emptyList(), LocalMediaKind.VIDEO, null, LocalMediaRelationStatus.LEGACY)
            compose.setContent { MaterialTheme { if (visible.value) MediaPlaybackDialog(
                MediaReviewSelection(listOf(take), take.primary, GallerySettings(), "", null), settings.value,
                { settings.value = it }, { visible.value = false }, { error("No extra page") }) } }
            for ((member, transfer) in listOf(PreciseHdrTransfer.PQ, PreciseHdrTransfer.HLG).withIndex()) {
                if (member > 0) click("next-member")
                exact(1, 0L)
                node("hdr-preview").performScrollTo().assertTextEquals(context.getString(R.string.media_playback_hdr_preview, transfer.name))
                node("color-interpretation").assertDoesNotExist()
                node("interpret-track").assertDoesNotExist()
                click("next-frame"); exact(2, 400_000L)
                click("end"); exact(4, 1_700_000L)
                node("next-frame").performScrollTo().assertIsNotEnabled()
                node("hdr-preview").performScrollTo().assertTextEquals(context.getString(R.string.media_playback_hdr_preview, transfer.name))
                click("start"); exact(1, 0L)
                node("previous-frame").performScrollTo().assertIsNotEnabled()
            }
            click("next-member"); exact(1, 0L)
            node("hdr-preview").assertDoesNotExist()
            compose.runOnIdle { assertEquals(PlaybackSettings(muted = true), settings.value) }
            node("close").performClick()
            node("dialog").assertDoesNotExist()
            compose.waitUntil(30_000) { Thread.getAllStackTraces().keys.none { it.name == "media-review-reader" && it !in existingReaders && it.isAlive } }
            files.zip(bytes).forEach { (file, original) -> assertArrayEquals(original, file.readBytes()) }
        } finally {
            compose.runOnIdle { visible.value = false }
            compose.waitUntil(30_000) { Thread.getAllStackTraces().keys.none { it.name == "media-review-reader" && it !in existingReaders && it.isAlive } }
            files.forEach { assertTrue(it.delete()) }
        }
    }
}
