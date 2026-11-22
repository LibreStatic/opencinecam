/* SPDX-License-Identifier: Apache-2.0 */

package com.librestatic.opencinecam.export

import com.librestatic.opencinecam.media.ocraw.OcrawFrameIndex
import com.librestatic.opencinecam.media.ocraw.OcrawImuChunk
import com.librestatic.opencinecam.media.ocraw.OcrawImuMetadata
import com.librestatic.opencinecam.media.ocraw.OcrawImuSamples
import com.librestatic.opencinecam.media.ocraw.OcrawImuSamplesData
import com.librestatic.opencinecam.media.ocraw.OcrawStreamReader
import com.librestatic.opencinecam.motion.MotionTakeRender
import com.librestatic.opencinecam.storage.GyroflowLog
import java.io.IOException
import java.io.Writer
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Locale

/**
 * The Gyroflow `.gcsv` log of a RAW take's `IMU_SAMPLES` chunks, the same text as `gcsv_text` in
 * `desktop-tools/ocraw_to_dng.py` writes for the same take: t = 0 is the first `RAW_FRAME` (DNG number 0), gyroscope
 * samples before it or after the last frame plus one frame interval are dropped, and each row pairs its gyroscope
 * sample with the nearest accelerometer and magnetometer samples (a tie takes the later one; the magnetometer only
 * with the accelerometer, the gcsv column order). Rows and scales follow [GyroflowLog].
 *
 * Streamed: chunks are decoded one at a time, once per sensor, so a take of any length costs one chunk's samples.
 * It relies on each sensor's samples being in time order across chunks, as the recorder writes them (the desktop
 * tool sorts them, which changes nothing for such a take).
 */
internal object OcrawGyroflowLog {
    const val NOTE_SHARED = "t=0 at first recorded frame"
    const val NOTE_ESTIMATED = MotionTakeRender.NOTE_CLOCK_ESTIMATED

    /** The sensors a take's readable `IMU_SAMPLES` chunks hold. */
    class Sensors(val gyroscope: Boolean, val accelerometer: Boolean, val magnetometer: Boolean)

    class Report(val rows: Long, val droppedRows: Long, val accelerometer: Boolean, val magnetometer: Boolean, val synced: Boolean)

    /** Thrown by [sensors] and [render] when their `isInterrupted` answers true before a chunk is read. */
    class Interrupted : RuntimeException("motion log interrupted")

    /**
     * Which sensors the take holds samples of; a chunk that cannot be read or decoded is skipped. [isInterrupted] is
     * asked before each chunk is read; true throws [Interrupted].
     */
    fun sensors(reader: OcrawStreamReader, index: OcrawFrameIndex, isInterrupted: () -> Boolean = { false }): Sensors {
        var gyro = false
        var accel = false
        var mag = false
        for (chunk in index.imu) {
            if (isInterrupted()) throw Interrupted()
            val data = read(reader, chunk) ?: continue
            for (i in 0 until data.count) when (data.kind(i)) {
                OcrawImuSamples.KIND_GYROSCOPE -> gyro = true
                OcrawImuSamples.KIND_ACCELEROMETER -> accel = true
                OcrawImuSamples.KIND_MAGNETOMETER -> mag = true
            }
            if (gyro && accel && mag) break
        }
        return Sensors(gyro, accel, mag)
    }

