/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Explicit vertex input also works on drivers that mishandle attribute-less gl_VertexID draws. */
internal class PreviewQuad {
    private val buffer = IntArray(1)
    init {
        GLES30.glGenBuffers(1, buffer, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffer[0])
        val positions = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer()
        positions.put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).position(0)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 32, positions, GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }
    fun draw() {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffer[0])
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }
    fun delete() { GLES30.glDeleteBuffers(1, buffer, 0) }
}
