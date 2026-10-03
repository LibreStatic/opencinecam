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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.storage.*
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Exact Surface output is checked against each independently colored source frame, not merely
 * a label or any running video frame. Screen captures prove SDR composition, not HDR capability. */
class MediaPlaybackExactSurfaceUiTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val pts = listOf(0L, 400_000L, 1_100_000L, 1_700_000L)
    private val colors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
    private fun node(tag: String) = compose.onNodeWithTag("media-playback-$tag", useUnmergedTree = true)
    private fun click(tag: String) { node(tag).performScrollTo().performClick() }
    private fun present(tag: String) = compose.onAllNodesWithTag("media-playback-$tag", useUnmergedTree = true)
        .fetchSemanticsNodes().isNotEmpty()

    @Test fun landscapeExactSurfaceMatchesEveryPtsAndHandsOffToPlayerAndCpu() = exercise(0)

    @Test fun rotatedExactSurfaceMatchesEveryPtsAndHandsOffToPlayerAndCpu() = exercise(90)

    private fun exercise(rotation: Int) {
        val file = createPrecisePlaybackFixture(context, rotation)
        val originalHash = digest(file.readBytes())
        val visible = mutableStateOf(true)
        val initialSettings = PlaybackSettings(muted = true, loop = true)
        val settings = mutableStateOf(initialSettings)
        val existingReaders = Thread.getAllStackTraces().keys.filter { it.name == "media-review-reader" }.toSet()
        var settingsWrites = 0
        try {
            val artifact = LocalMediaArtifact(Uri.fromFile(file).toString(), file.name, "video/mp4", file.length(), file.lastModified() / 1000)
            val take = LocalMediaTake("exact-surface-$rotation", artifact, listOf(artifact), emptyList(), LocalMediaKind.VIDEO, null, LocalMediaRelationStatus.LEGACY)
            compose.setContent { MaterialTheme { if (visible.value) MediaPlaybackDialog(
                MediaReviewSelection(listOf(take), artifact, GallerySettings(), "", null), settings.value,
                { settingsWrites++; settings.value = it }, { visible.value = false }, { error("No extra page") }) } }
            exact(0)
            node("frame").assertExists()
            node("native-frame-mode").assertDoesNotExist()
            // Direct output is the operator's Settings › Playback choice, never an automatic fallback.
            compose.runOnIdle { settings.value = settings.value.copy(nativeSurfaceFrames = true) }
            compose.waitUntil(30_000) { (present("native-frame-mode") && !present("frame") && present("exact")) || present("error-detail") }
            nativeExact(0, rotation)
            node("previous-frame").performScrollTo().assertIsNotEnabled()
            click("next-frame"); nativeExact(1, rotation)
            click("next-frame"); nativeExact(2, rotation)
            click("end"); nativeExact(3, rotation)
            node("next-frame").performScrollTo().assertIsNotEnabled()
            click("previous-frame"); nativeExact(2, rotation)
            // Half the actual final PTS is 850000 us: floor must select 400000, not 1100000.
            node("seek").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { action ->
                assertTrue("Slider accepts an explicit midpoint seek", action(0.5f))
            }
            nativeExact(1, rotation)
            click("start"); nativeExact(0, rotation)
            node("previous-frame").performScrollTo().assertIsNotEnabled()

            click("play-pause")
            compose.waitUntil(20_000) { isPlaying() || present("error-detail") }
            node("error-detail").assertDoesNotExist()
            assertTrue("Native exact reader hands surface ownership to MediaPlayer", isPlaying())
            node("exact").assertDoesNotExist()
            node("frame").assertDoesNotExist()
            node("estimated").assertExists()
            node("native-frame-mode").assertExists()
            // Continuous output must also reach the composited surface, without claiming exact PTS.
            rendered(rotation, null)
            click("play-pause")
            compose.waitUntil(30_000) { present("exact") || present("error-detail") }
            node("error-detail").assertDoesNotExist()
            val pausedIndex = pts.indices.singleOrNull { index ->
                runCatching { node("exact").assertExactFrame(context, index + 1, pts.size, pts[index]) }.isSuccess
            }
            assertNotNull("Paused native output must identify one actual indexed PTS", pausedIndex)
            nativeExact(requireNotNull(pausedIndex), rotation)
            click("end"); nativeExact(3, rotation)
            click("start"); nativeExact(0, rotation)

            compose.runOnIdle { settings.value = settings.value.copy(nativeSurfaceFrames = false) }
            compose.waitUntil(30_000) { (!present("native-frame-mode") && present("frame") && present("exact")) || present("error-detail") }
            exact(0)
            node("frame").assertExists()
            node("native-frame-mode").assertDoesNotExist()
            click("next-frame"); exact(1)
            node("frame").assertExists()
            compose.runOnIdle {
                assertEquals(initialSettings, settings.value)
                assertEquals("Review navigation must not write preferences", 0, settingsWrites)
            }
            node("close").performClick()
            node("dialog").assertDoesNotExist()
            awaitReaderRetirement(existingReaders)
            assertEquals(originalHash, digest(file.readBytes()))
            Log.i("E16ExactSurfaceProbe", "rotation=$rotation exactPtsUs=$pts composedCorrespondingColors=true floor850000Us=400000 exclusivePlayerHandoff=true returnedToCpu=true originalUnchanged=true settingsUnchanged=true readersRetired=true")
        } finally {
            compose.runOnIdle { visible.value = false }
            awaitReaderRetirement(existingReaders)
            assertTrue(file.delete())
        }
    }

    private fun exact(index: Int) {
        compose.waitUntil(30_000) {
            runCatching { node("exact").assertExactFrame(context, index + 1, pts.size, pts[index]) }.isSuccess || present("error-detail")
        }
        node("error-detail").assertDoesNotExist()
        node("exact").performScrollTo().assertExactFrame(context, index + 1, pts.size, pts[index])
        node("status").assertTextEquals(context.getString(R.string.media_playback_paused))
    }

    private fun nativeExact(index: Int, rotation: Int) {
        exact(index)
        node("native-frame-mode").assertExists()
        node("frame").assertDoesNotExist()
        rendered(rotation, index)
        // A stale compositor frame or a running player must not satisfy an exact-frame assertion.
        node("exact").assertExactFrame(context, index + 1, pts.size, pts[index])
        node("status").assertTextEquals(context.getString(R.string.media_playback_paused))
        node("frame").assertDoesNotExist()
    }

    private fun rendered(rotation: Int, exactIndex: Int?) {
        node("surface").performScrollTo().assertIsDisplayed()
        var sample: SurfaceSample? = null
        compose.waitUntil(15_000) {
            if (present("error-detail")) true
            else {
                sample = capture(rotation)
                sample?.let { value ->
                    matches(Color.WHITE, value.white) &&
                        (if (exactIndex == null) isPlaying() && colors.any { matches(it, value.color) }
                        else matches(colors[exactIndex], value.color))
                } == true
            }
        }
        node("error-detail").assertDoesNotExist()
        val value = requireNotNull(sample) { "No screen-composited SurfaceView frame" }
        assertTrue("White reference must be composited: $value", matches(Color.WHITE, value.white))
        if (exactIndex != null) assertTrue("PTS=${pts[exactIndex]} must show its own source color: $value", matches(colors[exactIndex], value.color))
        else assertTrue("Continuous source color must be visible: $value", colors.any { matches(it, value.color) })
        assertEquals("Viewport must honor rotation once", if (rotation == 0) 1.5 else 2.0 / 3,
            value.bounds.width().toDouble() / value.bounds.height(), 0.025)
        Log.i("E16ExactSurfaceProbe", "rotation=$rotation ptsUs=${exactIndex?.let { pts[it] } ?: "ESTIMATED"} colorRgb=${rgb(value.color)} whiteRgb=${rgb(value.white)} bounds=${value.bounds} bitmapOverlay=false")
    }

    private fun capture(rotation: Int): SurfaceSample? {
        var bounds: Rect? = null
        compose.runOnIdle {
            val views = WindowInspector.getGlobalWindowViews().mapNotNull {
                it.findViewWithTag<SurfaceView>("media-playback-native-surface")
            }.distinct()
            assertEquals("Exactly one owned native review surface", 1, views.size)
            val view = views.single()
            val visible = Rect()
            if (view.isAttachedToWindow && view.holder.surface.isValid && view.width > 0 && view.height > 0 &&
                view.getGlobalVisibleRect(visible) && visible.width() == view.width && visible.height() == view.height) {
                val position = IntArray(2)
                view.getLocationOnScreen(position)
                bounds = Rect(position[0], position[1], position[0] + view.width, position[1] + view.height)
            }
        }
        val rect = bounds ?: return null
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Screen compositor screenshot unavailable" }
        try {
            assertTrue("Surface exceeds captured screen: $rect", rect.left >= 0 && rect.top >= 0 && rect.right <= bitmap.width && rect.bottom <= bitmap.height)
            return if (rotation == 0) SurfaceSample(rect, pixel(bitmap, rect, 0.25, 0.5), pixel(bitmap, rect, 0.75, 0.5))
            else SurfaceSample(rect, pixel(bitmap, rect, 0.5, 0.25), pixel(bitmap, rect, 0.5, 0.75))
        } finally { bitmap.recycle() }
    }

    private fun pixel(bitmap: Bitmap, rect: Rect, x: Double, y: Double): Int {
        val centerX = rect.left + (rect.width() * x).toInt()
        val centerY = rect.top + (rect.height() * y).toInt()
        var red = 0; var green = 0; var blue = 0
        for (dy in -1..1) for (dx in -1..1) {
            val value = bitmap.getPixel(centerX + dx, centerY + dy)
            red += Color.red(value); green += Color.green(value); blue += Color.blue(value)
        }
        return Color.rgb(red / 9, green / 9, blue / 9)
    }

    private fun matches(expected: Int, actual: Int) = kotlin.math.abs(Color.red(expected) - Color.red(actual)) <= 20 &&
        kotlin.math.abs(Color.green(expected) - Color.green(actual)) <= 20 && kotlin.math.abs(Color.blue(expected) - Color.blue(actual)) <= 20
    private fun rgb(color: Int) = "${Color.red(color)},${Color.green(color)},${Color.blue(color)}"
    private fun isPlaying() = runCatching { node("status").assertTextEquals(context.getString(R.string.media_playback_playing)) }.isSuccess
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toList()
    private fun awaitReaderRetirement(existing: Set<Thread>) {
        compose.waitUntil(30_000) { Thread.getAllStackTraces().keys.none { it.name == "media-review-reader" && it !in existing && it.isAlive } }
    }
    private data class SurfaceSample(val bounds: Rect, val color: Int, val white: Int)
}
