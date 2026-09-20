/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.content.Context
import com.librestatic.opencinecam.storage.PreparedCaptureArtifact
import com.librestatic.opencinecam.transfers.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*

/** Actual codec bytes/MediaStore/SQLite/Keystore/worker, with an explicit in-memory HTTP peer. */
internal object PreparedBundleWorkerProbe {
    fun verify(context: Context, settings: WebDavQueueSettings, database: String, bundleId: String,
        artifacts: List<PreparedCaptureArtifact>, bytes: (PreparedCaptureArtifact) -> ByteArray): String {
        val namespace = "codec-worker-${UUID.randomUUID()}"
        val directory = File(context.noBackupFilesDir, namespace)
        val vault = WebDavCredentialStore(directory, namespace)
        val createdKeys = mutableListOf<String>()
        var primaryFailure: Throwable? = null
        try {
            WebDavSqliteOutbox(context, database).use { store ->
                val before = requireNotNull(store.load(bundleId))
                val original = artifacts.associate { it.role.name to bytes(it) }
                val policy = WebDavUploadPolicy(enabled = true, allowCellular = true, network = WebDavNetwork.WIFI)
                val admission = WebDavOutboxAdmission(UUID.randomUUID().toString(), settings.states.value.preferences.revision, policy)
                val control = WebDavUploadControl(policy)
                val strictSources = WebDavMediaStoreArtifactSource(context.contentResolver)
                var sourceCalls = 0
                var hashCalls = 0
                val sources = WebDavArtifactSourceFactory { spec -> sourceCalls++; strictSources.source(spec) }
                val actualHasher = DefaultWebDavArtifactHasher(sources)
                val hasher = WebDavArtifactHasher { artifact, scope -> hashCalls++; actualHasher.hash(artifact, scope) }
                val peer = Peer(WebDavCredentials("original-operator", "original-destination-secret").authorization())
                fun worker() = WebDavArtifactWorker(store, sources, hasher,
                    WebDavStoredDestinationResolver(settings, vault), control,
                    WebDavUploadTransport(peer::connection), WebDavRemoteReconciler(peer::connection))
                // Actual missing-vault lookup must not hash, claim or create any socket.
                assertEquals(WebDavWorkerResult.Held(WebDavWorkerHold.AUTHENTICATION),
                    worker().runOne(bundleId, before.artifacts.first().spec.id, admission))
                assertEquals(before, store.load(bundleId)); assertEquals(0, peer.requests.size)
                assertTrue(peer.opened.isEmpty()); assertEquals(0, sourceCalls); assertEquals(0, hashCalls)
                createdKeys.add(before.endpointId)
                vault.save(before.endpointId, "original-operator", "original-destination-secret")
                val current = requireNotNull(settings.states.value.preferences.activeEndpointId)
                assertNotEquals(before.endpointId, current)
                createdKeys.add(current)
                vault.save(current, "later-operator", "must-not-be-forwarded")
                val report = JSONArray()
                for (artifact in before.artifacts) {
                    val expected = original.getValue(artifact.spec.role.name)
                    val step = worker()
                    assertEquals(WebDavWorkerResult.Applied(WebDavWorkerTransition.PUT_ACKNOWLEDGED),
                        step.runOne(bundleId, artifact.spec.id, admission))
                    val acknowledged = requireNotNull(store.load(bundleId))
                    val uncertain = acknowledged.artifacts.single { it.spec.id == artifact.spec.id }
                    assertEquals(WebDavArtifactState.UNCERTAIN, uncertain.state)
                    assertNull(uncertain.attempt); assertTrue(uncertain.remoteMayExist)
                    assertNotEquals(WebDavBundleState.COMPLETE, acknowledged.state)
                    assertEquals(WebDavWorkerResult.Applied(WebDavWorkerTransition.RECONCILED),
                        step.runOne(bundleId, artifact.spec.id, admission))
                    val complete = requireNotNull(store.load(bundleId)).artifacts.single { it.spec.id == artifact.spec.id }
                    val digest = MessageDigest.getInstance("SHA-256").digest(expected).joinToString("") { "%02x".format(it) }
                    assertEquals(WebDavArtifactState.VERIFIED, complete.state)
                    assertEquals(digest, complete.sha256); assertNull(complete.attempt)
                    assertEquals(artifact.spec, complete.spec)
                    assertEquals(artifact.modifiedSeconds, complete.modifiedSeconds)
                    val requests = peer.requests.takeLast(2)
                    assertEquals(listOf("PUT", "GET"), requests.map { it.first })
                    assertEquals(requests[0].second, requests[1].second)
                    assertTrue(requests[0].second.startsWith("https://first.example/recorded/"))
                    assertArrayEquals(expected, peer.remote.getValue(requests[0].second))
                    assertArrayEquals(expected, bytes(artifacts.single { it.role.name == artifact.spec.role.name }))
                    report.put(JSONObject().put("role", artifact.spec.role.name).put("sha256", digest)
                        .put("bytes", expected.size).put("state", complete.state.name))
                }
                val completed = requireNotNull(store.load(bundleId))
                assertEquals(WebDavBundleState.COMPLETE, completed.state)
                assertEquals(before.endpointId, completed.endpointId)
                assertEquals(before.endpointRevision, completed.endpointRevision)
                assertEquals(artifacts.size * 2, peer.requests.size)
                assertTrue(peer.opened.all { it.disconnected })
                assertTrue(control.cancel().isRetired)
                WebDavSqliteOutbox(context, database).use { assertEquals(completed, it.load(bundleId)) }
                return JSONObject().put("schema", "prepared-codec-worker-v1").put("bundleId", bundleId)
                    .put("state", "COMPLETE").put("artifacts", report).put("protocolPeer", "IN_MEMORY_HTTP_CONNECTION")
                    .put("tlsQualified", false).put("destinationBinding", "ORIGINAL_ENROLLMENT")
                    .put("requests", peer.requests.size).toString()
            }
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            val problems = mutableListOf<Throwable>()
            fun cleanup(action: () -> Unit) { try { action() } catch (failure: Throwable) { problems.add(failure) } }
            for (endpoint in createdKeys) cleanup { vault.clear(endpoint) }
            // Only this UUID fixture namespace; include keys from a partially failed save.
            cleanup {
                val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                for (alias in keys.aliases().toList().filter { it.startsWith("$namespace.") }) cleanup { keys.deleteEntry(alias) }
                assertFalse(keys.aliases().toList().any { it.startsWith("$namespace.") })
            }
            cleanup { if (directory.exists()) assertTrue(directory.deleteRecursively()) }
            val primary = primaryFailure
            if (primary != null) problems.forEach(primary::addSuppressed)
            else if (problems.isNotEmpty()) {
                val first = problems.first(); problems.drop(1).forEach(first::addSuppressed); throw first
            }
        }
    }

