/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.service

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.os.SystemClock
import com.librestatic.opencinecam.camera.AudioLevelMeter
import com.librestatic.opencinecam.camera.AudioLevelSnapshot
import com.librestatic.opencinecam.camera.AudioSpectrum
import com.librestatic.opencinecam.camera.DigitalRecordingGain
import com.librestatic.opencinecam.camera.EqCurve
import com.librestatic.opencinecam.camera.InputEqualizer
import com.librestatic.opencinecam.camera.PcmMeterEncoding
import com.librestatic.opencinecam.camera.SpectrumAnalyzer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/** A short fade at the loop's seam, so the jump from the snippet's end to its start does not click. */
internal const val EQ_SNIPPET_SEAM_FADE_MS = 5

/** Where a playback chunk goes; called on the playback thread. */
internal interface EqSnippetOutput {
    fun onSpectrum(spectrum: AudioSpectrum)
    fun onLevel(snapshot: AudioLevelSnapshot)
    /** Plays the 16-bit PCM between [pcm16]'s position 0 and its limit, blocking; the output may move the position. */
    fun write(pcm16: ByteBuffer)
}

/**
 * One 40 ms step of the snippet audition, as the preview monitor processes a buffer: the spectrum before and after
 * its own equaliser, the recording gain (no AGC), and a level meter that sees the raw chunk before the equaliser and
 * the result after the gain, so the clip latches and meters follow what plays. The loop runs on: a chunk spanning
 * the snippet's end takes its tail and then its head, and the equaliser keeps its state across the seam. Nothing is
 * allocated per chunk here beyond what the meter and analyser publish.
 */
