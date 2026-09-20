/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.transfers

import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import org.junit.Assert.*
import org.junit.Test

class WebDavCredentialTest {
    private val endpoint = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"

    @Test fun payloadRoundTripsUnicodeColonInPasswordAndEmptyPassword() {
        for ((user, pass) in listOf("camera" to "", "camera-é🌈" to "secret:ñ🌈", "u" to " ")) {
            val bytes = WebDavCredentialCodec.encodePayload(user, pass)
            assertEquals(WebDavCredentials(user, pass).authorization(), WebDavCredentialCodec.decodePayload(bytes).authorization())
        }
    }

    @Test fun transportValidationRejectsTheSameCredentialsBeforeSerialization() {
        for ((user, pass) in listOf("" to "secret", "a:b" to "secret", "a\r" to "secret", "a" to "b\n", "a" to "x".repeat(8192))) {
            assertThrows(IllegalArgumentException::class.java) { WebDavCredentials(user, pass) }
            assertThrows(IllegalArgumentException::class.java) { WebDavCredentialCodec.encodePayload(user, pass) }
        }
    }

    @Test fun maximumTransportLengthSurvivesMultibyteEncoding() {
        val user = "界".repeat(4096)
        val pass = "猫".repeat(4096)
        val payload = WebDavCredentialCodec.encodePayload(user, pass)
        assertTrue(payload.size <= WebDavCredentialCodec.MAX_PLAINTEXT_BYTES)
        assertEquals(WebDavCredentials(user, pass).authorization(), WebDavCredentialCodec.decodePayload(payload).authorization())
    }

    @Test fun unpairedSurrogatesNeverSilentlyChangeTheSavedCredential() {
        assertThrows(Exception::class.java) { WebDavCredentialCodec.encodePayload("a\ud800", "p") }
        assertThrows(Exception::class.java) { WebDavCredentialCodec.encodePayload("a", "p\udc00") }
    }

    @Test fun decodedPayloadUsesTheTransportValidationContract() {
        val bytes = WebDavCredentialCodec.encodePayload("a", "b")
        bytes[8] = ':'.code.toByte()
        rejectsPayload(bytes)
        bytes[8] = '\n'.code.toByte()
        rejectsPayload(bytes)
    }

    @Test fun malformedUtf8IsNotReplacedDuringDecryption() {
        val bytes = WebDavCredentialCodec.encodePayload("a", "b")
        bytes[8] = 0xff.toByte()
        rejectsPayload(bytes)
    }

    @Test fun payloadRejectsFutureVersionNegativeLengthsTruncationAndTrailingBytes() {
        val valid = WebDavCredentialCodec.encodePayload("a", "b")
        rejectsPayload(valid.copyOf().also { ByteBuffer.wrap(it).putInt(2) })
        rejectsPayload(valid.copyOf().also { ByteBuffer.wrap(it).putInt(4, -1) })
        rejectsPayload(valid.copyOf().also { ByteBuffer.wrap(it).putInt(4, Int.MAX_VALUE) })
        for (size in 0 until valid.size) rejectsPayload(valid.copyOf(size))
        rejectsPayload(valid + byteArrayOf(0))
        rejectsPayload(ByteArray(WebDavCredentialCodec.MAX_PLAINTEXT_BYTES + 1))
    }

    @Test fun envelopeHasOneCanonicalVersionAndExactLength() {
        val iv = ByteArray(12) { it.toByte() }
        val encrypted = ByteArray(32) { (it + 20).toByte() }
        val record = WebDavCredentialCodec.encodeEnvelope(iv, encrypted)
        assertEquals(56, record.size)
        val decoded = WebDavCredentialCodec.decodeEnvelope(record)
        assertArrayEquals(iv, decoded.iv)
        assertArrayEquals(encrypted, decoded.ciphertext)
        assertArrayEquals(record, WebDavCredentialCodec.encodeEnvelope(decoded.iv, decoded.ciphertext))
    }

