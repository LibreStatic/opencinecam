/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import android.media.MediaFormat
import android.util.Log

/**
 * Sees exactly what the GPU pipeline's muxer is given, so the app can keep a side index of a take on a removable
 * drive (PLAN-072): a pulled drive leaves an MP4 with no `moov`, and the index is what lets one be rebuilt.
 *
 * Both calls run on the codec drain thread, once per sample: implementations must be cheap and must not allocate.
 * [format] is the muxer's track format, csd-0/csd-1 included. [rotationDegrees] is the container rotation (0 for audio).
 */
interface EncodedSampleListener {
    fun onTrack(video: Boolean, format: MediaFormat, rotationDegrees: Int)

    fun onSample(video: Boolean, size: Int, presentationTimeUs: Long, flags: Int)
}

/** Wraps a listener so it can never break a take: the first Throwable is logged and ends the calls for the take. */
internal class GuardedEncodedSampleListener(private val delegate: EncodedSampleListener) {
    @Volatile
    private var failed = false

    val isDisabled: Boolean get() = failed

    fun onTrack(video: Boolean, format: MediaFormat, rotationDegrees: Int) {
        if (failed) return
        try { delegate.onTrack(video, format, rotationDegrees) } catch (failure: Throwable) { disable(failure) }
    }

    fun onSample(video: Boolean, size: Int, presentationTimeUs: Long, flags: Int) {
        if (failed) return
        try { delegate.onSample(video, size, presentationTimeUs, flags) } catch (failure: Throwable) { disable(failure) }
    }

    private fun disable(failure: Throwable) {
        failed = true
        // The log itself must never throw into the muxer path (it does under plain JVM tests).
        runCatching { Log.w(TAG, "The take's sample listener failed; it is not called again for this take.", failure) }
    }

    private companion object {
        const val TAG = "EncodedSampleListener"
    }
}
