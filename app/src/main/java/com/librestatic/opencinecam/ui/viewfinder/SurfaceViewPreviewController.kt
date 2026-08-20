/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.ui.viewfinder

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.librestatic.opencinecam.core.model.PreviewSurfaceEvent
import com.librestatic.opencinecam.core.model.PreviewSurfaceSession

/** Bridges SurfaceView lifecycle events without letting the view own camera/session resources. */
class SurfaceViewPreviewController(
    private val onEvent: (PreviewSurfaceEvent, Surface?) -> Unit,
) : SurfaceHolder.Callback {
    private val session = PreviewSurfaceSession()
    private var view: SurfaceView? = null
    private var surfaceId: String? = null

    fun attach(next: SurfaceView) {
        if (view != null) {
            onEvent(
                session.attach(surfaceKey(next)),
                null,
            )
            return
        }
        view = next
        surfaceId = surfaceKey(next)
        onEvent(session.attach(surfaceId!!), null)
        next.holder.addCallback(this)
    }

    fun detach(current: SurfaceView) {
        val id = surfaceId ?: return
        if (view !== current) return
        current.holder.removeCallback(this)
        onEvent(session.detach(id), null)
        view = null
        surfaceId = null
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val id = surfaceId ?: return
        onEvent(session.markReady(id), holder.surface)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        val id = surfaceId ?: return
        onEvent(session.markLost(id), null)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    private fun surfaceKey(surfaceView: SurfaceView): String =
        "surface-${System.identityHashCode(surfaceView)}"
}
