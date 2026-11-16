/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.service

import com.librestatic.opencinecam.camera.PcmMeterEncoding
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class EqSnippetRecorderTest {
    private fun buffer(bytes: Int, first: Int = 0) = ByteBuffer.allocateDirect(bytes).apply {
        for (index in 0 until bytes) put(index, (first + index).toByte())
    }

    @Test fun stopsAtTheCapAndReportsFullOnce() {
        val reports = mutableListOf<Pair<Int, Boolean>>()
        // 8 kHz mono 16-bit for 1 s: 16000 bytes.
        val recorder = EqSnippetRecorder(PcmMeterEncoding.PCM_16, 8_000, 1, maxMs = 1_000) { ms, full -> reports += ms to full }
        repeat(5) { recorder.append(buffer(6_400), 6_400) }
        assertEquals(1, reports.count { it.second })
        assertEquals(1_000 to true, reports.last())
        val snippet = recorder.finish()!!
        assertEquals(16_000, snippet.byteCount)
        assertEquals(1_000, snippet.durationMs)
        assertEquals(8_000, snippet.frames)
    }

    @Test fun reportsProgressAtItsInterval() {
        val reports = mutableListOf<Int>()
        val recorder = EqSnippetRecorder(PcmMeterEncoding.PCM_16, 8_000, 1, maxMs = 2_000, progressIntervalMs = 200) { ms, _ -> reports += ms }
        // 40 ms buffers: 640 bytes.
        repeat(25) { recorder.append(buffer(640), 640) }
        assertEquals(listOf(200, 400, 600, 800, 1_000), reports)
    }

    @Test fun discardsASnippetShorterThanHalfASecond() {
        val recorder = EqSnippetRecorder(PcmMeterEncoding.PCM_16, 8_000, 1) { _, _ -> }
        recorder.append(buffer(7_984), 7_984) // 499 ms
        assertNull(recorder.finish())
        assertNull(EqSnippetRecorder(PcmMeterEncoding.PCM_16, 8_000, 1) { _, _ -> }.finish())
    }

    @Test fun keepsTheMonitorFormat() {
        val recorder = EqSnippetRecorder(PcmMeterEncoding.PCM_24, 48_000, 2) { _, _ -> }
        recorder.append(buffer(48_000 * 6), 48_000 * 6)
        val snippet = recorder.finish()!!
        assertEquals(PcmMeterEncoding.PCM_24, snippet.encoding)
        assertEquals(48_000, snippet.sampleRateHz)
        assertEquals(2, snippet.channels)
        assertEquals(1_000, snippet.durationMs)
    }

    @Test fun neverStoresAPartialFrameAndLeavesTheBufferAlone() {
        val recorder = EqSnippetRecorder(PcmMeterEncoding.PCM_16, 8_000, 2, minMs = 0) { _, _ -> }
        val first = buffer(4_002).apply { position(3) }
        recorder.append(first, 4_002)
        assertEquals(3, first.position())
        recorder.append(buffer(6, first = 100), 6)
        val snippet = recorder.finish()!!
        assertEquals(4_004, snippet.byteCount)
        assertEquals(0, snippet.byteCount % 4)
        for (index in 0 until 4_000) assertEquals(index.toByte(), snippet.pcm[index])
        // The second buffer's whole frame follows the first's last whole frame.
        for (index in 0 until 4) assertEquals((100 + index).toByte(), snippet.pcm[4_000 + index])
    }

    @Test fun ignoresBuffersAfterItFinishes() {
        val recorder = EqSnippetRecorder(PcmMeterEncoding.PCM_16, 8_000, 1) { _, _ -> }
        recorder.append(buffer(16_000), 16_000)
        val snippet = recorder.finish()!!
        val before = snippet.pcm.copyOf(snippet.byteCount)
        recorder.append(buffer(640, first = 7), 640)
        assertEquals(16_000, snippet.byteCount)
        assertArrayEquals(before, snippet.pcm.copyOf(snippet.byteCount))
    }
}
