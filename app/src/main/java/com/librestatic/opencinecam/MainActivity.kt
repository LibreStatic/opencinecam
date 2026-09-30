/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import android.app.Activity
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
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.librestatic.opencinecam.ui.theme.AppTheme
import com.librestatic.opencinecam.ui.theme.AppThemeStore
import com.librestatic.opencinecam.ui.theme.OpenCineCamTheme
import com.librestatic.opencinecam.ui.theme.SyncWindowBackground
import com.librestatic.opencinecam.ui.theme.rememberAppTheme

class MainActivity : ComponentActivity() {
    private val operatorKeys = OperatorKeyLatch()
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
    override fun onPause() { CaptureLocations.get(this).setForeground(locationOwner, false); operatorKeys.clear(); super.onPause() }
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean =
        handleOperatorKey(keyCode, event, down = true) || super.onKeyDown(keyCode, event)

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
    private lateinit var foldDisplays: FoldDisplayCoordinator
    private val splashHandoff = mutableStateOf(SplashHandoff(onScreen = true))
    @Volatile private var contentReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        // installSplashScreen applied the Cine post-splash theme; Material You needs its own window colour.
        val theme = AppThemeStore(this).load()
        setTheme(windowThemeFor(theme))
        super.onCreate(savedInstanceState)
        applySplashTheme(this, theme)
        com.librestatic.opencinecam.storage.MediaProxyQueue.get(this).start()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        foldDisplays = FoldDisplayCoordinator(this)

        // The logo intro plays once per process, on a start from scratch that lands on the wizard.
        val playIntro = savedInstanceState == null && !introPlayed && !OnboardingStore(this).isCompleted()
        introPlayed = true
        splashHandoff.value = SplashHandoff(onScreen = savedInstanceState == null, playIntro = playIntro)
        // A normal start holds the splash while the camera opens, so "Preparing camera" never flashes
        // by; never for long, so a slow or failing camera does not trap the user on the logo.
        val start = SystemClock.uptimeMillis()
        splashScreen.setKeepOnScreenCondition { !contentReady && SystemClock.uptimeMillis() - start < KeepSplashMaxMillis }
        splashScreen.setOnExitAnimationListener { provider ->
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
            CompositionLocalProvider(LocalFoldDisplayCoordinator provides foldDisplays) {
                OpenCineCamApp(splash = splashHandoff.value, onReady = { contentReady = true })
            }
        }
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
        const val KeepSplashMaxMillis = 800L
    }
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
            CameraRootScreen(splash = splash, onReady = onReady)
        }
    }
}
