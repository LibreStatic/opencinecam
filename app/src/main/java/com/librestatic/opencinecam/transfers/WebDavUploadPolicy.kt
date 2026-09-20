/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.transfers

import java.net.HttpURLConnection
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

enum class WebDavNetwork { WIFI, CELLULAR, OTHER, OFFLINE }

/** No network operation is eligible until the user explicitly enables transfers. */
data class WebDavUploadPolicy(
    val enabled: Boolean = false,
    val allowCellular: Boolean = false,
    val recording: Boolean = false,
    val network: WebDavNetwork = WebDavNetwork.OFFLINE,
) {
    fun stopReason(): WebDavStopReason? = when {
        !enabled -> WebDavStopReason.DISABLED
        recording -> WebDavStopReason.RECORDING
        network == WebDavNetwork.CELLULAR && !allowCellular -> WebDavStopReason.CELLULAR_CONSENT_REQUIRED
        network != WebDavNetwork.WIFI && network != WebDavNetwork.CELLULAR -> WebDavStopReason.NETWORK_UNAVAILABLE
        else -> null
    }
}

enum class WebDavStopReason { DISABLED, RECORDING, CELLULAR_CONSENT_REQUIRED, NETWORK_UNAVAILABLE, CANCELLED, BUSY }

/**
 * Proof that the captured attempt has left its transport finally AND all control-owned disconnects
 * have returned. A timeout/interruption is not retirement. This is not a REC permission: the owner
 * must also keep recording exclusion in force; later policy changes may admit a different attempt.
 */
class WebDavRetirementReceipt internal constructor() {
    private val retired = CountDownLatch(1)
    val isRetired: Boolean get() = retired.count == 0L

    @Throws(InterruptedException::class)
    fun awaitRetired(timeout: Long, unit: TimeUnit): Boolean {
        require(timeout >= 0)
        return retired.await(timeout, unit)
    }

    internal fun complete() { retired.countDown() }
}

/**
 * One transfer at a time, including cancellation cleanup. Admission and the first stop reason
 * linearize under the same control lock; lock order is control -> attempt. No blocking I/O or
 * retirement waiting occurs under either lock. A stopped attempt never resumes.
 *
 * updatePolicy/cancel/pauseForRecording may disconnect synchronously and belong off the UI thread.
 * Their receipts are attempt-specific; callers must keep REC exclusion active while using them.
 */
class WebDavUploadControl(initialPolicy: WebDavUploadPolicy = WebDavUploadPolicy()) {
    private var policy = initialPolicy
    private var active: Attempt? = null

    fun updatePolicy(value: WebDavUploadPolicy): WebDavRetirementReceipt {
        val stop = synchronized(this) {
            policy = value
            stopLocked(value.stopReason())
        }
        return disconnectOutsideLock(stop)
    }

    /** Dispatcher must enqueue cleanup without running it on the caller. Rejection never retires it. */
    internal fun updatePolicyAsync(value: WebDavUploadPolicy, dispatchCleanup: (() -> Unit) -> Unit): WebDavRetirementReceipt {
        val stop = synchronized(this) { policy = value; stopLocked(value.stopReason()) }
        if (stop.connection != null) dispatchCleanup { disconnectOutsideLock(stop) }
        return stop.receipt
    }

    internal fun cancelAsync(dispatchCleanup: (() -> Unit) -> Unit): WebDavRetirementReceipt {
        val stop = synchronized(this) { stopLocked(WebDavStopReason.CANCELLED) }
        if (stop.connection != null) dispatchCleanup { disconnectOutsideLock(stop) }
        return stop.receipt
    }

    /** Preserve the latest consent/network snapshot while atomically excluding new transfers. */
    fun pauseForRecording(): WebDavRetirementReceipt {
        val stop = synchronized(this) {
            policy = policy.copy(recording = true)
            stopLocked(policy.stopReason())
        }
        return disconnectOutsideLock(stop)
    }

    /** Cancel only the owner captured at this call's linearization point, not a later attempt. */
    fun cancel(): WebDavRetirementReceipt {
        val stop = synchronized(this) { stopLocked(WebDavStopReason.CANCELLED) }
        return disconnectOutsideLock(stop)
    }

    private data class Stop(val attempt: Attempt?, val connection: HttpURLConnection?, val receipt: WebDavRetirementReceipt)

    // Caller holds control lock. latchStop reserves cleanup before another thread can call leave.
    private fun stopLocked(reason: WebDavStopReason?): Stop {
        val attempt = active
        return Stop(attempt, reason?.let { attempt?.latchStop(it) },
            attempt?.retirement ?: WebDavRetirementReceipt().also { it.complete() })
    }

    private fun disconnectOutsideLock(stop: Stop): WebDavRetirementReceipt {
        if (stop.connection != null) {
            try {
                stop.connection.disconnectQuietly()
            } finally {
                synchronized(this) {
                    val attempt = requireNotNull(stop.attempt)
                    attempt.disconnectFinished()
                    retireIfFinished(attempt)
                }
            }
        }
        return stop.receipt
    }

    @Synchronized
    internal fun enter(): Admission {
        policy.stopReason()?.let { return Admission(null, it, policy) }
        if (active != null) return Admission(null, WebDavStopReason.BUSY, policy)
        return Attempt().also { active = it }.let { Admission(it, null, policy) }
    }

    /** Called only by transport finally, after source/socket cleanup; never by cancellation. */
    @Synchronized
    internal fun leave(attempt: Attempt) {
        if (active !== attempt) return
        attempt.transportLeft()
        retireIfFinished(attempt)
    }

    private fun retireIfFinished(attempt: Attempt) {
        if (active === attempt && attempt.readyToRetire()) {
            active = null
            attempt.retirement.complete()
        }
    }

    internal data class Admission(val attempt: Attempt?, val reason: WebDavStopReason?, val policy: WebDavUploadPolicy)

    internal class Attempt {
        val retirement = WebDavRetirementReceipt()
        @Volatile var reason: WebDavStopReason? = null
            private set
        private var connection: HttpURLConnection? = null
        private var left = false
        private var disconnectPending = false

        /** No I/O; called with control lock held. First reason and cleanup reservation are final. */
        @Synchronized fun latchStop(value: WebDavStopReason): HttpURLConnection? {
            if (reason != null) return null
            reason = value
            return connection.also { disconnectPending = it != null }
        }

        @Synchronized fun disconnectFinished() {
            check(disconnectPending)
            disconnectPending = false
        }

        @Synchronized fun transportLeft() { left = true }
        @Synchronized fun readyToRetire(): Boolean = left && !disconnectPending

        fun bind(value: HttpURLConnection) {
            synchronized(this) {
                checkRunning()
                check(!left && connection == null)
                connection = value
            }
            checkRunning()
        }

        @Synchronized fun unbind() { connection = null }

        @Synchronized fun checkRunning() {
            reason?.let { throw UploadStopped() }
            check(!left) { "Operation owner has retired" }
        }
    }

    internal class UploadStopped : RuntimeException()
}

/** Cleanup must not replace the acknowledged transfer outcome or strand single-flight admission. */
internal fun HttpURLConnection.disconnectQuietly() {
    try {
        disconnect()
    } catch (_: RuntimeException) {
        // The request/attempt already owns its outcome. Never surface credential-bearing errors.
    }
}
