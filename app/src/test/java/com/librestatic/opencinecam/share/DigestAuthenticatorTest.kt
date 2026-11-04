/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.share

import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DigestAuthenticatorTest {
    private val credentials = ShareCredentials("occ", "k7pq2m9xwe4hjtna")
    private val clock = ManualClock()
    private val auth = DigestAuthenticator(credentials, clock)
    private val client = "192.168.1.20"

    private fun params(challenge: String) = DigestAuthenticator.parseAuthParams(challenge.substringAfter(' '))!!

    private fun header(
        challenge: String,
        method: String = "PROPFIND",
        uri: String = "/",
        nc: String = "00000001",
        password: String = credentials.password,
        user: String = "occ",
        qop: String? = "auth",
        cnonce: String = "abcdef",
    ): String {
        val c = params(challenge)
        val ha1 = DigestAuthenticator.md5Hex("$user:${c["realm"]}:$password")
        val ha2 = DigestAuthenticator.md5Hex("$method:$uri")
        val response = if (qop == null) DigestAuthenticator.md5Hex("$ha1:${c["nonce"]}:$ha2")
            else DigestAuthenticator.md5Hex("$ha1:${c["nonce"]}:$nc:$cnonce:$qop:$ha2")
        return "Digest username=\"$user\", realm=\"${c["realm"]}\", nonce=\"${c["nonce"]}\", uri=\"$uri\", response=\"$response\", " +
            "opaque=\"${c["opaque"]}\"" + (if (qop != null) ", qop=$qop, nc=$nc, cnonce=\"$cnonce\"" else "")
    }

    @Test
    fun challengeOffersMd5WithQopAuth() {
        val c = params(auth.challenge())
        assertEquals("OpenCineCam", c["realm"])
        assertEquals("auth", c["qop"])
        assertEquals("MD5", c["algorithm"])
        assertEquals(DigestAuthenticator.NONCE_CHARS, c["nonce"]!!.length)
        assertTrue(c.containsKey("opaque"))
        assertEquals("true", params(auth.challenge(stale = true))["stale"])
    }

    @Test
    fun aValidResponseIsAccepted() {
        val challenge = auth.challenge()
        assertEquals(AuthResult.Ok, auth.authenticate(client, "PROPFIND", "/", header(challenge)))
        // Same nonce, next count, another resource.
        assertEquals(AuthResult.Ok, auth.authenticate(client, "GET", "/A001/A001_000000.dng",
            header(challenge, "GET", "/A001/A001_000000.dng", nc = "00000002")))
    }

    @Test
    fun missingCredentialsAreAChallengeNotAFailure() {
        repeat(50) { assertEquals(AuthResult.Missing, auth.authenticate(client, "OPTIONS", "/", null)) }
        assertEquals(AuthResult.Ok, auth.authenticate(client, "PROPFIND", "/", header(auth.challenge())))
    }

    @Test
    fun wrongPasswordUserOrResourceFail() {
        val challenge = auth.challenge()
        assertTrue(auth.authenticate(client, "PROPFIND", "/", header(challenge, password = "wrongwrong")) is AuthResult.Failed)
        assertTrue(auth.authenticate(client, "PROPFIND", "/", header(challenge, user = "admin", nc = "00000002")) is AuthResult.Failed)
        // A response computed for another method or path does not authorise this request.
        assertTrue(auth.authenticate(client, "GET", "/", header(challenge, method = "PROPFIND", nc = "00000003")) is AuthResult.Failed)
        assertTrue(auth.authenticate(client, "PROPFIND", "/A001/", header(challenge, uri = "/", nc = "00000004")) is AuthResult.Failed)
    }

    @Test
    fun missingQopAndOtherVariantsAreRefused() {
        val challenge = auth.challenge()
        assertTrue(auth.authenticate(client, "PROPFIND", "/", header(challenge, qop = null)) is AuthResult.Failed)
        assertTrue(auth.authenticate(client, "PROPFIND", "/", header(challenge, qop = "auth-int")) is AuthResult.Failed)
        assertTrue(auth.authenticate(client, "PROPFIND", "/", "Basic b2NjOms3cHEybTl4d2U=") is AuthResult.Failed)
        assertTrue(auth.authenticate(client, "PROPFIND", "/", header(challenge).replace("algorithm", "x") + ", algorithm=SHA-256") is AuthResult.Failed)
        assertTrue(auth.authenticate(client, "PROPFIND", "/", header(challenge, nc = "1")) is AuthResult.Failed)
        assertTrue(auth.authenticate(client, "PROPFIND", "/", "Digest username=\"occ\", realm=\"OpenCineCam\"") is AuthResult.Failed)
        assertTrue(auth.authenticate(client, "PROPFIND", "/", "Digest username=\"occ") is AuthResult.Failed)
        assertTrue(auth.authenticate(client, "PROPFIND", "/", header(challenge).replace(params(challenge)["opaque"]!!, "forged")) is AuthResult.Failed)
    }

    @Test
    fun aReplayedNonceCountGetsAFreshChallengeAndIsNotAFailure() {
        val challenge = auth.challenge()
        val first = header(challenge, nc = "00000001")
        assertEquals(AuthResult.Ok, auth.authenticate(client, "PROPFIND", "/", first))
        // Replaying more than the lockout threshold never locks the address out: the response was right.
        repeat(DigestAuthenticator.MAX_FAILURES + 2) { assertEquals(AuthResult.Stale, auth.authenticate(client, "PROPFIND", "/", first)) }
        assertTrue(auth.authenticate(client, "PROPFIND", "/", header(challenge, nc = "00000000")) is AuthResult.Failed)
        assertEquals(AuthResult.Ok, auth.authenticate(client, "PROPFIND", "/", header(challenge, nc = "00000002")))
    }

    @Test
    fun noncesAreStatelessUntilTheyAuthenticate() {
        // A nonce issued before thousands of others still works: issuing stores nothing to evict.
        val early = auth.challenge()
        repeat(20_000) { auth.challenge() }
        assertEquals(AuthResult.Ok, auth.authenticate(client, "PROPFIND", "/", header(early)))
    }

    @Test
    fun aTamperedNonceIsForeignEvenWithAMatchingResponse() {
        val nonce = params(auth.challenge())["nonce"]!!
        // Flip the issue time (first bytes) and compute a correct response over the forged nonce.
        val flipped = (if (nonce[2] == 'A') 'B' else 'A').let { nonce.substring(0, 2) + it + nonce.substring(3) }
        val forged = auth.challenge().replace(Regex("nonce=\"[^\"]+\""), "nonce=\"$flipped\"")
        assertEquals(AuthResult.Failed("foreign nonce"), auth.authenticate(client, "PROPFIND", "/", header(forged)))
    }

    @Test
    fun evictedReplayStateIsNotReset() {
        val first = auth.challenge()
        assertEquals(AuthResult.Ok, auth.authenticate(client, "PROPFIND", "/", header(first)))
        repeat(DigestAuthenticator.MAX_NONCES) { assertEquals(AuthResult.Ok, auth.authenticate(client, "PROPFIND", "/", header(auth.challenge()))) }
        // Its counts are forgotten, so even an unused count is answered with a fresh nonce instead of trusted.
        assertEquals(AuthResult.Stale, auth.authenticate(client, "PROPFIND", "/", header(first, nc = "00000002")))
    }

    @Test
    fun parallelConnectionsMaySendCountsOutOfOrderWithinTheWindow() {
        val challenge = auth.challenge()
        assertEquals(AuthResult.Ok, auth.authenticate(client, "PROPFIND", "/", header(challenge, nc = "00000005")))
        assertEquals(AuthResult.Ok, auth.authenticate(client, "PROPFIND", "/", header(challenge, nc = "00000003")))
        assertEquals(AuthResult.Stale, auth.authenticate(client, "PROPFIND", "/", header(challenge, nc = "00000003")))
        assertEquals(AuthResult.Ok, auth.authenticate(client, "PROPFIND", "/", header(challenge, nc = "00000400")))
        // 0x400 - 5 is past the 256-count window: too old to tell from a replay.
        assertEquals(AuthResult.Stale, auth.authenticate(client, "PROPFIND", "/", header(challenge, nc = "00000004")))
    }

    @Test
    fun anExpiredNonceIsStaleNotAFailure() {
        val challenge = auth.challenge()
        clock.advance(DigestAuthenticator.NONCE_LIFETIME_MS + 1)
        assertEquals(AuthResult.Stale, auth.authenticate(client, "PROPFIND", "/", header(challenge)))
        // It stays stale; a wrong password over it is still a failure.
        assertEquals(AuthResult.Stale, auth.authenticate(client, "PROPFIND", "/", header(challenge, nc = "00000002")))
        assertTrue(auth.authenticate(client, "PROPFIND", "/", header(challenge, password = "nope", nc = "00000003")) is AuthResult.Failed)
    }

    @Test
    fun aForeignNonceIsRefused() {
        val other = DigestAuthenticator(credentials, clock).challenge()
        // Same password, but the other server's opaque: refused before the nonce is looked at.
        assertTrue(auth.authenticate(client, "PROPFIND", "/", header(other)) is AuthResult.Failed)
        val forged = header(auth.challenge()).replace(Regex("nonce=\"[^\"]+\""), "nonce=\"not-a-nonce\"")
        assertTrue(auth.authenticate(client, "PROPFIND", "/", forged) is AuthResult.Failed)
    }

    @Test
    fun tenFailuresLockTheAddressOutFor30Seconds() {
        val challenge = auth.challenge()
        repeat(DigestAuthenticator.MAX_FAILURES) { i ->
            assertTrue(auth.authenticate(client, "PROPFIND", "/", header(challenge, password = "guess$i")) is AuthResult.Failed)
        }
        val locked = auth.authenticate(client, "PROPFIND", "/", header(challenge, nc = "00000002"))
        assertTrue(locked is AuthResult.LockedOut)
        assertEquals(30L, (locked as AuthResult.LockedOut).retryAfterSeconds)
        // Even an unauthenticated probe is refused, and another address is unaffected.
        assertTrue(auth.authenticate(client, "OPTIONS", "/", null) is AuthResult.LockedOut)
        assertEquals(AuthResult.Ok, auth.authenticate("192.168.1.21", "PROPFIND", "/", header(auth.challenge())))
        clock.advance(DigestAuthenticator.LOCKOUT_MS)
        assertEquals(AuthResult.Ok, auth.authenticate(client, "PROPFIND", "/", header(auth.challenge())))
    }

    @Test
    fun aSuccessClearsTheFailureCount() {
        val challenge = auth.challenge()
        repeat(DigestAuthenticator.MAX_FAILURES - 1) { auth.authenticate(client, "PROPFIND", "/", header(challenge, password = "x$it")) }
        assertEquals(AuthResult.Ok, auth.authenticate(client, "PROPFIND", "/", header(challenge, nc = "00000001")))
        assertTrue(auth.authenticate(client, "PROPFIND", "/", header(challenge, password = "again", nc = "00000002")) is AuthResult.Failed)
        assertEquals(AuthResult.Ok, auth.authenticate(client, "PROPFIND", "/", header(challenge, nc = "00000003")))
    }

    @Test
    fun theDigestUriMayBeEscapedDifferentlyButMustNameTheSameResource() {
        val challenge = auth.challenge()
        assertEquals(AuthResult.Ok, auth.authenticate(client, "GET", "/Caf%C3%A9/", header(challenge, "GET", "/Caf%c3%a9/")))
        assertFalse(DigestAuthenticator.sameResource("/a/", "/b/"))
        assertFalse(DigestAuthenticator.sameResource("/a/../b", "/b"))
    }

    @Test
    fun passwordsComeFromTheUnambiguousAlphabet() {
        val random = SecureRandom()
        val seen = HashSet<String>()
        repeat(200) {
            val generated = ShareCredentials.generate(random)
            assertEquals("occ", generated.user)
            assertEquals(ShareCredentials.LENGTH, generated.password.length)
            assertTrue(generated.password.all { it in ShareCredentials.ALPHABET })
            assertFalse(generated.toString().contains(generated.password))
            seen += generated.password
        }
        assertEquals(200, seen.size)
        assertEquals(16, ShareCredentials.LENGTH)
        assertFalse(ShareCredentials.ALPHABET.any { it in "0O1lI" })
    }
}
