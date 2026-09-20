/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import java.util.Collections

/** Actual sample presentation timestamps, not a frame-rate estimate. Decode order may differ. */
class VideoFrameTimeline(timestamps: Iterable<Long>) {
    val timestampsUs: List<Long>
    init {
        val values = ArrayList<Long>()
        for (timestamp in timestamps) {
            require(values.size < MAX_FRAMES) { "Precise frame index exceeds $MAX_FRAMES samples" }
            require(timestamp >= 0) { "Negative video presentation timestamp is unsupported" }
            values.add(timestamp)
        }
        require(values.isNotEmpty()) { "Video contains no indexed frames" }
        values.sort()
        for (index in 1 until values.size) require(values[index - 1] != values[index]) { "Duplicate video presentation timestamps are unsupported" }
        timestampsUs = Collections.unmodifiableList(values)
    }
    /** Floor, clamped to the first/last actual frame at the boundaries. */
    fun indexAt(timeUs: Long): Int {
        val result = timestampsUs.binarySearch(timeUs)
        return if (result >= 0) result else (-result - 2).coerceAtLeast(0)
    }
    fun step(index: Int, delta: Int): Int {
        require(index in timestampsUs.indices) { "Frame index is out of range" }
        return (index.toLong() + delta.toLong()).coerceIn(0, timestampsUs.lastIndex.toLong()).toInt()
    }
    companion object { const val MAX_FRAMES = 250_000 }
}
