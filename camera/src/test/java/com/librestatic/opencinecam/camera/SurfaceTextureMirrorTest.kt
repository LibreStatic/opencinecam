/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import org.junit.Assert.assertArrayEquals
import org.junit.Test

/**
 * Matrices built as SurfaceTexture builds them (AOSP computeTransformMatrix, no crop) from the
 * buffer transform Camera2 sets on a stream (CameraUtils.getRotationTransform).
 */
class SurfaceTextureMirrorTest {
    @Test
    fun frontStreamsLoseTheSelfieFlipAndMatchAnUnmirroredSensorOfTheSameOrientation() {
        // Front: FLIP_H composed with the rotation; the same orientation unmirrored is the back-camera transform.
        assertUnmirrors(FLIP_H, ROT_0)
        assertUnmirrors(FLIP_H xor ROT_270, ROT_90)
        assertUnmirrors(FLIP_H xor ROT_180, ROT_180)
        assertUnmirrors(FLIP_H xor ROT_90, ROT_270)
    }

    @Test
    fun unmirroredStreamsAreLeftAlone() {
        for (transform in listOf(ROT_0, ROT_90, ROT_180, ROT_270)) {
            val matrix = surfaceTextureMatrix(transform)
            val expected = matrix.copyOf()
            unmirrorSurfaceTextureMatrix(matrix)
            assertArrayEquals(expected, matrix, 0f)
        }
    }

    private fun assertUnmirrors(mirrored: Int, unmirrored: Int) {
        val matrix = surfaceTextureMatrix(mirrored)
        unmirrorSurfaceTextureMatrix(matrix)
        assertArrayEquals(surfaceTextureMatrix(unmirrored), matrix, 1e-6f)
        // Twice is still unmirrored: the frame is sampled from the same matrix on every draw.
        unmirrorSurfaceTextureMatrix(matrix)
        assertArrayEquals(surfaceTextureMatrix(unmirrored), matrix, 1e-6f)
    }

    private fun surfaceTextureMatrix(transform: Int): FloatArray {
        var xform = IDENTITY
        if (transform and FLIP_H != 0) xform = multiply(xform, MTX_FLIP_H)
        if (transform and FLIP_V != 0) xform = multiply(xform, MTX_FLIP_V)
        if (transform and ROT_90 != 0) xform = multiply(xform, MTX_ROT_90)
        return multiply(MTX_FLIP_V, xform)
    }

    private fun multiply(a: FloatArray, b: FloatArray) = FloatArray(16) { index ->
        val column = index / 4
        val row = index % 4
        (0 until 4).sumOf { k -> (a[k * 4 + row] * b[column * 4 + k]).toDouble() }.toFloat()
    }

    private companion object {
        const val FLIP_H = 1
        const val FLIP_V = 2
        const val ROT_0 = 0
        const val ROT_90 = 4
        const val ROT_180 = FLIP_H or FLIP_V
        const val ROT_270 = ROT_180 or ROT_90

        // Column-major, as android.opengl.Matrix and SurfaceTexture lay them out.
        val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        val MTX_FLIP_H = floatArrayOf(-1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 0f, 0f, 1f)
        val MTX_FLIP_V = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f)
        val MTX_ROT_90 = floatArrayOf(0f, 1f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 0f, 0f, 1f)
    }
}
