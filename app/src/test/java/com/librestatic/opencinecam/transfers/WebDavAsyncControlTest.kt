/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.transfers

import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class WebDavAsyncControlTest {
    private val enabled = WebDavUploadPolicy(enabled = true, network = WebDavNetwork.WIFI)

    @Test fun asynchronousStopLatchesBeforeReturnWithoutRunningDisconnectOnCaller() {
        val control = WebDavUploadControl(enabled)
        val attempt = requireNotNull(control.enter().attempt)
        val socket = Connection()
        attempt.bind(socket)
        val tasks = mutableListOf<() -> Unit>()
        val receipt = control.updatePolicyAsync(enabled.copy(recording = true), tasks::add)
        assertEquals(WebDavStopReason.RECORDING, attempt.reason)
        assertEquals(WebDavStopReason.RECORDING, control.enter().reason)
        assertEquals(0, socket.disconnects)
        assertEquals(1, tasks.size)
        attempt.unbind()
        control.leave(attempt)
        assertFalse(receipt.isRetired)
        assertFalse(receipt.awaitRetired(0, TimeUnit.SECONDS))
        control.updatePolicyAsync(enabled, tasks::add)
        assertEquals(WebDavStopReason.BUSY, control.enter().reason)
        tasks.single().invoke()
        assertEquals(1, socket.disconnects)
        assertTrue(receipt.isRetired)
        val next = requireNotNull(control.enter().attempt)
        control.leave(next)
    }

    @Test fun repeatedAsyncCancelKeepsFirstReasonAndSchedulesOnlyOneCleanup() {
        val control = WebDavUploadControl(enabled)
        val attempt = requireNotNull(control.enter().attempt)
        val socket = Connection()
        attempt.bind(socket)
        val tasks = mutableListOf<() -> Unit>()
        val first = control.cancelAsync(tasks::add)
        assertSame(first, control.updatePolicyAsync(enabled.copy(recording = true), tasks::add))
        assertSame(first, control.cancelAsync(tasks::add))
        assertEquals(WebDavStopReason.CANCELLED, attempt.reason)
        assertEquals(1, tasks.size)
        tasks.single().invoke()
        assertFalse(first.isRetired)
        attempt.unbind(); control.leave(attempt)
        assertTrue(first.isRetired)
    }

    @Test fun rejectedCleanupDispatchNeverClaimsRetirement() {
        val control = WebDavUploadControl(enabled)
        val attempt = requireNotNull(control.enter().attempt)
        attempt.bind(Connection())
        var reserved: (() -> Unit)? = null
        assertThrows(IllegalStateException::class.java) {
            control.cancelAsync { task -> reserved = task; throw IllegalStateException("dispatcher fixture") }
        }
        assertEquals(WebDavStopReason.CANCELLED, attempt.reason)
        attempt.unbind(); control.leave(attempt)
        assertFalse(attempt.retirement.isRetired)
        assertEquals(WebDavStopReason.BUSY, control.enter().reason)
        requireNotNull(reserved).invoke() // Test repairs the retained dispatch; never fake completion.
        assertTrue(attempt.retirement.isRetired)
    }

    @Test fun throwingDisconnectDoesNotLoseTheReservedCleanupCompletion() {
        val control = WebDavUploadControl(enabled)
        val attempt = requireNotNull(control.enter().attempt)
        attempt.bind(Connection(throws = true))
        val tasks = mutableListOf<() -> Unit>()
        val receipt = control.cancelAsync(tasks::add)
        tasks.single().invoke()
        assertFalse(receipt.isRetired)
        attempt.unbind(); control.leave(attempt)
        assertTrue(receipt.isRetired)
    }

    private class Connection(private val throws: Boolean = false) : HttpURLConnection(URI("https://dav.example/").toURL()) {
        var disconnects = 0
        override fun disconnect() { disconnects++; if (throws) throw IllegalStateException("cleanup fixture") }
        override fun connect() = Unit
        override fun usingProxy() = false
    }
}
