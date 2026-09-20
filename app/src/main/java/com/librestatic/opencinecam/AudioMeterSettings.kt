/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import com.librestatic.opencinecam.camera.AudioChannelLevel
import com.librestatic.opencinecam.camera.AudioLevelSnapshot

/** Presentation only; changing these values never reconfigures recorded audio. */
data class AudioMeterSettings(
    val visible: Boolean = true,
    val mode: AudioMeterMode = AudioMeterMode.PEAK_RMS,
    val vuReferenceDbfs: Int = -18,
    val peakHoldMs: Int = 1500,
    val showValues: Boolean = false,
) {
    init { require(vuReferenceDbfs in -24..-6); require(peakHoldMs in 0..3000) }
}

enum class AudioMeterMode { PEAK_RMS, VU, PPM }

internal const val AUDIO_METER_FRESHNESS_MS = 500L
internal fun currentAudioMeterSnapshot(snapshot: AudioLevelSnapshot?, active: Boolean, nowMs: Long): AudioLevelSnapshot? =
    snapshot?.takeIf { active && it.capturedAtElapsedRealtimeMs >= 0 &&
        nowMs >= it.capturedAtElapsedRealtimeMs && nowMs - it.capturedAtElapsedRealtimeMs <= AUDIO_METER_FRESHNESS_MS &&
        it.channels.size in 1..2 }

/** Nullable ballistics never fall back to instantaneous RMS under a different label. */
internal fun AudioMeterSettings.displayDb(level: AudioChannelLevel): Float? = when (mode) {
    AudioMeterMode.PEAK_RMS -> level.peakDbfs
    AudioMeterMode.VU -> level.vuDbfs?.minus(vuReferenceDbfs)
    AudioMeterMode.PPM -> level.ppmDbfs
}?.takeIf { it.isFinite() }

/** Bounded UI hold, keyed by the composing caller to channel layout/configuration/producer. */
internal class AudioMeterPeakHold {
    private var lastTimestamp: Long? = null
    private var peaks = emptyList<Float?>()
    private var deadlines = emptyList<Long>()
    fun clear() { lastTimestamp = null; peaks = emptyList(); deadlines = emptyList() }
    fun observe(timestampMs: Long, nowMs: Long, values: List<Float?>, holdMs: Int): List<Float?> {
        require(values.size in 1..2 && holdMs in 0..3000)
        if (peaks.size != values.size || lastTimestamp?.let { timestampMs < it } == true) clear()
        if (peaks.isEmpty()) { peaks = List(values.size) { null }; deadlines = List(values.size) { 0L } }
        val newSample = lastTimestamp != timestampMs
        val nextDeadlines = deadlines.toMutableList()
        peaks = values.mapIndexed { index, candidate ->
            val value = candidate?.takeIf { it.isFinite() }
            val old = peaks[index]
            when {
                value == null -> { nextDeadlines[index] = 0; null }
                holdMs == 0 -> value
                old == null || nowMs >= deadlines[index] || newSample && value >= old -> {
                    // Hold runs from observation time, not repeated UI redraws of one sample.
                    nextDeadlines[index] = if (newSample) timestampMs.coerceAtMost(Long.MAX_VALUE - holdMs) + holdMs else nowMs
                    value
                }
                else -> old
            }
        }
        lastTimestamp = timestampMs; deadlines = nextDeadlines
        return peaks
    }
}
