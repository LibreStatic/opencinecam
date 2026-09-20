/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

data class PcmSourceTimingReport(
    val sampleRateHz: Int,
    val frameBytes: Int,
    val capturedFrames: Long,
    val writtenFrames: Long,
    val durationNs: Long,
    val epoch: AudioCaptureEpoch?,
    val timestampObservations: Long,
    val unavailableTimestamps: Long,
    val firstTimestampFrame: Long?,
    val firstTimestampNs: Long?,
    val lastTimestampFrame: Long?,
    val lastTimestampNs: Long?,
)

/** Single PCM producer owns this clock; read its final report only after worker retirement. */
class PcmSourceTiming(private val rate: Int, private val frameBytes: Int) {
    init { require(rate in 1..384000 && frameBytes in 1..32) }
    private val epoch = PcmCaptureEpoch(rate)
    private var captured = 0L
    private var written = 0L
    private var observations = 0L
    private var unavailable = 0L
    private var firstFrame: Long? = null
    private var firstNs: Long? = null
    private var lastFrame: Long? = null
    private var lastNs: Long? = null

    fun captured(bytes: Int) {
        require(bytes >= 0 && bytes % frameBytes == 0) { "PCM read contains a partial interleaved frame" }
        captured = Math.addExact(captured, (bytes / frameBytes).toLong())
    }
    fun written(bytes: Int) {
        require(bytes >= 0 && bytes % frameBytes == 0)
        val next = Math.addExact(written, (bytes / frameBytes).toLong())
        check(next <= captured) { "PCM output exceeds captured source" }
        written = next
    }
    fun observeTimestamp(frame: Long, timeNs: Long) {
        epoch.observe(frame, timeNs)
        if (firstFrame == null) { firstFrame = frame; firstNs = timeNs }
        lastFrame = frame; lastNs = timeNs
        observations = Math.addExact(observations, 1)
    }
    fun timestampUnavailable() { unavailable = Math.addExact(unavailable, 1) }
    fun requireComplete(expectedFrames: Long) {
        check(written == captured && written == expectedFrames) { "Lossless output did not retain all captured PCM frames" }
    }
    fun report() = PcmSourceTimingReport(rate, frameBytes, captured, written,
        pcmFrameDurationNs(written, rate), epoch.current(), observations, unavailable,
        firstFrame, firstNs, lastFrame, lastNs)
}
