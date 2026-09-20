/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

/** Mandatory container stages, in order; release always runs, retiming never follows a failure. */
fun finalizeEncodedRecording(
    ready: Boolean,
    initialFailure: Throwable?,
    muxerStarted: Boolean,
    stopMuxer: () -> Unit,
    releaseMuxer: () -> Unit,
    finalizeTimeline: (() -> Unit)? = null,
): Throwable? {
    var failure = initialFailure ?: if (ready) null else IllegalStateException("Encoder completion prerequisites were not met")
    fun attempt(operation: () -> Unit) {
        try { operation() } catch (next: Throwable) {
            val first = failure
            if (first == null) failure = next else if (first !== next) first.addSuppressed(next)
        }
    }
    if (muxerStarted) attempt(stopMuxer)
    attempt(releaseMuxer)
    if (failure == null) finalizeTimeline?.let(::attempt)
    return failure
}
