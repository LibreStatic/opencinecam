/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import com.librestatic.opencinecam.media.mux.DescriptorMp4Bytes
import com.librestatic.opencinecam.media.mux.Mp4Bytes
import com.librestatic.opencinecam.media.mux.Mp4CammAppender
import com.librestatic.opencinecam.media.mux.Mp4VideoTiming
import com.librestatic.opencinecam.media.mux.readMp4VideoTiming
import com.librestatic.opencinecam.motion.CammSeries
import com.librestatic.opencinecam.motion.MappedSeries
import java.io.FileDescriptor

/** Receives CAMM packets in file order. */
internal interface CammPacketSink {
    fun vector(timeUs: Long, type: Int, a: Float, b: Float, c: Float)
    fun exposure(timeUs: Long, exposureNs: Int, skewNs: Int)
}

/**
 * Hands every packet of [series] to [sink] in non-decreasing time, as a CAMM track needs across all its types. Ties go
 * exposure, gyroscope, accelerometer, magnetometer; a time before the movie starts is left out. Returns the packets.
 */
internal fun mergeCammPackets(series: CammSeries, sink: CammPacketSink): Int {
    val vectors = arrayOf(series.gyro, series.accel, series.mag)
    val types = intArrayOf(Mp4CammAppender.TYPE_GYRO, Mp4CammAppender.TYPE_ACCELERATION, Mp4CammAppender.TYPE_MAGNETIC_FIELD)
    val next = IntArray(vectors.size)
    var exposure = 0
    var written = 0
    while (true) {
        var best = -1
        var bestUs = Long.MAX_VALUE
        if (exposure < series.exposureFileUs.size) {
            bestUs = series.exposureFileUs[exposure]
        }
        for (i in vectors.indices) {
            val s: MappedSeries = vectors[i]
            if (next[i] < s.fileUs.size && s.fileUs[next[i]] < bestUs) {
                best = i
                bestUs = s.fileUs[next[i]]
            }
        }
        if (best < 0) {
            if (exposure >= series.exposureFileUs.size) return written
            if (bestUs >= 0) {
                sink.exposure(bestUs, series.exposureNs[exposure], series.skewNs[exposure])
                written++
            }
            exposure++
        } else {
            val s = vectors[best]
            val j = next[best]
            if (bestUs >= 0) {
                sink.vector(bestUs, types[best], s.x[j], s.y[j], s.z[j])
                written++
            }
            next[best] = j + 1
        }
    }
}

/**
 * Appends [series] as a CAMM track to the finished MP4 [file] in place. Returns the packets written, 0 when there are
 * none or the appender ran out of memory (the file is then as it was read). Throws when the file cannot take the track;
 * the file is then as it was read.
 */
internal fun appendCamm(file: Mp4Bytes, series: CammSeries): Int {
    if (series.gyro.fileUs.isEmpty() && series.accel.fileUs.isEmpty() && series.mag.fileUs.isEmpty() &&
        series.exposureFileUs.isEmpty()
    ) return 0
    var appender: Mp4CammAppender? = null
    try {
        // Inside the guard: begin reads the movie into memory, which can run out of it as well.
        val opened = Mp4CammAppender.begin(file).also { appender = it }
        val packets = mergeCammPackets(series, object : CammPacketSink {
            override fun vector(timeUs: Long, type: Int, a: Float, b: Float, c: Float) = opened.writePacket(timeUs, type, a, b, c)
            override fun exposure(timeUs: Long, exposureNs: Int, skewNs: Int) = opened.writeExposure(timeUs, exposureNs, skewNs)
        })
        return if (packets == 0) 0 else opened.finish()
    } catch (problem: OutOfMemoryError) {
        // The packet tables and the new moov are built in memory; MotionCammBudget keeps them small, and this is the
        // last line: the take finalises without the track rather than taking the service down.
        // Guarded so the function stays usable on a plain JVM, where android.util.Log is not implemented.
        runCatching { android.util.Log.w("MotionData", "CAMM track not added: out of memory", problem) }
        return 0
    } finally {
        appender?.close()
    }
}

/** [appendCamm] on the take's open read-write descriptor. */
internal fun appendCammTrack(descriptor: FileDescriptor, series: CammSeries): Int = appendCamm(DescriptorMp4Bytes(descriptor), series)

/** The video track timing of the take's finished file. Throws when it cannot be read. */
internal fun readTakeVideoTiming(descriptor: FileDescriptor): Mp4VideoTiming = readMp4VideoTiming(DescriptorMp4Bytes(descriptor))
