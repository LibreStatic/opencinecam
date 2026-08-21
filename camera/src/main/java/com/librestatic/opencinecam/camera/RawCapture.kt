/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class RawFormat {
    RAW10,
    RAW12,
    RAW_SENSOR,
}

data class RawFrameMetadata(
    val width: Int,
    val height: Int,
    val rowStride: Int,
    val pixelStride: Int,
    val cfaPattern: String,
    val blackLevel: Int,
    val whiteLevel: Int,
    val timestampNs: Long,
    val format: RawFormat,
) {
    init {
        require(width > 0 && height > 0) { "RAW dimensions must be positive" }
        require(rowStride > 0) { "RAW row stride must be positive" }
        // Android reports a zero pixel stride for some packed RAW10/RAW12
        // Image planes because those formats do not expose a per-pixel byte
        // address. Preserve that value; RAW_SENSOR still requires two-byte
        // addressing and validates it in its unpacker.
        require(pixelStride >= 0) { "RAW pixel stride must not be negative" }
        require(cfaPattern.isNotBlank()) { "CFA pattern must be explicit" }
        require(blackLevel >= 0 && whiteLevel > blackLevel) { "RAW levels are invalid" }
        require(timestampNs >= 0) { "RAW timestamp must be monotonic" }
    }
}

data class RawFrame(
    val frameId: Long,
    val metadata: RawFrameMetadata,
    val payload: ByteArray,
) {
    init {
        require(frameId > 0) { "RAW frame ID must be positive" }
        require(payload.isNotEmpty()) { "RAW payload must not be empty" }
        require(payload.size >= metadata.rowStride * metadata.height) { "RAW payload is shorter than stride" }
    }
}

object RawUnpacker {
    fun unpack(frame: RawFrame): IntArray = when (frame.metadata.format) {
        RawFormat.RAW10 -> unpackRaw10(frame)
        RawFormat.RAW12 -> unpackRaw12(frame)
        RawFormat.RAW_SENSOR -> unpackSensor(frame)
    }

    private fun unpackRaw10(frame: RawFrame): IntArray {
        val metadata = frame.metadata
        val output = IntArray(metadata.width * metadata.height)
        var out = 0
        for (row in 0 until metadata.height) {
            val start = row * metadata.rowStride
            var x = 0
            while (x < metadata.width) {
                val group = start + (x / 4) * 5
                require(group + 4 < frame.payload.size) { "RAW10 row is truncated" }
                val low = frame.payload[group + 4].toInt() and 0xff
                repeat(4) { index ->
                    if (x + index < metadata.width) {
                        val high = frame.payload[group + index].toInt() and 0xff
                        output[out++] = (high shl 2) or ((low ushr (index * 2)) and 0x03)
                    }
                }
                x += 4
            }
        }
        return output
    }

    private fun unpackRaw12(frame: RawFrame): IntArray {
        val metadata = frame.metadata
        val output = IntArray(metadata.width * metadata.height)
        var out = 0
        for (row in 0 until metadata.height) {
            val start = row * metadata.rowStride
            var x = 0
            while (x < metadata.width) {
                val group = start + (x / 2) * 3
                require(group + 2 < frame.payload.size) { "RAW12 row is truncated" }
                val low = frame.payload[group + 2].toInt() and 0xff
                val first = (frame.payload[group].toInt() and 0xff shl 4) or (low and 0x0f)
                output[out++] = first
                if (x + 1 < metadata.width) {
                    val second = (frame.payload[group + 1].toInt() and 0xff shl 4) or (low ushr 4)
                    output[out++] = second
                }
                x += 2
            }
        }
        return output
    }

    private fun unpackSensor(frame: RawFrame): IntArray {
        val metadata = frame.metadata
        require(metadata.pixelStride >= 2) { "RAW_SENSOR requires a two-byte pixel stride" }
        val output = IntArray(metadata.width * metadata.height)
        var out = 0
        for (row in 0 until metadata.height) {
            val rowStart = row * metadata.rowStride
            for (x in 0 until metadata.width) {
                val offset = rowStart + x * metadata.pixelStride
                require(offset + 1 < frame.payload.size) { "RAW_SENSOR row is truncated" }
                output[out++] = (frame.payload[offset].toInt() and 0xff) or
                    ((frame.payload[offset + 1].toInt() and 0xff) shl 8)
            }
        }
        return output
    }
}

data class DngFixture(
    val tags: Map<String, String>,
    val payload: ByteArray,
) {
    init {
        require(tags["CFA"]?.isNotBlank() == true) { "DNG CFA tag is required" }
        require(tags["BlackLevel"]?.isNotBlank() == true) { "DNG black level tag is required" }
        require(tags["WhiteLevel"]?.isNotBlank() == true) { "DNG white level tag is required" }
        require(payload.isNotEmpty()) { "DNG payload must not be empty" }
    }
}

object RawStillDng {
    fun createFixture(frame: RawFrame): DngFixture {
        val pixels = RawUnpacker.unpack(frame)
        val payload = ByteBuffer.allocate(pixels.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            pixels.forEach { putShort(it.toShort()) }
        }.array()
        return DngFixture(
            tags = mapOf(
                "DNGVersion" to "1.4.0.0",
                "CFA" to frame.metadata.cfaPattern,
                "BlackLevel" to frame.metadata.blackLevel.toString(),
                "WhiteLevel" to frame.metadata.whiteLevel.toString(),
                "Width" to frame.metadata.width.toString(),
                "Height" to frame.metadata.height.toString(),
                "TimestampNs" to frame.metadata.timestampNs.toString(),
                "Format" to frame.metadata.format.name,
            ),
            payload = payload,
        )
    }

    fun compare(expected: DngFixture, actual: DngFixture): Boolean =
        expected.tags == actual.tags && expected.payload.contentEquals(actual.payload)
}

enum class RawRingStatus {
    ENQUEUED,
    DUPLICATE,
    CAPACITY_EXCEEDED,
    CLOSED,
}

class RawFrameRing(
    private val maxFrames: Int,
    private val maxBytes: Long,
) {
    private val frames = ArrayDeque<RawFrame>()
    private val ids = HashSet<Long>()
    private var bytes = 0L
    private var closed = false

    init {
        require(maxFrames > 0 && maxBytes > 0) { "RAW ring bounds must be positive" }
    }

    fun offer(frame: RawFrame): RawRingStatus {
        if (closed) return RawRingStatus.CLOSED
        if (ids.contains(frame.frameId)) return RawRingStatus.DUPLICATE
        if (frames.size >= maxFrames || bytes + frame.payload.size > maxBytes) {
            return RawRingStatus.CAPACITY_EXCEEDED
        }
        val owned = frame.copy(payload = frame.payload.copyOf())
        frames.addLast(owned)
        ids += frame.frameId
        bytes += frame.payload.size
        return RawRingStatus.ENQUEUED
    }

    fun poll(): RawFrame? {
        val frame = frames.removeFirstOrNull() ?: return null
        ids -= frame.frameId
        bytes -= frame.payload.size
        return frame
    }

    fun close() {
        frames.clear()
        ids.clear()
        bytes = 0
        closed = true
    }

    fun size(): Int = frames.size
    fun byteSize(): Long = bytes
}
