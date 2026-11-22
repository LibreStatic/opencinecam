/* SPDX-License-Identifier: Apache-2.0 */

package com.librestatic.opencinecam.rawvideo

import com.librestatic.opencinecam.media.ocraw.OcrawImuMetadata
import com.librestatic.opencinecam.media.ocraw.OcrawImuSamples
import com.librestatic.opencinecam.motion.MotionSpoolFormat
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * The motion sensors of a RAW take (`IMU_SAMPLES`, `docs/formats/opencine-raw-v1.md`): raw device-frame
 * gyroscope, accelerometer and magnetometer samples, delivered on the source's own thread.
 */
interface RawMotionSource {
    /** What sensor event times minus this are on the RAW frames' clock (0 when they share one clock). */
    val clockOffsetNs: Long

    /** The take's static IMU `METADATA` keys ([OcrawImuMetadata]), written with the first frame. */
    val metadata: Map<String, String>

    /** Starts the sensors; samples go to [sink] until [stop]. False when there is no gyroscope to read. Never throws. */
    fun start(sink: RawMotionSink): Boolean

    /** Stops the sensors; no [RawMotionSink] call happens once it returns. Idempotent; never throws. */
    fun stop()
}

/** Where a [RawMotionSource] puts its samples; primitive arguments, so a sample allocates nothing. */
fun interface RawMotionSink {
    /** [kind] is a [MotionSpoolFormat] sensor kind, [sensorNs] the `SensorEvent` time, x, y and z its raw values. */
    fun onSample(kind: Int, sensorNs: Long, x: Float, y: Float, z: Float)
}

/**
 * Samples on their way from the sensor thread to the RAW writer, in a fixed pool of preallocated batches,
 * so a sample costs no allocation and the writer never waits for the sensors nor they for it.
 *
 * The sensor thread [append]s into the batch being filled under a small lock, mapping the kind to
 * [OcrawImuSamples] and the time to the frames' clock. A batch spanning [batchSpanNs] (or full) is handed to
 * the ready queue and an empty one taken from the free queue; when none is free (the writer is behind) the
 * batch's samples are dropped and counted instead ([droppedSamples]). The writer [poll]s ready batches and
 * gives each back with [recycle] once written, or [discard] when it could not write it. [close] hands the
 * partial batch over and refuses later samples.
 */
internal class RawImuBuffer(
    private val clockOffsetNs: Long,
    val batchCapacity: Int = BATCH_CAPACITY,
    batches: Int = BATCHES,
    private val batchSpanNs: Long = BATCH_SPAN_NS,
) {
    /** Record `i` is [kinds]`[i]` (an [OcrawImuSamples] kind), [timesNs]`[i]` and [xyz]`[3 i until 3 i + 3]`, for `i <` [count]. */
    class Batch(capacity: Int) {
        val kinds = ByteArray(capacity)
        val timesNs = LongArray(capacity)
        val xyz = FloatArray(capacity * 3)
        var count = 0
            internal set
    }

    init {
        require(batchCapacity in 1..OcrawImuSamples.MAX_RECORDS) { "batchCapacity must be within 1..${OcrawImuSamples.MAX_RECORDS}" }
        require(batches >= 2) { "at least two batches are needed to swap" }
        require(batchSpanNs > 0) { "batchSpanNs must be positive" }
    }

    private val lock = Any()
    private val free = ArrayBlockingQueue<Batch>(batches)
    // Holds every batch at most, so an offer from the sensor thread or close() always succeeds.
    private val ready = ArrayBlockingQueue<Batch>(batches)
    private val dropped = AtomicLong()

    // Guarded by lock.
    private var filling = Batch(batchCapacity)
    private var closed = false

    init {
        repeat(batches - 1) { free.add(Batch(batchCapacity)) }
    }

    /** Samples lost because no batch was free, or that the writer discarded. */
    val droppedSamples: Long get() = dropped.get()

    /** Sensor thread: one sample; unknown kinds and samples after [close] are ignored. */
    fun append(kind: Int, sensorNs: Long, x: Float, y: Float, z: Float) {
        val mapped = ocrawKind(kind)
        if (mapped == 0) return
        val timeNs = sensorNs - clockOffsetNs
        synchronized(lock) {
            if (closed) return
            val batch = filling
            val i = batch.count
            batch.kinds[i] = mapped.toByte()
            batch.timesNs[i] = timeNs
            batch.xyz[i * 3] = x
            batch.xyz[i * 3 + 1] = y
            batch.xyz[i * 3 + 2] = z
            batch.count = i + 1
            if (batch.count == batchCapacity || timeNs - batch.timesNs[0] >= batchSpanNs) handOffLocked()
        }
    }

    private fun handOffLocked() {
        val next = free.poll()
        if (next == null) {
            // The writer still holds every other batch: this one's samples go, the batch is refilled.
            dropped.addAndGet(filling.count.toLong())
            filling.count = 0
            return
        }
        check(ready.offer(filling))
        filling = next
    }

    /** Hands the partial batch to the writer and refuses later samples. Idempotent. */
    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            if (filling.count > 0) check(ready.offer(filling))
        }
    }

    /** Writer thread: the oldest batch ready to write, or null. */
    fun poll(): Batch? = ready.poll()

    /** Writer thread: [batch] was written; it goes back to the free pool. */
    fun recycle(batch: Batch) {
        batch.count = 0
        free.offer(batch)
    }

    /** Writer thread: [batch] could not be written; its samples count as dropped. */
    fun discard(batch: Batch) {
        dropped.addAndGet(batch.count.toLong())
        recycle(batch)
    }

    companion object {
        /** Gyroscope, accelerometer and magnetometer at their fastest rates make about 1,000 to 2,000 samples a second. */
        const val BATCH_CAPACITY = 4_096

        /** Four batches: about three seconds of samples may wait for a busy writer before any is dropped. */
        const val BATCHES = 4

        /** One `IMU_SAMPLES` chunk holds about a second of samples. */
        const val BATCH_SPAN_NS = 1_000_000_000L

        /** The [OcrawImuSamples] kind of a [MotionSpoolFormat] sensor kind, or 0 for any other kind. */
        fun ocrawKind(motionKind: Int): Int = when (motionKind) {
            MotionSpoolFormat.KIND_GYRO -> OcrawImuSamples.KIND_GYROSCOPE
            MotionSpoolFormat.KIND_ACCEL -> OcrawImuSamples.KIND_ACCELEROMETER
            MotionSpoolFormat.KIND_MAG -> OcrawImuSamples.KIND_MAGNETOMETER
            else -> 0
        }

        /**
         * The take's static IMU keys. A camera whose timestamp source is REALTIME stamps frames in boot time, the
         * sensors' clock ([OcrawImuMetadata.CLOCK_SHARED]); otherwise the offset was estimated at the take start.
         */
        fun takeMetadata(orientation: String, readoutDirection: Int, sensorRealtime: Boolean, takeStartUnixMs: Long): Map<String, String> =
            linkedMapOf(
                OcrawImuMetadata.ORIENTATION to orientation,
                OcrawImuMetadata.READOUT_DIRECTION to readoutDirection.toString(),
                OcrawImuMetadata.CLOCK to if (sensorRealtime) OcrawImuMetadata.CLOCK_SHARED else OcrawImuMetadata.CLOCK_ESTIMATED,
                OcrawImuMetadata.TAKE_START_UNIX_MS to takeStartUnixMs.toString(),
            )
    }
}
