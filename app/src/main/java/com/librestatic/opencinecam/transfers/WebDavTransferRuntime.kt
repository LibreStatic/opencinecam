/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.transfers

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Test seam supplies network identity/events, never credentials or a production trust override. */
internal interface WebDavRuntimeNetworkSource : AutoCloseable {
    fun start(changed: (Network?, WebDavNetwork) -> Unit)
}

class CaptureTransferReservation internal constructor(
    private val finished: CompletableDeferred<Unit>?,
    private val receipt: WebDavRetirementReceipt,
    private val release: () -> Unit,
) : AutoCloseable {
    private var closed = false
    val isRetired: Boolean get() = (finished == null || finished.isCompleted) && receipt.isRetired
    suspend fun awaitRetired() {
        finished?.await()
        withContext(Dispatchers.IO) {
            // A timeout is merely another wait, never permission to start capture.
            while (!receipt.awaitRetired(1, TimeUnit.DAYS)) { /* Wait for actual cleanup. */ }
        }
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        release()
    }
}

/** One explicit bundle batch. No scheduler, historical enrollment, automatic retry or remote delete. */
class WebDavTransferRuntime internal constructor(
    context: Context,
    private val databaseName: String = "webdav-outbox.db",
    private val settingsFactory: (Context) -> WebDavQueueSettings = { WebDavQueueSettings.get(it) },
    networkSource: WebDavRuntimeNetworkSource? = null,
    private val connections: (Network, URI) -> HttpURLConnection = { network, uri ->
        require(uri.scheme.equals("https", ignoreCase = true))
        network.openConnection(uri.toURL()) as HttpURLConnection
    },
) {
    private val context = context.applicationContext
    private val gate = Any()
    private val executor = Executors.newSingleThreadExecutor()
    private val cleanup = Executors.newSingleThreadExecutor()
    private val observer = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val networkSource = networkSource ?: AndroidNetworkSource(this.context)
    private val control = WebDavUploadControl()
    private var receipt = control.updatePolicy(WebDavUploadPolicy())
    private val mutable = MutableStateFlow(WebDavTransferUiState())
    val states: StateFlow<WebDavTransferUiState> = mutable.asStateFlow()
    private var settings: WebDavQueueSettings? = null
    private var settingsState = WebDavQueueSettingsState(WebDavQueuePreferences())
    private var network: Network? = null
    private var networkKind = WebDavNetwork.OFFLINE
    private var initializing = false
    private var initialized = false
    private var captures = 0
    private var mediaMutations = 0
    private var mediaMutationFinished: CompletableDeferred<Unit>? = null
    private var work: Work? = null
    private var closed = false
    private val processToken = UUID.randomUUID().toString()

    private class Work(val bundleId: String?) {
        val finished = CompletableDeferred<Unit>()
        @Volatile var stopped: WebDavTransferMessage? = null
    }

    /** All public entry points below only mutate memory/enqueue work on their caller. */
    fun refresh() = start(null)
    fun send(bundleId: String) { requireOutboxUuid(bundleId); start(bundleId) }
    fun cancel() = synchronized(gate) {
        work?.stopped = WebDavTransferMessage.CANCELLED
        receipt = control.cancelAsync(::dispatchCleanup)
        receipt = control.updatePolicyAsync(policyLocked(), ::dispatchCleanup)
        publishLocked(WebDavTransferMessage.CANCELLED)
    }

    fun reserveCapture(): CaptureTransferReservation = synchronized(gate) {
        check(!closed)
        captures++
        val current = work
        current?.stopped = WebDavTransferMessage.WAITING_RECORDING
        receipt = control.updatePolicyAsync(policyLocked(), ::dispatchCleanup)
        publishLocked(WebDavTransferMessage.WAITING_RECORDING)
        CaptureTransferReservation(current?.finished ?: mediaMutationFinished, receipt) {
            synchronized(gate) {
                check(captures > 0)
                captures--
                receipt = control.updatePolicyAsync(policyLocked(), ::dispatchCleanup)
                publishLocked(if (captures > 0) WebDavTransferMessage.WAITING_RECORDING else WebDavTransferMessage.IDLE)
            }
        }
    }

    /** Admit destructive local IO only when no transfer owns a source. Never cancels a worker,
     * initializes the outbox or waits on capture; callers retry after the visible operation ends. */
    fun reserveIdleMediaMutation(): AutoCloseable = synchronized(gate) {
        check(!closed && work == null && receipt.isRetired && captures == 0 && mediaMutations == 0) {
            "Local media changes require idle capture and transfer ownership"
        }
        mediaMutations++
        mediaMutationFinished = CompletableDeferred()
        publishLocked(WebDavTransferMessage.WAITING_MEDIA)
        object : AutoCloseable {
            private var released = false
            override fun close() = synchronized(gate) {
                if (!released) {
                    released = true
                    check(mediaMutations == 1)
                    mediaMutations--
                    mediaMutationFinished?.complete(Unit)
                    mediaMutationFinished = null
                    if (!closed) {
                        receipt = control.updatePolicyAsync(policyLocked(), ::dispatchCleanup)
                        publishLocked(WebDavTransferMessage.IDLE)
                    }
                }
            }
        }
    }

    /** Reflect a committed local-mutation hold without starting settings/network/database IO. */
    internal fun reflectLocalMutation(bundles: List<WebDavOutboxBundle>) = synchronized(gate) {
        check(!closed && mediaMutations == 1 && work == null && receipt.isRetired)
        val updated = mutable.value.bundles.associateBy { it.id }.toMutableMap()
        bundles.forEach { updated[it.id] = it }
        publishLocked(WebDavTransferMessage.WAITING_MEDIA, updated.values.toList())
    }

    private fun start(bundleId: String?) {
        synchronized(gate) {
            if (closed) return
            if (mediaMutations > 0) { publishLocked(WebDavTransferMessage.WAITING_MEDIA); return }
            if (captures > 0) { publishLocked(WebDavTransferMessage.WAITING_RECORDING); return }
            if (work != null || !receipt.isRetired) return
            val admitted = Work(bundleId)
            work = admitted // Register local I/O ownership BEFORE enqueuing it.
            publishLocked(if (bundleId == null) WebDavTransferMessage.IDLE else WebDavTransferMessage.SENDING)
            executor.execute { execute(admitted) }
        }
    }

    private fun dispatchCleanup(action: () -> Unit) {
        cleanup.execute {
            try { action() } finally {
                synchronized(gate) { publishLocked(mutable.value.message) }
            }
        }
    }

    private fun policyLocked() = WebDavUploadPolicy(
        enabled = !settingsState.storageFailed && settingsState.preferences.enabled && work?.stopped == null,
        allowCellular = settingsState.preferences.allowCellular,
        recording = captures > 0 || mediaMutations > 0,
        network = networkKind,
    )

    private fun publishLocked(message: WebDavTransferMessage, bundles: List<WebDavOutboxBundle>? = null) {
        val visible = when {
            mediaMutations > 0 -> WebDavTransferMessage.WAITING_MEDIA
            captures > 0 -> WebDavTransferMessage.WAITING_RECORDING
            message == WebDavTransferMessage.WAITING_RECORDING -> WebDavTransferMessage.CANCELLED
            else -> message
        }
        mutable.value = WebDavTransferUiState(work != null || !receipt.isRetired,
            bundles ?: mutable.value.bundles, visible, work?.bundleId)
    }

    private fun checkWork(current: Work) = synchronized(gate) {
        if (closed || work !== current || captures > 0 || mediaMutations > 0 || current.stopped != null) throw BatchStopped()
    }

    private fun initialize(current: Work) {
        checkWork(current)
        if (initialized) return
        synchronized(gate) { initializing = true }
        try {
            val repository = settingsFactory(context)
            checkWork(current)
            synchronized(gate) { settings = repository; settingsState = repository.states.value }
            networkSource.start networkChanged@ { selected, kind ->
                synchronized(gate) {
                    if (closed) return@networkChanged
                    val changed = network != selected || networkKind != kind
                    network = selected; networkKind = kind
                    if (changed && !initializing) {
                        work?.stopped = WebDavTransferMessage.NETWORK_UNAVAILABLE
                        receipt = control.cancelAsync(::dispatchCleanup)
                    }
                    receipt = control.updatePolicyAsync(policyLocked(), ::dispatchCleanup)
                    if (changed && !initializing) publishLocked(WebDavTransferMessage.NETWORK_UNAVAILABLE)
                }
            }
            observer.launch {
                repository.states.collect {
                    synchronized(gate) {
                        if (closed) return@collect
                        val fresh = repository.states.value
                        if (fresh != settingsState) {
                            settingsState = fresh
                            if (!initializing) {
                                work?.stopped = if (fresh.storageFailed) WebDavTransferMessage.ERROR else WebDavTransferMessage.DISABLED
                                receipt = control.cancelAsync(::dispatchCleanup)
                            }
                            receipt = control.updatePolicyAsync(policyLocked(), ::dispatchCleanup)
                            if (!initializing) publishLocked(if (fresh.storageFailed) WebDavTransferMessage.ERROR else WebDavTransferMessage.DISABLED)
                        }
                    }
                }
            }
            initialized = true
        } finally { synchronized(gate) { initializing = false } }
        checkWork(current)
    }

    private fun execute(current: Work) {
        var message = WebDavTransferMessage.ERROR
        try {
            initialize(current)
            checkWork(current)
            val repository = requireNotNull(settings)
            val fresh = repository.snapshotForAdmission()
            synchronized(gate) {
                if (fresh != settingsState) { settingsState = fresh; current.stopped = WebDavTransferMessage.DISABLED }
                receipt = control.updatePolicyAsync(policyLocked(), ::dispatchCleanup)
            }
            checkWork(current)
            WebDavSqliteOutbox(context, databaseName).use { store ->
                checkWork(current)
                if (current.bundleId == null) {
                    val bundles = store.list(1024).filter { it.sealed }
                    checkWork(current)
                    synchronized(gate) { publishLocked(WebDavTransferMessage.IDLE, bundles) }
                    message = WebDavTransferMessage.IDLE
                } else {
                    message = sendOwned(current, repository, store)
                    checkWork(current)
                    val bundles = store.list(1024).filter { it.sealed }
                    checkWork(current)
                    synchronized(gate) { publishLocked(message, bundles) }
                }
            }
        } catch (_: BatchStopped) { message = current.stopped ?: WebDavTransferMessage.CANCELLED }
        catch (_: Exception) { message = current.stopped ?: WebDavTransferMessage.ERROR }
        finally {
            synchronized(gate) {
                current.finished.complete(Unit) // SQLite/FD/worker finally has actually returned.
                if (work === current) work = null
                publishLocked(current.stopped ?: message)
            }
        }
    }

    private fun sendOwned(current: Work, repository: WebDavQueueSettings, store: WebDavOutboxStore): WebDavTransferMessage {
        val selected: Network
        val admission: WebDavOutboxAdmission
        synchronized(gate) {
            checkWork(current)
            val policy = policyLocked()
            policy.stopReason()?.let { return stopMessage(it) }
            selected = network ?: return WebDavTransferMessage.NETWORK_UNAVAILABLE
            admission = WebDavOutboxAdmission(processToken, settingsState.preferences.revision, policy)
            receipt = control.updatePolicyAsync(policy, ::dispatchCleanup)
        }
        val id = requireNotNull(current.bundleId)
        val bundle = store.load(id) ?: return WebDavTransferMessage.ERROR
        checkWork(current)
        if (!bundle.sealed) return WebDavTransferMessage.ERROR
        val enrollment = CaptureTransferEnrollmentFile(context).load(id) ?: return WebDavTransferMessage.ERROR
        checkWork(current)
        if (enrollment.endpointId != bundle.endpointId || enrollment.endpointRevision != bundle.endpointRevision) return WebDavTransferMessage.ERROR
        val source = WebDavMediaStoreArtifactSource(context.contentResolver)
        val factory: (URI) -> HttpURLConnection = { uri ->
            synchronized(gate) {
                checkWork(current)
                check(network == selected && policyLocked() == admission.policy)
            }
            require(uri.scheme.equals("https", ignoreCase = true))
            connections(selected, uri)
        }
        val worker = WebDavArtifactWorker(store, source, DefaultWebDavArtifactHasher(source),
            WebDavStoredDestinationResolver(repository, WebDavCredentialStore(context)), control,
            WebDavUploadTransport(factory), WebDavRemoteReconciler(factory))
        for (artifactId in bundle.artifacts.map { it.spec.id }) {
            checkWork(current)
            val before = requireNotNull(store.load(id)).artifacts.single { it.spec.id == artifactId }
            if (before.state == WebDavArtifactState.VERIFIED) continue
            if (before.state == WebDavArtifactState.CONFLICT) return WebDavTransferMessage.CONFLICT
            if (before.attempt != null) return WebDavTransferMessage.UNCERTAIN
            synchronized(gate) { publishLocked(if (before.state == WebDavArtifactState.UNCERTAIN) WebDavTransferMessage.VERIFYING else WebDavTransferMessage.SENDING) }
            val first = worker.runOne(id, artifactId, admission)
            checkWork(current)
            val result = if (first == WebDavWorkerResult.Applied(WebDavWorkerTransition.PUT_ACKNOWLEDGED)) {
                synchronized(gate) { publishLocked(WebDavTransferMessage.VERIFYING) }
                worker.runOne(id, artifactId, admission) // Exactly one verification after an acknowledged PUT.
            } else first
            checkWork(current)
            when (result) {
                is WebDavWorkerResult.Held -> return if (result.reason == WebDavWorkerHold.AUTHENTICATION) WebDavTransferMessage.AUTHENTICATION else WebDavTransferMessage.ERROR
                is WebDavWorkerResult.Stopped -> return stopMessage(result.reason)
                WebDavWorkerResult.Attention, WebDavWorkerResult.Stale -> return WebDavTransferMessage.ERROR
                is WebDavWorkerResult.Applied -> Unit
            }
            val after = requireNotNull(store.load(id)).artifacts.single { it.spec.id == artifactId }
            when (after.state) {
                WebDavArtifactState.VERIFIED -> Unit
                WebDavArtifactState.CONFLICT -> return WebDavTransferMessage.CONFLICT
                WebDavArtifactState.SOURCE_UNAVAILABLE -> return WebDavTransferMessage.SOURCE_UNAVAILABLE
                else -> return WebDavTransferMessage.UNCERTAIN // Includes qualified 404 -> QUEUED; no automatic PUT.
            }
        }
        return if (store.load(id)?.state == WebDavBundleState.COMPLETE) WebDavTransferMessage.COMPLETE else WebDavTransferMessage.UNCERTAIN
    }

    private fun stopMessage(reason: WebDavStopReason) = when (reason) {
        WebDavStopReason.RECORDING -> WebDavTransferMessage.WAITING_RECORDING
        WebDavStopReason.DISABLED -> WebDavTransferMessage.DISABLED
        WebDavStopReason.CELLULAR_CONSENT_REQUIRED -> WebDavTransferMessage.CELLULAR_CONSENT_REQUIRED
        WebDavStopReason.NETWORK_UNAVAILABLE -> WebDavTransferMessage.NETWORK_UNAVAILABLE
        WebDavStopReason.CANCELLED -> WebDavTransferMessage.CANCELLED
        WebDavStopReason.BUSY -> WebDavTransferMessage.UNCERTAIN
    }

    internal fun closeForTest() {
        synchronized(gate) { check(work == null && receipt.isRetired && captures == 0 && mediaMutations == 0); closed = true }
        observer.cancel()
        networkSource.close()
        executor.shutdown()
        cleanup.shutdown()
    }

    private class BatchStopped : RuntimeException()

    companion object {
        @Volatile private var instance: WebDavTransferRuntime? = null
        @Synchronized fun get(context: Context): WebDavTransferRuntime = instance ?: WebDavTransferRuntime(context).also { instance = it }
    }
}

