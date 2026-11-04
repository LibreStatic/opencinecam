// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 OpenCineCam contributors

package com.librestatic.opencinecam.media.raw

import java.nio.ByteBuffer

/**
 * The JNI surface of `libocc_rawcodec.so` (`media/src/main/cpp`). The library
 * loads on first use of [loaded]; host JVM tests and Robolectric have no such
 * library, so [loaded] is false there and nothing below may be called.
 * [NativeRawCodec] and `Crc32c` are the public entry points.
 */
internal object RawCodecJni {
    val loaded: Boolean by lazy {
        try {
            System.loadLibrary("occ_rawcodec")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    @JvmStatic external fun nativeNeonAvailable(): Boolean

    @JvmStatic external fun nativeCrc32cHardware(): Boolean

    @JvmStatic external fun nativeCreatePool(threads: Int): Long

    @JvmStatic external fun nativeDestroyPool(pool: Long)

    @JvmStatic external fun nativeMaxEncodedSize(
        codec: Int,
        width: Int,
        height: Int,
        strips: Int,
        predictor: Int,
        optimal: Boolean,
    ): Long

    @JvmStatic external fun nativeEncode(
        pool: Long,
        codec: Int,
        src: ByteBuffer,
        srcOffset: Int,
        width: Int,
        height: Int,
        rowBytes: Int,
        dst: ByteBuffer,
        dstOffset: Int,
        dstCapacity: Int,
        strips: Int,
        predictor: Int,
        optimal: Boolean,
        kernel: Int,
        /** Encoding 3 only: forward table then payload prefix, see `occ_rawcodec_jni.cpp`. */
        quant: ByteBuffer?,
    ): Int

    @JvmStatic external fun nativeDecode(
        pool: Long,
        codec: Int,
        src: ByteBuffer,
        srcOffset: Int,
        srcLength: Int,
        width: Int,
        height: Int,
        dst: ByteBuffer,
        dstOffset: Int,
        dstRowBytes: Int,
    ): Boolean

    /** [crc] is the finished CRC so far (0 to start); returns the updated CRC. */
    @JvmStatic external fun nativeCrc32cUpdate(crc: Int, buffer: ByteBuffer, offset: Int, length: Int): Int

    @JvmStatic external fun nativeCrc32cUpdateArray(crc: Int, bytes: ByteArray, offset: Int, length: Int): Int
}
