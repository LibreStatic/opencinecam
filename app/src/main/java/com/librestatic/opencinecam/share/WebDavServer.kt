/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.share

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.PushbackInputStream
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.channels.WritableByteChannel
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Connection and size limits; the defaults are the production values. */
internal data class ShareServerLimits(
    /** Concurrent connections, one worker each; one more is answered 503. */
    val workers: Int = 16,
    /** A request head must arrive within this, and no read may stall longer. */
    val readTimeoutMs: Int = 30_000,
    /** An idle keep-alive connection is closed after this. */
    val keepAliveIdleMs: Int = 15_000,
    /** A response write that makes no progress for this long closes the connection. */
    val writeStallMs: Long = 60_000,
    /** Largest PROPFIND body read (and ignored). */
    val maxPropfindBody: Int = 64 * 1024,
    /** Requests served on one connection before it is closed. */
    val maxRequestsPerConnection: Int = 10_000,
    /** Concurrent connections from one client address that has not authenticated lately; more are answered 503. */
    val connectionsPerClient: Int = 3,
    /** After an address authenticates, only [workers] caps its connections for this long (desktop clients open several). */
    val trustedClientMs: Long = 15 * 60_000L,
    /** Head timeout (first byte and whole head) until a request on the connection has authenticated. */
    val unauthenticatedTimeoutMs: Int = 10_000,
    /** Consecutive 401s on one connection before it is closed. */
    val maxConsecutiveChallenges: Int = 3,
)

/**
 * The read-only WebDAV class 1 server of the network drive (OCC-PLAN-071 §8, route 3):
 * hand-rolled HTTP/1.1 over [ServerSocket], one listener per given address (never the
 * wildcard), a bounded worker pool and strict limits ([ShareServerLimits], [HttpRequestReader]).
 *
 * Every request is authenticated by [auth] (Digest) before anything else, OPTIONS included,
 * and must name one of the bound addresses in `Host` (no DNS rebinding). Methods: OPTIONS,
 * PROPFIND (Depth 0/1; infinity is 403), GET and HEAD with one byte range; anything else is
 * 405. Paths resolve only by name lookup in [tree].
 *
 * Slow or hostile clients: at most [ShareServerLimits.connectionsPerClient] connections per
 * address until that address has authenticated (then only the worker pool caps it, for
 * [ShareServerLimits.trustedClientMs]), a shorter head timeout until the connection has authenticated, and a connection
 * closes after [ShareServerLimits.maxConsecutiveChallenges] 401s in a row.
 *
 * [lastActivityMs] advances only with real transfers: a GET that sends a body (file or
 * listing, also while it streams) and a Depth 1 PROPFIND of a take folder. OPTIONS, Depth 0
 * PROPFINDs and the root listing do not count, so a mounted desktop polling the share lets it
 * stop when idle. [NetworkShareSession] reads it for the idle stop.
 */
