/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.media.ocraw

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One `IMU_SAMPLES` chunk found by [OcrawStreamReader.indexFrames]: a batch of motion sensor
 * samples whose [timestampTicks] is the time of its first sample, in the RAW frame chunks' time base.
 */
data class OcrawImuChunk(
    val sequence: Long,
    val fileOffset: Long,
    val timestampTicks: Long,
    val payloadLength: Int,
)

/**
 * Decoded `IMU_SAMPLES` records as parallel primitive arrays, so a take's hour of 1 kHz samples
 * costs no object per sample. Record `i` is [kinds]`[i]`, [timesNs]`[i]` and
 * [xyz]`[3 i until 3 i + 3]`; only the first [count] entries are meaningful.
 */
class OcrawImuSamplesData(
    val count: Int,
    val kinds: ByteArray,
    val timesNs: LongArray,
    val xyz: FloatArray,
) {
    init {
        require(count >= 0 && kinds.size >= count && timesNs.size >= count && xyz.size >= count * 3) {
            "IMU arrays hold fewer than $count records"
        }
    }

    fun kind(index: Int): Int = kinds[index].toInt() and 0xff

    /** Records of [kind] among the first [count]. */
    fun countOf(kind: Int): Int {
        var n = 0
        for (i in 0 until count) if (kind(i) == kind) n++
        return n
    }
}

/**
 * Codec of the `IMU_SAMPLES` (`0x0050`) payload, see `docs/formats/opencine-raw-v1.md`.
 *
 * Little-endian: `u8 payloadVersion` (1), `u8` and `u16` reserved (0), `u32 recordCount`, then
 * `recordCount` records of 21 bytes: `u8 kind`, `s64 timeNs`, `f32 x, y, z`. Values are raw
 * Android `SensorEvent` values in the device frame; `timeNs` is on the frame ticks' clock.
 */
object OcrawImuSamples {
    const val PAYLOAD_VERSION = 1
    const val HEADER_BYTES = 8
    const val RECORD_BYTES = 21

    /** Gyroscope, rad/s. */
    const val KIND_GYROSCOPE = 1

    /** Accelerometer, m/s². */
    const val KIND_ACCELEROMETER = 2

    /** Magnetometer, µT. */
    const val KIND_MAGNETOMETER = 3

    /** Most records one chunk can hold under the 64 MiB payload bound. */
    const val MAX_RECORDS = (OcrawCodec.MAX_CHUNK_PAYLOAD - HEADER_BYTES) / RECORD_BYTES

    fun payloadBytes(count: Int): Int {
        require(count in 0..MAX_RECORDS) { "IMU batch of $count records exceeds the bounded payload" }
        return HEADER_BYTES + count * RECORD_BYTES
    }

    /**
     * Encodes records `[from, from + count)` of [kinds], [timesNs] and [xyz] (three floats per
     * record) as a fresh payload buffer, positioned at 0 with its limit at the end.
     */
    fun encode(kinds: ByteArray, timesNs: LongArray, xyz: FloatArray, count: Int = kinds.size, from: Int = 0): ByteBuffer {
        val buffer = ByteBuffer.allocate(payloadBytes(count)).order(ByteOrder.LITTLE_ENDIAN)
        encodeInto(buffer, kinds, timesNs, xyz, count, from)
        buffer.flip()
        return buffer
    }

