/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** The biquad shapes of the RBJ Audio EQ Cookbook an equaliser point can take. */
enum class EqFilterType { BELL, LOW_SHELF, HIGH_SHELF, HIGH_PASS, LOW_PASS, NOTCH }

/**
 * One point of the input equaliser curve (PLAN-074 decision 6). [gainDb] is ignored by
 * [EqFilterType.HIGH_PASS], [EqFilterType.LOW_PASS] and [EqFilterType.NOTCH], but must still be
 * in range so a type change keeps a valid point.
 */
data class EqPoint(
    val type: EqFilterType,
    val frequencyHz: Double,
    val gainDb: Double,
    val q: Double,
    val enabled: Boolean = true,
) {
    init {
        require(frequencyHz in FREQUENCY_RANGE_HZ) { "Equaliser frequency must be between 20 Hz and 20 kHz." }
        require(gainDb in GAIN_RANGE_DB) { "Equaliser gain must be between -18 and +18 dB." }
        require(q in Q_RANGE) { "Equaliser Q must be between 0.1 and 10." }
    }

    /** True when the point, applied, changes the signal; a 0 dB bell or shelf is the identity. */
    val changesSignal: Boolean
        get() = enabled && (!type.usesGain || gainDb != 0.0)

    companion object {
        const val MIN_FREQUENCY_HZ = 20.0
        const val MAX_FREQUENCY_HZ = 20_000.0
        const val MIN_GAIN_DB = -18.0
        const val MAX_GAIN_DB = 18.0
        const val MIN_Q = 0.1
        const val MAX_Q = 10.0
        val FREQUENCY_RANGE_HZ = MIN_FREQUENCY_HZ..MAX_FREQUENCY_HZ
        val GAIN_RANGE_DB = MIN_GAIN_DB..MAX_GAIN_DB
        val Q_RANGE = MIN_Q..MAX_Q

        /** Builds a point from unchecked values (an editor drag), clamped into range; NaN goes to the minimum. */
        fun coerced(type: EqFilterType, frequencyHz: Double, gainDb: Double, q: Double, enabled: Boolean = true): EqPoint =
            EqPoint(
                type,
                clamp(frequencyHz, FREQUENCY_RANGE_HZ),
                clamp(gainDb, GAIN_RANGE_DB),
                clamp(q, Q_RANGE),
                enabled,
            )

        private fun clamp(value: Double, range: ClosedFloatingPointRange<Double>): Double =
            if (value.isNaN()) range.start else value.coerceIn(range)
    }
}

/** Returns a copy of this point with [frequencyHz], [gainDb] and [q] clamped into range. */
fun EqPoint.coerced(
    frequencyHz: Double = this.frequencyHz,
    gainDb: Double = this.gainDb,
    q: Double = this.q,
): EqPoint = EqPoint.coerced(type, frequencyHz, gainDb, q, enabled)

/**
 * The whole input equaliser: a free list of up to [MAX_POINTS] points applied in order, then
 * [trimDb] of output trim. A disabled curve is bypassed entirely, trim included.
 */
data class EqCurve(
    val points: List<EqPoint> = emptyList(),
    val trimDb: Double = 0.0,
    val enabled: Boolean = true,
) {
    init {
        require(points.size <= MAX_POINTS) { "An equaliser curve holds at most $MAX_POINTS points." }
        require(trimDb in TRIM_RANGE_DB) { "Equaliser trim must be between -12 and +12 dB." }
    }

    /** True when processing leaves the signal untouched, so the engine returns bit-exact. */
    val isFlat: Boolean
        get() = !enabled || (trimDb == 0.0 && points.none { it.changesSignal })

    companion object {
        const val MAX_POINTS = 24
        const val MIN_TRIM_DB = -12.0
        const val MAX_TRIM_DB = 12.0
        val TRIM_RANGE_DB = MIN_TRIM_DB..MAX_TRIM_DB
    }
}

