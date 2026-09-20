/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.transfers

import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class WebDavTransportConfigurationTest {
    private val policy = WebDavUploadPolicy(enabled = true, network = WebDavNetwork.WIFI)
    private val destination = WebDavDestination(URI("https://dav.example/takes/"))

    @Test fun setterFailureStillDisconnectsCreatedConnectionAndRetiresPublicOwner() {
        val control = WebDavUploadControl(policy)
        val socket = ConfigurationFailure()
        assertThrows(IllegalStateException::class.java) {
            WebDavUploadTransport({ socket }).upload(source(), destination, control)
        }
        assertEquals(1, socket.disconnects.get())
        val next = requireNotNull(control.enter().attempt)
        control.leave(next)
    }

    @Test fun setterFailureRetirementWaitsForTheRealDisconnectCleanup() {
        val control = WebDavUploadControl(policy)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val socket = ConfigurationFailure(onDisconnect = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        })
        try {
            val result = executor.submit<Boolean> {
                try { WebDavUploadTransport({ socket }).upload(source(), destination, control); false }
                catch (_: IllegalStateException) { true }
            }
            assertTrue("Created connection was not cleaned", entered.await(5, TimeUnit.SECONDS))
            val receipt = control.pauseForRecording()
            assertFalse(receipt.isRetired)
            assertFalse(receipt.awaitRetired(0, TimeUnit.SECONDS))
            control.updatePolicy(policy)
            assertEquals(WebDavStopReason.BUSY, control.enter().reason)
            release.countDown()
            assertTrue(result.get(5, TimeUnit.SECONDS))
            assertTrue(receipt.isRetired)
            assertEquals(1, socket.disconnects.get())
        } finally {
            release.countDown(); executor.shutdown()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun setterFailureAndCleanupRuntimeExceptionDoNotStrandAdmission() {
        val control = WebDavUploadControl(policy)
        val socket = ConfigurationFailure(onDisconnect = { throw IllegalArgumentException("cleanup fixture") })
        assertThrows(IllegalStateException::class.java) {
            WebDavUploadTransport({ socket }).upload(source(), destination, control)
        }
        assertEquals(1, socket.disconnects.get())
        val next = requireNotNull(control.enter().attempt)
        control.leave(next)
    }

    private fun source() = object : WebDavClipSource {
        override fun snapshot() = WebDavClipSnapshot("fixture", "take.mp4", 1, 1, true, "video/mp4")
        override fun open() = ByteArrayInputStream(byteArrayOf(1))
    }

    private class ConfigurationFailure(private val onDisconnect: () -> Unit = {}) :
        HttpURLConnection(URI("https://dav.example/").toURL()) {
        val disconnects = AtomicInteger()
        override fun setRequestMethod(method: String) { throw IllegalStateException("configuration fixture") }
        override fun disconnect() { disconnects.incrementAndGet(); onDisconnect() }
        override fun connect() = Unit
        override fun usingProxy() = false
    }
}
