/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.share

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.PersistableBundle
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.librestatic.opencinecam.R
import com.librestatic.opencinecam.SettingsCard
import com.librestatic.opencinecam.SettingsHeading
import com.librestatic.opencinecam.SettingsHelp
import com.librestatic.opencinecam.SettingsMuted
import com.librestatic.opencinecam.SettingsSwitchRow
import com.librestatic.opencinecam.SettingsTopBar
import com.librestatic.opencinecam.settingsSideGutterDp
import com.librestatic.opencinecam.ui.theme.LocalCineColors

/** The Network drive page, bound to [NetworkShare]. */
@Composable
internal fun NetworkDriveScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val state by NetworkShare.state.collectAsState()
    val takeActive by NetworkShare.takeActive.collectAsState()
    val showOriginals by remember(context) { NetworkShare.showOriginals(context) }.collectAsState()
    NetworkDriveContent(
        state = state,
        takeActive = takeActive,
        showOriginals = showOriginals,
        onEnabledChange = { on -> if (on) NetworkShare.start(context) else NetworkShare.stop(context) },
        onShowOriginalsChange = { NetworkShare.setShowOriginals(context, it) },
        onCopy = { label, text, sensitive -> copyToClipboard(context, label, text, sensitive) },
        onBack = onBack,
    )
}

/**
 * The Network drive page (OCC-PLAN-071 §8, route 3): the switch and its state, each address
 * with the user and password to copy, how to connect from each desktop, whether the take
 * folders show the original `.ocraw` (off by default: Windows Explorer cannot copy past 4 GB),
 * and the notes that matter (Windows' 50 MB default and 4 GB ceiling, copy before editing, the
 * automatic stop). Stateless apart from
 * the password's reveal toggle (hidden by default, [initialPasswordRevealed] for previews), so
 * Compose Driver renders it with fake state. While the share runs, the window is FLAG_SECURE:
 * no screenshots, no recents thumbnail, nothing on a cast or recorded screen.
 */
@Composable
internal fun NetworkDriveContent(
    state: NetworkShareState,
    takeActive: Boolean,
    showOriginals: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onShowOriginalsChange: (Boolean) -> Unit,
    onCopy: (label: String, text: String, sensitive: Boolean) -> Unit,
    onBack: () -> Unit,
    initialPasswordRevealed: Boolean = false,
) {
    val running = state as? NetworkShareState.Running
    if (running != null) SecureWindowWhileShown()
    var passwordRevealed by rememberSaveable { mutableStateOf(initialPasswordRevealed) }
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val gutter = settingsSideGutterDp(maxWidth.value)
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("network-drive-page"),
            contentPadding = PaddingValues(horizontal = gutter.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "header") { SettingsTopBar(stringResource(R.string.network_share_title), onBack, backTag = "network-drive-back") }
            item(key = "intro") { Text(stringResource(R.string.network_share_intro), color = SettingsMuted, fontSize = 13.sp) }
            item(key = "switch") {
                SettingsCard {
                    val on = state !is NetworkShareState.Stopped
                    SettingsSwitchRow(stringResource(R.string.network_share_switch), on, onEnabledChange, "network-drive-switch",
                        enabled = on || !takeActive)
                    Text(statusText(state, takeActive), color = statusColor(state), fontSize = 13.sp, modifier = Modifier.testTag("network-drive-status"))
                    SettingsHelp(stringResource(R.string.network_share_security_help_summary), stringResource(R.string.network_share_security_help),
                        tag = "network-drive-security-help")
                }
            }
            if (running != null) item(key = "credentials") {
                SettingsCard(Modifier.testTag("network-drive-credentials")) {
                    SettingsHeading(stringResource(R.string.network_share_addresses_title))
                    running.urls.forEachIndexed { index, url ->
                        CopyRow(stringResource(R.string.network_share_address), url, "network-drive-url-$index") { onCopy(it, url, false) }
                    }
                    CopyRow(stringResource(R.string.network_share_user), running.user, "network-drive-user") { onCopy(it, running.user, false) }
                    // Grouped in fours to read off the screen; Copy copies it without the spaces.
                    val shown = if (passwordRevealed) running.password.chunked(4).joinToString(" ")
                        else running.password.chunked(4).joinToString(" ") { "•".repeat(it.length) }
                    CopyRow(stringResource(R.string.network_share_password), shown, "network-drive-password") { onCopy(it, running.password, true) }
                    TextButton({ passwordRevealed = !passwordRevealed }, Modifier.heightIn(min = 48.dp).testTag("network-drive-password-reveal")) {
                        Text(stringResource(if (passwordRevealed) R.string.network_share_hide_password else R.string.network_share_show_password))
                    }
                }
            }
            item(key = "connect") {
                SettingsCard(Modifier.testTag("network-drive-connect")) {
                    SettingsHeading(stringResource(R.string.network_share_connect_title))
                    val example = running?.urls?.firstOrNull()
                    Text(stringResource(R.string.network_share_connect_windows), color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, modifier = Modifier.testTag("network-drive-windows"))
                    SettingsHelp(stringResource(R.string.network_share_windows_limit_help_summary), stringResource(R.string.network_share_windows_limit_help),
                        tag = "network-drive-windows-limit-help")
                    Text(stringResource(R.string.network_share_connect_macos), color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, modifier = Modifier.testTag("network-drive-macos"))
                    Text(stringResource(R.string.network_share_connect_linux, example?.replaceFirst("http://", "dav://") ?: "dav://…:8642/"),
                        color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, modifier = Modifier.testTag("network-drive-linux"))
                }
            }
            item(key = "originals") {
                // Applies to a running drive on its next request, so it stays enabled either way.
                SettingsCard {
                    SettingsSwitchRow(stringResource(R.string.network_share_show_originals), showOriginals, onShowOriginalsChange,
                        "network-drive-show-originals")
                    SettingsHelp(stringResource(R.string.network_share_show_originals_help_summary), stringResource(R.string.network_share_show_originals_help),
                        tag = "network-drive-show-originals-help")
                }
            }
            item(key = "notes") {
                SettingsCard {
                    Text(stringResource(R.string.network_share_copy_advice), color = SettingsMuted, fontSize = 13.sp, modifier = Modifier.testTag("network-drive-copy-advice"))
                    Text(stringResource(R.string.network_share_auto_stop), color = SettingsMuted, fontSize = 13.sp, modifier = Modifier.testTag("network-drive-auto-stop"))
                }
            }
        }
    }
}