/**
 * Magnitude response in dB of this point at [frequencyHz], from the same coefficients the engine
 * runs. A disabled point reads 0 dB. A notch at its exact centre reads [RESPONSE_FLOOR_DB].
 */
fun EqPoint.responseDb(frequencyHz: Double, sampleRateHz: Int): Double {
    if (!enabled) return 0.0
    return biquad(this, sampleRateHz) { b0, b1, b2, a1, a2 -> biquadResponseDb(b0, b1, b2, a1, a2, frequencyHz, sampleRateHz) }
}

/**
 * Magnitude response in dB of the whole curve at [frequencyHz]: the enabled points' responses
 * plus the trim, as the engine applies them. A disabled curve reads 0 dB.
 */
fun EqCurve.responseDb(frequencyHz: Double, sampleRateHz: Int): Double {
    if (!enabled) return 0.0
    var total = trimDb
    for (index in points.indices) total += points[index].responseDb(frequencyHz, sampleRateHz)
    return total.coerceAtLeast(RESPONSE_FLOOR_DB)
}

/** The lowest response [responseDb] reports, for a notch's exact zero. */
const val RESPONSE_FLOOR_DB = -300.0

/**
 * The input equaliser engine: one biquad per curve point, double precision, transposed direct
 * form II, state per channel. [setCurve] may run on any thread; the audio thread adopts the new
 * coefficients at the start of its next [process] call, so a change lands at a buffer boundary.
 * Filter state is kept for the indices both curves share, so moving a point does not click.
 * Nothing is allocated per buffer.
 */
class InputEqualizer(private val sampleRateHz: Int, private val channels: Int) {
    init {
        require(sampleRateHz > 0) { "Sample rate must be positive." }
        require(channels in 1..2)
    }

    /** An immutable compiled curve, published whole through [published]. */
    private class Compiled(
        val curve: EqCurve,
        val flat: Boolean,
        val count: Int,
        val active: BooleanArray,
        /** b0, b1, b2, a1, a2 per point, normalised by a0. */
        val coefficients: DoubleArray,
        val trim: Double,
    )

    @Volatile private var published: Compiled = compile(EqCurve())
    private var adopted: Compiled = published

    /** state[(point * channels + channel) * 2 + 0/1]: the two TDF-II delay values. */
    private val state = DoubleArray(EqCurve.MAX_POINTS * channels * 2)

    /** The audio thread's little-endian view of the last buffer [process] filtered, and that buffer. */
    private var source: ByteBuffer? = null
    private var view: ByteBuffer? = null

    /** The last curve passed to [setCurve]. */
    val curve: EqCurve get() = published.curve

    fun setCurve(curve: EqCurve) {
        published = compile(curve)
    }

