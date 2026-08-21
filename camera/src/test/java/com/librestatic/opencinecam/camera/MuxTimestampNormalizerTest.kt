/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class MuxTimestampNormalizerTest {
    @Test
    fun `rebases video and audio epochs independently`() {
        val normalizer = MuxTimestampNormalizer()

        assertEquals(0L, normalizer.normalize(video = false, presentationTimeUs = 0L))
        assertEquals(0L, normalizer.normalize(video = true, presentationTimeUs = 26_832_161_600L))
        assertEquals(21_333L, normalizer.normalize(video = false, presentationTimeUs = 21_333L))
        assertEquals(33_333L, normalizer.normalize(video = true, presentationTimeUs = 26_832_194_933L))
    }

    @Test
    fun `never emits a negative or regressing timestamp`() {
        val normalizer = MuxTimestampNormalizer()

        assertEquals(0L, normalizer.normalize(video = true, presentationTimeUs = 10_000L))
        assertEquals(5_000L, normalizer.normalize(video = true, presentationTimeUs = 15_000L))
        assertEquals(5_000L, normalizer.normalize(video = true, presentationTimeUs = 14_000L))
    }
}
