/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */
package com.librestatic.opencinecam.service

import com.librestatic.opencinecam.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EngineFailureTextTest {
    private val text: (Int) -> String = { "text-$it" }

    @Test fun fixedEngineSentenceIsReplacedByTheLocalizedNotice() {
        assertEquals("text-${R.string.engine_failure_camera_disconnected}", engineFailureText("camera-disconnected", "Camera disconnected", text))
        assertEquals("text-${R.string.engine_failure_still_format}", engineFailureText("still-format-unavailable", "Selected still format is unavailable", text))
    }

    @Test fun exceptionCarryingCodeAppendsTheRawDetail() {
        assertEquals("text-${R.string.engine_failure_camera_open} · CAMERA_IN_USE (4)", engineFailureText("camera-open-exception", "CAMERA_IN_USE (4)", text))
        assertEquals("text-${R.string.engine_failure_video_start} · a b", engineFailureText("video-start-failed", " a\n  b ", text))
    }

    @Test fun blankDetailShowsOnlyTheNotice() {
        assertEquals("text-${R.string.engine_failure_photo}", engineFailureText("still-encode-failed", "  ", text))
    }

    @Test fun sequencePrefixNamesTheSequence() {
        assertEquals(EngineFailureNotice(R.string.engine_failure_burst, false), engineFailureNotice("burst-photo-flash-timeout"))
        assertEquals(EngineFailureNotice(R.string.engine_failure_bracket, true), engineFailureNotice("bracket-photo-flash-capture-failed"))
        assertEquals(EngineFailureNotice(R.string.engine_failure_accumulation, true), engineFailureNotice("accumulation-restore-failed"))
        assertEquals(EngineFailureNotice(R.string.engine_failure_sequence_too_large, false), engineFailureNotice("burst-byte-limit"))
    }

    @Test fun familiesFallBackToTheirNotice() {
        assertEquals(EngineFailureNotice(R.string.engine_failure_camera_open, false), engineFailureNotice("camera-open-3"))
        assertEquals(EngineFailureNotice(R.string.engine_failure_photo, true), engineFailureNotice("photo-flash-submit-failed"))
        assertEquals(EngineFailureNotice(R.string.engine_failure_night, false), engineFailureNotice("night-extension-closed"))
    }

    @Test fun unknownCodeKeepsTheEngineMessage() {
        assertNull(engineFailureNotice("something-new"))
        assertEquals("Raw engine text", engineFailureText("something-new", "Raw engine text", text))
        assertEquals("something-new", engineFailureText("something-new", "", text))
    }
}
