/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.transfers

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Blocking device-local credential vault; call from an IO dispatcher, never the UI thread.
 * Each immutable endpoint identity has its own AndroidKeyStore AES-GCM key. Records and keys
 * never enter presets, enrollments, outbox data or logs. A readable file is not live upload consent.
 *
 * save explicitly replaces a valid record. Damaged/unknown/incomplete records or a lost key remain
 * untouched until explicit clear, rather than silently downgrading to unauthenticated credentials.
 * All instances serialize within this application process; no cross-process writer is supported.
 */
class WebDavCredentialStore internal constructor(
    private val directory: File,
    private val keyAlias: String,
) {
    constructor(context: Context) : this(
        File(context.applicationContext.noBackupFilesDir, "webdav-credentials"),
        "opencinecam.webdav.credentials.v1",
    )

    init { require(keyAlias.isNotBlank() && keyAlias.length <= 180) }

    fun status(endpointId: String): WebDavCredentialStatus {
        requireCredentialEndpoint(endpointId)
        return try {
            if (load(endpointId) == null) WebDavCredentialStatus.MISSING else WebDavCredentialStatus.AVAILABLE
        } catch (_: Exception) { WebDavCredentialStatus.UNAVAILABLE }
    }

    fun load(endpointId: String): WebDavCredentials? = synchronized(processLock) {
        requireCredentialEndpoint(endpointId)
        sanitized { readCredentials(endpointId) }
    }

    fun save(endpointId: String, username: String, password: String): Unit = synchronized(processLock) {
        requireCredentialEndpoint(endpointId)
        // Validate before any directory/key mutation; the transport owns this validation contract.
        WebDavCredentials(username, password)
        sanitized {
            val plaintext = WebDavCredentialCodec.encodePayload(username, password)
            try {
                readCredentials(endpointId) // Existing damaged evidence must be explicitly cleared.
                if (!exists(directory)) check(directory.mkdirs())
                requireDirectory()
                val key = key(endpointId, create = true)
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, key)
                cipher.updateAAD(WebDavCredentialCodec.aad(endpointId))
                val bytes = WebDavCredentialCodec.encodeEnvelope(cipher.iv, cipher.doFinal(plaintext))
                val atomic = AtomicFile(record(endpointId))
                val stream = atomic.startWrite()
                var finishAttempted = false
                try {
                    stream.write(bytes)
                    stream.fd.sync()
                    finishAttempted = true
                    atomic.finishWrite(stream)
                    // AtomicFile.finishWrite can log a failed rename without throwing. Certify bytes
                    // and authenticated plaintext before acknowledging persistence to the caller.
                    check(requireNotNull(readBytes(endpointId)).contentEquals(bytes))
                    check(readCredentials(endpointId) != null)
                } catch (failure: Exception) {
                    if (!finishAttempted) {
                        try { atomic.failWrite(stream) } catch (_: Exception) { /* Report unavailable below. */ }
                    }
                    throw failure
                }
            } finally { plaintext.fill(0) }
        }
    }

    /** Explicit removal is the only recovery from key loss, corruption or an unfinished write. */
    fun clear(endpointId: String): Unit = synchronized(processLock) {
        requireCredentialEndpoint(endpointId)
        sanitized {
            var failed = false
            if (exists(directory)) {
                requireDirectory()
                for (file in paths(endpointId)) {
                    try {
                        if (exists(file)) {
                            check(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS))
                            check(file.delete())
                        }
                    } catch (_: Exception) { failed = true }
                }
            }
            // Preserve the key if removal failed; a surviving valid record must remain recoverable.
            check(!failed)
            val store = keyStore()
            val alias = alias(endpointId)
            if (store.containsAlias(alias)) store.deleteEntry(alias)
            check(!store.containsAlias(alias))
        }
    }

    private fun readCredentials(endpointId: String): WebDavCredentials? {
        val bytes = readBytes(endpointId) ?: return null
        val envelope = WebDavCredentialCodec.decodeEnvelope(bytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(endpointId, create = false), GCMParameterSpec(128, envelope.iv))
        cipher.updateAAD(WebDavCredentialCodec.aad(endpointId))
        val plaintext = cipher.doFinal(envelope.ciphertext)
        return try { WebDavCredentialCodec.decodePayload(plaintext) } finally { plaintext.fill(0) }
    }

    private fun readBytes(endpointId: String): ByteArray? {
        if (!exists(directory)) return null
        requireDirectory()
        val paths = paths(endpointId)
        // openRead would delete .new/restore .bak. Never mutate uncertain evidence during status/load.
        check(paths.drop(1).none(::exists))
        val file = paths.first()
        if (!exists(file)) return null
        check(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS))
        return file.inputStream().use { input ->
            val bytes = ByteArray(WebDavCredentialCodec.MAX_RECORD_BYTES + 1)
            var size = 0
            while (size < bytes.size) {
                val count = input.read(bytes, size, bytes.size - size)
                if (count < 0) break
                check(count > 0)
                size += count
            }
            check(size <= WebDavCredentialCodec.MAX_RECORD_BYTES)
            bytes.copyOf(size)
        }
    }

    private fun key(endpointId: String, create: Boolean): SecretKey {
        val store = keyStore()
        val alias = alias(endpointId)
        if (store.containsAlias(alias)) return store.getKey(alias, null) as SecretKey
        check(create)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build())
            generateKey()
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
    private fun alias(endpointId: String) = "$keyAlias.$endpointId"
    private fun record(endpointId: String) = File(directory, "$endpointId.credential")
    private fun paths(endpointId: String) = listOf(record(endpointId), File(directory, "$endpointId.credential.new"), File(directory, "$endpointId.credential.bak"))
    private fun exists(file: File) = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)
    private fun requireDirectory() { check(Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) }
    private inline fun <T> sanitized(block: () -> T): T = try { block() }
    catch (_: Exception) { throw WebDavCredentialUnavailable() }

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private val processLock = Any()
    }
}
