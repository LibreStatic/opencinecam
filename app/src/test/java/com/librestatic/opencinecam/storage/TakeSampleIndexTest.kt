/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** PLAN-072, ADR-0037: the append-only side index of a take. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class TakeSampleIndexTest {
    @get:Rule val folder = TemporaryFolder()

    private val video = TakeTrackInfo(
        mime = "video/hevc", width = 3840, height = 2160, rotationDegrees = 90,
        colorStandard = 6, colorRange = 2, colorTransfer = 7, pixelAspectWidth = 4, pixelAspectHeight = 3,
        csd0 = byteArrayOf(0, 0, 0, 1, 0x40, 1, 9), csd1 = byteArrayOf(1, 2), csd2 = byteArrayOf(3),
    )
    private val audio = TakeTrackInfo(mime = "audio/mp4a-latm", sampleRate = 48000, channelCount = 2, encoderDelayFrames = 1105, csd0 = byteArrayOf(0x11, 0x90.toByte()))

    private fun write(file: File) {
        TakeSampleIndexWriter(file).use {
            it.writeTrack(TrackKind.VIDEO, video)
            it.writeTrack(TrackKind.AUDIO, audio)
            it.writeSample(TrackKind.VIDEO, 1000, 0, 1)
            it.writeSample(TrackKind.AUDIO, 300, 21_333, 1)
            it.writeSample(TrackKind.VIDEO, 500, 33_333, 0)
        }
    }

    @Test fun roundTrip() {
        val file = folder.newFile("take.ocix")
        write(file)
        val index = readTakeSampleIndex(file)!!
        val v = index.tracks.getValue(TrackKind.VIDEO)
        assertEquals("video/hevc", v.mime)
        assertEquals(3840, v.width)
        assertEquals(90, v.rotationDegrees)
        assertEquals(6, v.colorStandard)
        assertEquals(7, v.colorTransfer)
        assertEquals(4, v.pixelAspectWidth)
        assertNull(v.encoderDelayFrames)
        assertArrayEquals(video.csd0, v.csd0)
        assertArrayEquals(video.csd2, v.csd2)
        val a = index.tracks.getValue(TrackKind.AUDIO)
        assertEquals(48000, a.sampleRate)
        assertEquals(1105, a.encoderDelayFrames)
        assertNull(a.csd1)
        assertEquals(listOf(1000, 500), index.samplesOf(TrackKind.VIDEO).map { it.size })
        assertEquals(listOf(true, false), index.samplesOf(TrackKind.VIDEO).map { it.isKeyFrame })
        assertEquals(listOf(0L, 33_333L), index.samplesOf(TrackKind.VIDEO).map { it.presentationTimeUs })
        assertEquals(1, index.samplesOf(TrackKind.AUDIO).size)
    }

    @Test fun tornTrailingRecordIsIgnored() {
        val file = folder.newFile("torn.ocix")
        write(file)
        val whole = file.readBytes()
        for (cut in 1 until TakeSampleIndexWriter.SAMPLE_RECORD_BYTES) {
            file.writeBytes(whole.copyOf(whole.size - cut))
            val index = readTakeSampleIndex(file)!!
            assertEquals(1, index.samplesOf(TrackKind.VIDEO).size)
            assertEquals(1, index.samplesOf(TrackKind.AUDIO).size)
        }
    }

    @Test fun flushReachesTheFileWithoutClosing() {
        val file = folder.newFile("flush.ocix")
        val writer = TakeSampleIndexWriter(file)
        writer.writeTrack(TrackKind.AUDIO, audio)
        writer.writeSample(TrackKind.AUDIO, 10, 0, 1)
        writer.flush()
        assertEquals(1, readTakeSampleIndex(file)!!.samplesOf(TrackKind.AUDIO).size)
        writer.close()
        writer.writeSample(TrackKind.AUDIO, 10, 0, 1) // ignored after close
        assertEquals(1, readTakeSampleIndex(file)!!.samplesOf(TrackKind.AUDIO).size)
    }

    @Test fun badMagicOrVersionGivesNull() {
        val file = folder.newFile("bad.ocix")
        write(file)
        val bytes = file.readBytes()
        file.writeBytes(bytes.copyOf().also { it[0] = 'X'.code.toByte() })
        assertNull(readTakeSampleIndex(file))
        file.writeBytes(bytes.copyOf().also { it[4] = 2 })
        assertNull(readTakeSampleIndex(file))
        file.writeBytes(byteArrayOf(1, 2))
        assertNull(readTakeSampleIndex(file))
        assertNull(readTakeSampleIndex(File(folder.root, "missing")))
        file.writeBytes(bytes)
        assertNotNull(readTakeSampleIndex(file))
        assertTrue(bytes.size > 5)
    }
}
