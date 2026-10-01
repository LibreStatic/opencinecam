/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.playback

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.librestatic.opencinecam.camera.GpuEglDisplayLease
import com.librestatic.opencinecam.camera.OpenCineLogYcbcrConversion
import com.librestatic.opencinecam.storage.PreciseLogView
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * Review-only GL stage for OCLog2 clips: decoders write to [inputSurface], and every frame is drawn
 * to [output] either as the flat monitor or through the Rec.709 view assist. The math matches the
 * capture shaders in OpenCineLogGpuPipeline; those hash-pinned sources are deliberately not reused.
 * When the GPU exposes raw YCbCr (GL_EXT_YUV_target) the shader converts BT.2020 itself, because some
 * drivers' external samplers ignore the buffer's matrix and range.
 */
internal class LogPlaybackRenderer(output: Surface, private val fullRange: Boolean, view: PreciseLogView) : Closeable {
    private val thread = HandlerThread("oclog-review-gl").apply { start() }
    private val handler = Handler(thread.looper)
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var window: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var texture = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var hasFrame = false
    private var view = view
    private var width = 0
    private var height = 0
    private var closed = false
    private val textureMatrix = FloatArray(16)
    /** True when the shader converts raw YCbCr; false means the driver's sampler conversion is trusted. */
    @Volatile var shaderYcbcr = false; private set
    val inputSurface: Surface

    init {
        inputSurface = try { onGlThread { initialize(output) } } catch (failure: Throwable) { thread.quitSafely(); throw failure }
    }

    fun setView(value: PreciseLogView) { handler.post { if (!closed) { view = value; draw() } } }

    fun resize(newWidth: Int, newHeight: Int) { handler.post { if (!closed) { width = newWidth; height = newHeight; draw() } } }

    /** Blocks until the EGL window surface is gone, so the SurfaceHolder may destroy its Surface afterwards. */
    override fun close() {
        try { onGlThread { release() } }
        catch (failure: Exception) { android.util.Log.w(TAG, "OCLog2 review GL stage did not release in time.", failure) }
        finally { thread.quitSafely() }
    }

    private fun initialize(output: Surface): Surface {
        display = GpuEglDisplayLease.acquire()
        val config = chooseConfig()
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "OpenGL ES 3 context creation failed" }
        window = EGL14.eglCreateWindowSurface(display, config, output, intArrayOf(EGL14.EGL_NONE), 0)
        check(window != EGL14.EGL_NO_SURFACE) { "Review window surface creation failed" }
        check(EGL14.eglMakeCurrent(display, window, window, context)) { "Review EGL context is unavailable" }
        val size = IntArray(1)
        EGL14.eglQuerySurface(display, window, EGL14.EGL_WIDTH, size, 0); width = size[0]
        EGL14.eglQuerySurface(display, window, EGL14.EGL_HEIGHT, size, 0); height = size[0]
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        texture = textures[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        program = linkReviewProgram()
        val created = SurfaceTexture(texture).apply {
            setOnFrameAvailableListener({ latch() }, handler)
        }
        surfaceTexture = created
        return Surface(created)
    }

