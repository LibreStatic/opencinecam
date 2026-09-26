/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import java.math.BigInteger
import java.nio.ByteBuffer

data class PcmKeepSpan(val offsetFrames: Int, val frames: Int)
data class CapturePauseWindow(val startFrame: Long, val endFrame: Long?)
data class SharedCapturePauseReport(
    val sampleRateHz: Int, val audioFrameZeroNs: Long, val windows: List<CapturePauseWindow>,
    val stopFrame: Long?, val capturedPcmFrames: Long, val retainedPcmFrames: Long,
    val lastCommandNs: Long?, val lastEffectiveBoundaryNs: Long?,
    // Camera frames whose timestamp regressed below an already classified frame; dropped, never mapped.
    val regressedVideoFrames: Long = 0,
)

/** Why a shared/timelapse pause command was not applied; `null` from a request means it was applied. */
enum class CapturePauseRejection {
    /** No take owns the pipeline (idle, closed, or a stale take). */
    NOT_RECORDING,
    /** The take is stopping or its stop boundary is already sealed. */
    STOPPED,
    /** Pause while paused, or resume while running. */
    UNCHANGED,
    /** The per-take pause window cap was reached. */
    WINDOW_LIMIT,
    /** The first camera frame or the PCM capture anchor has not arrived yet. */
    ANCHORS_PENDING,
    /** The PCM anchor is a read-receipt estimate, not an AudioTimestamp; sample windows would not be comparable. */
    ESTIMATED_AUDIO_ANCHOR,
    /** The camera clock is not realtime or the take has no PCM clock. */
    UNSUPPORTED_CLOCK,
}

