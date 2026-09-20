/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

/** CONTINUOUS preserves live WB; LOCK_ON_RECORD holds the selected WB until finalization. */
enum class RecordingWhiteBalancePolicy { CONTINUOUS, LOCK_ON_RECORD }
enum class RecordingWhiteBalanceStatus { IDLE, CONVERGING, LOCKING, LOCKED, FIXED, FAILED }

data class RecordingWhiteBalanceResult(
    val status: RecordingWhiteBalanceStatus,
    val minimumSensorTimestampNs: Long? = null,
    val failure: String? = null,
) {
    val ready: Boolean get() = status == RecordingWhiteBalanceStatus.LOCKED || status == RecordingWhiteBalanceStatus.FIXED
}

/** Camera-executor confined; stale generations and unconfirmed requests never release the gate. */
class RecordingWhiteBalanceGate(private val timeoutMs: Long = 3_000L) {
    init { require(timeoutMs > 0) }
    private var generation = 0L
    private var startedAtMs = 0L
    private var convergedSensorTimestampNs: Long? = null
    var status = RecordingWhiteBalanceStatus.IDLE
        private set
    var minimumSensorTimestampNs: Long? = null
        private set
    val lockRequested: Boolean get() = status == RecordingWhiteBalanceStatus.LOCKING || status == RecordingWhiteBalanceStatus.LOCKED

    fun begin(nowMs: Long): Long {
        require(nowMs >= 0)
        generation++
        startedAtMs = nowMs
        minimumSensorTimestampNs = null
        convergedSensorTimestampNs = null
        status = RecordingWhiteBalanceStatus.CONVERGING
        return generation
    }

    fun observe(token: Long, nowMs: Long, autoRequested: Boolean, autoReported: Boolean,
        lockRequested: Boolean, lockReported: Boolean?, awbState: Int?, timestampNs: Long?): RecordingWhiteBalanceStatus {
        if (token != generation || status !in setOf(RecordingWhiteBalanceStatus.CONVERGING, RecordingWhiteBalanceStatus.LOCKING)) return status
        if (expire(token, nowMs)) return status
        if (!autoRequested || !autoReported) return status
        // Camera2 states: CONVERGED=2, LOCKED=3. A submitted lock alone is not evidence.
        if (status == RecordingWhiteBalanceStatus.CONVERGING && !lockRequested && lockReported == false && awbState == 2 && timestampNs != null && timestampNs > 0) {
            convergedSensorTimestampNs = timestampNs
            status = RecordingWhiteBalanceStatus.LOCKING
        } else if (status == RecordingWhiteBalanceStatus.LOCKING && lockRequested && lockReported == true && awbState == 3 && timestampNs != null && timestampNs > (convergedSensorTimestampNs ?: Long.MAX_VALUE)) {
            minimumSensorTimestampNs = timestampNs
            status = RecordingWhiteBalanceStatus.LOCKED
        }
        return status
    }

    fun observeFixed(token: Long, nowMs: Long, submittedAndReportedMatch: Boolean, timestampNs: Long?): RecordingWhiteBalanceStatus {
        if (token != generation || status != RecordingWhiteBalanceStatus.CONVERGING) return status
        if (expire(token, nowMs)) return status
        if (submittedAndReportedMatch && timestampNs != null && timestampNs > 0) {
            status = RecordingWhiteBalanceStatus.FIXED
            minimumSensorTimestampNs = timestampNs
        }
        return status
    }

    fun expire(token: Long, nowMs: Long): Boolean {
        if (token != generation || status !in setOf(RecordingWhiteBalanceStatus.CONVERGING, RecordingWhiteBalanceStatus.LOCKING)) return false
        if (nowMs < startedAtMs || nowMs - startedAtMs >= timeoutMs) {
            status = RecordingWhiteBalanceStatus.FAILED
            return true
        }
        return false
    }

    fun reset() {
        generation++
        status = RecordingWhiteBalanceStatus.IDLE
        minimumSensorTimestampNs = null
        convergedSensorTimestampNs = null
    }
}

/** SurfaceTexture and CaptureResult share SENSOR_TIMESTAMP; reject queued pre-lock frames. */
fun recordingFrameMeetsWhiteBalanceBoundary(timestampNs: Long, minimumSensorTimestampNs: Long?): Boolean =
    minimumSensorTimestampNs == null || (timestampNs > 0 && timestampNs >= minimumSensorTimestampNs)
