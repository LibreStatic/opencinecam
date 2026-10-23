/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera.calibration

/**
 * One calibration readback frame joined with the capture result of the same sensor timestamp.
 * [aeState] and [awbState] are the raw `CONTROL_AE_STATE` / `CONTROL_AWB_STATE` values,
 * [awbGains] the `COLOR_CORRECTION_GAINS` (R, Geven, Godd, B).
 */
class CalibrationSample(
    val frame: RgbaFrame,
    val timestampNs: Long,
    val physicalId: String?,
    val aeState: Int?,
    val awbState: Int?,
    val zoomRatio: Float?,
    val exposureNs: Long?,
    val iso: Int?,
    val awbGains: FloatArray?,
)

/** The per-frame capture metadata the joiner pairs with a readback frame. */
class CalibrationFrameMeta(
    val timestampNs: Long,
    val physicalId: String?,
    val aeState: Int?,
    val awbState: Int?,
    val zoomRatio: Float?,
    val exposureNs: Long?,
    val iso: Int?,
    val awbGains: FloatArray?,
)

/**
 * Pairs readback frames (from the GL thread) with capture results (from the camera thread) by
 * sensor timestamp. Either side may arrive first; unmatched entries are dropped once more than
 * [capacity] are waiting. A frame matches the metadata within [toleranceNs] (the GL timestamp is
 * the sensor timestamp, so normally they are equal). Not thread-safe: call from one thread.
 */
class CalibrationFrameJoiner(
    private val toleranceNs: Long = 2_000_000L,
    private val capacity: Int = 32,
    private val emit: (CalibrationSample) -> Unit,
) {
    private val frames = ArrayDeque<Pair<Long, RgbaFrame>>()
    private val metas = ArrayDeque<CalibrationFrameMeta>()

    fun offerFrame(timestampNs: Long, frame: RgbaFrame) {
        val meta = metas.firstOrNull { kotlin.math.abs(it.timestampNs - timestampNs) <= toleranceNs }
        if (meta != null) {
            metas.remove(meta)
            emit(sample(frame, timestampNs, meta))
            return
        }
        frames.addLast(timestampNs to frame)
        while (frames.size > capacity) frames.removeFirst()
    }

    fun offerMeta(meta: CalibrationFrameMeta) {
        val waiting = frames.firstOrNull { kotlin.math.abs(it.first - meta.timestampNs) <= toleranceNs }
        if (waiting != null) {
            frames.remove(waiting)
            emit(sample(waiting.second, waiting.first, meta))
            return
        }
        metas.addLast(meta)
        while (metas.size > capacity) metas.removeFirst()
    }

    fun clear() { frames.clear(); metas.clear() }

    private fun sample(frame: RgbaFrame, timestampNs: Long, m: CalibrationFrameMeta) = CalibrationSample(
        frame, timestampNs, m.physicalId, m.aeState, m.awbState, m.zoomRatio, m.exposureNs, m.iso, m.awbGains,
    )
}