/** FLAG_SECURE on the activity window while composed; cleared on leaving unless it was already set. */
@Composable
private fun SecureWindowWhileShown() {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(activity) {
        val window = activity.window
        val alreadySecure = window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        if (!alreadySecure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (!alreadySecure) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun CopyRow(label: String, value: String, tag: String, onCopy: (String) -> Unit) {
    val copyLabel = stringResource(R.string.network_share_copy_label, label)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = SettingsMuted, fontSize = 12.sp)
            Text(value, color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.testTag(tag))
        }
        TextButton({ onCopy(label) }, Modifier.heightIn(min = 48.dp).testTag("$tag-copy").semantics { contentDescription = copyLabel }) {
            Text(stringResource(R.string.network_share_copy))
        }
    }
}

@Composable
private fun statusText(state: NetworkShareState, takeActive: Boolean): String = when (state) {
    NetworkShareState.Starting -> stringResource(R.string.network_share_state_starting)
    is NetworkShareState.Running -> stringResource(R.string.network_share_state_running)
    is NetworkShareState.Stopped -> if (takeActive) stringResource(R.string.network_share_refused_recording) else when (state.reason) {
        null, NetworkShareStopReason.OPERATOR -> stringResource(R.string.network_share_state_stopped)
        NetworkShareStopReason.IDLE -> stringResource(R.string.network_share_stopped_idle)
        NetworkShareStopReason.NO_NETWORK -> stringResource(R.string.network_share_no_network)
        NetworkShareStopReason.RECORDING -> stringResource(R.string.network_share_stopped_recording)
        NetworkShareStopReason.PORT_BUSY -> stringResource(R.string.network_share_port_busy)
        NetworkShareStopReason.TIMEOUT -> stringResource(R.string.network_share_stopped_timeout)
        NetworkShareStopReason.FAILED -> stringResource(R.string.network_share_failed)
    }
}

@Composable
private fun statusColor(state: NetworkShareState) = when (state) {
    is NetworkShareState.Running -> LocalCineColors.current.verified
    is NetworkShareState.Stopped -> if (state.reason == null || state.reason == NetworkShareStopReason.OPERATOR) SettingsMuted else LocalCineColors.current.pending
    NetworkShareState.Starting -> SettingsMuted
}

private fun copyToClipboard(context: Context, label: String, text: String, sensitive: Boolean) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText(label, text)
    // The password stays out of the clipboard preview and keyboard suggestions.
    if (sensitive) clip.description.extras = PersistableBundle().apply {
        putBoolean(if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE", true)
    }
    clipboard.setPrimaryClip(clip)
    // Android 13 and later confirm a copy themselves.
    if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, R.string.network_share_copied, Toast.LENGTH_SHORT).show()
}
