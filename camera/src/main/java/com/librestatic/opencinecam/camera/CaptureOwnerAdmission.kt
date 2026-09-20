/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/**
 * Serializes capture owners, not Java Surface wrappers. A successor may acquire native camera/EGL
 * resources only after every preceding owner's actual retirement has succeeded. No timeout,
 * cancelled observer or closed waiting owner shortens that ownership chain.
 *
 * Futures exposed to callers are defensive views. A failed retirement poisons admission rather
 * than falsely certifying that a possibly live native window can be reused. Recovery requires
 * real owner retirement; this coordinator deliberately has no force-release/reset operation.
 */
class CaptureOwnerAdmission {
    private var tail = CompletableFuture.completedFuture(Unit)

    @Synchronized
    fun acquire(): Lease {
        val lease = Lease(tail)
        tail = lease.released
        return lease
    }

    class Lease internal constructor(private val predecessor: CompletableFuture<Unit>) {
        internal val released = CompletableFuture<Unit>()
        private var releaseRegistered = false

        fun ready(): CompletableFuture<Unit> = predecessor.defensiveView()

        /** Register actual native retirement once, including for an owner closed while waiting. */
        fun releaseAfter(retirement: CompletionStage<*>): CompletableFuture<Unit> {
            val register = synchronized(this) {
                if (releaseRegistered) false else { releaseRegistered = true; true }
            }
            if (register) {
                predecessor.thenCombine(retirement) { _, _ -> Unit }.whenComplete { _, failure ->
                    if (failure == null) released.complete(Unit) else released.completeExceptionally(failure)
                }
            }
            return released.defensiveView()
        }
    }

    companion object {
        val processGlobal = CaptureOwnerAdmission()
    }
}

private fun CompletableFuture<Unit>.defensiveView(): CompletableFuture<Unit> =
    CompletableFuture<Unit>().also { view ->
        whenComplete { _, failure ->
            if (failure == null) view.complete(Unit) else view.completeExceptionally(failure)
        }
    }
