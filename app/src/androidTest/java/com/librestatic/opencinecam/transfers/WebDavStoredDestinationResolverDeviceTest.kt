/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.transfers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.KeyStore
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual private settings and AndroidKeyStore records, without sockets or shared app state. */
@RunWith(AndroidJUnit4::class)
class WebDavStoredDestinationResolverDeviceTest {
    @Test fun originalEndpointCredentialsSurviveActiveSwitchButCurrentConsentOffHoldsResolution() = fixture { f ->
        val first = f.settings.save("https://original.example/takes/", true, false)
        val original = requireNotNull(first.activeEndpointId)
        f.save(original, "original-operator", "original-secret")
        val changed = f.settings.save("https://later.example/other/", true, true)
        val current = requireNotNull(changed.activeEndpointId)
        assertNotEquals(original, current)
        f.save(current, "later-operator", "later-secret")
        val before = f.snapshot()
        val resolved = requireNotNull(f.resolver.resolve(original, 0))
        assertEquals(original, resolved.endpointId)
        assertEquals(0L, resolved.endpointRevision)
        assertEquals("https://original.example/takes/", resolved.destination.collection.toASCIIString())
        assertEquals(WebDavCredentials("original-operator", "original-secret").authorization(), resolved.destination.credentials!!.authorization())
        assertNotEquals(WebDavCredentials("later-operator", "later-secret").authorization(), resolved.destination.credentials!!.authorization())
        f.assertSnapshot(before)

        // Use a separately reopened settings writer: resolve must reread current consent, not its
        // remembered StateFlow snapshot or the original REC admission's enabled bit.
        WebDavQueueSettings(f.settingsFile).save("https://later.example/other/", false, true)
        val disabled = f.snapshot()
        assertNull(f.resolver.resolve(original, 0))
        assertNull(f.resolver.resolve(current, 0))
        assertFalse(f.settings.states.value.preferences.enabled)
        f.assertSnapshot(disabled)
        assertEquals(WebDavCredentialStatus.AVAILABLE, f.vault.status(original))
        assertEquals(WebDavCredentialStatus.AVAILABLE, f.vault.status(current))
    }

    @Test fun missingVaultReturnsHoldWithoutCreatingCredentialFilesOrKeys() = fixture { f ->
        val endpoint = requireNotNull(f.settings.save("https://example.test/takes/", true, false).activeEndpointId)
        val before = f.snapshot()
        assertNull(f.resolver.resolve(endpoint, 0))
        assertFalse(f.vaultDirectory.exists())
        assertFalse(f.keys().containsAlias(f.alias(endpoint)))
        f.assertSnapshot(before)
    }