    /**
     * Writes the log to [out] (flushed, not closed) and returns what it wrote, or null without writing anything when
     * the take has no frame or no gyroscope sample. [videoFileName] is the `.ocraw` file's name, as the desktop tool writes.
     * [isInterrupted] is asked before each chunk is read; true throws [Interrupted], leaving [out] partly written.
     */
    fun render(
        out: Writer,
        reader: OcrawStreamReader,
        index: OcrawFrameIndex,
        videoFileName: String,
        sensors: Sensors = sensors(reader, index),
        isInterrupted: () -> Boolean = { false },
    ): Report? {
        val frames = index.frames
        val timeBase = index.timeBase
        if (frames.isEmpty() || timeBase == null || !sensors.gyroscope) return null
        val ns = TicksToNs(timeBase.numerator, timeBase.denominator)
        val anchor = ns(frames.first().timestampTicks)
        val last = ns(frames.last().timestampTicks)
        val fps = parseFps(index.metadata["fps"])
        // Without a frame rate, the last frame interval; with a single frame, none.
        val lastInterval = if (fps == null && frames.size > 1) last - ns(frames[frames.size - 2].timestampTicks) else 0L
        fun afterEnd(timeNs: Long): Boolean {
            val rel = timeNs - last
            if (rel <= 0) return false
            if (fps == null) return rel > lastInterval
            // rel > 1e9 / fps, with fps = numerator / denominator.
            return BigInteger.valueOf(rel).multiply(fps.first) > NS_PER_SECOND.multiply(fps.second)
        }

        val metadata = index.metadata
        val synced = metadata[OcrawImuMetadata.CLOCK] == OcrawImuMetadata.CLOCK_SHARED
        val accel = sensors.accelerometer
        val mag = accel && sensors.magnetometer
        out.write(header(metadata, videoFileName, synced, accel, mag))

        val gyro = KindStream(reader, index.imu, OcrawImuSamples.KIND_GYROSCOPE, isInterrupted)
        val accelPair = if (accel) Nearest(KindStream(reader, index.imu, OcrawImuSamples.KIND_ACCELEROMETER, isInterrupted)) else null
        val magPair = if (mag) Nearest(KindStream(reader, index.imu, OcrawImuSamples.KIND_MAGNETOMETER, isInterrupted)) else null
        val line = StringBuilder(96)
        var rows = 0L
        var dropped = 0L
        while (gyro.next()) {
            val t = gyro.timeNs
            if (t < anchor || afterEnd(t)) {
                dropped++
                continue
            }
            accelPair?.at(t)
            magPair?.at(t)
            line.setLength(0)
            GyroflowLog.appendRow(
                line, (t - anchor) / 1_000L, gyro.x, gyro.y, gyro.z,
                accelPair?.x, accelPair?.y ?: 0f, accelPair?.z ?: 0f,
                magPair?.x, magPair?.y ?: 0f, magPair?.z ?: 0f,
            )
            out.append(line)
            rows++
        }
        out.flush()
        return Report(rows, dropped, accel, mag, synced)
    }

    internal fun header(metadata: Map<String, String>, videoFileName: String, synced: Boolean, accel: Boolean, mag: Boolean): String =
        buildString {
            fun line(key: String, value: String) { append(key).append(',').append(GyroflowLog.clean(value)).append('\n') }
            append("GYROFLOW IMU LOG\n")
            line("version", "1.3")
            line("id", "opencinecam")
            metadata[OcrawImuMetadata.ORIENTATION]?.takeIf(OcrawImuMetadata::isValidOrientation)?.let { line("orientation", it) }
            line("note", if (synced) NOTE_SHARED else NOTE_ESTIMATED)
            metadata["appVersion"]?.takeIf { it.isNotEmpty() }?.let { line("fwversion", it) }
            metadata[OcrawImuMetadata.TAKE_START_UNIX_MS]?.let(::digits)?.let { line("timestamp", it.divide(MS_PER_SECOND).toString()) }
            line("vendor", "OpenCineCam")
            line("videofilename", videoFileName)
            val skew = metadata[OcrawImuMetadata.ROLLING_SHUTTER_SKEW_NS]?.let(::digits)
            if (skew != null && skew.signum() > 0) {
                val direction = metadata[OcrawImuMetadata.READOUT_DIRECTION]
                line("frame_readout_time", String.format(Locale.ROOT, "%.4f", skew.toDouble() / 1e6))
                line("frame_readout_direction", if (direction in READOUT_DIRECTIONS) direction!! else "0")
            }
            line("tscale", GyroflowLog.plain(GyroflowLog.TIME_SCALE))
            line("gscale", GyroflowLog.plain(GyroflowLog.GYRO_SCALE))
            if (accel) line("ascale", GyroflowLog.plain(GyroflowLog.ACCEL_SCALE))
            if (mag) line("mscale", GyroflowLog.plain(GyroflowLog.MAG_SCALE))
            append("t,gx,gy,gz").append(if (accel) ",ax,ay,az" else "").append(if (mag) ",mx,my,mz" else "").append('\n')
        }

    /** A non-empty run of ASCII digits as a number, else null (the desktop tool's `str.isdigit`). */
    private fun digits(value: String): BigInteger? =
        if (value.isNotEmpty() && value.all { it in '0'..'9' }) BigInteger(value) else null