    /**
     * Filters complete interleaved little-endian PCM frames at [0, byteCount) in place. The
     * buffer's position, limit, mark and byte order are untouched. A flat curve does not touch
     * the bytes. Integer output rounds and saturates; float output keeps headroom above 1,
     * saturating only at the float range, and nonfinite float input is read as silence.
     */
    fun process(buffer: ByteBuffer, byteCount: Int, encoding: PcmMeterEncoding) {
        require(byteCount in 0..buffer.capacity())
        val frameBytes = encoding.bytesPerSample * channels
        require(byteCount % frameBytes == 0) { "The equaliser requires complete PCM frames." }
        adopt()
        val compiled = adopted
        if (compiled.flat || byteCount == 0) return
        require(!buffer.isReadOnly) { "The equaliser requires a writable PCM buffer." }
        // A read loop passes the same buffer every time, so its view is made once.
        val pcm = view.takeIf { source === buffer } ?: buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).also {
            source = buffer
            view = it
        }
        pcm.clear()
        pcm.limit(byteCount)
        val bytesPerSample = encoding.bytesPerSample
        var channel = 0
        var offset = 0
        while (offset < byteCount) {
            val input = when (encoding) {
                PcmMeterEncoding.PCM_16 -> pcm.getShort(offset).toDouble()
                PcmMeterEncoding.PCM_24 -> ((pcm.get(offset).toInt() and 255) or
                    ((pcm.get(offset + 1).toInt() and 255) shl 8) or (pcm.get(offset + 2).toInt() shl 16)).toDouble()
                PcmMeterEncoding.PCM_FLOAT -> pcm.getFloat(offset).let { if (it.isFinite()) it.toDouble() else 0.0 }
            }
            var value = filter(compiled, input, channel)
            if (!value.isFinite()) {
                resetChannel(channel)
                value = 0.0
            }
            when (encoding) {
                PcmMeterEncoding.PCM_16 -> pcm.putShort(offset, value.coerceIn(-32768.0, 32767.0).roundToInt().toShort())
                PcmMeterEncoding.PCM_24 -> {
                    val output = value.coerceIn(-8388608.0, 8388607.0).roundToInt()
                    pcm.put(offset, output.toByte())
                    pcm.put(offset + 1, (output shr 8).toByte())
                    pcm.put(offset + 2, (output shr 16).toByte())
                }
                PcmMeterEncoding.PCM_FLOAT ->
                    pcm.putFloat(offset, value.coerceIn(-Float.MAX_VALUE.toDouble(), Float.MAX_VALUE.toDouble()).toFloat())
            }
            offset += bytesPerSample
            channel = if (channel + 1 == channels) 0 else channel + 1
        }
    }

    private fun filter(compiled: Compiled, input: Double, channel: Int): Double {
        val coefficients = compiled.coefficients
        val active = compiled.active
        var x = input
        for (point in 0 until compiled.count) {
            if (!active[point]) continue
            val c = point * 5
            val s = (point * channels + channel) * 2
            val y = coefficients[c] * x + state[s]
            state[s] = coefficients[c + 1] * x - coefficients[c + 3] * y + state[s + 1]
            state[s + 1] = coefficients[c + 2] * x - coefficients[c + 4] * y
            x = y
        }
        return x * compiled.trim
    }

    /** Swaps in a newly published curve, keeping the state of the points both curves share. */
    private fun adopt() {
        val next = published
        val previous = adopted
        if (next === previous) return
        if (previous.flat) {
            // The flat curve ran no filters, so whatever state is left is stale.
            state.fill(0.0)
        } else {
            for (point in 0 until EqCurve.MAX_POINTS) {
                val kept = point < previous.count && point < next.count && (previous.active[point] || !next.active[point])
                if (!kept) resetPoint(point)
            }
        }
        adopted = next
    }

    private fun resetPoint(point: Int) {
        val start = point * channels * 2
        state.fill(0.0, start, start + channels * 2)
    }

    private fun resetChannel(channel: Int) {
        for (point in 0 until EqCurve.MAX_POINTS) {
            val s = (point * channels + channel) * 2
            state[s] = 0.0
            state[s + 1] = 0.0
        }
    }

    private fun compile(curve: EqCurve): Compiled {
        val count = curve.points.size
        val active = BooleanArray(count)
        val coefficients = DoubleArray(count * 5)
        for (index in 0 until count) {
            val point = curve.points[index]
            active[index] = point.enabled
            if (!point.enabled) continue
            biquad(point, sampleRateHz) { b0, b1, b2, a1, a2 ->
                val c = index * 5
                coefficients[c] = b0
                coefficients[c + 1] = b1
                coefficients[c + 2] = b2
                coefficients[c + 3] = a1
                coefficients[c + 4] = a2
            }
        }
        return Compiled(curve, curve.isFlat, count, active, coefficients, 10.0.pow(curve.trimDb / 20.0))
    }
}

private val EqFilterType.usesGain: Boolean
    get() = this == EqFilterType.BELL || this == EqFilterType.LOW_SHELF || this == EqFilterType.HIGH_SHELF

/** Highest corner frequency as a fraction of the sample rate; the cookbook formulas fold above it. */
private const val MAX_FREQUENCY_FRACTION = 0.49

/**
 * RBJ Audio EQ Cookbook coefficients of [point], normalised by a0, handed to [block] without
 * allocating. The frequency is clamped to [MAX_FREQUENCY_FRACTION] of the sample rate.
 */
