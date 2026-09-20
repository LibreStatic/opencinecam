/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import android.media.AudioRecord
import android.media.AudioTimestamp
import com.librestatic.opencinecam.camera.PcmSourceTiming
import com.librestatic.opencinecam.camera.PcmSourceTimingReport
import org.json.JSONObject

/** AudioRecord timestamps use BOOTTIME; command receipts never substitute for missing source anchors. */
internal class SidecarCaptureTiming(rate: Int, private val frameBytes: Int,
    private val shared: com.librestatic.opencinecam.camera.CaptureEpochClock? = null) {
    init { require(shared == null || shared.sampleRateHz == rate) }
    private val source = PcmSourceTiming(rate, frameBytes)
    private val timestamp = AudioTimestamp()
    private var capturedFrames = 0L
    fun captured(record: AudioRecord, buffer: java.nio.ByteBuffer, bytes: Int): Int {
        source.captured(bytes)
        if (record.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_BOOTTIME) == AudioRecord.SUCCESS) {
            source.observeTimestamp(timestamp.framePosition, timestamp.nanoTime)
            shared?.audioInput(requireNotNull(source.report().epoch))
        } else source.timestampUnavailable()
        val spans = shared?.selectAudio(capturedFrames, bytes / frameBytes)
        capturedFrames = Math.addExact(capturedFrames, (bytes / frameBytes).toLong())
        return if (spans == null) bytes else com.librestatic.opencinecam.camera.compactPcmFrames(buffer, bytes, frameBytes, spans)
    }
    fun written(bytes: Int) = source.written(bytes)
    fun requireComplete(frames: Long) {
        if (shared == null) source.requireComplete(frames) else {
            shared.requireRetainedAudioFrames(frames)
            check(source.report().writtenFrames == frames) { "Selected lossless PCM did not reach output" }
        }
    }
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
