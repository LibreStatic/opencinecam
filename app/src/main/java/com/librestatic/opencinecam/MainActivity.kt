/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.librestatic.opencinecam.storage.MediaProxyQueue.get(this).start()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        foldDisplays = FoldDisplayCoordinator(this)
        setContent {
            CompositionLocalProvider(LocalFoldDisplayCoordinator provides foldDisplays) { OpenCineCamApp() }
        }
    }

    override fun onDestroy() {
        CaptureLocations.get(this).setForeground(locationOwner, false)
        if (::foldDisplays.isInitialized) foldDisplays.close()
        super.onDestroy()
    }
}

@Composable
fun OpenCineCamApp() {
    MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme(
        primary = androidx.compose.ui.graphics.Color(0xFFFFB300),
        onPrimary = androidx.compose.ui.graphics.Color.Black,
        secondary = androidx.compose.ui.graphics.Color(0xFF45D6E8),
        onSecondary = androidx.compose.ui.graphics.Color.Black,
        background = androidx.compose.ui.graphics.Color(0xFF0B0D0E),
        surface = androidx.compose.ui.graphics.Color(0xFF101417),
        onSurface = androidx.compose.ui.graphics.Color.White,
    )) {
        Surface(modifier = Modifier.fillMaxSize()) {
            CameraRootScreen()
        }
    }
}
