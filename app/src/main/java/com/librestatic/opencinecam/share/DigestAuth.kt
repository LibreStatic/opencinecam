/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.share

import java.security.MessageDigest
import java.security.SecureRandom
import java.nio.ByteBuffer
import java.util.Base64
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** A monotonic millisecond clock; tests drive it by hand. */
internal fun interface ShareClock {
    fun nowMs(): Long

    companion object {
        val MONOTONIC = ShareClock { System.nanoTime() / 1_000_000 }
    }
}

/** The per-session credentials: user [USER] and a random password from [generate]. */
internal class ShareCredentials(val user: String, val password: String) {
    override fun toString(): String = "ShareCredentials(user=$user, password=<redacted>)"

    companion object {
        const val USER = "occ"

        /** No 0/O, 1/l/i: easy to read off the screen and type. */
        const val ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"
        /** 16 of 31 symbols: about 79 bits, out of reach of guessing even without the lockout. */
        const val LENGTH = 16

        fun generate(random: SecureRandom = SecureRandom()): ShareCredentials {
            val chars = CharArray(LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] }
            return ShareCredentials(USER, String(chars))
        }
    }
}

/** The outcome of checking a request's `Authorization` header. */
internal sealed interface AuthResult {
    data object Ok : AuthResult

    /** No credentials yet: challenge, not a failure. */
    data object Missing : AuthResult

    /** A valid response over an expired nonce: challenge with `stale=true`, not a failure. */
    data object Stale : AuthResult

    /** Wrong or malformed credentials, a replayed nonce count or a foreign nonce: a failure. */
    data class Failed(val reason: String) : AuthResult

    /** Too many failures from this address: refused without checking. */
    data class LockedOut(val retryAfterSeconds: Long) : AuthResult
}

/**
 * HTTP Digest authentication (RFC 7616) with MD5 and `qop=auth` only, the one variant
 * Windows' WebDAV redirector, macOS and gvfs/davfs2 all speak over plain HTTP.
 *
 * - Nonces are stateless: an issue time, 8 random bytes and an HMAC-SHA256 tag over both
 *   under a key drawn from [random] for this session. Issuing one stores nothing, so a flood of
 *   unauthenticated requests cannot churn server state. A nonce whose tag fails is foreign; one
 *   older than [nonceLifetimeMs] gets `stale=true` so clients retry silently.
 * - Only a nonce that has authenticated gets replay state: the nonce counts it has seen, in an
 *   LRU of [MAX_NONCES]. A repeated `nc`, or one more than [NC_WINDOW] below the highest seen,
 *   is a replay; since its response was right, it gets a fresh challenge (stale), not a
 *   failure. Parallel connections sharing a nonce may send counts out of order, which the
 *   window allows. A nonce evicted from the LRU loses its history, so it is refused as stale
 *   rather than accepted again.
 * - The response is compared in constant time; the user name, realm, opaque, algorithm and
 *   digest-uri (against the request-target) must match.
 * - Failures count per client address: [MAX_FAILURES] within [FAILURE_WINDOW_MS] locks the
 *   address out for [LOCKOUT_MS]. A success clears the count.
 */
