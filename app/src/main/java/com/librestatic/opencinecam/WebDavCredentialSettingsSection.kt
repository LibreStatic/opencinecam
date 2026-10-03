/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.librestatic.opencinecam.transfers.WebDavCredentialStore
import com.librestatic.opencinecam.transfers.WebDavCredentialStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Caller keys this entire composition by endpoint identity, not the editable URL. */
@Composable internal fun WebDavCredentialSettingsSection(endpointId: String, store: WebDavCredentialStore) {
    var status by remember { mutableStateOf<WebDavCredentialStatus?>(null) }
    // Deliberately not saveable, prefilled from disk, or included in queue/preset settings.
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { username = ""; password = ""; confirmClear = false }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); username = ""; password = "" }
    }
    LaunchedEffect(endpointId, store) {
        try { status = withContext(Dispatchers.IO) { store.status(endpointId) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { status = WebDavCredentialStatus.UNAVAILABLE; failed = true }
        finally { busy = false }
    }
    fun change(clear: Boolean) {
        if (busy) return
        val submittedUser = username
        val submittedPassword = password
        busy = true; failed = false; confirmClear = false
        // Remove editable copies immediately; never show existing stored credentials.
        username = ""; password = ""
        scope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) {
                    val applied = try {
                        if (clear) store.clear(endpointId) else store.save(endpointId, submittedUser, submittedPassword)
                        true
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { false }
                    // A failed operation can reveal key loss while the screen remains mounted.
                    // Never leave a stale AVAILABLE badge after that failure.
                    store.status(endpointId) to !applied
                }
                status = outcome.first
                failed = outcome.second
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { status = WebDavCredentialStatus.UNAVAILABLE; failed = true }
            finally { busy = false }
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsSectionTitle(stringResource(R.string.webdav_credentials_title), help = stringResource(R.string.webdav_credentials_help), helpTag = "webdav-credentials-help")
        Text(stringResource(when (status) {
            WebDavCredentialStatus.AVAILABLE -> R.string.webdav_credentials_available
            WebDavCredentialStatus.MISSING -> R.string.webdav_credentials_missing
            WebDavCredentialStatus.UNAVAILABLE -> R.string.webdav_credentials_unavailable
            null -> R.string.webdav_credentials_loading
        }), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("webdav-credentials-status"))
        val colors = OutlinedTextFieldDefaults.colors(focusedTextColor = MaterialTheme.colorScheme.onSurface, unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            focusedLabelColor = MaterialTheme.colorScheme.onSurface, unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(username, { if (it.length <= 8192) username = it }, singleLine = true,
            label = { Text(stringResource(R.string.webdav_credentials_username)) }, colors = colors,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false), enabled = !busy,
            modifier = Modifier.fillMaxWidth().testTag("webdav-credentials-username"))
        OutlinedTextField(password, { if (it.length <= 8192) password = it }, singleLine = true,
            label = { Text(stringResource(R.string.webdav_credentials_password)) }, colors = colors,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), enabled = !busy,
            modifier = Modifier.fillMaxWidth().testTag("webdav-credentials-password"))
        Button(onClick = { change(false) }, enabled = !busy && username.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("webdav-credentials-save")) {
            Text(stringResource(R.string.webdav_credentials_save), Modifier.weight(1f), textAlign = TextAlign.Center)
        }
        OutlinedButton(onClick = { confirmClear = true }, enabled = !busy && status != null && status != WebDavCredentialStatus.MISSING,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("webdav-credentials-clear")) {
            Text(stringResource(R.string.webdav_credentials_clear), Modifier.weight(1f), textAlign = TextAlign.Center)
        }
        if (failed) Text(stringResource(R.string.webdav_credentials_failed), color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("webdav-credentials-error"))
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
        title = { Text(stringResource(R.string.webdav_credentials_clear)) },
        text = { Text(stringResource(R.string.webdav_credentials_clear_help)) },
        confirmButton = { TextButton(onClick = { change(true) }, modifier = Modifier.testTag("webdav-credentials-confirm-clear")) {
            Text(stringResource(R.string.webdav_credentials_clear))
        } },
        dismissButton = { TextButton(onClick = { confirmClear = false }, modifier = Modifier.testTag("webdav-credentials-cancel-clear")) {
            Text(stringResource(R.string.webdav_credentials_cancel))
        } })
}
