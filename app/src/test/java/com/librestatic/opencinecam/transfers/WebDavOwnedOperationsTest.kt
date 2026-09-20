/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.transfers

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class WebDavOwnedOperationsTest {
    private val policy = WebDavUploadPolicy(enabled = true, network = WebDavNetwork.WIFI)
    private val destination = WebDavDestination(URI("https://dav.example/takes/"))

    @Test fun admissionPolicyIsTheImmutableSnapshotForSuccessAndRejection() {
        val control = WebDavUploadControl(policy)
        val admitted = control.enter()
        assertEquals(policy, admitted.policy)
        assertEquals(policy, control.enter().policy)
        val disabled = policy.copy(enabled = false)
        control.updatePolicy(disabled)
        val rejected = control.enter()
        assertEquals(disabled, rejected.policy)
        assertEquals(WebDavStopReason.DISABLED, rejected.reason)
        assertEquals(policy, admitted.policy)
        control.leave(requireNotNull(admitted.attempt))
    }

    @Test fun ownedUploadDoesNotEnterOrRetireAndAllowsSequentialBorrowedOperations() {
        val control = WebDavUploadControl(policy)
        val attempt = requireNotNull(control.enter().attempt)
        val scope = WebDavOperationScope(attempt)
        try {
            repeat(2) {
                val socket = Connection()
                val result = WebDavUploadTransport({ socket }).upload(source(byteArrayOf(1, 2)), destination, scope, hash(byteArrayOf(1, 2)))
                assertEquals(WebDavUploadResult.Uploaded(2, "take.mp4"), result)
                assertArrayEquals(byteArrayOf(1, 2), socket.body.toByteArray())
                assertEquals(1, socket.disconnects)
                assertFalse(attempt.retirement.isRetired)
                assertEquals(WebDavStopReason.BUSY, control.enter().reason)
            }
        } finally { control.leave(attempt) }
        assertTrue(attempt.retirement.isRetired)
    }

    @Test fun explicitRemoteAliasChangesOnlyDestinationAndResultNotLocalSourceSnapshot() {
        val control = WebDavUploadControl(policy)
        val attempt = requireNotNull(control.enter().attempt)
        val original = source(byteArrayOf(1, 2))
        val before = original.snapshot()
        var opened: URI? = null
        try {
            val socket = Connection()
            val result = WebDavUploadTransport({ uri -> opened = uri; socket }).upload(original, destination,
                WebDavOperationScope(attempt), hash(byteArrayOf(1, 2)), remoteName = "alias:100%?#.mp4")
            assertEquals("/takes/alias%3A100%25%3F%23.mp4", requireNotNull(opened).rawPath)
            assertEquals(WebDavUploadResult.Uploaded(2, "alias:100%?#.mp4"), result)
            assertEquals(before, original.snapshot())
            assertEquals("take.mp4", original.snapshot().displayName)
            assertFalse(attempt.retirement.isRetired)
        } finally { control.leave(attempt) }
    }

    @Test fun remoteAliasDoesNotBypassLocalFilenameValidation() {
        val control = WebDavUploadControl(policy)
        val attempt = requireNotNull(control.enter().attempt)
        try {
            val invalid = object : WebDavClipSource {
                override fun snapshot() = WebDavClipSnapshot("fixture", "../invalid.mp4", 1, 1, true, "video/mp4")
                override fun open() = error("Invalid source must not open")
            }
            val result = WebDavUploadTransport({ error("Invalid source must not reach network") }).upload(invalid,
                destination, WebDavOperationScope(attempt), remoteName = "valid.mp4") as WebDavUploadResult.Failed
            assertEquals(WebDavFailure.SOURCE_INVALID, result.reason)
            assertFalse(result.remoteMayExist)
        } finally { control.leave(attempt) }
    }

    @Test fun digestUsesActualSentBytesNotUnchangedMetadataAndNeverAcknowledgesMismatch() {
        val control = WebDavUploadControl(policy)
        val attempt = requireNotNull(control.enter().attempt)
        try {
            val socket = Connection()
            val result = WebDavUploadTransport({ socket }).upload(source(byteArrayOf(2)), destination,
                WebDavOperationScope(attempt), hash(byteArrayOf(1))) as WebDavUploadResult.Failed
            assertEquals(WebDavFailure.SOURCE_CHANGED, result.reason)
            assertTrue(result.remoteMayExist)
            assertEquals(1L, result.bytesSent)
            assertEquals(0, socket.responses)
            assertArrayEquals(byteArrayOf(2), socket.body.toByteArray())
            assertFalse(attempt.retirement.isRetired)
        } finally { control.leave(attempt) }
    }

    @Test fun invalidExpectedHashFailsBeforeSourceOrSocketAndOwnerRemainsBorrowed() {
        val control = WebDavUploadControl(policy)
        val attempt = requireNotNull(control.enter().attempt)
        try {
            val transport = WebDavUploadTransport({ error("No socket") })
            for (value in listOf("", "A".repeat(64), "a".repeat(63), "z".repeat(64))) {
                assertThrows(IllegalArgumentException::class.java) {
                    transport.upload(source(byteArrayOf(1)), destination, WebDavOperationScope(attempt), value)
                }
            }
            assertFalse(attempt.retirement.isRetired)
        } finally { control.leave(attempt) }
    }

    @Test fun stopBetweenBorrowedPhasesPreventsAnotherSocketAndRequiresOuterLeave() {
        val control = WebDavUploadControl(policy)
        val attempt = requireNotNull(control.enter().attempt)
        val scope = WebDavOperationScope(attempt)
        try {
            val receipt = control.pauseForRecording()
            val result = WebDavUploadTransport({ error("No socket after REC") }).upload(source(byteArrayOf(1)), destination, scope)
            assertEquals(WebDavUploadResult.Stopped(WebDavStopReason.RECORDING), result)
            assertEquals(WebDavStopReason.RECORDING, scope.stopReason)
            assertFalse(receipt.isRetired)
        } finally { control.leave(attempt) }
    }

    @Test fun expiredScopeCannotBeReusedAfterOuterLeave() {
        val control = WebDavUploadControl(policy)
        val attempt = requireNotNull(control.enter().attempt)
        val scope = WebDavOperationScope(attempt)
        control.leave(attempt)
        assertThrows(IllegalStateException::class.java) { scope.checkRunning() }
        val next = requireNotNull(control.enter().attempt)
        assertThrows(IllegalStateException::class.java) { scope.bind(Connection()) }
        assertNull(next.reason)
        control.leave(next)
    }

    @Test fun borrowedOwnerDoesNotPermitReplacingAnAlreadyBoundSocket() {
        val control = WebDavUploadControl(policy)
        val attempt = requireNotNull(control.enter().attempt)
        val scope = WebDavOperationScope(attempt)
        try {
            scope.bind(Connection())
            assertThrows(IllegalStateException::class.java) { scope.bind(Connection()) }
            scope.unbind()
            scope.bind(Connection())
            scope.unbind()
        } finally { control.leave(attempt) }
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun source(bytes: ByteArray) = object : WebDavClipSource {
        override fun snapshot() = WebDavClipSnapshot("fixture", "take.mp4", bytes.size.toLong(), 1L, true, "video/mp4")
        override fun open() = ByteArrayInputStream(bytes)
    }
    private class Connection : HttpURLConnection(URI("https://dav.example/").toURL()) {
        val body = ByteArrayOutputStream()
        var responses = 0
        var disconnects = 0
        override fun getOutputStream() = body
        override fun getResponseCode(): Int { responses++; return 201 }
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun disconnect() { disconnects++ }
    }
}
