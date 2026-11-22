/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.motion

import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The raw motion spool written during a take and rendered once it stops, when the sensor-time to
 * file-time mapping is known.
 *
 * Layout: the magic bytes `OCMO`, one version byte ([VERSION]), then fixed-size little-endian records
 * of [RECORD_BYTES] bytes: kind u8, timeNs s64 and 12 value bytes.
 * - [KIND_GYRO] (rad/s), [KIND_ACCEL] (m/s²), [KIND_MAG] (µT): the raw device-frame SensorEvent
 *   values as three f32, timeNs is SensorEvent.timestamp.
 * - [KIND_FRAME]: timeNs is the camera sensor timestamp of a recorded video frame; the value bytes
 *   hold s64 filePtsNs (its presentation time in the file's timeline before any constant rebase)
 *   and 4 zero bytes.
 * - [KIND_EXPOSURE]: timeNs is the camera timestamp from which it applies; the value bytes hold s32
 *   exposureTimeNs, s32 rollingShutterSkewNs (0 = unknown) and 4 zero bytes.
 */
internal object MotionSpoolFormat {
    val MAGIC: ByteArray = byteArrayOf('O'.code.toByte(), 'C'.code.toByte(), 'M'.code.toByte(), 'O'.code.toByte())
    const val VERSION: Int = 1
    const val HEADER_BYTES: Int = 5
    const val RECORD_BYTES: Int = 21

    const val KIND_GYRO: Int = 1
    const val KIND_ACCEL: Int = 2
    const val KIND_MAG: Int = 3
    const val KIND_FRAME: Int = 4
    const val KIND_EXPOSURE: Int = 5

    internal const val BUFFER_BYTES: Int = 64 * 1024
}

/**
 * One sensor's samples as a scan of the spool counted them, after the drop rule of [MotionSpool.samples]: the count,
 * the first and last timestamp and the largest interval between consecutive samples (0 with fewer than two).
 */
internal class SensorStats(val count: Long, val firstNs: Long, val lastNs: Long, val largestGapNs: Long) {
    /** The mean rate on the raw sensor clock; null with fewer than two samples. */
    val measuredHz: Double?
        get() {
            if (count < 2) return null
            val span = lastNs - firstNs
            return if (span <= 0) null else (count - 1) * 1_000_000_000.0 / span
        }

    companion object {
        val NONE = SensorStats(0L, 0L, 0L, 0L)
    }
}

/** Recorded video frames in spool order: camera timestamp and file presentation time. */
internal class FrameTrack(val sensorNs: LongArray, val filePtsNs: LongArray) {
    init {
        require(sensorNs.size == filePtsNs.size) { "frame arrays differ in size" }
    }

    val size: Int get() = sensorNs.size
}

/** Exposure records sorted by the camera timestamp from which each applies. */
internal class ExposureTrack(val fromNs: LongArray, val exposureNs: IntArray, val skewNs: IntArray) {
    init {
        require(exposureNs.size == fromNs.size && skewNs.size == fromNs.size) { "exposure arrays differ in size" }
    }

    val size: Int get() = fromNs.size
}

/**
 * A scanned spool ([MotionSpoolReader.scan]): its frames and exposures in memory (16 and 16 bytes a record, a small
 * part of the spool), and per sensor only its [SensorStats]. The samples stay in the spool and are read again, in
 * order, through [samples], each cursor on its own stream from [open].
 */
internal class MotionSpool(
    val frames: FrameTrack,
    val exposures: ExposureTrack,
    val gyroStats: SensorStats,
    val accelStats: SensorStats,
    val magStats: SensorStats,
    /** Opens the spool from its start; every cursor calls it once and closes what it returns. */
    val open: () -> InputStream,
) {
    /**
     * The samples of one sensor [kind] in spool order. A sample whose timestamp is not after the previous kept one of
     * that sensor is dropped: a single listener thread never delivers out of order, so this only removes duplicates
     * and the rare glitch, and keeps the stream strictly increasing without sorting it. A torn tail ends the stream
     * quietly and unknown kinds are skipped. Close the cursor.
     *
     * @throws IllegalArgumentException when the spool's header is no longer valid.
     */
    fun samples(kind: Int): VectorCursor {
        require(kind == MotionSpoolFormat.KIND_GYRO || kind == MotionSpoolFormat.KIND_ACCEL || kind == MotionSpoolFormat.KIND_MAG) {
            "not a sensor kind: $kind"
        }
        return SpoolSampleCursor(SpoolRecords(open()), kind)
    }
}

/**
 * Appends records to [out] through a 64 KiB buffer. The header is written on construction. Every
 * method is thread-safe; calls after [close] are ignored, so a late sensor callback is harmless.
 */
internal class MotionSpoolWriter(private val out: OutputStream) {
    private val buffer = ByteBuffer.allocate(MotionSpoolFormat.BUFFER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    private var closed = false

    @get:Synchronized var gyroCount: Long = 0
        private set
    @get:Synchronized var accelCount: Long = 0
        private set
    @get:Synchronized var magCount: Long = 0
        private set
    @get:Synchronized var frameCount: Long = 0
        private set
    @get:Synchronized var exposureCount: Long = 0
        private set

    init {
        out.write(MotionSpoolFormat.MAGIC)
        out.write(MotionSpoolFormat.VERSION)
    }

    /** A gyro, accelerometer or magnetometer sample; [kind] is one of the three sensor kinds. */
    @Synchronized fun sample(kind: Int, timeNs: Long, x: Float, y: Float, z: Float) {
        require(kind == MotionSpoolFormat.KIND_GYRO || kind == MotionSpoolFormat.KIND_ACCEL || kind == MotionSpoolFormat.KIND_MAG) {
            "not a sensor kind: $kind"
        }
        if (closed) return
        begin(kind, timeNs)
        buffer.putFloat(x).putFloat(y).putFloat(z)
        when (kind) {
            MotionSpoolFormat.KIND_GYRO -> gyroCount++
            MotionSpoolFormat.KIND_ACCEL -> accelCount++
            else -> magCount++
        }
    }

    @Synchronized fun frame(sensorNs: Long, filePtsNs: Long) {
        if (closed) return
        begin(MotionSpoolFormat.KIND_FRAME, sensorNs)
        buffer.putLong(filePtsNs).putInt(0)
        frameCount++
    }

    @Synchronized fun exposure(fromNs: Long, exposureNs: Int, skewNs: Int) {
        if (closed) return
        begin(MotionSpoolFormat.KIND_EXPOSURE, fromNs)
        buffer.putInt(exposureNs).putInt(skewNs).putInt(0)
        exposureCount++
    }

    @Synchronized fun flush() {
        if (closed) return
        drain()
        out.flush()
    }

    @Synchronized fun close() {
        if (closed) return
        closed = true
        try {
            drain()
            out.flush()
        } finally {
            out.close()
        }
    }

    private fun begin(kind: Int, timeNs: Long) {
        if (buffer.remaining() < MotionSpoolFormat.RECORD_BYTES) drain()
        buffer.put(kind.toByte()).putLong(timeNs)
    }

    private fun drain() {
        if (buffer.position() == 0) return
        out.write(buffer.array(), 0, buffer.position())
        buffer.clear()
    }
}

internal object MotionSpoolReader {
    /**
     * Reads the spool once and keeps only what the timeline needs: the frames in spool order, the exposures sorted by
     * their start time, and each sensor's [SensorStats] after the drop rule of [MotionSpool.samples]. No sensor sample
     * is kept; the cursors read them again from [open]. A torn tail (a partial last record) ends the read and unknown
     * kinds are skipped.
     *
     * @throws IllegalArgumentException when the magic or version is wrong or the header is cut short.
     */
    fun scan(open: () -> InputStream): MotionSpool {
        val gyro = SensorTally()
        val accel = SensorTally()
        val mag = SensorTally()
        val frames = LongPairBuilder()
        val exposures = ExposureBuilder()
        SpoolRecords(open()).use { records ->
            while (records.next()) {
                when (records.kind) {
                    MotionSpoolFormat.KIND_GYRO -> gyro.accept(records.timeNs)
                    MotionSpoolFormat.KIND_ACCEL -> accel.accept(records.timeNs)
                    MotionSpoolFormat.KIND_MAG -> mag.accept(records.timeNs)
                    MotionSpoolFormat.KIND_FRAME -> frames.add(records.timeNs, records.long(0))
                    MotionSpoolFormat.KIND_EXPOSURE -> exposures.add(records.timeNs, records.int(0), records.int(4))
                    else -> Unit
                }
            }
        }
        return MotionSpool(frames.build(), exposures.build(), gyro.stats(), accel.stats(), mag.stats(), open)
    }

    fun scan(file: File): MotionSpool = scan { FileInputStream(file) }

    private class LongPairBuilder {
        private var first = LongArray(INITIAL)
        private var second = LongArray(INITIAL)
        private var size = 0

        fun add(a: Long, b: Long) {
            if (size == first.size) {
                first = first.copyOf(size * 2)
                second = second.copyOf(size * 2)
            }
            first[size] = a
            second[size] = b
            size++
        }

        fun build(): FrameTrack = FrameTrack(first.copyOf(size), second.copyOf(size))
    }

    private class ExposureBuilder {
        private var from = LongArray(INITIAL)
        private var exposure = IntArray(INITIAL)
        private var skew = IntArray(INITIAL)
        private var size = 0
        private var monotonic = true

        fun add(fromNs: Long, exposureNs: Int, skewNs: Int) {
            if (size == from.size) {
                from = from.copyOf(size * 2)
                exposure = exposure.copyOf(size * 2)
                skew = skew.copyOf(size * 2)
            }
            if (size > 0 && fromNs < from[size - 1]) monotonic = false
            from[size] = fromNs
            exposure[size] = exposureNs
            skew[size] = skewNs
            size++
        }

        fun build(): ExposureTrack {
            if (monotonic) return ExposureTrack(from.copyOf(size), exposure.copyOf(size), skew.copyOf(size))
            val order = stableOrder(from, size)
            return ExposureTrack(
                LongArray(size) { from[order[it]] },
                IntArray(size) { exposure[order[it]] },
                IntArray(size) { skew[order[it]] },
            )
        }
    }

    private const val INITIAL = 1024

    /** Indices 0 until [size] stably ordered by [keys], without boxing (a bottom-up merge sort). */
    private fun stableOrder(keys: LongArray, size: Int): IntArray {
        var src = IntArray(size) { it }
        var dst = IntArray(size)
        var width = 1
        while (width < size) {
            var lo = 0
            while (lo < size) {
                val mid = minOf(lo + width, size)
                val hi = minOf(lo + 2 * width, size)
                var i = lo
                var j = mid
                var k = lo
                while (i < mid && j < hi) {
                    if (keys[src[j]] < keys[src[i]]) dst[k++] = src[j++] else dst[k++] = src[i++]
                }
                while (i < mid) dst[k++] = src[i++]
                while (j < hi) dst[k++] = src[j++]
                lo = hi
            }
            val swap = src
            src = dst
            dst = swap
            width *= 2
        }
        return src
    }
}

/**
 * The records of a spool in file order, read through one chunk buffer. The header is checked on construction (and
 * [input] closed when it fails); [close] closes [input].
 */
internal class SpoolRecords(private val input: InputStream) : Closeable {
    private val chunk = ByteArray(MotionSpoolFormat.BUFFER_BYTES - MotionSpoolFormat.BUFFER_BYTES % MotionSpoolFormat.RECORD_BYTES)
    private val view = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)
    private var records = 0
    private var at = 0
    private var base = 0
    private var ended = false

    /** The current record's kind and time. */
    var kind: Int = 0
        private set
    var timeNs: Long = 0L
        private set

    init {
        try {
            val header = ByteArray(MotionSpoolFormat.HEADER_BYTES)
            require(readFully(header) == header.size) { "motion spool header is incomplete" }
            for (i in MotionSpoolFormat.MAGIC.indices) {
                require(header[i] == MotionSpoolFormat.MAGIC[i]) { "not a motion spool" }
            }
            val version = header[4].toInt() and 0xFF
            require(version == MotionSpoolFormat.VERSION) { "unsupported motion spool version $version" }
        } catch (problem: Throwable) {
            input.close()
            throw problem
        }
    }

    /** Moves to the next whole record; false at the end of the spool or at a torn tail, and from then on. */
    fun next(): Boolean {
        while (at >= records) {
            if (ended) return false
            val read = readFully(chunk)
            records = read / MotionSpoolFormat.RECORD_BYTES
            at = 0
            if (read < chunk.size) ended = true
        }
        base = at * MotionSpoolFormat.RECORD_BYTES
        at++
        kind = chunk[base].toInt() and 0xFF
        timeNs = view.getLong(base + 1)
        return true
    }

    /** The current record's value bytes at [offset] (0, 4 or 8). */
    fun float(offset: Int): Float = view.getFloat(base + VALUE_OFFSET + offset)
    fun int(offset: Int): Int = view.getInt(base + VALUE_OFFSET + offset)
    fun long(offset: Int): Long = view.getLong(base + VALUE_OFFSET + offset)

    override fun close() = input.close()

    private fun readFully(target: ByteArray): Int {
        var total = 0
        while (total < target.size) {
            val n = input.read(target, total, target.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    private companion object {
        const val VALUE_OFFSET = 9
    }
}

/** The drop rule of [MotionSpool.samples], with the stats of what it keeps. */
private class SensorTally {
    private var count = 0L
    private var firstNs = 0L
    private var lastNs = 0L
    private var largestGapNs = 0L

    /** False, and nothing counted, when [timeNs] is not after the last kept sample. */
    fun accept(timeNs: Long): Boolean {
        if (count > 0) {
            if (timeNs <= lastNs) return false
            largestGapNs = maxOf(largestGapNs, timeNs - lastNs)
        } else {
            firstNs = timeNs
        }
        lastNs = timeNs
        count++
        return true
    }

    fun stats(): SensorStats = if (count == 0L) SensorStats.NONE else SensorStats(count, firstNs, lastNs, largestGapNs)
}

/** One sensor's kept samples straight from the spool records. */
private class SpoolSampleCursor(private val records: SpoolRecords, private val kind: Int) : VectorCursor {
    private val tally = SensorTally()

    override var time: Long = 0L
        private set
    override var x: Float = 0f
        private set
    override var y: Float = 0f
        private set
    override var z: Float = 0f
        private set

    override fun next(): Boolean {
        while (records.next()) {
            if (records.kind != kind || !tally.accept(records.timeNs)) continue
            time = records.timeNs
            x = records.float(0)
            y = records.float(4)
            z = records.float(8)
            return true
        }
        return false
    }

    override fun close() = records.close()
}
