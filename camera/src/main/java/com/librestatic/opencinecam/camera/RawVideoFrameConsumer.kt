/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.TotalCaptureResult
import android.media.Image

/**
 * OCC-PLAN-071: the RAW_VIDEO session's RAW10 stream. The engine owns the camera
 * and the ImageReader; the recorder (in `:app`, writing through `:media`) owns
 * the ring and the file. Between takes the engine closes every RAW image itself.
 */
data class RawVideoSessionConfig(
    val rawWidth: Int,
    val rawHeight: Int,
    val fps: Int,
) {
    init {
        require(rawWidth > 0 && rawHeight > 0 && fps > 0) { "RAW video session values must be positive" }
    }
}

/** Receives a take's RAW frames and their results; called on the engine's RAW reader thread. */
interface RawVideoFrameConsumer {
    /**
     * Static camera data for the take's metadata chunk, delivered once before
     * the first frame.
     */
    fun onRawTakeStarted(cameraId: String, characteristics: CameraCharacteristics, config: RawVideoSessionConfig)

    /**
     * One RAW10 image. Copy what is needed and return at once; the engine closes
     * [image] after the call. Returning false means the consumer has no room
     * (PLAN-071 never drops a frame): the engine ends the take.
     */
    fun onRawFrame(image: Image): Boolean

    /** The capture result for a frame, matched by `SENSOR_TIMESTAMP`; may arrive before or after it. */
    fun onRawResult(result: TotalCaptureResult)

    /** The RAW stream failed or the session was lost; the take must end with [reason]. */
    fun onRawStreamFailed(reason: String)
}
