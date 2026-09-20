/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.transfers.*
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real isolated Keystore + AtomicFile behind the settings composition. */
class WebDavCredentialSettingsUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = UUID.randomUUID().toString()
    private val root = File(context.cacheDir, "credentials-ui-$id")
    private val alias = "credentials-ui-$id"
    private val store by lazy { WebDavCredentialStore(File(root, "credentials"), alias) }
    private val settings by lazy { WebDavQueueSettings(File(root, "settings.json")) }
    private val visible = mutableStateOf(true)
    private val endpoint get() = requireNotNull(settings.states.value.preferences.activeEndpointId)

    @After fun cleanup() {
        root.deleteRecursively()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.let { keys ->
            keys.aliases().toList().filter { it.startsWith("$alias.") }.forEach(keys::deleteEntry)
        }
    }

    @Test fun saveUsesKeystoreAndClearsDraftWithoutChangingConsentOrQueueSettings() {
        show()
        val before = File(root, "settings.json").readBytes()
        enter("operator", "application-secret")
        compose.onNodeWithTag("webdav-credentials-password")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        save()
        awaitStatus(WebDavCredentialStatus.AVAILABLE)
        assertCredential("operator", "application-secret")
        assertEmptyDrafts()
        assertArrayEquals(before, File(root, "settings.json").readBytes())
        assertFalse(settings.states.value.preferences.enabled)
        assertFalse(settings.states.value.preferences.allowCellular)
        root.walkTopDown().filter { it.isFile }.forEach {
            val bytes = it.readBytes().toString(Charsets.ISO_8859_1)
            assertFalse(it.name, bytes.contains("application-secret"))
            assertFalse(it.name, bytes.contains("operator"))
        }
    }

    @Test fun reopenDoesNotPrefillSecretsAndExplicitSaveReplacesThem() {
        show()
        enter("first", "old-password"); save(); awaitStatus(WebDavCredentialStatus.AVAILABLE)
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        compose.runOnIdle { visible.value = true }
        awaitStatus(WebDavCredentialStatus.AVAILABLE)
        assertEmptyDrafts()
        enter("second", "new-password"); save()
        compose.waitUntil(10_000) {
            store.load(endpoint)?.authorization() == WebDavCredentials("second", "new-password").authorization()
        }
        compose.waitForIdle()
        assertCredential("second", "new-password")
        assertEmptyDrafts()
    }

    @Test fun removalRequiresConfirmationAndDoesNotDeleteOtherEndpointCredentials() {
        show()
        val other = UUID.randomUUID().toString()
        store.save(other, "other", "retained")
        enter("operator", "remove-me"); save(); awaitStatus(WebDavCredentialStatus.AVAILABLE)
        compose.onNodeWithTag("webdav-credentials-clear").performScrollTo().performClick()
        compose.onNodeWithTag("webdav-credentials-cancel-clear").performClick()
        assertCredential("operator", "remove-me")
        compose.onNodeWithTag("webdav-credentials-clear").performScrollTo().performClick()
        compose.onNodeWithTag("webdav-credentials-confirm-clear").performClick()
        awaitStatus(WebDavCredentialStatus.MISSING)
        assertNull(store.load(endpoint))
        assertNotNull(store.load(other))
        assertNotNull(settings.states.value.preferences.activeEndpoint)
    }

    @Test fun invalidUsernameDoesNotReplaceStoredCredentials() {
        show()
        enter("operator", "retained"); save(); awaitStatus(WebDavCredentialStatus.AVAILABLE)
        enter("invalid:user", "ignored"); save()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("webdav-credentials-error").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("webdav-credentials-error").performScrollTo().assertIsDisplayed()
        assertCredential("operator", "retained")
        assertEmptyDrafts()
    }

    @Test fun changingDestinationDiscardsDraftAndNeverCopiesStoredCredentials() {
        show()
        val old = endpoint
        enter("operator", "old-destination"); save(); awaitStatus(WebDavCredentialStatus.AVAILABLE)
        enter("unsaved", "must-not-follow")
        compose.onNodeWithTag("webdav-queue-endpoint").performScrollTo().performTextReplacement("https://other.test/takes/")
        compose.onNodeWithTag("webdav-queue-save").performScrollTo().performClick()
        compose.waitUntil(10_000) { settings.states.value.preferences.activeEndpointId != old }
        awaitStatus(WebDavCredentialStatus.MISSING)
        assertEmptyDrafts()
        assertNull(store.load(endpoint))
        assertEquals(WebDavCredentials("operator", "old-destination").authorization(), store.load(old)?.authorization())
    }

    @Test fun missingKeyIsVisibleAndStatusReadPreservesCiphertext() {
        show()
        enter("operator", "lost-key"); save(); awaitStatus(WebDavCredentialStatus.AVAILABLE)
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        val original = File(root, "credentials").walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).path to it.readBytes() }
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("$alias.$endpoint")
        compose.runOnIdle { visible.value = true }
        awaitStatus(WebDavCredentialStatus.UNAVAILABLE)
        original.forEach { (path, bytes) -> assertArrayEquals(bytes, File(root, path).readBytes()) }
        compose.onNodeWithTag("webdav-credentials-clear").performScrollTo().assertIsEnabled()
        assertEmptyDrafts()
    }

    @Test fun credentialsControlsRemainReachableAndReadableAtDoubleFontScale() {
        show(fontScale = 2f)
        for (tag in listOf("webdav-credentials-username", "webdav-credentials-password", "webdav-credentials-save", "webdav-credentials-clear")) {
            compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        }
        for (resource in listOf(R.string.webdav_credentials_help, R.string.webdav_credentials_save, R.string.webdav_credentials_clear)) {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(context.getString(resource)).performScrollTo()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
            assertEquals(1, layouts.size)
            assertFalse("Overflow: ${context.getString(resource)} size=${layouts.single().size} lines=${layouts.single().lineCount}", layouts.single().hasVisualOverflow)
        }
    }

    @Test fun keyLossWhileMountedRefreshesStatusAndRequiresExplicitClearBeforeNewSave() {
        show()
        enter("operator", "lost-key"); save(); awaitStatus(WebDavCredentialStatus.AVAILABLE)
        val original = File(root, "credentials").walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).path to it.readBytes() }
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("$alias.$endpoint")
        enter("replacement", "new-secret"); save(); awaitStatus(WebDavCredentialStatus.UNAVAILABLE)
        compose.onNodeWithTag("webdav-credentials-error").performScrollTo().assertIsDisplayed()
        original.forEach { (path, bytes) -> assertArrayEquals(bytes, File(root, path).readBytes()) }
        compose.onNodeWithTag("webdav-credentials-clear").performScrollTo().performClick()
        compose.onNodeWithTag("webdav-credentials-confirm-clear").performClick()
        awaitStatus(WebDavCredentialStatus.MISSING)
        enter("replacement", "new-secret"); save(); awaitStatus(WebDavCredentialStatus.AVAILABLE)
        assertCredential("replacement", "new-secret")
        compose.onNodeWithTag("webdav-credentials-error").assertDoesNotExist()
    }

    @Test fun destinationChangeDuringPendingSaveNeverRebindsCredentialsOrItsStatus() {
        show()
        val old = endpoint
        enter("old-user", "old-destination-only")
        // Hold the actual process writer boundary, not an asynchronous fake store.
        val lock = requireNotNull(WebDavCredentialStore::class.java.getDeclaredField("processLock").apply { isAccessible = true }.get(null))
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = Thread {
            synchronized(lock) { held.countDown(); release.await() }
        }.apply { start() }
        try {
            assertTrue(held.await(10, TimeUnit.SECONDS))
            save()
            compose.waitUntil(10_000) {
                Thread.getAllStackTraces().any { (thread, stack) ->
                    thread.state == Thread.State.BLOCKED && stack.any {
                        it.className == WebDavCredentialStore::class.java.name && it.methodName == "save"
                    }
                }
            }
            compose.onNodeWithTag("webdav-queue-endpoint").performScrollTo().performTextReplacement("https://new.test/takes/")
            compose.onNodeWithTag("webdav-queue-save").performScrollTo().performClick()
            compose.waitUntil(10_000) { settings.states.value.preferences.activeEndpointId != old }
            assertEmptyDrafts()
        } finally { release.countDown(); holder.join(10_000); assertFalse(holder.isAlive) }
        awaitStatus(WebDavCredentialStatus.MISSING)
        assertNull(store.load(endpoint))
        compose.waitUntil(10_000) { store.status(old) == WebDavCredentialStatus.AVAILABLE }
        assertEquals(WebDavCredentials("old-user", "old-destination-only").authorization(), store.load(old)?.authorization())
        assertEmptyDrafts()
        // Disposing a composition cancels its result delivery, not a blocking atomic disk operation.
    }

    private fun show(fontScale: Float = 1f) {
        settings.save("https://example.test/takes/", false, false)
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale)) {
                MaterialTheme {
                    Column(Modifier.widthIn(max = 320.dp).fillMaxWidth().heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState())) {
                        if (visible.value) WebDavQueueSettingsSection(settings, store)
                    }
                }
            }
        }
        awaitStatus(WebDavCredentialStatus.MISSING)
    }
    private fun enter(user: String, password: String) {
        compose.onNodeWithTag("webdav-credentials-username").performScrollTo().performTextReplacement(user)
        compose.onNodeWithTag("webdav-credentials-password").performScrollTo().performTextReplacement(password)
    }
    private fun save() { compose.onNodeWithTag("webdav-credentials-save").performScrollTo().assertIsEnabled().performClick() }
    private fun awaitStatus(status: WebDavCredentialStatus) {
        val resource = when (status) {
            WebDavCredentialStatus.MISSING -> R.string.webdav_credentials_missing
            WebDavCredentialStatus.AVAILABLE -> R.string.webdav_credentials_available
            WebDavCredentialStatus.UNAVAILABLE -> R.string.webdav_credentials_unavailable
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("webdav-credentials-status").fetchSemanticsNodes().any {
                it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { text -> text.text == context.getString(resource) }
            }
        }
        compose.waitForIdle()
    }
    private fun assertCredential(user: String, password: String) {
        assertEquals(WebDavCredentials(user, password).authorization(), store.load(endpoint)?.authorization())
    }
    private fun assertEmptyDrafts() {
        for (tag in listOf("webdav-credentials-username", "webdav-credentials-password")) {
            assertEquals("", compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        }
    }
}
