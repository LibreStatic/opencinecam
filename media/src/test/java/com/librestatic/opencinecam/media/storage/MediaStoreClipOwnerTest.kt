/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.media.storage

import com.librestatic.opencinecam.core.model.FailureCode
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaStoreClipOwnerTest {
    private class Rows(private val sidecarFailure: Throwable? = null) : ClipRows<String> {
        val events = mutableListOf<String>()
        override fun delete(uri: String) { events += "delete:$uri" }
        override fun publish(uri: String) { events += "publish:$uri" }
        override fun writeSidecar(uri: String, bytes: ByteArray) {
            sidecarFailure?.let { throw it }
            events += "sidecar:$uri:${String(bytes)}"
        }
    }

    @Test
    fun pendingClipOpensExactlyOneDescriptor() {
        val opens = mutableListOf<String>()
        val (uri, descriptor) = openInsertedOnce(insert = { "clip" }, open = { opens += it; "fd:$it" }, delete = { error("no delete") })
        assertEquals("clip", uri)
        assertEquals("fd:clip", descriptor)
        assertEquals(listOf("clip"), opens)
    }

    @Test
    fun failedDescriptorOpenDeletesTheInsertedRowAndKeepsTheCause() {
        val deleted = mutableListOf<String>()
        val failure = assertThrows(IllegalStateException::class.java) {
            openInsertedOnce<String, String>(insert = { "clip" }, open = { null }, delete = { deleted += it })
        }
        assertEquals("Pending clip descriptor could not be opened.", failure.message)
        assertEquals(listOf("clip"), deleted)
    }

    @Test
    fun sidecarFailureNeverDeletesAMuxedClipAndReportsTheRootCause() {
        val cause = IOException("disk full")
        val rows = Rows(sidecarFailure = cause)
        val result = finalizeClip(rows, "clip", "sidecar", muxerFinalized = true, bytesWritten = 4096,
            sidecarBytes = { "{}".toByteArray() }, correlationId = "take-1")

        val failed = result as ClipFinalizationResult.Failed
        assertSame(cause, failed.cause)
        assertTrue(failed.clipRetained)
        assertEquals(FailureCode.MUXER_FINALIZATION_FAILED, failed.failure.code)
        assertEquals("java.io.IOException: disk full", failed.failure.details["cause"])
        assertFalse(rows.events.contains("delete:clip"))
        assertFalse(rows.events.contains("publish:clip"))
        assertEquals(listOf("delete:sidecar"), rows.events)
    }

    @Test
    fun incompleteMuxedClipIsAlsoRetainedOnSidecarFailure() {
        val rows = Rows(sidecarFailure = IllegalStateException("sidecar"))
        val result = finalizeClip(rows, "clip", "sidecar", muxerFinalized = false, bytesWritten = 1,
            sidecarBytes = { ByteArray(0) }, correlationId = "take-2") as ClipFinalizationResult.Failed
        assertTrue(result.clipRetained)
        assertFalse(rows.events.contains("delete:clip"))
    }

    @Test
    fun successfulAndEmptyClipsKeepTheirPreviousOutcomes() {
        val rows = Rows()
        assertEquals(ClipFinalizationResult.Published(incomplete = false),
            finalizeClip(rows, "clip", "sidecar", true, 10, { "{}".toByteArray() }, "take-3"))
        assertEquals(listOf("sidecar:sidecar:{}", "publish:clip"), rows.events)

        val empty = Rows()
        assertEquals(ClipFinalizationResult.Deleted, finalizeClip(empty, "clip", "sidecar", true, 0, null, "take-4"))
        assertEquals(listOf("delete:clip", "delete:sidecar"), empty.events)
    }
}
