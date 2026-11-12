// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 OpenCineCam contributors

package com.librestatic.opencinecam.media.ocraw

import android.annotation.SuppressLint
import com.librestatic.opencinecam.media.raw.RawCodecJni
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Checksum

/**
 * CRC32C (Castagnoli, reflected polynomial 0x82F63B78) for OpenCine RAW chunks.
 *
 * The native `libocc_rawcodec` (ARMv8 CRC32 instructions) is preferred when it
 * loads: on ART `java.util.zip.CRC32C` is plain Java, not intrinsified, and caps a
 * RAW writer far below storage speed. Without it, `java.util.zip.CRC32C` is used
 * when the runtime has it (Android API 34+ or a Java 9+ host JVM, where HotSpot
 * intrinsifies it). minSdk is 29, so the class is looked up once by name and
 * every other runtime falls back to a slicing-by-8 table that reads eight bytes
 * at a time. Every path yields the same values.
 *
 * Buffer overloads cover `position..limit` and never change the buffer's
 * position, limit or byte order. Accumulators are not thread-safe; give each
 * thread its own.
 */
object Crc32c {
    private const val POLY = 0x82f63b78.toInt()

    /** 8 x 256 table: entry `k * 256 + i` is the CRC of byte `i` followed by `k` zero bytes. */
    private val TABLE: IntArray = IntArray(8 * 256).also { table ->
        for (i in 0 until 256) {
            var crc = i
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor POLY else crc ushr 1 }
            table[i] = crc
        }
        for (k in 1 until 8) {
            for (i in 0 until 256) {
                val previous = table[(k - 1) * 256 + i]
                table[k * 256 + i] = (previous ushr 8) xor table[previous and 0xff]
            }
        }
    }

    private val platformConstructor: java.lang.reflect.Constructor<*>? = try {
        Class.forName("java.util.zip.CRC32C").getDeclaredConstructor()
    } catch (_: NoSuchMethodException) {
        null
    } catch (_: ClassNotFoundException) {
        null
    } catch (_: LinkageError) {
        null
    }

    /** True when `java.util.zip.CRC32C` exists on this runtime. */
    val platformAvailable: Boolean get() = platformConstructor != null

    /** True when the native library loaded, so [newAccumulator] uses it. */
    val nativeAvailable: Boolean get() = RawCodecJni.loaded

    /** True when the native path runs on the ARMv8 CRC32 instructions (not its table fallback). */
    val nativeHardware: Boolean get() = nativeAvailable && RawCodecJni.nativeCrc32cHardware()

    /** A fresh accumulator: native when the library loaded, else platform-backed when available. */
    fun newAccumulator(): Crc32cAccumulator = newAccumulator(preferPlatform = true)

    /**
     * A fresh accumulator. With [preferPlatform] false the table implementation
     * is always used; tests compare the paths this way. With it true the native
     * library wins when it loaded, then `java.util.zip.CRC32C`.
     */
    fun newAccumulator(preferPlatform: Boolean): Crc32cAccumulator {
        if (preferPlatform && nativeAvailable) return Crc32cAccumulator(platform = null, native = true)
        val platform = if (preferPlatform) newPlatformChecksum() else null
        return Crc32cAccumulator(platform)
    }

    /** A native accumulator, or null when the library is not loaded. */
    fun newNativeAccumulator(): Crc32cAccumulator? =
        if (nativeAvailable) Crc32cAccumulator(platform = null, native = true) else null

    /** A `java.util.zip.CRC32C` accumulator, or null when the runtime lacks it. */
    fun newPlatformAccumulator(): Crc32cAccumulator? = newPlatformChecksum()?.let { Crc32cAccumulator(it) }

    /** CRC32C of `buffer[position, limit)`. */
    fun compute(buffer: ByteBuffer): Int = newAccumulator().apply { update(buffer) }.value()

    /** CRC32C of `bytes[offset, offset + length)`. */
    fun compute(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Int =
        newAccumulator().apply { update(bytes, offset, length) }.value()

    private fun newPlatformChecksum(): Checksum? = try {
        platformConstructor?.newInstance() as Checksum?
    } catch (_: ReflectiveOperationException) {
        null
    }

    /**
     * Advances the raw (pre-inversion) table state over `buffer[from, to)` with
     * absolute reads, so the buffer is left untouched.
     */
    internal fun updateTable(state: Int, buffer: ByteBuffer, from: Int, to: Int): Int {
        val t = TABLE
        var crc = state
        var index = from
        val bigEndian = buffer.order() == ByteOrder.BIG_ENDIAN
        val wordEnd = to - 7
        while (index < wordEnd) {
            var word = buffer.getLong(index)
            if (bigEndian) word = java.lang.Long.reverseBytes(word)
            val low = crc xor word.toInt()
            val high = (word ushr 32).toInt()
            crc = t[7 * 256 + (low and 0xff)] xor
                t[6 * 256 + ((low ushr 8) and 0xff)] xor
                t[5 * 256 + ((low ushr 16) and 0xff)] xor
                t[4 * 256 + (low ushr 24)] xor
                t[3 * 256 + (high and 0xff)] xor
                t[2 * 256 + ((high ushr 8) and 0xff)] xor
                t[256 + ((high ushr 16) and 0xff)] xor
                t[high ushr 24]
            index += 8
        }
        while (index < to) {
            crc = (crc ushr 8) xor t[(crc xor buffer.get(index).toInt()) and 0xff]
            index++
        }
        return crc
    }
}

