/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.playback

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import com.librestatic.opencinecam.camera.GpuEglDisplayLease
import com.librestatic.opencinecam.media.ocraw.OcrawFrameHeader
import com.librestatic.opencinecam.zoomedQuad
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** How [RawPlaybackRenderer] debayers: one pixel per 2×2 quad (real time) or bilinear at full resolution (frame viewer). */
enum class RawDevelopQuality { HALF, FULL }

/**
 * Review-only GL stage for `.ocraw` takes (PLAN-071 §7 decisions 3 and 4). Each frame's packed RAW10
 * rows are uploaded as an `R8UI` texture `rowBytes` wide, unpacked and developed in the shader into
 * an RGBA8 texture at the develop resolution, then drawn letterboxed to the attached window. The
 * develop maths is [RawDevelop]'s, which the host tests cover; keep both in step.
 *
 * The EGL ES3 context lives for the renderer's lifetime on its own thread (current on a 1×1 pbuffer
 * while no window is attached), so the SurfaceView may come and go without relinking.
 */
internal class RawPlaybackRenderer : Closeable {
    private val thread = HandlerThread("ocraw-review-gl").apply { start() }
    private val handler = Handler(thread.looper)
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var config: EGLConfig? = null
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var window: EGLSurface = EGL14.EGL_NO_SURFACE
    private var windowSurface: Surface? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var halfProgram = 0
    private var fullProgram = 0
    private var blitProgram = 0
    private var maxTextureSize = 0
    private var rawTexture = 0
    private var rawWidth = 0
    private var rawHeight = 0
    private var imageTexture = 0
    private var imageWidth = 0
    private var imageHeight = 0
    private var framebuffer = 0
    /** The developed frame's aspect, kept so a resized or re-attached window redraws without a new upload. */
    private var frameWidth = 0
    private var frameHeight = 0
    private var zoomScale = 1f
    private var zoomX = 0f
    private var zoomY = 0f
    private var closed = false

    init {
        try { onGlThread { initialize() } } catch (failure: Throwable) { thread.quitSafely(); throw failure }
    }

    /**
     * Draws to [surface] from now on; blocks until the window surface exists. Calling it again with
     * the same surface only takes the new size (surfaceChanged) and redraws the last frame.
     */
    fun attach(surface: Surface, width: Int, height: Int) {
        onGlThread {
            check(!closed) { "RAW review GL stage is released" }
            if (surface !== windowSurface) {
                destroyWindow()
                window = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
                check(window != EGL14.EGL_NO_SURFACE) { "RAW review window surface creation failed" }
                windowSurface = surface
            }
            surfaceWidth = width
            surfaceHeight = height
            makeCurrent()
            // A resized window hands out one more buffer of the old size; drawing into it swaps the
            // new size in, so draw again or a paused frame stays at the stale size.
            val stale = windowSize().let { it[0] != width || it[1] != height }
            if (present() && stale) present()
        }
    }

    /**
     * The review's zoom, as a `graphicsLayer` on the surface would apply it: [scale] around the surface
     * centre, then the pan [offsetX]/[offsetY] as fractions of the surface's width and height (y down).
     * Redraws the last developed frame, so a paused frame follows the fingers too.
     */
    fun setZoom(scale: Float, offsetX: Float, offsetY: Float) {
        handler.post {
            if (closed) return@post
            zoomScale = scale; zoomX = offsetX; zoomY = offsetY
            try { makeCurrent(); present() }
            catch (failure: Exception) { android.util.Log.w(TAG, "RAW review zoom was not drawn.", failure) }
        }
    }

    /** Destroys the window surface and blocks until it is gone, so the SurfaceHolder may destroy its Surface. */
    fun detach() {
        try { onGlThread { destroyWindow() } }
        catch (failure: Exception) { android.util.Log.w(TAG, "RAW review window did not detach in time.", failure) }
    }

