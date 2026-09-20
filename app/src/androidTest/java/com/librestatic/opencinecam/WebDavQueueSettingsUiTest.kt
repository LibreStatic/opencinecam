/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.transfers.WebDavQueuePreferences
import com.librestatic.opencinecam.transfers.WebDavQueueSettings
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Isolated settings/UI evidence; no network requests or physical TalkBack qualification. */
class WebDavQueueSettingsUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "webdav-settings-ui-${UUID.randomUUID()}")
    private val file = File(root, "settings.json")
    private lateinit var inputModeManager: InputModeManager
    private val endpoint = "https://example.test/captures/"

    @After fun cleanupFixture() { root.deleteRecursively() }

    @Test fun defaultsAreOffAndEnrollmentToggleRequiresASavedEndpoint() {
        val repository = show()
        compose.onNodeWithTag("webdav-queue-enabled").performScrollTo().assertIsOff().assertIsNotEnabled()
        compose.onNodeWithTag("webdav-queue-cellular").performScrollTo().assertIsOff().assertIsEnabled()
        compose.onNodeWithTag("webdav-queue-ignore-tls").performScrollTo().assertIsOff().assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(WebDavQueuePreferences(), repository.states.value.preferences)
            assertFalse(repository.states.value.storageFailed)
            assertFalse(file.exists())
        }
    }

    @Test fun invalidEndpointShowsErrorWithoutChangingPreferencesAndValidCorrectionCanSave() {
        val repository = show()
        edit("http://example.test/captures/")
        save()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("webdav-queue-error").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("webdav-queue-error").performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(WebDavQueuePreferences(), repository.states.value.preferences)
            assertFalse(repository.states.value.storageFailed)
            assertFalse(file.exists())
        }
        edit(endpoint)
        save()
        awaitRevision(repository, 1)
        compose.onNodeWithTag("webdav-queue-error").assertDoesNotExist()
        assertEquals(endpoint, WebDavQueueSettings(file).states.value.preferences.activeEndpoint?.url)
    }

    @Test fun saveThenConsentAndCellularTogglesPersistSeparateExplicitRevisions() {
        val repository = show()
        edit(endpoint)
        save()
        awaitRevision(repository, 1)
        val endpointId = requireNotNull(repository.states.value.preferences.activeEndpointId)
        assertFalse(repository.states.value.preferences.enabled)
        assertFalse(repository.states.value.preferences.allowCellular)
        compose.onNodeWithTag("webdav-queue-enabled").performScrollTo().assertIsEnabled().performClick()
        awaitRevision(repository, 2)
        compose.onNodeWithTag("webdav-queue-enabled").assertIsOn()
        compose.onNodeWithTag("webdav-queue-cellular").performScrollTo().performClick()
        awaitRevision(repository, 3)
        compose.onNodeWithTag("webdav-queue-cellular").assertIsOn()
        val reopened = WebDavQueueSettings(file).states.value.preferences
        assertTrue(reopened.enabled)
        assertTrue(reopened.allowCellular)
        assertEquals(endpointId, reopened.activeEndpointId)
        assertEquals(1, reopened.endpoints.size)
        assertEquals(repository.states.value.preferences, reopened)
    }

    @Test fun endpointDraftDoesNotMutateStorageOrRideAlongWithAConsentToggle() {
        val repository = show(initialEndpoint = endpoint)
        val before = repository.states.value.preferences
        val bytes = file.readBytes()
        val draft = "https://other.test/captures/"
        edit(draft)
        compose.runOnIdle {
            assertEquals(before, repository.states.value.preferences)
            assertArrayEquals(bytes, file.readBytes())
        }
        compose.onNodeWithTag("webdav-queue-enabled").performScrollTo().performClick()
        awaitRevision(repository, 2)
        assertEquals(endpoint, repository.states.value.preferences.activeEndpoint?.url)
        assertTrue(repository.states.value.preferences.enabled)
        compose.onNodeWithTag("webdav-queue-endpoint").performScrollTo().assertTextContains(draft)
        save()
        awaitRevision(repository, 3)
        assertEquals(draft, repository.states.value.preferences.activeEndpoint?.url)
        assertNotEquals(before.activeEndpointId, repository.states.value.preferences.activeEndpointId)
        assertTrue(repository.states.value.preferences.enabled)
    }

    @Test fun mergedSwitchLabelsAndSaveRemainReadableAndReachableAtDoubleFontScale() {
        show(initialEndpoint = endpoint, fontScale = 2f)
        for ((tag, label) in listOf("webdav-queue-enabled" to R.string.webdav_queue_enabled,
            "webdav-queue-cellular" to R.string.webdav_queue_cellular,
            "webdav-queue-ignore-tls" to R.string.webdav_queue_ignore_tls_label)) {
            val node = compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
            node.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
                .assert(hasText(context.getString(label)))
                .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(context.getString(label), useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
            assertEquals(1, layouts.size)
            assertFalse("$tag label clipped: ${layouts.single().layoutInput.text}", layouts.single().hasVisualOverflow)
        }
        compose.onNodeWithTag("webdav-queue-endpoint").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("webdav-queue-save").performScrollTo().assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
    }

    @Test fun keyboardActivatesOneConsentChangeAndTraversesToCellularControl() {
        val repository = show(initialEndpoint = endpoint)
        compose.runOnIdle {
            assertTrue(inputModeManager.requestInputMode(InputMode.Keyboard))
            assertEquals(InputMode.Keyboard, inputModeManager.inputMode)
        }
        compose.onNodeWithTag("webdav-queue-enabled").performScrollTo()
            .performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
            .assertIsFocused().performKeyInput { keyDown(Key.Enter); keyUp(Key.Enter) }
        awaitRevision(repository, 2)
        assertTrue(repository.states.value.preferences.enabled)
        assertFalse(repository.states.value.preferences.allowCellular)
        // Saving briefly disables the row; explicitly refocus before separately testing Tab.
        compose.onNodeWithTag("webdav-queue-enabled").performScrollTo()
            .performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
            .assertIsFocused().performKeyInput { keyDown(Key.Tab); keyUp(Key.Tab) }
        compose.onNodeWithTag("webdav-queue-cellular").assertIsFocused()
            .performKeyInput { keyDown(Key.Enter); keyUp(Key.Enter) }
        awaitRevision(repository, 3)
        assertTrue(repository.states.value.preferences.allowCellular)
    }

    @Test fun damagedSettingsShowRecoveryErrorWithoutReplacingTheirBytes() {
        assertTrue(root.mkdirs())
        val damaged = "{damaged settings".toByteArray()
        file.writeBytes(damaged)
        val repository = show()
        compose.onNodeWithTag("webdav-queue-endpoint").assertIsNotEnabled()
        compose.onNodeWithTag("webdav-queue-save").assertIsNotEnabled()
        compose.onNodeWithTag("webdav-queue-enabled").assertIsNotEnabled()
        compose.onNodeWithTag("webdav-queue-cellular").assertIsNotEnabled()
        compose.onNodeWithTag("webdav-queue-ignore-tls").assertIsNotEnabled()
        compose.onNodeWithTag("webdav-queue-error").performScrollTo().assertIsDisplayed()
        assertTrue(repository.states.value.storageFailed)
        assertArrayEquals(damaged, file.readBytes())
    }

    @Test fun tlsExceptionRequiresSavedLocalEndpointRatherThanAnUnsavedLocalDraft() {
        val repository = show(initialEndpoint = endpoint)
        val bytes = file.readBytes()
        edit("https://192.168.1.20:8443/captures/")
        compose.onNodeWithTag("webdav-queue-ignore-tls").performScrollTo().assertIsOff().assertIsNotEnabled()
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(endpoint, repository.states.value.preferences.activeEndpoint?.url)
        save()
        awaitRevision(repository, 2)
        compose.onNodeWithTag("webdav-queue-ignore-tls").performScrollTo().assertIsOff().assertIsEnabled()
        assertFalse(requireNotNull(WebDavQueueSettings(file).states.value.preferences.activeEndpoint).ignoreTlsErrors)
    }

    @Test fun tlsToggleChangesOnlySavedProfileAndDoesNotSaveOrTrustTheCurrentDraft() {
        val local = "https://192.168.1.20:8443/captures/"
        val repository = show(initialEndpoint = local)
        val id = requireNotNull(repository.states.value.preferences.activeEndpointId)
        edit(endpoint)
        compose.onNodeWithTag("webdav-queue-ignore-tls").performScrollTo().assertIsOff().assertIsEnabled().performClick()
        awaitRevision(repository, 2)
        val enabled = WebDavQueueSettings(file).states.value.preferences
        assertEquals(id, enabled.activeEndpointId)
        assertEquals(local, enabled.activeEndpoint?.url)
        assertTrue(requireNotNull(enabled.activeEndpoint).ignoreTlsErrors)
        assertFalse(enabled.enabled)
        assertFalse(enabled.allowCellular)
        assertEquals(1, enabled.endpoints.size)
        compose.onNodeWithTag("webdav-queue-endpoint").performScrollTo().assertTextContains(endpoint)
        save()
        awaitRevision(repository, 3)
        compose.onNodeWithTag("webdav-queue-ignore-tls").performScrollTo().assertIsOff().assertIsNotEnabled()
        val public = WebDavQueueSettings(file).states.value.preferences
        assertFalse(requireNotNull(public.activeEndpoint).ignoreTlsErrors)
        assertTrue(public.endpoints.single { it.id == id }.ignoreTlsErrors)
        edit(local)
        save()
        awaitRevision(repository, 4)
        compose.onNodeWithTag("webdav-queue-ignore-tls").performScrollTo().assertIsOn().assertIsEnabled()
        assertEquals(id, repository.states.value.preferences.activeEndpointId)
    }

    @Test fun tlsConsentPersistsAcrossOtherTogglesAndCanBeExplicitlyRevoked() {
        val local = "https://127.0.0.1:8443/captures/"
        val repository = show(initialEndpoint = local)
        compose.onNodeWithTag("webdav-queue-ignore-tls").performScrollTo().performClick()
        awaitRevision(repository, 2)
        compose.onNodeWithTag("webdav-queue-enabled").performScrollTo().performClick()
        awaitRevision(repository, 3)
        compose.onNodeWithTag("webdav-queue-cellular").performScrollTo().performClick()
        awaitRevision(repository, 4)
        val before = WebDavQueueSettings(file).states.value.preferences
        assertTrue(requireNotNull(before.activeEndpoint).ignoreTlsErrors)
        compose.onNodeWithTag("webdav-queue-ignore-tls").performScrollTo().assertIsOn().performClick()
        awaitRevision(repository, 5)
        compose.onNodeWithTag("webdav-queue-ignore-tls").assertIsOff()
        val after = WebDavQueueSettings(file).states.value.preferences
        assertEquals(before.activeEndpointId, after.activeEndpointId)
        assertEquals(local, after.activeEndpoint?.url)
        assertFalse(requireNotNull(after.activeEndpoint).ignoreTlsErrors)
        assertTrue(after.enabled)
        assertTrue(after.allowCellular)
        assertEquals(1, after.endpoints.size)
    }

    private fun show(initialEndpoint: String? = null, fontScale: Float = 1f): WebDavQueueSettings {
        val repository = WebDavQueueSettings(file)
        if (initialEndpoint != null) repository.save(initialEndpoint, false, false)
        compose.setContent {
            val manager = LocalInputModeManager.current
            SideEffect { inputModeManager = manager }
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale)) {
                MaterialTheme {
                    Column(Modifier.widthIn(max = 320.dp).fillMaxWidth().heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState())) { WebDavQueueSettingsSection(repository) }
                }
            }
        }
        return repository
    }

    private fun edit(value: String) {
        compose.onNodeWithTag("webdav-queue-endpoint").performScrollTo().performTextReplacement(value)
    }
    private fun save() { compose.onNodeWithTag("webdav-queue-save").performScrollTo().assertIsEnabled().performClick() }
    private fun awaitRevision(repository: WebDavQueueSettings, expected: Long) {
        compose.waitUntil(10_000) { repository.states.value.preferences.revision >= expected }
        compose.waitForIdle()
        assertEquals(expected, repository.states.value.preferences.revision)
    }
}
