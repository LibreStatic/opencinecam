/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.media.audio

import android.media.AudioRecord
import android.media.AudioTimestamp
import java.nio.ByteBuffer

/** Which platform API a [PcmInput] reads from (PLAN-074 decision 3). */
enum class PcmInputPath { AUDIO_RECORD, AAUDIO_LOW_LATENCY }

/** A capture position: [framePosition] was captured at [nanoTime] on the boot-time clock. */
class PcmInputTimestamp {
    var framePosition: Long = 0
    var nanoTime: Long = 0
}

/**
 * The PCM source a read loop consumes (PLAN-074 decision 3): start, blocking reads into a direct
 * buffer, a frame-position timestamp on the boot-time clock, stop and close. [AudioRecordPcmInput]
 * wraps today's `AudioRecord`; [AAudioPcmInput] the native low-latency stream.
 */
interface PcmInput : AutoCloseable {
    val sampleRateHz: Int
    val channels: Int
    /** The `AudioFormat.ENCODING_*` of the delivered samples, interleaved. */
    val encoding: Int
    /** The session platform effects attach to, or null when this input has none. */
    val audioSessionId: Int?
    /** The device the platform routed the input to, or null while it is not known. */
    val routedDeviceId: Int?
    val path: PcmInputPath

    /**
     * Times the input discarded captured audio because the reads fell behind, so far; 0 when the
     * input cannot tell. A read after an overrun succeeds, so this is the only sign of the gap.
     */
    val overrunCount: Int get() = 0

    /** Starts capturing; throws [IllegalStateException] when the input does not start. */
    fun start()

    /**
     * Blocks until [byteCount] bytes (whole frames) are written from the start of [buffer], a
     * direct buffer whose position and limit are left alone. Returns the bytes read, 0 on a
     * timeout with nothing read, or [ERROR_DEAD] / [ERROR_INVALID].
     */
    fun read(buffer: ByteBuffer, byteCount: Int): Int

    /** Fills [out] with the latest capture position; false while none is published yet. */
    fun timestamp(out: PcmInputTimestamp): Boolean

    fun stop()

    /** Releases the input. Idempotent. */
    override fun close()

    companion object {
        /** The input is gone (released, disconnected, or its server died); reopen to continue. */
        const val ERROR_DEAD = AudioRecord.ERROR_DEAD_OBJECT

        /** The read was refused: bad arguments, wrong state, or another platform error. */
        const val ERROR_INVALID = AudioRecord.ERROR_INVALID_OPERATION
    }
}

/**
 * Today's `AudioRecord` behind [PcmInput], with the semantics the read loops already rely on:
 * `READ_BLOCKING`, `TIMEBASE_BOOTTIME`. [audioRecord] stays exposed so callers still attach the
 * platform effects and [AudioInputRouteGuard] to it.
 */
class AudioRecordPcmInput(val audioRecord: AudioRecord) : PcmInput {
    private val stamp = AudioTimestamp()

    override val sampleRateHz: Int get() = audioRecord.sampleRate
    override val channels: Int get() = audioRecord.channelCount
    override val encoding: Int get() = audioRecord.audioFormat
    override val audioSessionId: Int? get() = audioRecord.audioSessionId.takeIf { it > 0 }
    override val routedDeviceId: Int? get() = audioRecord.routedDevice?.id
    override val path: PcmInputPath get() = PcmInputPath.AUDIO_RECORD

    override fun start() {
        audioRecord.startRecording()
        check(audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            "AudioRecord did not enter RECORDING state."
        }
    }

    override fun read(buffer: ByteBuffer, byteCount: Int): Int =
        audioRecordReadResult(audioRecord.read(buffer, byteCount, AudioRecord.READ_BLOCKING))

    override fun timestamp(out: PcmInputTimestamp): Boolean = synchronized(stamp) {
        if (audioRecord.getTimestamp(stamp, AudioTimestamp.TIMEBASE_BOOTTIME) != AudioRecord.SUCCESS) {
            return false
        }
        out.framePosition = stamp.framePosition
        out.nanoTime = stamp.nanoTime
        true
    }

    /** Stops a recording record; a record that never started or already stopped is left alone. */
    override fun stop() {
        if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) audioRecord.stop()
    }

    override fun close() = audioRecord.release()
}

/**
 * `AudioRecord.read` codes as [PcmInput.read] reports them. A dead object is [PcmInput.ERROR_DEAD];
 * every other negative code (invalid operation, bad value, generic error) is [PcmInput.ERROR_INVALID].
 */
internal fun audioRecordReadResult(code: Int): Int = when {
    code >= 0 -> code
    code == AudioRecord.ERROR_DEAD_OBJECT -> PcmInput.ERROR_DEAD
    else -> PcmInput.ERROR_INVALID
}
