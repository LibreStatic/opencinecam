/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import kotlin.math.roundToInt

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
        return "%02d:%02d:%02d%s%02d".format(hours, minutes, seconds, sep, frames)
    }

    fun toTotalFrames(rate: TimecodeRate): Long {
        val wholeFrames = ((hours * 3600 + minutes * 60 + seconds) * rate.nominalFps + frames).toLong()
        return wholeFrames
    }

    companion object {
        fun fromTotalFrames(total: Long, rate: TimecodeRate): SmpteTimecode {
            if (rate.dropFrame) return fromTotalFramesDrop(total, rate)
            return fromTotalFramesNdf(total, rate)
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
           val dropFrames = if (fps == 30) 2 else if (fps == 60) 4 else 0
           if (dropFrames == 0) return fromTotalFramesNdf(total, rate)
            // Each 10-min block drops 9 * dropFrames frames (all minutes except the 0th).
            val framesPer10Min = fps * 60 * 10 - 9 * dropFrames
            // Each minute drops dropFrames frames except the 0th minute of each 10-min block.
            val framesPerMin = fps * 60 - dropFrames
           val d = total / framesPer10Min
           val m = total % framesPer10Min
            val dropAdjust = if (m > dropFrames) d * 9 * dropFrames + ((m - dropFrames) / framesPerMin * dropFrames) else d * 9 * dropFrames
            val adjustedTotal = total + dropAdjust
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
    val label: String get() = if (dropFrame) "${nominalFps - 1}.${if (nominalFps == 30) 97 else 94}DF" else "${nominalFps}NDF"
    val frameDurationUs: Long get() = when {
        dropFrame && nominalFps == 30 -> 1_000_000L * 1001 / 30000
        dropFrame && nominalFps == 60 -> 1_000_000L * 1001 / 60000
        else -> 1_000_000L / nominalFps
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
)
