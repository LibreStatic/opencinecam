/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.librestatic.opencinecam.camera.PhotoAspectDisposition
import com.librestatic.opencinecam.camera.PhotoAspectSelection
import com.librestatic.opencinecam.camera.StillImageKind
import com.librestatic.opencinecam.camera.StillImagePayload
import java.io.ByteArrayOutputStream
import java.lang.reflect.InvocationTargetException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

/** Actual platform JPEG encode/decode. EXIF is inserted in memory, not through a production
 * orientation helper. Expected corner tables are independent of its matrix/pixel transforms. */
class PhotoAspectBitmapDeviceTest {
    private val selection = PhotoAspectSelection(true, 1, 1)
    private val colors = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
    // Positions are top-left, top-right, bottom-left, bottom-right after the EXIF transform.
    private val exifCorners = listOf(
        intArrayOf(0, 1, 2, 3), // normal
        intArrayOf(1, 0, 3, 2), // horizontal mirror
        intArrayOf(3, 2, 1, 0), // half turn
        intArrayOf(2, 3, 0, 1), // vertical mirror
        intArrayOf(0, 2, 1, 3), // transpose
        intArrayOf(2, 0, 3, 1), // clockwise quarter turn
        intArrayOf(3, 1, 2, 0), // transverse
        intArrayOf(1, 3, 0, 2), // counterclockwise quarter turn
    )
    private val clockwiseCorners = mapOf(
        0 to intArrayOf(0, 1, 2, 3),
        90 to intArrayOf(2, 0, 3, 1),
        180 to intArrayOf(3, 2, 1, 0),
        270 to intArrayOf(1, 3, 0, 2),
    )

    @Test fun allExifTransformsAndFrozenRotationsPreserveActualQuadrantPositionsAfterCrop() {
        val source = jpeg { x, y -> colors[(if (y >= 40) 2 else 0) + if (x >= 60) 1 else 0] }
        for (exif in 1..8) for (degrees in listOf(0, 90, 180, 270)) {
            val result = crop(withExif(source, exif), degrees)
            assertEquals(StillImageKind.JPEG, result.kind)
            assertEquals(80, result.width)
            assertEquals(80, result.height)
            val report = requireNotNull(result.aspectReport)
            assertEquals(selection, report.requested)
            assertEquals(PhotoAspectDisposition.APPLIED, report.disposition)
            assertEquals(0, report.outputOrientationDegrees)
            val swapped = (exif >= 5) != (degrees == 90 || degrees == 270)
            assertEquals(if (swapped) 80 else 120, report.sourceWidth)
            assertEquals(if (swapped) 120 else 80, report.sourceHeight)
            assertEquals(if (swapped) 0 else 20, report.crop.left)
            assertEquals(if (swapped) 20 else 0, report.crop.top)
            assertEquals(80, report.crop.width)
            assertEquals(80, report.crop.height)
            val positions = arrayOf(20 to 20, 60 to 20, 20 to 60, 60 to 60)
            decode(result).let { bitmap ->
                try {
                    assertEquals(80, bitmap.width)
                    assertEquals(80, bitmap.height)
                    val rotation = clockwiseCorners.getValue(degrees)
                    positions.forEachIndexed { index, (x, y) ->
                        val expected = colors[exifCorners[exif - 1][rotation[index]]]
                        assertColor("EXIF=$exif desired=$degrees quadrant=$index", expected, bitmap.getPixel(x, y))
                    }
                } finally { bitmap.recycle() }
            }
        }
        android.util.Log.i("PhotoAspectBitmapProbe", "orientationCases=32 quadrantPixelChecks=128 decoded=true outputOrientation=0")
    }

    @Test fun centralCropKeepsInteriorMarkerAtExactOffsetAndDropsOutsideStripWithoutResizing() {
        val source = jpeg { x, y ->
            when {
                x < 16 -> Color.CYAN // Entirely outside the centered x=20..99 crop.
                x in 24..35 && y in 12..27 -> Color.MAGENTA
                else -> Color.BLACK
            }
        }
        val result = crop(withExif(source, 1), 0)
        val report = requireNotNull(result.aspectReport)
        assertEquals(20, report.crop.left)
        assertEquals(0, report.crop.top)
        val bitmap = decode(result)
        try {
            // The marker moves left by exactly 20px. Scaling 120->80 would place it elsewhere.
            assertColor("center crop marker", Color.MAGENTA, bitmap.getPixel(10, 20))
            assertColor("marker must not remain at its original x", Color.BLACK, bitmap.getPixel(30, 20))
            assertColor("discarded left strip", Color.BLACK, bitmap.getPixel(2, 40))
            assertColor("unchanged lower region", Color.BLACK, bitmap.getPixel(40, 60))
        } finally { bitmap.recycle() }
    }