private inline fun <R> biquad(
    point: EqPoint,
    sampleRateHz: Int,
    block: (b0: Double, b1: Double, b2: Double, a1: Double, a2: Double) -> R,
): R {
    val frequency = point.frequencyHz.coerceAtMost(MAX_FREQUENCY_FRACTION * sampleRateHz)
    val w0 = 2.0 * PI * frequency / sampleRateHz
    val cosW = cos(w0)
    val alpha = sin(w0) / (2.0 * point.q)
    val a = 10.0.pow(point.gainDb / 40.0)
    val b0: Double
    val b1: Double
    val b2: Double
    val a0: Double
    val a1: Double
    val a2: Double
    when (point.type) {
        EqFilterType.BELL -> {
            b0 = 1.0 + alpha * a; b1 = -2.0 * cosW; b2 = 1.0 - alpha * a
            a0 = 1.0 + alpha / a; a1 = -2.0 * cosW; a2 = 1.0 - alpha / a
        }
        EqFilterType.LOW_SHELF -> {
            val k = 2.0 * sqrt(a) * alpha
            b0 = a * ((a + 1) - (a - 1) * cosW + k); b1 = 2.0 * a * ((a - 1) - (a + 1) * cosW); b2 = a * ((a + 1) - (a - 1) * cosW - k)
            a0 = (a + 1) + (a - 1) * cosW + k; a1 = -2.0 * ((a - 1) + (a + 1) * cosW); a2 = (a + 1) + (a - 1) * cosW - k
        }
        EqFilterType.HIGH_SHELF -> {
            val k = 2.0 * sqrt(a) * alpha
            b0 = a * ((a + 1) + (a - 1) * cosW + k); b1 = -2.0 * a * ((a - 1) + (a + 1) * cosW); b2 = a * ((a + 1) + (a - 1) * cosW - k)
            a0 = (a + 1) - (a - 1) * cosW + k; a1 = 2.0 * ((a - 1) - (a + 1) * cosW); a2 = (a + 1) - (a - 1) * cosW - k
        }
        EqFilterType.HIGH_PASS -> {
            b0 = (1.0 + cosW) / 2.0; b1 = -(1.0 + cosW); b2 = b0
            a0 = 1.0 + alpha; a1 = -2.0 * cosW; a2 = 1.0 - alpha
        }
        EqFilterType.LOW_PASS -> {
            b0 = (1.0 - cosW) / 2.0; b1 = 1.0 - cosW; b2 = b0
            a0 = 1.0 + alpha; a1 = -2.0 * cosW; a2 = 1.0 - alpha
        }
        EqFilterType.NOTCH -> {
            b0 = 1.0; b1 = -2.0 * cosW; b2 = 1.0
            a0 = 1.0 + alpha; a1 = -2.0 * cosW; a2 = 1.0 - alpha
        }
    }
    return block(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
}

/** |H(e^jw)| in dB of a normalised biquad, floored at [RESPONSE_FLOOR_DB]. */
private fun biquadResponseDb(b0: Double, b1: Double, b2: Double, a1: Double, a2: Double, frequencyHz: Double, sampleRateHz: Int): Double {
    val w = 2.0 * PI * frequencyHz / sampleRateHz
    val cos1 = cos(w)
    val sin1 = sin(w)
    val cos2 = cos(2.0 * w)
    val sin2 = sin(2.0 * w)
    val numeratorRe = b0 + b1 * cos1 + b2 * cos2
    val numeratorIm = b1 * sin1 + b2 * sin2
    val denominatorRe = 1.0 + a1 * cos1 + a2 * cos2
    val denominatorIm = a1 * sin1 + a2 * sin2
    val power = (numeratorRe * numeratorRe + numeratorIm * numeratorIm) /
        (denominatorRe * denominatorRe + denominatorIm * denominatorIm)
    return (10.0 * log10(power)).coerceAtLeast(RESPONSE_FLOOR_DB)
}