    /** The take's `fps` as numerator and denominator, or null when missing, unreadable or not positive. */
    internal fun parseFps(text: String?): Pair<BigInteger, BigInteger>? {
        val value = text?.trim().orEmpty()
        if (value.isEmpty()) return null
        val fraction = try {
            if ('/' in value) {
                val (n, d) = value.split('/', limit = 2)
                BigInteger(n.trim()) to BigInteger(d.trim())
            } else {
                val decimal = BigDecimal(value)
                if (decimal.scale() > 0) decimal.unscaledValue() to BigInteger.TEN.pow(decimal.scale())
                else decimal.toBigIntegerExact() to BigInteger.ONE
            }
        } catch (_: NumberFormatException) {
            return null
        } catch (_: ArithmeticException) {
            return null
        }
        return fraction.takeIf { it.first.signum() > 0 && it.second.signum() > 0 }
    }

    private fun read(reader: OcrawStreamReader, chunk: OcrawImuChunk): OcrawImuSamplesData? = try {
        reader.readImu(chunk.fileOffset)
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    /** Chunk ticks to integer ns: exact for the usual 1 ns time base, floored otherwise. */
    private class TicksToNs(numerator: Long, denominator: Long) {
        private val num = BigInteger.valueOf(numerator).multiply(NS_PER_SECOND)
        private val den = BigInteger.valueOf(denominator)
        private val factor: Long? = num.divideAndRemainder(den).takeIf { it[1].signum() == 0 && it[0].bitLength() < 63 }?.get(0)?.toLong()

        operator fun invoke(ticks: Long): Long =
            factor?.let { Math.multiplyExact(ticks, it) } ?: BigInteger.valueOf(ticks).multiply(num).divide(den).toLong()
    }

    /** One sensor's samples across the take's chunks, in file order, decoding one chunk at a time. */
    private class KindStream(
        private val reader: OcrawStreamReader,
        private val chunks: List<OcrawImuChunk>,
        private val kind: Int,
        private val isInterrupted: () -> Boolean,
    ) {
        private var nextChunk = 0
        private var data: OcrawImuSamplesData? = null
        private var at = 0
        var timeNs = 0L
            private set
        var x = 0f
            private set
        var y = 0f
            private set
        var z = 0f
            private set

        fun next(): Boolean {
            while (true) {
                val current = data
                if (current != null) {
                    while (at < current.count) {
                        val i = at++
                        if (current.kind(i) != kind) continue
                        timeNs = current.timesNs[i]
                        x = current.xyz[i * 3]
                        y = current.xyz[i * 3 + 1]
                        z = current.xyz[i * 3 + 2]
                        return true
                    }
                }
                if (nextChunk >= chunks.size) {
                    data = null
                    return false
                }
                if (isInterrupted()) throw Interrupted()
                data = read(reader, chunks[nextChunk++])
                at = 0
            }
        }
    }

    /**
     * The sample of [stream] nearest each target time, for targets that only increase: the last sample before the
     * target and the first at or after it, the later one on a tie (`_nearest` in the desktop tool).
     */
    private class Nearest(private val stream: KindStream) {
        private var hasBefore = false
        private var beforeNs = 0L
        private var bx = 0f
        private var by = 0f
        private var bz = 0f
        private var hasNext = stream.next()
        var x = 0f
            private set
        var y = 0f
            private set
        var z = 0f
            private set

        fun at(targetNs: Long) {
            while (hasNext && stream.timeNs < targetNs) {
                hasBefore = true
                beforeNs = stream.timeNs
                bx = stream.x
                by = stream.y
                bz = stream.z
                hasNext = stream.next()
            }
            val useBefore = !hasNext || (hasBefore && targetNs - beforeNs < stream.timeNs - targetNs)
            if (useBefore) {
                x = bx; y = by; z = bz
            } else {
                x = stream.x; y = stream.y; z = stream.z
            }
        }
    }

    private val NS_PER_SECOND: BigInteger = BigInteger.valueOf(1_000_000_000L)
    private val MS_PER_SECOND: BigInteger = BigInteger.valueOf(1_000L)
    private val READOUT_DIRECTIONS = setOf("0", "1", "2", "3")
}