internal class WebDavServer(
    private val tree: ShareTree,
    private val auth: DigestAuthenticator,
    private val clock: ShareClock = ShareClock.MONOTONIC,
    private val limits: ShareServerLimits = ShareServerLimits(),
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    private class Listener(val address: InetAddress, val socket: ServerSocket, val thread: Thread)

    private class Connection(val socket: Socket, val client: String) {
        @Volatile var writing = false
        val lastProgressMs = AtomicLong()
        /** A request on this connection has authenticated: the longer timeouts apply from then on. */
        var authenticated = false
        var consecutiveChallenges = 0
    }

    private val lock = Any()
    private val listeners = LinkedHashMap<InetAddress, Listener>()
    private val connections: MutableSet<Connection> = Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())
    private val workerCount = AtomicInteger()
    private val perClient = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()
    /** Client address → when it last authenticated, on [clock]. */
    private val trusted = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val workers = ThreadPoolExecutor(limits.workers, limits.workers, 30, TimeUnit.SECONDS, SynchronousQueue()) { task ->
        Thread(task, "share-worker-${workerCount.incrementAndGet()}").apply { isDaemon = true }
    }.apply { allowCoreThreadTimeOut(true) }
    private val reaper: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "share-reaper").apply { isDaemon = true }
    }
    private val lastActivity = AtomicLong(clock.nowMs())
    private val streaming = AtomicInteger()
    @Volatile private var closed = false

    /** The port every listener uses; 0 before [bind]. */
    @Volatile var port: Int = 0
        private set

    init {
        reaper.scheduleWithFixedDelay(::reapStalled, 5, 5, TimeUnit.SECONDS)
    }

    /**
     * Binds one listener per address on the first port of [ports] free on all of them. Throws
     * [BindException] when none is; an empty [addresses] is a programming error.
     */
    fun bind(addresses: List<InetAddress>, ports: IntRange) {
        require(addresses.isNotEmpty()) { "no address to bind" }
        require(addresses.none { it.isAnyLocalAddress }) { "never bind the wildcard address" }
        synchronized(lock) {
            check(!closed && listeners.isEmpty()) { "already bound" }
            for (candidate in ports) {
                val opened = mutableListOf<Pair<InetAddress, ServerSocket>>()
                try {
                    for (address in addresses.distinct()) opened += address to openSocket(address, candidate)
                } catch (_: IOException) {
                    opened.forEach { runCatching { it.second.close() } }
                    continue
                }
                port = candidate
                opened.forEach { (address, socket) -> startListener(address, socket) }
                return
            }
        }
        throw BindException("no free port in $ports")
    }

    /** The addresses currently listened on. */
    fun boundAddresses(): List<InetAddress> = synchronized(lock) { listeners.keys.toList() }

    fun lastActivityMs(): Long = if (streaming.get() > 0) clock.nowMs() else lastActivity.get()

    /** Closes every listener and connection; idempotent. */
    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            listeners.values.forEach { runCatching { it.socket.close() } }
            listeners.clear()
        }
        connections.forEach { runCatching { it.socket.close() } }
        workers.shutdownNow()
        reaper.shutdownNow()
    }

    private fun openSocket(address: InetAddress, port: Int): ServerSocket {
        val socket = ServerSocket()
        try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(address, port), 16)
        } catch (error: IOException) {
            runCatching { socket.close() }
            throw error
        }
        return socket
    }

    private fun startListener(address: InetAddress, socket: ServerSocket) {
        val thread = Thread({ acceptLoop(socket) }, "share-accept").apply { isDaemon = true }
        listeners[address] = Listener(address, socket, thread)
        thread.start()
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!closed && !server.isClosed) {
            val socket = try { server.accept() } catch (_: IOException) { break }
            val client = socket.inetAddress?.hostAddress ?: "?"
            val count = perClient.compute(client) { _, current -> (current ?: AtomicInteger()).apply { incrementAndGet() } }!!
            if (count.get() > limits.connectionsPerClient && !isTrusted(client)) {
                releaseClient(client)
                refuseBusy(socket)
                continue
            }
            val connection = Connection(socket, client)
            try {
                workers.execute { serve(connection) }
            } catch (_: RejectedExecutionException) {
                releaseClient(client)
                refuseBusy(socket)
            }
        }
    }

    private fun isTrusted(client: String): Boolean {
        val since = trusted[client] ?: return false
        if (clock.nowMs() - since < limits.trustedClientMs) return true
        trusted.remove(client, since)
        return false
    }

    private fun releaseClient(client: String) {
        perClient.computeIfPresent(client) { _, current -> if (current.decrementAndGet() <= 0) null else current }
    }

    /** Over the connection limit: a short 503 the socket buffer takes without blocking, then close. */
    private fun refuseBusy(socket: Socket) {
        runCatching {
            socket.soTimeout = 1_000
            socket.getOutputStream().write(("HTTP/1.1 503 Service Unavailable\r\nRetry-After: 2\r\nContent-Length: 0\r\n" +
                "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
            socket.getOutputStream().flush()
        }
        runCatching { socket.close() }
    }

    private fun reapStalled() {
        val now = clock.nowMs()
        for (connection in connections) {
            if (connection.writing && now - connection.lastProgressMs.get() > limits.writeStallMs) runCatching { connection.socket.close() }
        }
    }

    private fun serve(connection: Connection) {
        val socket = connection.socket
        connections += connection
        var input: PushbackInputStream? = null
        var peerClosed = false
        try {
            socket.tcpNoDelay = true
            input = PushbackInputStream(BufferedInputStream(socket.getInputStream(), 8192), 1)
            val output = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
            val reader = HttpRequestReader(input)
            val client = connection.client
            var served = 0
            while (!closed && served < limits.maxRequestsPerConnection) {
                // Wait for the next request with the keep-alive timeout, then read it under the request deadline;
                // both are shorter until the connection has authenticated.
                val headTimeout = if (connection.authenticated) limits.readTimeoutMs else minOf(limits.readTimeoutMs, limits.unauthenticatedTimeoutMs)
                socket.soTimeout = when {
                    served == 0 -> headTimeout
                    connection.authenticated -> limits.keepAliveIdleMs
                    else -> minOf(limits.keepAliveIdleMs, headTimeout)
                }
                val first = try { input.read() } catch (_: SocketTimeoutException) { break }
                if (first < 0) { peerClosed = true; break }
                input.unread(first)
                socket.soTimeout = headTimeout
                val deadlineNs = System.nanoTime() + headTimeout * 1_000_000L
                val deadline = { if (System.nanoTime() > deadlineNs) throw SocketTimeoutException("request deadline") }
                val request = try {
                    reader.read(deadline)
                } catch (_: HttpConnectionClosed) {
                    peerClosed = true
                    break
                } catch (error: HttpParseException) {
                    respond(output, error.status, keepAlive = false)
                    break
                } catch (_: SocketTimeoutException) {
                    respond(output, 408, keepAlive = false)
                    break
                }
                served++
                val keep = handle(request, client, socket.localAddress, input, output, connection, deadline)
                output.flush()
                if (!keep) break
            }
        } catch (_: IOException) {
            // The peer went away or a write stalled: nothing more to say on this connection.
        } catch (error: RuntimeException) {
            log("share: connection failed: ${error.javaClass.simpleName}")
        } finally {
            connections -= connection
            releaseClient(connection.client)
            if (!peerClosed && !closed) input?.let { lingerClose(socket, it) }
            runCatching { socket.close() }
        }
    }

    /**
     * Closes our side first and discards what the client still sends for a short while, so an
     * error response is not destroyed by the RST a close over unread request bytes provokes.
     * Bounded in time and bytes: a client cannot hold the worker with it.
     */
    private fun lingerClose(socket: Socket, input: java.io.InputStream) {
        runCatching {
            socket.shutdownOutput()
            socket.soTimeout = LINGER_MS
            val end = System.nanoTime() + LINGER_MS * 1_000_000L
            val discard = ByteArray(8192)
            var total = 0
            while (total < LINGER_MAX_BYTES && System.nanoTime() < end) {
                val n = input.read(discard)
                if (n < 0) break
                total += n
            }
        }
    }

    /** Serves one request; false when the connection must close afterwards. */
    private fun handle(
        request: HttpRequest,
        client: String,
        local: InetAddress,
        input: java.io.InputStream,
        output: OutputStream,
        connection: Connection,
        deadline: () -> Unit,
    ): Boolean {
        var keepAlive = request.keepAlive
        if (!hostMatches(request.header("host"), local)) {
            respond(output, 400, keepAlive = false)
            return false
        }
        val hasBody = request.header("transfer-encoding") != null ||
            (request.header("content-length")?.substringBefore(',')?.trim()?.toLong() ?: 0L) > 0
        val expectsContinue = request.header("expect")?.equals("100-continue", ignoreCase = true) == true

        when (val result = auth.authenticate(client, request.method, request.target, request.header("authorization"))) {
            AuthResult.Ok -> {
                if (trusted.size < MAX_TRUSTED_CLIENTS || trusted.containsKey(client)) trusted[client] = clock.nowMs()
                connection.authenticated = true
                connection.consecutiveChallenges = 0
            }
            is AuthResult.LockedOut -> {
                respond(output, 429, listOf("Retry-After" to result.retryAfterSeconds.toString()), keepAlive = false)
                return false
            }
            else -> {
                // An unauthenticated body is read only when small and already on its way; otherwise the connection closes.
                if (hasBody) {
                    val small = (request.header("content-length")?.substringBefore(',')?.trim()?.toLong() ?: Long.MAX_VALUE) <= limits.maxPropfindBody
                    if (expectsContinue || !small) keepAlive = false else drainRequestBody(request, input, limits.maxPropfindBody, deadline)
                }
                connection.consecutiveChallenges++
                if (connection.consecutiveChallenges >= limits.maxConsecutiveChallenges) keepAlive = false
                val challenge = auth.challenge(stale = result == AuthResult.Stale)
                respond(output, 401, listOf("WWW-Authenticate" to challenge), keepAlive = keepAlive)
                return keepAlive
            }
        }

        val method = request.method
        if (method !in ALLOWED) {
            respond(output, 405, listOf("Allow" to ALLOW), keepAlive = keepAlive && !hasBody)
            return keepAlive && !hasBody
        }
        if (hasBody) {
            if (method != "PROPFIND") {
                respond(output, 400, keepAlive = false)
                return false
            }
            if (expectsContinue && request.minorVersion >= 1) {
                output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.US_ASCII))
                output.flush()
            }
            try {
                drainRequestBody(request, input, limits.maxPropfindBody, deadline)
            } catch (error: HttpParseException) {
                respond(output, error.status, keepAlive = false)
                return false
            }
        }
        if (method == "OPTIONS" && request.target == "*") {
            respond(output, 200, OPTIONS_HEADERS, keepAlive = keepAlive)
            return keepAlive
        }
        val path = try {
            parseSharePath(request.target)
        } catch (error: HttpParseException) {
            respond(output, error.status, keepAlive = keepAlive)
            return keepAlive
        }
        val node = try {
            tree.resolve(path)
        } catch (error: Exception) {
            if (error is InterruptedException) throw error
            return serverError(output, error)
        }
        if (node == null || node is ShareFile && path.trailingSlash) {
            respond(output, 404, keepAlive = keepAlive)
            return keepAlive
        }
        return when (method) {
            "OPTIONS" -> { respond(output, 200, OPTIONS_HEADERS, keepAlive = keepAlive); keepAlive }
            "PROPFIND" -> propfind(request, path, node, output, connection, keepAlive)
            else -> get(request, path, node, output, connection, keepAlive, head = method == "HEAD")
        }
    }

    /** Nothing has been sent for this request yet: a 500, and the connection closes. */
    private fun serverError(output: OutputStream, error: Exception): Boolean {
        log("share: request failed before the response: ${error.javaClass.simpleName}")
        respond(output, 500, keepAlive = false)
        return false
    }

    private fun hostMatches(host: String?, local: InetAddress): Boolean {
        if (host == null) return true // HTTP/1.0 without Host: the parser already demands it of 1.1
        val name = if (host.startsWith("[")) host.substringBefore(']') + "]" else host.substringBefore(':')
        val portPart = host.removePrefix(name).removePrefix(":")
        if (portPart.isNotEmpty() && portPart != port.toString()) return false
        return name.equals(local.hostAddress, ignoreCase = true)
    }

    private fun propfind(request: HttpRequest, path: SharePath, node: ShareNode, output: OutputStream, connection: Connection, keepAlive: Boolean): Boolean {
        val depth = try { parseDepth(request.header("depth")) } catch (error: HttpParseException) {
            respond(output, error.status, keepAlive = keepAlive)
            return keepAlive
        }
        if (depth == null) {
            val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<D:error xmlns:D=\"DAV:\"><D:propfind-finite-depth/></D:error>\n"
            respond(output, 403, listOf("Content-Type" to XML_TYPE), body.toByteArray(Charsets.UTF_8), keepAlive = keepAlive)
            return keepAlive
        }
        val folder = path.segments.firstOrNull()
        val entries = ArrayList<Pair<ShareNode, String?>>()
        val actualFolder = (node as? ShareCollection)?.take?.stem ?: folder
        entries += node to actualFolder
        if (depth == 1 && node is ShareCollection) {
            val take = node.take
            try {
                if (take == null) tree.takeFolders().forEach { entries += it to null }
                else tree.files(take).forEach { entries += it to take.stem }
            } catch (error: Exception) {
                return serverError(output, error)
            }
        }
        // Opening a take's folder is real use; polling the root or a single node is not.
        val activity = depth == 1 && node is ShareCollection && node.take != null
        // Two passes over the same deterministic parts: count for Content-Length, then stream, so a
        // folder of thousands of frames is never held in memory as one document.
        val parts = multistatusParts(entries)
        var length = 0L
        for (part in parts) length += part.toByteArray(Charsets.UTF_8).size
        writeHead(output, 207, listOf("Content-Type" to XML_TYPE, "Content-Length" to length.toString()), keepAlive)
        streamBody(connection, activity) {
            for (part in parts) {
                output.write(part.toByteArray(Charsets.UTF_8))
                connection.lastProgressMs.set(clock.nowMs())
            }
        }
        return keepAlive
    }

    private fun get(request: HttpRequest, path: SharePath, node: ShareNode, output: OutputStream, connection: Connection, keepAlive: Boolean, head: Boolean): Boolean {
        if (node is ShareCollection) {
            val take = node.take
            val entries = try {
                if (take == null) tree.takeFolders().map { it to null } else tree.files(take).map { it to take.stem }
            } catch (error: Exception) {
                return serverError(output, error)
            }
            val body = collectionHtml(if (take == null) "OpenCineCam" else take.stem, entries).toByteArray(Charsets.UTF_8)
            respond(output, 200, listOf("Content-Type" to "text/html; charset=utf-8", "X-Content-Type-Options" to "nosniff",
                "Content-Security-Policy" to "default-src 'none'"), body, keepAlive = keepAlive, head = head)
            if (!head) lastActivity.set(clock.nowMs())
            return keepAlive
        }
        val file = node as ShareFile
        val ifRange = request.header("if-range")
        val rangeHeader = request.header("range")?.takeIf { ifRange == null || ifRange == file.etag || ifRange == httpDate(file.modifiedMs) }
        val common = listOf("Accept-Ranges" to "bytes", "ETag" to file.etag, "Last-Modified" to httpDate(file.modifiedMs),
            "Content-Type" to file.contentType)
        when (val range = decideRange(rangeHeader, file.size)) {
            RangeDecision.Unsatisfiable -> {
                respond(output, 416, listOf("Content-Range" to "bytes */${file.size}", "Accept-Ranges" to "bytes"), keepAlive = keepAlive)
                return keepAlive
            }
            RangeDecision.Full -> {
                writeHead(output, 200, common + ("Content-Length" to file.size.toString()), keepAlive)
                if (!head) sendFile(file, 0, file.size, output, connection)
            }
            is RangeDecision.Partial -> {
                writeHead(output, 206, common + listOf("Content-Range" to "bytes ${range.start}-${range.endInclusive}/${file.size}",
                    "Content-Length" to range.length.toString()), keepAlive)
                if (!head) sendFile(file, range.start, range.length, output, connection)
            }
        }
        return keepAlive
    }

    private fun sendFile(file: ShareFile, offset: Long, length: Long, output: OutputStream, connection: Connection) {
        streamBody(connection, activity = length > 0) {
            val channel = ProgressChannel(output) { connection.lastProgressMs.set(clock.nowMs()) }
            try {
                file.writeRange(offset, length, channel)
            } catch (error: IOException) {
                throw error
            } catch (error: RuntimeException) {
                // The head is out with a Content-Length: the only honest signal left is to cut the connection.
                log("share: generating ${file.contentType} failed: ${error.javaClass.simpleName}")
                throw IOException("body generation failed", error)
            }
            if (channel.written != length) throw IOException("short body")
        }
    }

    /** Writes a body under the stall reaper; [activity] bodies also hold off the idle stop while they stream. */
    private inline fun streamBody(connection: Connection, activity: Boolean, body: () -> Unit) {
        if (activity) streaming.incrementAndGet()
        connection.lastProgressMs.set(clock.nowMs())
        connection.writing = true
        try {
            body()
        } finally {
            connection.writing = false
            if (activity) {
                streaming.decrementAndGet()
                lastActivity.set(clock.nowMs())
            }
        }
    }

    private fun writeHead(output: OutputStream, status: Int, headers: List<Pair<String, String>>, keepAlive: Boolean) {
        val head = StringBuilder(256)
        head.append("HTTP/1.1 ").append(status).append(' ').append(reasonPhrase(status)).append("\r\n")
        head.append("Date: ").append(httpDate(wallClock())).append("\r\n")
        for ((name, value) in headers) head.append(name).append(": ").append(value).append("\r\n")
        head.append("Connection: ").append(if (keepAlive) "keep-alive" else "close").append("\r\n\r\n")
        output.write(head.toString().toByteArray(Charsets.US_ASCII))
    }

    private fun respond(
        output: OutputStream,
        status: Int,
        headers: List<Pair<String, String>> = emptyList(),
        body: ByteArray = ByteArray(0),
        keepAlive: Boolean,
        head: Boolean = false,
    ) {
        writeHead(output, status, headers + ("Content-Length" to body.size.toString()), keepAlive)
        if (!head && body.isNotEmpty()) output.write(body)
        output.flush()
    }

    /** Counts what reaches the socket and reports progress for the stall reaper and the idle clock. */
    private class ProgressChannel(private val out: OutputStream, private val progress: () -> Unit) : WritableByteChannel {
        var written = 0L
            private set
        private val chunk = ByteArray(64 * 1024)
        @Volatile private var open = true

        override fun write(src: ByteBuffer): Int {
            val total = src.remaining()
            while (src.hasRemaining()) {
                val n = minOf(chunk.size, src.remaining())
                src.get(chunk, 0, n)
                out.write(chunk, 0, n)
                written += n
                progress()
            }
            return total
        }

        override fun isOpen() = open
        override fun close() { open = false }
    }

    companion object {
        val ALLOWED = setOf("OPTIONS", "PROPFIND", "GET", "HEAD")
        const val ALLOW = "OPTIONS, PROPFIND, GET, HEAD"
        const val XML_TYPE = "application/xml; charset=utf-8"
        private const val LINGER_MS = 1_000
        private const val LINGER_MAX_BYTES = 256 * 1024
        /** Bounds the trusted-address map; the server only listens on local networks. */
        private const val MAX_TRUSTED_CLIENTS = 64
        val OPTIONS_HEADERS = listOf("DAV" to "1", "MS-Author-Via" to "DAV", "Allow" to ALLOW, "Accept-Ranges" to "bytes")
    }
}
