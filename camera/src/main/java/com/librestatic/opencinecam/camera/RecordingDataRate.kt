/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

/**
 * The encoder bitrate a take is configured with, in one place: the engine programs the encoders
 * with these values and the storage preflight projects the take's data rate from the same ones,
 * so the projection cannot drift from what is written.
 */
object RecordingDataRate {
    /** The frame rate the user-facing video bitrate is quoted at. */
    const val REFERENCE_FPS = 30

    /** MediaRecorder (direct constrained high-speed) path: scaled by the capture rate, clamped to 8-200 Mbps. */
    fun recorderVideoBps(videoBitrate: Int, targetFps: Int): Int =
        (videoBitrate.toLong() * targetFps / REFERENCE_FPS).coerceIn(8_000_000L, 200_000_000L).toInt()

    /** GPU path (SDR, LOG, HLG, time-lapse): scaled by the encoder rate, clamped to 12-200 Mbps. */
    fun gpuVideoBps(videoBitrate: Int, encoderRate: Int): Int =
        (videoBitrate.toLong() * encoderRate / REFERENCE_FPS).coerceIn(12_000_000L, 200_000_000L).toInt()

    /**
     * Time-lapse writes one frame per capture interval while the encoder is rated for the project
     * rate, so the file grows by that fraction of [gpuVideoBps]. The rate controller can overshoot
     * on sparse frames, so the estimate is the project-rate share rounded up and never below a
     * tenth of the nominal rate.
     */
    fun timelapseVideoBps(videoBitrate: Int, projectFps: Double, intervalMs: Long): Long {
        val nominal = gpuVideoBps(videoBitrate, kotlin.math.ceil(projectFps).toInt().coerceAtLeast(1)).toLong()
        val capturedFps = 1000.0 / intervalMs.coerceAtLeast(1L)
        val share = (capturedFps / projectFps.coerceAtLeast(1.0)).coerceIn(0.1, 1.0)
        return kotlin.math.ceil(nominal * share).toLong()
    }

    /** Whole bytes per minute of a stream of [videoBps] plus [audioBps]. */
    fun bytesPerMinute(videoBps: Long, audioBps: Long): Long = (videoBps.coerceAtLeast(0L) + audioBps.coerceAtLeast(0L)) / 8L * 60L
}