/** Owned by CaptureEpochClock's monitor. Boundaries use the actual PCM source grid, never codec PTS. */
internal class SharedCapturePause(
    private val rate: Int, private val originNs: Long,
    initialAudioFrames: Long = 0, initialVideoNs: Long? = null,
) {
    init { require(rate in 8000..192000 && originNs > 0 && initialAudioFrames >= 0) }
    private val windows = mutableListOf<CapturePauseWindow>()
    private var readFrames = initialAudioFrames
    private var retainedFrames = initialAudioFrames
    private var lastVideoNs = initialVideoNs
    private var stopFrame: Long? = null
    private var lastCommandNs: Long? = null
    private var lastBoundaryNs: Long? = null
    var regressedVideoFrames = 0L
        private set
    val paused: Boolean get() = windows.lastOrNull()?.endFrame == null && windows.isNotEmpty()
    private fun frameTime(frame: Long): Long = Math.addExact(originNs, pcmFrameDurationNs(frame, rate))
    private fun boundary(nowNs: Long): Long {
        require(nowNs > 0 && lastCommandNs?.let { nowNs < it } != true)
        // Already classified inputs cannot be changed retroactively, even if an input timestamp leads receipt time.
        val notBefore = maxOf(nowNs, frameTime(readFrames), lastVideoNs?.let { Math.addExact(it, 1) } ?: originNs, lastBoundaryNs ?: originNs)
        val value = BigInteger.valueOf(Math.subtractExact(notBefore, originNs)).multiply(BigInteger.valueOf(rate.toLong()))
        val frame = value.add(BigInteger.valueOf(999999999)).divide(BigInteger.valueOf(1000000000))
        require(frame.bitLength() <= 63)
        lastCommandNs = nowNs; lastBoundaryNs = frameTime(frame.toLong())
        return frame.toLong()
    }
    fun setPaused(value: Boolean, nowNs: Long): Boolean = requestPaused(value, nowNs) == null
    fun requestPaused(value: Boolean, nowNs: Long): CapturePauseRejection? {
        if (stopFrame != null) return CapturePauseRejection.STOPPED
        if (paused == value) return CapturePauseRejection.UNCHANGED
        if (value && windows.size >= MAX_WINDOWS) return CapturePauseRejection.WINDOW_LIMIT
        val frame = boundary(nowNs)
        if (value) windows += CapturePauseWindow(frame, null)
        else windows[windows.lastIndex] = windows.last().copy(endFrame = frame)
        return null
    }
    fun finish(nowNs: Long) {
        if (stopFrame != null) return
        val end = boundary(nowNs)
        if (paused) windows[windows.lastIndex] = windows.last().copy(endFrame = end)
        stopFrame = end
    }
    fun video(sourceNs: Long): Long? {
        require(sourceNs > 0)
        // A HAL timestamp glitch costs one frame, not the take: the regressed frame is dropped before any
        // window/PTS math, so boundaries and mapped PTS still only ever see a monotonic video clock.
        if (lastVideoNs?.let { sourceNs < it } == true) { regressedVideoFrames++; return null }
        lastVideoNs = sourceNs
        if (stopFrame?.let { sourceNs >= frameTime(it) } == true) return null
        var removedNs = 0L
        for (window in windows) {
            val start = frameTime(window.startFrame)
            if (sourceNs < start) break
            val end = window.endFrame?.let(::frameTime) ?: return null
            if (sourceNs < end) return null
            removedNs = Math.addExact(removedNs, end - start)
        }
        return Math.subtractExact(sourceNs, removedNs)
    }
    fun audio(startFrame: Long, count: Int): List<PcmKeepSpan> {
        require(startFrame == readFrames && count >= 0)
        val readEnd = Math.addExact(startFrame, count.toLong())
        val end = minOf(readEnd, stopFrame ?: readEnd)
        var at = startFrame
        val keep = mutableListOf<PcmKeepSpan>()
        fun retain(until: Long) {
            if (until > at) { keep += PcmKeepSpan(Math.toIntExact(at - startFrame), Math.toIntExact(until - at)); at = until }
        }
        for (window in windows) {
            if (at >= end || window.startFrame >= end) break
            val pauseEnd = window.endFrame ?: Long.MAX_VALUE
            if (pauseEnd <= at) continue
            retain(minOf(end, maxOf(at, window.startFrame)))
            at = minOf(end, maxOf(at, pauseEnd))
        }
        retain(end)
        readFrames = readEnd
        retainedFrames = Math.addExact(retainedFrames, keep.sumOf { it.frames.toLong() })
        return keep
    }
    fun report() = SharedCapturePauseReport(rate, originNs, windows.toList(), stopFrame, readFrames, retainedFrames, lastCommandNs, lastBoundaryNs, regressedVideoFrames)
    companion object { const val MAX_WINDOWS = 10000 }
}

/** Forward in-place compaction preserves complete interleaved frames; the common no-cut path copies nothing. */
fun compactPcm16(buffer: ByteBuffer, readBytes: Int, channels: Int, spans: List<PcmKeepSpan>): Int {
    require(channels in 1..2)
    return compactPcmFrames(buffer, readBytes, channels * 2, spans)
}

/** Byte-preserving compaction also supports packed PCM24 and interleaved float; validates before writes. */
fun compactPcmFrames(buffer: ByteBuffer, readBytes: Int, frameBytes: Int, spans: List<PcmKeepSpan>): Int {
    require(frameBytes in 1..32 && readBytes in 0..buffer.capacity() && readBytes % frameBytes == 0)
    var previousEnd = 0
    for (span in spans) {
        require(span.offsetFrames >= previousEnd && span.frames > 0 && span.offsetFrames.toLong() + span.frames <= readBytes / frameBytes)
        previousEnd = span.offsetFrames + span.frames
    }
    buffer.limit(readBytes)
    var write = 0
    for (span in spans) {
        val from = span.offsetFrames * frameBytes; val bytes = span.frames * frameBytes
        if (write != from) for (offset in 0 until bytes) buffer.put(write + offset, buffer.get(from + offset))
        write += bytes
    }
    buffer.position(0); buffer.limit(write)
    return write
}
