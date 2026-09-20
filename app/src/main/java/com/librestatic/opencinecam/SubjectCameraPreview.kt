/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.librestatic.opencinecam.camera.SubjectPreviewStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

@Composable
internal fun SubjectCameraPreview(port: SubjectPreviewPort?, modifier: Modifier = Modifier) {
    val fallback = remember { MutableStateFlow(SubjectPreviewStatus()) }
    val status by (port?.statuses ?: fallback).collectAsState()
    val context = LocalContext.current
    val view = remember(context) { SurfaceView(context).apply { isClickable = false; isFocusable = false } }
    var revision by remember { mutableIntStateOf(0) }
    var lease by remember { mutableStateOf<AutoCloseable?>(null) }
    DisposableEffect(view) {
        val callback = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { revision++ }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { revision++ }
            override fun surfaceDestroyed(holder: SurfaceHolder) { lease?.close(); lease = null; revision++ }
        }
        val displays = context.getSystemService(DisplayManager::class.java)
        var lastRotation = view.display?.rotation
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) { if (displayId == view.display?.displayId) revision++ }
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == view.display?.displayId && view.display?.rotation != lastRotation) {
                    lastRotation = view.display?.rotation
                    revision++
                }
            }
        }
        view.holder.addCallback(callback)
        displays.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        onDispose { view.holder.removeCallback(callback); displays.unregisterDisplayListener(listener); lease?.close(); lease = null }
    }
    LaunchedEffect(view, port, revision) {
        lease?.close()
        lease = null
        withFrameNanos { }
        withFrameNanos { }
        if (view.holder.surface.isValid && view.width > 0 && view.height > 0) {
            val rotation = when (view.display?.rotation) {
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }
            lease = port?.attach(view.holder.surface, rotation)
        }
    }
    Box(modifier.background(Color.Black).testTag("subject-camera-preview")) {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
        SubjectFrameBadge(status, Modifier.align(Alignment.BottomCenter).fillMaxWidth())
    }
}

@Composable
internal fun SubjectFrameBadge(status: SubjectPreviewStatus, modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) { while (true) { now = SystemClock.elapsedRealtime(); delay(200) } }
    val failure = status.failure
    val receivedAt = status.sourceReceivedAtMs
    val message = when {
        failure != null -> stringResource(R.string.subject_preview_failed, failure)
        receivedAt == null -> stringResource(R.string.subject_preview_waiting)
        !status.isFresh(now) -> stringResource(R.string.subject_preview_stale)
        else -> stringResource(R.string.subject_preview_age, (now - receivedAt).coerceAtLeast(0))
    }
    Text(message, color = Color.White, fontSize = 16.sp,
        modifier = modifier.background(Color.Black.copy(alpha = 0.85f)).padding(8.dp).testTag("subject-preview-age"))
}
