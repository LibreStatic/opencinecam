/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.view.Surface
import java.io.Closeable
import java.util.concurrent.CompletableFuture

/**
 * What the review dialog drives (PLAN-071 §7 decision 6). [MediaPlaybackSession] (MediaPlayer and
 * MediaCodec) and [RawPlaybackSession] (`.ocraw` takes) implement it and report through
 * [PlaybackObservation]. Calls come from the main thread.
 */
internal interface PlaybackController : Closeable {
    /** Completes once the session has let go of its decoders, files and Surface producers after [close]. */
    val retirement: CompletableFuture<Unit>

    /** The display Surface, or null once it is destroyed; a null must stop drawing to the old one before returning. */
    fun setSurface(surface: Surface?)

    /** The Surface's size in pixels (surfaceChanged). Only sessions that draw with their own GL stage need it. */
    fun setSurfaceSize(width: Int, height: Int) {}

    /**
     * The review's zoom for sessions that draw with their own GL stage: [scale] around the surface centre,
     * then the pan [offsetX]/[offsetY] as fractions of the surface's width and height (y down). Others
     * are zoomed by the dialog itself.
     */
    fun setZoom(scale: Float, offsetX: Float, offsetY: Float) {}

    /** The host is paused or stopped: stop moving and give up output that cannot survive it. */
    fun suspendOutput()

    /** The host is resumed after [suspendOutput]. */
    fun resumeOutput()

    fun update(settings: PlaybackSettings)
    fun play()
    fun pause()
    fun seek(timeUs: Long)
    fun step(delta: Int)
}
