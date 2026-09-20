/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.transfers

import android.content.Context
import android.util.AtomicFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.SecretKey
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebDavCredentialStoreDeviceTest {
    private val first = "00000000-0000-0000-0000-000000000001"
    private val second = "00000000-0000-0000-0000-000000000002"

    @Test fun missingReadAndStatusDoNotCreateDirectoryOrKey() = fixture { f ->
        assertEquals(WebDavCredentialStatus.MISSING, f.store.status(first))
        assertNull(f.store.load(first))
        assertFalse(f.root.exists())
        assertFalse(f.keys().containsAlias(f.alias(first)))
        f.store.clear(first)
        assertFalse(f.root.exists())
    }

    @Test fun realKeystoreEncryptedRecordReopensWithoutExportingKeyOrPlaintext() = fixture { f ->
        val user = "camera-user-sensitive-ñ"
        val password = "camera-password-sensitive-🌈"
        f.store.save(first, user, password)
        assertEquals(WebDavCredentialStatus.AVAILABLE, f.reopen().status(first))
        assertEquals(WebDavCredentials(user, password).authorization(), f.reopen().load(first)!!.authorization())
        assertNull(f.keys().getKey(f.alias(first), null).encoded)
        val bytes = f.file(first).readBytes()
        assertFalse(bytes.toString(Charsets.UTF_8).contains(user))
        assertFalse(bytes.toString(Charsets.UTF_8).contains(password))
        assertFalse(bytes.toString(Charsets.UTF_8).contains(WebDavCredentials(user, password).authorization()))
        assertTrue(bytes.size <= WebDavCredentialCodec.MAX_RECORD_BYTES)
        assertEquals(listOf("$first.credential"), f.root.list()!!.toList())
    }

    @Test fun explicitReplacementUsesFreshIvAndDoesNotAlterAnotherEndpoint() = fixture { f ->
        f.store.save(first, "first", "old")
        f.store.save(second, "second", "untouched")
        val old = f.file(first).readBytes()
        val other = f.file(second).readBytes()
        f.reopen().save(first, "first", "new")
        val replacement = f.file(first).readBytes()
        assertFalse(WebDavCredentialCodec.decodeEnvelope(old).iv.contentEquals(WebDavCredentialCodec.decodeEnvelope(replacement).iv))
        assertEquals(WebDavCredentials("first", "new").authorization(), f.store.load(first)!!.authorization())
        assertArrayEquals(other, f.file(second).readBytes())
        // Even the same plaintext is encrypted with a new provider-generated nonce.
        f.store.save(first, "first", "new")
        assertFalse(WebDavCredentialCodec.decodeEnvelope(replacement).iv.contentEquals(WebDavCredentialCodec.decodeEnvelope(f.file(first).readBytes()).iv))
    }

    @Test fun keyLossIsVisiblePreservesBytesAndNeedsExplicitClearBeforeNewSave() = fixture { f ->
        f.store.save(first, "u", "old")
        f.store.save(second, "v", "other")
        val before = f.snapshot()
        f.keys().deleteEntry(f.alias(first))
        assertUnavailablePreserved(f, first, before)
        assertFalse(f.keys().containsAlias(f.alias(first)))
        assertEquals(WebDavCredentialStatus.AVAILABLE, f.store.status(second))
        f.store.clear(first)
        assertEquals(WebDavCredentialStatus.MISSING, f.store.status(first))
        f.store.save(first, "u", "new")
        assertEquals(WebDavCredentials("u", "new").authorization(), f.reopen().load(first)!!.authorization())
        assertArrayEquals(before.getValue("$second.credential"), f.file(second).readBytes())
    }

    @Test fun tamperUnknownVersionTruncatedOversizedAndPlaintextRemainUnavailableAndIntact() = fixture { f ->
        f.store.save(first, "u", "p")
        f.store.save(second, "v", "other")
        val valid = f.file(first).readBytes()
        for (damaged in listOf(
            valid.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() },
            valid.copyOf().also { ByteBuffer.wrap(it).putInt(4, 2) },
            valid.copyOf(valid.size - 1), ByteArray(WebDavCredentialCodec.MAX_RECORD_BYTES + 1),
            "{\"username\":\"u\",\"password\":\"p\"}".toByteArray(),
        )) {
            f.file(first).writeBytes(damaged)
            assertUnavailablePreserved(f, first, f.snapshot())
        }
    }

    @Test fun endpointRecordSwapAndWrongAuthenticatedIdentityAreRejected() = fixture { f ->
        f.store.save(first, "first", "a")
        f.store.save(second, "second", "b")
        f.file(second).writeBytes(f.file(first).readBytes())
        assertUnavailablePreserved(f, second, f.snapshot())
        // Encrypt under the correct destination key but the wrong endpoint AAD. This independently
        // exercises AAD binding, rather than merely relying on different per-endpoint keys.
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, f.keys().getKey(f.alias(second), null) as SecretKey)
        cipher.updateAAD(WebDavCredentialCodec.aad(first))
        f.file(second).writeBytes(WebDavCredentialCodec.encodeEnvelope(cipher.iv, cipher.doFinal(WebDavCredentialCodec.encodePayload("first", "a"))))
        assertUnavailablePreserved(f, second, f.snapshot())
        assertEquals(WebDavCredentialStatus.AVAILABLE, f.store.status(first))
    }

    @Test fun partialFirstAtomicWriteIsNotRecoveredOrDeletedByReadOrSave() = fixture { f ->
        assertTrue(f.root.mkdirs())
        AtomicFile(f.file(first)).startWrite().use { it.write(byteArrayOf(1, 2, 3)); it.fd.sync() }
        assertUnavailablePreserved(f, first, f.snapshot())
        f.store.clear(first)
        assertEquals(WebDavCredentialStatus.MISSING, f.store.status(first))
        assertTrue(f.root.list()!!.isEmpty())
    }

    @Test fun newOrBackupBesideValidRecordRequiresExplicitClearAndPreservesOtherEndpoints() = fixture { f ->
        f.store.save(second, "other", "secret")
        val other = f.file(second).readBytes()
        for (suffix in listOf(".new", ".bak")) {
            f.store.save(first, "u", "p")
            File(f.file(first).path + suffix).writeText("unfinished")
            assertUnavailablePreserved(f, first, f.snapshot())
            f.store.clear(first)
            assertEquals(WebDavCredentialStatus.MISSING, f.store.status(first))
            assertFalse(f.keys().containsAlias(f.alias(first)))
            assertArrayEquals(other, f.file(second).readBytes())
        }
    }

    @Test fun explicitClearIsIdempotentAndRemovesOnlyTheRequestedRecordAndKey() = fixture { f ->
        f.store.save(first, "first", "a")
        f.store.save(second, "second", "b")
        val other = f.file(second).readBytes()
        f.reopen().clear(first)
        f.store.clear(first)
        assertFalse(f.file(first).exists())
        assertFalse(f.keys().containsAlias(f.alias(first)))
        assertTrue(f.keys().containsAlias(f.alias(second)))
        assertArrayEquals(other, f.file(second).readBytes())
        assertEquals(WebDavCredentials("second", "b").authorization(), f.reopen().load(second)!!.authorization())
    }

    @Test fun invalidInputDoesNotMutateExistingCiphertextOrAllocateKeys() = fixture { f ->
        f.store.save(first, "valid", "old")
        val before = f.snapshot()
        for ((user, pass) in listOf("" to "p", "a:b" to "p", "a" to "b\n", "a" to "p".repeat(8192))) {
            assertThrows(IllegalArgumentException::class.java) { f.store.save(first, user, pass) }
            f.assertSnapshot(before)
        }
        assertThrows(WebDavCredentialUnavailable::class.java) { f.store.save(second, "a\ud800", "p") }
        assertFalse(f.keys().containsAlias(f.alias(second)))
        for (id in listOf("../escape", "1-1-1-1-1", first + "/child")) {
            assertThrows(IllegalArgumentException::class.java) { f.store.status(id) }
            assertThrows(IllegalArgumentException::class.java) { f.store.load(id) }
            assertThrows(IllegalArgumentException::class.java) { f.store.save(id, "a", "b") }
            assertThrows(IllegalArgumentException::class.java) { f.store.clear(id) }
        }
        f.assertSnapshot(before)
    }

    @Test fun concurrentInstancesPublishOnlyCompleteAuthenticatedReplacements() = fixture { f ->
        f.store.save(first, "u", "initial")
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(4)
        try {
            val writers = (0..3).map { writer ->
                executor.submit {
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    val store = f.reopen()
                    repeat(3) { iteration ->
                        store.save(first, "u", "$writer-$iteration")
                        assertNotNull(store.load(first))
                        assertEquals(WebDavCredentialStatus.AVAILABLE, store.status(first))
                    }
                }
            }
            start.countDown()
            writers.forEach { it.get(30, TimeUnit.SECONDS) }
            val possible = (0..3).flatMap { writer -> (0..2).map { WebDavCredentials("u", "$writer-$it").authorization() } }
            assertTrue(f.reopen().load(first)!!.authorization() in possible)
            assertEquals(listOf("$first.credential"), f.root.list()!!.toList())
        } finally { start.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)) }
    }

    @Test fun symlinkRecordAndOccupiedDirectoryDoNotReadWriteOrDeleteForeignBytes() = fixture { f ->
        val foreign = File(f.root.parentFile, f.root.name + "-foreign")
        try {
            foreign.writeText("foreign evidence")
            assertTrue(f.root.mkdirs())
            Files.createSymbolicLink(f.file(first).toPath(), foreign.toPath())
            assertEquals(WebDavCredentialStatus.UNAVAILABLE, f.store.status(first))
            assertThrows(WebDavCredentialUnavailable::class.java) { f.store.save(first, "u", "p") }
            assertThrows(WebDavCredentialUnavailable::class.java) { f.store.clear(first) }
            assertEquals("foreign evidence", foreign.readText())
            assertTrue(f.file(first).delete())
            assertTrue(f.root.delete())
            f.root.writeText("occupied directory")
            assertEquals(WebDavCredentialStatus.UNAVAILABLE, f.store.status(first))
            assertThrows(WebDavCredentialUnavailable::class.java) { f.store.save(first, "u", "p") }
            assertThrows(WebDavCredentialUnavailable::class.java) { f.store.clear(first) }
            assertEquals("occupied directory", f.root.readText())
        } finally { foreign.delete() }
    }

    private fun assertUnavailablePreserved(f: Fixture, endpoint: String, before: Map<String, ByteArray>) {
        assertEquals(WebDavCredentialStatus.UNAVAILABLE, f.reopen().status(endpoint))
        val failure = assertThrows(WebDavCredentialUnavailable::class.java) { f.store.load(endpoint) }
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
        assertThrows(WebDavCredentialUnavailable::class.java) { f.store.save(endpoint, "replacement", "never written") }
        f.assertSnapshot(before)
    }

    private class Fixture {
        private val context = ApplicationProvider.getApplicationContext<Context>()
        private val namespace = "webdav-credential-test-${UUID.randomUUID()}"
        val root = File(context.noBackupFilesDir, namespace)
        private val prefix = "opencinecam.test.$namespace"
        val store = WebDavCredentialStore(root, prefix)
        fun reopen() = WebDavCredentialStore(root, prefix)
        fun file(endpoint: String) = File(root, "$endpoint.credential")
        fun alias(endpoint: String) = "$prefix.$endpoint"
        fun keys(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        fun snapshot() = root.listFiles()!!.associate { it.name to it.readBytes() }
        fun assertSnapshot(before: Map<String, ByteArray>) {
            val after = snapshot()
            assertEquals(before.keys, after.keys)
            before.forEach { (name, bytes) -> assertArrayEquals(name, bytes, after.getValue(name)) }
        }
        fun close() {
            val store = keys()
            store.aliases().toList().filter { it.startsWith("$prefix.") }.forEach(store::deleteEntry)
            root.deleteRecursively()
        }
    }

    private fun fixture(block: (Fixture) -> Unit) {
        val fixture = Fixture()
        try { block(fixture) } finally { fixture.close() }
    }
}