    @Test fun envelopeRejectsBadMagicFutureVersionLengthsAndTrailingBytes() {
        val valid = WebDavCredentialCodec.encodeEnvelope(ByteArray(12), ByteArray(32))
        for ((offset, value) in listOf(0 to 0, 4 to 2, 20 to -1, 20 to Int.MAX_VALUE, 20 to 31)) {
            rejectsEnvelope(valid.copyOf().also { ByteBuffer.wrap(it).putInt(offset, value) })
        }
        for (size in 0 until valid.size) rejectsEnvelope(valid.copyOf(size))
        rejectsEnvelope(valid + byteArrayOf(0))
        rejectsEnvelope(ByteArray(WebDavCredentialCodec.MAX_RECORD_BYTES + 1))
        assertThrows(WebDavCredentialUnavailable::class.java) { WebDavCredentialCodec.encodeEnvelope(ByteArray(16), ByteArray(32)) }
    }

    @Test fun arbitraryNestedDocumentsHaveNoRecursiveParserOrPlaintextFallback() {
        for (bytes in listOf("{\"username\":\"a\",\"password\":\"b\"}".toByteArray(), "[".repeat(5000).toByteArray(), "Basic YTpi".toByteArray())) {
            rejectsEnvelope(bytes)
            rejectsPayload(bytes)
        }
    }

    @Test fun endpointAadRequiresCanonicalUuidAndIsDifferentForDifferentIdentities() {
        assertEquals("opencinecam:webdav-credentials:v1:$endpoint", WebDavCredentialCodec.aad(endpoint).toString(Charsets.US_ASCII))
        assertFalse(WebDavCredentialCodec.aad(endpoint).contentEquals(WebDavCredentialCodec.aad("00000000-0000-0000-0000-000000000001")))
        for (invalid in listOf("", "../credentials", "1-1-1-1-1", endpoint.uppercase(), "$endpoint ")) {
            assertThrows(IllegalArgumentException::class.java) { WebDavCredentialCodec.aad(invalid) }
        }
    }

    @Test fun realAesGcmAuthenticatesEndpointAadAndRejectsCiphertextTampering() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val encrypt = Cipher.getInstance("AES/GCM/NoPadding")
        encrypt.init(Cipher.ENCRYPT_MODE, key)
        encrypt.updateAAD(WebDavCredentialCodec.aad(endpoint))
        val encrypted = encrypt.doFinal(WebDavCredentialCodec.encodePayload("a", "b"))
        fun decrypt(aad: ByteArray, data: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, encrypt.iv))
            cipher.updateAAD(aad)
            return cipher.doFinal(data)
        }
        assertEquals(WebDavCredentials("a", "b").authorization(), WebDavCredentialCodec.decodePayload(decrypt(WebDavCredentialCodec.aad(endpoint), encrypted)).authorization())
        assertThrows(Exception::class.java) { decrypt(WebDavCredentialCodec.aad("00000000-0000-0000-0000-000000000001"), encrypted) }
        assertThrows(Exception::class.java) { decrypt(WebDavCredentialCodec.aad(endpoint), encrypted.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }) }
    }

    @Test fun resultsAndFailuresDoNotRenderCredentialMaterial() {
        assertEquals("WebDavCredentials(redacted)", WebDavCredentialCodec.decodePayload(WebDavCredentialCodec.encodePayload("sensitive-user", "sensitive-password")).toString())
        val failure = assertThrows(WebDavCredentialUnavailable::class.java) { WebDavCredentialCodec.decodePayload("sensitive-password".toByteArray()) }
        assertEquals("WebDAV credentials are unavailable", failure.message)
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
        assertEquals(listOf("MISSING", "AVAILABLE", "UNAVAILABLE"), WebDavCredentialStatus.entries.map { it.name })
    }

    private fun rejectsPayload(bytes: ByteArray) {
        assertThrows(WebDavCredentialUnavailable::class.java) { WebDavCredentialCodec.decodePayload(bytes) }
    }
    private fun rejectsEnvelope(bytes: ByteArray) {
        assertThrows(WebDavCredentialUnavailable::class.java) { WebDavCredentialCodec.decodeEnvelope(bytes) }
    }
}
