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

/** Cropped96x62 AVC/SAR2:1 must display192:62 (or62:192 after90deg), in CPU stills,
 * native exact stills and continuous video. Composited SDR screenshots do not prove HDR. */
class MediaPlaybackAnamorphicUiTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val pts = listOf(0L, 400_000L, 1_100_000L, 1_700_000L)
    private val colors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
    private fun node(tag: String) = compose.onNodeWithTag("media-playback-$tag", useUnmergedTree = true)
    private fun click(tag: String) { node(tag).performScrollTo().performClick() }
    private fun present(tag: String) = compose.onAllNodesWithTag("media-playback-$tag", useUnmergedTree = true)
        .fetchSemanticsNodes().isNotEmpty()

    @Test fun landscapeCpuCropAndSarPersistAcrossStepsAndContinuousPlayback() = exercise(0, false)
    @Test fun rotatedCpuCropAndSarPersistAcrossStepsAndContinuousPlayback() = exercise(90, false)
    @Test fun landscapeNativeCropAndSarPersistAcrossStepsAndContinuousPlayback() = exercise(0, true)
    @Test fun rotatedNativeCropAndSarPersistAcrossStepsAndContinuousPlayback() = exercise(90, true)

    private fun exercise(rotation: Int, native: Boolean) {
        val file = createPreciseAnamorphicFixture(context, rotation)
        val originalHash = digest(file.readBytes())
        val visible = mutableStateOf(true)
        val initialSettings = PlaybackSettings(muted = true, loop = true)
        val settings = mutableStateOf(initialSettings)
        val existingReaders = Thread.getAllStackTraces().keys.filter { it.name == "media-review-reader" }.toSet()
        var settingsWrites = 0
        try {
            if (!native) verifyCpuPixels(file, rotation)
            val artifact = LocalMediaArtifact(Uri.fromFile(file).toString(), file.name, "video/mp4", file.length(), file.lastModified() / 1000)
            val take = LocalMediaTake("anamorphic-$rotation-$native", artifact, listOf(artifact), emptyList(), LocalMediaKind.VIDEO, null, LocalMediaRelationStatus.LEGACY)
            compose.setContent { MaterialTheme { if (visible.value) MediaPlaybackDialog(
                MediaReviewSelection(listOf(take), artifact, GallerySettings(), "", null), settings.value,
                { settingsWrites++; settings.value = it }, { visible.value = false }, { error("No extra page") }) } }
            assertFrame(0, rotation, false)
            node("native-frame-mode").assertDoesNotExist()
            if (native) {
                click("native-frames")
                compose.waitUntil(30_000) { (present("native-frame-mode") && !present("frame") && present("exact")) || present("error-detail") }
                assertFrame(0, rotation, true)
            }
            node("previous-frame").performScrollTo().assertIsNotEnabled()
            click("next-frame"); assertFrame(1, rotation, native)
            click("next-frame"); assertFrame(2, rotation, native)
            click("end"); assertFrame(3, rotation, native)
            node("next-frame").performScrollTo().assertIsNotEnabled()
            click("previous-frame"); assertFrame(2, rotation, native)
            // Half the actual final PTS is 850000 us: floor must select 400000, not 1100000.
            node("seek").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { action ->
                assertTrue("Slider accepts an explicit midpoint seek", action(0.5f))
            }
            assertFrame(1, rotation, native)
            click("start"); assertFrame(0, rotation, native)
            node("previous-frame").performScrollTo().assertIsNotEnabled()

            click("play-pause")
            var observedContinuous = false
            compose.waitUntil(20_000) {
                observedContinuous = continuousSnapshot(native)
                observedContinuous || present("error-detail")
            }
            node("error-detail").assertDoesNotExist()
            assertTrue("Explicit play must simultaneously show PLAYING/estimated without an exact bitmap", observedContinuous)
            // Continuous output must also reach the composited surface, without claiming exact PTS.
            rendered(rotation, null)
            node("play-pause").performScrollTo()
            val pauseMatcher = hasTestTag("media-playback-play-pause") and
                hasContentDescription(context.getString(R.string.media_playback_pause)) and isEnabled()
            compose.waitUntil(20_000) { compose.onAllNodes(pauseMatcher).fetchSemanticsNodes().size == 1 || present("error-detail") }
            node("error-detail").assertDoesNotExist()
            // Exactly one action for the displayed Pause intent; do not resend clicks or disable loop.
            compose.onNode(pauseMatcher).performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
            compose.waitUntil(30_000) { present("exact") || present("error-detail") }
            node("error-detail").assertDoesNotExist()
            val pausedIndex = pts.indices.singleOrNull { index ->
                runCatching { node("exact").assertTextEquals(exactText(index)) }.isSuccess
            }
            assertNotNull("Paused output must identify one actual indexed PTS", pausedIndex)
            assertFrame(requireNotNull(pausedIndex), rotation, native)
            click("end"); assertFrame(3, rotation, native)
            click("start"); assertFrame(0, rotation, native)

            if (native) {
                click("native-frames")
                compose.waitUntil(30_000) { (!present("native-frame-mode") && present("frame") && present("exact")) || present("error-detail") }
                assertFrame(0, rotation, false)
            }
            node("native-frame-mode").assertDoesNotExist()
            click("next-frame"); assertFrame(1, rotation, false)
            compose.runOnIdle {
                assertEquals(initialSettings, settings.value)
                assertEquals("Decoder choice and navigation must not persist preferences", 0, settingsWrites)
            }
            node("close").performClick()
            node("dialog").assertDoesNotExist()
            awaitReaderRetirement(existingReaders)
            assertEquals(originalHash, digest(file.readBytes()))
            Log.i("E16AnamorphicProbe", "rotation=$rotation native=$native visibleRaster=96x62 codedRaster=96x64 sar=2:1 exactPtsUs=$pts composedCorrespondingColors=true floor850000Us=400000 continuousDarPreserved=true returnedToCpu=true originalUnchanged=true settingsUnchanged=true readersRetired=true")
        } finally {
            compose.runOnIdle { visible.value = false }
            awaitReaderRetirement(existingReaders)
            assertTrue(file.delete())
        }
    }

    private fun continuousSnapshot(native: Boolean): Boolean {
        val selected = listOf("status", "estimated", "exact", "frame", "native-frame-mode")
            .map { hasTestTag("media-playback-$it") }.reduce { a, c -> a or c }
        val byTag = compose.onAllNodes(selected, useUnmergedTree = true).fetchSemanticsNodes()
            .associateBy { it.config[SemanticsProperties.TestTag] }
        val status = byTag["media-playback-status"]?.config?.getOrNull(SemanticsProperties.Text)?.map { it.text }
        return status == listOf(context.getString(R.string.media_playback_playing)) &&
            "media-playback-estimated" in byTag && "media-playback-exact" !in byTag && "media-playback-frame" !in byTag &&
            ("media-playback-native-frame-mode" in byTag) == native
    }

    private fun exactText(index: Int) = context.getString(R.string.media_playback_exact, index + 1, pts.size, pts[index])
    private fun exact(index: Int) {
        compose.waitUntil(30_000) {
            runCatching { node("exact").assertTextEquals(exactText(index)) }.isSuccess || present("error-detail")
        }
        node("error-detail").assertDoesNotExist()
        node("exact").performScrollTo().assertTextEquals(exactText(index))
        node("status").assertTextEquals(context.getString(R.string.media_playback_paused))
    }

    private fun assertFrame(index: Int, rotation: Int, native: Boolean) {
        exact(index)
        if (native) {
            node("native-frame-mode").assertExists()
            node("frame").assertDoesNotExist()
        } else {
            node("native-frame-mode").assertDoesNotExist()
            node("frame").performScrollTo().assertIsDisplayed()
            val bounds = node("frame").fetchSemanticsNode().boundsInRoot
            assertEquals("CPU image rectangle must apply crop and SAR once", ratio(rotation), bounds.width.toDouble() / bounds.height, 0.025)
        }
        rendered(rotation, index)
        node("exact").assertTextEquals(exactText(index))
        node("status").assertTextEquals(context.getString(R.string.media_playback_paused))
        if (native) node("frame").assertDoesNotExist() else node("frame").assertExists()
    }

    private fun verifyCpuPixels(file: java.io.File, rotation: Int) {
        PreciseVideoFrames(context, Uri.fromFile(file).toString()).use { reader ->
            assertEquals(pts, reader.timeline.timestampsUs)
            for (index in pts.indices) {
                val frame = reader.frame(index)
                try {
                    assertEquals(pts[index], frame.presentationTimeUs)
                    assertEquals(index, frame.index)
                    assertEquals(if (rotation == 0) 96 else 62, frame.bitmap.width)
                    assertEquals(if (rotation == 0) 62 else 96, frame.bitmap.height)
                    assertEquals(if (rotation == 0) 192 else 62, frame.displayWidth)
                    assertEquals(if (rotation == 0) 62 else 192, frame.displayHeight)
                    val color = if (rotation == 0) frame.bitmap.getPixel(24, 31) else frame.bitmap.getPixel(31, 24)
                    val white = if (rotation == 0) frame.bitmap.getPixel(72, 31) else frame.bitmap.getPixel(31, 72)
                    assertTrue("CPU crop retained corresponding frame color", matches(colors[index], color))
                    assertTrue("CPU crop retained white reference", matches(Color.WHITE, white))
                } finally { frame.bitmap.recycle() }
            }
        }
    }
    private fun ratio(rotation: Int) = if (rotation == 0) 192.0 / 62 else 62.0 / 192

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
        assertEquals("Viewport must honor rotation once", ratio(rotation),
            value.bounds.width().toDouble() / value.bounds.height(), 0.025)
        Log.i("E16AnamorphicProbe", "rotation=$rotation ptsUs=${exactIndex?.let { pts[it] } ?: "ESTIMATED"} colorRgb=${rgb(value.color)} whiteRgb=${rgb(value.white)} bounds=${value.bounds} expectedDar=${ratio(rotation)} bitmapOverlay=${present("frame")}")
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
