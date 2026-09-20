/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Native Bitmap geometry/ownership contracts only. Generated pixels are not a DNG decoder
 * fixture; actual DNG capture/decode is covered separately by PhotoFormatServiceTest. */
@RunWith(AndroidJUnit4::class)
class DngPhotoPreviewDeviceTest {
    @Test fun allEightExifOrientationsMapEveryNonsquarePixelIncludingBothDiagonalMirrors() {
        val width = 5
        val height = 3
        val pixels = IntArray(width * height) { index ->
            val x = index % width; val y = index / width
            Color.rgb(20 + 40 * x, 30 + 70 * y, 20 + 7 * x + 11 * y)
        }
        assertEquals("Every source pixel identifies a different coordinate", pixels.size, pixels.toSet().size)
        val source = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        try {
            for (orientation in 1..8) {
                val output = orientDngPreview(source, orientation)
                try {
                    val swapsAxes = orientation in 5..8
                    assertEquals(if (swapsAxes) height else width, output.width)
                    assertEquals(if (swapsAxes) width else height, output.height)
                    // Independent integer coordinate table for EXIF. No Matrix or helper-derived
                    // expected image participates, so ignored/doubled rotation or mirrors fail.
                    for (y in 0 until height) for (x in 0 until width) {
                        val (outX, outY) = when (orientation) {
                            1 -> x to y
                            2 -> (width - 1 - x) to y
                            3 -> (width - 1 - x) to (height - 1 - y)
                            4 -> x to (height - 1 - y)
                            5 -> y to x
                            6 -> (height - 1 - y) to x
                            7 -> (height - 1 - y) to (width - 1 - x)
                            8 -> y to (width - 1 - x)
                            else -> error("Unexpected orientation")
                        }
                        assertEquals("EXIF=$orientation source=$x,$y target=$outX,$outY",
                            pixels[y * width + x], output.getPixel(outX, outY))
                    }
                    assertFalse("Caller retains ownership of source", source.isRecycled)
                    assertPixelsUnchanged(source, pixels)
                } finally { if (output !== source) output.recycle() }
                assertFalse("Disposing a transformed result must not recycle source", source.isRecycled)
                assertPixelsUnchanged(source, pixels)
            }
            Log.i("E16DngOrientationProbe", "generatedBitmapOnly=true exifOrientations=1..8 exactPixelMappings=120 sourceUnchanged=true sourceNotRecycled=true dngDecodeAcceptance=false")
        } finally { source.recycle() }
    }

    @Test fun invalidExifOrientationRejectsWithoutMutatingOrRecyclingCallerBitmap() {
        val source = Bitmap.createBitmap(5, 3, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.MAGENTA)
        val before = IntArray(15) { Color.MAGENTA }
        try {
            for (orientation in listOf(-1, 0, 9, Int.MIN_VALUE, Int.MAX_VALUE)) {
                assertThrows(IllegalArgumentException::class.java) { orientDngPreview(source, orientation) }
                assertFalse(source.isRecycled)
                assertPixelsUnchanged(source, before)
            }
            val fresh = orientDngPreview(source, 6)
            try {
                assertEquals(3, fresh.width); assertEquals(5, fresh.height)
                assertEquals(Color.MAGENTA, fresh.getPixel(1, 2))
            } finally { if (fresh !== source) fresh.recycle() }
            assertFalse(source.isRecycled)
        } finally { source.recycle() }
    }

    @Test fun sampleSizeUsesSmallestPowerOfTwoThatCoversCeilingDecodedDimensions() {
        val cases = listOf(
            SampleCase(1, 1, 2048, 1), SampleCase(2048, 2048, 2048, 1),
            SampleCase(2049, 1, 2048, 2), SampleCase(1, 2049, 2048, 2),
            SampleCase(4096, 2048, 2048, 2), SampleCase(4097, 2048, 2048, 4),
            SampleCase(320, 240, 2048, 1), SampleCase(7, 5, 3, 4),
            SampleCase(3, 3, 3, 1), SampleCase(4, 3, 3, 2),
            SampleCase(100, 1, 3, 64), SampleCase(Int.MAX_VALUE, 1, 2048, 1_048_576),
            SampleCase(Int.MAX_VALUE, 1, 2, 1_073_741_824), SampleCase(1, 1, Int.MAX_VALUE, 1),
        )
        for ((width, height, maximum, expected) in cases) {
            val sample = dngPreviewSampleSize(width, height, maximum)
            assertEquals("${width}x$height maxEdge=$maximum", expected, sample)
            assertTrue(sample > 0 && (sample and (sample - 1)) == 0)
            val edge = maxOf(width, height).toLong()
            assertTrue("Ceiling decoded edge stays within bound", (edge + sample - 1) / sample <= maximum)
            if (sample > 1) assertTrue("The preceding power of two would exceed the edge bound",
                (edge + sample / 2 - 1) / (sample / 2) > maximum)
        }
        assertEquals(4, dngPreviewSampleSize(4097, 2048))
    }

    @Test fun invalidSampleDimensionsBoundsAndUnrepresentablePositivePowerAreRejected() {
        for (bad in listOf(0, -1, Int.MIN_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { dngPreviewSampleSize(bad, 100, 2048) }
            assertThrows(IllegalArgumentException::class.java) { dngPreviewSampleSize(100, bad, 2048) }
            assertThrows(IllegalArgumentException::class.java) { dngPreviewSampleSize(100, 100, bad) }
        }
        // ceil(Int.MAX_VALUE/1) requires2^31, which is not a positive Int sample size.
        assertThrows(IllegalArgumentException::class.java) { dngPreviewSampleSize(Int.MAX_VALUE, 1, 1) }
        assertThrows(IllegalArgumentException::class.java) { dngPreviewSampleSize(1, Int.MAX_VALUE, 1) }
    }

    private fun assertPixelsUnchanged(source: Bitmap, expected: IntArray) {
        val actual = IntArray(source.width * source.height)
        source.getPixels(actual, 0, source.width, 0, 0, source.width, source.height)
        assertArrayEquals(expected, actual)
    }
    private data class SampleCase(val width: Int, val height: Int, val maximum: Int, val expected: Int)
}
