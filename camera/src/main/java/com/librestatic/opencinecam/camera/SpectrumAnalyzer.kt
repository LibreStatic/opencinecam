/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The input equaliser editor's live spectrum (PLAN-074 decision 13): the level per log-spaced band before
 * ([inputDb]) and after ([outputDb]) the equaliser, in dBFS, where a full-scale sine reads about 0 dB in its band.
 */
data class AudioSpectrum(
    val bandCentresHz: List<Float>,
    val inputDb: List<Float>,
    val outputDb: List<Float>,
    val capturedAtElapsedRealtimeMs: Long,
)

/**
 * Spectrum of the PCM a read loop sees before and after its equaliser, mixed to mono. Each side keeps the last
 * [fftSize] samples; a snapshot runs a Hann-windowed FFT on both and folds the bins into [bands] log-spaced bands
 * from 20 Hz to 20 kHz (or 0.49 of the rate). Call both methods on the read loop's thread; neither moves or changes
 * the buffer, and nothing is allocated per buffer once the loop's buffer is known, except a published snapshot.
 */
class SpectrumAnalyzer(
    private val encoding: PcmMeterEncoding,
    private val channels: Int,
    private val sampleRateHz: Int,
    private val bands: Int = 64,
    private val fftSize: Int = 2048,
    private val publishIntervalMs: Long = 66L,
) {
    private val input: Side
    private val output: Side
    private val window: DoubleArray
    private val cosTable: DoubleArray
    private val sinTable: DoubleArray
    private val bitReversed: IntArray
    private val bandFirstBin: IntArray
    private val bandEndBin: IntArray
    private val bandCentres: List<Float>
    private val real: DoubleArray
    private val imaginary: DoubleArray
    private val bandPower: DoubleArray

    /**
     * The bin power of a full-scale sine summed over its main lobe, which compensates the Hann window's gain: a sine
     * whose lobe falls inside one band reads 0 dB there.
     */
    private val fullScalePower: Double
    private var lastPublishedAtMs = Long.MIN_VALUE

    init {
        require(channels >= 1)
        require(sampleRateHz in 8_000..192_000)
        require(bands in 1..512)
        require(fftSize in 64..65_536 && fftSize and (fftSize - 1) == 0) { "The FFT size is a power of two from 64 to 65536." }
        require(publishIntervalMs >= 0L)
        input = Side(fftSize)
        output = Side(fftSize)
        window = DoubleArray(fftSize) { 0.5 - 0.5 * cos(2 * PI * it / fftSize) }
        cosTable = DoubleArray(fftSize / 2) { cos(2 * PI * it / fftSize) }
        sinTable = DoubleArray(fftSize / 2) { -sin(2 * PI * it / fftSize) }
        val bits = Integer.numberOfTrailingZeros(fftSize)
        bitReversed = IntArray(fftSize) { Integer.reverse(it) ushr (32 - bits) }
        real = DoubleArray(fftSize)
        imaginary = DoubleArray(fftSize)
        bandPower = DoubleArray(bands)
        // Parseval: the windowed sine of amplitude 1 has energy sum(w²) / 2, which the N-point FFT holds as
        // N · sum(w²) / 2 over both halves, so N · sum(w²) / 4 over the positive bins of its main lobe.
        fullScalePower = window.sumOf { it * it } * fftSize / 4.0
        val low = MIN_FREQUENCY_HZ
        val high = minOf(MAX_FREQUENCY_HZ, MAX_FREQUENCY_SHARE * sampleRateHz)
        val ratio = high / low
        val binHz = sampleRateHz.toDouble() / fftSize
        val lastBin = fftSize / 2
        bandFirstBin = IntArray(bands)
        bandEndBin = IntArray(bands)
        bandCentres = List(bands) { band ->
            val lower = low * ratio.pow(band.toDouble() / bands)
            val upper = low * ratio.pow((band + 1.0) / bands)
            val centre = sqrt(lower * upper)
            var first = ceil(lower / binHz).toInt()
            var end = ceil(upper / binHz).toInt().coerceAtMost(lastBin + 1)
            if (end <= first) {
                // Narrower than a bin: the band takes the bin nearest its centre.
                first = (centre / binHz).roundToInt().coerceIn(1, lastBin)
                end = first + 1
            }
            bandFirstBin[band] = first
            bandEndBin[band] = end
            centre.toFloat()
        }
    }

    /** The PCM as the microphone delivered it, before the equaliser. */
    fun observeInput(buffer: ByteBuffer, byteCount: Int) {
        input.observe(buffer, byteCount)
    }

    /**
     * The PCM after the equaliser. Returns a snapshot when both sides hold [fftSize] samples and [publishIntervalMs]
     * has passed since the last one (or the clock went back); otherwise null.
     */
    fun observeOutput(buffer: ByteBuffer, byteCount: Int, nowElapsedRealtimeMs: Long): AudioSpectrum? {
        output.observe(buffer, byteCount)
        if (input.filled < fftSize || output.filled < fftSize) return null
        if (lastPublishedAtMs != Long.MIN_VALUE && nowElapsedRealtimeMs >= lastPublishedAtMs &&
            nowElapsedRealtimeMs - lastPublishedAtMs < publishIntervalMs) return null
        lastPublishedAtMs = nowElapsedRealtimeMs
        return AudioSpectrum(bandCentres, spectrum(input), spectrum(output), nowElapsedRealtimeMs)
    }

    private fun spectrum(side: Side): List<Float> {
        val ring = side.ring
        val oldest = side.next
        for (index in 0 until fftSize) {
            val sample = ring[(oldest + index) and (fftSize - 1)] * window[index]
            val target = bitReversed[index]
            real[target] = sample
            imaginary[target] = 0.0
        }
        transform()
        bandPower.fill(0.0)
        for (band in 0 until bands) {
            var power = 0.0
            for (bin in bandFirstBin[band] until bandEndBin[band]) power += real[bin] * real[bin] + imaginary[bin] * imaginary[bin]
            bandPower[band] = power
        }
        return List(bands) { band ->
            val ratio = bandPower[band] / fullScalePower
            if (ratio <= 0.0) MIN_DB else (10.0 * log10(ratio)).coerceAtLeast(MIN_DB.toDouble()).toFloat()
        }
    }

    /** In-place iterative radix-2 FFT of [real] and [imaginary], already in bit-reversed order. */
    private fun transform() {
        var size = 2
        while (size <= fftSize) {
            val half = size / 2
            val step = fftSize / size
            var start = 0
            while (start < fftSize) {
                for (k in 0 until half) {
                    val wr = cosTable[k * step]
                    val wi = sinTable[k * step]
                    val a = start + k
                    val b = a + half
                    val tr = real[b] * wr - imaginary[b] * wi
                    val ti = real[b] * wi + imaginary[b] * wr
                    real[b] = real[a] - tr
                    imaginary[b] = imaginary[a] - ti
                    real[a] += tr
                    imaginary[a] += ti
                }
                start += size
            }
            size *= 2
        }
    }

    /** One side's ring of the last mono samples, and the view of the read loop's buffer it decodes through. */
    private inner class Side(size: Int) {
        val ring = DoubleArray(size)
        var next = 0
        var filled = 0
        private var source: ByteBuffer? = null
        private var view: ByteBuffer? = null

        fun observe(buffer: ByteBuffer, byteCount: Int) {
            val frameBytes = encoding.bytesPerSample * channels
            val usableBytes = byteCount.coerceAtMost(buffer.capacity()) / frameBytes * frameBytes
            if (usableBytes <= 0) return
            // A read loop passes the same buffer every time, so its view is made once.
            val pcm = view.takeIf { source === buffer } ?: buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).also {
                source = buffer
                view = it
            }
            pcm.clear()
            pcm.limit(usableBytes)
            val mask = ring.size - 1
            while (pcm.remaining() >= frameBytes) {
                var sum = 0.0
                for (channel in 0 until channels) sum += readPcmSample(pcm, encoding)
                ring[next] = sum / channels
                next = (next + 1) and mask
                if (filled < ring.size) filled++
            }
        }
    }

    companion object {
        const val MIN_DB = -120f
        private const val MIN_FREQUENCY_HZ = 20.0
        private const val MAX_FREQUENCY_HZ = 20_000.0
        private const val MAX_FREQUENCY_SHARE = 0.49
    }
}