    /**
     * Uploads [frame] (tight RAW10 rows from its position, `header.rowBytes * header.height` bytes),
     * develops it with [params] and shows it. Runs on the GL thread; the caller must not reuse
     * [frame] until [onDone] runs there with the upload-to-swap time in nanoseconds and whether the
     * frame reached the window (false when detached, released, or on a GL failure).
     */
    fun render(
        frame: ByteBuffer,
        header: OcrawFrameHeader,
        params: RawDevelopParams,
        quality: RawDevelopQuality,
        onDone: (renderNanos: Long, drawn: Boolean) -> Unit,
    ) {
        val posted = handler.post {
            val started = SystemClock.elapsedRealtimeNanos()
            val drawn = try {
                if (closed) false else {
                    makeCurrent()
                    upload(frame, header)
                    develop(header, params, quality)
                    present()
                }
            } catch (failure: Exception) {
                android.util.Log.w(TAG, "RAW review frame ${header.frameNumber} was not drawn.", failure)
                false
            }
            onDone(SystemClock.elapsedRealtimeNanos() - started, drawn)
        }
        if (!posted) onDone(0L, false)
    }

    /** Blocks until the GL objects, the window surface and the context are gone. */
    fun release() {
        try { onGlThread { teardown() } }
        catch (failure: Exception) { android.util.Log.w(TAG, "RAW review GL stage did not release in time.", failure) }
        finally { thread.quitSafely() }
    }

    override fun close() = release()

