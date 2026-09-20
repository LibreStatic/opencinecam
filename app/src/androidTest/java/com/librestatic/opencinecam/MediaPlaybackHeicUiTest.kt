/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.graphics.Color
import android.net.Uri
import android.util.Log
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.storage.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Existing E8 libheif oracle: identical mdat, base320x240 and irot03 clockwise90=>240x320.
 * These tests inspect the real dialog's composed Image, not a second decoder used as an oracle. */
class MediaPlaybackHeicUiTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun node(tag: String) = compose.onNodeWithTag("media-playback-$tag", useUnmergedTree = true)
    private fun present(tag: String) = compose.onAllNodesWithTag("media-playback-$tag", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun click(tag: String) { node(tag).performScrollTo().performClick() }

    @Test fun bothHeicMembersDisplayOracleQuadrantsMarkerAndOrientationWithoutVideoControls() {
        val files = mutableListOf<File>()
        val visible = mutableStateOf(true)
        val initialSettings = PlaybackSettings(muted = true, loop = true, showFramePosition = false)
        val settings = mutableStateOf(initialSettings)
        val existingReaders = readers()
        var writes = 0
        try {
            files += fixture(asset(false))
            files += fixture(asset(true))
            val before = files.map { hash(it.readBytes()) }
            val members = files.map(::artifact)
            val take = LocalMediaTake("heic-review", members.first(), members, emptyList(), LocalMediaKind.PHOTO, null, LocalMediaRelationStatus.LEGACY)
            compose.setContent { MaterialTheme { if (visible.value) MediaPlaybackDialog(
                MediaReviewSelection(listOf(take), take.primary, GallerySettings(), "", null), settings.value,
                { writes++; settings.value = it }, { visible.value = false }, { error("No extra page") }) } }
            readyPhoto()
            assertComposedOracle(rotated = false)
            node("previous-member").performScrollTo().assertIsNotEnabled()
            click("next-member")
            readyPhoto()
            node("member").assertTextEquals(context.getString(R.string.media_playback_member, 2, 2, files[1].name))
            assertComposedOracle(rotated = true)
            node("next-member").performScrollTo().assertIsNotEnabled()
            click("previous-member")
            readyPhoto()
            node("member").assertTextEquals(context.getString(R.string.media_playback_member, 1, 2, files[0].name))
            assertComposedOracle(rotated = false)
            compose.runOnIdle { assertEquals(initialSettings, settings.value); assertEquals(0, writes) }
            node("close").performClick()
            node("dialog").assertDoesNotExist()
            awaitRetirement(existingReaders)
            files.zip(before).forEach { (file, original) -> assertEquals(original, hash(file.readBytes())) }
            Log.i("E16HeicReviewProbe", "members=base,rotated90,base quadrantChecks=12 interiorMarkers=3 cyanStrips=3 originalBytesUnchanged=true settingsUnchanged=true readerRetirement=true")
        } finally {
            compose.runOnIdle { visible.value = false }
            awaitRetirement(existingReaders)
            files.forEach { assertTrue(it.delete()) }
        }
    }

    @Test fun truncatedHeicShowsErrorAndRetryDecodesRepairedPrivateFixtureAtTheSameUri() {
        val valid = asset(true)
        val truncated = valid.copyOf(40)
        val file = fixture(truncated)
        val visible = mutableStateOf(true)
        val settings = mutableStateOf(PlaybackSettings(muted = true))
        val existingReaders = readers()
        var writes = 0
        try {
            val member = artifact(file)
            val take = LocalMediaTake("broken-heic-review", member, listOf(member), emptyList(), LocalMediaKind.PHOTO, null, LocalMediaRelationStatus.LEGACY)
            compose.setContent { MaterialTheme { if (visible.value) MediaPlaybackDialog(
                MediaReviewSelection(listOf(take), member, GallerySettings(), "", null), settings.value,
                { writes++; settings.value = it }, { visible.value = false }, { error("No extra page") }) } }
            compose.waitUntil(30_000) { present("error-detail") }
            node("status").assertTextEquals(context.getString(R.string.media_playback_error))
            node("error-detail").assertExists()
            node("frame").assertDoesNotExist()
            noVideoControls()
            assertArrayEquals(truncated, file.readBytes())
            // Only this isolated test cache file changes. Existing source assets are never edited.
            // Same URI and frozen catalog artifact deliberately exercise retry, not member reopen.
            file.writeBytes(valid)
            click("retry")
            // Retry can retain the old error text while its replacement reader starts; demand
            // the new decoded frame rather than accepting that previous terminal observation.
            compose.waitUntil(30_000) { present("frame") }
            readyPhoto()
            assertComposedOracle(rotated = true)
            node("retry").assertDoesNotExist()
            compose.runOnIdle { assertEquals(PlaybackSettings(muted = true), settings.value); assertEquals(0, writes) }
            node("close").performClick()
            awaitRetirement(existingReaders)
            assertArrayEquals(valid, file.readBytes())
            assertArrayEquals(valid, asset(true))
            Log.i("E16HeicReviewProbe", "truncatedHeicVisibleError=true repairedOnlyPrivateCache=true sameUriRetry=true recoveredOrientation=90 sourceAssetUnchanged=true settingsUnchanged=true readerRetirement=true")
        } finally {
            compose.runOnIdle { visible.value = false }
            awaitRetirement(existingReaders)
            assertTrue(file.delete())
        }
    }

    private fun readyPhoto() {
        compose.waitUntil(30_000) { present("frame") || present("error-detail") }
        node("error-detail").assertDoesNotExist()
        node("status").assertTextEquals(context.getString(R.string.media_playback_paused))
        node("frame").performScrollTo().assertIsDisplayed()
        noVideoControls()
    }

    private fun noVideoControls() {
        for (tag in listOf("surface", "play-pause", "start", "end", "previous-frame", "next-frame", "seek", "seek-label",
            "exact", "estimated", "native-frames", "native-frame-mode", "native-hdr-display", "hdr-preview", "dng-preview", "interpret-track")) {
            node(tag).assertDoesNotExist()
        }
    }

    private fun assertComposedOracle(rotated: Boolean) {
        node("frame").performScrollTo().assertIsDisplayed()
        val image = node("frame").captureToImage()
        try {
            val pixels = image.toPixelMap()
            val sourceWidth = if (rotated) 240 else 320
            val sourceHeight = if (rotated) 320 else 240
            val scale = min(image.width.toDouble() / sourceWidth, image.height.toDouble() / sourceHeight)
            val width = sourceWidth * scale
            val height = sourceHeight * scale
            val left = (image.width - width) / 2
            val top = (image.height - height) / 2
            // Observe the actual colored extent independently of the computed expected fit.
            // Every source quadrant/edge is saturated; surrounding MaterialTheme surface is not.
            var minX = image.width; var minY = image.height; var maxX = -1; var maxY = -1
            for (y in 0 until image.height) for (x in 0 until image.width) {
                val c = pixels[x, y].toArgb()
                val high = maxOf(Color.red(c), Color.green(c), Color.blue(c))
                val low = minOf(Color.red(c), Color.green(c), Color.blue(c))
                if (high > 180 && low < 60 && high - low > 150) {
                    minX = minOf(minX, x); minY = minOf(minY, y); maxX = maxOf(maxX, x); maxY = maxOf(maxY, y)
                }
            }
            assertTrue("HEIC must occupy a real colored display region", maxX >= minX && maxY >= minY)
            assertEquals("Observed photo display aspect must follow container orientation once", sourceWidth.toDouble() / sourceHeight,
                (maxX - minX + 1).toDouble() / (maxY - minY + 1), 0.035)
            assertEquals("Photo fit left edge", left, minX.toDouble(), 3.0)
            assertEquals("Photo fit top edge", top, minY.toDouble(), 3.0)
            assertEquals("Photo fit right edge", left + width, (maxX + 1).toDouble(), 3.0)
            assertEquals("Photo fit bottom edge", top + height, (maxY + 1).toDouble(), 3.0)
            // E8 generator/independent heif-dec oracle points, not decoded by this test itself.
            val sourcePoints = listOf(Triple(80, 60, Color.RED), Triple(240, 60, Color.GREEN), Triple(80, 180, Color.BLUE),
                Triple(240, 180, Color.YELLOW), Triple(50, 42, Color.MAGENTA), Triple(8, 80, Color.CYAN))
            for ((x, y, expected) in sourcePoints) {
                val orientedX = if (rotated) 239 - y else x
                val orientedY = if (rotated) x else y
                val screenX = (left + (orientedX + 0.5) * scale).roundToInt().coerceIn(0, image.width - 1)
                val screenY = (top + (orientedY + 0.5) * scale).roundToInt().coerceIn(0, image.height - 1)
                val actual = pixels[screenX, screenY].toArgb()
                for (shift in listOf(0, 8, 16)) assertTrue("HEIC rotated=$rotated sourcePoint=$x,$y expected=$expected actual=$actual channel=$shift",
                    abs((expected ushr shift and 255) - (actual ushr shift and 255)) <= 35)
            }
            Log.i("E16HeicReviewProbe", "compositedHeic=true rotated=$rotated expectedSize=${sourceWidth}x$sourceHeight observedRect=$minX,$minY,$maxX,$maxY quadrantsAndMarkerAndStripVerified=true noVideoControls=true")
        } finally { image.asAndroidBitmap().recycle() }
    }

    private fun asset(rotated: Boolean): ByteArray {
        val name = if (rotated) "e8-heic-quadrants-rotated90.heic" else "e8-heic-quadrants.heic"
        val size = if (rotated) 2593 else 2583
        val expected = if (rotated) "b73fbec239e1490f90ab1042a4b78535004396d7c18ffb009a3a46225704986c"
            else "cd93a33d3092f35d00fdc3d480d1e3cea557a79a1d8edca89820a04c7051fe04"
        val bytes = instrumentation.context.assets.open(name).use { input ->
            val buffer = ByteArray(size + 1)
            var total = 0
            while (total < buffer.size) {
                val count = input.read(buffer, total, buffer.size - total)
                if (count == -1) break
                check(count > 0); total += count
            }
            assertEquals(size, total)
            buffer.copyOf(total)
        }
        assertEquals(expected, hash(bytes))
        return bytes
    }
    private fun fixture(bytes: ByteArray) = File(context.cacheDir, "heic-review-${UUID.randomUUID()}.heic").apply { writeBytes(bytes) }
    private fun artifact(file: File) = LocalMediaArtifact(Uri.fromFile(file).toString(), file.name, "image/heic", file.length(), file.lastModified() / 1000)
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun readers() = Thread.getAllStackTraces().keys.filter { it.name == "media-review-reader" }.toSet()
    private fun awaitRetirement(existing: Set<Thread>) {
        compose.waitUntil(35_000) { readers().none { it !in existing && it.isAlive } }
    }
}
