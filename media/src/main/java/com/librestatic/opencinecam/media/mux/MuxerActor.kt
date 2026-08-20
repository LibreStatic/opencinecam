/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.media.mux

import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.FileDescriptor
import java.util.ArrayDeque
import java.util.EnumSet
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import com.librestatic.opencinecam.core.model.FailureCode
import com.librestatic.opencinecam.core.model.FailureSeverity
import com.librestatic.opencinecam.core.model.Recoverability
import com.librestatic.opencinecam.core.model.StableFailure

enum class MuxerTrack { VIDEO, AUDIO }

data class MuxerTrackFormat(
    val track: MuxerTrack,
    val mime: String,
    val platformFormat: MediaFormat? = null,
) {
    init {
        require(mime.isNotBlank()) { "track MIME must not be blank" }
    }
}

data class EncodedSample(
    val track: MuxerTrack,
    val presentationTimeUs: Long,
    val flags: Int,
    val data: ByteArray,
) {
    init {
        require(presentationTimeUs >= 0) { "sample PTS must not be negative" }
    }
}

interface MuxerSink : AutoCloseable {
    fun addTrack(format: MuxerTrackFormat): Int
    fun start()
    fun writeSample(trackIndex: Int, sample: EncodedSample, normalizedPtsUs: Long)
    fun stop()
    fun release()
}

class AndroidMediaMuxerSink(
    descriptor: FileDescriptor,
    outputFormat: Int = MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
) : MuxerSink {
    private val muxer = MediaMuxer(descriptor, outputFormat)

    override fun addTrack(format: MuxerTrackFormat): Int =
        format.platformFormat?.let(muxer::addTrack)
            ?: error("Android muxer requires a platform MediaFormat")

    override fun start() = muxer.start()

    override fun writeSample(trackIndex: Int, sample: EncodedSample, normalizedPtsUs: Long) {
        val buffer = java.nio.ByteBuffer.wrap(sample.data)
        muxer.writeSampleData(
            trackIndex,
            buffer,
            android.media.MediaCodec.BufferInfo().apply {
                offset = 0
                size = sample.data.size
                presentationTimeUs = normalizedPtsUs
                flags = sample.flags
            },
        )
    }

    override fun stop() = muxer.stop()

    override fun release() = muxer.release()

    override fun close() = release()
}

enum class MuxerState { WAITING_FOR_FORMATS, RUNNING, EOS, FAILED, CLOSED }

data class MuxerResult(val state: MuxerState, val failure: StableFailure? = null)

private sealed interface MuxerCommand {
    data class Format(val value: MuxerTrackFormat) : MuxerCommand
    data class Sample(val value: EncodedSample) : MuxerCommand
    data class Eos(val track: MuxerTrack) : MuxerCommand
    data class Error(val failure: StableFailure) : MuxerCommand
    data object Close : MuxerCommand
}