    private fun initialize() {
        display = GpuEglDisplayLease.acquire()
        try {
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE,
            ), 0, configs, 0, 1, count, 0) && count[0] > 0) { "An 8-bit OpenGL ES 3 window config is unavailable" }
            config = configs[0]
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            check(context != EGL14.EGL_NO_CONTEXT) { "OpenGL ES 3 context creation failed" }
            pbuffer = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            check(pbuffer != EGL14.EGL_NO_SURFACE) { "RAW review pbuffer creation failed" }
            makeCurrent()
            val size = IntArray(1)
            GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, size, 0)
            maxTextureSize = size[0]
            halfProgram = linkProgram(VERTEX_SHADER, developFragmentShader(full = false))
            fullProgram = linkProgram(VERTEX_SHADER, developFragmentShader(full = true))
            blitProgram = linkProgram(BLIT_VERTEX_SHADER, BLIT_FRAGMENT_SHADER)
            val names = IntArray(1)
            GLES30.glGenFramebuffers(1, names, 0)
            framebuffer = names[0]
        } catch (failure: Throwable) {
            teardown()
            throw failure
        }
    }

    private fun makeCurrent() {
        val target = if (window != EGL14.EGL_NO_SURFACE) window else pbuffer
        check(EGL14.eglMakeCurrent(display, target, target, context)) { "RAW review EGL context is unavailable" }
    }

    /** Copies the packed rows into the R8UI texture, reallocating it only when the frame size changes. */
    private fun upload(frame: ByteBuffer, header: OcrawFrameHeader) {
        val bytes = header.rowBytes.toLong() * header.height
        require(frame.isDirect && frame.remaining() >= bytes) { "frame buffer holds ${frame.remaining()} of $bytes bytes" }
        require(header.rowBytes == OcrawFrameHeader.raw10RowBytes(header.width)) { "frame is not tight RAW10" }
        check(header.rowBytes <= maxTextureSize && header.height <= maxTextureSize) {
            "a ${header.rowBytes}x${header.height} texture exceeds GL_MAX_TEXTURE_SIZE $maxTextureSize"
        }
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        if (rawTexture == 0 || rawWidth != header.rowBytes || rawHeight != header.height) {
            if (rawTexture != 0) GLES30.glDeleteTextures(1, intArrayOf(rawTexture), 0)
            rawTexture = newTexture(GLES30.GL_NEAREST, GLES30.GL_NEAREST)
            GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_R8UI, header.rowBytes, header.height)
            rawWidth = header.rowBytes
            rawHeight = header.height
        } else {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, rawTexture)
        }
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, header.rowBytes, header.height,
            GLES30.GL_RED_INTEGER, GLES30.GL_UNSIGNED_BYTE, frame)
    }

    /** Develops the uploaded frame into the RGBA8 image texture (top row in texture row 0) and builds its mips. */
    private fun develop(header: OcrawFrameHeader, params: RawDevelopParams, quality: RawDevelopQuality) {
        val width = if (quality == RawDevelopQuality.HALF) header.width / 2 else header.width
        val height = if (quality == RawDevelopQuality.HALF) header.height / 2 else header.height
        check(width in 1..maxTextureSize && height in 1..maxTextureSize) { "a ${width}x$height image exceeds GL_MAX_TEXTURE_SIZE $maxTextureSize" }
        if (imageTexture == 0 || imageWidth != width || imageHeight != height) {
            if (imageTexture != 0) GLES30.glDeleteTextures(1, intArrayOf(imageTexture), 0)
            imageTexture = newTexture(GLES30.GL_LINEAR_MIPMAP_LINEAR, GLES30.GL_LINEAR)
            GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, RawDevelop.mipLevels(width, height), GLES30.GL_RGBA8, width, height)
            imageWidth = width
            imageHeight = height
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, imageTexture, 0)
        check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) { "RAW develop framebuffer is incomplete" }
        GLES30.glViewport(0, 0, width, height)
        val program = if (quality == RawDevelopQuality.HALF) halfProgram else fullProgram
        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, rawTexture)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uRaw"), 0)
        GLES30.glUniform2i(GLES30.glGetUniformLocation(program, "uSize"), header.width, header.height)
        GLES30.glUniform1iv(GLES30.glGetUniformLocation(program, "uSiteChannel"), 4, params.cfa.siteChannels, 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uMono"), if (params.cfa == RawCfa.MONO) 1 else 0)
        GLES30.glUniform1fv(GLES30.glGetUniformLocation(program, "uBlack"), 4, params.blackLevel, 0)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uWhite"), params.whiteLevel)
        GLES30.glUniform3fv(GLES30.glGetUniformLocation(program, "uGains"), 1, params.wbGains, 0)
        GLES30.glUniformMatrix3fv(GLES30.glGetUniformLocation(program, "uCameraToRec709"), 1, false, params.cameraToRec709ColumnMajor(), 0)
        drawQuad()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, imageTexture)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        frameWidth = header.width
        frameHeight = header.height
    }

    /**
     * Draws the developed image letterboxed and zoomed to the window and swaps; false when there is
     * nothing to show it on. The viewport stays the whole surface and the letterbox becomes the quad's
     * own scale and offset ([zoomedQuad]), so a zoomed picture fills the surface and the clip volume crops it.
     */
    private fun present(): Boolean {
        if (window == EGL14.EGL_NO_SURFACE || surfaceWidth <= 0 || surfaceHeight <= 0) return false
        // The buffer being drawn, which can lag surfaceChanged by a frame; its size, not the
        // reported one, keeps the letterbox and the zoom in place.
        val (bufferWidth, bufferHeight) = windowSize().let { if (it[0] > 0 && it[1] > 0) it[0] to it[1] else surfaceWidth to surfaceHeight }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, bufferWidth, bufferHeight)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        if (imageTexture != 0 && frameWidth > 0) {
            val box = RawDevelop.letterbox(frameWidth, frameHeight, bufferWidth, bufferHeight)
            GLES30.glUseProgram(blitProgram)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, imageTexture)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(blitProgram, "uImage"), 0)
            GLES30.glUniform4fv(GLES30.glGetUniformLocation(blitProgram, "uZoom"), 1,
                zoomedQuad(box, bufferWidth, bufferHeight, zoomScale, zoomX, zoomY), 0)
            drawQuad()
        }
        return EGL14.eglSwapBuffers(display, window)
    }

    private fun windowSize(): IntArray {
        val size = IntArray(2)
        EGL14.eglQuerySurface(display, window, EGL14.EGL_WIDTH, size, 0)
        EGL14.eglQuerySurface(display, window, EGL14.EGL_HEIGHT, size, 1)
        return size
    }

    private fun drawQuad() {
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, QUAD)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(0)
    }

    private fun newTexture(minFilter: Int, magFilter: Int): Int {
        val names = IntArray(1)
        GLES30.glGenTextures(1, names, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, names[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, minFilter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, magFilter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return names[0]
    }

    private fun destroyWindow() {
        if (window == EGL14.EGL_NO_SURFACE) return
        if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
        else EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, window)
        window = EGL14.EGL_NO_SURFACE
        windowSurface = null
    }

    private fun teardown() {
        if (closed) return
        closed = true
        if (display == EGL14.EGL_NO_DISPLAY) return
        if (context != EGL14.EGL_NO_CONTEXT && pbuffer != EGL14.EGL_NO_SURFACE &&
            EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)) {
            val textures = intArrayOf(rawTexture, imageTexture).filter { it != 0 }.toIntArray()
            if (textures.isNotEmpty()) GLES30.glDeleteTextures(textures.size, textures, 0)
            if (framebuffer != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
            for (program in intArrayOf(halfProgram, fullProgram, blitProgram)) if (program != 0) GLES30.glDeleteProgram(program)
        }
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window)
        if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pbuffer)
        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
        GpuEglDisplayLease.release(display)
        window = EGL14.EGL_NO_SURFACE; windowSurface = null; pbuffer = EGL14.EGL_NO_SURFACE
        context = EGL14.EGL_NO_CONTEXT; display = EGL14.EGL_NO_DISPLAY
    }

    private fun <T> onGlThread(block: () -> T): T {
        val task = FutureTask(block)
        check(handler.post(task)) { "RAW review GL thread is closed" }
        return task.get(5, TimeUnit.SECONDS)
    }

    private fun linkProgram(vertexSource: String, fragmentSource: String): Int {
        fun compile(type: Int, source: String): Int {
            val shader = GLES30.glCreateShader(type)
            try {
                GLES30.glShaderSource(shader, source); GLES30.glCompileShader(shader)
                val status = IntArray(1); GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
                check(status[0] == GLES30.GL_TRUE) { GLES30.glGetShaderInfoLog(shader) }
                return shader
            } catch (failure: Throwable) { GLES30.glDeleteShader(shader); throw failure }
        }
        var vertex = 0; var fragment = 0; var linked = 0
        try {
            vertex = compile(GLES30.GL_VERTEX_SHADER, vertexSource)
            fragment = compile(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
            linked = GLES30.glCreateProgram()
            GLES30.glAttachShader(linked, vertex); GLES30.glAttachShader(linked, fragment)
            GLES30.glBindAttribLocation(linked, 0, "aPosition")
            GLES30.glLinkProgram(linked)
            val status = IntArray(1); GLES30.glGetProgramiv(linked, GLES30.GL_LINK_STATUS, status, 0)
            check(status[0] == GLES30.GL_TRUE) { GLES30.glGetProgramInfoLog(linked) }
            return linked
        } catch (failure: Throwable) {
            if (linked != 0) GLES30.glDeleteProgram(linked)
            throw failure
        } finally {
            if (vertex != 0) GLES30.glDeleteShader(vertex)
            if (fragment != 0) GLES30.glDeleteShader(fragment)
        }
    }

    companion object {
        private const val TAG = "OcrawReview"
        private val QUAD = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).apply { position(0) }

        private val VERTEX_SHADER = """
            #version 300 es
            layout(location = 0) in vec2 aPosition;
            out vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vTexCoord = (aPosition + 1.0) * 0.5;
            }
        """.trimIndent()

        /** The develop passes' quad with the letterbox and zoom of [zoomedQuad]; texture coordinates stay on the plain quad. */
        private val BLIT_VERTEX_SHADER = """
            #version 300 es
            uniform vec4 uZoom;
            layout(location = 0) in vec2 aPosition;
            out vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPosition * uZoom.xy + uZoom.zw, 0.0, 1.0);
                vTexCoord = (aPosition + 1.0) * 0.5;
            }
        """.trimIndent()

        /** The image texture holds the top row in row 0, so the window flips it upright. */
        private val BLIT_FRAGMENT_SHADER = """
            #version 300 es
            precision mediump float;
            uniform sampler2D uImage;
            in vec2 vTexCoord;
            out vec4 outColor;
            void main() { outColor = vec4(texture(uImage, vec2(vTexCoord.x, 1.0 - vTexCoord.y)).rgb, 1.0); }
        """.trimIndent()

        /**
         * One develop pass per output pixel, mirroring [RawDevelop.developHalf] (quad) or
         * [RawDevelop.developBilinear] (full); output row y is frame row y (top first).
         */
        internal fun developFragmentShader(full: Boolean): String {
            val knee = RawDevelop.SHOULDER_KNEE
            val demosaic = if (full) """
                |    ivec2 p = ivec2(gl_FragCoord.xy);
                |    float centre = siteValue(p);
                |    vec3 sum = vec3(0.0);
                |    vec3 count = vec3(0.0);
                |    for (int dy = -1; dy <= 1; dy++) {
                |        for (int dx = -1; dx <= 1; dx++) {
                |            ivec2 s = p + ivec2(dx, dy);
                |            // Mirror by two at the edges so the CFA phase holds.
                |            if (s.x < 0) s.x = min(s.x + 2, uSize.x - 1);
                |            if (s.x >= uSize.x) s.x = max(s.x - 2, 0);
                |            if (s.y < 0) s.y = min(s.y + 2, uSize.y - 1);
                |            if (s.y >= uSize.y) s.y = max(s.y - 2, 0);
                |            vec3 mask = channelMask(uSiteChannel[site(s)]);
                |            sum += siteValue(s) * mask;
                |            count += mask;
                |        }
                |    }
                |    vec3 own = channelMask(uSiteChannel[site(p)]);
                |    vec3 camera = uMono == 1 ? vec3(centre) : mix(sum / max(count, vec3(1.0)), vec3(centre), own);
            """ else """
                |    ivec2 q = ivec2(gl_FragCoord.xy) * 2;
                |    vec3 sum = vec3(0.0);
                |    vec3 count = vec3(0.0);
                |    float total = 0.0;
                |    for (int i = 0; i < 4; i++) {
                |        float v = siteValue(q + ivec2(i & 1, i >> 1));
                |        vec3 mask = channelMask(uSiteChannel[i]);
                |        sum += v * mask;
                |        count += mask;
                |        total += v;
                |    }
                |    vec3 camera = uMono == 1 ? vec3(total * 0.25) : sum / max(count, vec3(1.0));
            """
            return """
                |#version 300 es
                |precision highp float;
                |precision highp int;
                |precision highp usampler2D;
                |uniform usampler2D uRaw;
                |uniform ivec2 uSize;
                |uniform int uSiteChannel[4];
                |uniform int uMono;
                |uniform float uBlack[4];
                |uniform float uWhite;
                |uniform vec3 uGains;
                |uniform mat3 uCameraToRec709;
                |out vec4 outColor;
                |const float KNEE = $knee;
                |// Android RAW10: four MSB bytes, then one byte of 2-bit remainders, pixel 0 in bits 0-1.
                |float rawAt(ivec2 p) {
                |    int group = (p.x >> 2) * 5;
                |    int lane = p.x & 3;
                |    uint msb = texelFetch(uRaw, ivec2(group + lane, p.y), 0).r;
                |    uint low = texelFetch(uRaw, ivec2(group + 4, p.y), 0).r;
                |    return float((msb << 2u) | ((low >> uint(lane * 2)) & 3u));
                |}
                |int site(ivec2 p) { return (p.y & 1) * 2 + (p.x & 1); }
                |float siteValue(ivec2 p) {
                |    float black = uBlack[site(p)];
                |    float range = uWhite - black;
                |    return range > 0.0 ? clamp((rawAt(p) - black) / range, 0.0, 1.0) : 0.0;
                |}
                |vec3 channelMask(int c) { return vec3(equal(ivec3(c), ivec3(0, 1, 2))); }
                |vec3 shoulder(vec3 x) {
                |    vec3 rolled = KNEE + (1.0 - KNEE) * (1.0 - exp(-(x - KNEE) / (1.0 - KNEE)));
                |    return mix(x, rolled, greaterThan(x, vec3(KNEE)));
                |}
                |vec3 srgbOetf(vec3 x) {
                |    x = clamp(x, 0.0, 1.0);
                |    return mix(1.055 * pow(x, vec3(1.0 / 2.4)) - 0.055, 12.92 * x, lessThan(x, vec3(0.0031308)));
                |}
                |void main() {
                $demosaic
                |    vec3 balanced = min(camera * uGains, vec3(1.0));
                |    vec3 linear = max(uCameraToRec709 * balanced, vec3(0.0));
                |    outColor = vec4(srgbOetf(shoulder(linear)), 1.0);
                |}
            """.trimMargin()
        }
    }
}