    private class Peer(private val authorization: String) {
        val remote = mutableMapOf<String, ByteArray>()
        val requests = mutableListOf<Pair<String, String>>()
        val opened = mutableListOf<Connection>()
        fun connection(uri: URI): HttpURLConnection = Connection(uri).also(opened::add)
        inner class Connection(private val uri: URI) : HttpURLConnection(uri.toURL()) {
            private val body = ByteArrayOutputStream()
            var disconnected = false
            private var response: Int? = null
            override fun getOutputStream() = body
            override fun getResponseCode(): Int {
                response?.let { return it }
                assertEquals(authorization, getRequestProperty("Authorization"))
                assertFalse(instanceFollowRedirects)
                requests.add(requestMethod to uri.toString())
                val code = when (requestMethod) {
                    "PUT" -> {
                        assertEquals("*", getRequestProperty("If-None-Match"))
                        if (remote.containsKey(uri.toString())) 412 else { remote[uri.toString()] = body.toByteArray(); 201 }
                    }
                    "GET" -> { assertEquals("identity", getRequestProperty("Accept-Encoding")); if (remote.containsKey(uri.toString())) 200 else 404 }
                    else -> error("Unexpected worker request")
                }
                response = code
                return code
            }
            override fun getHeaderFields(): Map<String, List<String>> =
                mapOf("Content-Length" to listOf(remote.getValue(uri.toString()).size.toString()))
            override fun getInputStream() = ByteArrayInputStream(remote.getValue(uri.toString()))
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun disconnect() { disconnected = true }
        }
    }
}
