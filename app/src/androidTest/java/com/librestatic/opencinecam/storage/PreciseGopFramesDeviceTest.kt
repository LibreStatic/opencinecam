/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import android.graphics.Color
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.InterruptedIOException
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PreciseGopFramesDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun coldFinalFrameDrainsReorderedGopThenEveryPresentationFrameMatchesItsOwnPixels() {
        val file = createPreciseGopFixture(context)
        try {
            val before = digest(file)
            PreciseVideoFrames(context, Uri.fromFile(file).toString()).use { reader ->
                assertEquals(preciseGopPtsUs, reader.timeline.timestampsUs)
                // First decode is the final presentation frame. Its packet precedes the final B
                // packet in decode order: reading only through targetPTS is insufficient.
                assertFrame(reader, 11)
                for (index in 0..10) assertFrame(reader, index)
            }
            assertEquals(before, digest(file))
            Log.i("E16GopProbe", "coldFirstRequestedPtsUs=3300000 all12PresentationFramesHaveDistinctVerifiedPixels=true sourceUnchanged=true")
        } finally { assertTrue(file.delete()) }
    }

    @Test fun backwardsAcrossBothClosedGopsAndFloorSeeksReturnExactBAndReferenceFrames() {
        val file = createPreciseGopFixture(context)
        try {
            val before = digest(file)
            PreciseVideoFrames(context, Uri.fromFile(file).toString()).use { reader ->
                // B and reference pictures on both sides of the second IDR, deliberately not
                // chronological. Every result checks the independent per-index gray reference.
                for (index in listOf(11, 1, 8, 2, 6, 5, 10, 4, 7, 3, 9, 0)) assertFrame(reader, index)
                for ((time, expected) in listOf(-1L to 0, 1_199_999L to 5, 1_200_000L to 6,
                    1_539_999L to 6, 1_650_000L to 7, 2_809_999L to 9, 2_810_000L to 10, Long.MAX_VALUE to 11)) {
                    val index = reader.timeline.indexAt(time)
                    assertEquals("Floor query $time", expected, index)
                    assertFrame(reader, index)
                }
                assertEquals(0, reader.timeline.step(0, -1))
                assertEquals(11, reader.timeline.step(11, 1))
                assertFrame(reader, reader.timeline.step(6, -1))
                assertFrame(reader, reader.timeline.step(5, 1))
            }
            assertEquals(before, digest(file))
            Log.i("E16GopProbe", "crossGopBackwardAndForward=true floor1650000Us=1540000 limitsVerified=true sourceUnchanged=true")
        } finally { assertTrue(file.delete()) }
    }

    @Test fun invalidIndicesInterruptedAndClosedReadersNeverReturnFallbackAndFreshReaderRecovers() {
        val file = createPreciseGopFixture(context)
        try {
            val before = digest(file)
            val uri = Uri.fromFile(file).toString()
            val reader = PreciseVideoFrames(context, uri)
            try {
                assertThrows(IllegalArgumentException::class.java) { reader.frame(-1).bitmap.recycle() }
                assertThrows(IllegalArgumentException::class.java) { reader.frame(12).bitmap.recycle() }
                Thread.currentThread().interrupt()
                try {
                    assertThrows(InterruptedIOException::class.java) { reader.frame(11).bitmap.recycle() }
                    assertTrue(Thread.currentThread().isInterrupted)
                } finally { Thread.interrupted() }
                assertFrame(reader, 11)
            } finally { reader.close(); reader.close() }
            assertThrows(IllegalStateException::class.java) { reader.frame(0).bitmap.recycle() }
            PreciseVideoFrames(context, uri).use { fresh ->
                assertFrame(fresh, 8)
                assertFrame(fresh, 1)
            }
            assertEquals(before, digest(file))
            Log.i("E16GopProbe", "invalidAndInterruptedReadsRejected=true closedReaderRejected=true freshReaderRecoveredAcrossGops=true sourceUnchanged=true")
        } finally { assertTrue(file.delete()) }
    }

    private fun assertFrame(reader: PreciseVideoFrames, index: Int) {
        val frame = reader.frame(index)
        try {
            assertEquals(index, frame.index)
            assertEquals(preciseGopPtsUs[index], frame.presentationTimeUs)
            assertEquals(96, frame.bitmap.width)
            assertEquals(64, frame.bitmap.height)
            assertEquals(96, frame.displayWidth)
            assertEquals(64, frame.displayHeight)
            assertNull(frame.hdrPreview)
            assertNotNull(frame.color)
            assertFalse("Strict original needs no manual color interpretation", frame.color!!.interpreted)
            // Adjacent references differ by >=20 RGB codes; tolerance3 cannot confuse frames.
            for (y in listOf(8, 32, 56)) {
                for (x in listOf(12, 24, 36)) assertGray(preciseGopExpectedGray[index], frame.bitmap.getPixel(x, y))
                for (x in listOf(60, 72, 84)) assertGray(255, frame.bitmap.getPixel(x, y))
            }
            Log.i("E16GopProbe", "index=$index exactPtsUs=${frame.presentationTimeUs} independentGray=${preciseGopExpectedGray[index]} white=255 tolerance=3")
        } finally { frame.bitmap.recycle() }
    }
    private fun assertGray(expected: Int, actual: Int) {
        assertEquals(255, Color.alpha(actual))
        assertTrue("Expected independent gray=$expected, actual=${Color.red(actual)},${Color.green(actual)},${Color.blue(actual)}",
            kotlin.math.abs(Color.red(actual) - expected) <= 3 && kotlin.math.abs(Color.green(actual) - expected) <= 3 &&
                kotlin.math.abs(Color.blue(actual) - expected) <= 3)
    }
    private fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()
}
