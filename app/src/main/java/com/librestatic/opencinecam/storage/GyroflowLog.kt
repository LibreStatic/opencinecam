/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import java.io.Writer
import java.util.Locale

/**
 * Pure pieces of the Gyroflow `.gcsv` motion log: axis mapping, header, row formatting, timestamp
 * anchoring and accelerometer-to-gyro pairing. Nothing here touches Android, so it is host-tested.
 */
internal object GyroflowLog {
    const val EXTENSION = "gcsv"
    const val MIME_TYPE = "text/csv"
    const val GYRO_SCALE = 0.00001
    const val ACCEL_SCALE = 0.00001
    const val TIME_SCALE = 0.000001
    const val STANDARD_GRAVITY = 9.80665
    const val UNSYNCED_NOTE = "unsynced"
    /** Samples kept while the first recorded frame has not been reported yet (about 8 s at 1 kHz). */
    const val PRE_ANCHOR_LIMIT = 8192

    private fun quarterTurns(degrees: Int): Int = Math.floorMod(Math.round(degrees / 90.0).toInt(), 4)

    /**
     * Gyroflow's expected axes are X = image right, Y = image down, Z = forward through the lens, of
     * the pixels as stored. The sensor raster is the device plane rotated clockwise by the sensor
     * orientation, and the stored pixels are further rotated back by [storedPixelRotationDegrees].
     * Each letter names the raw device axis (x right, y up, z out of the screen) that feeds the
     * expected axis, uppercase when it points the same way and lowercase when inverted.
     */
    fun orientation(sensorOrientationDegrees: Int, frontFacing: Boolean, storedPixelRotationDegrees: Int): String {
        var right = if (frontFacing) intArrayOf(-1, 0) else intArrayOf(1, 0)
        var down = intArrayOf(0, -1)
        repeat(quarterTurns(sensorOrientationDegrees - storedPixelRotationDegrees)) {
            // A clockwise quarter turn maps (x, y) to (y, -x).
            right = intArrayOf(right[1], -right[0])
            down = intArrayOf(down[1], -down[0])
        }
        fun letter(x: Int, y: Int, z: Int): Char = when {
            x != 0 -> if (x > 0) 'X' else 'x'
            y != 0 -> if (y > 0) 'Y' else 'y'
            else -> if (z > 0) 'Z' else 'z'
        }
        return buildString {
            append(letter(right[0], right[1], 0))
            append(letter(down[0], down[1], 0))
            append(letter(0, 0, if (frontFacing) 1 else -1))
        }
    }

    /** Gyroflow readout direction: 0 top-to-bottom, 1 bottom-to-top, 2 left-to-right, 3 right-to-left. */
    fun readoutDirection(storedPixelRotationDegrees: Int): Int =
        intArrayOf(0, 3, 1, 2)[quarterTurns(storedPixelRotationDegrees)]

    class Header(
        val orientation: String,
        val videoFileName: String,
        val unixSeconds: Long,
        val firmware: String,
        val readoutTimeMs: Double?,
        val readoutDirection: Int,
        val synced: Boolean,
        val hasAccelerometer: Boolean,
        val vendor: String = "OpenCineCam",
    )

    private fun clean(value: String): String = value.replace(Regex("[\\r\\n,]+"), " ").trim()

    fun header(header: Header): String = buildString {
        fun line(key: String, value: String) { append(key).append(',').append(clean(value)).append('\n') }
        append("GYROFLOW IMU LOG\n")
        line("version", "1.3")
        line("id", "opencinecam")
        line("orientation", header.orientation)
        line("note", if (header.synced) "t=0 at first recorded frame" else "${UNSYNCED_NOTE}: t=0 at recording start")
        line("fwversion", header.firmware)
        line("timestamp", header.unixSeconds.toString())
        line("vendor", header.vendor)
        line("videofilename", header.videoFileName)
        header.readoutTimeMs?.takeIf { it > 0.0 && it.isFinite() }?.let {
            line("frame_readout_time", String.format(Locale.ROOT, "%.4f", it))
            line("frame_readout_direction", header.readoutDirection.toString())
        }
        line("tscale", plain(TIME_SCALE))
        line("gscale", plain(GYRO_SCALE))
        if (header.hasAccelerometer) line("ascale", plain(ACCEL_SCALE))
        append(if (header.hasAccelerometer) "t,gx,gy,gz,ax,ay,az\n" else "t,gx,gy,gz\n")
    }