    private fun chooseConfig(): EGLConfig {
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, EGL14.EGL_NONE,
        ), 0, configs, 0, 1, count, 0) && count[0] > 0) { "An 8-bit OpenGL ES 3 window config is unavailable" }
        return requireNotNull(configs[0])
    }

    private fun linkReviewProgram(): Int {
        val extensions = GLES30.glGetString(GLES30.GL_EXTENSIONS).orEmpty().split(' ')
        if ("GL_EXT_YUV_target" in extensions) {
            try {
                return linkProgram(VERTEX_SHADER, reviewFragmentShader(shaderYcbcr = true)).also { linked ->
                    shaderYcbcr = true
                    val conversion = OpenCineLogYcbcrConversion.create(OpenCineLogYcbcrConversion.Standard.BT2020, fullRange, 10)
                    GLES30.glUseProgram(linked)
                    GLES30.glUniformMatrix3fv(GLES30.glGetUniformLocation(linked, "uYcbcrToRgb"), 1, false, conversion.matrix, 0)
                    GLES30.glUniform3fv(GLES30.glGetUniformLocation(linked, "uYcbcrOffset"), 1, conversion.offset, 0)
                }
            } catch (failure: Exception) {
                android.util.Log.w(TAG, "GL_EXT_YUV_target review shader did not link; using the driver YCbCr conversion.", failure)
                for (attempt in 0 until 8) if (GLES30.glGetError() == GLES30.GL_NO_ERROR) break
            }
        }
        shaderYcbcr = false
        return linkProgram(VERTEX_SHADER, reviewFragmentShader(shaderYcbcr = false))
    }

    private fun latch() {
        if (closed) return
        val source = surfaceTexture ?: return
        try {
            check(EGL14.eglMakeCurrent(display, window, window, context)) { "Review EGL context is unavailable" }
            source.updateTexImage()
            source.getTransformMatrix(textureMatrix)
            hasFrame = true
            draw()
        } catch (failure: Exception) {
            android.util.Log.w(TAG, "OCLog2 review frame was not drawn.", failure)
        }
    }

    private fun draw() {
        if (closed || !hasFrame || width <= 0 || height <= 0) return
        check(EGL14.eglMakeCurrent(display, window, window, context)) { "Review EGL context is unavailable" }
        GLES30.glViewport(0, 0, width, height)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uTexture"), 0)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(program, "uTextureMatrix"), 1, false, textureMatrix, 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uView"), if (view == PreciseLogView.REC709) 1 else 0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, QUAD)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(0)
        EGL14.eglSwapBuffers(display, window)
    }

    private fun release() {
        if (closed) return
        closed = true
        runCatching { surfaceTexture?.release() }
        runCatching { inputSurface.release() }
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            GpuEglDisplayLease.release(display)
            display = EGL14.EGL_NO_DISPLAY
        }
    }

    private fun <T> onGlThread(block: () -> T): T {
        val task = FutureTask(block)
        check(handler.post(task)) { "Review GL thread is closed" }
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
        private const val TAG = "OcLogReview"
        private val QUAD = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).apply { position(0) }

        private val VERTEX_SHADER = """
            #version 300 es
            uniform mat4 uTextureMatrix;
            layout(location = 0) in vec2 aPosition;
            out vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vTexCoord = (uTextureMatrix * vec4((aPosition + 1.0) * 0.5, 0.0, 1.0)).xy;
            }
        """.trimIndent()

        /** OCLog2 codes in, monitor out. uView 1 is the capture view assist; 0 is its flat monitor. */
        internal fun reviewFragmentShader(shaderYcbcr: Boolean): String {
            val extension = if (shaderYcbcr) "#extension GL_EXT_YUV_target : require\n" else ""
            val sampler = if (shaderYcbcr) "uniform __samplerExternal2DY2YEXT uTexture;\nuniform mat3 uYcbcrToRgb;\nuniform vec3 uYcbcrOffset;"
                else "uniform samplerExternalOES uTexture;"
            val sample = if (shaderYcbcr) "clamp(uYcbcrToRgb * (texture(uTexture, vTexCoord).rgb - uYcbcrOffset), 0.0, 1.0)"
                else "texture(uTexture, vTexCoord).rgb"
            return """
                |#version 300 es
                |#extension GL_OES_EGL_image_external_essl3 : require
                |$extension$sampler
                |precision highp float;
                |uniform int uView;
                |in vec2 vTexCoord;
                |out vec4 outColor;
                |vec3 encodeOcLog2(vec3 linearBt2020) {
                |    return vec3(0.10) + vec3(0.80) * log(vec3(1.0) + 50.0 * clamp(linearBt2020, 0.0, 1.0)) / log(vec3(51.0));
                |}
                |vec3 decodeOcLog2(vec3 code) {
                |    vec3 normalized = clamp((code - vec3(0.10)) / vec3(0.80), 0.0, 1.0);
                |    return (pow(vec3(51.0), normalized) - 1.0) / 50.0;
                |}
                |vec3 bt2020ToBt709(vec3 c) {
                |    return mat3(
                |        1.660491, -0.124550, -0.018151,
                |       -0.587641,  1.132900, -0.100579,
                |       -0.072850, -0.008349,  1.118730
                |    ) * c;
                |}
                |vec3 rec709Oetf(vec3 x) {
                |    x = clamp(x, 0.0, 1.0);
                |    bvec3 low = lessThan(x, vec3(0.018));
                |    return mix(1.099 * pow(x, vec3(0.45)) - 0.099, 4.5 * x, low);
                |}
                |void main() {
                |    vec3 displayLinear = max(bt2020ToBt709(decodeOcLog2($sample)), vec3(0.0));
                |    float luma = dot(displayLinear, vec3(0.2126, 0.7152, 0.0722));
                |    vec3 flatMonitor = encodeOcLog2(mix(vec3(luma), displayLinear, 0.68));
                |    outColor = vec4(uView == 1 ? rec709Oetf(displayLinear) : flatMonitor, 1.0);
                |}
            """.trimMargin()
        }
    }
}
