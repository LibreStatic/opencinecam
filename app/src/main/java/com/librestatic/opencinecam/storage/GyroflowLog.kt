/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import com.librestatic.opencinecam.motion.MappedSeries
import com.librestatic.opencinecam.motion.VectorCursor
import com.librestatic.opencinecam.motion.cursor
import java.io.Writer
import java.util.Locale

/**
 * Pure pieces of the Gyroflow `.gcsv` motion log: axis mapping, header, row formatting, timestamp
 * anchoring and accelerometer-to-gyro pairing by file time. Nothing here touches Android, so it is host-tested.
 */
internal object GyroflowLog {
    const val EXTENSION = "gcsv"
    /**
     * Opaque on purpose: MediaStore appends the extension of a type it knows, so `text/csv` publishes `x.gcsv.csv`, which
     * Gyroflow does not pair with the video and the recording journal refuses as a changed identity.
     */
    const val MIME_TYPE = "application/octet-stream"
    const val GYRO_SCALE = 0.00001
    const val ACCEL_SCALE = 0.00001
    /** Magnetometer scale: values are in gauss (µT / 100). */
    const val MAG_SCALE = 0.00001
    const val MICROTESLA_PER_GAUSS = 100.0
    const val TIME_SCALE = 0.000001
    const val STANDARD_GRAVITY = 9.80665
    const val UNSYNCED_NOTE = "unsynced"

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

