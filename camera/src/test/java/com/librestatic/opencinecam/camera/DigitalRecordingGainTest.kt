/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Test

class DigitalRecordingGainTest {
    private fun bytes(buffer: ByteBuffer): ByteArray = ByteArray(buffer.capacity()).also { buffer.duplicate().apply { clear() }.get(it) }
    private fun pcm16(vararg samples: Int): ByteBuffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
        samples.forEach { putShort(it.toShort()) }; position(0)
    }
    private fun pcm24(vararg samples: Int): ByteBuffer = ByteBuffer.allocate(samples.size * 3).apply {
        samples.forEach { put(it.toByte()); put((it shr 8).toByte()); put((it shr 16).toByte()) }; position(0)
    }
    private fun read24(buffer: ByteBuffer, index: Int): Int = (buffer.get(index*3).toInt() and 255) or
        ((buffer.get(index*3+1).toInt() and 255) shl 8) or (buffer.get(index*3+2).toInt() shl 16)

    @Test fun defaultsAndInclusiveDecibelBounds() {
        assertEquals(DigitalRecordingGain(false,0), DigitalRecordingGain())
        for (db in -24..24) assertEquals(db, DigitalRecordingGain(true,db).decibels)
        for (db in listOf(-25,25,Int.MIN_VALUE,Int.MAX_VALUE))
            assertThrows(IllegalArgumentException::class.java) { DigitalRecordingGain(false,db) }
    }

    @Test fun disabledAndZeroDbPreserveEveryByteForAllEncodings() {
        for (encoding in PcmMeterEncoding.entries) for (channels in 1..2) {
            val buffer = ByteBuffer.allocate(encoding.bytesPerSample * channels * 8)
            repeat(buffer.capacity()) { buffer.put(it, (it*43+159).toByte()) }
            val original = bytes(buffer)
            for (gain in listOf(DigitalRecordingGain(false,24), DigitalRecordingGain(true,0))) {
                gain.process(buffer,buffer.capacity(),encoding,channels)
                assertArrayEquals(original,bytes(buffer))
                gain.process(buffer.asReadOnlyBuffer(),buffer.capacity(),encoding,channels)
            }
        }
    }

    @Test fun pcm16ProcessesEveryInterleavedStereoSampleWithOneGain() {
        val input = intArrayOf(100,-200,300,-400,500,-600,700,-800)
        val buffer = pcm16(*input)
        DigitalRecordingGain(true,6).process(buffer,buffer.capacity(),PcmMeterEncoding.PCM_16,2)
        input.forEachIndexed { index, value -> assertEquals((value * 10.0.pow(6.0/20)).roundToInt(),buffer.getShort(index*2).toInt()) }
    }

    @Test fun pcm16SaturatesBothSignedExtremesWithoutWrapping() {
        val buffer = pcm16(32767,-32768,20000,-20000)
        DigitalRecordingGain(true,24).process(buffer,8,PcmMeterEncoding.PCM_16,2)
        assertEquals(listOf(32767,-32768,32767,-32768), (0..3).map { buffer.getShort(it*2).toInt() })
    }

    @Test fun pcm16AttenuationUsesFullSignedInputRange() {
        val input = intArrayOf(-32768,-10000,10000,32767)
        val buffer = pcm16(*input)
        DigitalRecordingGain(true,-24).process(buffer,8,PcmMeterEncoding.PCM_16,1)
        input.forEachIndexed { index,value -> assertEquals((value*10.0.pow(-24.0/20)).roundToInt(),buffer.getShort(index*2).toInt()) }
    }

    @Test fun packed24ProcessesAllChannelsAndSaturatesCorrectly() {
        val input = intArrayOf(1,-1,100000,-100000,8388607,-8388608,1000000,-1000000)
        val buffer = pcm24(*input)
        DigitalRecordingGain(true,24).process(buffer,buffer.capacity(),PcmMeterEncoding.PCM_24,2)
        input.forEachIndexed { index,value -> assertEquals((value*10.0.pow(24.0/20)).coerceIn(-8388608.0,8388607.0).roundToInt(),read24(buffer,index)) }
    }

    @Test fun packed24AttenuationPreservesSignExtension() {
        val input = intArrayOf(-8388608,8388607,-65536,65536,-256,256)
        val buffer = pcm24(*input)
        DigitalRecordingGain(true,-12).process(buffer,buffer.capacity(),PcmMeterEncoding.PCM_24,2)
        input.forEachIndexed { index,value -> assertEquals((value*10.0.pow(-12.0/20)).roundToInt(),read24(buffer,index)) }
    }

    @Test fun floatPreservesHeadroomAndSanitizesNonfiniteInputsOnlyWhenProcessing() {
        val values = floatArrayOf(2f,-2f,Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY,0.25f)
        val buffer = ByteBuffer.allocate(values.size*4).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach(buffer::putFloat)
        val original = bytes(buffer)
        DigitalRecordingGain(true,0).process(buffer,buffer.capacity(),PcmMeterEncoding.PCM_FLOAT,2)
        assertArrayEquals(original,bytes(buffer))
        DigitalRecordingGain(true,6).process(buffer,buffer.capacity(),PcmMeterEncoding.PCM_FLOAT,2)
        assertEquals((2*10.0.pow(6.0/20)).toFloat(),buffer.getFloat(0),0f)
        assertEquals((-2*10.0.pow(6.0/20)).toFloat(),buffer.getFloat(4),0f)
        for (index in 2..4) assertEquals(0f,buffer.getFloat(index*4),0f)
        assertTrue(buffer.getFloat(0)>1f)
    }

    @Test fun finiteFloatOverflowSaturatesRepresentationNotUnity() {
        val buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).apply { putFloat(0,Float.MAX_VALUE); putFloat(4,-Float.MAX_VALUE) }
        DigitalRecordingGain(true,24).process(buffer,8,PcmMeterEncoding.PCM_FLOAT,2)
        assertEquals(Float.MAX_VALUE,buffer.getFloat(0),0f)
        assertEquals(-Float.MAX_VALUE,buffer.getFloat(4),0f)
    }

    @Test fun sourceCursorLimitMarkAndOrderAreUnchangedEvenWithNarrowLimit() {
        val buffer = pcm16(100,200,300,400).order(ByteOrder.BIG_ENDIAN)
        buffer.limit(6); buffer.position(2); buffer.mark(); buffer.position(4)
        DigitalRecordingGain(true,6).process(buffer,8,PcmMeterEncoding.PCM_16,2)
        assertEquals(4,buffer.position()); assertEquals(6,buffer.limit()); assertEquals(ByteOrder.BIG_ENDIAN,buffer.order())
        buffer.reset(); assertEquals(2,buffer.position())
        assertEquals(798,buffer.duplicate().apply { clear() }.order(ByteOrder.LITTLE_ENDIAN).getShort(6).toInt())
    }

    @Test fun slicesAndByteCountDoNotModifyAdjacentBytes() {
        val original = ByteBuffer.allocateDirect(16).apply { repeat(16) { put(it,0x55.toByte()) } }
        val slice = original.duplicate().apply { position(4); limit(12) }.slice().order(ByteOrder.LITTLE_ENDIAN)
        slice.putShort(0,100); slice.putShort(2,-100)
        val before = bytes(original)
        DigitalRecordingGain(true,6).process(slice,4,PcmMeterEncoding.PCM_16,2)
        val after = bytes(original)
        for (i in after.indices) if (i !in 4..7) assertEquals(before[i],after[i])
        assertEquals(200,slice.getShort(0).toInt()); assertEquals(-200,slice.getShort(2).toInt())
    }

    @Test fun rejectsIncompleteFramesBoundsAndChannelCountsBeforeWriting() {
        val gain = DigitalRecordingGain(true,6)
        for (encoding in PcmMeterEncoding.entries) for (channels in 1..2) {
            val buffer = ByteBuffer.allocate(encoding.bytesPerSample*channels*2)
            val before = bytes(buffer)
            for (count in listOf(-1,buffer.capacity()+1,Int.MAX_VALUE,encoding.bytesPerSample*channels-1))
                assertThrows(IllegalArgumentException::class.java) { gain.process(buffer,count,encoding,channels) }
            assertArrayEquals(before,bytes(buffer))
        }
        for (channels in listOf(0,3,Int.MAX_VALUE)) assertThrows(IllegalArgumentException::class.java) {
            gain.process(ByteBuffer.allocate(8),8,PcmMeterEncoding.PCM_16,channels)
        }
    }

    @Test fun readonlyActiveInputRejectsAndEmptyCompleteInputIsValid() {
        val gain = DigitalRecordingGain(true,6)
        assertThrows(IllegalArgumentException::class.java) { gain.process(pcm16(1).asReadOnlyBuffer(),2,PcmMeterEncoding.PCM_16,1) }
        gain.process(ByteBuffer.allocate(0).asReadOnlyBuffer(),0,PcmMeterEncoding.PCM_FLOAT,2)
    }

    @Test fun appliedReceiptIsSeparateFromRequestedAndAbsentForLegacySnapshots() {
        assertNull(AudioLevelSnapshot(emptyList(),false,0).appliedRecordingGain)
        val gain = DigitalRecordingGain(true,6)
        val snapshot = AudioLevelSnapshot(emptyList(),false,0).copy(appliedRecordingGain=gain)
        assertEquals(gain,snapshot.appliedRecordingGain)
    }
}
