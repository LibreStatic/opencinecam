/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class InputEqualizerTest {
    private val rate = 48_000

    private fun bytes(buffer: ByteBuffer): ByteArray = ByteArray(buffer.capacity()).also { buffer.duplicate().apply { clear() }.get(it) }

    private fun tone(frequencyHz: Double, seconds: Double, amplitude: Double = 0.5, phase: Double = 0.0): DoubleArray =
        DoubleArray((seconds * rate).toInt()) { amplitude * sin(2 * PI * frequencyHz * it / rate + phase) }

    private fun encode(samples: DoubleArray, encoding: PcmMeterEncoding): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(samples.size * encoding.bytesPerSample).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEachIndexed { index, value ->
            val offset = index * encoding.bytesPerSample
            when (encoding) {
                PcmMeterEncoding.PCM_16 -> buffer.putShort(offset, (value * 32768).coerceIn(-32768.0, 32767.0).roundToInt().toShort())
                PcmMeterEncoding.PCM_24 -> {
                    val v = (value * 8388608).coerceIn(-8388608.0, 8388607.0).roundToInt()
                    buffer.put(offset, v.toByte()); buffer.put(offset + 1, (v shr 8).toByte()); buffer.put(offset + 2, (v shr 16).toByte())
                }
                PcmMeterEncoding.PCM_FLOAT -> buffer.putFloat(offset, value.toFloat())
            }
        }
        return buffer
    }

    private fun decode(buffer: ByteBuffer, encoding: PcmMeterEncoding): DoubleArray {
        val pcm = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        return DoubleArray(buffer.capacity() / encoding.bytesPerSample) {
            val offset = it * encoding.bytesPerSample
            when (encoding) {
                PcmMeterEncoding.PCM_16 -> pcm.getShort(offset) / 32768.0
                PcmMeterEncoding.PCM_24 -> ((pcm.get(offset).toInt() and 255) or ((pcm.get(offset + 1).toInt() and 255) shl 8) or
                    (pcm.get(offset + 2).toInt() shl 16)) / 8388608.0
                PcmMeterEncoding.PCM_FLOAT -> pcm.getFloat(offset).toDouble()
            }
        }
    }

    private fun run(curve: EqCurve, samples: DoubleArray, encoding: PcmMeterEncoding = PcmMeterEncoding.PCM_FLOAT, channels: Int = 1): DoubleArray {
        val buffer = encode(samples, encoding)
        InputEqualizer(rate, channels).apply { setCurve(curve) }.process(buffer, buffer.capacity(), encoding)
        return decode(buffer, encoding)
    }

    /** Amplitude at an integer frequency over the last second, which holds a whole number of cycles. */
    private fun amplitude(samples: DoubleArray, frequencyHz: Double): Double {
        var s = 0.0; var c = 0.0
        for (n in samples.size - rate until samples.size) {
            val w = 2 * PI * frequencyHz * n / rate
            s += samples[n] * sin(w); c += samples[n] * kotlin.math.cos(w)
        }
        return 2.0 / rate * sqrt(s * s + c * c)
    }

    private fun measuredDb(curve: EqCurve, frequencyHz: Double): Double {
        val input = tone(frequencyHz, 1.5)
        return 20 * log10(amplitude(run(curve, input), frequencyHz) / amplitude(input, frequencyHz))
    }

    private fun single(type: EqFilterType, frequencyHz: Double, gainDb: Double = 0.0, q: Double = 0.7071) =
        EqCurve(listOf(EqPoint(type, frequencyHz, gainDb, q)))

    private val probeFrequencies = doubleArrayOf(50.0, 120.0, 400.0, 1000.0, 2500.0, 6000.0, 12000.0, 19000.0)

    private fun assertMatchesResponse(curve: EqCurve) {
        for (f in probeFrequencies) {
            val expected = curve.responseDb(f, rate)
            if (expected < -60) continue
            assertEquals("${curve.points} at $f Hz", expected, measuredDb(curve, f), 0.1)
        }
    }

    @Test fun everyFilterTypeMeasuresAsItsAnalyticResponse() {
        val curves = listOf(
            single(EqFilterType.BELL, 1000.0, 9.0, 2.0),
            single(EqFilterType.BELL, 400.0, -12.0, 0.5),
            single(EqFilterType.LOW_SHELF, 200.0, 6.0),
            single(EqFilterType.HIGH_SHELF, 4000.0, -8.0),
            single(EqFilterType.HIGH_PASS, 400.0),
            single(EqFilterType.LOW_PASS, 2500.0, q = 2.0),
            single(EqFilterType.NOTCH, 1000.0, q = 4.0),
            EqCurve(
                listOf(
                    EqPoint(EqFilterType.HIGH_PASS, 80.0, 0.0, 0.7071),
                    EqPoint(EqFilterType.BELL, 2500.0, 4.0, 1.4),
                    EqPoint(EqFilterType.HIGH_SHELF, 8000.0, -3.0, 0.7071),
                ),
                trimDb = -2.5,
            ),
        )
        curves.forEach(::assertMatchesResponse)
    }

    @Test fun knownAnalyticValues() {
        assertEquals(9.0, single(EqFilterType.BELL, 1000.0, 9.0, 2.0).responseDb(1000.0, rate), 1e-9)
        assertEquals(9.0, measuredDb(single(EqFilterType.BELL, 1000.0, 9.0, 2.0), 1000.0), 0.05)
        assertEquals(-12.0, single(EqFilterType.BELL, 3000.0, -12.0, 0.3).responseDb(3000.0, rate), 1e-9)

        val lowShelf = single(EqFilterType.LOW_SHELF, 500.0, 6.0)
        assertEquals(6.0, lowShelf.responseDb(20.0, rate), 0.05)
        assertEquals(0.0, lowShelf.responseDb(20000.0, rate), 0.05)
        assertEquals(6.0, measuredDb(lowShelf, 20.0), 0.1)
        val highShelf = single(EqFilterType.HIGH_SHELF, 2000.0, -9.0)
        assertEquals(-9.0, highShelf.responseDb(20000.0, rate), 0.05)
        assertEquals(0.0, highShelf.responseDb(20.0, rate), 0.05)
        assertEquals(0.0, measuredDb(highShelf, 50.0), 0.05)

        val cornerDb = 20 * log10(sqrt(0.5))
        assertEquals(cornerDb, single(EqFilterType.HIGH_PASS, 1000.0, q = sqrt(0.5)).responseDb(1000.0, rate), 1e-6)
        assertEquals(cornerDb, single(EqFilterType.LOW_PASS, 1000.0, q = sqrt(0.5)).responseDb(1000.0, rate), 1e-6)
        assertEquals(cornerDb, measuredDb(single(EqFilterType.HIGH_PASS, 1000.0, q = sqrt(0.5)), 1000.0), 0.05)
        assertEquals(cornerDb, measuredDb(single(EqFilterType.LOW_PASS, 1000.0, q = sqrt(0.5)), 1000.0), 0.05)

        val notch = single(EqFilterType.NOTCH, 1000.0, q = 2.0)
        assertTrue(notch.responseDb(1000.0, rate) < -40)
        assertTrue(measuredDb(notch, 1000.0) < -40)
        // Gain is ignored by the pass and notch filters.
        assertEquals(notch.responseDb(700.0, rate), single(EqFilterType.NOTCH, 1000.0, 18.0, 2.0).responseDb(700.0, rate), 1e-12)

        val trimmed = EqCurve(trimDb = 6.0)
        assertEquals(6.0, trimmed.responseDb(1000.0, rate), 1e-12)
        assertEquals(6.0, measuredDb(trimmed, 1000.0), 0.01)
    }

    @Test fun responseIgnoresDisabledPointsAndDisabledCurve() {
        val bell = EqPoint(EqFilterType.BELL, 1000.0, 6.0, 1.0)
        assertEquals(0.0, bell.copy(enabled = false).responseDb(1000.0, rate), 0.0)
        assertEquals(0.0, EqCurve(listOf(bell), trimDb = 3.0, enabled = false).responseDb(1000.0, rate), 0.0)
        assertEquals(9.0, EqCurve(listOf(bell, bell.copy(enabled = false, gainDb = -18.0)), trimDb = 3.0).responseDb(1000.0, rate), 1e-9)
    }

    @Test fun frequenciesNearNyquistAreClamped() {
        // At 40 kHz the 20 kHz point would sit at Nyquist; it is moved down to 0.49 x 40 kHz.
        val point = EqPoint(EqFilterType.BELL, 20000.0, 6.0, 1.0)
        val atRate = 40_000
        assertEquals(6.0, point.responseDb(0.49 * atRate, atRate), 1e-9)
        assertEquals(
            EqPoint(EqFilterType.BELL, 0.49 * atRate, 6.0, 1.0).responseDb(1234.0, atRate),
            point.responseDb(1234.0, atRate), 1e-12,
        )
        val samples = DoubleArray(4000) { 0.3 * sin(2 * PI * 1234 * it / atRate) }
        val buffer = encode(samples, PcmMeterEncoding.PCM_FLOAT)
        InputEqualizer(atRate, 1).apply { setCurve(EqCurve(listOf(point))) }.process(buffer, buffer.capacity(), PcmMeterEncoding.PCM_FLOAT)
        assertTrue(decode(buffer, PcmMeterEncoding.PCM_FLOAT).all { it.isFinite() && abs(it) < 1.0 })
    }

    @Test fun flatAndDisabledCurvesAreBitExactForEveryEncoding() {
        val bell = EqPoint(EqFilterType.BELL, 1000.0, 6.0, 1.0)
        val flats = listOf(
            EqCurve(),
            EqCurve(listOf(bell.copy(gainDb = 0.0), EqPoint(EqFilterType.LOW_SHELF, 100.0, 0.0, 1.0))),
            EqCurve(listOf(bell, EqPoint(EqFilterType.HIGH_PASS, 100.0, 0.0, 1.0)), trimDb = 6.0, enabled = false),
            EqCurve(listOf(bell.copy(enabled = false), EqPoint(EqFilterType.NOTCH, 100.0, 0.0, 1.0, enabled = false))),
        )
        for (curve in flats) {
            assertTrue(curve.isFlat)
            for (encoding in PcmMeterEncoding.entries) for (channels in 1..2) {
                val buffer = ByteBuffer.allocateDirect(encoding.bytesPerSample * channels * 64)
                repeat(buffer.capacity()) { buffer.put(it, (it * 43 + 159).toByte()) }
                if (encoding == PcmMeterEncoding.PCM_FLOAT) buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).putFloat(0, Float.NaN)
                val original = bytes(buffer)
                val eq = InputEqualizer(rate, channels)
                eq.setCurve(curve)
                eq.process(buffer, buffer.capacity(), encoding)
                assertArrayEquals(original, bytes(buffer))
                eq.process(buffer.asReadOnlyBuffer(), buffer.capacity(), encoding)
            }
        }
        assertFalse(EqCurve(listOf(EqPoint(EqFilterType.HIGH_PASS, 100.0, 0.0, 1.0))).isFlat)
        assertFalse(EqCurve(trimDb = -1.0).isFlat)
        assertFalse(EqCurve(listOf(bell)).isFlat)
    }

    @Test fun integerOutputSaturatesWithoutWrapping() {
        val curve = single(EqFilterType.BELL, 1000.0, 18.0, 1.0)
        val input = tone(1000.0, 0.1, amplitude = 32767.0 / 32768.0)
        val reference = run(curve, input, PcmMeterEncoding.PCM_FLOAT)
        for (encoding in listOf(PcmMeterEncoding.PCM_16, PcmMeterEncoding.PCM_24)) {
            val output = run(curve, input, encoding)
            val lsb = if (encoding == PcmMeterEncoding.PCM_16) 1 / 32768.0 else 1 / 8388608.0
            var clipped = 0
            for (n in output.indices) {
                val expected = reference[n].coerceIn(-1.0, 1.0 - lsb)
                assertEquals("$encoding sample $n", expected, output[n], 3 * lsb)
                if (abs(output[n]) >= 1.0 - lsb) clipped++
            }
            assertTrue(clipped > output.size / 4)
        }
        assertTrue(reference.any { abs(it) > 4.0 }) // float keeps the headroom
    }

    @Test fun stereoChannelsAreFilteredIndependently() {
        val curve = EqCurve(listOf(EqPoint(EqFilterType.BELL, 800.0, 8.0, 3.0), EqPoint(EqFilterType.HIGH_PASS, 300.0, 0.0, 0.9)), trimDb = -3.0)
        val random = Random(7)
        val left = tone(440.0, 0.2)
        val right = DoubleArray(left.size) { random.nextDouble(-0.4, 0.4) }
        for (encoding in PcmMeterEncoding.entries) {
            val stereo = DoubleArray(left.size * 2) { if (it % 2 == 0) left[it / 2] else right[it / 2] }
            val processed = run(curve, stereo, encoding, channels = 2)
            val monoLeft = run(curve, left, encoding)
            val monoRight = run(curve, right, encoding)
            for (n in left.indices) {
                assertEquals(monoLeft[n], processed[2 * n], 0.0)
                assertEquals(monoRight[n], processed[2 * n + 1], 0.0)
            }
        }
    }

    @Test fun everyEncodingAgreesWithinQuantisation() {
        val curve = EqCurve(listOf(EqPoint(EqFilterType.LOW_SHELF, 150.0, 5.0, 0.7), EqPoint(EqFilterType.BELL, 3000.0, -6.0, 2.0)), trimDb = 1.5)
        val random = Random(11)
        val input = DoubleArray(9600) { 0.3 * sin(2 * PI * 330 * it / rate) + random.nextDouble(-0.1, 0.1) }
        val float = run(curve, input, PcmMeterEncoding.PCM_FLOAT)
        val pcm24 = run(curve, input, PcmMeterEncoding.PCM_24)
        val pcm16 = run(curve, input, PcmMeterEncoding.PCM_16)
        for (n in input.indices) {
            assertEquals(float[n], pcm24[n], 1e-5)
            assertEquals(float[n], pcm16[n], 8 / 32768.0)
        }
    }

    @Test fun splitBuffersMatchOneLongCall() {
        val curve = EqCurve(listOf(EqPoint(EqFilterType.BELL, 120.0, 10.0, 8.0), EqPoint(EqFilterType.LOW_PASS, 5000.0, 0.0, 0.7)))
        val random = Random(3)
        val input = DoubleArray(4800 * 2) { random.nextDouble(-0.5, 0.5) }
        for (encoding in PcmMeterEncoding.entries) {
            val whole = encode(input, encoding)
            InputEqualizer(rate, 2).apply { setCurve(curve) }.process(whole, whole.capacity(), encoding)
            val split = encode(input, encoding)
            val eq = InputEqualizer(rate, 2).apply { setCurve(curve) }
            val frameBytes = encoding.bytesPerSample * 2
            var start = 0
            for (frames in generateSequence(1) { it * 3 % 997 + 1 }) {
                if (start >= split.capacity()) break
                val end = minOf(split.capacity(), start + frames * frameBytes)
                val slice = split.duplicate().apply { position(start); limit(end) }.slice()
                eq.process(slice, end - start, encoding)
                start = end
            }
            assertArrayEquals(bytes(whole), bytes(split))
        }
    }

    @Test fun changingTheCurveBetweenBuffersDoesNotClick() {
        val frequency = 1000.0
        val input = tone(frequency, 0.4)
        val buffer = encode(input, PcmMeterEncoding.PCM_FLOAT)
        val eq = InputEqualizer(rate, 1)
        val gains = doubleArrayOf(6.0, 7.0, 5.5, 6.5)
        val blockBytes = 4800 * 4
        for (block in 0 until input.size / 4800) {
            eq.setCurve(single(EqFilterType.BELL, frequency, gains[block % gains.size], 1.0))
            val slice = buffer.duplicate().apply { position(block * blockBytes); limit((block + 1) * blockBytes) }.slice()
            eq.process(slice, blockBytes, PcmMeterEncoding.PCM_FLOAT)
        }
        val output = decode(buffer, PcmMeterEncoding.PCM_FLOAT)
        // A sine of peak A never steps by more than A * 2 sin(pi f / fs) between samples.
        val loudest = 0.5 * 10.0.pow(7.0 / 20)
        val maxStep = loudest * 2 * sin(PI * frequency / rate)
        for (n in 1 until output.size) assertTrue("step at $n", abs(output[n] - output[n - 1]) <= maxStep * 1.05)
        // Adding a point keeps the state of the existing one: the first stays continuous.
        val again = encode(input, PcmMeterEncoding.PCM_FLOAT)
        val eq2 = InputEqualizer(rate, 1)
        val base = EqPoint(EqFilterType.BELL, frequency, 6.0, 1.0)
        eq2.setCurve(EqCurve(listOf(base)))
        eq2.process(again.duplicate().apply { limit(blockBytes) }.slice(), blockBytes, PcmMeterEncoding.PCM_FLOAT)
        eq2.setCurve(EqCurve(listOf(base, EqPoint(EqFilterType.LOW_SHELF, 60.0, 0.0, 0.7))))
        eq2.process(again.duplicate().apply { position(blockBytes); limit(2 * blockBytes) }.slice(), blockBytes, PcmMeterEncoding.PCM_FLOAT)
        val reference = run(EqCurve(listOf(base)), input.copyOf(9600))
        val continued = decode(again, PcmMeterEncoding.PCM_FLOAT)
        for (n in 0 until 9600) assertEquals(reference[n], continued[n], 1e-6)
        assertEquals(EqCurve(listOf(base, EqPoint(EqFilterType.LOW_SHELF, 60.0, 0.0, 0.7))), eq2.curve)
    }

    @Test fun nonfiniteFloatInputIsSilenceAndIntegersRoundTrip() {
        val buffer = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).apply {
            putFloat(0, Float.NaN); putFloat(4, Float.POSITIVE_INFINITY); putFloat(8, 0f); putFloat(12, 0f)
        }
        InputEqualizer(rate, 1).apply { setCurve(EqCurve(trimDb = 6.0)) }.process(buffer, 16, PcmMeterEncoding.PCM_FLOAT)
        for (i in 0 until 4) assertEquals(0f, buffer.getFloat(i * 4), 0f)
    }

    @Test fun processRejectsBadInputBeforeWriting() {
        val eq = InputEqualizer(rate, 2).apply { setCurve(EqCurve(trimDb = 3.0)) }
        for (encoding in PcmMeterEncoding.entries) {
            val buffer = ByteBuffer.allocate(encoding.bytesPerSample * 4)
            for (count in listOf(-1, buffer.capacity() + 1, encoding.bytesPerSample))
                assertThrows(IllegalArgumentException::class.java) { eq.process(buffer, count, encoding) }
        }
        assertThrows(IllegalArgumentException::class.java) { eq.process(ByteBuffer.allocate(4).asReadOnlyBuffer(), 4, PcmMeterEncoding.PCM_16) }
        eq.process(ByteBuffer.allocate(0).asReadOnlyBuffer(), 0, PcmMeterEncoding.PCM_16)
        assertThrows(IllegalArgumentException::class.java) { InputEqualizer(rate, 3) }
        assertThrows(IllegalArgumentException::class.java) { InputEqualizer(0, 1) }
    }

    @Test fun modelValidation() {
        val point = EqPoint(EqFilterType.BELL, 1000.0, 0.0, 1.0)
        EqCurve(List(EqCurve.MAX_POINTS) { point })
        assertThrows(IllegalArgumentException::class.java) { EqCurve(List(EqCurve.MAX_POINTS + 1) { point }) }
        for (trim in listOf(-12.01, 12.01, Double.NaN)) assertThrows(IllegalArgumentException::class.java) { EqCurve(trimDb = trim) }
        EqCurve(trimDb = -12.0); EqCurve(trimDb = 12.0)
        for (f in listOf(19.9, 20000.1, Double.NaN)) assertThrows(IllegalArgumentException::class.java) { point.copy(frequencyHz = f) }
        for (g in listOf(-18.1, 18.1, Double.NaN)) assertThrows(IllegalArgumentException::class.java) { point.copy(gainDb = g) }
        for (q in listOf(0.09, 10.1, Double.NaN)) assertThrows(IllegalArgumentException::class.java) { point.copy(q = q) }
        EqPoint(EqFilterType.LOW_PASS, 20.0, -18.0, 0.1); EqPoint(EqFilterType.HIGH_PASS, 20000.0, 18.0, 10.0)

        assertEquals(EqPoint(EqFilterType.NOTCH, 20.0, 18.0, 10.0, enabled = false),
            EqPoint.coerced(EqFilterType.NOTCH, 5.0, 40.0, 99.0, enabled = false))
        assertEquals(EqPoint(EqFilterType.BELL, 20000.0, -18.0, 0.1), EqPoint.coerced(EqFilterType.BELL, 1e9, -1e9, 0.0))
        assertEquals(EqPoint(EqFilterType.BELL, 20.0, -18.0, 0.1), EqPoint.coerced(EqFilterType.BELL, Double.NaN, Double.NaN, Double.NaN))
        assertEquals(point.copy(frequencyHz = 20000.0, q = 0.1), point.coerced(frequencyHz = 50000.0, q = -1.0))
        assertEquals(point, point.coerced())
    }
}
