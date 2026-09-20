/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import java.math.BigInteger
import java.util.Locale

/**
 * SMPTE timecode value in HH:MM:SS:FF format.
 * When [dropFrame] is true the canonical separator is ; instead of :.
 */
data class SmpteTimecode(
    val hours: Int,
    val minutes: Int,
    val seconds: Int,
    val frames: Int,
    val dropFrame: Boolean = false,
) {
    init {
        require(hours in 0..23)
        require(minutes in 0..59)
        require(seconds in 0..59)
        require(frames in 0..59)
    }

    fun format(): String {
        val sep = if (dropFrame) ";" else ":"
        return String.format(Locale.ROOT, "%02d:%02d:%02d%s%02d", hours, minutes, seconds, sep, frames)
    }

    fun toTotalFrames(rate: TimecodeRate): Long {
        require(dropFrame == rate.dropFrame && frames < rate.nominalFps) { "Timecode label does not match rate" }
        require(!dropFrame || minutes % 10 == 0 || seconds != 0 || frames >= rate.droppedLabelsPerMinute) {
            "Timecode label is omitted by drop-frame numbering"
        }
        val totalMinutes = hours * 60L + minutes
        val nominalFrames = ((totalMinutes * 60 + seconds) * rate.nominalFps + frames)
        return nominalFrames - rate.droppedLabelsPerMinute * (totalMinutes - totalMinutes / 10)
    }

    companion object {
        fun fromTotalFrames(total: Long, rate: TimecodeRate): SmpteTimecode {
            val wrapped = Math.floorMod(total, rate.framesPerDay)
            if (rate.dropFrame) return fromTotalFramesDrop(wrapped, rate)
            return fromTotalFramesNdf(wrapped, rate)
        }

        private fun fromTotalFramesNdf(total: Long, rate: TimecodeRate): SmpteTimecode {
            val fps = rate.nominalFps
            val frames = (total % fps).toInt()
            val totalSeconds = total / fps
            val seconds = (totalSeconds % 60).toInt()
            val totalMinutes = totalSeconds / 60
            val minutes = (totalMinutes % 60).toInt()
            val hours = (totalMinutes / 60 % 24).toInt()
            return SmpteTimecode(hours, minutes, seconds, frames, false)
        }

        private fun fromTotalFramesDrop(total: Long, rate: TimecodeRate): SmpteTimecode {
            val fps = rate.nominalFps
            val dropFrames = rate.droppedLabelsPerMinute
            val framesPer10Min = fps * 600L - 9 * dropFrames
            val framesPerMin = fps * 60L - dropFrames
            val blocks = total / framesPer10Min
            val remainder = total % framesPer10Min
            val omittedMinutes = ((remainder - dropFrames).coerceAtLeast(0) / framesPerMin)
            val adjustedTotal = total + blocks * 9 * dropFrames + omittedMinutes * dropFrames
            val frames = (adjustedTotal % fps).toInt()
            val totalSeconds = adjustedTotal / fps
            val seconds = (totalSeconds % 60).toInt()
            val totalMinutes = totalSeconds / 60
            val minutes = (totalMinutes % 60).toInt()
            val hours = (totalMinutes / 60 % 24).toInt()
            return SmpteTimecode(hours, minutes, seconds, frames, true)
        }
    }
}

/**
 * Rational timecode rate. For 29.97/59.94 DF, nominalFps is 30/60 and dropFrame is true.
 */
data class TimecodeRate(
    val nominalFps: Int,
    val dropFrame: Boolean = false,
) {
    init {
        require(nominalFps in setOf(24, 25, 30, 50, 60)) { "Unsupported nominal timecode rate" }
        require(!dropFrame || nominalFps in setOf(30, 60)) { "Drop-frame requires nominal 30 or 60" }
    }
    val numerator: Int get() = if (dropFrame) nominalFps * 1000 else nominalFps
    val denominator: Int get() = if (dropFrame) 1001 else 1
    val droppedLabelsPerMinute: Int get() = if (dropFrame) nominalFps / 15 else 0
    val framesPerDay: Long get() = nominalFps * 86400L - droppedLabelsPerMinute * (1440L - 144L)
    val label: String get() = if (dropFrame) (if (nominalFps == 30) "29.97DF" else "59.94DF") else "${nominalFps}NDF"
    /** Truncated single-frame duration for legacy display only; never accumulate this value. */
    val frameDurationUs: Long get() = 1_000_000L * denominator / numerator
    fun framesForElapsedNs(elapsedNs: Long): Long {
        require(elapsedNs >= 0)
        val frames = BigInteger.valueOf(elapsedNs).multiply(BigInteger.valueOf(numerator.toLong()))
            .divide(BigInteger.valueOf(1_000_000_000L * denominator))
        check(frames.bitLength() <= 63) { "Elapsed timecode frame count exceeds Long range" }
        return frames.toLong()
    }

}

enum class TimecodeMode { FREE_RUN, RECORD_RUN, REGEN }

/**
 * Configuration for the timecode system.
 */
data class TimecodeConfig(
    val enabled: Boolean = false,
    val mode: TimecodeMode = TimecodeMode.RECORD_RUN,
    val rate: TimecodeRate = TimecodeRate(30, false),
    val startValue: SmpteTimecode = SmpteTimecode(1, 0, 0, 0, false),
    val rememberPosition: Boolean = true,
    val resetRevision: Int = 0,
)
