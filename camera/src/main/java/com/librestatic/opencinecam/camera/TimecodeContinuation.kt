/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

/** Completed-take positions only: no claim of recovery of frames from a process killed during REC. */
data class TimecodeContinuation(val config: TimecodeConfig, val recordRunCursor: Long, val regenNext: Long?) {
    init {
        config.startValue.toTotalFrames(config.rate)
        require(config.resetRevision >= 0)
        require(recordRunCursor in 0 until config.rate.framesPerDay)
        require(regenNext == null || regenNext in 0 until config.rate.framesPerDay)
    }
    fun matches(other: TimecodeConfig): Boolean = config.copy(enabled = other.enabled) == other
}

interface TimecodeContinuationStore {
    fun load(): TimecodeContinuation?
    fun save(value: TimecodeContinuation)
    fun clear()
}
