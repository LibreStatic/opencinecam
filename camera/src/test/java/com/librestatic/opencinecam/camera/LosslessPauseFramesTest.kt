/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class LosslessPauseFramesTest {
    private fun bytes(size: Int) = ByteBuffer.allocateDirect(size).apply { repeat(size) { put(it, it.toByte()) } }
    @Test fun stereoFloatCompactionPreservesEveryBit() {
        val buffer = bytes(40)
        assertEquals(24, compactPcmFrames(buffer, 40, 8, listOf(PcmKeepSpan(0,1), PcmKeepSpan(3,2))))
        assertArrayEquals((0 until 8).map { it.toByte() }.toByteArray() + (24 until 40).map { it.toByte() }.toByteArray(), ByteArray(24).also { buffer.get(it) })
    }
    @Test fun packed24StereoCompactionPreservesChannelOrder() {
        val buffer = bytes(30)
        assertEquals(12, compactPcmFrames(buffer, 30, 6, listOf(PcmKeepSpan(1,2))))
        assertArrayEquals((6 until 18).map { it.toByte() }.toByteArray(), ByteArray(12).also { buffer.get(it) })
    }
    @Test fun malformedLaterSpanNeverPartiallyChangesBuffer() {
        val buffer = bytes(20)
        assertThrows(IllegalArgumentException::class.java) { compactPcmFrames(buffer,20,2,listOf(PcmKeepSpan(2,2),PcmKeepSpan(9,2))) }
        repeat(20) { assertEquals(it.toByte(),buffer.get(it)) }
    }
    @Test fun fullyPausedReadContainsNoOutputFrames() {
        val buffer = bytes(32)
        assertEquals(0,compactPcmFrames(buffer,32,8,emptyList())); assertEquals(0,buffer.remaining())
    }
    @Test fun partialPackedFrameIsRejectedBeforeChanges() {
        val buffer = bytes(25)
        assertThrows(IllegalArgumentException::class.java) { compactPcmFrames(buffer,25,6,listOf(PcmKeepSpan(0,4))) }
        assertEquals(25,buffer.limit())
    }
    @Test fun externalClockSelectsFloatFramesAndSealsStopBoundary() {
        val clock = CaptureEpochClock(true, 44100)
        clock.audioInput(AudioCaptureEpoch(1000000000L,true));clock.mapVideoInput(1000000000L)
        assertTrue(clock.setPaused(true,1000000000L))
        assertTrue(clock.setPaused(false,1000045352L))
        val spans=clock.selectAudio(0,5)
        val buffer=bytes(40);val retained=compactPcmFrames(buffer,40,8,spans)
        assertEquals(listOf(PcmKeepSpan(0,1),PcmKeepSpan(3,2)),spans)
        assertEquals(24,retained);clock.requireRetainedAudioFrames(3)
        clock.finishPause(1000200000L)
        assertNull(clock.mapVideoInput(1000300000L))
        assertEquals(3L,clock.report(null,null,null).sharedPause!!.retainedPcmFrames)
    }
}
