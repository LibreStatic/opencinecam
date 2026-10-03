/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/**
 * One optional, bounded, lossy output. Producer calls stay on the camera GL worker;
 * all native-window operations (including a potentially stalled swap) stay on the subject worker.
 * A process-wide lease prevents repeated reconnects from accumulating stalled workers/resources.
 */
internal class SubjectPreviewOutput private constructor(
    private val display: EGLDisplay,
    private val sharedContext: EGLContext,
    private val config: EGLConfig,
    private val surface: Surface,
    initialOptions: SubjectPreviewOptions,
    private val onStatus: (SubjectPreviewStatus) -> Unit,
) {
    private data class Frame(val fence: Long, val receivedAtMs: Long, val lutStatus: OperatorLutStatus)
    private val exchange = LatestFrameExchange<Frame>()
    private val textures = IntArray(exchange.capacity)
    private val framebuffers = IntArray(exchange.capacity)
    private val thread = HandlerThread("SubjectPreviewGL").apply { start() }
    private val handler = Handler(thread.looper)
    private val scheduled = AtomicBoolean(false)
    private val closing = AtomicBoolean(false)
    private val completion = CompletableFuture<Unit>()
    @Volatile private var options = initialOptions
    @Volatile private var width = 0
    @Volatile private var height = 0
    private var windowWidth = 0
    private var windowHeight = 0
    @Volatile private var failure: String? = null
    @Volatile private var status = SubjectPreviewStatus()
    private var allocated = false
    private val pacer = SubjectFramePacer()
    private val dropped = AtomicLong()
    private var context = EGL14.EGL_NO_CONTEXT
    private var window = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var quad: PreviewQuad? = null

    init { handler.post { runCatching(::initializeConsumer).onFailure(::fail) } }

    fun updateOptions(value: SubjectPreviewOptions) { options = value }

    /** The caller owns the current producer context. Never wait for a consumer or GPU fence here. */
    fun render(receivedAtMs: Long, draw: (Int, Int, SubjectPreviewOptions) -> OperatorLutStatus) {
        if (closing.get() || failure != null || width == 0) return
        if (!pacer.admit(receivedAtMs, options.maxFrameRate)) return
        try {
            reclaimCompleted()
            val slot = exchange.acquire() ?: run { dropped.incrementAndGet(); return }
            try {
                if (!allocated) allocateProducerTargets()
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffers[slot])
                // Capture the producer's exact selection with the pixels, never relabel later.
                val lutStatus = draw(width, height, options)
                val fence = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
                check(fence != 0L) { "Subject producer fence creation failed." }
                GLES30.glFlush()
                if (exchange.publish(slot, Frame(fence, receivedAtMs, lutStatus))) dropped.incrementAndGet()
            } catch (error: Throwable) {
                exchange.cancelWrite(slot)
                throw error
            } finally {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            }
            scheduleConsumer()
        } catch (error: Throwable) { fail(error) }
    }

    private fun allocateProducerTargets() {
        GLES30.glGenTextures(textures.size, textures, 0)
        GLES30.glGenFramebuffers(framebuffers.size, framebuffers, 0)
        for (slot in textures.indices) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[slot])
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, width, height, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffers[slot])
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, textures[slot], 0)
            check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) { "Subject framebuffer is incomplete." }
        }
        allocated = true
    }

    private fun reclaimCompleted() {
        for ((slot, frame) in exchange.returned()) {
            when (GLES30.glClientWaitSync(frame.fence, 0, 0)) {
                GLES30.GL_ALREADY_SIGNALED, GLES30.GL_CONDITION_SATISFIED -> {
                    GLES30.glDeleteSync(frame.fence)
                    exchange.release(slot)
                }
                GLES30.GL_WAIT_FAILED -> error("Subject completion fence failed.")
            }
        }
    }

    private fun initializeConsumer() {
        check(surface.isValid) { "Subject surface is invalid." }
        context = EGL14.eglCreateContext(display, config, sharedContext, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "Subject shared context is unavailable." }
        window = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        check(window != EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(display, window, window, context)) { "Subject window is unavailable." }
        program = createProgram()
        quad = PreviewQuad()
        val w = IntArray(1)
        val h = IntArray(1)
        check(EGL14.eglQuerySurface(display, window, EGL14.EGL_WIDTH, w, 0))
        check(EGL14.eglQuerySurface(display, window, EGL14.EGL_HEIGHT, h, 0))
        check(w[0] > 0 && h[0] > 0) { "Subject surface has no extent." }
        windowWidth = w[0]
        windowHeight = h[0]
        val scale = minOf(1f, MAX_EDGE.toFloat() / maxOf(w[0], h[0]))
        height = (h[0] * scale).roundToInt().coerceAtLeast(1)
        // Publish width last: producer reads it as the initialized flag.
        width = (w[0] * scale).roundToInt().coerceAtLeast(1)
    }

    private fun scheduleConsumer() {
        if (!closing.get() && scheduled.compareAndSet(false, true)) handler.post {
            try { consumeLatest() } catch (error: Throwable) { fail(error) }
            finally {
                scheduled.set(false)
                if (failure == null && exchange.hasPending()) scheduleConsumer()
            }
        }
    }

    private fun consumeLatest() {
        if (closing.get() || failure != null) return
        val (slot, frame) = exchange.takeLatest() ?: return
        var returnedFence = frame.fence
        var samplingStarted = false
        try {
            // A finite wait on this output's worker, never on the camera/encoder worker.
            when (GLES30.glClientWaitSync(frame.fence, 0, CONSUMER_FENCE_BUDGET_NS)) {
                GLES30.GL_TIMEOUT_EXPIRED -> { dropped.incrementAndGet(); return }
                GLES30.GL_WAIT_FAILED -> error("Subject input fence failed.")
            }
            val dimensions = IntArray(2)
            check(EGL14.eglQuerySurface(display, window, EGL14.EGL_WIDTH, dimensions, 0))
            check(EGL14.eglQuerySurface(display, window, EGL14.EGL_HEIGHT, dimensions, 1))
            check(dimensions[0] == windowWidth && dimensions[1] == windowHeight) {
                "Subject surface size changed. Reattach the output at its new size."
            }
            GLES30.glViewport(0, 0, dimensions[0], dimensions[1])
            GLES30.glUseProgram(program)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[slot])
            samplingStarted = true
            requireNotNull(quad).draw()
            // Place the reuse fence after texture sampling, before the native-window swap.
            val consumerFence = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
            check(consumerFence != 0L) { "Subject consumer fence creation failed." }
            GLES30.glFlush()
            GLES30.glDeleteSync(frame.fence)
            returnedFence = consumerFence
            check(GLES30.glGetError() == GLES30.GL_NO_ERROR) { "Subject texture sampling failed." }
            check(EGL14.eglSwapBuffers(display, window)) { "Subject swap failed." }
            if (!closing.get()) {
                status = SubjectPreviewStatus(frame.receivedAtMs, SystemClock.elapsedRealtime(), dropped.get(), lutStatus = frame.lutStatus)
                notifyStatus(status)
            }
        } finally {
            // If sampling began but no completion fence could be created, quarantine this lease
            // until teardown. Returning the producer fence would permit an unsafe overwrite.
            if (!samplingStarted || returnedFence != frame.fence) exchange.complete(slot, Frame(returnedFence, frame.receivedAtMs, frame.lutStatus))
        }
    }

    private fun fail(error: Throwable) {
        failure = error.message ?: "Subject output failed."
        status = status.copy(failure = failure)
        notifyStatus(status)
    }

    private fun notifyStatus(value: SubjectPreviewStatus) {
        // An observer must never become a camera failure or kill the output cleanup worker.
        if (Thread.currentThread() === thread) runCatching { onStatus(value) }
        else handler.post { runCatching { onStatus(value) } }
    }

    /** Producer thread only. Returns immediately even if a window swap is stuck. */
    fun closeOnProducer(): CompletableFuture<Unit> {
        if (!closing.compareAndSet(false, true)) return completion
        exchange.close()
        GLES30.glDeleteFramebuffers(framebuffers.size, framebuffers, 0)
        // Textures stay alive until the independent consumer actually releases them.
        handler.post {
            try {
                if (context != EGL14.EGL_NO_CONTEXT && window != EGL14.EGL_NO_SURFACE) {
                    if (EGL14.eglMakeCurrent(display, window, window, context)) {
                        exchange.closedValues().forEach { frame -> GLES30.glDeleteSync(frame.fence) }
                        GLES30.glDeleteTextures(textures.size, textures, 0)
                        if (program != 0) GLES30.glDeleteProgram(program)
                        quad?.delete()
                    }
                }
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                EGL14.eglReleaseThread()
            } finally {
                workerLease.set(false)
                thread.quitSafely()
                completion.complete(Unit)
            }
        }
        return completion
    }

    private fun createProgram(): Int {
        fun compile(type: Int, source: String): Int {
            val shader = GLES30.glCreateShader(type)
            GLES30.glShaderSource(shader, source)
            GLES30.glCompileShader(shader)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) {
                val reason = GLES30.glGetShaderInfoLog(shader)
                GLES30.glDeleteShader(shader)
                error(reason)
            }
            return shader
        }
        val vertex = compile(GLES30.GL_VERTEX_SHADER, VERTEX)
        val fragment = try { compile(GLES30.GL_FRAGMENT_SHADER, FRAGMENT) } catch (error: Throwable) {
            GLES30.glDeleteShader(vertex); throw error
        }
        val result = GLES30.glCreateProgram()
        GLES30.glAttachShader(result, vertex)
        GLES30.glAttachShader(result, fragment)
        GLES30.glLinkProgram(result)
        GLES30.glDeleteShader(vertex)
        GLES30.glDeleteShader(fragment)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(result, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            val reason = GLES30.glGetProgramInfoLog(result)
            GLES30.glDeleteProgram(result)
            error(reason)
        }
        return result
    }

    companion object {
        // At most 3 × 720 × 720 × 4 = 6,220,800 bytes of app-owned color targets.
        // Native-window buffers and driver allocations are additional and device-dependent.
        private const val MAX_EDGE = 720
        private const val CONSUMER_FENCE_BUDGET_NS = 5_000_000L
        private val workerLease = AtomicBoolean(false)
        fun create(display: EGLDisplay, context: EGLContext, config: EGLConfig, surface: Surface, options: SubjectPreviewOptions, onStatus: (SubjectPreviewStatus) -> Unit): SubjectPreviewOutput? {
            if (!workerLease.compareAndSet(false, true)) return null
            return try { SubjectPreviewOutput(display, context, config, surface, options, onStatus) }
            catch (error: Throwable) { workerLease.set(false); throw error }
        }
        private const val VERTEX = """#version 300 es
            layout(location = 0) in vec2 aPosition;
            out vec2 uv;
            void main() {
                vec2 p = aPosition;
                gl_Position = vec4(p, 0.0, 1.0);
                uv = (p + 1.0) * 0.5;
            }
        """
        private const val FRAGMENT = """#version 300 es
            precision mediump float;
            uniform sampler2D image;
            in vec2 uv;
            out vec4 color;
            void main() { color = texture(image, uv); }
        """
    }
}

/**
 * Paces subject submissions to the subject display's rate instead of a fixed cadence (Razr U8: a
 * 66 ms floor plus camera jitter showed 10-15 fps on the cover). A quarter period of slack keeps
 * a slightly early frame from being skipped, which would otherwise halve the cadence.
 */
internal class SubjectFramePacer {
    private var lastAdmittedMs = Long.MIN_VALUE
    fun admit(receivedAtMs: Long, maxFrameRate: Float): Boolean {
        if (maxFrameRate > 0f && lastAdmittedMs != Long.MIN_VALUE && receivedAtMs - lastAdmittedMs < 750f / maxFrameRate) return false
        lastAdmittedMs = receivedAtMs
        return true
    }
}
