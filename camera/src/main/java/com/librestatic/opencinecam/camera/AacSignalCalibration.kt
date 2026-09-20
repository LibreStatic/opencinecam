/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/** Known nonperiodic test signal; never microphone data or inserted into a recording. */
fun aacCalibrationSignal(frames: Int, rate: Int, channels: Int): ShortArray {
    require(frames in 16384..65536 && rate in 8000..192000 && channels in 1..2)
    return ShortArray(frames * channels) { index ->
        val frame = index / channels; val channel = index % channels
        val phase = 2.0 * PI * ((317.0 + channel * 223) * frame / rate +
            (1900.0 + channel * 113) * frame.toDouble() * frame / (2.0 * rate * frames))
        (sin(phase) * 12000).toInt().toShort()
    }
}

data class AacSignalAlignment(val lagFrames: Int, val minimumCorrelation: Double)

/** Correlate each channel independently, then require the full source, including its last block. */
fun measureAacSignal(source: ShortArray, decoded: ShortArray, channels: Int): AacSignalAlignment {
    require(channels in 1..2 && source.size % channels == 0 && decoded.size % channels == 0)
    val sourceFrames = source.size / channels; val decodedFrames = decoded.size / channels
    require(sourceFrames >= 16384 && decodedFrames >= 16384)
    var commonLag: Int? = null
    var minimum = 1.0
    fun correlation(channel: Int, start: Int, count: Int, lag: Int, step: Int = 1): Double {
        val first = maxOf(start, -lag); val last = minOf(start + count, sourceFrames, decodedFrames - lag)
        if (last - first < 64) return -1.0
        var aa = 0L; var bb = 0L; var ab = 0L
        for (frame in first until last step step) {
            val a = source[frame * channels + channel].toLong()
            val b = decoded[(frame + lag) * channels + channel].toLong()
            aa += a * a; bb += b * b; ab += a * b
        }
        return if (aa > 0 && bb > 0) ab / sqrt(aa.toDouble() * bb) else 0.0
    }
    for (channel in 0 until channels) {
        val lag = (-8192..8192).maxBy { correlation(channel, 8192, 2048, it, 8) }
        require(lag >= 0 && decodedFrames - lag >= sourceFrames) { "AAC probe lost source samples" }
        require(commonLag == null || commonLag == lag) { "AAC channel delay differs" }
        commonLag = lag
        for ((start, count) in listOf(0 to 2048, sourceFrames / 2 to 2048, sourceFrames - 2048 to 2048, 0 to sourceFrames)) {
            minimum = minOf(minimum, correlation(channel, start, count, lag))
        }
    }
    require(minimum >= 0.98) { "AAC source waveform did not meet the calibration correlation threshold" }
    return AacSignalAlignment(requireNotNull(commonLag), minimum)
}