/**
 * Incremental CRC32C. [update] calls append; [value] reads the CRC so far
 * without ending the computation; [reset] starts over without allocating.
 */
class Crc32cAccumulator internal constructor(
    private val platform: Checksum?,
    private val native: Boolean = false,
) {
    private var state = -1

    /** True when backed by `java.util.zip.CRC32C`. */
    val usesPlatform: Boolean get() = platform != null

    /** True when backed by the native library. */
    val usesNative: Boolean get() = native

    /**
     * Native update of `buffer[from, from + length)` (absolute); false when the
     * range is too short to be worth a JNI call or the buffer has no native
     * route, so the caller uses the table.
     */
    private fun updateNative(buffer: ByteBuffer, from: Int, length: Int): Boolean {
        if (length < NATIVE_MIN_BYTES) return false
        if (buffer.isDirect) {
            state = RawCodecJni.nativeCrc32cUpdate(state.inv(), buffer, from, length).inv()
            return true
        }
        if (buffer.hasArray()) {
            state = RawCodecJni.nativeCrc32cUpdateArray(state.inv(), buffer.array(), buffer.arrayOffset() + from, length).inv()
            return true
        }
        return false
    }

    fun reset() {
        if (platform != null) platform.reset() else state = -1
    }

    /** Appends `buffer[position, limit)`; the buffer's position is restored. */
    @SuppressLint("NewApi") // Checksum.update(ByteBuffer) only runs when CRC32C exists, i.e. API 34+.
    fun update(buffer: ByteBuffer) {
        val position = buffer.position()
        val limit = buffer.limit()
        if (position == limit) return
        if (native) {
            if (!updateNative(buffer, position, limit - position)) {
                state = Crc32c.updateTable(state, buffer, position, limit)
            }
        } else if (platform != null) {
            try {
                platform.update(buffer)
            } finally {
                buffer.position(position)
            }
        } else {
            state = Crc32c.updateTable(state, buffer, position, limit)
        }
    }

    /** Appends `buffer[offset, offset + length)` (absolute); position and limit are restored. */
    fun update(buffer: ByteBuffer, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= buffer.capacity() - length) { "range out of bounds" }
        if (length == 0) return
        if (platform == null) {
            if (!native || !updateNative(buffer, offset, length)) {
                state = Crc32c.updateTable(state, buffer, offset, offset + length)
            }
            return
        }
        val position = buffer.position()
        val limit = buffer.limit()
        try {
            buffer.limit(offset + length)
            buffer.position(offset)
            update(buffer)
        } finally {
            buffer.limit(limit)
            buffer.position(position)
        }
    }

    /** Appends `bytes[offset, offset + length)`. */
    fun update(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length) { "range out of bounds" }
        if (length == 0) return
        if (native && length >= NATIVE_MIN_BYTES) {
            state = RawCodecJni.nativeCrc32cUpdateArray(state.inv(), bytes, offset, length).inv()
        } else if (platform != null) {
            platform.update(bytes, offset, length)
        } else {
            state = Crc32c.updateTable(state, ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN), offset, offset + length)
        }
    }

    /** CRC32C of everything appended since creation or the last [reset]. */
    fun value(): Int = platform?.value?.toInt() ?: state.inv()

    private companion object {
        /** Below this, the table beats the JNI transition. */
        const val NATIVE_MIN_BYTES = 256
    }
}
