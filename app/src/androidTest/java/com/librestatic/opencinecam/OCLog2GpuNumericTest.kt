// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 OpenCineCam contributors

@file:Suppress("DEPRECATION")

package com.librestatic.opencinecam

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLES30
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.RequiresDevice
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.camera.OpenCineLogGpuPipeline
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/**
 * Physical GLES numeric harness for the normative OCLog2 transform.
 *
 * This is intentionally androidTest-only: it creates a GLES 3 context, renders into an
 * RGBA32F texture, and compares glReadPixels() values against a CPU Double reference.
 * It must not be used as a production color transform or as a CPU-only substitute.
 */
@RunWith(AndroidJUnit4::class)
@RequiresDevice
class OCLog2GpuNumericTest {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var renderer = ""
    private var vendor = ""
    private var glVersion = ""

    @Before
    fun setUp() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        assumeTrue("EGL display unavailable", display != EGL14.EGL_NO_DISPLAY)
        checkEgl(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0))

        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        checkEgl(EGL14.eglChooseConfig(display, intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, 0x40, // EGL_OPENGL_ES3_BIT_KHR
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE,
        ), 0, configs, 0, 1, count, 0) && count[0] == 1)

        context = EGL14.eglCreateContext(
            display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
        )
        checkEgl(context != EGL14.EGL_NO_CONTEXT)
        surface = EGL14.eglCreatePbufferSurface(
            display, configs[0], intArrayOf(EGL14.EGL_WIDTH, WIDTH, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
        )
        checkEgl(surface != EGL14.EGL_NO_SURFACE)
        checkEgl(EGL14.eglMakeCurrent(display, surface, surface, context))

        renderer = GLES20.glGetString(GLES20.GL_RENDERER).orEmpty()
        vendor = GLES20.glGetString(GLES20.GL_VENDOR).orEmpty()
        glVersion = GLES20.glGetString(GLES20.GL_VERSION).orEmpty()
        val normalizedRenderer = renderer.lowercase()
        assumeFalse("Software GLES renderer ($renderer) is not physical GPU evidence", normalizedRenderer.contains("llvmpipe") || normalizedRenderer.contains("swiftshader") || normalizedRenderer.contains("software") || normalizedRenderer.contains("rasterizer"))
        // GLES 3 makes RGBA32F textures available, but does not by itself make them
        // color-renderable.  Without the float-color-buffer extension this test would
        // continue past setup and fail with an incomplete framebuffer on valid devices.
        assumeTrue(
            "RGBA32F render target unsupported",
            GLES30.glGetString(GLES30.GL_EXTENSIONS).orEmpty().split(' ').contains("GL_EXT_color_buffer_float"),
        )
    }

    @After
    fun tearDown() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
    }

    @Test
    fun gpuEncodeDecodeMatchesDoubleReferenceWithinTwoTimesTenToTheMinusFive() {
        val program = linkProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        val texture = IntArray(1)
        val framebuffer = IntArray(1)
        GLES30.glGenTextures(1, texture, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture[0])
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA32F, WIDTH, 1)
        GLES30.glGenFramebuffers(1, framebuffer, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer[0])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texture[0], 0)
        assertEquals("RGBA32F framebuffer incomplete", GLES30.GL_FRAMEBUFFER_COMPLETE, GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER))

        GLES30.glUseProgram(program)
        GLES30.glViewport(0, 0, WIDTH, 1)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glFinish()

        val values = ByteBuffer.allocateDirect(WIDTH * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        GLES30.glReadPixels(0, 0, WIDTH, 1, GLES30.GL_RGBA, GLES30.GL_FLOAT, values)
        checkGl("glReadPixels")
        var maxEncodeError = 0.0
        var maxRoundTripError = 0.0
        var maxInputError = 0.0
        for (i in 0 until WIDTH) {
            val x = i.toDouble() / (WIDTH - 1).toDouble()
            val encoded = encode(x)
            val decoded = decode(encoded)
            val base = i * 4
            maxEncodeError = max(maxEncodeError, abs(encoded - values.get(base).toDouble()))
            maxRoundTripError = max(maxRoundTripError, abs(decoded - values.get(base + 1).toDouble()))
            maxInputError = max(maxInputError, abs(x - values.get(base + 2).toDouble()))
            assertEquals("encode at sample $i", encoded.toFloat(), values.get(base), TOLERANCE)
            assertEquals("decode(encode(x)) at sample $i", decoded.toFloat(), values.get(base + 1), TOLERANCE)
            assertEquals("shader input at sample $i", x.toFloat(), values.get(base + 2), TOLERANCE)
        }
        val maxAbsError = max(maxEncodeError, max(maxRoundTripError, maxInputError))
        val output = requireNotNull(
            InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
        ).resolve(RESULT_NAME)
        output.writeText(
            JSONObject()
                .put("schema", "opencinecam-oclog2-gpu-numeric-v1")
                .put("status", "PASS")
                .put("physical", true)
                .put("fingerprint", Build.FINGERPRINT)
                .put("renderer", renderer)
                .put("vendor", vendor)
                .put("glVersion", glVersion)
                .put("samples", WIDTH)
                .put("tolerance", TOLERANCE.toDouble())
                .put("maxAbsError", maxAbsError)
                .put("maxEncodeAbsError", maxEncodeError)
                .put("maxRoundTripAbsError", maxRoundTripError)
                .put("maxInputAbsError", maxInputError)
                .put("harnessShaderSha256", sha256(FRAGMENT_SHADER))
                .put("runtimeShaderSha256", OpenCineLogGpuPipeline.TRANSFORM_SHA256)
                .toString(2) + "\n",
            Charsets.UTF_8,
        )
        GLES30.glDeleteFramebuffers(1, framebuffer, 0)
        GLES30.glDeleteTextures(1, texture, 0)
        GLES30.glDeleteProgram(program)
    }

    private fun encode(x: Double): Double = BLACK + (WHITE - BLACK) * ln(1.0 + BASE * x.coerceIn(0.0, 1.0)) / ln(1.0 + BASE)
    private fun decode(y: Double): Double = ((1.0 + BASE).pow(((y.coerceIn(BLACK, WHITE) - BLACK) / (WHITE - BLACK))) - 1.0) / BASE

    private fun linkProgram(vertex: String, fragment: String): Int {
        val vs = compile(GLES30.GL_VERTEX_SHADER, vertex)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, fragment)
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, vs); GLES30.glAttachShader(p, fs); GLES30.glLinkProgram(p)
        val status = IntArray(1); GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES30.GL_TRUE) { GLES30.glGetProgramInfoLog(p) }
        GLES30.glDeleteShader(vs); GLES30.glDeleteShader(fs)
        return p
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source); GLES30.glCompileShader(shader)
        val status = IntArray(1); GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES30.GL_TRUE) { GLES30.glGetShaderInfoLog(shader) }
        return shader
    }

    private fun checkGl(where: String) { check(GLES30.glGetError() == GLES30.GL_NO_ERROR) { "$where: GL error" } }
    private fun checkEgl(ok: Boolean) { check(ok) { "EGL error 0x${Integer.toHexString(EGL14.eglGetError())}" } }
    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    companion object {
        private const val WIDTH = 513
        private const val RESULT_NAME = "occ-plan-061-oclog2-gpu-numeric.json"
        private const val TOLERANCE = 2e-5f
        private const val BASE = 50.0
        private const val BLACK = 0.10
        private const val WHITE = 0.90
        private const val VERTEX_SHADER = """
            #version 300 es
            void main() { vec2 p[3] = vec2[3](vec2(-1.0,-1.0), vec2(3.0,-1.0), vec2(-1.0,3.0)); gl_Position=vec4(p[gl_VertexID],0.0,1.0); }
        """
        private const val FRAGMENT_SHADER = """
            #version 300 es
            precision highp float;
            layout(location=0) out vec4 outValue;
            const float BASE=50.0, BLACK=0.10, WHITE=0.90;
            float enc(float x) { x=clamp(x,0.0,1.0); return BLACK+(WHITE-BLACK)*log(1.0+BASE*x)/log(1.0+BASE); }
            float dec(float y) { y=clamp(y,BLACK,WHITE); return (pow(1.0+BASE,(y-BLACK)/(WHITE-BLACK))-1.0)/BASE; }
            void main() { float x=clamp((gl_FragCoord.x-0.5)/512.0,0.0,1.0); float y=enc(x); outValue=vec4(y,dec(y),x,1.0); }
        """
    }
}
