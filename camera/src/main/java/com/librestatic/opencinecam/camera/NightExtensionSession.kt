/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import android.annotation.TargetApi
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraExtensionCharacteristics
import android.hardware.camera2.CameraExtensionSession
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.ExtensionSessionConfiguration
import android.hardware.camera2.params.OutputConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import java.util.concurrent.CancellationException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Reads the night extension of [cameraId] (API 31+), or null when the camera does not offer it.
 * Moto's HAL advertises `com.lenovo.moto.extension.night_support`, but that is not a contract: only
 * the platform's own [CameraExtensionCharacteristics] answer decides. Any failure reads as absent.
 */
@TargetApi(NIGHT_EXTENSION_MIN_SDK)
internal fun probeNightExtension(manager: CameraManager, cameraId: String): NightExtensionCapability? {
    if (Build.VERSION.SDK_INT < NIGHT_EXTENSION_MIN_SDK) return null
    return runCatching {
        val characteristics = manager.getCameraExtensionCharacteristics(cameraId)
        val supported = characteristics.supportedExtensions
        if (CameraExtensionCharacteristics.EXTENSION_NIGHT !in supported) return@runCatching null
        fun sizes(query: () -> List<android.util.Size>) = runCatching(query).getOrNull().orEmpty().map { it.width to it.height }
        val night = CameraExtensionCharacteristics.EXTENSION_NIGHT
        val preview = sizes { characteristics.getExtensionSupportedSizes(night, SurfaceTexture::class.java) }
            .ifEmpty { sizes { characteristics.getExtensionSupportedSizes(night, SurfaceHolder::class.java) } }
        val jpeg = sizes { characteristics.getExtensionSupportedSizes(night, ImageFormat.JPEG) }
        nightExtensionCapability(supported, preview, jpeg)
    }.getOrNull()
}

/**
 * One night [CameraExtensionSession]: a preview output and a JPEG reader, one capture at a time.
 * The engine only creates it, forwards still requests to it and closes it; everything specific to
 * extension sessions (outputs, request keys, progress, delivery) lives here.
 *
 * [onUnavailable] fires at most once, and only when the session never reached its first preview, so
 * the engine can fall back to a regular session. After that, problems surface as listener failures.
 */
