/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import com.librestatic.opencinecam.media.audio.PcmInput
import com.librestatic.opencinecam.media.audio.PcmInputTimestamp
import com.librestatic.opencinecam.camera.PcmSourceTiming
import com.librestatic.opencinecam.camera.PcmSourceTimingReport
import org.json.JSONObject

/**
 * PCM input timestamps use BOOTTIME; command receipts never substitute for missing source anchors.
 * The capture thread writes while the recording ticker reads [report] live, so every access locks.
 */
internal class SidecarCaptureTiming(rate: Int, private val frameBytes: Int,
    private val shared: com.librestatic.opencinecam.camera.CaptureEpochClock? = null) {
    init { require(shared == null || shared.sampleRateHz == rate) }
    private val source = PcmSourceTiming(rate, frameBytes)
    private val readEpoch = com.librestatic.opencinecam.camera.PcmReadEpochEstimate(rate)
    private var readFrames = 0L
    private val timestamp = PcmInputTimestamp()
    private var capturedFrames = 0L
    /** A read of [bytes] returned at [boottimeNs]; call it right after the read, before the PCM is processed. */
    @Synchronized
    fun readReturned(bytes: Int, boottimeNs: Long) {
        readFrames += bytes / frameBytes
        readEpoch.observeRead(readFrames, boottimeNs)
    }
    /**
     * Frame zero estimated from the reads, for an input without timestamps. It places audio appended to a take
     * (PLAN-074 decision 10); the source epoch in [report] stays timestamps only.
     */
    @Synchronized
    fun readEstimatedFrameZeroNs(): Long? = readEpoch.frameZeroNs()
    @Synchronized
    fun captured(input: PcmInput, buffer: java.nio.ByteBuffer, bytes: Int): Int {
        source.captured(bytes)
        if (input.timestamp(timestamp)) {
            source.observeTimestamp(timestamp.framePosition, timestamp.nanoTime)
            shared?.audioInput(requireNotNull(source.report().epoch))
        } else source.timestampUnavailable()
        val spans = shared?.selectAudio(capturedFrames, bytes / frameBytes)
        capturedFrames = Math.addExact(capturedFrames, (bytes / frameBytes).toLong())
        return if (spans == null) bytes else com.librestatic.opencinecam.camera.compactPcmFrames(buffer, bytes, frameBytes, spans)
    }
    @Synchronized
    fun written(bytes: Int) = source.written(bytes)
    @Synchronized
    fun requireComplete(frames: Long) {
        if (shared == null) source.requireComplete(frames) else {
            shared.requireRetainedAudioFrames(frames)
            check(source.report().writtenFrames == frames) { "Selected lossless PCM did not reach output" }
        }
    }
    @Synchronized
    fun report() = source.report()
}

internal fun pcmSourceTimingJson(timing: PcmSourceTimingReport): JSONObject = JSONObject()
    .put("policy", if (timing.epoch != null) "AUDIORECORD_BOOTTIME_FIXED_SOURCE_EPOCH_V1" else "AUDIORECORD_SOURCE_EPOCH_UNAVAILABLE")
    .put("sampleRateHz", timing.sampleRateHz).put("frameBytes", timing.frameBytes)
    .put("capturedFrames", timing.capturedFrames).put("writtenFrames", timing.writtenFrames)
    .put("durationNs", timing.durationNs)
    .put("frameZeroNs", timing.epoch?.frameZeroNs ?: JSONObject.NULL)
    .put("timestampBacked", timing.epoch?.timestampBacked == true)
    .put("maxResidualNs", timing.epoch?.maxResidualNs ?: JSONObject.NULL)
    .put("timestampObservations", timing.timestampObservations).put("unavailableTimestamps", timing.unavailableTimestamps)
    .put("firstTimestampFrame", timing.firstTimestampFrame ?: JSONObject.NULL)
    .put("firstTimestampNs", timing.firstTimestampNs ?: JSONObject.NULL)
    .put("lastTimestampFrame", timing.lastTimestampFrame ?: JSONObject.NULL)
    .put("lastTimestampNs", timing.lastTimestampNs ?: JSONObject.NULL)
    .put("videoAlignmentApplied", false).put("waveformAlignmentVerified", false)