    @Test fun lostKeyAndCorruptCiphertextRemainVisibleAndPreservedWithoutAnonymousFallback() = fixture { f ->
        val endpoint = requireNotNull(f.settings.save("https://example.test/takes/", true, false).activeEndpointId)
        f.save(endpoint, "operator", "original-secret")
        f.keys().deleteEntry(f.alias(endpoint))
        val lost = f.snapshot()
        assertThrows(WebDavCredentialUnavailable::class.java) { f.resolver.resolve(endpoint, 0) }
        assertEquals(WebDavCredentialStatus.UNAVAILABLE, f.vault.status(endpoint))
        assertFalse(f.keys().containsAlias(f.alias(endpoint)))
        f.assertSnapshot(lost)

        f.vault.clear(endpoint)
        f.save(endpoint, "operator", "replacement-after-explicit-clear")
        val record = f.record(endpoint)
        record.writeBytes(record.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
        val damaged = f.snapshot()
        assertThrows(WebDavCredentialUnavailable::class.java) { f.resolver.resolve(endpoint, 0) }
        assertEquals(WebDavCredentialStatus.UNAVAILABLE, f.vault.status(endpoint))
        assertTrue(f.keys().containsAlias(f.alias(endpoint)))
        f.assertSnapshot(damaged)
    }

    @Test fun encodedTerminalSlashLegacyProfileIsRejectedWithoutRewritingSettingsOrCredentials() = fixture { f ->
        // Schema-1 settings legitimately retain this old value. It is not a qualified collection:
        // concatenating a filename after %2F would change the raw path's segment boundary.
        val profile = f.settings.save("https://legacy.example/takes%2F", true, false)
        val endpoint = requireNotNull(profile.activeEndpointId)
        f.save(endpoint, "legacy-operator", "legacy-secret")
        val before = f.snapshot()
        assertThrows(IllegalArgumentException::class.java) { f.resolver.resolve(endpoint, 0) }
        f.assertSnapshot(before)
        val reopened = WebDavQueueSettings(f.settingsFile).snapshotForAdmission()
        assertFalse(reopened.storageFailed)
        assertEquals(profile, reopened.preferences)
        assertEquals("https://legacy.example/takes%2F", reopened.preferences.activeEndpoint!!.url)
        assertEquals(WebDavCredentialStatus.AVAILABLE, f.vault.status(endpoint))
    }

    @Test fun unknownIdentityUnsupportedRevisionAndNewlyCorruptSettingsNeverResolveOrMutateEvidence() = fixture { f ->
        val endpoint = requireNotNull(f.settings.save("https://example.test/takes/", true, false).activeEndpointId)
        f.save(endpoint, "operator", "secret")
        val before = f.snapshot()
        assertNull(f.resolver.resolve(UUID.randomUUID().toString(), 0))
        for (revision in listOf(-1L, 1L, Long.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { f.resolver.resolve(endpoint, revision) }
        }
        assertThrows(IllegalArgumentException::class.java) { f.resolver.resolve("../endpoint", 0) }
        f.assertSnapshot(before)
        assertNotNull(f.resolver.resolve(endpoint, 0))
        f.settingsFile.writeText("{damaged-current-settings")
        val damaged = f.snapshot()
        assertThrows(IllegalStateException::class.java) { f.resolver.resolve(endpoint, 0) }
        assertTrue(f.settings.states.value.storageFailed)
        f.assertSnapshot(damaged)
        assertEquals(WebDavCredentialStatus.AVAILABLE, f.vault.status(endpoint))
    }

    @Test fun lanCertificateOptInIsResolvedPerOriginalEndpointAndCanBeRevoked() = fixture { f ->
        val lanUrl = "https://192.168.1.20/takes/"
        val lan = requireNotNull(f.settings.save(lanUrl, true, false, ignoreTlsErrors = true).activeEndpointId)
        f.save(lan, "lan-operator", "lan-secret")
        val other = requireNotNull(f.settings.save("https://192.168.1.21/takes/", true, false).activeEndpointId)
        f.save(other, "other-operator", "other-secret")
        assertTrue(requireNotNull(f.resolver.resolve(lan, 0)).destination.ignoreTlsErrors)
        assertFalse(requireNotNull(f.resolver.resolve(other, 0)).destination.ignoreTlsErrors)
        val before = f.settings.states.value.preferences.revision
        WebDavQueueSettings(f.settingsFile).save(lanUrl, true, false, ignoreTlsErrors = false)
        assertFalse(requireNotNull(f.resolver.resolve(lan, 0)).destination.ignoreTlsErrors)
        assertTrue(f.settings.states.value.preferences.revision > before)
        assertEquals(WebDavCredentialStatus.AVAILABLE, f.vault.status(lan))
        assertEquals(lan, f.settings.states.value.preferences.activeEndpointId)
    }

    private class Fixture {
        private val context = ApplicationProvider.getApplicationContext<Context>()
        private val namespace = "stored-destination-${UUID.randomUUID()}"
        private val root = File(context.noBackupFilesDir, namespace)
        val settingsFile = File(root, "settings.json")
        val vaultDirectory = File(root, "credentials")
        private val prefix = "opencinecam.test.$namespace"
        val settings = WebDavQueueSettings(settingsFile)
        val vault = WebDavCredentialStore(vaultDirectory, prefix)
        val resolver = WebDavStoredDestinationResolver(settings, vault)
        private val touched = linkedSetOf<String>()
        fun alias(endpoint: String) = "$prefix.$endpoint"
        fun record(endpoint: String) = File(vaultDirectory, "$endpoint.credential")
        fun keys(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        fun save(endpoint: String, username: String, password: String) {
            touched += endpoint // Key generation can precede a failed AtomicFile save.
            vault.save(endpoint, username, password)
        }
        fun snapshot(): Map<String, ByteArray> = if (!root.exists()) emptyMap() else
            root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).path to it.readBytes() }
        fun assertSnapshot(before: Map<String, ByteArray>) {
            val after = snapshot()
            assertEquals(before.keys, after.keys)
            before.forEach { (path, bytes) -> assertArrayEquals(path, bytes, after.getValue(path)) }
        }
        fun close() {
            var failed: Throwable? = null
            fun attempt(action: () -> Unit) {
                try { action() } catch (problem: Throwable) {
                    if (failed == null) failed = problem else failed!!.addSuppressed(problem)
                }
            }
            for (endpoint in touched) attempt { vault.clear(endpoint) }
            // A failed partial save/clear must not strand another fixture key. Only this fresh,
            // private namespace is considered; application and other tests' aliases are untouched.
            attempt {
                val store = keys()
                val owned = store.aliases().toList().filter { it.startsWith("$prefix.") }
                owned.forEach { alias -> attempt { store.deleteEntry(alias) } }
                assertFalse(store.aliases().toList().any { it.startsWith("$prefix.") })
            }
            attempt { if (root.exists()) assertTrue(root.deleteRecursively()) }
            failed?.let { throw it }
        }
    }

    private fun fixture(block: (Fixture) -> Unit) {
        val fixture = Fixture()
        var primary: Throwable? = null
        try { block(fixture) } catch (problem: Throwable) { primary = problem; throw problem }
        finally {
            try { fixture.close() } catch (cleanup: Throwable) {
                if (primary == null) throw cleanup else primary.addSuppressed(cleanup)
            }
        }
    }
}
