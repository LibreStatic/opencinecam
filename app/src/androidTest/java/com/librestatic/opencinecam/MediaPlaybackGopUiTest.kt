/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.util.Log
import android.view.SurfaceView
import android.view.inspector.WindowInspector
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.storage.*
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Closed GOPs and B-frame decode order must not become review presentation order. */
class MediaPlaybackGopUiTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val pts get() = preciseGopPtsUs
    private fun node(tag: String) = compose.onNodeWithTag("media-playback-$tag", useUnmergedTree = true)
    private fun present(tag: String) = compose.onAllNodesWithTag("media-playback-$tag", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun click(tag: String) { node(tag).performScrollTo().performClick() }
    private fun phase(value: Int) = runCatching { node("status").assertTextEquals(context.getString(value)) }.isSuccess

    @Test fun cpuReviewUsesPresentationOrderAcrossGopsAndConfirmsLastFrameAfterPlayback() = exercise(false)
    @Test fun nativeReviewUsesPresentationOrderAcrossGopsAndConfirmsLastFrameAfterPlayback() = exercise(true)

    private fun exercise(native: Boolean) {
        val file = createPreciseGopFixture(context)
        val before = digest(file.readBytes())
        val visible = mutableStateOf(true)
        val originalSettings = PlaybackSettings(muted = true, loop = false)
        val settings = mutableStateOf(originalSettings)
        val existing = readers()
        var writes = 0
        try {
            val artifact = LocalMediaArtifact(Uri.fromFile(file).toString(), file.name, "video/mp4", file.length(), file.lastModified() / 1000)
            val take = LocalMediaTake("gop-$native", artifact, listOf(artifact), emptyList(), LocalMediaKind.VIDEO, null, LocalMediaRelationStatus.LEGACY)
            compose.setContent { MaterialTheme { if (visible.value) MediaPlaybackDialog(
                MediaReviewSelection(listOf(take), artifact, GallerySettings(), "", null), settings.value,
                { writes++; settings.value = it }, { visible.value = false }, { error("No next page") }) } }
            exact(0, false)
            if (native) { compose.runOnIdle { settings.value = settings.value.copy(nativeSurfaceFrames = true) }; exact(0, true) }
            node("previous-frame").performScrollTo().assertIsNotEnabled()
            // Includes every B/P/I frame in presentation order, across the second sync boundary.
            for (index in 1..pts.lastIndex) { click("next-frame"); exact(index, native) }
            node("next-frame").performScrollTo().assertIsNotEnabled()
            for (index in pts.lastIndex - 1 downTo 4) { click("previous-frame"); exact(index, native) }
            // Midpoint1650000us floors to1540000us, not the following displayed frame.
            node("seek").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(0.5f)) }
            exact(pts.indexOfLast { it <= pts.last() / 2 }, native)
            click("start"); exact(0, native)
            click("next-frame"); exact(1, native)
            // Handoff starts at a B frame. Continuous time is estimated; EOS must restore an exact final frame.
            click("play-pause")
            // Read one semantics snapshot: separate assertions can straddle completion of a
            // short, non-looping clip and mistake its restored still for a PLAYING overlay.
            var observedContinuous = false
            compose.waitUntil(20_000) {
                observedContinuous = continuousSnapshot()
                observedContinuous || present("error-detail")
            }
            node("error-detail").assertDoesNotExist()
            assertTrue("PLAYING must simultaneously show estimated time without exact text or bitmap", observedContinuous)
            compose.waitUntil(35_000) { phase(R.string.media_playback_ended) || present("error-detail") }
            exact(pts.lastIndex, native, ended = true)
            node("next-frame").performScrollTo().assertIsNotEnabled()
            click("previous-frame"); exact(pts.lastIndex - 1, native)
            click("start"); exact(0, native)
            if (native) { compose.runOnIdle { settings.value = settings.value.copy(nativeSurfaceFrames = false) }; exact(0, false) }
            compose.runOnIdle { assertEquals(originalSettings, settings.value); assertEquals(0, writes) }
            node("close").performClick(); node("dialog").assertDoesNotExist()
            retired(existing)
            assertEquals(before, digest(file.readBytes()))
            Log.i("E16GopUiProbe", "native=$native allPresentationFrames=true backwardAcrossSync=true midpointFloor=true handoffFromBFrame=true eosExactLast=true originalUnchanged=true settingsWrites=0 readersRetired=true")
        } finally {
            compose.runOnIdle { visible.value = false }
            retired(existing)
            assertTrue(file.delete())
        }
    }

    private fun exact(index: Int, native: Boolean, ended: Boolean = false) {
        fun expected() = node("exact").assertExactFrame(context, index + 1, pts.size, pts[index])
        compose.waitUntil(35_000) {
            (runCatching { expected() }.isSuccess && present("native-frame-mode") == native) || present("error-detail")
        }
        node("error-detail").assertDoesNotExist()
        node("exact").performScrollTo(); expected()
        node("status").assertTextEquals(context.getString(if (ended) R.string.media_playback_ended else R.string.media_playback_paused))
        if (native) { node("native-frame-mode").assertExists(); node("frame").assertDoesNotExist() }
        else { node("native-frame-mode").assertDoesNotExist(); node("frame").assertExists() }
        node("surface").performScrollTo().assertIsDisplayed()
        var colors: Pair<Int, Int>? = null
        // Both bitmap and screen getPixel report sRGB; compare the independent transfer oracle.
        // https://android.googlesource.com/platform/frameworks/base/+/android13-release/graphics/java/android/graphics/Bitmap.java
        val gray = preciseGopExpectedGray[index]
        try {
            compose.waitUntil(10_000) {
                colors = capture()
                colors?.let { matches(gray, it.first) && matches(255, it.second) } == true
            }
        } catch (failure: Throwable) {
            fun rgb(value: Int?) = value?.let { "${Color.red(it)},${Color.green(it)},${Color.blue(it)}" }
            Log.i("E16GopUiProbe", "native=$native index=$index ptsUs=${pts[index]} expectedGray=$gray lastColor=${rgb(colors?.first)} lastWhite=${rgb(colors?.second)} exactComposited=false")
            throw failure
        }
        val sample = requireNotNull(colors)
        assertTrue("PTS=${pts[index]} must display only its own gray=$gray; actual=${sample.first}", matches(gray, sample.first))
        assertTrue("White spatial reference", matches(255, sample.second))
        expected()
        Log.i("E16GopUiProbe", "native=$native index=$index ptsUs=${pts[index]} expectedGray=$gray rgb=${Color.red(sample.first)},${Color.green(sample.first)},${Color.blue(sample.first)} exactComposited=true")
    }

    private fun continuousSnapshot(): Boolean {
        val selected = listOf("status", "estimated", "exact", "frame")
            .map { hasTestTag("media-playback-$it") }.reduce { a, c -> a or c }
        val nodes = compose.onAllNodes(selected, useUnmergedTree = true).fetchSemanticsNodes()
        val byTag = nodes.associateBy { it.config[SemanticsProperties.TestTag] }
        val status = byTag["media-playback-status"]?.config?.getOrNull(SemanticsProperties.Text)?.map { it.text }
        return status == listOf(context.getString(R.string.media_playback_playing)) &&
            "media-playback-estimated" in byTag && "media-playback-exact" !in byTag && "media-playback-frame" !in byTag
    }

    private fun capture(): Pair<Int, Int>? {
        var bounds: Rect? = null
        compose.runOnIdle {
            val views = WindowInspector.getGlobalWindowViews().mapNotNull { it.findViewWithTag<SurfaceView>("media-playback-native-surface") }.distinct()
            assertEquals(1, views.size)
            val view = views.single(); val visible = Rect()
            if (view.isAttachedToWindow && view.holder.surface.isValid && view.width > 0 && view.height > 0 &&
                view.getGlobalVisibleRect(visible) && visible.width() == view.width && visible.height() == view.height) {
                val at = IntArray(2); view.getLocationOnScreen(at)
                bounds = Rect(at[0], at[1], at[0] + view.width, at[1] + view.height)
            }
        }
        val rect = bounds ?: return null
        assertEquals(1.5, rect.width().toDouble() / rect.height(), 0.025)
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            assertTrue(rect.left >= 0 && rect.top >= 0 && rect.right <= screenshot.width && rect.bottom <= screenshot.height)
            return screenshot.getPixel(rect.left + rect.width() / 4, rect.top + rect.height() / 2) to
                screenshot.getPixel(rect.left + rect.width() * 3 / 4, rect.top + rect.height() / 2)
        } finally { screenshot.recycle() }
    }
    private fun matches(gray: Int, pixel: Int) = kotlin.math.abs(Color.red(pixel) - gray) <= 4 &&
        kotlin.math.abs(Color.green(pixel) - gray) <= 4 && kotlin.math.abs(Color.blue(pixel) - gray) <= 4
    private fun readers() = Thread.getAllStackTraces().keys.filter { it.name == "media-review-reader" && it.isAlive }.toSet()
    private fun retired(existing: Set<Thread>) { compose.waitUntil(30_000) { readers().none { it !in existing } } }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toList()
}
