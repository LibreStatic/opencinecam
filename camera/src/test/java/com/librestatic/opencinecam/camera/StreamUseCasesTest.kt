/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamUseCasesTest {
    private val razr = setOf(0L, 1L, 2L, 3L, 4L, 5L, 65536L, 65537L)

    @After fun reset() = StreamUseCaseRejections.clearForTest()

    @Test fun rolesMapToTheirStandardUseCase() {
        assertEquals(StreamUseCase.PREVIEW, streamUseCaseFor(StreamRole.PREVIEW, razr))
        assertEquals(StreamUseCase.VIDEO_RECORD, streamUseCaseFor(StreamRole.RECORDING, razr))
        assertEquals(StreamUseCase.STILL_CAPTURE, streamUseCaseFor(StreamRole.STILL, razr))
    }

    @Test fun rawStaysOnDefault() {
        assertNull(streamUseCaseFor(StreamRole.RAW, razr))
    }

    @Test fun sharedOutputPrefersPreviewVideoStillThenVideoRecord() {
        assertEquals(StreamUseCase.PREVIEW_VIDEO_STILL, streamUseCaseFor(StreamRole.PREVIEW_AND_RECORDING, razr))
        assertEquals(StreamUseCase.VIDEO_RECORD, streamUseCaseFor(StreamRole.PREVIEW_AND_RECORDING, setOf(0L, 1L, 3L)))
        assertNull(streamUseCaseFor(StreamRole.PREVIEW_AND_RECORDING, setOf(0L, 1L, 2L)))
    }

    @Test fun unadvertisedAndVendorValuesAreNeverChosen() {
        assertNull(streamUseCaseFor(StreamRole.STILL, setOf(0L, 1L, 3L)))
        assertNull(streamUseCaseFor(StreamRole.PREVIEW, setOf(65536L, 65537L)))
        assertNull(streamUseCaseFor(StreamRole.RECORDING, emptySet()))
    }

    @Test fun applicabilityNeedsApiCapabilityAndARegularSession() {
        val caps = setOf(1, StreamUseCase.CAPABILITY)
        assertTrue(streamUseCasesApplicable(33, caps, razr, highSpeed = false))
        assertFalse(streamUseCasesApplicable(32, caps, razr, highSpeed = false))
        assertFalse(streamUseCasesApplicable(34, setOf(1), razr, highSpeed = false))
        assertFalse(streamUseCasesApplicable(34, caps, razr, highSpeed = true))
        assertFalse(streamUseCasesApplicable(34, caps, setOf(65536L), highSpeed = false))
    }

    @Test fun rejectionIsRememberedPerCameraAndSuppressesApplied() {
        StreamUseCaseRejections.markApplied("0")
        assertTrue(StreamUseCaseRejections.wasApplied("0"))
        StreamUseCaseRejections.reject("0")
        assertTrue(StreamUseCaseRejections.isRejected("0"))
        assertFalse(StreamUseCaseRejections.wasApplied("0"))
        assertFalse(StreamUseCaseRejections.isRejected("1"))
        StreamUseCaseRejections.markApplied("0")
        assertFalse(StreamUseCaseRejections.wasApplied("0"))
    }
}
