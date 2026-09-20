/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.transfers

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class WebDavUploadControlTest {
    private val enabled = WebDavUploadPolicy(enabled = true, network = WebDavNetwork.WIFI)
    private val destination = WebDavDestination(URI("https://dav.example/takes/"))

    @Test fun idleReceiptIsRetiredButRecordingPolicyStillDeniesAdmission() {
        val control = WebDavUploadControl(enabled)
        val receipt = control.pauseForRecording()
        assertTrue(receipt.isRetired)
        assertTrue(receipt.awaitRetired(0, TimeUnit.NANOSECONDS))
        assertEquals(WebDavStopReason.RECORDING, control.enter().reason)
        assertThrows(IllegalArgumentException::class.java) { receipt.awaitRetired(-1, TimeUnit.SECONDS) }
    }

    @Test fun firstStopReasonIsVisibleWhileDisconnectBlocksAndCleanupRetainsAdmission() = Threads().use { threads ->
        val control = WebDavUploadControl(enabled)
        val attempt = requireNotNull(control.enter().attempt)
        val disconnect = threads.gate()
        val socket = Connection(onDisconnect = { disconnect.block() })
        attempt.bind(socket)
        val stopping = threads.submit { control.cancel() }
        disconnect.awaitEntered()
        assertEquals(WebDavStopReason.CANCELLED, attempt.reason)
        assertThrows(WebDavUploadControl.UploadStopped::class.java) { attempt.checkRunning() }
        val receipt = control.pauseForRecording()
        assertSame(attempt.retirement, receipt)
        assertEquals(WebDavStopReason.RECORDING, control.enter().reason)
        assertEquals(WebDavStopReason.CANCELLED, attempt.reason)
        assertEquals(1, socket.disconnects.get())
        attempt.unbind()
        control.leave(attempt)
        assertFalse(receipt.isRetired)
        control.updatePolicy(enabled)
        assertEquals(WebDavStopReason.BUSY, control.enter().reason)
        disconnect.release()
        assertSame(receipt, stopping.get(5, TimeUnit.SECONDS))
        assertTrue(receipt.awaitRetired(0, TimeUnit.SECONDS))
        val next = requireNotNull(control.enter().attempt)
        control.leave(attempt) // Stale finalizer cannot clear the new owner.
        assertEquals(WebDavStopReason.BUSY, control.enter().reason)
        assertNull(next.reason)
        control.leave(next)
    }

    @Test fun policyReasonIsLatchedBeforeExternalDisconnectAndCannotBeOverwritten() = Threads().use { threads ->
        val control = WebDavUploadControl(enabled)
        val attempt = requireNotNull(control.enter().attempt)
        val disconnect = threads.gate()
        attempt.bind(Connection(onDisconnect = { disconnect.block() }))
        val stopping = threads.submit { control.updatePolicy(enabled.copy(network = WebDavNetwork.OFFLINE)) }
        disconnect.awaitEntered()
        assertEquals(WebDavStopReason.NETWORK_UNAVAILABLE, attempt.reason)
        val receipt = control.cancel()
        control.updatePolicy(enabled)
        assertEquals(WebDavStopReason.NETWORK_UNAVAILABLE, attempt.reason)
        assertEquals(WebDavStopReason.BUSY, control.enter().reason)
        assertFalse(receipt.isRetired)
        disconnect.release()
        stopping.get(5, TimeUnit.SECONDS)
        assertFalse(receipt.isRetired) // Disconnect is not transport retirement.
        attempt.unbind()
        control.leave(attempt)
        assertTrue(receipt.isRetired)
    }

    @Test fun stopBeforeBindingPreventsSocketAdmissionAndNeverResurrects() {
        val control = WebDavUploadControl(enabled)
        val attempt = requireNotNull(control.enter().attempt)
        val receipt = control.pauseForRecording()
        val socket = Connection()
        assertThrows(WebDavUploadControl.UploadStopped::class.java) { attempt.bind(socket) }
        assertEquals(0, socket.disconnects.get())
        control.updatePolicy(enabled)
        assertThrows(WebDavUploadControl.UploadStopped::class.java) { attempt.checkRunning() }
        assertFalse(receipt.awaitRetired(0, TimeUnit.SECONDS))
        assertEquals(WebDavStopReason.BUSY, control.enter().reason)
        control.leave(attempt)
        assertTrue(receipt.isRetired)
        val next = requireNotNull(control.enter().attempt)
        assertNotSame(receipt, next.retirement)
        assertNull(next.reason)
        control.leave(next)
    }

    @Test fun recordingPausePreservesLatestDisabledAndCellularConsentFields() {
        val control = WebDavUploadControl(enabled.copy(network = WebDavNetwork.CELLULAR, allowCellular = false))
        assertTrue(control.pauseForRecording().isRetired)
        assertEquals(WebDavStopReason.RECORDING, control.enter().reason)
        control.updatePolicy(enabled.copy(enabled = false))
        control.pauseForRecording()
        assertEquals(WebDavStopReason.DISABLED, control.enter().reason)
    }

    @Test fun heldSourceSnapshotDoesNotRetireOnPauseOrTimeout() = heldTransport(Phase.SNAPSHOT)
    @Test fun heldSourceReadDoesNotRetireOnPauseOrTimeout() = heldTransport(Phase.READ)
    @Test fun heldOutputWriteDoesNotRetireOnPauseOrTimeout() = heldTransport(Phase.WRITE)
    @Test fun heldSourceCloseDoesNotRetireOnPauseOrTimeout() = heldTransport(Phase.SOURCE_CLOSE)
    @Test fun heldTransportFinallyDisconnectDoesNotRetireOnPauseOrTimeout() = heldTransport(Phase.FINAL_DISCONNECT)

    private fun heldTransport(phase: Phase) = Threads().use { threads ->
        val control = WebDavUploadControl(enabled)
        val held = threads.gate()
        val snapshots = AtomicInteger()
        val source = source(
            onSnapshot = { if (phase == Phase.SNAPSHOT && snapshots.incrementAndGet() == 1) held.block() },
            onRead = { if (phase == Phase.READ) held.block() },
            onClose = { if (phase == Phase.SOURCE_CLOSE) held.block() },
        )
        val socket = Connection(
            onWrite = { if (phase == Phase.WRITE) held.block() },
            onDisconnect = { if (phase == Phase.FINAL_DISCONNECT) held.block() },
        )
        val transfer = threads.submit { WebDavUploadTransport({ socket }).upload(source, destination, control) }
        held.awaitEntered()
        // In FINAL_DISCONNECT the transport unbound its socket, so pause never invokes a second blocking disconnect.
        val receipt = control.pauseForRecording()
        assertFalse(receipt.isRetired)
        assertFalse(receipt.awaitRetired(0, TimeUnit.NANOSECONDS))
        assertEquals(WebDavStopReason.RECORDING, control.enter().reason)
        control.updatePolicy(enabled)
        assertEquals(WebDavStopReason.BUSY, control.enter().reason)
        control.pauseForRecording()
        held.release()
        val result = transfer.get(5, TimeUnit.SECONDS)
        assertTrue(receipt.awaitRetired(5, TimeUnit.SECONDS))
        if (phase in setOf(Phase.SNAPSHOT, Phase.READ, Phase.WRITE)) {
            assertTrue(result is WebDavUploadResult.Stopped)
            result as WebDavUploadResult.Stopped
            assertEquals(WebDavStopReason.RECORDING, result.reason)
            assertEquals(phase != Phase.SNAPSHOT, result.remoteMayExist)
        } else {
            // An acknowledgement selected before cleanup is not rewritten as a failed PUT.
            assertEquals(WebDavUploadResult.Uploaded(1L, "take.mp4"), result)
        }
        assertEquals(WebDavStopReason.RECORDING, control.enter().reason)
    }

    @Test fun cancellationDisconnectStillBlocksReceiptAfterTransportActuallyReturned() = Threads().use { threads ->
        val control = WebDavUploadControl(enabled)
        val reading = threads.gate()
        val disconnect = threads.gate()
        val calls = AtomicInteger()
        val socket = Connection(onDisconnect = { if (calls.incrementAndGet() == 1) disconnect.block() })
        val transfer = threads.submit { WebDavUploadTransport({ socket }).upload(source(onRead = { reading.block() }), destination, control) }
        reading.awaitEntered()
        val stopping = threads.submit { control.pauseForRecording() }
        disconnect.awaitEntered()
        val receipt = control.cancel() // Same attempt; does not reserve duplicate disconnect work.
        reading.release()
        val result = transfer.get(5, TimeUnit.SECONDS) as WebDavUploadResult.Stopped
        assertEquals(WebDavStopReason.RECORDING, result.reason)
        assertTrue(result.remoteMayExist)
        assertEquals(2, socket.disconnects.get()) // Cancellation plus transport finally.
        assertFalse(receipt.isRetired)
        assertFalse(receipt.awaitRetired(0, TimeUnit.SECONDS))
        control.updatePolicy(enabled)
        assertEquals(WebDavStopReason.BUSY, control.enter().reason)
        disconnect.release()
        assertSame(receipt, stopping.get(5, TimeUnit.SECONDS))
        assertTrue(receipt.isRetired)
        val next = requireNotNull(control.enter().attempt)
        assertNull(next.reason)
        control.leave(next)
    }

    @Test fun disconnectRuntimeExceptionsDoNotRetireEarlyOrStrandTheNextOwner() = Threads().use { threads ->
        val control = WebDavUploadControl(enabled)
        val reading = threads.gate()
        val socket = Connection(onDisconnect = { throw IllegalStateException("cleanup must stay private") })
        val transfer = threads.submit { WebDavUploadTransport({ socket }).upload(source(onRead = { reading.block() }), destination, control) }
        reading.awaitEntered()
        val receipt = control.cancel()
        assertFalse(receipt.isRetired)
        assertEquals(WebDavStopReason.BUSY, control.enter().reason)
        reading.release()
        val result = transfer.get(5, TimeUnit.SECONDS) as WebDavUploadResult.Stopped
        assertEquals(WebDavStopReason.CANCELLED, result.reason)
        assertTrue(result.remoteMayExist)
        assertFalse(result.toString().contains("private"))
        assertTrue(receipt.awaitRetired(5, TimeUnit.SECONDS))
        assertEquals(2, socket.disconnects.get())
        val next = requireNotNull(control.enter().attempt)
        control.leave(next)
    }

    @Test fun cancelledFutureIsNotEvidenceOfTransportRetirement() = Threads().use { threads ->
        val control = WebDavUploadControl(enabled)
        val reading = threads.gate()
        val socket = Connection()
        val transfer = threads.submit { WebDavUploadTransport({ socket }).upload(source(onRead = { reading.block() }), destination, control) }
        reading.awaitEntered()
        val receipt = control.pauseForRecording()
        assertTrue(transfer.cancel(false))
        assertTrue(transfer.isDone)
        assertTrue(transfer.isCancelled)
        assertFalse(receipt.isRetired)
        control.updatePolicy(enabled)
        assertEquals(WebDavStopReason.BUSY, control.enter().reason)
        reading.release()
        assertTrue(receipt.awaitRetired(5, TimeUnit.SECONDS))
        val next = requireNotNull(control.enter().attempt)
        control.leave(next)
    }

    @Test fun previouslyRetiredReceiptDoesNotCertifyANewOwner() {
        val control = WebDavUploadControl(enabled)
        val idle = control.cancel()
        val attempt = requireNotNull(control.enter().attempt)
        assertTrue(idle.isRetired)
        assertFalse(attempt.retirement.isRetired)
        assertEquals(WebDavStopReason.BUSY, control.enter().reason)
        val actual = control.cancel()
        assertNotSame(idle, actual)
        assertFalse(actual.isRetired)
        control.leave(attempt)
        assertTrue(actual.isRetired)
    }

    @Test fun interruptedWaitIsNotRetirementAndDoesNotClearOwner() {
        val control = WebDavUploadControl(enabled)
        val attempt = requireNotNull(control.enter().attempt)
        val receipt = control.cancel()
        try {
            Thread.currentThread().interrupt()
            assertThrows(InterruptedException::class.java) { receipt.awaitRetired(5, TimeUnit.SECONDS) }
        } finally { Thread.interrupted() }
        assertFalse(receipt.isRetired)
        assertEquals(WebDavStopReason.BUSY, control.enter().reason)
        control.leave(attempt)
        assertTrue(receipt.isRetired)
    }

    private enum class Phase { SNAPSHOT, READ, WRITE, SOURCE_CLOSE, FINAL_DISCONNECT }

    private fun source(onSnapshot: () -> Unit = {}, onRead: () -> Unit = {}, onClose: () -> Unit = {}) = object : WebDavClipSource {
        override fun snapshot(): WebDavClipSnapshot {
            onSnapshot()
            return WebDavClipSnapshot("fixture", "take.mp4", 1L, 1L, true, "video/mp4")
        }
        override fun open(): InputStream = object : ByteArrayInputStream(byteArrayOf(1)) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                onRead()
                return super.read(bytes, offset, length)
            }
            override fun close() { onClose(); super.close() }
        }
    }

    private class Connection(private val onWrite: () -> Unit = {}, private val onDisconnect: () -> Unit = {}) :
        HttpURLConnection(URI("https://dav.example/").toURL()) {
        val disconnects = AtomicInteger()
        override fun getOutputStream() = object : OutputStream() {
            override fun write(value: Int) { onWrite() }
            override fun write(bytes: ByteArray, offset: Int, length: Int) { onWrite() }
        }
        override fun getResponseCode() = 201
        override fun connect() = Unit
        override fun disconnect() { disconnects.incrementAndGet(); onDisconnect() }
        override fun usingProxy() = false
    }

    private class Gate {
        private val entered = CountDownLatch(1)
        private val released = CountDownLatch(1)
        fun block() { entered.countDown(); check(released.await(5, TimeUnit.SECONDS)) { "Test gate was not released" } }
        fun awaitEntered() { assertTrue("Worker did not reach test gate", entered.await(5, TimeUnit.SECONDS)) }
        fun release() { released.countDown() }
    }

    private class Threads : AutoCloseable {
        private val executor = Executors.newCachedThreadPool()
        private val gates = mutableListOf<Gate>()
        fun gate() = Gate().also { gates.add(it) }
        fun <T> submit(block: () -> T): Future<T> = executor.submit(Callable { block() })
        override fun close() {
            gates.forEach { it.release() }
            executor.shutdown()
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow()
                assertTrue("Test workers did not retire", executor.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
    }
}
