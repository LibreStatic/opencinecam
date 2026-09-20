/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.content.Context
import android.net.Network
import android.net.Uri
import android.util.AtomicFile
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.service.CaptureService
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
import javax.net.ssl.TrustManagerFactory
import org.json.JSONObject
import org.junit.Assert.*

/** Test-only CA on real Android Network connections; production creates every persisted artifact. */
internal class E1ServiceHttpsFixture(private val context: Context, private val lanOptIn: Boolean = false) {
    private val originalDefaultFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
    private val originalDefaultVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
    val fixtureId = UUID.randomUUID().toString()
    private val prefix = "/takes/$fixtureId/"
    private val settings = WebDavQueueSettings.get(context)
    private val previous = settings.snapshotForAdmission().also { check(!it.storageFailed) }
    private val settingsFile = File(context.noBackupFilesDir, "webdav-queue-settings.json")
    private val previousBytes = if (settingsFile.exists()) AtomicFile(settingsFile).readFully() else null
    private val previousEnrollment = enrollmentIds()
    private var endpoint: String? = null
    private var service: Any? = null
    private var previousRuntime: Any? = null
    private val runtimeField = CaptureService::class.java.getDeclaredField("transferRuntime").apply { isAccessible = true }
    lateinit var runtime: WebDavTransferRuntime
        private set
    private var receipt: CapturePublicationReceipt? = null
    private var admitted: String? = null
    var clicked: String? = null
        private set
    val bundleId: String get() = requireNotNull(receipt).bundleId
    private data class Local(val snapshot: WebDavClipSnapshot, val sha256: String)
    private val before = linkedMapOf<String, Local>()
    private val trace = Trace()
    private val reportFile = File(context.cacheDir, "e1-service-https.jsonl")
    private val socketFactory by lazy {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val ca = assets.open("e1-tls-ca.pem").use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
        val trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("e1-fixture-ca", ca) }
        val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
        SSLContext.getInstance("TLS").apply { init(null, managers.trustManagers, null) }.socketFactory
    }

    fun prepare() {
        endpoint = settings.save("https://10.0.2.2:18443$prefix", true, false, ignoreTlsErrors = lanOptIn).activeEndpointId
        WebDavCredentialStore(context).save(requireNotNull(endpoint), "e1-operator", "e1-fixture-secret")
        runtime = WebDavTransferRuntime(context, connections = ::open)
        reportFile.writeText("")
    }

    fun inject(owner: CaptureService.LocalBinder) {
        val outer = owner.javaClass.getDeclaredField("this\$0").apply { isAccessible = true }.get(owner)
        service = outer
        previousRuntime = runtimeField.get(outer)
        runtimeField.set(outer, runtime)
    }

    private fun enrollmentIds(): Set<String> = File(context.noBackupFilesDir, "capture-transfer-enrollment")
        .listFiles().orEmpty().filter { it.name.endsWith(".json") }.map { it.name.removeSuffix(".json") }.toSet()

    fun observeAdmission() {
        admitted = (enrollmentIds() - previousEnrollment).single()
        val enrollment = requireNotNull(CaptureTransferEnrollmentFile(context).load(requireNotNull(admitted)))
        assertEquals(endpoint, enrollment.endpointId)
        assertNull("Production enrollment precedes publication", CapturePublicationJournal.load(context, enrollment.bundleId))
        assertTrue("No upload before explicit UI action", trace.connections.isEmpty())
    }

    fun observePublication(value: CapturePublicationReceipt) {
        receipt = value
        assertEquals(admitted, value.bundleId)
        assertEquals(CapturePublicationState.COMMITTED, value.state)
        val enrollment = requireNotNull(CaptureTransferEnrollmentFile(context).load(value.bundleId))
        assertEquals(endpoint, enrollment.endpointId)
        WebDavSqliteOutbox(context).use { store ->
            val bundle = requireNotNull(store.load(value.bundleId))
            assertTrue(bundle.sealed)
            assertEquals(endpoint, bundle.endpointId)
            assertEquals(value.artifacts.map { it.uri }.toSet(), bundle.artifacts.map { it.spec.sourceUri }.toSet())
            assertEquals(value.artifacts.size, bundle.artifacts.size)
            assertTrue(bundle.artifacts.all { it.state == WebDavArtifactState.QUEUED && it.attempt == null })
            value.artifacts.forEach { artifact ->
                val local = inspect(artifact)
                assertEquals(bundle.artifacts.single { it.spec.sourceUri == artifact.uri }.spec.sizeBytes, local.snapshot.sizeBytes)
                before[artifact.uri] = local
            }
        }
        assertTrue("Production publication must not trigger network upload", trace.connections.isEmpty())
    }

    @Composable fun Panel() {
        val state = runtime.states.collectAsState().value
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            WebDavTransferSettingsSection(state, runtime::refresh, { clicked = it; runtime.send(it) }, runtime::cancel)
        }
    }

    fun assertCompleteAndExport() {
        val publication = requireNotNull(receipt)
        val state = runtime.states.value
        assertSame(originalDefaultFactory, HttpsURLConnection.getDefaultSSLSocketFactory())
        assertSame(originalDefaultVerifier, HttpsURLConnection.getDefaultHostnameVerifier())
        if (lanOptIn) {
            assertTrue(settings.snapshotForAdmission().preferences.activeEndpoint!!.ignoreTlsErrors)
            val untouched = java.net.URL("https://10.0.2.2:18443/").openConnection() as HttpsURLConnection
            assertSame(originalDefaultFactory, untouched.sslSocketFactory)
            assertSame(originalDefaultVerifier, untouched.hostnameVerifier)
            untouched.disconnect()
        }
        assertFalse(state.busy)
        assertEquals(WebDavTransferMessage.COMPLETE, state.message)
        assertEquals(publication.bundleId, clicked)
        val bundle = WebDavSqliteOutbox(context).use { requireNotNull(it.load(publication.bundleId)) }
        assertEquals(WebDavBundleState.COMPLETE, bundle.state)
        assertEquals(publication.artifacts.size, bundle.artifacts.size)
        val responses = synchronized(trace.responses) { trace.responses.toList() }
        assertEquals(bundle.artifacts.size * 2, responses.size)
        assertTrue(trace.failures.toString(), trace.failures.isEmpty())
        assertEquals("One actual Android selected network", 1, responses.map { it.networkHandle }.toSet().size)
        for (artifact in bundle.artifacts) {
            val prepared = publication.artifacts.single { it.uri == artifact.spec.sourceUri }
            val original = requireNotNull(before[prepared.uri])
            assertEquals(original, inspect(prepared))
            assertEquals(WebDavArtifactState.VERIFIED, artifact.state)
            assertEquals(original.sha256, artifact.sha256)
            val path = prefix + artifact.spec.remoteName
            val actual = responses.filter { it.remotePath == path }
            assertEquals(listOf("PUT", "GET"), actual.map { it.method })
            assertEquals(listOf(201, 200), actual.map { it.status })
            actual.forEach {
                assertTrue(it.cipherSuite.isNotBlank()); assertEquals(64, it.peerCertificateSha256.length)
                assertTrue(it.networkHandle.toLong() != 0L)
            }
            requireNotNull(context.contentResolver.openInputStream(Uri.parse(prepared.uri))).use { input ->
                File(context.cacheDir, "e1-service-$fixtureId-${artifact.spec.remoteName}").outputStream().use { input.copyTo(it) }
            }
            report(JSONObject().put("kind", "artifact").put("fixtureId", fixtureId).put("bundleId", bundle.id)
                .put("role", artifact.spec.role.name).put("sourceUri", prepared.uri).put("remotePath", path)
                .put("sha256", original.sha256).put("sizeBytes", original.snapshot.sizeBytes)
                .put("localSourceVerifiedUnchanged", true).put("artifactState", artifact.state.name))
        }
        responses.forEach { report(JSONObject().put("kind", "response").put("bundleId", bundle.id)
            .put("method", it.method).put("status", it.status).put("remotePath", it.remotePath)
            .put("networkHandle", it.networkHandle).put("cipherSuite", it.cipherSuite)
            .put("peerCertificateSha256", it.peerCertificateSha256)) }
        report(JSONObject().put("kind", "summary").put("bundleId", bundle.id).put("artifactCount", bundle.artifacts.size)
            .put("message", state.message.name).put("bundleState", bundle.state.name).put("fixtureId", fixtureId)
            .put("clickedBundleId", clicked).put("productionEnrollmentAndPublication", true)
            .put("networkSource", "AndroidDefaultNetworkSource").put("tlsTrust", if (lanOptIn) "endpoint-lan-opt-in" else "fixture-ca-only")
            .put("hostnameVerifier", if (lanOptIn) "endpoint-lan-opt-in" else "platform-unmodified").put("encodedFrames", 3).put("decodedFrame", true))
    }

    private fun report(json: JSONObject) {
        reportFile.appendText(json.toString() + "\n")
        android.util.Log.i("E1_SERVICE_HTTPS", json.toString())
    }

    private fun inspect(artifact: PreparedCaptureArtifact): Local {
        val probe = MediaStorePreparedArtifactProbe(context.contentResolver)
        val snapshot = probe.inspect(artifact, false)
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        requireNotNull(context.contentResolver.openInputStream(Uri.parse(artifact.uri))).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                check(count > 0)
                bytes = Math.addExact(bytes, count.toLong())
                check(bytes <= snapshot.sizeBytes)
                digest.update(buffer, 0, count)
            }
        }
        assertEquals(snapshot.sizeBytes, bytes)
        assertEquals(snapshot, probe.inspect(artifact, false))
        return Local(snapshot, hex(digest.digest()))
    }

    private fun open(network: Network, uri: URI): HttpURLConnection {
        check(uri.scheme == "https" && uri.host == "10.0.2.2" && uri.port == 18443 && uri.rawPath.startsWith(prefix))
        check(uri.rawPath.removePrefix(prefix).matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,240}")))
        val real = network.openConnection(uri.toURL()) as HttpsURLConnection
        val verifier = real.hostnameVerifier
        if (!lanOptIn) real.sslSocketFactory = socketFactory
        assertSame(verifier, real.hostnameVerifier)
        trace.connections.add(uri.rawPath)
        return TrackedConnection(real, trace, uri.rawPath, network.networkHandle.toString())
    }

    /** Caller waits for real recording retirement and runtime idle before invoking this cleanup. */
    fun closeRetired() {
        try {
            if (::runtime.isInitialized) {
                service?.let { runtimeField.set(it, previousRuntime) }
                runtime.closeForTest()
            }
        } finally {
            try { endpoint?.let { WebDavCredentialStore(context).clear(it) } }
            finally {
                val atomic = AtomicFile(settingsFile)
                val bytes = previousBytes
                if (bytes == null) atomic.delete() else {
                    val output = atomic.startWrite()
                    try { output.write(bytes); atomic.finishWrite(output) }
                    catch (failure: Throwable) { atomic.failWrite(output); throw failure }
                }
                settings.reload()
                assertEquals(previous, settings.states.value)
            }
        }
    }

    private data class ResponseRecord(val method: String, val status: Int, val remotePath: String,
        val networkHandle: String, val cipherSuite: String, val peerCertificateSha256: String)
    private class Trace {
        val connections = java.util.Collections.synchronizedList(mutableListOf<String>())
        val responses = java.util.Collections.synchronizedList(mutableListOf<ResponseRecord>())
        val failures = java.util.Collections.synchronizedList(mutableListOf<String>())
    }

    /** Delegates every transport operation to the actual network-bound HTTPS connection. */
    private class TrackedConnection(private val real: HttpsURLConnection, private val trace: Trace, private val path: String, private val networkHandle: String) : HttpsURLConnection(real.url) {
        private fun <T> observed(action: () -> T): T = try { action() } catch (failure: Exception) {
            var cause: Throwable? = failure
            val types = mutableListOf<String>()
            repeat(8) { cause?.let { types.add(it.javaClass.name); cause = it.cause } }
            trace.failures.add(types.joinToString(" -> "))
            throw failure
        }
        override fun getResponseCode(): Int = observed {
            val status = real.responseCode
            trace.responses.add(ResponseRecord(real.requestMethod, status, path, networkHandle, real.cipherSuite, hex(MessageDigest.getInstance("SHA-256").digest(real.serverCertificates.first().encoded))))
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
        override fun setSSLSocketFactory(value: javax.net.ssl.SSLSocketFactory) { real.sslSocketFactory = value }
        override fun getSSLSocketFactory(): javax.net.ssl.SSLSocketFactory = real.sslSocketFactory
        override fun setHostnameVerifier(value: javax.net.ssl.HostnameVerifier) { real.hostnameVerifier = value }
        override fun getHostnameVerifier(): javax.net.ssl.HostnameVerifier = real.hostnameVerifier
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
