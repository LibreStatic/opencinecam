/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** The displayed preview uses no clock or IO and never owns an active capture's frozen naming. */
internal const val CAPTURE_NAMING_EXAMPLE_UUID = "00000000-0000-4000-8000-000000000000"
internal const val CAPTURE_NAMING_EXAMPLE_EPOCH_MS = 1767323045006L

@Composable
internal fun CaptureNamingSettingsControls(settings: CameraSettings, onSettingsChange: (CameraSettings) -> Unit) {
    val saved = settings.captureNaming
    var enabled by rememberSaveable(saved) { mutableStateOf(saved.enabled) }
    var template by rememberSaveable(saved) { mutableStateOf(saved.template) }
    var excessiveInput by rememberSaveable(saved) { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val candidate = remember(enabled, template, excessiveInput) {
        if (excessiveInput) null else try { CaptureNamingSettings(enabled, validateCaptureNameTemplate(template)) }
        catch (_: IllegalArgumentException) { null }
    }
    val preview = remember(candidate, settings.productionSlate) {
        candidate?.let { captureFileStem(CAPTURE_NAMING_EXAMPLE_UUID,
            CaptureNameSnapshot(it, settings.productionSlate, CAPTURE_NAMING_EXAMPLE_EPOCH_MS)) }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.capture_naming_title), Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.capture_naming_help), Modifier.fillMaxWidth().testTag("capture-naming-help"))
        val label = stringResource(R.string.capture_naming_enabled)
        Text(label, Modifier.fillMaxWidth().testTag("capture-naming-enabled-label"))
        Switch(enabled, { enabled = it }, modifier = Modifier.heightIn(min = 48.dp)
            .testTag("capture-naming-enabled").semantics { contentDescription = label })
        Text(stringResource(R.string.capture_naming_tokens), Modifier.fillMaxWidth().testTag("capture-naming-tokens"))
        OutlinedTextField(template, { value ->
            // Preserve rejection across an IME echo of the retained text, not just one callback.
            if (value != template || !excessiveInput) {
                excessiveInput = value.length > 512
                if (!excessiveInput) template = value
            }
        }, isError = candidate == null, singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
            label = { Text(stringResource(R.string.capture_naming_template), Modifier.fillMaxWidth().testTag("capture-naming-template-label")) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("capture-naming-template"))
        if (candidate == null) Text(stringResource(R.string.capture_naming_invalid), Modifier.fillMaxWidth().testTag("capture-naming-invalid"))
        Text(stringResource(R.string.capture_naming_identity), Modifier.fillMaxWidth().testTag("capture-naming-identity"))
        Text(stringResource(R.string.capture_naming_example), Modifier.fillMaxWidth().testTag("capture-naming-example"))
        preview?.let { Text(stringResource(R.string.capture_naming_preview, it), Modifier.fillMaxWidth().testTag("capture-naming-preview")) }
        OutlinedButton(onClick = {
            candidate?.takeIf { it != settings.captureNaming }?.let {
                keyboard?.hide()
                onSettingsChange(settings.copy(captureNaming = it))
            }
        }, enabled = candidate != null && candidate != saved,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("capture-naming-save")) {
            Text(stringResource(R.string.capture_naming_save), Modifier.weight(1f).testTag("capture-naming-save-label"), textAlign = TextAlign.Center)
        }
    }
}
