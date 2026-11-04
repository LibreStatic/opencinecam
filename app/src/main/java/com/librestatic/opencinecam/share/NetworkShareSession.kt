/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.share

import java.net.BindException

/** Why the network drive is not running. */
enum class NetworkShareStopReason {
    /** The operator turned it off (screen switch or notification). */
    OPERATOR,
    /** 15 minutes without an authenticated request. */
    IDLE,
    /** No Wi-Fi, hotspot or USB tethering address is left. */
    NO_NETWORK,
    /** A take started recording, or was recording when asked to start. */
    RECORDING,
    /** No port of the range could be bound. */
    PORT_BUSY,
    /** Android ended the foreground service (its data-sync time limit). */
    TIMEOUT,
    /** Anything else that kept it from starting. */
    FAILED,
}

/** What the share screen shows. */
sealed interface NetworkShareState {
    data class Stopped(val reason: NetworkShareStopReason? = null) : NetworkShareState
    data object Starting : NetworkShareState
    data class Running(val urls: List<String>, val user: String, val password: String) : NetworkShareState {
        override fun toString(): String = "Running(urls=$urls, user=$user, password=<redacted>)"
    }
}

/**
 * One run of the network drive, free of Android: binds [WebDavServer] on the interfaces and
 * addresses [addresses] reports at [start], and only those for its whole life. [tick] stops it
 * when a take records ([takeActive]), when any of those bindings disappears or changes
 * (NO_NETWORK: a network that appears later is never bound; the operator starts the share
 * again to pick it up), or [IDLE_STOP_MS] after the last real transfer. The foreground
 * service calls [tick] every few seconds and [stop] on the operator's request.
 */
internal class NetworkShareSession(
    tree: ShareTree,
    val credentials: ShareCredentials,
    private val addresses: () -> List<ShareBinding>,
    private val takeActive: () -> Boolean,
    private val clock: ShareClock = ShareClock.MONOTONIC,
    private val ports: IntRange = DEFAULT_PORTS,
    limits: ShareServerLimits = ShareServerLimits(),
    log: (String) -> Unit = {},
    private val idleStopMs: Long = IDLE_STOP_MS,
    private val onStopped: (NetworkShareStopReason) -> Unit = {},
) {
    private val server = WebDavServer(tree, DigestAuthenticator(credentials, clock), clock, limits, log = log)
    private val lock = Any()
    private var stoppedReason: NetworkShareStopReason? = null
    private var started = false
    /** The bindings of [start]; the share never listens anywhere else. */
    private var original: Set<ShareBinding> = emptySet()

    sealed interface Start {
        data class Running(val port: Int, val urls: List<String>) : Start
        data class Refused(val reason: NetworkShareStopReason) : Start
    }

    /** Binds the listeners, or refuses while recording, with no address, or with every port busy. */
    fun start(): Start = synchronized(lock) {
        check(!started && stoppedReason == null) { "a session starts once" }
        if (takeActive()) return refuse(NetworkShareStopReason.RECORDING)
        val bindTo = addresses().distinctBy { it.address }
        if (bindTo.isEmpty()) return refuse(NetworkShareStopReason.NO_NETWORK)
        try {
            server.bind(bindTo.map { it.address }, ports)
        } catch (_: BindException) {
            return refuse(NetworkShareStopReason.PORT_BUSY)
        } catch (_: java.io.IOException) {
            return refuse(NetworkShareStopReason.FAILED)
        }
        started = true
        original = bindTo.toSet()
        Start.Running(server.port, urls())
    }

    private fun refuse(reason: NetworkShareStopReason): Start {
        stoppedReason = reason
        server.close()
        return Start.Refused(reason)
    }

    /** `http://<ip>:<port>/` for every address listened on. */
    fun urls(): List<String> = server.boundAddresses().map { "http://${it.hostAddress}:${server.port}/" }

    val running: Boolean get() = synchronized(lock) { started && stoppedReason == null }

    /** Applies the stop rules; returns the reason when this call stopped the session. */
    fun tick(): NetworkShareStopReason? {
        if (!running) return null
        if (takeActive()) return stop(NetworkShareStopReason.RECORDING)
        // Every original binding must still be there, on the same interface; new ones are ignored.
        if (!addresses().toSet().containsAll(original)) return stop(NetworkShareStopReason.NO_NETWORK)
        if (clock.nowMs() - server.lastActivityMs() >= idleStopMs) return stop(NetworkShareStopReason.IDLE)
        return null
    }

    /** Closes every listener and connection; the first reason wins. */
    fun stop(reason: NetworkShareStopReason): NetworkShareStopReason? {
        synchronized(lock) {
            if (stoppedReason != null) return null
            stoppedReason = reason
        }
        server.close()
        onStopped(reason)
        return reason
    }

    companion object {
        const val DEFAULT_PORT = 8642
        val DEFAULT_PORTS = DEFAULT_PORT..DEFAULT_PORT + 9
        const val IDLE_STOP_MS = 15 * 60_000L
    }
}
