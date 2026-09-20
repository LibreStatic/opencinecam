/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import java.util.Collections

/** One timestamp-correlated JPEG exposure; unknown sensor metadata remains unknown. */
data class BurstFrame(val index: Int, val capture: CapturedStill,
    val exposureTimeNs: Long?, val sensitivityIso: Int?) {
    init {
        require(index in 0 until CapturedBurst.MAX_FRAMES)
        require(capture.images.size == 1 && capture.images.single().kind == StillImageKind.JPEG)
        require(exposureTimeNs == null || exposureTimeNs > 0)
        require(sensitivityIso == null || sensitivityIso > 0)
    }
}

/** A bounded complete sequential burst, not a promised FPS or the cadence of Camera2 captureBurst. */
class CapturedBurst(val id: Long, val requestedCount: Int, frames: List<BurstFrame>,
    val quality: Int, val aspectSelection: PhotoAspectSelection) {
    val frames: List<BurstFrame> = Collections.unmodifiableList(frames.toList())
    init {
        require(id > 0 && requestedCount in MIN_FRAMES..MAX_FRAMES && quality in 1..100)
        require(this.frames.size == requestedCount && this.frames.map { it.index } == this.frames.indices.toList())
        require(this.frames.map { it.capture.captureId }.distinct().size == requestedCount)
        require(this.frames.zipWithNext().all { (a, b) -> a.capture.sensorTimestampNs < b.capture.sensorTimestampNs })
        require(this.frames.all { it.capture.quality == quality && it.capture.aspectSelection == aspectSelection })
        require(this.frames.map { it.capture.orientationDegrees }.distinct().size == 1)
        require(this.frames.map { it.capture.images.single().let { image -> image.width to image.height } }.distinct().size == 1)
        require(this.frames.sumOf { it.capture.images.single().byteCount.toLong() } <= MAX_ENCODED_BYTES)
    }
    companion object {
        const val MIN_FRAMES = 3
        const val MAX_FRAMES = 10
        const val MAX_ENCODED_BYTES = 32 * 1024 * 1024
    }
}
