/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.media.video

import com.librestatic.opencinecam.core.model.FailureCode
import com.librestatic.opencinecam.core.model.FailureSeverity
import com.librestatic.opencinecam.core.model.Recoverability
import com.librestatic.opencinecam.core.model.StableFailure
import com.librestatic.opencinecam.media.mux.EncodedSample
import com.librestatic.opencinecam.media.mux.MuxerActor
import com.librestatic.opencinecam.media.mux.MuxerTrack
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

sealed interface DrainEvent {
    data class Sample(val value: EncodedSample) : DrainEvent
    data object EndOfStream : DrainEvent
    data class Error(val message: String) : DrainEvent
}

fun interface DrainSource {
    fun next(): DrainEvent?
}

/** Dedicated non-main drain loop that forwards into the bounded muxer actor. */
class AsyncCodecDrain(
    private val source: DrainSource,
    private val track: MuxerTrack,
    private val muxer: MuxerActor,
    private val correlationId: String,
    executor: ExecutorService? = null,
) : AutoCloseable {
    private val executor: ExecutorService = executor ?: Executors.newSingleThreadExecutor()
    private val ownExecutor = executor == null
    private val started = AtomicBoolean(false)
    private val running = AtomicBoolean(false)

    fun start(): Boolean {
        if (!started.compareAndSet(false, true)) return false
        running.set(true)
        executor.execute {
            try {
                while (running.get()) {
                    when (val event = source.next() ?: break) {
                        is DrainEvent.Sample -> if (!muxer.submitSample(event.value)) {
                            muxer.submitError(failure(FailureCode.STORAGE_WRITE_FAILED, "Muxer queue is full."))
                            break
                        }
                        DrainEvent.EndOfStream -> {
                            muxer.submitEos(track)
                            break
                        }
                        is DrainEvent.Error -> {
                            muxer.submitError(failure(FailureCode.CAPTURE_OPEN_FAILED, event.message))
                            break
                        }
                    }
                }
            } catch (_: Throwable) {
                muxer.submitError(failure(FailureCode.CAPTURE_OPEN_FAILED, "Codec drain failed."))
            } finally {
                running.set(false)
            }
        }
        return true
    }

    override fun close() {
        running.set(false)
        if (ownExecutor) executor.shutdownNow()
    }

    private fun failure(code: FailureCode, message: String) = StableFailure(
        component = "codec-drain",
        code = code,
        severity = FailureSeverity.ERROR,
        recoverability = Recoverability.RETRYABLE,
        correlationId = correlationId,
        userMessage = message.ifBlank { "Codec drain failed." }.replace('\n', ' '),
    )
}