    /**
     * The inverse use of [orientation]: output[i] is the raw device axis named by letter i of
     * [orientation] ('X', 'Y', 'Z'), negated when the letter is lowercase. Applied to a raw
     * device-frame vector it gives the vector in image axes (X right, Y down, Z forward).
     *
     * @throws IllegalArgumentException unless [orientation] is three letters using each axis once.
     */
    fun applyOrientation(orientation: String, x: Float, y: Float, z: Float): FloatArray {
        require(orientation.length == 3) { "orientation must have three letters: $orientation" }
        val used = BooleanArray(3)
        val out = FloatArray(3)
        for (i in 0 until 3) {
            val letter = orientation[i]
            val axis = when (letter.uppercaseChar()) {
                'X' -> 0
                'Y' -> 1
                'Z' -> 2
                else -> throw IllegalArgumentException("unknown axis '$letter' in $orientation")
            }
            require(!used[axis]) { "axis used twice in $orientation" }
            used[axis] = true
            val value = when (axis) {
                0 -> x
                1 -> y
                else -> z
            }
            out[i] = if (letter.isUpperCase()) value else -value
        }
        return out
    }

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
        /** Magnetometer columns are written only together with the accelerometer's (gcsv allows t,g,a,m). */
        val hasMagnetometer: Boolean = false,
        /** Replaces the synced or unsynced note text when not null. */
        val note: String? = null,
    ) {
        internal val writesMagnetometer: Boolean get() = hasMagnetometer && hasAccelerometer
    }

    internal fun clean(value: String): String = value.replace(Regex("[\\r\\n,]+"), " ").trim()

    fun header(header: Header): String = buildString {
        fun line(key: String, value: String) { append(key).append(',').append(clean(value)).append('\n') }
        append("GYROFLOW IMU LOG\n")
        line("version", "1.3")
        line("id", "opencinecam")
        line("orientation", header.orientation)
        line(
            "note",
            header.note ?: if (header.synced) "t=0 at first recorded frame" else "${UNSYNCED_NOTE}: t=0 at recording start",
        )
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
        if (header.writesMagnetometer) line("mscale", plain(MAG_SCALE))
        append(
            when {
                header.writesMagnetometer -> "t,gx,gy,gz,ax,ay,az,mx,my,mz\n"
                header.hasAccelerometer -> "t,gx,gy,gz,ax,ay,az\n"
                else -> "t,gx,gy,gz\n"
            },
        )
    }

    internal fun plain(value: Double): String = java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

    /**
     * One paired sample: times in sensor-clock nanoseconds, gyro in rad/s, acceleration in m/s^2,
     * magnetic field in µT.
     */
    class Sample(
        val timestampNs: Long,
        val gx: Float, val gy: Float, val gz: Float,
        val ax: Float, val ay: Float, val az: Float,
        val mx: Float = 0f, val my: Float = 0f, val mz: Float = 0f,
    )

    /** Microseconds since the anchor, or null for a sample before the first recorded frame. */
    fun relativeMicros(timestampNs: Long, anchorNs: Long): Long? {
        val delta = timestampNs - anchorNs
        return if (delta < 0) null else delta / 1_000L
    }

    /** Magnetometer columns are written only together with the accelerometer's. */
    fun row(micros: Long, sample: Sample, withAccelerometer: Boolean, withMagnetometer: Boolean = false): String =
        StringBuilder(64).also {
            appendRow(
                it, micros, sample.gx, sample.gy, sample.gz,
                if (withAccelerometer) sample.ax else null, sample.ay, sample.az,
                if (withAccelerometer && withMagnetometer) sample.mx else null, sample.my, sample.mz,
            )
        }.toString()

    /** A null [ax] or [mx] leaves out that sensor's columns. */
    internal fun appendRow(
        out: StringBuilder,
        micros: Long,
        gx: Float, gy: Float, gz: Float,
        ax: Float?, ay: Float, az: Float,
        mx: Float?, my: Float, mz: Float,
    ) {
        out.append(micros)
        out.append(',').append(scaled(gx.toDouble(), GYRO_SCALE))
        out.append(',').append(scaled(gy.toDouble(), GYRO_SCALE))
        out.append(',').append(scaled(gz.toDouble(), GYRO_SCALE))
        if (ax != null) {
            out.append(',').append(scaled(ax / STANDARD_GRAVITY, ACCEL_SCALE))
            out.append(',').append(scaled(ay / STANDARD_GRAVITY, ACCEL_SCALE))
            out.append(',').append(scaled(az / STANDARD_GRAVITY, ACCEL_SCALE))
            if (mx != null) {
                out.append(',').append(scaled(mx / MICROTESLA_PER_GAUSS, MAG_SCALE))
                out.append(',').append(scaled(my / MICROTESLA_PER_GAUSS, MAG_SCALE))
                out.append(',').append(scaled(mz / MICROTESLA_PER_GAUSS, MAG_SCALE))
            }
        }
        out.append('\n')
    }

    /** What [render] wrote: the rows, and the file times of the first and last in microseconds (0 without rows). */
    class Rows(val count: Long, val firstUs: Long, val lastUs: Long)

    /** [render] over whole series; returns the rows written. */
    fun render(out: Writer, header: Header, gyro: MappedSeries, accel: MappedSeries?, mag: MappedSeries?): Long =
        render(out, header, gyro.cursor(), accel?.cursor(), mag?.cursor()).count

    /**
     * Writes a whole log to [out]: the header, then one row per [gyro] sample, each paired with the
     * [accel] and [mag] samples nearest in file time (a tie takes the later one).
     * Row times are the gyro sample's file time in microseconds. The accelerometer columns are
     * written when [header] asks for them and [accel] has samples; the magnetometer's likewise, and
     * only with the accelerometer's. All three are file-time cursors with strictly increasing times;
     * [accel] and [mag] are read at most one sample past the last row's time, and none is closed.
     * [out] is flushed, not closed.
     */
    fun render(out: Writer, header: Header, gyro: VectorCursor, accel: VectorCursor?, mag: VectorCursor?): Rows {
        // Peeking before the header decides whether each sensor has a column.
        val accelNearest = accel?.let(::Nearest)
        val magNearest = mag?.let(::Nearest)
        val withAccel = header.hasAccelerometer && accelNearest != null && accelNearest.any
        val withMag = withAccel && header.hasMagnetometer && magNearest != null && magNearest.any
        val effective = Header(
            orientation = header.orientation,
            videoFileName = header.videoFileName,
            unixSeconds = header.unixSeconds,
            firmware = header.firmware,
            readoutTimeMs = header.readoutTimeMs,
            readoutDirection = header.readoutDirection,
            synced = header.synced,
            hasAccelerometer = withAccel,
            vendor = header.vendor,
            hasMagnetometer = withMag,
            note = header.note,
        )
        out.write(header(effective))
        val line = StringBuilder(96)
        var rows = 0L
        var firstUs = 0L
        var lastUs = 0L
        while (gyro.next()) {
            val t = gyro.time
            line.setLength(0)
            var ax: Float? = null
            var ay = 0f
            var az = 0f
            if (withAccel) {
                accelNearest!!.at(t)
                ax = accelNearest.x
                ay = accelNearest.y
                az = accelNearest.z
            }
            var mx: Float? = null
            var my = 0f
            var mz = 0f
            if (withMag) {
                magNearest!!.at(t)
                mx = magNearest.x
                my = magNearest.y
                mz = magNearest.z
            }
            appendRow(line, t, gyro.x, gyro.y, gyro.z, ax, ay, az, mx, my, mz)
            out.append(line)
            if (rows == 0L) firstUs = t
            lastUs = t
            rows++
        }
        out.flush()
        return Rows(rows, firstUs, lastUs)
    }

    /**
     * The sample of [cursor] nearest each target time, for targets that only increase: the floor (the last sample at
     * or before the target, else the first) and the sample after it, which wins when the floor is before the target
     * and the next is no farther (a tie takes the later one).
     */
    private class Nearest(private val cursor: VectorCursor) {
        /** False when the stream has no sample. */
        val any: Boolean = cursor.next()
        private var floorUs = 0L
        var x = 0f
            private set
        var y = 0f
            private set
        var z = 0f
            private set
        private var floorX = 0f
        private var floorY = 0f
        private var floorZ = 0f

        // The cursor itself holds the sample after the floor while this is true.
        private var hasNext = false

        init {
            if (any) {
                takeFloor()
                hasNext = cursor.next()
            }
        }

        fun at(t: Long) {
            while (hasNext && cursor.time <= t) {
                takeFloor()
                hasNext = cursor.next()
            }
            if (hasNext && floorUs < t && cursor.time - t <= t - floorUs) {
                x = cursor.x
                y = cursor.y
                z = cursor.z
            } else {
                x = floorX
                y = floorY
                z = floorZ
            }
        }

        private fun takeFloor() {
            floorUs = cursor.time
            floorX = cursor.x
            floorY = cursor.y
            floorZ = cursor.z
        }
    }

    private fun scaled(value: Double, scale: Double): Long = Math.round(value / scale)
}
