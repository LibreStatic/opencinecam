/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.motion

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** One sensor's kept samples as arrays, sensor-clock times. */
internal class SampleArrays(val timeNs: LongArray, val x: FloatArray, val y: FloatArray, val z: FloatArray) {
    val size: Int get() = timeNs.size
}

/** A whole spool in memory, as the scan and the cursors give it. */
internal class ReadSpool(
    val spool: MotionSpool,
    val gyro: SampleArrays,
    val accel: SampleArrays,
    val mag: SampleArrays,
) {
    val frames: FrameTrack get() = spool.frames
    val exposures: ExposureTrack get() = spool.exposures
}

internal fun scanSpool(bytes: ByteArray): MotionSpool = MotionSpoolReader.scan { ByteArrayInputStream(bytes) }

internal fun spoolBytes(block: MotionSpoolWriter.() -> Unit): ByteArray {
    val bytes = ByteArrayOutputStream()
    MotionSpoolWriter(bytes).apply(block).close()
    return bytes.toByteArray()
}

internal fun MotionSpool.sampleArrays(kind: Int): SampleArrays =
    samples(kind).use { it.collect() }.let { SampleArrays(it.fileUs, it.x, it.y, it.z) }

internal fun readSpool(bytes: ByteArray): ReadSpool {
    val spool = scanSpool(bytes)
    return ReadSpool(
        spool,
        spool.sampleArrays(MotionSpoolFormat.KIND_GYRO),
        spool.sampleArrays(MotionSpoolFormat.KIND_ACCEL),
        spool.sampleArrays(MotionSpoolFormat.KIND_MAG),
    )
}
