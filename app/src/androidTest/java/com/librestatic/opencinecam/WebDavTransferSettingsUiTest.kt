/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.transfers.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Pure presentation: no runtime, MediaStore, credentials, settings writes or network. */
class WebDavTransferSettingsUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var inputModeManager: InputModeManager
    private val sent = mutableListOf<String>()
    private var refreshed = 0
    private var cancelled = 0

    @Test fun emptyAndUnsealedHistoryNeverProduceSendButtonsOrAutomaticActions() {
        val unsealed = WebDavOutboxBundle(uuid(1), uuid(90), 0, 0, false,
            listOf(WebDavOutboxArtifact(spec(1, 0, "unfinished.mp4"))))
        show(WebDavTransferUiState(bundles = listOf(unsealed)))
        compose.onNodeWithTag("webdav-transfer-empty").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("unfinished.mp4").assertDoesNotExist()
        compose.onNodeWithTag("webdav-transfer-send-${unsealed.id}").assertDoesNotExist()
        compose.runOnIdle { assertTrue(sent.isEmpty()); assertEquals(0, refreshed); assertEquals(0, cancelled) }
        compose.onNodeWithTag("webdav-transfer-refresh").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, refreshed); assertTrue(sent.isEmpty()) }
    }

    @Test fun explicitSendSelectsOnlyTheRequestedBundleAndDisplaysNoSourceUriOrEndpoint() {
        val first = bundle(1)
        val second = bundle(2)
        show(WebDavTransferUiState(bundles = listOf(first, second)))
        compose.runOnIdle { assertTrue(sent.isEmpty()) }
        compose.onNodeWithTag("webdav-transfer-send-${second.id}").performScrollTo()
            .assertTextEquals(text(R.string.webdav_transfer_send)).performClick()
        compose.runOnIdle { assertEquals(listOf(second.id), sent); assertEquals(0, refreshed) }
        for (value in listOf(first.endpointId, first.artifacts.first().spec.sourceUri, second.artifacts.first().spec.sourceUri)) {
            compose.onAllNodes(hasText(value, substring = true)).assertCountEquals(0)
        }
        compose.onNodeWithText("take-1.mp4").performScrollTo().assertIsDisplayed()
    }

    @Test fun uncertainCopyRequiresVerificationAndNeverAppearsCompleteFromGlobalMessage() {
        val uncertain = bundle(1, WebDavArtifactState.UNCERTAIN)
        show(WebDavTransferUiState(bundles = listOf(uncertain), message = WebDavTransferMessage.COMPLETE))
        compose.onNodeWithTag("webdav-transfer-status-${uncertain.id}").performScrollTo()
            .assertTextEquals(text(R.string.webdav_transfer_uncertain))
        compose.onNodeWithTag("webdav-transfer-send-${uncertain.id}").performScrollTo()
            .assertTextEquals(text(R.string.webdav_transfer_verify)).performClick()
        compose.onNodeWithText(text(R.string.webdav_transfer_verified_bundle)).assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(uncertain.id), sent) }
    }

    @Test fun completeConflictAndActiveBundlesOfferNoUnsafeResendWhileSourceRecoveryStaysExplicit() {
        val complete = bundle(1, WebDavArtifactState.VERIFIED)
        val conflict = bundle(2, WebDavArtifactState.CONFLICT)
        val active = bundle(3, WebDavArtifactState.UPLOADING)
        val source = bundle(4, WebDavArtifactState.SOURCE_UNAVAILABLE)
        show(WebDavTransferUiState(bundles = listOf(complete, conflict, active, source)))
        for (bundle in listOf(complete, conflict, active)) compose.onNodeWithTag("webdav-transfer-send-${bundle.id}").assertDoesNotExist()
        compose.onNodeWithTag("webdav-transfer-status-${complete.id}").performScrollTo()
            .assertTextEquals(text(R.string.webdav_transfer_verified_bundle))
        compose.onNodeWithTag("webdav-transfer-artifact-${conflict.artifacts.first().spec.id}").performScrollTo()
            .assertTextContains(text(R.string.webdav_transfer_conflict), substring = true)
        compose.onNodeWithTag("webdav-transfer-artifact-${source.artifacts.first().spec.id}").performScrollTo()
            .assertTextContains(text(R.string.webdav_transfer_source_unavailable), substring = true)
        compose.onNodeWithTag("webdav-transfer-send-${source.id}").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(source.id), sent) }
    }

    @Test fun eachArtifactRetainsItsOwnStatusUntilTheWholeBundleIsVerified() {
        val video = bundle(1, WebDavArtifactState.VERIFIED)
        val metadata = WebDavOutboxArtifact(spec(1, 1, "take-1.json"), revision = 1, modifiedSeconds = 7)
        val mixed = video.copy(artifacts = video.artifacts + metadata)
        show(WebDavTransferUiState(bundles = listOf(mixed)))
        compose.onNodeWithTag("webdav-transfer-status-${mixed.id}").performScrollTo()
            .assertTextEquals(text(R.string.webdav_transfer_queued))
        compose.onNodeWithTag("webdav-transfer-artifact-${video.artifacts.single().spec.id}").performScrollTo()
            .assertTextEquals(context.getString(R.string.webdav_transfer_artifact_status,
                text(R.string.webdav_transfer_video), text(R.string.webdav_transfer_verified_artifact)))
        compose.onNodeWithTag("webdav-transfer-artifact-${metadata.spec.id}").performScrollTo()
            .assertTextEquals(context.getString(R.string.webdav_transfer_artifact_status,
                text(R.string.webdav_transfer_video_metadata), text(R.string.webdav_transfer_queued)))
        compose.onNodeWithText(text(R.string.webdav_transfer_verified_bundle)).assertDoesNotExist()
        compose.onNodeWithTag("webdav-transfer-send-${mixed.id}").performScrollTo().assertIsEnabled()
    }

    @Test fun busyDisablesNewRequestsShowsSelectedTakeAndKeepsCancellationReachable() {
        val first = bundle(1)
        val second = bundle(2)
        val state = show(WebDavTransferUiState(busy = true, bundles = listOf(first, second),
            message = WebDavTransferMessage.SENDING, activeBundleId = first.id))
        compose.onNodeWithTag("webdav-transfer-refresh").performScrollTo().assertIsNotEnabled()
        for (bundle in listOf(first, second)) compose.onNodeWithTag("webdav-transfer-send-${bundle.id}").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.webdav_transfer_selected)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("webdav-transfer-cancel").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, cancelled); assertTrue(sent.isEmpty()); assertEquals(0, refreshed) }
        // UI cancellation does not fabricate retirement or clear busy before the runtime reports it.
        compose.onNodeWithTag("webdav-transfer-send-${second.id}").assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(busy = false, message = WebDavTransferMessage.CANCELLED, activeBundleId = null) }
        compose.onNodeWithTag("webdav-transfer-cancel").assertDoesNotExist()
        compose.onNodeWithTag("webdav-transfer-send-${second.id}").assertIsEnabled()
    }

    @Test fun recordingReservationDisablesRequestsAndEachRuntimeOutcomeHasALocalizedMessage() {
        val bundle = bundle(1)
        val state = show(WebDavTransferUiState(bundles = listOf(bundle)))
        val messages = listOf(
            WebDavTransferMessage.IDLE to R.string.webdav_transfer_idle,
            WebDavTransferMessage.SENDING to R.string.webdav_transfer_sending,
            WebDavTransferMessage.VERIFYING to R.string.webdav_transfer_verifying,
            WebDavTransferMessage.COMPLETE to R.string.webdav_transfer_complete,
            WebDavTransferMessage.WAITING_RECORDING to R.string.webdav_transfer_waiting_recording,
            WebDavTransferMessage.WAITING_MEDIA to R.string.webdav_transfer_waiting_media,
            WebDavTransferMessage.DISABLED to R.string.webdav_transfer_disabled,
            WebDavTransferMessage.NETWORK_UNAVAILABLE to R.string.webdav_transfer_network_unavailable,
            WebDavTransferMessage.CELLULAR_CONSENT_REQUIRED to R.string.webdav_transfer_cellular_consent_required,
            WebDavTransferMessage.AUTHENTICATION to R.string.webdav_transfer_authentication,
            WebDavTransferMessage.CONFLICT to R.string.webdav_transfer_conflict,
            WebDavTransferMessage.SOURCE_UNAVAILABLE to R.string.webdav_transfer_source_unavailable,
            WebDavTransferMessage.UNCERTAIN to R.string.webdav_transfer_uncertain,
            WebDavTransferMessage.ERROR to R.string.webdav_transfer_error,
            WebDavTransferMessage.CANCELLED to R.string.webdav_transfer_cancelled,
        )
        assertEquals(WebDavTransferMessage.entries.toSet(), messages.map { it.first }.toSet())
        for ((message, resource) in messages) {
            compose.runOnIdle { state.value = state.value.copy(message = message) }
            compose.onNodeWithTag("webdav-transfer-message").performScrollTo().assertTextEquals(text(resource))
        }
        for (waiting in listOf(WebDavTransferMessage.WAITING_RECORDING, WebDavTransferMessage.WAITING_MEDIA)) {
            compose.runOnIdle { state.value = state.value.copy(message = waiting) }
            compose.onNodeWithTag("webdav-transfer-refresh").assertIsNotEnabled()
            compose.onNodeWithTag("webdav-transfer-send-${bundle.id}").assertIsNotEnabled()
            compose.onNodeWithTag("webdav-transfer-cancel").assertDoesNotExist()
        }
        compose.runOnIdle { assertTrue(sent.isEmpty()); assertEquals(0, refreshed) }
    }

    @Test fun doubleFontScaleKeepsButtonsAtLeast48DpAndLongNamesAndStatesUnclipped() {
        val bundle = bundle(1, WebDavArtifactState.UNCERTAIN, "A long production take with several descriptive words and a camera number 001.mp4")
        show(WebDavTransferUiState(busy = true, bundles = listOf(bundle), message = WebDavTransferMessage.VERIFYING,
            activeBundleId = bundle.id), fontScale = 2f)
        for (tag in listOf("webdav-transfer-refresh", "webdav-transfer-cancel", "webdav-transfer-send-${bundle.id}")) {
            compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        }
        for (label in listOf(bundle.artifacts.first().spec.sourceName, text(R.string.webdav_transfer_verify), text(R.string.webdav_transfer_cancel))) {
            val node = compose.onNodeWithText(label, useUnmergedTree = true).performScrollTo()
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
            assertEquals(1, layouts.size)
            assertFalse("Clipped transfer label", layouts.single().hasVisualOverflow)
        }
    }

    @Test fun keyboardCanRequestOneExplicitSendWithoutAnyAutomaticWork() {
        val bundle = bundle(1)
        show(WebDavTransferUiState(bundles = listOf(bundle)))
        compose.runOnIdle { assertTrue(inputModeManager.requestInputMode(InputMode.Keyboard)); assertTrue(sent.isEmpty()) }
        compose.onNodeWithTag("webdav-transfer-send-${bundle.id}").performScrollTo()
            .performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
            .assertIsFocused().performKeyInput { keyDown(Key.Enter); keyUp(Key.Enter) }
        compose.runOnIdle { assertEquals(listOf(bundle.id), sent); assertEquals(0, refreshed); assertEquals(0, cancelled) }
    }

    private fun show(initial: WebDavTransferUiState, fontScale: Float = 1f): MutableState<WebDavTransferUiState> {
        val state = mutableStateOf(initial)
        compose.setContent {
            val manager = LocalInputModeManager.current
            SideEffect { inputModeManager = manager }
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale)) {
                MaterialTheme {
                    Column(Modifier.widthIn(max = 320.dp).fillMaxWidth().heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState()).padding(16.dp)) {
                        WebDavTransferSettingsSection(state.value, { refreshed++ }, { sent += it }, { cancelled++ })
                    }
                }
            }
        }
        return state
    }
    private fun text(resource: Int) = context.getString(resource)
    private fun uuid(value: Int) = value.toString().padStart(8, '0') + "-0000-0000-0000-000000000000"
    private fun spec(bundle: Int, index: Int, name: String) = WebDavArtifactSpec(uuid(bundle * 10 + index),
        if (index == 0) WebDavArtifactRole.VIDEO else WebDavArtifactRole.VIDEO_METADATA,
        if (index == 0) "content://media/external/video/media/$bundle" else "content://media/external/downloads/$bundle", name, 123)
    private fun bundle(number: Int, state: WebDavArtifactState = WebDavArtifactState.QUEUED, name: String = "take-$number.mp4"): WebDavOutboxBundle {
        val spec = spec(number, 0, name)
        val admission = WebDavOutboxAdmission(uuid(88), 1, WebDavUploadPolicy(enabled = true, network = WebDavNetwork.WIFI))
        val artifact = WebDavOutboxArtifact(spec, revision = 1, modifiedSeconds = 7,
            sha256 = if (state == WebDavArtifactState.QUEUED) null else "a".repeat(64), state = state,
            attempt = if (state == WebDavArtifactState.UPLOADING) WebDavOutboxAttempt(uuid(77), WebDavAttemptKind.PUT, admission.processToken) else null,
            lastAdmission = if (state == WebDavArtifactState.QUEUED) null else admission,
            remoteMayExist = state != WebDavArtifactState.QUEUED,
            sourceFailure = if (state == WebDavArtifactState.SOURCE_UNAVAILABLE) WebDavSourceFailure(spec.sourceUri, WebDavSourceFailureReason.CONTENT_CHANGED) else null)
        return WebDavOutboxBundle(uuid(number), uuid(90), 0, 1, true, listOf(artifact))
    }
}
