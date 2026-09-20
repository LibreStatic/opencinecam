/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.transfers

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class WebDavArtifactWorkerTest {
    @Test fun explicitRemoteAliasUsesSamePutAndGetPathWithoutRebindingLocalSourceName() {
        val f = Fixture(remoteName = "alias.mp4")
        Server(listOf(Reply(201), Reply(200, f.data))).use { server ->
            f.connections = server::connect
            assertEquals(applied(WebDavWorkerTransition.PUT_ACKNOWLEDGED), f.run())
            assertEquals(applied(WebDavWorkerTransition.RECONCILED), f.run())
            assertEquals(listOf("/takes/alias.mp4", "/takes/alias.mp4"), server.await().map { it.path })
            assertEquals("take.mp4", f.artifact().spec.sourceName)
            assertEquals("alias.mp4", f.artifact().spec.remoteName)
            assertEquals(WebDavArtifactState.VERIFIED, f.artifact().state)
        }
    }

    @Test fun actualConditionalPutAcknowledgementStaysUncertainUntilActualMatchingGet() {
        val f = Fixture()
        Server(listOf(Reply(201), Reply(200, f.data))).use { server ->
            f.connections = server::connect
            assertEquals(applied(WebDavWorkerTransition.PUT_ACKNOWLEDGED), f.run())
            assertEquals(WebDavArtifactState.UNCERTAIN, f.artifact().state)
            assertNull(f.artifact().attempt)
            assertTrue(f.artifact().remoteMayExist)
            assertEquals(applied(WebDavWorkerTransition.RECONCILED), f.run())
            assertEquals(WebDavArtifactState.VERIFIED, f.artifact().state)
            val requests = server.await()
            assertEquals(listOf("PUT", "GET"), requests.map { it.method })
            assertEquals("/takes/take.mp4", requests.first().path)
            assertEquals("*", requests.first().headers["if-none-match"])
            assertArrayEquals(f.data, requests.first().body)
            assertTrue(requests.all { it.headers["authorization"] == "Basic Y2FtZXJhOnNlY3JldA==" })
            assertEquals(0, f.store.closeCalls)
        }
    }

    @Test fun actualMismatchingRemoteBodyCreatesConflictWithoutAnotherPut() {
        val f = Fixture(); f.makeUncertain()
        Server(listOf(Reply(200, ByteArray(f.data.size) { 99 }))).use { server ->
            f.connections = server::connect
            assertEquals(applied(WebDavWorkerTransition.RECONCILED), f.run())
            assertEquals(WebDavArtifactState.CONFLICT, f.artifact().state)
            assertEquals(held(WebDavWorkerHold.NOT_READY), f.run())
            assertEquals(listOf("GET"), server.await().map { it.method })
            assertEquals(1, f.network.get())
        }
    }

    @Test fun actual404ReturnsQueuedButNeverAutomaticallyRetriesPut() {
        val f = Fixture(); f.makeUncertain()
        Server(listOf(Reply(404))).use { server ->
            f.connections = server::connect
            assertEquals(applied(WebDavWorkerTransition.RECONCILED), f.run())
            assertEquals(WebDavArtifactState.QUEUED, f.artifact().state)
            assertFalse(f.artifact().remoteMayExist)
            assertNull(f.artifact().attempt)
            assertEquals(listOf("GET"), server.await().map { it.method })
            assertEquals(1, f.network.get())
        }
    }

    @Test fun inconclusiveGetReleasesExactLeaseAndPreservesUncertaintyWithoutFinalHash() {
        val f = Fixture(); f.makeUncertain()
        val before = f.artifact()
        f.connections = { MemoryConnection(status = 503) }
        assertEquals(applied(WebDavWorkerTransition.RECONCILE_INCONCLUSIVE), f.run())
        assertEquals(1, f.store.reconcileFinishes)
        assertEquals(1, f.opens.get())
        assertEquals(before.sha256, f.artifact().sha256)
        assertEquals(before.modifiedSeconds, f.artifact().modifiedSeconds)
        assertEquals(WebDavArtifactState.UNCERTAIN, f.artifact().state)
        assertTrue(f.artifact().remoteMayExist)
        assertNull(f.artifact().attempt)
    }

    @Test fun ineligibleAndChangedPoliciesDoNotResolveCredentialsOrOpenSourceOrNetwork() {
        val policies = listOf(policy.copy(enabled = false), policy.copy(recording = true),
            policy.copy(network = WebDavNetwork.OFFLINE), policy.copy(network = WebDavNetwork.CELLULAR))
        for (p in policies) {
            val f = Fixture(p)
            assertEquals(WebDavWorkerResult.Stopped(requireNotNull(p.stopReason())), f.run())
            assertEquals(0, f.store.loads); f.assertNoIo(); assertEquals(0, f.resolutions.get())
        }
        val changed = Fixture(policy.copy(allowCellular = true))
        assertEquals(held(WebDavWorkerHold.POLICY_CHANGED), changed.run())
        assertEquals(0, changed.store.loads); changed.assertNoIo()
    }

    @Test fun absentAuthenticationOrDifferentDestinationNeverOpensArtifactOrNetwork() {
        for (resolution in listOf<WebDavResolvedDestination?>(null,
            WebDavResolvedDestination(id(91), 7, destination), WebDavResolvedDestination(id(9), 8, destination))) {
            val f = Fixture(); f.resolved = resolution
            assertEquals(held(if (resolution == null) WebDavWorkerHold.AUTHENTICATION else WebDavWorkerHold.DESTINATION_CHANGED), f.run())
            f.assertNoIo(); assertEquals(1, f.resolutions.get()); assertEquals(0, f.store.claims)
        }
    }

    @Test fun absentUnsealedAndAlreadyOwnedArtifactsDoNotResolveOrOpenAnything() {
        val missing = Fixture(); missing.store.current = null
        assertEquals(held(WebDavWorkerHold.NOT_FOUND), missing.run()); missing.assertNoIo()
        val unsealed = Fixture(); unsealed.store.current = WebDavOutboxTransitions.stage(unsealed.plan)
        assertEquals(held(WebDavWorkerHold.NOT_READY), unsealed.run()); unsealed.assertNoIo()
        val owned = Fixture(); owned.makeUncertain()
        val a = owned.artifact()
        owned.store.claim(id(1), a.spec.id, a.revision, id(31), WebDavAttemptKind.RECONCILE, admission)
        assertEquals(held(WebDavWorkerHold.NOT_READY), owned.run()); owned.assertNoIo()
        assertEquals(0, missing.resolutions.get() + unsealed.resolutions.get() + owned.resolutions.get())
    }

    @Test fun changedSourceBeforeGetPersistsTypedFailureWithoutNetworkClaim() {
        val f = Fixture(); f.makeUncertain(); f.data = ByteArray(f.data.size) { 22 }
        assertEquals(applied(WebDavWorkerTransition.SOURCE_UNAVAILABLE), f.run())
        assertEquals(WebDavSourceFailure(f.spec.sourceUri, WebDavSourceFailureReason.CONTENT_CHANGED), f.artifact().sourceFailure)
        assertEquals(0, f.network.get()); assertNull(f.artifact().attempt)
        assertTrue(f.artifact().remoteMayExist)
    }

    @Test fun changedSourceAfterGetCannotBeDeclaredVerifiedOrQueued() {
        val f = Fixture(); f.makeUncertain()
        val original = f.data.copyOf()
        f.connections = { MemoryConnection(body = original, onResponse = { f.data = ByteArray(original.size) { 44 } }) }
        assertEquals(applied(WebDavWorkerTransition.SOURCE_UNAVAILABLE), f.run())
        assertEquals(WebDavArtifactState.SOURCE_UNAVAILABLE, f.artifact().state)
        assertEquals(WebDavSourceFailureReason.CONTENT_CHANGED, f.artifact().sourceFailure?.reason)
        assertTrue(f.artifact().remoteMayExist); assertNull(f.artifact().attempt)
        assertEquals(2, f.opens.get()); assertEquals(1, f.network.get())
    }

    @Test fun freshPutSourceWithDifferentMtimeNeverReusesThePrehashFingerprintOrOpensNetwork() {
        val f = Fixture()
        f.snapshotForSource = { index -> if (index == 1) f.snapshot else f.snapshot.copy(modifiedSeconds = 12L) }
        assertEquals(applied(WebDavWorkerTransition.PUT_NOT_STARTED), f.run())
        assertEquals(0, f.network.get())
        assertEquals(1, f.opens.get()) // Initial hash only; the rebound PUT source stays unopened.
        assertEquals(WebDavArtifactState.QUEUED, f.artifact().state)
        assertEquals(11L, f.artifact().modifiedSeconds)
        assertEquals(f.snapshot.displayName, f.artifact().spec.sourceName)
        assertNull(f.artifact().attempt); assertFalse(f.artifact().remoteMayExist)
        assertEquals(applied(WebDavWorkerTransition.SOURCE_UNAVAILABLE), f.run())
        assertEquals(WebDavSourceFailureReason.CONTENT_CHANGED, f.artifact().sourceFailure?.reason)
        assertEquals(0, f.network.get())
    }

    @Test fun heldHashReadKeepsCancellationReceiptPendingUntilActualReaderRetires() = heldSource(close = false)
    @Test fun heldSourceCloseKeepsCancellationReceiptPendingUntilActualCleanupRetires() = heldSource(close = true)

    private fun heldSource(close: Boolean) {
        val f = Fixture(); val gate = Gate()
        if (close) f.onClose = gate::hold else f.onRead = gate::hold
        val pool = Executors.newSingleThreadExecutor()
        try {
            val result = pool.submit<WebDavWorkerResult> { f.run() }
            assertTrue(gate.entered.await(5, TimeUnit.SECONDS))
            val receipt = f.control.cancel()
            assertFalse(receipt.isRetired); assertFalse(result.isDone)
            assertEquals(WebDavWorkerResult.Stopped(WebDavStopReason.BUSY), f.run())
            assertEquals(0, f.network.get()); assertEquals(0, f.store.claims)
            gate.release.countDown()
            assertEquals(WebDavWorkerResult.Stopped(WebDavStopReason.CANCELLED), result.get(5, TimeUnit.SECONDS))
            assertTrue(receipt.awaitRetired(5, TimeUnit.SECONDS))
            assertEquals(WebDavArtifactState.QUEUED, f.artifact().state)
            assertEquals(1, f.closes.get()); assertNull(f.artifact().sourceFailure)
        } finally { gate.release.countDown(); pool.shutdown(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)) }
    }

    @Test fun heldSqlFinishKeepsCancellationReceiptPendingAfterSocketCleanup() {
        val f = Fixture(); val gate = Gate(); f.store.beforePutFinish = gate::hold
        val connection = MemoryConnection(status = 201)
        f.connections = { connection }
        val pool = Executors.newSingleThreadExecutor()
        try {
            val result = pool.submit<WebDavWorkerResult> { f.run() }
            assertTrue(gate.entered.await(5, TimeUnit.SECONDS))
            assertTrue(connection.disconnected)
            val receipt = f.control.cancel()
            assertFalse(receipt.isRetired); assertFalse(result.isDone)
            gate.release.countDown()
            assertEquals(applied(WebDavWorkerTransition.PUT_ACKNOWLEDGED), result.get(5, TimeUnit.SECONDS))
            assertTrue(receipt.awaitRetired(5, TimeUnit.SECONDS))
            assertEquals(WebDavArtifactState.UNCERTAIN, f.artifact().state)
            assertNull(f.artifact().attempt); assertEquals(1, f.store.putFinishes)
        } finally { gate.release.countDown(); pool.shutdown(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)) }
    }

    @Test fun cancellationImmediatelyAfterClaimReleasesOnlyTheExactUndispatchedLease() {
        for (reconcile in listOf(false, true)) {
            val f = Fixture(); if (reconcile) f.makeUncertain()
            f.store.afterClaim = { f.control.cancel() }
            assertEquals(WebDavWorkerResult.Stopped(WebDavStopReason.CANCELLED), f.run())
            assertEquals(0, f.network.get()); assertNull(f.artifact().attempt)
            assertEquals(if (reconcile) WebDavArtifactState.UNCERTAIN else WebDavArtifactState.QUEUED, f.artifact().state)
            assertEquals(reconcile, f.artifact().remoteMayExist)
            assertEquals(if (reconcile) 1 else 0, f.store.reconcileFinishes)
            assertEquals(if (reconcile) 0 else 1, f.store.putFinishes)
        }
    }

    @Test fun terminalPersistenceFailureIsNotBlindlyRetriedAndRetainsLeaseForRecovery() {
        for (reconcile in listOf(false, true)) {
            val f = Fixture(); if (reconcile) f.makeUncertain()
            var atFailure: WebDavOutboxBundle? = null
            val failSql: () -> Unit = { atFailure = f.store.current; throw IOException("SQL unavailable with secret provider detail") }
            if (reconcile) {
                f.connections = { MemoryConnection(status = 503) }
                f.store.beforeReconcileFinish = failSql
            } else f.store.beforePutFinish = failSql
            assertEquals(WebDavWorkerResult.Attention, f.run())
            assertNotNull(atFailure); assertEquals(atFailure, f.store.current)
            assertNotNull(f.artifact().attempt)
            assertTrue(f.artifact().remoteMayExist)
            assertEquals(if (reconcile) WebDavAttemptKind.RECONCILE else WebDavAttemptKind.PUT, f.artifact().attempt?.kind)
            assertEquals(1, if (reconcile) f.store.reconcileFinishes else f.store.putFinishes)
            val network = f.network.get()
            assertEquals(held(WebDavWorkerHold.NOT_READY), f.run())
            assertEquals(network, f.network.get())
            assertTrue(f.control.cancel().isRetired)
        }
    }

    @Test fun staleClaimCasNeverDispatchesNetworkOrMutatesAnotherAttempt() {
        val f = Fixture()
        f.store.raceClaim = true
        assertEquals(WebDavWorkerResult.Stale, f.run())
        assertEquals(1, f.store.claims)
        assertEquals(0, f.network.get())
        assertEquals(f.store.racedWinner, f.store.current)
        assertEquals(id(95), f.artifact().attempt?.id)
        assertEquals(id(96), f.artifact().attempt?.processToken)
        assertEquals(WebDavArtifactState.UPLOADING, f.artifact().state)
        assertNotNull(f.artifact().sha256)
    }

    private class Gate {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        fun hold() { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
    }

    private class Fixture(initialPolicy: WebDavUploadPolicy = policy, remoteName: String = "take.mp4") {
        var data = ByteArray(1024) { (it * 13).toByte() }
        val spec = WebDavArtifactSpec(id(2), WebDavArtifactRole.VIDEO, "content://media/external/video/media/1", "take.mp4", data.size.toLong(), remoteName)
        val plan = WebDavBundlePlan(id(1), id(9), 7L, listOf(spec))
        val snapshot = WebDavClipSnapshot(spec.sourceUri, spec.sourceName, spec.sizeBytes, 11L, true, "video/mp4")
        val store = Store(WebDavOutboxTransitions.seal(WebDavOutboxTransitions.stage(plan), listOf(WebDavArtifactPublication(spec.id, snapshot))))
        val control = WebDavUploadControl(initialPolicy)
        val opens = AtomicInteger(); val closes = AtomicInteger(); val network = AtomicInteger(); val resolutions = AtomicInteger()
        val sourceCreations = AtomicInteger()
        var snapshotForSource: ((Int) -> WebDavClipSnapshot)? = null
        var onRead: (() -> Unit)? = null; var onClose: (() -> Unit)? = null
        var resolved: WebDavResolvedDestination? = WebDavResolvedDestination(id(9), 7L, destination)
        var connections: (URI) -> HttpURLConnection = { MemoryConnection(status = 201) }
        private val factory = WebDavArtifactSourceFactory { requested ->
            val sourceIndex = sourceCreations.incrementAndGet()
            check(requested == spec)
            object : WebDavClipSource {
                override fun snapshot(): WebDavClipSnapshot = snapshotForSource?.invoke(sourceIndex) ?: this@Fixture.snapshot
                override fun open(): InputStream {
                    opens.incrementAndGet()
                    return object : ByteArrayInputStream(data.copyOf()) {
                        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                            onRead?.invoke(); return super.read(bytes, offset, length)
                        }
                        override fun close() { try { onClose?.invoke() } finally { closes.incrementAndGet(); super.close() } }
                    }
                }
            }
        }
        private val worker = WebDavArtifactWorker(store, factory, DefaultWebDavArtifactHasher(factory),
            WebDavWorkerDestinationResolver { _, _ -> resolutions.incrementAndGet(); resolved }, control,
            WebDavUploadTransport({ uri -> network.incrementAndGet(); connections(uri) }),
            WebDavRemoteReconciler({ uri -> network.incrementAndGet(); connections(uri) }))
        fun run() = worker.runOne(id(1), id(2), admission)
        fun artifact() = requireNotNull(store.current).artifacts.single()
        fun assertNoIo() { assertEquals(0, sourceCreations.get()); assertEquals(0, opens.get()); assertEquals(0, network.get()) }
        fun makeUncertain() {
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
            var bundle = requireNotNull(store.current)
            bundle = WebDavOutboxTransitions.hash(bundle, WebDavArtifactPublication(spec.id, snapshot), hash)
            val (claimed, lease) = WebDavOutboxTransitions.claim(bundle, spec.id, id(20), WebDavAttemptKind.PUT, admission)
            store.current = WebDavOutboxTransitions.finishPut(claimed, lease, WebDavPutOutcome.REMOTE_UNCERTAIN)
        }
    }

    /** All state mutations delegate to production transitions; hooks only model SQL timing/failure. */
    private class Store(@Volatile var current: WebDavOutboxBundle?) : WebDavOutboxStore {
        var loads = 0; var claims = 0; var putFinishes = 0; var reconcileFinishes = 0; var closeCalls = 0
        var raceClaim = false
        var racedWinner: WebDavOutboxBundle? = null
        var afterClaim: (() -> Unit)? = null
        var beforePutFinish: (() -> Unit)? = null
        var beforeReconcileFinish: (() -> Unit)? = null
        override fun stage(plan: WebDavBundlePlan): WebDavOutboxBundle = error("Worker must not stage")
        override fun load(bundleId: String): WebDavOutboxBundle? { loads++; return current?.takeIf { it.id == bundleId } }
        override fun list(limit: Int): List<WebDavOutboxBundle> = error("Worker must not scan")
        override fun seal(bundleId: String, expectedRevision: Long, publications: List<WebDavArtifactPublication>): WebDavOutboxBundle? = error("Worker must not seal")
        override fun recordHash(bundleId: String, expectedArtifactRevision: Long, publication: WebDavArtifactPublication, sha256: String): WebDavOutboxBundle? {
            val before = current ?: return null
            if (before.id != bundleId || before.artifacts.none { it.spec.id == publication.artifactId && it.revision == expectedArtifactRevision }) return null
            return WebDavOutboxTransitions.hash(before, publication, sha256).also { current = it }
        }
        override fun claim(bundleId: String, artifactId: String, expectedArtifactRevision: Long, attemptId: String,
            kind: WebDavAttemptKind, admission: WebDavOutboxAdmission): WebDavOutboxLease? {
            claims++
            val before = current ?: return null
            if (before.id != bundleId || before.artifacts.none { it.spec.id == artifactId && it.revision == expectedArtifactRevision && it.attempt == null }) return null
            if (raceClaim) {
                current = WebDavOutboxTransitions.claim(before, artifactId, id(95), kind, admission.copy(processToken = id(96))).first
                racedWinner = current
                return null // A competing exact CAS won before this worker's SQL claim.
            }
            val (next, lease) = WebDavOutboxTransitions.claim(before, artifactId, attemptId, kind, admission)
            current = next; afterClaim?.invoke(); return lease
        }
        override fun finishPut(lease: WebDavOutboxLease, outcome: WebDavPutOutcome): Boolean {
            putFinishes++; beforePutFinish?.invoke()
            return mutate { WebDavOutboxTransitions.finishPut(it, lease, outcome) }
        }
        override fun finishReconcile(lease: WebDavOutboxLease): Boolean {
            reconcileFinishes++; beforeReconcileFinish?.invoke()
            return mutate { WebDavOutboxTransitions.finishReconcile(it, lease) }
        }
        override fun reconcile(lease: WebDavOutboxLease, evidence: WebDavReconciliationEvidence) = mutate { WebDavOutboxTransitions.reconcile(it, lease, evidence) }
        override fun sourceUnavailable(bundleId: String, artifactId: String, expectedArtifactRevision: Long, failure: WebDavSourceFailure) =
            mutate { if (it.id != bundleId) null else WebDavOutboxTransitions.sourceUnavailable(it, artifactId, expectedArtifactRevision, failure) }
        override fun sourceUnavailable(lease: WebDavOutboxLease, failure: WebDavSourceFailure) = mutate { WebDavOutboxTransitions.sourceUnavailable(it, lease, failure) }
        override fun sourceRecovered(bundleId: String, expectedArtifactRevision: Long, publication: WebDavArtifactPublication, sha256: String): WebDavOutboxBundle? {
            val before = current ?: return null
            if (before.id != bundleId) return null
            return WebDavOutboxTransitions.sourceRecovered(before, expectedArtifactRevision, publication, sha256)?.also { current = it }
        }
        override fun recoverProcess(deadProcessToken: String): Int = error("Worker must not impersonate process retirement")
        override fun close() { closeCalls++ }
        private fun mutate(change: (WebDavOutboxBundle) -> WebDavOutboxBundle?): Boolean {
            val next = current?.let(change) ?: return false
            current = next; return true
        }
    }

    private class MemoryConnection(private val status: Int = 200, private val body: ByteArray = byteArrayOf(),
        private val onResponse: () -> Unit = {}) : HttpURLConnection(URI("https://memory.example/").toURL()) {
        var disconnected = false
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun disconnect() { disconnected = true }
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getResponseCode(): Int { onResponse(); return status }
        override fun getInputStream() = ByteArrayInputStream(body)
        override fun getHeaderFields(): Map<String?, List<String>> = mapOf("Content-Length" to listOf(body.size.toString()))
    }

    private data class Reply(val status: Int, val body: ByteArray = byteArrayOf())
    private data class Request(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray)
    private class Server(replies: List<Reply>) : AutoCloseable {
        private val socket = ServerSocket(0, 4, InetAddress.getLoopbackAddress()).apply { soTimeout = 10_000 }
        private val executor = Executors.newSingleThreadExecutor()
        private val requests = executor.submit<List<Request>> {
            replies.map { reply -> socket.accept().use { connection ->
                connection.soTimeout = 10_000
                val input = connection.getInputStream()
                fun line(): String {
                    val bytes = ByteArrayOutputStream()
                    while (true) { val b = input.read(); check(b >= 0); if (b == 10) break; if (b != 13) bytes.write(b) }
                    return bytes.toString("US-ASCII")
                }
                val first = line().split(' ')
                val headers = linkedMapOf<String, String>()
                while (true) { val value = line(); if (value.isEmpty()) break; headers[value.substringBefore(':').lowercase()] = value.substringAfter(':').trim() }
                val body = ByteArray(headers["content-length"]?.toInt() ?: 0)
                var read = 0
                while (read < body.size) { val count = input.read(body, read, body.size - read); check(count > 0); read += count }
                connection.getOutputStream().apply {
                    write("HTTP/1.1 ${reply.status} Fixture\r\nContent-Length: ${reply.body.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    write(reply.body); flush()
                }
                Request(first[0], first[1], headers, body)
            } }
        }
        fun connect(uri: URI): HttpURLConnection = URI("http", null, socket.inetAddress.hostAddress, socket.localPort, uri.path, null, null).toURL().openConnection() as HttpURLConnection
        fun await() = requests.get(15, TimeUnit.SECONDS)
        override fun close() { socket.close(); executor.shutdown(); assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS)) }
    }

    companion object {
        private val policy = WebDavUploadPolicy(enabled = true, network = WebDavNetwork.WIFI)
        private val admission = WebDavOutboxAdmission(id(10), 3L, policy)
        private val destination = WebDavDestination(URI("https://dav.example/takes/"), WebDavCredentials("camera", "secret"))
        private fun id(number: Int) = "00000000-0000-0000-0000-${number.toString().padStart(12, '0')}"
        private fun applied(value: WebDavWorkerTransition) = WebDavWorkerResult.Applied(value)
        private fun held(value: WebDavWorkerHold) = WebDavWorkerResult.Held(value)
    }
}