    /**
     * Writes the payload for records `[from, from + count)` at `dst.position()`, advancing it, and
     * returns the bytes written; lets a writer thread reuse one buffer. [dst] keeps its byte order.
     */
    fun encodeInto(
        dst: ByteBuffer,
        kinds: ByteArray,
        timesNs: LongArray,
        xyz: FloatArray,
        count: Int = kinds.size,
        from: Int = 0,
    ): Int {
        val bytes = payloadBytes(count)
        require(from >= 0 && kinds.size >= from + count && timesNs.size >= from + count && xyz.size >= (from + count) * 3) {
            "IMU arrays hold fewer than ${from + count} records"
        }
        require(dst.remaining() >= bytes) { "destination holds ${dst.remaining()} of $bytes bytes" }
        val out = dst.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        out.put(PAYLOAD_VERSION.toByte())
        out.put(0)
        out.putShort(0)
        out.putInt(count)
        for (i in from until from + count) {
            out.put(kinds[i])
            out.putLong(timesNs[i])
            out.putFloat(xyz[i * 3])
            out.putFloat(xyz[i * 3 + 1])
            out.putFloat(xyz[i * 3 + 2])
        }
        dst.position(dst.position() + bytes)
        return bytes
    }

    /**
     * Decodes the payload between [payload]'s position and limit (not consumed). Returns null when
     * the payload version is not [PAYLOAD_VERSION] or the record count disagrees with its length,
     * so readers ignore such a chunk. Records of unknown kinds are skipped.
     */
    fun decode(payload: ByteBuffer): OcrawImuSamplesData? {
        val buffer = payload.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.remaining() < HEADER_BYTES) return null
        if (buffer.get().toInt() and 0xff != PAYLOAD_VERSION) return null
        buffer.get()
        buffer.short
        val count = buffer.int.toLong() and 0xffff_ffffL
        if (buffer.remaining().toLong() != count * RECORD_BYTES) return null
        val records = count.toInt()
        val kinds = ByteArray(records)
        val times = LongArray(records)
        val xyz = FloatArray(records * 3)
        var kept = 0
        repeat(records) {
            val kind = buffer.get()
            val time = buffer.long
            val x = buffer.float
            val y = buffer.float
            val z = buffer.float
            if (isKnownKind(kind.toInt() and 0xff)) {
                kinds[kept] = kind
                times[kept] = time
                xyz[kept * 3] = x
                xyz[kept * 3 + 1] = y
                xyz[kept * 3 + 2] = z
                kept++
            }
        }
        return OcrawImuSamplesData(kept, kinds, times, xyz)
    }

    fun decode(payload: ByteArray): OcrawImuSamplesData? = decode(ByteBuffer.wrap(payload))

    fun isKnownKind(kind: Int): Boolean = kind == KIND_GYROSCOPE || kind == KIND_ACCELEROMETER || kind == KIND_MAGNETOMETER
}

/**
 * Take `METADATA` keys that describe the IMU samples for stabilisation in post (Gyroflow). All
 * are optional text values; a later `METADATA` chunk may add them, a later value wins.
 */
object OcrawImuMetadata {
    /** Gyroflow orientation string: three letters from `XYZxyz`, one per axis, e.g. `yxz`. */
    const val ORIENTATION = "imuOrientation"

    /** Gyroflow `frame_readout_direction`: 0 top to bottom, 1 bottom to top, 2 left to right, 3 right to left. */
    const val READOUT_DIRECTION = "imuReadoutDirection"

    /** `SENSOR_ROLLING_SHUTTER_SKEW` in ns, integer; absent when the camera does not report it. */
    const val ROLLING_SHUTTER_SKEW_NS = "rollingShutterSkewNs"

    /** How sample times relate to the frame clock: [CLOCK_SHARED] or [CLOCK_ESTIMATED]. */
    const val CLOCK = "imuClock"

    /** The IMU and camera timestamps were measured to share one clock; no offset was applied. */
    const val CLOCK_SHARED = "shared"

    /** The IMU clock differs (or could not be checked) and the writer applied an estimated offset. */
    const val CLOCK_ESTIMATED = "estimated"

    /** Wall-clock time of the take start, integer milliseconds since the Unix epoch. */
    const val TAKE_START_UNIX_MS = "takeStartUnixMs"

    /** True for a Gyroflow orientation: each of x, y and z once, in either case. */
    fun isValidOrientation(value: String?): Boolean =
        value != null && value.length == 3 && value.lowercase().toSet() == setOf('x', 'y', 'z')
}
