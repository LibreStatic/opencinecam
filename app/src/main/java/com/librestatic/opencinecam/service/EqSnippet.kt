/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.service

import com.librestatic.opencinecam.camera.PcmMeterEncoding
import java.nio.ByteBuffer

/** The longest snippet the equaliser editor keeps; it lives in memory only and is never written anywhere. */
const val EQ_SNIPPET_MAX_MS = 10_000

/** A shorter snippet is discarded: it would loop too fast to judge a curve by. */
internal const val EQ_SNIPPET_MIN_MS = 500

/** How often a recording reports its length to the editor. */
internal const val EQ_SNIPPET_PROGRESS_MS = 200

/**
 * The equaliser editor's snippet audition (PLAN-074 decision 13): with only the phone's speaker, listening live
 * would feed back, so the editor records a few seconds of the raw input and loops them through the curve it edits.
 */
data class EqAuditionState(
    val phase: EqAuditionPhase = EqAuditionPhase.IDLE,
    /** Length of the kept snippet; 0 when there is none. */
    val snippetMs: Int = 0,
    /** Recording: time captured so far. */
    val recordedMs: Int = 0,
    val maxMs: Int = EQ_SNIPPET_MAX_MS,
    /** The last record or play request failed (no microphone, track could not be created). */
    val failed: Boolean = false,
)

enum class EqAuditionPhase { IDLE, RECORDING, PLAYING }

/** Interleaved little-endian PCM as the preview monitor read it, before the equaliser. Never changed once built. */
internal class EqSnippet(
    val pcm: ByteArray,
    val byteCount: Int,
    val encoding: PcmMeterEncoding,
    val sampleRateHz: Int,
    val channels: Int,
) {
    val frameBytes: Int = encoding.bytesPerSample * channels
    val frames: Int = byteCount / frameBytes
    val durationMs: Int = (frames.toLong() * 1_000L / sampleRateHz).toInt()

    init {
        require(channels in 1..2)
        require(sampleRateHz > 0)
        require(byteCount in frameBytes..pcm.size && byteCount % frameBytes == 0) { "A snippet holds complete PCM frames." }
    }
}

/**
 * Collects a snippet from the preview monitor's read loop. The array is sized for [maxMs] up front, so [append]
 * never blocks or allocates on the audio thread; it keeps complete frames only and stops at the cap. [onProgress]
 * runs on the audio thread every [progressIntervalMs] of PCM and once when the cap is reached.
 */
internal class EqSnippetRecorder(
    val encoding: PcmMeterEncoding,
    val sampleRateHz: Int,
    val channels: Int,
    maxMs: Int = EQ_SNIPPET_MAX_MS,
    private val minMs: Int = EQ_SNIPPET_MIN_MS,
    progressIntervalMs: Int = EQ_SNIPPET_PROGRESS_MS,
    private val onProgress: (recordedMs: Int, full: Boolean) -> Unit,
) {
    private val frameBytes = encoding.bytesPerSample * channels
    private val pcm: ByteArray
    private val progressFrames: Int
    /** Written by the audio thread only, after the bytes it covers. */
    @Volatile private var written = 0
    @Volatile private var finished = false
    private var reportedFrames = 0

    init {
        require(channels in 1..2)
        require(sampleRateHz > 0 && maxMs > 0)
        pcm = ByteArray(Math.toIntExact(sampleRateHz.toLong() * maxMs / 1_000L * frameBytes))
        progressFrames = (sampleRateHz.toLong() * progressIntervalMs / 1_000L).toInt().coerceAtLeast(1)
    }

    /** Audio thread: the PCM at [0, byteCount) of [buffer], up to its limit; a trailing partial frame is left out. */
    fun append(buffer: ByteBuffer, byteCount: Int) {
        if (finished) return
        val room = pcm.size - written
        if (room <= 0) return
        val count = minOf(byteCount.coerceIn(0, buffer.limit()) / frameBytes * frameBytes, room)
        if (count <= 0) return
        val start = written
        // Absolute reads leave the monitor's buffer as it was, and need no view of it.
        for (index in 0 until count) pcm[start + index] = buffer.get(index)
        written = start + count
        val frames = written / frameBytes
        val full = written == pcm.size
        if (full || frames - reportedFrames >= progressFrames) {
            reportedFrames = frames
            onProgress(millis(frames), full)
        }
    }

    /**
     * Ends the recording and returns the snippet, or null when it is shorter than [minMs]. A buffer the audio
     * thread is still appending lands past the length read here, so the snippet's bytes never change.
     */
    fun finish(): EqSnippet? {
        finished = true
        val length = written
        return if (length == 0 || millis(length / frameBytes) < minMs) null
        else EqSnippet(pcm, length, encoding, sampleRateHz, channels)
    }

    private fun millis(frames: Int): Int = (frames.toLong() * 1_000L / sampleRateHz).toInt()
}

/** The preview monitor as the snippet recorder sees it. */
internal interface EqSnippetSource {
    val snippetEncoding: PcmMeterEncoding
    val snippetSampleRateHz: Int
    val snippetChannels: Int
    /** Fed the raw PCM of each buffer the monitor reads, before its equaliser, while set. */
    var snippetTap: EqSnippetRecorder?
}