    private fun plain(value: Double): String = java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

    /** One paired sample: times in sensor-clock nanoseconds, gyro in rad/s, acceleration in m/s^2. */
    class Sample(
        val timestampNs: Long,
        val gx: Float, val gy: Float, val gz: Float,
        val ax: Float, val ay: Float, val az: Float,
    )

    /** Microseconds since the anchor, or null for a sample before the first recorded frame. */
    fun relativeMicros(timestampNs: Long, anchorNs: Long): Long? {
        val delta = timestampNs - anchorNs
        return if (delta < 0) null else delta / 1_000L
    }

    fun row(micros: Long, sample: Sample, withAccelerometer: Boolean): String = buildString {
        append(micros)
        append(',').append(scaled(sample.gx.toDouble(), GYRO_SCALE))
        append(',').append(scaled(sample.gy.toDouble(), GYRO_SCALE))
        append(',').append(scaled(sample.gz.toDouble(), GYRO_SCALE))
        if (withAccelerometer) {
            append(',').append(scaled(sample.ax / STANDARD_GRAVITY, ACCEL_SCALE))
            append(',').append(scaled(sample.ay / STANDARD_GRAVITY, ACCEL_SCALE))
            append(',').append(scaled(sample.az / STANDARD_GRAVITY, ACCEL_SCALE))
        }
        append('\n')
    }

    private fun scaled(value: Double, scale: Double): Long = Math.round(value / scale)

    /**
     * Pairs every gyro sample with the accelerometer sample nearest in time. Gyro samples wait until
     * an accelerometer sample at or after them arrives, so the nearest one on either side is known.
     */
    class Pairer(private val emit: (Sample) -> Unit) {
        private class Gyro(val ns: Long, val x: Float, val y: Float, val z: Float)
        private class Accel(val ns: Long, val x: Float, val y: Float, val z: Float)
        private val pending = ArrayDeque<Gyro>()
        private var previous: Accel? = null

        fun onGyro(ns: Long, x: Float, y: Float, z: Float) { pending.addLast(Gyro(ns, x, y, z)) }

        fun onAccel(ns: Long, x: Float, y: Float, z: Float) {
            val next = Accel(ns, x, y, z)
            while (pending.isNotEmpty() && pending.first().ns <= ns) {
                val gyro = pending.removeFirst()
                val before = previous
                val nearest = if (before != null && gyro.ns - before.ns < ns - gyro.ns) before else next
                emit(pair(gyro, nearest))
            }
            previous = next
        }

        /** Pairs gyro samples still waiting with the last accelerometer sample seen, if any. */
        fun flush() {
            val last = previous
            while (pending.isNotEmpty()) {
                val gyro = pending.removeFirst()
                if (last != null) emit(pair(gyro, last))
            }
        }

        private fun pair(g: Gyro, a: Accel) = Sample(g.ns, g.x, g.y, g.z, a.x, a.y, a.z)
    }

    /**
     * Streams a log to [out]. The header is written once the anchor is known; samples before that are
     * held in a bounded buffer and those older than the anchor are dropped. Thread-safe.
     */
    class Stream(
        private val out: Writer,
        private val buildHeader: (synced: Boolean) -> String,
        private val withAccelerometer: Boolean,
    ) {
        private val early = ArrayDeque<Sample>()
        private var anchorNs: Long? = null
        private var closed = false
        var rows = 0L
            private set

        @Synchronized fun setAnchor(timestampNs: Long, synced: Boolean) {
            if (closed || anchorNs != null) return
            anchorNs = timestampNs
            out.write(buildHeader(synced))
            while (early.isNotEmpty()) write(early.removeFirst(), timestampNs)
        }

        @Synchronized fun add(sample: Sample) {
            if (closed) return
            val anchor = anchorNs
            if (anchor != null) { write(sample, anchor); return }
            if (early.size >= PRE_ANCHOR_LIMIT) early.removeFirst()
            early.addLast(sample)
        }

        val anchored: Boolean
            @Synchronized get() = anchorNs != null

        private fun write(sample: Sample, anchor: Long) {
            val micros = relativeMicros(sample.timestampNs, anchor) ?: return
            out.write(row(micros, sample, withAccelerometer))
            rows++
        }

        @Synchronized fun close() {
            if (closed) return
            closed = true
            early.clear()
            out.flush()
            out.close()
        }
    }
}