@TargetApi(NIGHT_EXTENSION_MIN_SDK)
internal class NightExtensionSession(
    private val manager: CameraManager,
    private val device: CameraDevice,
    private val descriptor: Camera2CameraDescriptor,
    private val previewSurface: Surface,
    private val sizes: NightExtensionSizes,
    private val cameraExecutor: Executor,
    private val imageHandler: Handler,
    private val listener: Camera2PreviewListener,
    private val jpegOrientation: () -> Int,
    private val isCurrent: () -> Boolean,
    private val onUnavailable: (String) -> Unit,
) {
    private class Pending(val id: Long, val quality: Int, val aspect: PhotoAspectSelection, val legacy: Boolean) {
        var orientation = 0
        var cancelled = false
        var timeout: Runnable? = null
    }

    private val lock = Any()
    private val closed = AtomicBoolean(false)
    private val ids = AtomicLong()
    @Volatile private var session: CameraExtensionSession? = null
    @Volatile private var started = false
    private var reader: ImageReader? = null
    private var pending: Pending? = null
    private var availableKeys: Set<CaptureRequest.Key<*>>? = null

    fun start() {
        try {
            availableKeys = if (Build.VERSION.SDK_INT >= 33) runCatching {
                manager.getCameraExtensionCharacteristics(descriptor.cameraId)
                    .getAvailableCaptureRequestKeys(CameraExtensionCharacteristics.EXTENSION_NIGHT)
            }.getOrNull() else null
            val jpeg = ImageReader.newInstance(sizes.jpeg.first, sizes.jpeg.second, ImageFormat.JPEG, 2)
            reader = jpeg
            jpeg.setOnImageAvailableListener({ onImage(it) }, imageHandler)
            val outputs = listOf(OutputConfiguration(previewSurface), OutputConfiguration(jpeg.surface))
            device.createExtensionSession(ExtensionSessionConfiguration(
                CameraExtensionCharacteristics.EXTENSION_NIGHT, outputs, cameraExecutor, stateCallback))
        } catch (failure: Exception) {
            unavailable(failure.message ?: "The night extension session could not be created.")
        }
    }

    private val stateCallback = object : CameraExtensionSession.StateCallback() {
        override fun onConfigured(configured: CameraExtensionSession) {
            if (closed.get() || !isCurrent()) {
                runCatching { configured.close() }
                return
            }
            session = configured
            try {
                val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(previewSurface) }
                configured.setRepeatingRequest(builder.build(), cameraExecutor, object : CameraExtensionSession.ExtensionCaptureCallback() {})
                started = true
                listener.onPreviewStarted(descriptor)
            } catch (failure: Exception) {
                runCatching { configured.close() }
                session = null
                unavailable(failure.message ?: "The night extension preview could not start.")
            }
        }

        override fun onConfigureFailed(configured: CameraExtensionSession) {
            runCatching { configured.close() }
            unavailable("The night extension session was rejected.")
        }

        override fun onClosed(closedSession: CameraExtensionSession) {
            releaseReader()
        }
    }

    /** False when not ready or a capture is still in flight; the result arrives through the listener. */
    fun capture(quality: Int, aspect: PhotoAspectSelection, legacy: Boolean): Boolean {
        val active = session ?: return false
        val request = synchronized(lock) {
            if (closed.get() || pending != null || !started) return false
            Pending(ids.incrementAndGet(), quality, aspect, legacy).also { pending = it }
        }
        cameraExecutor.execute { submit(request, active) }
        return true
    }

    /** A capture cannot be aborted inside the extension; its image is dropped when it arrives. */
    fun cancel(): Boolean = synchronized(lock) {
        val request = pending ?: return false
        if (request.cancelled) return false
        request.cancelled = true
        true
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(lock) {
            pending?.timeout?.let(imageHandler::removeCallbacks)
            pending = null
        }
        val active = session
        session = null
        if (active != null) runCatching { active.close() } else releaseReader()
    }

    private fun submit(request: Pending, active: CameraExtensionSession) {
        try {
            if (!owns(request)) return
            request.orientation = jpegOrientation()
            val target = requireNotNull(reader).surface
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(target)
                setIfAvailable(CaptureRequest.JPEG_QUALITY, request.quality.toByte())
                // The aspect crop normalises the rotation itself, as for regular JPEGs.
                setIfAvailable(CaptureRequest.JPEG_ORIENTATION, if (request.aspect.enabled) 0 else request.orientation)
            }
            request.timeout = Runnable { cameraExecutor.execute { fail(request, "night-timeout", "The night capture did not complete before its deadline.") } }
                .also { check(imageHandler.postDelayed(it, DEADLINE_MS)) { "Night capture deadline owner is unavailable" } }
            active.capture(builder.build(), cameraExecutor, captureCallback(request))
        } catch (failure: Exception) {
            fail(request, "night-capture-failed", failure.message ?: "The night capture could not start.")
        }
    }

    private fun <T> CaptureRequest.Builder.setIfAvailable(key: CaptureRequest.Key<T>, value: T) {
        val keys = availableKeys
        if (keys == null || key in keys) set(key, value)
    }

    private fun captureCallback(request: Pending) = object : CameraExtensionSession.ExtensionCaptureCallback() {
        override fun onCaptureFailed(session: CameraExtensionSession, failed: CaptureRequest) {
            fail(request, "night-capture-failed", "The night extension reported a failed capture.")
        }

        override fun onCaptureSequenceAborted(session: CameraExtensionSession, sequenceId: Int) {
            fail(request, "night-capture-aborted", "The night capture was aborted.")
        }

        // API 34; earlier platforms never call it and the busy card stays indeterminate.
        override fun onCaptureProcessProgressed(session: CameraExtensionSession, captured: CaptureRequest, progress: Int) {
            if (owns(request)) listener.onNightCaptureProgress(progress.coerceIn(0, 100))
        }
    }

    private fun owns(request: Pending): Boolean = synchronized(lock) { pending === request && !request.cancelled } && !closed.get()

    /** Takes the pending capture, or null when it was cancelled, replaced or this session closed. */
    private fun claim(request: Pending): Boolean = synchronized(lock) {
        if (pending !== request) return false
        pending = null
        request.timeout?.let(imageHandler::removeCallbacks)
        !request.cancelled && !closed.get()
    }

    private fun fail(request: Pending, code: String, message: String) {
        if (claim(request)) listener.onFailure(code, message, true)
    }

    private fun onImage(imageReader: ImageReader) {
        val image = runCatching { imageReader.acquireNextImage() }.getOrNull() ?: return
        val request = synchronized(lock) { pending?.takeIf { !it.cancelled } }
        var bytes: ByteArray? = null
        var width = 0
        var height = 0
        var timestamp = 0L
        var problem: String? = null
        try {
            image.use {
                if (request == null || closed.get()) return@use
                val buffer = it.planes.first().buffer
                if (buffer.remaining() !in 1..StillImagePayload.MAX_COMPRESSED_BYTES) {
                    problem = "The night JPEG exceeds its byte limit."
                    return@use
                }
                bytes = ByteArray(buffer.remaining()).also(buffer::get)
                width = it.width
                height = it.height
                timestamp = it.timestamp
            }
        } catch (failure: Exception) {
            problem = failure.message ?: "The night JPEG could not be read."
        }
        // Stale, cancelled and post-close images are dropped without a word.
        if (request == null) return
        val data = bytes
        if (problem != null || data == null) {
            cameraExecutor.execute { problem?.let { fail(request, "still-image-read-failed", it) } }
            return
        }
        cameraExecutor.execute { deliver(request, data, width, height, timestamp) }
    }

    private fun deliver(request: Pending, bytes: ByteArray, width: Int, height: Int, imageTimestampNs: Long) {
        if (!claim(request)) return
        try {
            val timestamp = imageTimestampNs.takeIf { it > 0 } ?: SystemClock.elapsedRealtimeNanos()
            val payload = if (request.aspect.enabled) {
                cropPhotoAspectJpeg(bytes, request.aspect, request.orientation, request.quality) {
                    if (closed.get()) throw CancellationException("Night session closed")
                }
            } else StillImagePayload.owned(StillImageKind.JPEG, bytes, width, height)
            val report = PhotoFlashReport(PhotoFlashSelection(), null, 0, null, null, null, null, timestamp)
            val capture = CapturedStill(request.id, timestamp, if (request.aspect.enabled) 0 else request.orientation,
                request.quality, report, listOf(payload), request.aspect)
            if (closed.get()) return
            if (request.legacy) {
                listener.onPhotoFlashResult(report)
                listener.onJpegCaptured(payload.bytes, payload.width, payload.height)
            } else listener.onStillCaptured(capture)
        } catch (failure: Exception) {
            if (!closed.get()) listener.onFailure("still-encode-failed", failure.message ?: "The night still could not be encoded.", true)
        }
    }

    private fun unavailable(reason: String) {
        if (started || closed.get()) return
        Log.w(TAG, "Night extension unavailable ($reason); using the regular session.")
        close()
        onUnavailable(reason)
    }

    private fun releaseReader() {
        val toClose = synchronized(lock) { reader.also { reader = null } }
        runCatching { toClose?.close() }
    }

    private companion object {
        const val TAG = "NightExtension"
        /** Night processing can take many seconds; this bounds a lost callback, not a slow one. */
        const val DEADLINE_MS = 45_000L
    }
}
