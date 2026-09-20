/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import android.opengl.EGL14
import android.opengl.EGLDisplay

/** EGL initialization is not reference-counted by the API. Retired output windows may outlive a pipeline. */
internal object GpuEglDisplayLease {
    private var display = EGL14.EGL_NO_DISPLAY
    private var clients = 0

    @Synchronized fun acquire(): EGLDisplay {
        if (clients == 0) {
            val candidate = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(candidate != EGL14.EGL_NO_DISPLAY) { "EGL display is unavailable." }
            val version = IntArray(2)
            check(EGL14.eglInitialize(candidate, version, 0, version, 1)) { "EGL initialization failed." }
            display = candidate
        }
        clients++
        return display
    }

    @Synchronized fun release(value: EGLDisplay) {
        check(clients > 0 && value == display)
        clients--
        if (clients == 0) {
            EGL14.eglTerminate(display)
            display = EGL14.EGL_NO_DISPLAY
        }
    }
}