internal class DigestAuthenticator(
    private val credentials: ShareCredentials,
    private val clock: ShareClock = ShareClock.MONOTONIC,
    private val random: SecureRandom = SecureRandom(),
    private val nonceLifetimeMs: Long = NONCE_LIFETIME_MS,
) {
    private class NonceState {
        var highest = 0L
        /** Bit i set: count `highest - i` was seen. */
        var seen = java.util.BitSet(NC_WINDOW)
    }

    private class Failures(var count: Int, var firstMs: Long, var lockedUntilMs: Long)

    private val lock = Any()
    /** Replay state of the nonces that have authenticated, least recently used evicted first. */
    private val nonces = object : LinkedHashMap<String, NonceState>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, NonceState>?): Boolean {
            if (size <= MAX_NONCES) return false
            eldest?.key?.let { evicted[it] = true }
            return true
        }
    }
    /** Nonces whose replay state was evicted: they may not start over at nc 1. */
    private val evicted = object : LinkedHashMap<String, Boolean>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > MAX_EVICTED
    }
    private val nonceKey = ByteArray(32).also(random::nextBytes)
    private val failures = object : LinkedHashMap<String, Failures>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Failures>?) = size > MAX_TRACKED_CLIENTS
    }
    private val opaque = token(16)
    private val ha1 = md5Hex("${credentials.user}:$REALM:${credentials.password}")

    /** A `WWW-Authenticate` value carrying a fresh nonce. */
    fun challenge(stale: Boolean = false): String {
        val nonce = issueNonce(clock.nowMs())
        return "Digest realm=\"$REALM\", qop=\"auth\", algorithm=MD5, nonce=\"$nonce\", opaque=\"$opaque\"" +
            if (stale) ", stale=true" else ""
    }

    /** Checks [authorization] for a request with [method] and raw [target] from [client]. */
    fun authenticate(client: String, method: String, target: String, authorization: String?): AuthResult {
        val now = clock.nowMs()
        synchronized(lock) {
            val record = failures[client]
            if (record != null && record.lockedUntilMs > now) return AuthResult.LockedOut((record.lockedUntilMs - now + 999) / 1000)
        }
        if (authorization == null) return AuthResult.Missing
        val result = verify(method, target, authorization, now)
        synchronized(lock) {
            when (result) {
                is AuthResult.Failed -> {
                    val record = failures[client]
                    if (record == null || now - record.firstMs > FAILURE_WINDOW_MS) {
                        failures[client] = Failures(1, now, 0)
                    } else {
                        record.count++
                        if (record.count >= MAX_FAILURES) {
                            record.lockedUntilMs = now + LOCKOUT_MS
                            record.count = 0
                            record.firstMs = now
                        }
                    }
                }
                AuthResult.Ok -> failures.remove(client)
                else -> Unit
            }
        }
        return result
    }

    private fun verify(method: String, target: String, authorization: String, now: Long): AuthResult {
        val space = authorization.indexOf(' ')
        if (space <= 0 || !authorization.substring(0, space).equals("Digest", ignoreCase = true)) return AuthResult.Failed("not digest")
        val params = parseAuthParams(authorization.substring(space + 1)) ?: return AuthResult.Failed("malformed")
        val username = params["username"] ?: return AuthResult.Failed("no username")
        val realm = params["realm"] ?: return AuthResult.Failed("no realm")
        val nonce = params["nonce"] ?: return AuthResult.Failed("no nonce")
        val uri = params["uri"] ?: return AuthResult.Failed("no uri")
        val response = params["response"]?.lowercase(Locale.ROOT) ?: return AuthResult.Failed("no response")
        val qop = params["qop"] ?: return AuthResult.Failed("no qop")
        val nc = params["nc"] ?: return AuthResult.Failed("no nc")
        val cnonce = params["cnonce"] ?: return AuthResult.Failed("no cnonce")
        if (qop != "auth") return AuthResult.Failed("qop")
        params["algorithm"]?.let { if (!it.equals("MD5", ignoreCase = true)) return AuthResult.Failed("algorithm") }
        params["opaque"]?.let { if (it != opaque) return AuthResult.Failed("opaque") }
        if (realm != REALM) return AuthResult.Failed("realm")
        if (nc.length != 8 || !nc.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return AuthResult.Failed("nc")
        if (nc.all { it == '0' }) return AuthResult.Failed("nc") // counts start at 1
        if (cnonce.isEmpty() || cnonce.length > 256) return AuthResult.Failed("cnonce")
        if (!sameResource(uri, target)) return AuthResult.Failed("uri")
        val ha2 = md5Hex("$method:$uri")
        val expected = md5Hex("$ha1:$nonce:$nc:$cnonce:$qop:$ha2")
        // Constant time over the response; the user name check is folded in so timing does not tell them apart.
        val responseOk = MessageDigest.isEqual(expected.toByteArray(Charsets.US_ASCII), response.toByteArray(Charsets.US_ASCII))
        val userOk = MessageDigest.isEqual(credentials.user.toByteArray(Charsets.UTF_8), username.toByteArray(Charsets.UTF_8))
        if (!(responseOk and userOk)) return AuthResult.Failed("response")
        val count = nc.toLong(16)
        val issuedMs = nonceIssuedMs(nonce) ?: return AuthResult.Failed("foreign nonce")
        if (now - issuedMs > nonceLifetimeMs || issuedMs > now) return AuthResult.Stale
        synchronized(lock) {
            val state = nonces[nonce] ?: run {
                if (evicted.containsKey(nonce)) return AuthResult.Stale
                NonceState().also { nonces[nonce] = it }
            }
            // The response was right: a replayed count is answered with a fresh nonce, not counted as a guess.
            if (!state.accept(count)) return AuthResult.Stale
        }
        return AuthResult.Ok
    }

    /** `base64url(issued ms (8) | random (8) | HMAC-SHA256(key, first 16)[0, 16))`. */
    private fun issueNonce(now: Long): String {
        val body = ByteBuffer.allocate(NONCE_BODY_BYTES).putLong(now).put(ByteArray(8).also(random::nextBytes)).array()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(body + tag(body))
    }

    /** The issue time of a nonce this session minted, or null for anything else. */
    private fun nonceIssuedMs(nonce: String): Long? {
        if (nonce.length != NONCE_CHARS) return null
        val raw = try { Base64.getUrlDecoder().decode(nonce) } catch (_: IllegalArgumentException) { return null }
        if (raw.size != NONCE_BODY_BYTES + NONCE_TAG_BYTES) return null
        val body = raw.copyOfRange(0, NONCE_BODY_BYTES)
        if (!MessageDigest.isEqual(tag(body), raw.copyOfRange(NONCE_BODY_BYTES, raw.size))) return null
        return ByteBuffer.wrap(body).long
    }

    private fun tag(body: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(nonceKey, "HmacSHA256"))
        return mac.doFinal(body).copyOf(NONCE_TAG_BYTES)
    }

    private fun NonceState.accept(count: Long): Boolean {
        if (count <= 0) return false
        if (count > highest) {
            val shift = count - highest
            seen = if (shift >= NC_WINDOW) java.util.BitSet(NC_WINDOW) else shiftUp(seen, shift.toInt())
            seen.set(0)
            highest = count
            return true
        }
        val offset = highest - count
        if (offset >= NC_WINDOW || seen.get(offset.toInt())) return false
        seen.set(offset.toInt())
        return true
    }

    private fun shiftUp(bits: java.util.BitSet, by: Int): java.util.BitSet {
        val next = java.util.BitSet(NC_WINDOW)
        var i = bits.nextSetBit(0)
        while (i >= 0) {
            if (i + by < NC_WINDOW) next.set(i + by)
            i = bits.nextSetBit(i + 1)
        }
        return next
    }

    private fun token(bytes: Int): String {
        val buffer = ByteArray(bytes)
        random.nextBytes(buffer)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer)
    }

    companion object {
        const val REALM = "OpenCineCam"
        const val NONCE_LIFETIME_MS = 5 * 60_000L
        const val MAX_NONCES = 512
        private const val MAX_EVICTED = 4096
        private const val NONCE_BODY_BYTES = 16
        private const val NONCE_TAG_BYTES = 16
        /** base64url without padding of 32 bytes. */
        const val NONCE_CHARS = 43
        const val NC_WINDOW = 256
        const val MAX_FAILURES = 10
        const val FAILURE_WINDOW_MS = 10 * 60_000L
        const val LOCKOUT_MS = 30_000L
        const val MAX_TRACKED_CLIENTS = 1024

        fun md5Hex(text: String): String {
            val digest = MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }

        /**
         * The digest-uri must name the request's resource: equal to the request-target, or equal
         * once both are parsed (clients differ in which characters they escape). Never a looser match.
         */
        fun sameResource(uri: String, target: String): Boolean {
            if (uri == target) return true
            return try {
                parseSharePath(uri).segments == parseSharePath(target).segments
            } catch (_: HttpParseException) {
                false
            }
        }

        /** RFC 9110 auth-param list: `name=token` or `name="quoted"`, comma separated. Null when malformed. */
        fun parseAuthParams(text: String): Map<String, String>? {
            val out = HashMap<String, String>()
            var i = 0
            val n = text.length
            while (i < n) {
                while (i < n && (text[i] == ' ' || text[i] == '\t' || text[i] == ',')) i++
                if (i >= n) break
                val nameStart = i
                while (i < n && HttpRequestReader.isTokenChar(text[i])) i++
                if (i == nameStart) return null
                val name = text.substring(nameStart, i).lowercase(Locale.ROOT)
                while (i < n && (text[i] == ' ' || text[i] == '\t')) i++
                if (i >= n || text[i] != '=') return null
                i++
                while (i < n && (text[i] == ' ' || text[i] == '\t')) i++
                val value: String
                if (i < n && text[i] == '"') {
                    i++
                    val sb = StringBuilder()
                    var closed = false
                    while (i < n) {
                        val c = text[i]
                        if (c == '\\' && i + 1 < n) { sb.append(text[i + 1]); i += 2; continue }
                        if (c == '"') { closed = true; i++; break }
                        sb.append(c)
                        i++
                    }
                    if (!closed) return null
                    value = sb.toString()
                } else {
                    val start = i
                    while (i < n && text[i] != ',' && text[i] != ' ' && text[i] != '\t') i++
                    value = text.substring(start, i)
                }
                if (out.put(name, value) != null) return null // a repeated parameter is malformed
                if (out.size > 16) return null
            }
            return out
        }
    }
}
