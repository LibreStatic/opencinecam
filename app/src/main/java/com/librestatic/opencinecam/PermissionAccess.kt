package com.librestatic.opencinecam

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Whether Android will no longer show the permission prompt, so only the app's system settings can grant it.
 *
 * [rationaleSeen] records that the system once asked for a rationale, i.e. the user really denied once.
 * Without it, a prompt dismissed with Back (which also leaves the rationale false) would look blocked.
 */
internal fun isPermissionBlocked(granted: Boolean, showRationale: Boolean, rationaleSeen: Boolean): Boolean =
    !granted && !showRationale && rationaleSeen

internal fun Context.isPermissionGranted(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

internal tailrec fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
}

/** Opens this app's page in the system settings, where a blocked permission can still be granted. */
internal fun openAppPermissionSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

private const val PREFS = "permission_access"

private fun rationaleSeenKey(permission: String) = "rationale_seen:$permission"

/** Remembers, across restarts, that the system asked for a rationale for [permission]. */
private fun noteRationale(context: Context, permission: String): Boolean {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val showing = context.findHostActivity()?.shouldShowRequestPermissionRationale(permission) == true
    if (showing && !prefs.getBoolean(rationaleSeenKey(permission), false)) {
        prefs.edit().putBoolean(rationaleSeenKey(permission), true).apply()
    }
    return showing
}

/** Whether [permission] can now be granted only from the system settings. */
internal fun permissionBlocked(context: Context, permission: String): Boolean {
    val granted = context.isPermissionGranted(permission)
    val showing = noteRationale(context, permission)
    val seen = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(rationaleSeenKey(permission), false)
    return isPermissionBlocked(granted, showing, seen)
}

internal class PermissionAccess(
    val granted: Boolean,
    val blocked: Boolean,
    /** Shows the system prompt, or the app's settings page once the prompt is blocked. */
    val request: () -> Unit,
)

/**
 * Tracks one runtime permission: re-checks it on every resume (it may change in the system settings)
 * and routes [PermissionAccess.request] to the app's settings page when the system prompt is blocked.
 */
@Composable
internal fun rememberPermissionAccess(permission: String, onResult: (Boolean) -> Unit = {}): PermissionAccess {
    val context = androidx.compose.ui.platform.LocalContext.current
    var granted by remember(permission) { mutableStateOf(context.isPermissionGranted(permission)) }
    var blocked by remember(permission) { mutableStateOf(!granted && permissionBlocked(context, permission)) }
    val currentOnResult by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { result ->
        granted = result || context.isPermissionGranted(permission)
        blocked = !granted && permissionBlocked(context, permission)
        currentOnResult(granted)
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, permission) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val now = context.isPermissionGranted(permission)
                blocked = !now && permissionBlocked(context, permission)
                if (now != granted) {
                    granted = now
                    currentOnResult(now)
                }
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return PermissionAccess(granted, blocked) {
        if (blocked) openAppPermissionSettings(context) else {
            noteRationale(context, permission)
            launcher.launch(permission)
        }
    }
}
