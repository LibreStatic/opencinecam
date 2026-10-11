/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.service

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.librestatic.opencinecam.camera.Camera2CameraDescriptor
import com.librestatic.opencinecam.camera.Camera2PreviewEngine
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A head start for the camera on a cold start. Without it the camera waits for Compose to draw the
 * first frame, the capture service to bind and the cameras to be enumerated on the main thread
 * before it even begins to open.
 *
 * The activity calls [begin] in onCreate. That creates the engine, enumerates the cameras on a
 * background thread and opens the camera the service will start on. The service then adopts the
 * engine ([adoptEngine]) and the enumeration ([descriptors]); its first preview takes over the
 * already open device. Nothing here decides a setting: the session is still configured by the
 * service, from the saved settings, once the viewfinder surface exists.
 */
internal object CaptureStartup {
    private class HeadStart(
        val engine: Camera2PreviewEngine,
        val widthPx: Int,
        val heightPx: Int,
        val descriptors: FutureTask<List<Camera2CameraDescriptor>>,
    )

    private var pending: HeadStart? = null
    private var adopted: HeadStart? = null
    private var liveEngine: Camera2PreviewEngine? = null
    private val firstPreviewLogged = AtomicBoolean(false)

    /** Starts opening the camera for a viewfinder of the given size, unless a service already owns one. */
    @Synchronized
    fun begin(context: Context, widthPx: Int, heightPx: Int) {
        if (liveEngine != null || widthPx <= 0 || heightPx <= 0) return
        // A head start no service took (the activity went away first) is stale: start over.
        pending?.engine?.close()
        val engine = Camera2PreviewEngine(context.applicationContext)
        val task = FutureTask {
            // The service starts on the first descriptor (selectedCameraId is not kept across
            // processes). Its likely id is cheap to find, so the open runs alongside the full
            // enumeration; if the guess is wrong, the first preview closes it and opens the right one.
            engine.likelyPreferredCameraId()?.let(engine::preopen)
            engine.descriptors(widthPx, heightPx)
        }
        val head = HeadStart(engine, widthPx, heightPx, task)
        pending = head
        Thread(task, "OpenCineCamStartup").start()
    }

    /** The engine the capture service uses: the one [begin] prepared, or a new one. */
    @Synchronized
    fun adoptEngine(context: Context): Camera2PreviewEngine {
        val head = pending
        pending = null
        adopted = head
        Log.i(Tag, if (head != null) "Service adopted the startup engine" else "No startup engine; starting cold")
        return (head?.engine ?: Camera2PreviewEngine(context)).also { liveEngine = it }
    }

    /**
     * The cameras [begin] enumerated for [engine], if it did so for exactly this viewfinder size.
     * Taken once; any later preparation enumerates again, as before.
     */
    fun descriptors(engine: Camera2PreviewEngine, widthPx: Int, heightPx: Int): List<Camera2CameraDescriptor>? {
        val head = synchronized(this) {
            adopted.also { adopted = null }
        }?.takeIf { it.engine === engine } ?: return null
        if (head.widthPx != widthPx || head.heightPx != heightPx) {
            Log.i(Tag, "Viewfinder is ${widthPx}x$heightPx, not the predicted ${head.widthPx}x${head.heightPx}; enumerating again")
            return null
        }
        return runCatching { head.descriptors.get() }.getOrNull()
    }

    /** The service retired [engine]; the next start may prepare a new one. */
    @Synchronized
    fun released(engine: Camera2PreviewEngine) {
        if (liveEngine === engine) liveEngine = null
        if (adopted?.engine === engine) adopted = null
    }

    /** The activity left the screen: a camera opened ahead of a preview must not stay open behind it. */
    @Synchronized
    fun stopped() {
        (pending?.engine ?: liveEngine)?.dropPreopen("the app left the screen")
    }

    /** Logs, once per process, how long the first viewfinder frame took from process start. */
    fun previewStarted() {
        if (!firstPreviewLogged.compareAndSet(false, true)) return
        Log.i(Tag, "First preview ${SystemClock.uptimeMillis() - Process.getStartUptimeMillis()} ms after process start")
    }

    private const val Tag = "CaptureStartup"
}
