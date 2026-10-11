/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.splashscreen.SplashScreenViewProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.librestatic.opencinecam.service.CaptureStartup
import com.librestatic.opencinecam.ui.theme.AppTheme
import com.librestatic.opencinecam.ui.theme.AppThemeStore
import com.librestatic.opencinecam.ui.theme.OpenCineCamTheme
import com.librestatic.opencinecam.ui.theme.SyncWindowBackground
import com.librestatic.opencinecam.ui.theme.rememberAppTheme

class MainActivity : ComponentActivity() {
    private val operatorKeys = OperatorKeyLatch()
    private val shortcuts = ShortcutDispatcher()
    private val locationOwner = Any()
    private var operatorKeyOwner: Any? = null
    private var operatorKeyMapping: ((Int) -> OperatorAction?)? = null
    internal var operatorAction: ((OperatorAction) -> Unit)? = null
    internal fun installOperatorKeys(owner: Any, mapping: (Int) -> OperatorAction?) {
        operatorKeys.clear(); operatorKeyOwner = owner; operatorKeyMapping = mapping
    }
    internal fun removeOperatorKeys(owner: Any) {
        if (operatorKeyOwner !== owner) return
        operatorKeys.clear(); operatorKeyOwner = null; operatorKeyMapping = null; operatorAction = null
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) operatorKeys.clear()
    }
    override fun onResume() { super.onResume(); CaptureLocations.get(this).setForeground(locationOwner, true) }
    override fun onStop() { CaptureStartup.stopped(); super.onStop() }
    override fun onPause() { CaptureLocations.get(this).setForeground(locationOwner, false); operatorKeys.clear(); super.onPause() }
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean =
        handleOperatorKey(keyCode, event, down = true) || handleShortcut(keyCode, event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean =
        handleOperatorKey(keyCode, event, down = false) || super.onKeyUp(keyCode, event)

    private fun handleOperatorKey(keyCode: Int, event: android.view.KeyEvent, down: Boolean): Boolean {
        if (keyCode != android.view.KeyEvent.KEYCODE_VOLUME_UP && keyCode != android.view.KeyEvent.KEYCODE_VOLUME_DOWN) return false
        val mapping = operatorKeyMapping?.invoke(keyCode)
        val imeVisible = androidx.core.view.ViewCompat.getRootWindowInsets(window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        val eligible = !imeVisible && hasWindowFocus() && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) && mapping != null
        return operatorKeys.dispatch(keyCode, down, event.repeatCount, event.isCanceled,
            eligible, mapping ?: OperatorAction.SYSTEM_VOLUME) { operatorAction?.invoke(it) }
    }
    /** Keyboard shortcuts get only the presses the window's views did not use (a focused text field keeps its letters). */
    private fun handleShortcut(keyCode: Int, event: android.view.KeyEvent): Boolean {
        val action = shortcutActionFor(Key(keyCode), event.isShiftPressed, event.isCtrlPressed, event.isAltPressed,
            event.isMetaPressed, event.repeatCount > 0) ?: return false
        return shortcuts.dispatch(action)
    }
    private lateinit var foldDisplays: FoldDisplayCoordinator
    private val splashHandoff = mutableStateOf(SplashHandoff(onScreen = true))
    private var contentReady = false
    private var heldSplash: SplashScreenViewProvider? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        // installSplashScreen applied the Cine post-splash theme; Material You needs its own window colour.
        val theme = AppThemeStore(this).load()
        setTheme(windowThemeFor(theme))
        super.onCreate(savedInstanceState)
        val onboardingDone = OnboardingStore(this).isCompleted()
        // The camera starts opening now, while the splash is up and Compose builds the first frame.
        // The wizard comes first on a first run, so the camera can wait for it there.
        if (onboardingDone && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            val window = viewfinderWindowSize()
            CaptureStartup.begin(this, window.width, window.height)
        }
        applySplashTheme(this, theme)
        com.librestatic.opencinecam.storage.MediaProxyQueue.get(this).start()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // With the bars hidden, the default cutout mode letterboxes the window below the camera
        // cutout, so a full-screen viewfinder could never reach the top edge. Every screen already
        // pads its content by safeDrawing; Android 15+ applies ALWAYS by itself.
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        foldDisplays = FoldDisplayCoordinator(this)

        // The logo intro plays once per process, on a start from scratch that lands on the wizard.
        val playIntro = savedInstanceState == null && !introPlayed && !onboardingDone
        introPlayed = true
        splashHandoff.value = SplashHandoff(onScreen = savedInstanceState == null, playIntro = playIntro)
        val start = SystemClock.uptimeMillis()
        splashScreen.setOnExitAnimationListener { provider ->
            if (!playIntro) {
                // A normal start keeps the splash on top until the viewfinder shows its first frame,
                // so "Preparing camera" never flashes by. The window keeps drawing underneath (a
                // keep-on-screen condition would stop it), so lifting the splash shows a finished
                // frame at once. Never for long: a slow or failing camera must not trap the user.
                heldSplash = provider
                if (contentReady) releaseSplash()
                else window.decorView.postDelayed(::releaseSplash, (KeepSplashMaxMillis - (SystemClock.uptimeMillis() - start)).coerceAtLeast(0))
                return@setOnExitAnimationListener
            }
            val bounds = runCatching {
                val icon = provider.iconView
                // An icon-less splash hands back a detached, empty stand-in view; its zero bounds
                // would draw the intro logo at 0 px, so the wizard centres it instead.
                if (!icon.isAttachedToWindow || icon.width <= 0 || icon.height <= 0) return@runCatching null
                val location = IntArray(2).also(icon::getLocationInWindow)
                Rect(Offset(location[0].toFloat(), location[1].toFloat()), Size(icon.width.toFloat(), icon.height.toFloat()))
            }.getOrNull()
            splashHandoff.value = splashHandoff.value.copy(onScreen = false, iconBounds = bounds)
            // The wizard draws the same logo in the same place, so the splash fades over an identical
            // frame. The short delay lets Compose draw one frame with the real bounds first.
            provider.view.animate().alpha(0f).setStartDelay(SplashFadeDelayMillis).setDuration(SplashFadeMillis)
                .withEndAction(provider::remove).start()
        }
        setContent {
            CompositionLocalProvider(LocalFoldDisplayCoordinator provides foldDisplays, LocalShortcutDispatcher provides shortcuts) {
                OpenCineCamApp(splash = splashHandoff.value, onReady = { contentReady = true; releaseSplash() })
            }
        }
    }

    /**
     * Lifts a held splash, showing the live viewfinder and handing over the controls. A very short
     * fade keeps a fast camera from snapping the logo away.
     */
    private fun releaseSplash() {
        val provider = heldSplash ?: return
        heldSplash = null
        splashHandoff.value = splashHandoff.value.copy(onScreen = false)
        provider.view.animate().alpha(0f).setDuration(SplashReleaseFadeMillis).withEndAction(provider::remove).start()
    }

    override fun onDestroy() {
        CaptureLocations.get(this).setForeground(locationOwner, false)
        if (::foldDisplays.isInitialized) foldDisplays.close()
        super.onDestroy()
    }

    private companion object {
        var introPlayed = false
        const val SplashFadeMillis = 200L
        const val SplashFadeDelayMillis = 34L
        const val KeepSplashMaxMillis = 2_000L
        const val SplashReleaseFadeMillis = 120L
    }
}

