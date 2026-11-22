/* SPDX-License-Identifier: Apache-2.0 */

package com.librestatic.opencinecam.rawvideo

import android.content.Context
import com.librestatic.opencinecam.motion.MotionSampleSource
import com.librestatic.opencinecam.storage.GyroflowLog

/**
 * [RawMotionSource] over the phone's motion sensors ([MotionSampleSource]). RAW frames are stored as the sensor
 * raster, unrotated (the take's `orientation` key says how to show them), so the Gyroflow orientation and readout
 * direction are those of a stored rotation of 0.
 */
internal class SensorRawMotionSource private constructor(
    private val context: Context,
    override val clockOffsetNs: Long,
    override val metadata: Map<String, String>,
) : RawMotionSource {
    @Volatile private var source: MotionSampleSource? = null

    override fun start(sink: RawMotionSink): Boolean {
        val started = MotionSampleSource.start(context) { kind, timestampNs, x, y, z -> sink.onSample(kind, timestampNs, x, y, z) }
        source = started
        return started != null
    }

    override fun stop() {
        runCatching { source?.stop() }
    }

    companion object {
        /** Measures the clock offset now, so call it right before the take starts. */
        fun create(context: Context, sensorOrientationDegrees: Int, frontFacing: Boolean, sensorRealtime: Boolean): SensorRawMotionSource {
            // An UNKNOWN timestamp source is CLOCK_MONOTONIC in practice; SensorEvent times are boot time.
            val offset = if (sensorRealtime) 0L else RawAudioClock.measure(false).boottimeMinusMonotonicNs
            val metadata = RawImuBuffer.takeMetadata(
                orientation = GyroflowLog.orientation(sensorOrientationDegrees, frontFacing, storedPixelRotationDegrees = 0),
                readoutDirection = GyroflowLog.readoutDirection(0),
                sensorRealtime = sensorRealtime,
                takeStartUnixMs = System.currentTimeMillis(),
            )
            return SensorRawMotionSource(context.applicationContext, offset, metadata)
        }
    }
}
