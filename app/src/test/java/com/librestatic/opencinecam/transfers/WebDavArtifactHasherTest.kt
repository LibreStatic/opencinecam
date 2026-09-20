/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.transfers

import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class WebDavArtifactHasherTest {
    private val id = "00000000-0000-0000-0000-000000000001"
    private val abcHash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    private fun spec(size: Long = 3) = WebDavArtifactSpec(id, WebDavArtifactRole.VIDEO_METADATA, "content://media/external/downloads/1", "take.json", size)
    private fun artifact(size: Long = 3) = WebDavOutboxArtifact(spec(size), modifiedSeconds = 7)
    private fun snapshot(size: Long = 3) = WebDavClipSnapshot(spec().sourceUri, "take.json", size, 7, true, "application/json")

    @Test fun actualSha256VectorUsesRealBytesAndPreservesPublishedIdentity() = owner { _, scope ->
        val source = Source()
        val result = hash(source, scope) as WebDavHashResult.Hashed
        assertEquals(abcHash, result.sha256)
        assertEquals(WebDavArtifactPublication(id, snapshot()), result.publication)
        assertEquals(2, source.snapshots)
        assertEquals(1, source.opens)
        assertTrue(source.closed)
    }

    @Test fun knownDigestAlsoHandlesMultipleChunksWithoutAnIntByteCounter() = owner { _, scope ->
        val size = 2_147_483_665L
        val input = object : InputStream() {
            var remaining = size
            var maxRequested = 0
            override fun read(): Int = if (remaining == 0L) -1 else { remaining--; 0 }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                maxRequested = maxOf(maxRequested, length)
                if (remaining == 0L) return -1
                val count = minOf(remaining, length.toLong()).toInt()
                java.util.Arrays.fill(bytes, offset, offset + count, 0.toByte())
                remaining -= count
                return count
            }
        }
        val source = Source(snapshot(size), input)
        val result = hash(source, scope, artifact(size)) as WebDavHashResult.Hashed
        assertEquals("d9378e43c0e666027848e63668ba0a821b4726843e366626946e3823b477299e", result.sha256)
        assertEquals(size, result.publication.source.sizeBytes)
        assertEquals(65_536, input.maxRequested)
        assertTrue(source.closed)
    }

    @Test fun fixedDigestRejectsSizeAndTimestampStableContentReplacement() = owner { _, scope ->
        val source = Source(input = ByteArrayInputStream("abd".toByteArray()))
        val bound = artifact().copy(sha256 = abcHash)
        assertFailure(WebDavSourceFailureReason.CONTENT_CHANGED, hash(source, scope, bound))
        assertEquals(2, source.snapshots)
        assertTrue(source.closed)
    }

    @Test fun previouslyFixedMatchingHashRemainsUsable() = owner { _, scope ->
        assertEquals(abcHash, (hash(Source(), scope, artifact().copy(sha256 = abcHash)) as WebDavHashResult.Hashed).sha256)
    }

    @Test fun unsealedArtifactDoesNotOpenOrObserveAProvider() = owner { _, scope ->
        var factories = 0
        val result = DefaultWebDavArtifactHasher { factories++; Source() }.hash(WebDavOutboxArtifact(spec()), scope)
        assertFailure(WebDavSourceFailureReason.CONTENT_CHANGED, result)
        assertEquals(0, factories)
    }

    @Test fun metadataIdentityNameSizeTimestampFinalizationAndMimeMustMatchBeforeOpening() = owner { _, scope ->
        for (bad in listOf(snapshot().copy(identity = "content://media/external/downloads/2"), snapshot().copy(displayName = "other.json"),
            snapshot().copy(sizeBytes = 4), snapshot().copy(modifiedSeconds = 8), snapshot().copy(finalized = false), snapshot().copy(mimeType = "text/plain"))) {
            val source = Source(bad)
            assertFailure(WebDavSourceFailureReason.CONTENT_CHANGED, hash(source, scope))
            assertEquals(0, source.opens)
        }
    }

    @Test fun changedFinalSnapshotNeverProducesHashEvenAfterCorrectBytes() = owner { _, scope ->
        val source = Source(after = snapshot().copy(modifiedSeconds = 8))
        assertFailure(WebDavSourceFailureReason.CONTENT_CHANGED, hash(source, scope))
        assertTrue(source.closed)
    }

    @Test fun prematureEofAndGrowthAreContentChangesAndCloseTheirStreams() = owner { _, scope ->
        for (bytes in listOf("ab", "abcd")) {
            val source = Source(input = ByteArrayInputStream(bytes.toByteArray()))
            assertFailure(WebDavSourceFailureReason.CONTENT_CHANGED, hash(source, scope))
            assertTrue(source.closed)
        }
    }

    @Test fun zeroProgressAndImpossibleReadCountsAreReadFailuresNotInfiniteLoops() = owner { _, scope ->
        for (count in listOf(0, 4)) {
            val source = Source(input = object : InputStream() {
                override fun read() = error("Unexpected single byte read")
                override fun read(bytes: ByteArray, offset: Int, length: Int) = count
            })
            assertFailure(WebDavSourceFailureReason.READ_FAILED, hash(source, scope))
            assertTrue(source.closed)
        }
    }

    @Test fun missingDeniedOpenAndReadFailuresAreTypedWithoutExceptionData() = owner { _, scope ->
        for ((failure, reason) in listOf(FileNotFoundException("secret path") to WebDavSourceFailureReason.MISSING,
            SecurityException("secret access") to WebDavSourceFailureReason.ACCESS_DENIED, IOException("secret read") to WebDavSourceFailureReason.READ_FAILED)) {
            assertFailure(reason, DefaultWebDavArtifactHasher { throw failure }.hash(artifact(), scope))
            assertFailure(reason, hash(Source(openFailure = failure), scope))
            val source = Source(input = object : InputStream() { override fun read(): Int = throw failure })
            assertFailure(reason, hash(source, scope))
            assertTrue(source.closed)
        }
    }

    @Test fun closeFailurePreventsSuccessfulHashAndFinalObservation() = owner { _, scope ->
        val source = Source(closeFailure = IOException("close failed with private details"))
        assertFailure(WebDavSourceFailureReason.READ_FAILED, hash(source, scope))
        assertTrue(source.closed)
        assertEquals(1, source.snapshots)
    }

    @Test fun cancellationBeforeHashDoesNotTouchTheFactory() = owner { control, scope ->
        var called = false
        control.cancel()
        assertEquals(WebDavHashResult.Stopped(WebDavStopReason.CANCELLED), DefaultWebDavArtifactHasher { called = true; Source() }.hash(artifact(), scope))
        assertFalse(called)
    }

    @Test fun recordingStopDuringReadWinsOverReadAndCloseFailures() = owner { control, scope ->
        val source = Source(input = object : InputStream() {
            override fun read(): Int { control.pauseForRecording(); throw IOException("read aborted") }
        }, closeFailure = IOException("close also failed"))
        assertEquals(WebDavHashResult.Stopped(WebDavStopReason.RECORDING), hash(source, scope))
        assertTrue(source.closed)
    }

    @Test fun cancellationDuringFinalObservationCannotReturnHashed() = owner { control, scope ->
        val source = object : WebDavClipSource {
            var observations = 0
            override fun snapshot(): WebDavClipSnapshot { if (++observations == 2) control.cancel(); return this@WebDavArtifactHasherTest.snapshot(3) }
            override fun open() = ByteArrayInputStream("abc".toByteArray())
        }
        assertEquals(WebDavHashResult.Stopped(WebDavStopReason.CANCELLED), hash(source, scope))
    }

    @Test fun cancellationReceiptStaysPendingThroughBlockedReadAndBlockedClose() {
        for (holdClose in listOf(false, true)) {
            val control = WebDavUploadControl(WebDavUploadPolicy(enabled = true, network = WebDavNetwork.WIFI))
            val attempt = requireNotNull(control.enter().attempt)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val executor = Executors.newSingleThreadExecutor()
            val source = Source(input = object : ByteArrayInputStream("abc".toByteArray()) {
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    if (!holdClose) { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
                    return super.read(bytes, offset, length)
                }
                override fun close() {
                    if (holdClose) { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
                    super.close()
                }
            })
            try {
                val future = executor.submit<WebDavHashResult> {
                    try { hash(source, WebDavOperationScope(attempt)) } finally { control.leave(attempt) }
                }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val receipt = control.cancel()
                assertFalse(receipt.isRetired)
                assertEquals(WebDavStopReason.BUSY, control.enter().reason)
                release.countDown()
                assertEquals(WebDavHashResult.Stopped(WebDavStopReason.CANCELLED), future.get(5, TimeUnit.SECONDS))
                assertTrue(source.closed)
                assertTrue(receipt.awaitRetired(1, TimeUnit.SECONDS))
            } finally {
                release.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)); control.leave(attempt)
            }
        }
    }

    @Test fun helperNeverLeavesBorrowedOwnerEvenAfterSuccessOrSourceFailure() = owner { control, scope ->
        assertTrue(hash(Source(), scope) is WebDavHashResult.Hashed)
        assertFailure(WebDavSourceFailureReason.READ_FAILED, hash(Source(openFailure = IOException()), scope))
        assertEquals(WebDavStopReason.BUSY, control.enter().reason)
        assertFalse(control.cancel().isRetired)
    }

    @Test fun allSupportedArtifactRolesValidateMimeAndExtension() = owner { _, scope ->
        for ((role, name, mime) in listOf(Triple(WebDavArtifactRole.VIDEO, "a.mp4", "video/mp4"),
            Triple(WebDavArtifactRole.AUDIO, "a.wav", "audio/x-wav"), Triple(WebDavArtifactRole.AUDIO, "a.flac", "audio/flac"),
            Triple(WebDavArtifactRole.VIDEO_METADATA, "a.json", "application/json"), Triple(WebDavArtifactRole.AUDIO_METADATA, "a.json", "application/json"))) {
            val spec = spec().copy(role = role, sourceName = name)
            val source = Source(snapshot().copy(displayName = name, mimeType = mime))
            assertTrue(hash(source, scope, WebDavOutboxArtifact(spec, modifiedSeconds = 7)) is WebDavHashResult.Hashed)
        }
    }

    private class Source(
        private val before: WebDavClipSnapshot = WebDavClipSnapshot("content://media/external/downloads/1", "take.json", 3, 7, true, "application/json"),
        private val input: InputStream = ByteArrayInputStream("abc".toByteArray()),
        private val after: WebDavClipSnapshot = before,
        private val openFailure: Exception? = null,
        private val closeFailure: Exception? = null,
    ) : WebDavClipSource {
        var snapshots = 0
        var opens = 0
        @Volatile var closed = false
        override fun snapshot() = if (++snapshots == 1) before else after
        override fun open(): InputStream {
            opens++
            openFailure?.let { throw it }
            return object : InputStream() {
                override fun read() = input.read()
                override fun read(bytes: ByteArray, offset: Int, length: Int) = input.read(bytes, offset, length)
                override fun close() { try { input.close(); closeFailure?.let { throw it } } finally { closed = true } }
            }
        }
    }
    private fun hash(source: WebDavClipSource, scope: WebDavOperationScope, artifact: WebDavOutboxArtifact = artifact()) =
        DefaultWebDavArtifactHasher { source }.hash(artifact, scope)
    private fun assertFailure(reason: WebDavSourceFailureReason, result: WebDavHashResult) {
        assertEquals(WebDavHashResult.Unavailable(WebDavSourceFailure(spec().sourceUri, reason)), result)
    }
    private fun owner(block: (WebDavUploadControl, WebDavOperationScope) -> Unit) {
        val control = WebDavUploadControl(WebDavUploadPolicy(enabled = true, network = WebDavNetwork.WIFI))
        val attempt = requireNotNull(control.enter().attempt)
        try { block(control, WebDavOperationScope(attempt)) } finally { control.leave(attempt) }
    }
}
