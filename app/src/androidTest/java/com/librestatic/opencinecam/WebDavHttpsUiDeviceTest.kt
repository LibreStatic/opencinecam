/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.net.Network
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.storage.CaptureArtifactRole
import com.librestatic.opencinecam.storage.PreparedCaptureArtifact
import com.librestatic.opencinecam.transfers.*
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real default Android Network + HTTPS peer; trust customization is confined to these connections. */
class WebDavHttpsUiDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun trustedFixtureCaUiSendUploadsAndVerifiesTheSelectedBundle() = runCase(trusted = true)
    @Test fun defaultTrustRejectsFixtureCaBeforeAnyHttpAndPreservesLocalCapture() = runCase(trusted = false)

    private fun runCase(trusted: Boolean) {
        val fixture = Fixture(trusted)
        var runtime: WebDavTransferRuntime? = null
        var clicked: String? = null
        try {
            fixture.prepare()
            val active = WebDavTransferRuntime(fixture.context, fixture.database, { fixture.settings },
                connections = { network, uri -> fixture.open(network, uri) }) // No injected NetworkSource.
            runtime = active
            compose.setContent {
                val state by active.states.collectAsState()
                MaterialTheme {
                    Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).verticalScroll(rememberScrollState())) {
                        WebDavTransferSettingsSection(state, active::refresh, { id -> clicked = id; active.send(id) }, active::cancel)
                    }
                }
            }
            active.refresh()
            compose.waitUntil(20_000) { !active.states.value.busy && active.states.value.bundles.any { it.id == fixture.id } }
            assertTrue(fixture.trace.connections.isEmpty())
            compose.onNodeWithTag("webdav-transfer-send-${fixture.id}").performScrollTo().assertIsEnabled().performClick()
            compose.waitUntil(90_000) { !active.states.value.busy && fixture.trace.connections.isNotEmpty() }
            compose.waitForIdle()
            assertEquals(fixture.id, clicked)
            val bundle = WebDavSqliteOutbox(fixture.context, fixture.database).use { requireNotNull(it.load(fixture.id)) }
            fixture.assertLocalUnchanged()
            fixture.report(active.states.value, bundle, clicked)
            assertTrue(fixture.trace.connections.all { it.networkHandle.toLong() > 0L })
            if (trusted) {
                assertEquals(WebDavTransferMessage.COMPLETE, active.states.value.message)
                assertEquals(WebDavBundleState.COMPLETE, bundle.state)
                assertEquals(WebDavArtifactState.VERIFIED, bundle.artifacts.single().state)
                assertEquals(listOf("PUT", "GET"), fixture.trace.responses.map { it.method })
                assertEquals(listOf(201, 200), fixture.trace.responses.map { it.status })
                assertEquals(2, fixture.trace.connections.size)
                fixture.trace.responses.forEach {
                    assertTrue("Missing TLS cipher for ${it.method}", it.cipher.isNotBlank())
                    assertEquals("Peer certificate SHA-256 must be 64 hex characters for ${it.method}", 64, it.peerSha256.length)
                }
                assertEquals(1, fixture.trace.connections.map { it.networkHandle }.distinct().size)
                assertTrue(fixture.trace.failures.isEmpty())
                compose.onNodeWithTag("webdav-transfer-status-${fixture.id}")
                    .performScrollTo().assertTextEquals(fixture.base.getString(R.string.webdav_transfer_verified_bundle))
            } else {
                assertNotEquals(WebDavTransferMessage.COMPLETE, active.states.value.message)
                assertNotEquals(WebDavBundleState.COMPLETE, bundle.state)
                assertNotEquals(WebDavArtifactState.VERIFIED, bundle.artifacts.single().state)
                assertTrue(fixture.trace.responses.isEmpty())
                assertTrue("Failure must be certificate/TLS validation, not connectivity", fixture.trace.failures.any {
                    it.contains("SSLHandshakeException") || it.contains("CertificateException")
                })
                assertEquals(1, fixture.trace.connections.size) // No automatic retry or GET after failure.
            }
        } finally {
            val active = runtime
            if (active != null) {
                active.cancel()
                runBlocking { withTimeout(90_000) { active.states.first { !it.busy } } }
                active.closeForTest()
            }
            fixture.close()
        }
    }

    private class Fixture(private val trusted: Boolean) : AutoCloseable {
        val base: Context = InstrumentationRegistry.getInstrumentation().targetContext
        private val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val id = UUID.randomUUID().toString()
        val database = "e1-https-$id.db"
        private val directory = File(base.cacheDir, "e1-https-private-$id").apply { check(mkdirs()) }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir() = directory
        }
        val settings = WebDavQueueSettings(File(directory, "settings.json"))
        val trace = Trace()
        private val reportFile = File(base.cacheDir, if (trusted) "e1-https-trusted.json" else "e1-https-untrusted.json")
        init { if (reportFile.exists()) check(reportFile.delete()) }
        private var row: Uri? = null
        private var endpoint: String? = null
        private var snapshot: WebDavClipSnapshot? = null
        private var sha256 = ""
        private var size = 0L
        private val path get() = "/takes/$id/take.mp4"
        private val socketFactory: SSLSocketFactory? by lazy {
            if (!trusted) null else {
                val ca = assets.open("e1-tls-ca.pem").use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
                val trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("e1-fixture-ca", ca) }
                val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
                SSLContext.getInstance("TLS").apply { init(null, managers.trustManagers, null) }.socketFactory
            }
        }

        fun prepare() {
            val preferences = settings.save("https://10.0.2.2:18443/takes/$id/", true, false)
            endpoint = requireNotNull(preferences.activeEndpointId)
            WebDavCredentialStore(context).save(endpoint!!, "e1-operator", "e1-fixture-secret")
            val uri = requireNotNull(context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "e1-https-$id.mp4")
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }))
            row = uri
            val digest = MessageDigest.getInstance("SHA-256")
            assets.open("e1-video.mp4").use { input ->
                requireNotNull(context.contentResolver.openOutputStream(uri)).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        check(count > 0)
                        size = Math.addExact(size, count.toLong())
                        check(size <= 64L * 1024 * 1024) { "Unexpectedly large E1 video fixture" }
                        output.write(buffer, 0, count); digest.update(buffer, 0, count)
                    }
                }
            }
            check(size > 0)
            sha256 = hex(digest.digest())
            assertEquals(1, context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null))
            val name = requireNotNull(context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)).use {
                check(it.moveToFirst()); it.getString(0)
            }
            val published = MediaStorePreparedArtifactProbe(context.contentResolver).inspect(PreparedCaptureArtifact(CaptureArtifactRole.VIDEO, uri.toString(), name), false)
            snapshot = published
            assertEquals(size, published.sizeBytes)
            val spec = WebDavArtifactSpec(UUID.randomUUID().toString(), WebDavArtifactRole.VIDEO, uri.toString(), name, size, "take.mp4")
            WebDavSqliteOutbox(context, database).use { store ->
                val staged = store.stage(WebDavBundlePlan(id, endpoint!!, 0L, listOf(spec)))
                assertNotNull(store.seal(id, staged.revision, listOf(WebDavArtifactPublication(spec.id, published))))
            }
            CaptureTransferEnrollmentFile(context).enroll(CaptureTransferEnrollment(id, endpoint!!, 0L, preferences.revision))
        }

        fun open(network: Network, uri: URI): HttpURLConnection {
            check(uri.scheme == "https" && uri.host == "10.0.2.2" && uri.port == 18443 && uri.rawPath == path)
            val real = network.openConnection(uri.toURL()) as HttpsURLConnection
            val originalHostnameVerifier = real.hostnameVerifier
            socketFactory?.let { real.sslSocketFactory = it }
            assertSame(originalHostnameVerifier, real.hostnameVerifier)
            val entry = ConnectionRecord(network.networkHandle.toString(), uri.toString())
            trace.connections.add(entry)
            return TrackedConnection(real, trace)
        }

        fun assertLocalUnchanged() {
            val old = requireNotNull(snapshot)
            val current = MediaStorePreparedArtifactProbe(context.contentResolver).inspect(
                PreparedCaptureArtifact(CaptureArtifactRole.VIDEO, old.identity, old.displayName), false)
            assertEquals(old, current)
            val digest = MessageDigest.getInstance("SHA-256")
            requireNotNull(context.contentResolver.openInputStream(requireNotNull(row))).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val count = input.read(buffer); if (count < 0) break; check(count > 0); digest.update(buffer, 0, count) }
            }
            assertEquals(sha256, hex(digest.digest()))
        }

        fun report(state: WebDavTransferUiState, bundle: WebDavOutboxBundle, clicked: String?) {
            val json = JSONObject().put("case", if (trusted) "trusted" else "untrusted").put("bundleId", id)
                .put("endpointId", endpoint).put("clickedBundleId", clicked).put("uri", row.toString())
                .put("remotePath", path).put("sha256", sha256).put("sizeBytes", size)
                .put("localSourceVerifiedUnchanged", true)
                .put("message", state.message.name).put("bundleState", bundle.state.name)
                .put("artifactState", bundle.artifacts.single().state.name)
                .put("networkSource", "AndroidDefaultNetworkSource").put("tlsTrust", if (trusted) "fixture-ca-only" else "platform-default")
                .put("hostnameVerifier", "platform-unmodified")
                .put("connections", JSONArray().apply { trace.connections.forEach {
                    put(JSONObject().put("networkHandle", it.networkHandle).put("url", it.url))
                } })
                .put("responses", JSONArray().apply { trace.responses.forEach {
                    put(JSONObject().put("method", it.method).put("status", it.status).put("cipherSuite", it.cipher).put("peerCertificateSha256", it.peerSha256))
                } })
                .put("failureTypes", JSONArray(trace.failures))
            reportFile.writeText(json.toString(2))
            android.util.Log.i("E1_HTTPS_REPORT", json.toString())
        }

        override fun close() {
            try { row?.let { context.contentResolver.delete(it, null, null) } }
            finally {
                try { endpoint?.let { WebDavCredentialStore(context).clear(it) } }
                finally { base.deleteDatabase(database); directory.deleteRecursively() }
            }
        }
    }

    private data class ConnectionRecord(val networkHandle: String, val url: String)
    private data class ResponseRecord(val method: String, val status: Int, val cipher: String, val peerSha256: String)
    private class Trace {
        val connections = java.util.Collections.synchronizedList(mutableListOf<ConnectionRecord>())
        val responses = java.util.Collections.synchronizedList(mutableListOf<ResponseRecord>())
        val failures = java.util.Collections.synchronizedList(mutableListOf<String>())
    }

    /** Delegates every transport operation to the actual network-bound HTTPS connection. */
    private class TrackedConnection(private val real: HttpsURLConnection, private val trace: Trace) : HttpsURLConnection(real.url) {
        private fun <T> observed(action: () -> T): T = try { action() } catch (failure: Exception) {
            var cause: Throwable? = failure
            val types = mutableListOf<String>()
            repeat(8) { cause?.let { types.add(it.javaClass.name); cause = it.cause } }
            trace.failures.add(types.joinToString(" -> "))
            throw failure
        }
        override fun getResponseCode(): Int = observed {
            val status = real.responseCode
            trace.responses.add(ResponseRecord(real.requestMethod, status, real.cipherSuite, hex(MessageDigest.getInstance("SHA-256").digest(real.serverCertificates.first().encoded))))
            status
        }
        override fun getOutputStream(): OutputStream = observed { real.outputStream }
        override fun getInputStream(): InputStream = observed { real.inputStream }
        override fun getHeaderFields(): Map<String, List<String>> = real.headerFields
        override fun setRequestMethod(method: String) { real.requestMethod = method }
        override fun getRequestMethod(): String = real.requestMethod
        override fun setRequestProperty(key: String, value: String) { real.setRequestProperty(key, value) }
        override fun getRequestProperty(key: String): String? = real.getRequestProperty(key)
        override fun setInstanceFollowRedirects(follow: Boolean) { real.instanceFollowRedirects = follow }
        override fun getInstanceFollowRedirects(): Boolean = real.instanceFollowRedirects
        override fun setUseCaches(value: Boolean) { real.useCaches = value }
        override fun getUseCaches(): Boolean = real.useCaches
        override fun setDoOutput(value: Boolean) { real.doOutput = value }
        override fun getDoOutput(): Boolean = real.doOutput
        override fun setConnectTimeout(value: Int) { real.connectTimeout = value }
        override fun getConnectTimeout(): Int = real.connectTimeout
        override fun setReadTimeout(value: Int) { real.readTimeout = value }
        override fun getReadTimeout(): Int = real.readTimeout
        override fun setFixedLengthStreamingMode(value: Long) { real.setFixedLengthStreamingMode(value) }
        override fun setFixedLengthStreamingMode(value: Int) { real.setFixedLengthStreamingMode(value) }
        override fun connect() = observed { real.connect() }
        override fun disconnect() { real.disconnect() }
        override fun usingProxy(): Boolean = real.usingProxy()
        override fun getCipherSuite(): String = real.cipherSuite
        override fun getLocalCertificates(): Array<Certificate>? = real.localCertificates
        override fun getServerCertificates(): Array<Certificate> = real.serverCertificates
        override fun getPeerPrincipal(): Principal = real.peerPrincipal
        override fun getLocalPrincipal(): Principal? = real.localPrincipal
    }

    private companion object {
        fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