/** One bounded actor owns the muxer, track indexes, PTS origin, EOS and cleanup. */
class MuxerActor(
    expectedTracks: Set<MuxerTrack> = EnumSet.of(MuxerTrack.VIDEO, MuxerTrack.AUDIO),
    private val queueCapacity: Int = 64,
    private val sink: MuxerSink,
    private val correlationId: String = "muxer",
    providedExecutor: ExecutorService? = null,
) : AutoCloseable {
    private val expectedTracks = expectedTracks.toSet().also { require(it.isNotEmpty()) }
    private val queue = ArrayBlockingQueue<MuxerCommand>(queueCapacity.also { require(it > 0) })
    private val executor = providedExecutor ?: Executors.newSingleThreadExecutor()
    private val ownExecutor = providedExecutor == null
    private val closed = AtomicBoolean(false)
    private val completion = CompletableFuture<MuxerResult>()
    private val formats = mutableMapOf<MuxerTrack, MuxerTrackFormat>()
    private val indexes = mutableMapOf<MuxerTrack, Int>()
    private val preStartSamples = ArrayDeque<EncodedSample>()
    private val eosTracks = mutableSetOf<MuxerTrack>()
    private var originPtsUs: Long? = null
    private var lastInputPtsUs = -1L
    private var lastPtsUs = -1L
    private var state = MuxerState.WAITING_FOR_FORMATS
    private var failure: StableFailure? = null

    init {
        executor.execute {
            try {
                while (!closed.get() || queue.isNotEmpty()) {
                    process(queue.poll(100, TimeUnit.MILLISECONDS) ?: continue)
                    if (state == MuxerState.EOS || state == MuxerState.FAILED) break
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                if (!completion.isDone) completion.complete(MuxerResult(state, failure))
            }
        }
    }

    fun submitFormat(format: MuxerTrackFormat): Boolean = offer(MuxerCommand.Format(format))

    fun submitSample(sample: EncodedSample): Boolean = offer(MuxerCommand.Sample(sample))

    fun submitEos(track: MuxerTrack): Boolean = offer(MuxerCommand.Eos(track))

    fun submitError(failure: StableFailure): Boolean = offer(MuxerCommand.Error(failure))

    fun awaitCompletion(timeout: Long, unit: TimeUnit): MuxerResult = completion.get(timeout, unit)

    fun currentState(): MuxerState = state

    private fun offer(command: MuxerCommand): Boolean = !closed.get() && queue.offer(command)

    private fun process(command: MuxerCommand) {
        if (state == MuxerState.FAILED || state == MuxerState.EOS) return
        when (command) {
            is MuxerCommand.Format -> onFormat(command.value)
            is MuxerCommand.Sample -> onSample(command.value)
            is MuxerCommand.Eos -> onEos(command.track)
            is MuxerCommand.Error -> fail(command.failure)
            MuxerCommand.Close -> closeInternal()
        }
    }

    private fun onFormat(format: MuxerTrackFormat) {
        if (format.track in formats) {
            fail(failure(FailureCode.DUPLICATE_COMMAND, "A track format was submitted twice."))
            return
        }
        if (format.track !in expectedTracks) {
            fail(failure(FailureCode.UNSUPPORTED_CAPABILITY, "An unexpected muxer track was submitted."))
            return
        }
        formats[format.track] = format
        if (formats.keys == expectedTracks) {
            try {
                expectedTracks.forEach { track -> indexes[track] = sink.addTrack(formats.getValue(track)) }
                sink.start()
                state = MuxerState.RUNNING
                while (preStartSamples.isNotEmpty()) onRunningSample(preStartSamples.removeFirst())
            } catch (_: Throwable) {
                fail(failure(FailureCode.MUXER_FINALIZATION_FAILED, "Muxer tracks could not be started."))
            }
        }
    }

    private fun onSample(sample: EncodedSample) {
        when (state) {
            MuxerState.WAITING_FOR_FORMATS -> {
                if (preStartSamples.size >= queueCapacity) {
                    fail(failure(FailureCode.STORAGE_WRITE_FAILED, "Muxer pre-start buffer is full."))
                } else {
                    preStartSamples.addLast(sample)
                }
            }
            MuxerState.RUNNING -> onRunningSample(sample)
            else -> Unit
        }
    }

    private fun onRunningSample(sample: EncodedSample) {
        if (sample.track !in indexes) {
            fail(failure(FailureCode.INVALID_COMMAND, "Sample arrived before its track format."))
            return
        }
        if (sample.presentationTimeUs < lastInputPtsUs) {
            fail(failure(FailureCode.CADENCE_DISCONTINUITY, "Sample PTS moved backwards."))
            return
        }
        lastInputPtsUs = sample.presentationTimeUs
        val origin = originPtsUs ?: sample.presentationTimeUs.also { originPtsUs = it }
        val normalized = (sample.presentationTimeUs - origin).coerceAtLeast(0)
        if (normalized < lastPtsUs) {
            fail(failure(FailureCode.CADENCE_DISCONTINUITY, "Sample PTS moved backwards."))
            return
        }
        try {
            sink.writeSample(indexes.getValue(sample.track), sample, normalized)
            lastPtsUs = normalized
        } catch (_: Throwable) {
            fail(failure(FailureCode.STORAGE_WRITE_FAILED, "Muxer sample write failed."))
        }
    }

    private fun onEos(track: MuxerTrack) {
        if (track !in expectedTracks || track !in formats) {
            fail(failure(FailureCode.INVALID_COMMAND, "EOS arrived before the track was ready."))
            return
        }
        eosTracks += track
        if (state == MuxerState.RUNNING && eosTracks == expectedTracks) {
            runCatching { sink.stop() }.onFailure { fail(failure(FailureCode.MUXER_FINALIZATION_FAILED, "Muxer stop failed.")) }
            if (state != MuxerState.FAILED) {
                runCatching { sink.release() }
                state = MuxerState.EOS
            }
        }
    }

    private fun fail(nextFailure: StableFailure) {
        if (state == MuxerState.FAILED || state == MuxerState.EOS) return
        failure = nextFailure
        state = MuxerState.FAILED
        runCatching { sink.release() }
    }

    private fun closeInternal() {
        if (state != MuxerState.EOS && state != MuxerState.FAILED) runCatching { sink.release() }
        state = MuxerState.CLOSED
        closed.set(true)
    }

    private fun failure(code: FailureCode, message: String) = StableFailure(
        component = "muxer",
        code = code,
        severity = FailureSeverity.ERROR,
        recoverability = Recoverability.RETRYABLE,
        correlationId = correlationId,
        userMessage = message,
    )

    override fun close() {
        if (!closed.get()) {
            queue.offer(MuxerCommand.Close)
            closed.set(true)
        }
        if (ownExecutor) executor.shutdownNow()
    }
}
