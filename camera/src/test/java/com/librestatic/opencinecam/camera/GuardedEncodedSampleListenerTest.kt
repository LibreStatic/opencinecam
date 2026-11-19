/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import android.media.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A take's sample listener must never break the muxer path: the first failure ends its calls for the take. */
class GuardedEncodedSampleListenerTest {
    private class Recording(private val failAt: Int = Int.MAX_VALUE, private val failure: Throwable = IllegalStateException("boom")) : EncodedSampleListener {
        val samples = ArrayList<Triple<Boolean, Int, Long>>()
        override fun onTrack(video: Boolean, format: MediaFormat, rotationDegrees: Int) = Unit
        override fun onSample(video: Boolean, size: Int, presentationTimeUs: Long, flags: Int) {
            if (samples.size == failAt) throw failure
            samples += Triple(video, size, presentationTimeUs)
        }
    }

    @Test fun passesSamplesThrough() {
        val delegate = Recording()
        val guard = GuardedEncodedSampleListener(delegate)
        guard.onSample(true, 10, 1L, 1)
        guard.onSample(false, 20, 2L, 0)
        assertEquals(listOf(Triple(true, 10, 1L), Triple(false, 20, 2L)), delegate.samples)
        assertFalse(guard.isDisabled)
    }

    @Test fun aFailureIsSwallowedAndEndsTheCallsForTheTake() {
        val delegate = Recording(failAt = 1)
        val guard = GuardedEncodedSampleListener(delegate)
        guard.onSample(true, 10, 1L, 1)
        guard.onSample(true, 11, 2L, 0) // throws inside the delegate; must not reach the caller
        assertTrue(guard.isDisabled)
        guard.onSample(true, 12, 3L, 0)
        assertEquals(1, delegate.samples.size)
    }

    @Test fun anErrorIsSwallowedToo() {
        val guard = GuardedEncodedSampleListener(Recording(failAt = 0, failure = OutOfMemoryError()))
        guard.onSample(true, 10, 1L, 1)
        assertTrue(guard.isDisabled)
    }
}
