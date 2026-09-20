/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.util.Log
import android.view.SurfaceView
import android.view.inspector.WindowInspector
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.storage.*
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Composited-screen SDR geometry acceptance, not an HDR-display or continuous exact-PTS claim. */
class MediaPlaybackSurfaceUiTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun node(tag: String) = compose.onNodeWithTag("media-playback-$tag", useUnmergedTree = true)
    private fun click(tag: String) { node(tag).performScrollTo().performClick() }
    private fun present(tag: String) = compose.onAllNodesWithTag("media-playback-$tag", useUnmergedTree = true)
        .fetchSemanticsNodes().isNotEmpty()

    @Test fun landscapeSurfaceCompositesColorLeftWhiteRightAndReturnsToExactControls() = exerciseSurface(0)

    @Test fun rotatedSurfaceCompositesColorTopWhiteBottomAndReturnsToExactControls() = exerciseSurface(90)

    private fun exerciseSurface(rotation: Int) {
        val file = createPrecisePlaybackFixture(context, rotation)
        val originalHash = digest(file.readBytes())
        val visible = mutableStateOf(true)
        val initialSettings = PlaybackSettings(muted = true, loop = true)
        val settings = mutableStateOf(initialSettings)
        val existingReaders = Thread.getAllStackTraces().keys.filter { it.name == "media-review-reader" }.toSet()
        var settingsWrites = 0
        try {
            val artifact = LocalMediaArtifact(Uri.fromFile(file).toString(), file.name, "video/mp4", file.length(), file.lastModified() / 1000)
            val take = LocalMediaTake("surface-review-$rotation", artifact, listOf(artifact), emptyList(), LocalMediaKind.VIDEO, null, LocalMediaRelationStatus.LEGACY)
            compose.setContent { MaterialTheme { if (visible.value) MediaPlaybackDialog(
                MediaReviewSelection(listOf(take), artifact, GallerySettings(), "", null), settings.value,
                { settingsWrites++; settings.value = it }, { visible.value = false }, { error("No extra page") }) } }
            exact(1, 0L)
            node("status").assertTextEquals(context.getString(R.string.media_playback_paused))
            node("frame").assertExists()
            node("previous-frame").performScrollTo().assertIsNotEnabled()
            // The dialog has not started playback by opening; only this explicit action starts it.
            click("play-pause")
            compose.waitUntil(20_000) { isPlaying() || present("error-detail") }
            node("error-detail").assertDoesNotExist()
            assertTrue("Explicit play must enter PLAYING", isPlaying())
            node("frame").assertDoesNotExist()
            node("exact").assertDoesNotExist()
            node("estimated").assertExists()
            node("surface").performScrollTo().assertIsDisplayed()

            // Wait for observable compositor pixels, not a sleep or MediaPlayer prepared alone.
            // Every in-memory screenshot is recycled; one bounded readiness interval, no test retry.
            var capture: SurfaceCapture? = null
            compose.waitUntil(15_000) {
                if (present("error-detail")) true
                else if (!isPlaying() || present("frame")) false
                else {
                    capture = captureSurface(rotation)
                    capture?.let { it.colorReady && it.whiteReady } == true
                }
            }
            node("error-detail").assertDoesNotExist()
            val rendered = requireNotNull(capture) { "No composited native surface capture" }
            assertTrue("Composited colored half did not render: $rendered", rendered.colorReady)
            assertTrue("Composited white half did not render: $rendered", rendered.whiteReady)
            val expectedRatio = if (rotation == 0) 96.0 / 64 else 64.0 / 96
            assertEquals("Native viewport uses oriented display ratio", expectedRatio,
                rendered.bounds.width().toDouble() / rendered.bounds.height(), 0.025)
            assertTrue("Surface must be fully visible when sampled", rendered.fullyVisible)
            Log.i("E16SurfaceProbe", "rotation=$rotation surfaceBounds=${rendered.bounds} colorRgb=${rgb(rendered.color)} whiteRgb=${rgb(rendered.white)} nativeSurfaceComposited=true previewProof=SDR_GEOMETRY continuousPosition=ESTIMATED")

            // Pausing restores the exact CPU reader; all frame/limit controls remain connected.
            click("play-pause")
            compose.waitUntil(20_000) { present("exact") || present("error-detail") }
            node("error-detail").assertDoesNotExist()
            node("status").assertTextEquals(context.getString(R.string.media_playback_paused))
            node("frame").assertExists()
            click("start"); exact(1, 0L)
            node("previous-frame").performScrollTo().assertIsNotEnabled()
            click("next-frame"); exact(2, 400_000L)
            click("end"); exact(4, 1_700_000L)
            node("next-frame").performScrollTo().assertIsNotEnabled()
            click("previous-frame"); exact(3, 1_100_000L)
            click("start"); exact(1, 0L)
            node("previous-frame").performScrollTo().assertIsNotEnabled()
            compose.runOnIdle {
                assertEquals(initialSettings, settings.value)
                assertEquals("Review controls must not rewrite playback preferences", 0, settingsWrites)
            }
            node("close").performClick()
            node("dialog").assertDoesNotExist()
            awaitReaderRetirement(existingReaders)
            assertEquals(originalHash, digest(file.readBytes()))
            Log.i("E16SurfaceProbe", "rotation=$rotation exactPtsUs=[0,400000,1100000,1700000] limitsVerified=true originalUnchanged=true readersRetired=true settingsUnchanged=true")
        } finally {
            compose.runOnIdle { visible.value = false }
            awaitReaderRetirement(existingReaders)
            assertTrue(file.delete())
        }
    }

    private fun exact(index: Int, pts: Long) {
        val text = context.getString(R.string.media_playback_exact, index, 4, pts)
        compose.waitUntil(20_000) {
            runCatching { node("exact").assertTextEquals(text) }.isSuccess || present("error-detail")
        }
        node("error-detail").assertDoesNotExist()
        node("exact").performScrollTo().assertTextEquals(text)
        node("status").assertTextEquals(context.getString(R.string.media_playback_paused))
    }

    private fun isPlaying() = runCatching {
        node("status").assertTextEquals(context.getString(R.string.media_playback_playing))
    }.isSuccess

    private fun captureSurface(rotation: Int): SurfaceCapture? {
        var bounds: Rect? = null
        var fullyVisible = false
        compose.runOnIdle {
            val views = WindowInspector.getGlobalWindowViews().mapNotNull {
                it.findViewWithTag<SurfaceView>("media-playback-native-surface")
            }.distinct()
            assertEquals("Exactly one owned review SurfaceView", 1, views.size)
            val view = views.single()
            if (view.isAttachedToWindow && view.holder.surface.isValid && view.width > 0 && view.height > 0) {
                val location = IntArray(2)
                view.getLocationOnScreen(location)
                bounds = Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
                val visibleRect = Rect()
                fullyVisible = view.getGlobalVisibleRect(visibleRect) && visibleRect.width() == view.width && visibleRect.height() == view.height
            }
        }
        val rect = bounds ?: return null
        if (!fullyVisible) return null
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Composited screenshot unavailable" }
        try {
            assertTrue("Surface bounds exceed composited screen: $rect vs ${screenshot.width}x${screenshot.height}",
                rect.left >= 0 && rect.top >= 0 && rect.right <= screenshot.width && rect.bottom <= screenshot.height)
            val color = if (rotation == 0) sample(screenshot, rect, 0.25, 0.5) else sample(screenshot, rect, 0.5, 0.25)
            val white = if (rotation == 0) sample(screenshot, rect, 0.75, 0.5) else sample(screenshot, rect, 0.5, 0.75)
            return SurfaceCapture(rect, fullyVisible, color, white,
                listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW).any { matches(it, color) }, matches(Color.WHITE, white))
        } finally { screenshot.recycle() }
    }

    private fun sample(bitmap: Bitmap, rect: Rect, x: Double, y: Double): Int {
        val centerX = rect.left + (rect.width() * x).toInt()
        val centerY = rect.top + (rect.height() * y).toInt()
        var red = 0; var green = 0; var blue = 0
        for (dy in -1..1) for (dx in -1..1) {
            val pixel = bitmap.getPixel(centerX + dx, centerY + dy)
            red += Color.red(pixel); green += Color.green(pixel); blue += Color.blue(pixel)
        }
        return Color.rgb(red / 9, green / 9, blue / 9)
    }

    private fun matches(expected: Int, actual: Int) =
        kotlin.math.abs(Color.red(expected) - Color.red(actual)) <= 20 &&
            kotlin.math.abs(Color.green(expected) - Color.green(actual)) <= 20 &&
            kotlin.math.abs(Color.blue(expected) - Color.blue(actual)) <= 20

    private fun rgb(color: Int) = "${Color.red(color)},${Color.green(color)},${Color.blue(color)}"
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toList()
    private fun awaitReaderRetirement(existing: Set<Thread>) {
        compose.waitUntil(30_000) {
            Thread.getAllStackTraces().keys.none { it.name == "media-review-reader" && it !in existing && it.isAlive }
        }
    }

    private data class SurfaceCapture(val bounds: Rect, val fullyVisible: Boolean, val color: Int, val white: Int,
        val colorReady: Boolean, val whiteReady: Boolean)
}