private class AndroidNetworkSource(private val context: Context) : WebDavRuntimeNetworkSource {
    private var manager: ConnectivityManager? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var selected: Network? = null
    @Synchronized override fun start(changed: (Network?, WebDavNetwork) -> Unit) {
        if (callback != null) return
        val service = context.getSystemService(ConnectivityManager::class.java)
        manager = service
        val listener = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = synchronized(this@AndroidNetworkSource) {
                if (selected != network) { selected = network; changed(network, WebDavNetwork.OFFLINE) }
            }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = synchronized(this@AndroidNetworkSource) {
                if (selected == network) changed(network, classify(capabilities))
            }
            override fun onLost(network: Network) = synchronized(this@AndroidNetworkSource) {
                if (selected == network) { selected = null; changed(null, WebDavNetwork.OFFLINE) }
            }
        }
        service.registerDefaultNetworkCallback(listener)
        callback = listener
        selected = service.activeNetwork
        changed(selected, selected?.let { service.getNetworkCapabilities(it) }?.let(::classify) ?: WebDavNetwork.OFFLINE)
    }
    @Synchronized override fun close() { callback?.let { manager?.unregisterNetworkCallback(it) }; callback = null }
    private fun classify(value: NetworkCapabilities): WebDavNetwork = classifyWebDavNetwork(value)
}

internal fun classifyWebDavNetwork(value: NetworkCapabilities): WebDavNetwork = classifyWebDavNetwork(
    wifi = value.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
    cellular = value.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
    vpn = value.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
    internet = value.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
    validated = value.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
)

internal fun classifyWebDavNetwork(
    wifi: Boolean = false,
    cellular: Boolean = false,
    vpn: Boolean = false,
    internet: Boolean = false,
    validated: Boolean = false,
): WebDavNetwork {
    if (vpn) return WebDavNetwork.OTHER
    // A LAN-only Wi-Fi connection need not pass Android's public-Internet validation.
    if (wifi && !cellular) return WebDavNetwork.WIFI
    if (!internet || !validated) return WebDavNetwork.OFFLINE
    if (cellular) return WebDavNetwork.CELLULAR
    return WebDavNetwork.OTHER
}
