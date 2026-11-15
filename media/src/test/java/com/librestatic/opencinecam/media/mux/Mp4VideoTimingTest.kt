/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.media.mux

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Mp4VideoTimingTest {
    /** Under the module's build directory: /tmp is RAM-backed on the dev hosts. */
    @get:Rule
    val temp = TemporaryFolder(File("build/tmp/mp4-timing-test").apply { mkdirs() })

    private fun readTiming(spec: TestMp4Spec): Mp4VideoTiming {
        val file = temp.newFile()
        file.writeBytes(TestMp4.build(spec))
        return withMp4(file) { readMp4VideoTiming(it) }
    }

    @Test
    fun plainStts() {
        val timing = readTiming(TestMp4Spec(stts = listOf(Run(30, 40))))

        assertEquals(30L, timing.sampleCount)
        assertEquals(1_160_000L, timing.lastSampleTimeUs)
        assertEquals(1_200_000L, timing.durationUs)
        assertEquals(1_000, timing.timescale)
    }

    @Test
    fun plainSttsWithA64BitMdatHeader() {
        val timing = readTiming(TestMp4Spec(stts = listOf(Run(30, 40)), largeMdatHeader = true))

        assertEquals(1_160_000L, timing.lastSampleTimeUs)
    }

    @Test
    fun severalSttsRuns() {
        val timing = readTiming(TestMp4Spec(stts = listOf(Run(10, 40), Run(5, 80), Run(3, 20))))

        assertEquals(18L, timing.sampleCount)
        assertEquals(840_000L, timing.lastSampleTimeUs)
        assertEquals(860_000L, timing.durationUs)
    }

    @Test
    fun unsignedCompositionOffsets() {
        // Decode 0, 40, 80, 120, 160; present 80, 40, 200, 160, 200.
        val timing = readTiming(TestMp4Spec(stts = listOf(Run(5, 40)), ctts = listOf(Run(1, 80), Run(1, 0), Run(1, 120), Run(2, 40))))

        assertEquals(5L, timing.sampleCount)
        assertEquals(200_000L, timing.lastSampleTimeUs)
        assertEquals(200_000L, timing.durationUs)
    }

    @Test
    fun signedCompositionOffsets() {
        // Decode 0, 40, 80, 120; present 40, 80, 160, 100.
        val timing = readTiming(TestMp4Spec(stts = listOf(Run(4, 40)), ctts = listOf(Run(2, 40), Run(1, 80), Run(1, -20)), cttsVersion = 1))

        assertEquals(160_000L, timing.lastSampleTimeUs)
    }

    @Test
    fun editMediaTimeIsTheTimelineZero() {
        val timing = readTiming(TestMp4Spec(stts = listOf(Run(10, 40)), edits = listOf(TestEdit(320, 80))))

        assertEquals(280_000L, timing.lastSampleTimeUs)
        assertEquals(320_000L, timing.durationUs)
    }

    @Test
    fun initialEmptyEditDelaysTheTrack() {
        val timing = readTiming(
            TestMp4Spec(
                stts = listOf(Run(10, 40)),
                edits = listOf(TestEdit(300, -1), TestEdit(240, 0)),
                elstVersion = 1,
                movieTimescale = 600,
                mvhdVersion = 1,
                mdhdVersion = 1,
            ),
        )

        assertEquals(10L, timing.sampleCount)
        assertEquals(860_000L, timing.lastSampleTimeUs)
        assertEquals(900_000L, timing.durationUs)
    }

    @Test
    fun noVideoTrackIsRefused() {
        val file = temp.newFile()
        // The video track's handler retyped to sound: the first "vide" in the file is its hdlr.
        val patched = TestMp4.build(TestMp4Spec())
        val at = indexOf(patched, "vide".toByteArray(Charsets.ISO_8859_1))
        "soun".toByteArray(Charsets.ISO_8859_1).copyInto(patched, at)
        file.writeBytes(patched)
        val refusal = assertThrows(Mp4FormatException::class.java) {
            withMp4(file) { readMp4VideoTiming(it) }
        }
        assertEquals("no-video-track", refusal.reason)
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        for (i in 0..haystack.size - needle.size) {
            if (needle.indices.all { haystack[i + it] == needle[it] }) return i
        }
        error("not found")
    }
}