    @Test fun malformedJpegInvalidExifAndDisabledRequestsRejectWithoutReturningAPayload() {
        assertThrows(IllegalArgumentException::class.java) { crop(byteArrayOf(1, 2, 3), 0) }
        val source = jpeg { _, _ -> Color.RED }
        assertThrows(IllegalArgumentException::class.java) { crop(withExif(source, 9), 0) }
        assertThrows(IllegalArgumentException::class.java) { invokeCrop(source, PhotoAspectSelection(), 0, 100) {} }
        assertThrows(IllegalArgumentException::class.java) { invokeCrop(source, selection, 45, 100) {} }
        assertThrows(IllegalArgumentException::class.java) { invokeCrop(source, selection, 0, 0) {} }
        // Rejection does not poison the next real decode/encode operation.
        val valid = decode(crop(withExif(source, 1), 0))
        try { assertColor("valid after rejected input", Color.RED, valid.getPixel(40, 40)) }
        finally { valid.recycle() }
    }

    @Test fun cancellationBeforeDecodeAfterDecodeBeforeCropAndDuringEncodingNeverReturnsPartialOutput() {
        val source = withExif(jpeg { _, _ -> Color.BLUE }, 1)
        // Production checkpoints 1..5 span predecode, decoded-bitmap ownership, crop and writer.
        for (stopAt in 1..5) {
            var checks = 0
            val failure = CancellationException("fixture checkpoint $stopAt")
            val caught = assertThrows(CancellationException::class.java) {
                invokeCrop(source, selection, 90, 100) {
                    checks++
                    if (checks >= stopAt) throw failure
                }
            }
            assertSame(failure, caught)
            assertTrue("The cancellation checkpoint must be reached", checks >= stopAt)
            val valid = decode(crop(source, 90))
            try { assertColor("valid after cancelled checkpoint $stopAt", Color.BLUE, valid.getPixel(40, 40)) }
            finally { valid.recycle() }
        }
    }

    private fun crop(bytes: ByteArray, degrees: Int): StillImagePayload = invokeCrop(bytes, selection, degrees, 100) {}

    private fun invokeCrop(bytes: ByteArray, aspect: PhotoAspectSelection, degrees: Int,
        quality: Int, checkRunning: () -> Unit): StillImagePayload {
        val method = Class.forName("com.librestatic.opencinecam.camera.PhotoAspectBitmapProcessorKt")
            .getDeclaredMethod("cropPhotoAspectJpeg", ByteArray::class.java, PhotoAspectSelection::class.java,
                Integer.TYPE, Integer.TYPE, Function0::class.java)
        return try { method.invoke(null, bytes, aspect, degrees, quality, checkRunning) as StillImagePayload }
        catch (failure: InvocationTargetException) { throw failure.targetException }
    }

    private fun jpeg(pixel: (Int, Int) -> Int): ByteArray {
        val bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
        return try {
            val pixels = IntArray(120 * 80) { index -> pixel(index % 120, index / 120) }
            bitmap.setPixels(pixels, 0, 120, 0, 0, 120, 80)
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output))
                output.toByteArray()
            }
        } finally { bitmap.recycle() }
    }

    /** APP1 Exif header + little-endian TIFF IFD0 with one SHORT Orientation tag. */
    private fun withExif(jpeg: ByteArray, orientation: Int): ByteArray {
        require(jpeg.size >= 2 && jpeg[0] == 0xff.toByte() && jpeg[1] == 0xd8.toByte())
        val payload = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(byteArrayOf(0x45, 0x78, 0x69, 0x66, 0, 0)) // Exif followed by two NUL bytes
            put(0x49.toByte()); put(0x49.toByte()); putShort(42.toShort()); putInt(8)
            putShort(1.toShort())
            putShort(0x0112.toShort()); putShort(3.toShort()); putInt(1)
            putShort(orientation.toShort()); putShort(0.toShort()); putInt(0)
        }.array()
        return ByteArrayOutputStream(jpeg.size + 36).use { output ->
            output.write(jpeg, 0, 2)
            output.write(byteArrayOf(0xff.toByte(), 0xe1.toByte(), 0, 34))
            output.write(payload)
            output.write(jpeg, 2, jpeg.size - 2)
            output.toByteArray()
        }
    }

    private fun decode(payload: StillImagePayload): Bitmap {
        val bytes = payload.bytes
        return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
    }

    private fun assertColor(label: String, expected: Int, actual: Int) {
        assertEquals("$label alpha", 255, Color.alpha(actual))
        // Two JPEG generations may round/chroma-subsample; samples avoid every region edge.
        val wanted = intArrayOf(Color.red(expected), Color.green(expected), Color.blue(expected))
        val received = intArrayOf(Color.red(actual), Color.green(actual), Color.blue(actual))
        for (channel in wanted.indices) {
            assertTrue("$label channel=$channel expected=${wanted[channel]} actual=${received[channel]}",
                kotlin.math.abs(wanted[channel] - received[channel]) <= 32)
        }
    }
}