internal class EqSnippetChunkProcessor(
    private val snippet: EqSnippet,
    curve: EqCurve,
    recordingGain: DigitalRecordingGain,
    private val output: EqSnippetOutput,
    chunkMs: Int = PREVIEW_READ_CHUNK_MS,
    seamFadeMs: Int = EQ_SNIPPET_SEAM_FADE_MS,
) {
    private val encoding = snippet.encoding
    private val channels = snippet.channels
    private val frameBytes = snippet.frameBytes
    private val equalizer = InputEqualizer(snippet.sampleRateHz, channels).apply { setCurve(curve) }
    private val analyzer = SpectrumAnalyzer(encoding, channels, snippet.sampleRateHz)
    private val meter = AudioLevelMeter(encoding, channels, sampleRateHz = snippet.sampleRateHz)
    private val chunkFrames = (snippet.sampleRateHz.toLong() * chunkMs / 1_000L).toInt().coerceAtLeast(1)
    private val chunkBytes = chunkFrames * frameBytes
    private val work = ByteBuffer.allocateDirect(chunkBytes).order(ByteOrder.LITTLE_ENDIAN)
    private val pcm16 = ByteBuffer.allocateDirect(chunkFrames * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
    private val fadeFrames = (snippet.sampleRateHz.toLong() * seamFadeMs / 1_000L).toInt().coerceIn(0, snippet.frames / 4)
    private var positionFrames = 0

    /** Any thread; adopted at the next chunk, as the monitor adopts it at its next buffer. */
    @Volatile var recordingGain: DigitalRecordingGain = recordingGain

    /** Any thread; adopted at the next chunk without resetting the filters. */
    fun setCurve(curve: EqCurve) = equalizer.setCurve(curve)

    /** Processes and writes the next chunk, stamping what it publishes with [nowElapsedRealtimeMs]. */
    fun processNext(nowElapsedRealtimeMs: Long) {
        var filled = 0
        while (filled < chunkBytes) {
            val start = positionFrames * frameBytes
            val count = minOf(chunkBytes - filled, snippet.byteCount - start)
            work.position(filled)
            work.put(snippet.pcm, start, count)
            fadeSeam(filled, positionFrames, count / frameBytes)
            filled += count
            positionFrames += count / frameBytes
            if (positionFrames >= snippet.frames) positionFrames = 0
        }
        work.position(0)
        meter.observeInput(work, chunkBytes)
        analyzer.observeInput(work, chunkBytes)
        equalizer.process(work, chunkBytes, encoding)
        analyzer.observeOutput(work, chunkBytes, nowElapsedRealtimeMs)?.let(output::onSpectrum)
        val gain = recordingGain
        gain.process(work, chunkBytes, encoding, channels)
        meter.analyze(work, chunkBytes, nowElapsedRealtimeMs)?.let { output.onLevel(it.copy(appliedRecordingGain = gain)) }
        toPcm16()
        output.write(pcm16)
    }

    /** Scales the frames within [fadeFrames] of the snippet's start (in) or end (out); the rest is untouched. */
    private fun fadeSeam(offsetBytes: Int, firstFrame: Int, frames: Int) {
        if (fadeFrames == 0) return
        val total = snippet.frames
        for (index in 0 until frames) {
            val frame = firstFrame + index
            val fromEnd = total - 1 - frame
            if (frame >= fadeFrames && fromEnd >= fadeFrames) continue
            val factor = minOf((frame + 0.5) / fadeFrames, (fromEnd + 0.5) / fadeFrames, 1.0)
            var offset = offsetBytes + index * frameBytes
            repeat(channels) {
                scaleSample(offset, factor)
                offset += encoding.bytesPerSample
            }
        }
    }

    private fun scaleSample(offset: Int, factor: Double) {
        when (encoding) {
            PcmMeterEncoding.PCM_16 -> work.putShort(offset, (work.getShort(offset) * factor).roundToInt().toShort())
            PcmMeterEncoding.PCM_24 -> {
                val value = ((work.get(offset).toInt() and 255) or ((work.get(offset + 1).toInt() and 255) shl 8) or
                    (work.get(offset + 2).toInt() shl 16))
                val scaled = (value * factor).roundToInt()
                work.put(offset, scaled.toByte())
                work.put(offset + 1, (scaled shr 8).toByte())
                work.put(offset + 2, (scaled shr 16).toByte())
            }
            PcmMeterEncoding.PCM_FLOAT -> work.getFloat(offset).let { if (it.isFinite()) work.putFloat(offset, (it * factor).toFloat()) }
        }
    }

    /** The speaker track is 16-bit, as the listening output is; float headroom clips here, never in what is metered. */
    private fun toPcm16() {
        val samples = chunkFrames * channels
        for (sample in 0 until samples) {
            val offset = sample * encoding.bytesPerSample
            val value = when (encoding) {
                PcmMeterEncoding.PCM_16 -> work.getShort(offset).toInt()
                PcmMeterEncoding.PCM_24 -> {
                    val raw = (work.get(offset).toInt() and 255) or ((work.get(offset + 1).toInt() and 255) shl 8) or
                        (work.get(offset + 2).toInt() shl 16)
                    (raw / 256.0).roundToInt().coerceIn(-32768, 32767)
                }
                PcmMeterEncoding.PCM_FLOAT -> work.getFloat(offset).let { sampleValue ->
                    if (sampleValue.isFinite()) (sampleValue.toDouble() * 32768).coerceIn(-32768.0, 32767.0).roundToInt() else 0
                }
            }
            pcm16.putShort(sample * 2, value.toShort())
        }
        pcm16.clear()
    }
}

/** What the audition controller keeps of a running playback. */
internal interface EqSnippetPlayback {
    fun setCurve(curve: EqCurve)
    fun setRecordingGain(gain: DigitalRecordingGain)
    /** Stops the loop; the playback thread releases the track. Any thread, idempotent. */
    fun close()
}

/**
 * Loops a snippet on the media stream, through its own equaliser, on a thread of its own. The track is a plain
 * 16-bit streaming one with default routing (the speaker, or the headphones when connected); it is separate from
 * the listening output, which allows one track per process. The callbacks run on the playback thread;
 * [onFailure] runs at most once, and never after [close].
 */
internal class EqSnippetPlayer(
    private val snippet: EqSnippet,
    curve: EqCurve,
    recordingGain: DigitalRecordingGain,
    private val onSpectrum: (AudioSpectrum) -> Unit,
    private val onLevel: (AudioLevelSnapshot) -> Unit,
    private val onProgress: () -> Unit,
    private val onFailure: (Throwable) -> Unit,
) : EqSnippetPlayback {
    private val running = AtomicBoolean(true)
    private val trackLock = Any()
    private var track: AudioTrack? = null

    private val processor = EqSnippetChunkProcessor(snippet, curve, recordingGain, object : EqSnippetOutput {
        override fun onSpectrum(spectrum: AudioSpectrum) { if (running.get()) this@EqSnippetPlayer.onSpectrum(spectrum) }
        override fun onLevel(snapshot: AudioLevelSnapshot) { if (running.get()) this@EqSnippetPlayer.onLevel(snapshot) }
        override fun write(pcm16: ByteBuffer) {
            val current = synchronized(trackLock) { track } ?: return
            while (running.get() && pcm16.hasRemaining()) {
                val written = current.write(pcm16, pcm16.remaining(), AudioTrack.WRITE_BLOCKING)
                check(written >= 0) { "Snippet AudioTrack write failed with code $written." }
                // Zero only comes back from a paused track, which close() does; the loop then ends.
                if (written == 0) break
            }
        }
    })

    private val thread = Thread(::run, "EqSnippetPlayer")

    fun start() = thread.start()

    override fun setCurve(curve: EqCurve) = processor.setCurve(curve)

    override fun setRecordingGain(gain: DigitalRecordingGain) { processor.recordingGain = gain }

    override fun close() {
        if (!running.getAndSet(false)) return
        // A blocking write returns once the track pauses; the playback thread then releases it.
        synchronized(trackLock) { track?.let { runCatching { it.pause(); it.flush() } } }
    }

    private fun run() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val created = createTrack()
            synchronized(trackLock) {
                if (!running.get()) { created.release(); return }
                track = created
            }
            created.play()
            var progressAt = SystemClock.elapsedRealtime()
            while (running.get()) {
                val now = SystemClock.elapsedRealtime()
                processor.processNext(now)
                if (now - progressAt >= PROGRESS_INTERVAL_MS) {
                    progressAt = now
                    if (running.get()) onProgress()
                }
            }
        } catch (failure: Throwable) {
            if (running.getAndSet(false)) onFailure(failure)
        } finally {
            synchronized(trackLock) {
                track?.let { runCatching { it.release() } }
                track = null
            }
        }
    }

    private fun createTrack(): AudioTrack {
        val mask = if (snippet.channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minimum = AudioTrack.getMinBufferSize(snippet.sampleRateHz, mask, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum in 1..524288) { "Snippet PCM output is unsupported or exceeds its buffer budget." }
        val created = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(snippet.sampleRateHz).setChannelMask(mask)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            // A short buffer keeps the meters and the spectrum close to what is heard.
            .setBufferSizeInBytes(maxOf(minimum, snippet.sampleRateHz * snippet.channels * 2 / 10))
            .build()
        if (created.state != AudioTrack.STATE_INITIALIZED) {
            created.release()
            error("Snippet AudioTrack initialization failed.")
        }
        return created
    }

    private companion object {
        /** How often the controller is told the loop is still going, so it can stop it when the editor loses the audio. */
        const val PROGRESS_INTERVAL_MS = 250L
    }
}