/** The window the viewfinder fills: the app draws edge to edge, cutout included. */
private fun Activity.viewfinderWindowSize(): android.util.Size =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        windowManager.currentWindowMetrics.bounds.let { android.util.Size(it.width(), it.height()) }
    } else {
        @Suppress("DEPRECATION")
        android.graphics.Point().also { windowManager.defaultDisplay.getRealSize(it) }.let { android.util.Size(it.x, it.y) }
    }

internal fun windowThemeFor(theme: AppTheme): Int =
    if (theme == AppTheme.YOU) R.style.Theme_OpenCineCam_You else R.style.Theme_OpenCineCam

/**
 * The platform draws the next splash before any app code runs, so on Android 13+ it is told ahead
 * of time which splash matches the chosen theme. Older versions keep the Cine splash; its short
 * fade covers the change of colour.
 */
internal fun applySplashTheme(activity: Activity, theme: AppTheme) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val splash = if (theme == AppTheme.YOU) R.style.Theme_OpenCineCam_Starting_You else R.style.Theme_OpenCineCam_Starting
    runCatching { activity.splashScreen.setSplashScreenTheme(splash) }
}

@Composable
fun OpenCineCamApp(splash: SplashHandoff = SplashHandoff(onScreen = false), onReady: () -> Unit = {}) {
    val theme by rememberAppTheme()
    val activity = LocalActivity.current
    LaunchedEffect(theme, activity) { activity?.let { applySplashTheme(it, theme) } }
    OpenCineCamTheme(theme) {
        SyncWindowBackground()
        // An opaque themed floor: crossfades and frames that draw nothing never reveal the window.
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            ProvideAdaptiveWindow { CameraRootScreen(splash = splash, onReady = onReady) }
        }
    }
}
