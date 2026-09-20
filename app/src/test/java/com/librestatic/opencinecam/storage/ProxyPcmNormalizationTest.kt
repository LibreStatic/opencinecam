/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class ProxyPcmNormalizationTest {
    private fun shorts(vararg values: Int) = ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        .apply { values.forEach { putShort(it.toShort()) } }.array()
    private fun converted(bytes: ByteArray, type: ProxyPcmSampleType): ByteArray {
        val output = normalizeProxyPcm16(ByteBuffer.wrap(bytes), type)
        return ByteArray(output.remaining()).also { output.get(it) }
    }
    @Test fun pcm16IsExactAndInputPositionOrderAreNotChanged() {
        val data = shorts(-32768, -1, 0, 32767); val input = ByteBuffer.wrap(byteArrayOf(42) + data + byteArrayOf(43))
        input.position(1); input.limit(9)
        val result = normalizeProxyPcm16(input, ProxyPcmSampleType.S16_LE)
        assertArrayEquals(data, ByteArray(result.remaining()).also { result.get(it) })
        assertEquals(1, input.position()); assertEquals(9, input.limit()); assertEquals(ByteOrder.BIG_ENDIAN, input.order())
    }
    @Test fun unsigned8MapsExtremaAndZeroExactly() {
        assertArrayEquals(shorts(-32768, -256, 0, 32512), converted(byteArrayOf(0, 127, -128, -1), ProxyPcmSampleType.U8))
    }
    @Test fun packed24DropsOnlyLeastSignificantByteIncludingNegativeValues() {
        val bytes = byteArrayOf(1,0,-128, 127,-1,-1, -1,0,0, -1,-1,127)
        assertArrayEquals(shorts(-32768,-1,0,32767), converted(bytes, ProxyPcmSampleType.S24_LE))
    }
    @Test fun signed32DropsOnlyLeastSignificantWord() {
        val bytes = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(Int.MIN_VALUE+1).putInt(-1).putInt(65535).putInt(Int.MAX_VALUE).array()
        assertArrayEquals(shorts(-32768,-1,0,32767), converted(bytes, ProxyPcmSampleType.S32_LE))
    }
    @Test fun finiteFloatClampsAndTruncatesLikePinnedMedia3() {
        val values = floatArrayOf(-2f,-1f,-0.5f,-0f,0.5f,1f,2f)
        val bytes = ByteBuffer.allocate(values.size*4).order(ByteOrder.LITTLE_ENDIAN).apply { values.forEach { putFloat(it) } }.array()
        assertArrayEquals(shorts(-32767,-32767,-16383,0,16383,32767,32767), converted(bytes, ProxyPcmSampleType.F32_LE))
    }
    @Test fun rejectsPartialSamplesAndNonFiniteFloatWithoutConsumingInput() {
        assertThrows(IllegalArgumentException::class.java) { converted(byteArrayOf(1,2), ProxyPcmSampleType.S24_LE) }
        for (value in floatArrayOf(Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY)) {
            val bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array()
            assertThrows(IllegalArgumentException::class.java) { converted(bytes, ProxyPcmSampleType.F32_LE) }
        }
    }
}
