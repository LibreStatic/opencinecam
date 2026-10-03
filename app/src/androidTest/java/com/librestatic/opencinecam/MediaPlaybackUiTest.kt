/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.storage.*
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MediaPlaybackUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun node(tag:String) = compose.onNodeWithTag("media-playback-$tag",useUnmergedTree=true)
    private fun click(tag:String) { node(tag).performScrollTo().performClick() }
    private fun artifact(file:File,mime:String) = LocalMediaArtifact(Uri.fromFile(file).toString(),file.name,mime,file.length(),file.lastModified()/1000)
    private fun take(id:String,artifacts:List<LocalMediaArtifact>,kind:LocalMediaKind) = LocalMediaTake(id,artifacts.first(),artifacts,emptyList(),kind,null,LocalMediaRelationStatus.LEGACY)

    @Test fun exactFrameControlsAndPagedMemberNavigationKeepOriginalBytesAndSharePreferences() {
        val video=createPrecisePlaybackFixture(context)
        val photo=File(context.cacheDir,"review-photo-${UUID.randomUUID()}.png")
        Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888).let { bitmap ->
            try { photo.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) } } finally { bitmap.recycle() }
        }
        val secondPhoto=File(context.cacheDir,"review-photo-second-${UUID.randomUUID()}.png")
        Bitmap.createBitmap(24,16,Bitmap.Config.ARGB_8888).let { bitmap ->
            bitmap.eraseColor(android.graphics.Color.RED)
            try { secondPhoto.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) } } finally { bitmap.recycle() }
        }
        val initialBytes=video.readBytes();val photoBytes=photo.readBytes();val secondBytes=secondPhoto.readBytes()
        val initial=take("video",listOf(artifact(video,"video/mp4")),LocalMediaKind.VIDEO)
        val later=take("photo",listOf(artifact(photo,"image/png"),artifact(secondPhoto,"image/png")),LocalMediaKind.PHOTO)
        val cursor=LocalMediaCursor("review-fixture",emptyMap(),emptySet())
        val settings=mutableStateOf(PlaybackSettings(muted=true))
        val visible=mutableStateOf(true)
        var pages=0
        try {
            compose.setContent { MaterialTheme { if(visible.value) MediaPlaybackDialog(
                MediaReviewSelection(listOf(initial),initial.primary,GallerySettings(),"",cursor), settings.value,
                { settings.value=it }, { visible.value=false }, { requested ->
                    assertSame(cursor,requested);pages++;LocalMediaPage(listOf(later),null)
                }) } }
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("media-playback-exact",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty() }
            node("exact").performScrollTo().assertTextEquals(context.getString(R.string.media_playback_exact,1,4,0L))
            node("previous-frame").performScrollTo().assertIsNotEnabled()
            click("next-frame")
            compose.waitUntil(20_000) { runCatching { node("exact").assertTextEquals(context.getString(R.string.media_playback_exact,2,4,400_000L)) }.isSuccess }
            click("end")
            compose.waitUntil(20_000) { runCatching { node("exact").assertTextEquals(context.getString(R.string.media_playback_exact,4,4,1_700_000L)) }.isSuccess }
            node("next-frame").performScrollTo().assertIsNotEnabled()
            click("start")
            compose.waitUntil(20_000) { runCatching { node("exact").assertTextEquals(context.getString(R.string.media_playback_exact,1,4,0L)) }.isSuccess }
            click("settings-toggle")
            compose.onNodeWithTag("playback-frame-position",useUnmergedTree=true).performScrollTo().performClick()
            compose.runOnIdle { assertEquals(PlaybackSettings(muted=true,showFramePosition=false),settings.value) }
            node("exact").assertDoesNotExist()
            node("next-take").performClick()
            compose.waitUntil(10_000) { runCatching { node("take").assertTextEquals(later.primary.name) }.isSuccess }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("media-playback-frame",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(1,pages)
            node("previous-member").performScrollTo().assertIsNotEnabled()
            click("next-member")
            node("member").performScrollTo().assertTextEquals(context.getString(R.string.media_playback_member,2,2,secondPhoto.name))
            node("next-member").performScrollTo().assertIsNotEnabled()
            click("previous-member")
            node("member").performScrollTo().assertTextEquals(context.getString(R.string.media_playback_member,1,2,photo.name))
            node("next-take").assertIsNotEnabled()
            node("play-pause").assertDoesNotExist()
            node("previous-take").performClick()
            compose.waitUntil(20_000) { runCatching { node("take").assertTextEquals(initial.primary.name) }.isSuccess }
            node("close").performClick()
            node("dialog").assertDoesNotExist()
            assertArrayEquals(initialBytes,video.readBytes());assertArrayEquals(photoBytes,photo.readBytes());assertArrayEquals(secondBytes,secondPhoto.readBytes())
        } finally { compose.runOnIdle { visible.value=false };assertTrue(video.delete());assertTrue(photo.delete());assertTrue(secondPhoto.delete()) }
    }
    @Test fun missingOriginalIsVisibleAndCloseWorksAtDoubleFontWithoutStartingPlayback() {
        val missing=LocalMediaArtifact("file://${context.cacheDir}/absent-${UUID.randomUUID()}.mp4","Missing.mp4","video/mp4",1,0)
        val selected=take("missing",listOf(missing),LocalMediaKind.VIDEO)
        val visible=mutableStateOf(true)
        compose.setContent { DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(2f)) { MaterialTheme {
            if(visible.value) MediaPlaybackDialog(MediaReviewSelection(listOf(selected),missing,GallerySettings(),"",null),
                PlaybackSettings(),{}, { visible.value=false }, { error("No continuation") })
        } } }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("media-playback-error-detail",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty() }
        node("status").performScrollTo().assertTextEquals(context.getString(R.string.media_playback_error))
        node("play-pause").performScrollTo().assertIsNotEnabled()
        node("retry").performScrollTo().assertHeightIsAtLeast(48.dp)
        node("close").assertHeightIsAtLeast(48.dp).performClick()
        node("dialog").assertDoesNotExist()
    }
}
