/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.service

import com.librestatic.opencinecam.camera.AudioLevelSnapshot
import com.librestatic.opencinecam.camera.AudioSpectrum
import com.librestatic.opencinecam.camera.DigitalRecordingGain
import com.librestatic.opencinecam.camera.EqCurve
import com.librestatic.opencinecam.camera.EqFilterType
import com.librestatic.opencinecam.camera.EqPoint
import com.librestatic.opencinecam.camera.PcmMeterEncoding
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class EqSnippetChunkProcessorTest {
    private class Collected : EqSnippetOutput {
        val samples = mutableListOf<Short>()
        val spectra = mutableListOf<AudioSpectrum>()
        val levels = mutableListOf<AudioLevelSnapshot>()
        override fun onSpectrum(spectrum: AudioSpectrum) { spectra += spectrum }
        override fun onLevel(snapshot: AudioLevelSnapshot) { levels += snapshot }
        override fun write(pcm16: ByteBuffer) {
            val pcm = pcm16.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            while (pcm.hasRemaining()) samples += pcm.short
            // A track consumes what it plays.
            pcm16.position(pcm16.limit())
        }
    }

    private fun pcm16Snippet(values: IntArray, rate: Int = 8_000, channels: Int = 1): EqSnippet {
        val bytes = ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { bytes.putShort(it.toShort()) }
        return EqSnippet(bytes.array(), values.size * 2, PcmMeterEncoding.PCM_16, rate, channels)
    }

    private fun tone(frames: Int, rate: Int, amplitude: Double = 8_000.0, hz: Double = 440.0) =
        IntArray(frames) { (amplitude * sin(2 * PI * hz * it / rate)).roundToInt() }

    private fun run(processor: EqSnippetChunkProcessor, chunks: Int, startMs: Long = 0L) {
        repeat(chunks) { processor.processNext(startMs + it * 40L) }
    }

    @Test fun loopsAcrossTheSnippetsEnd() {
        val values = IntArray(500) { it }
        val output = Collected()
        // 8 kHz: 320 frames a chunk, so the second chunk spans the seam.
        val processor = EqSnippetChunkProcessor(pcm16Snippet(values), EqCurve(), DigitalRecordingGain(), output, seamFadeMs = 0)
        run(processor, 4)
        assertEquals(1_280, output.samples.size)
        output.samples.forEachIndexed { index, sample -> assertEquals(values[index % 500].toShort(), sample) }
    }

    @Test fun aDisabledCurveIsBitExact() {
        val values = tone(4_000, 8_000, amplitude = 20_000.0)
        val curve = EqCurve(points = listOf(EqPoint(EqFilterType.BELL, 1_000.0, 12.0, 1.0)), trimDb = -6.0, enabled = false)
        val output = Collected()
        run(EqSnippetChunkProcessor(pcm16Snippet(values), curve, DigitalRecordingGain(), output, seamFadeMs = 0), 10)
        output.samples.forEachIndexed { index, sample -> assertEquals(values[index].toShort(), sample) }
    }

    @Test fun aMinusSixDecibelTrimHalvesTheSamples() {
        val values = tone(4_000, 8_000, amplitude = 20_000.0)
        val output = Collected()
        run(EqSnippetChunkProcessor(pcm16Snippet(values), EqCurve(trimDb = HALF_DB), DigitalRecordingGain(), output, seamFadeMs = 0), 10)
        output.samples.forEachIndexed { index, sample -> assertTrue(abs(values[index] / 2.0 - sample) <= 1.0) }
    }

    @Test fun adoptsACurveChangeAtTheNextChunk() {
        val values = tone(4_000, 8_000, amplitude = 20_000.0)
        val output = Collected()
        val processor = EqSnippetChunkProcessor(pcm16Snippet(values), EqCurve(), DigitalRecordingGain(), output, seamFadeMs = 0)
        processor.processNext(0L)
        processor.setCurve(EqCurve(trimDb = HALF_DB))
        processor.processNext(40L)
        for (index in 0 until 320) assertEquals(values[index].toShort(), output.samples[index])
        for (index in 320 until 640) assertTrue(abs(values[index] / 2.0 - output.samples[index]) <= 1.0)
    }

    @Test fun appliesTheRecordingGainAfterTheEqualiser() {
        val values = tone(4_000, 8_000, amplitude = 4_000.0)
        val output = Collected()
        val gain = DigitalRecordingGain(enabled = true, decibels = 6)
        val processor = EqSnippetChunkProcessor(pcm16Snippet(values), EqCurve(trimDb = HALF_DB), gain, output, seamFadeMs = 0)
        run(processor, 2)
        // Half from the trim, then the gain's own factor, each rounded once.
        val factor = 0.5 * 10.0.pow(6 / 20.0)
        output.samples.forEachIndexed { index, sample -> assertTrue(abs(values[index] * factor - sample) <= 2.0) }
        processor.recordingGain = DigitalRecordingGain()
        processor.processNext(80L)
        for (index in 640 until 960) assertTrue(abs(values[index] / 2.0 - output.samples[index]) <= 1.0)
    }

    @Test fun publishesSpectraAndLevelsAsTheMonitorDoes() {
        val output = Collected()
        val gain = DigitalRecordingGain(enabled = true, decibels = 6)
        // 48 kHz: two 40 ms chunks fill the analyser's 2048 samples.
        val processor = EqSnippetChunkProcessor(pcm16Snippet(tone(48_000, 48_000, amplitude = 30_000.0), 48_000), EqCurve(), gain, output)
        run(processor, 10)
        assertTrue(output.spectra.isNotEmpty())
        assertEquals(output.spectra.first().inputDb.size, output.spectra.first().outputDb.size)
        assertTrue(output.levels.isNotEmpty())
        assertTrue(output.levels.all { it.appliedRecordingGain == gain })
        // The gain drives the full-scale tone into clipping after the equaliser, as a take would record it.
        assertTrue(output.levels.any { it.clipped && it.recordedClipped })
        // The meter sees the raw snippet as its input, which stays below full scale.
        assertFalse(output.levels.any { it.inputClipped })
    }

    @Test fun fadesTheSeamOnly() {
        val values = IntArray(4_000) { 10_000 }
        val output = Collected()
        // 5 ms at 8 kHz: 40 frames at each end.
        run(EqSnippetChunkProcessor(pcm16Snippet(values), EqCurve(), DigitalRecordingGain(), output), 13)
        assertTrue(output.samples[0] < 1_000)
        assertTrue(output.samples[3_999] < 1_000)
        assertTrue(output.samples[20] in 4_000..6_000)
        assertEquals(10_000.toShort(), output.samples[40])
        assertEquals(10_000.toShort(), output.samples[3_959])
        assertTrue(output.samples[4_000] < 1_000)
    }

    @Test fun convertsStereoFloatToSixteenBit() {
        val frames = 4_000
        val bytes = ByteBuffer.allocate(frames * 8).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames) { bytes.putFloat(0.5f); bytes.putFloat(-0.25f) }
        val snippet = EqSnippet(bytes.array(), frames * 8, PcmMeterEncoding.PCM_FLOAT, 8_000, 2)
        val output = Collected()
        run(EqSnippetChunkProcessor(snippet, EqCurve(), DigitalRecordingGain(), output, seamFadeMs = 0), 2)
        assertEquals(1_280, output.samples.size)
        output.samples.forEachIndexed { index, sample -> assertEquals(if (index % 2 == 0) 16_384 else -8_192, sample.toInt()) }
    }

    private companion object {
        /** A gain of exactly one half. */
        val HALF_DB = 20 * kotlin.math.log10(0.5)
    }
}
