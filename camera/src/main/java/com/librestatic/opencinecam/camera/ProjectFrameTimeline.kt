/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

/** Constant project cadence for off-speed video; never retimes audio implicitly. */
class ProjectFrameTimeline(val rate: CaptureFrameRate) {
    init { require(rate.numerator.toLong() in rate.denominator.toLong()..120L * rate.denominator) }
    private var lastSourceNs: Long? = null
    var selectedFrames = 0L
        private set
    fun select(sourceTimestampNs: Long): Long? {
        if (sourceTimestampNs <= 0 || lastSourceNs?.let { sourceTimestampNs <= it } == true) return null
        lastSourceNs = sourceTimestampNs
        return projectFrameTimestampNs(selectedFrames++, rate)
    }
}
