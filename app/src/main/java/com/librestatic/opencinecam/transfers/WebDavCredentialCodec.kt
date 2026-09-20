/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.transfers

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

/** No exception retains the parser/crypto cause, which could include sensitive input. */
class WebDavCredentialUnavailable : IOException("WebDAV credentials are unavailable")

enum class WebDavCredentialStatus { MISSING, AVAILABLE, UNAVAILABLE }

internal fun requireCredentialEndpoint(endpointId: String) {
    require(endpointId.length == 36 && UUID.fromString(endpointId).toString() == endpointId) {
        "Invalid credential endpoint identity"
    }
}

/** Fixed-depth, length-prefixed binary schema. There is no permissive JSON or plaintext fallback. */
internal object WebDavCredentialCodec {
    private const val MAGIC = 0x57444352 // WDCR
    private const val VERSION = 1
    const val MAX_PLAINTEXT_BYTES = 32_780 // 8192 UTF-16 code units, UTF-8 plus three Int fields.
    const val MAX_RECORD_BYTES = MAX_PLAINTEXT_BYTES + 16 + 24
    const val IV_BYTES = 12

    class Envelope(val iv: ByteArray, val ciphertext: ByteArray)

    fun aad(endpointId: String): ByteArray {
        requireCredentialEndpoint(endpointId)
        return "opencinecam:webdav-credentials:v1:$endpointId".toByteArray(Charsets.US_ASCII)
    }

    fun encodePayload(username: String, password: String): ByteArray {
        // This is the transport's sole credential validation contract, not a competing rule set.
        WebDavCredentials(username, password)
        val user = utf8(username)
        val pass = utf8(password)
        return try {
            require(user.size + pass.size + 12 <= MAX_PLAINTEXT_BYTES)
            ByteBuffer.allocate(12 + user.size + pass.size)
                .putInt(VERSION).putInt(user.size).put(user).putInt(pass.size).put(pass).array()
        } finally { user.fill(0); pass.fill(0) }
    }

    fun decodePayload(bytes: ByteArray): WebDavCredentials = checked {
        require(bytes.size in 12..MAX_PLAINTEXT_BYTES)
        val buffer = ByteBuffer.wrap(bytes)
        require(buffer.int == VERSION)
        val username = readString(buffer)
        val password = readString(buffer)
        require(!buffer.hasRemaining())
        WebDavCredentials(username, password)
    }

    fun encodeEnvelope(iv: ByteArray, ciphertext: ByteArray): ByteArray = checked {
        require(iv.size == IV_BYTES && ciphertext.size in 28..(MAX_PLAINTEXT_BYTES + 16))
        ByteBuffer.allocate(24 + ciphertext.size)
            .putInt(MAGIC).putInt(VERSION).put(iv).putInt(ciphertext.size).put(ciphertext).array()
    }

    fun decodeEnvelope(bytes: ByteArray): Envelope = checked {
        require(bytes.size in 52..MAX_RECORD_BYTES)
        val buffer = ByteBuffer.wrap(bytes)
        require(buffer.int == MAGIC && buffer.int == VERSION)
        val iv = ByteArray(IV_BYTES).also(buffer::get)
        val size = buffer.int
        require(size in 28..(MAX_PLAINTEXT_BYTES + 16) && size == buffer.remaining())
        Envelope(iv, ByteArray(size).also(buffer::get))
    }

    private fun utf8(value: String): ByteArray {
        val buffer = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(value))
        return ByteArray(buffer.remaining()).also(buffer::get)
    }

    private fun readString(buffer: ByteBuffer): String {
        require(buffer.remaining() >= 4)
        val length = buffer.int
        require(length >= 0 && length <= buffer.remaining())
        val bounded = buffer.slice().apply { limit(length) }
        val value = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(bounded).toString()
        buffer.position(buffer.position() + length)
        return value
    }

    private inline fun <T> checked(block: () -> T): T = try { block() }
    catch (_: Exception) { throw WebDavCredentialUnavailable() }
}
