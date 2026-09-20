/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import java.util.concurrent.CompletableFuture

/** One terminal decision; caller timeout cannot revoke an already committed native owner. */
internal class RecordingPreparation {
    private val callerDone = java.util.concurrent.atomic.AtomicBoolean()
    private val ownerDone = java.util.concurrent.atomic.AtomicBoolean()
    private val decision = CompletableFuture<Boolean>()
    val isCommitted: Boolean get() = decision.getNow(false)
    val isCancelled: Boolean get() = decision.isDone && !isCommitted
    fun commit(): Boolean = decision.complete(true)
    /** True means cancellation won (or was already chosen), false means commit won. */
    fun cancel(): Boolean { decision.complete(false); return isCancelled }
    /** A started drain worker touches no native resource until its owner commits. */
    fun awaitCommit(): Boolean = decision.join()
    // Admission retires only after both native cleanup and caller result/failure delivery.
    fun callerFinished(): Boolean { callerDone.set(true); return ownerDone.get() }
    fun ownerFinished(): Boolean { ownerDone.set(true); return callerDone.get() }
}
