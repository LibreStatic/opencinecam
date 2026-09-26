/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class SharedCapturePauseTest {
    private val origin = 1_000_000_000L
    private fun time(frame: Long) = origin + frame * 125000
    @Test fun uninterruptedInputsKeepTheirSourceAndTime() {
        val clock = SharedCapturePause(8000, origin)
        assertEquals(listOf(PcmKeepSpan(0, 32)), clock.audio(0, 32))
        assertEquals(time(10), clock.video(time(10)))
        assertEquals(32L, clock.report().retainedPcmFrames)
    }
    @Test fun bothTracksRemoveTheSameSampleWindow() {
        val clock = SharedCapturePause(8000, origin)
        clock.audio(0, 10); clock.video(time(9)); assertTrue(clock.setPaused(true, time(10)))
        assertTrue(clock.audio(10, 10).isEmpty()); assertNull(clock.video(time(19)))
        assertTrue(clock.setPaused(false, time(20)))
        assertEquals(time(10), clock.video(time(20)))
        assertEquals(listOf(PcmKeepSpan(0, 10)), clock.audio(20, 10))
        assertEquals(20L, clock.report().retainedPcmFrames)
    }
    @Test fun aReadCanStraddleBothPauseBoundaries() {
        val clock = SharedCapturePause(8000, origin)
        clock.setPaused(true, time(10)); clock.setPaused(false, time(20))
        assertEquals(listOf(PcmKeepSpan(0, 10), PcmKeepSpan(20, 12)), clock.audio(0, 32))
        assertEquals(22L, clock.report().retainedPcmFrames)
    }
    @Test fun openPauseDropsAudioAndVideoWithoutStoppingReadAccounting() {
        val clock = SharedCapturePause(8000, origin)
        clock.setPaused(true, time(0)); assertTrue(clock.audio(0, 200).isEmpty())
        assertNull(clock.video(time(100))); assertEquals(200L, clock.report().capturedPcmFrames)
    }
    @Test fun stopWhilePausedSealsTheIntervalAndRejectsLaterInput() {
        val clock = SharedCapturePause(8000, origin)
        clock.setPaused(true, time(10)); clock.finish(time(30))
        assertEquals(listOf(PcmKeepSpan(0, 10)), clock.audio(0, 40))
        assertEquals(listOf(CapturePauseWindow(10, 30)), clock.report().windows)
        assertNull(clock.video(time(40))); assertFalse(clock.setPaused(false, time(50)))
    }
    @Test fun terminalCutDoesNotPublishCapturedFramesBeyondStop() {
        val clock = SharedCapturePause(8000, origin); clock.finish(time(10))
        assertEquals(listOf(PcmKeepSpan(0, 10)), clock.audio(0, 20)); assertEquals(10L, clock.report().stopFrame)
    }
    @Test fun commandsNeverReclassifyPreviouslySelectedAudio() {
        val clock = SharedCapturePause(8000, origin); clock.audio(0, 100)
        clock.setPaused(true, time(20)); assertEquals(100L, clock.report().windows.single().startFrame)
        assertEquals(time(100), clock.report().lastEffectiveBoundaryNs)
    }
    @Test fun commandsNeverReclassifyPreviouslySubmittedVideo() {
        val clock = SharedCapturePause(8000, origin); clock.video(time(100))
        clock.setPaused(true, time(20)); assertEquals(101L, clock.report().windows.single().startFrame)
    }
    @Test fun fractionalSampleRateUsesTheSameIntegerBoundariesForVideo() {
        val rate = 44100; fun at(frame: Long) = origin + pcmFrameDurationNs(frame, rate)
        val clock = SharedCapturePause(rate, origin)
        clock.setPaused(true, at(101)); clock.setPaused(false, at(733))
        val window = clock.report().windows.single()
        assertEquals(at(1000) - (at(window.endFrame!!) - at(window.startFrame)), clock.video(at(1000)))
        assertEquals(1000L - (window.endFrame!! - window.startFrame), clock.audio(0, 1000).sumOf { it.frames.toLong() })
    }
    @Test fun finishIsIdempotentAndPauseCannotReopenIt() {
        val clock = SharedCapturePause(8000, origin); clock.finish(time(10)); clock.finish(time(20))
        assertEquals(10L, clock.report().stopFrame); assertFalse(clock.setPaused(true, time(30)))
    }
    @Test fun disjointSourceReadsAreRejected() {
        val clock = SharedCapturePause(8000, origin); clock.audio(0, 10)
        assertThrows(IllegalArgumentException::class.java) { clock.audio(11, 5) }
    }
    @Test fun regressingVideoIsDroppedAndRegressingCommandClockIsRejected() {
        val clock = SharedCapturePause(8000, origin); clock.video(time(10))
        assertNull(clock.video(time(9))); assertEquals(1L, clock.report().regressedVideoFrames)
        // The dropped frame never became the classification horizon.
        clock.setPaused(true, time(5)); assertEquals(11L, clock.report().windows.single().startFrame)
        assertThrows(IllegalArgumentException::class.java) { clock.setPaused(false, time(4)) }
    }
    @Test fun regressionAfterAPauseWindowIsDroppedAndTheNextMonotonicFrameKeepsExactSubtraction() {
        val clock = SharedCapturePause(8000, origin); assertEquals(time(5), clock.video(time(5)))
        assertTrue(clock.setPaused(true, time(10))); assertTrue(clock.setPaused(false, time(20)))
        assertEquals(time(11), clock.video(time(21)))
        assertNull(clock.video(time(15))); assertNull(clock.video(time(3)))
        assertEquals(time(12), clock.video(time(22)))
        assertEquals(2L, clock.report().regressedVideoFrames)
        assertEquals(listOf(CapturePauseWindow(10, 20)), clock.report().windows)
    }
    @Test fun rejectedCommandsCarryAReasonAndTheWindowCapIsExplicit() {
        val clock = SharedCapturePause(8000, origin)
        assertEquals(CapturePauseRejection.UNCHANGED, clock.requestPaused(false, time(1)))
        for (i in 0 until SharedCapturePause.MAX_WINDOWS) {
            assertNull(clock.requestPaused(true, time(2L * i + 2))); assertNull(clock.requestPaused(false, time(2L * i + 3)))
        }
        assertEquals(10000, clock.report().windows.size)
        assertEquals(CapturePauseRejection.WINDOW_LIMIT, clock.requestPaused(true, time(30000)))
        assertFalse(clock.setPaused(true, time(30001))); assertFalse(clock.paused)
        clock.finish(time(30002)); assertEquals(CapturePauseRejection.STOPPED, clock.requestPaused(true, time(30003)))
    }
    @Test fun stereoCompactionPreservesWholeFramesAcrossTwoCuts() {
        val buffer = ByteBuffer.allocateDirect(40); repeat(40) { buffer.put(it, it.toByte()) }
        val count = compactPcm16(buffer, 40, 2, listOf(PcmKeepSpan(0, 2), PcmKeepSpan(4, 2), PcmKeepSpan(9, 1)))
        assertEquals(20, count)
        val expected = (0..7).toList() + (16..23).toList() + (36..39).toList()
        assertEquals(expected, (0 until count).map { buffer.get(it).toInt() })
    }
    @Test fun invalidCompactionDoesNotAcceptOverlappingFrames() {
        assertThrows(IllegalArgumentException::class.java) { compactPcm16(ByteBuffer.allocate(40), 40, 2, listOf(PcmKeepSpan(1, 3), PcmKeepSpan(2, 1))) }
    }
    @Test fun pauseNeedsActualComparableCameraAndPcmAnchors() {
        for ((realtime, backed) in listOf(false to true, true to false)) {
            val clock = CaptureEpochClock(realtime, 8000)
            clock.audioInput(AudioCaptureEpoch(origin, backed)); clock.mapVideoInput(time(1))
            assertFalse(clock.pauseAvailable()); assertFalse(clock.setPaused(true, time(10)))
            assertEquals(listOf(PcmKeepSpan(0, 20)), clock.selectAudio(0, 20))
        }
    }
    @Test fun captureClockConnectsSelectionToFinalRetainedFrameAcceptance() {
        val clock = CaptureEpochClock(true, 8000)
        clock.selectAudio(0, 10); clock.audioInput(AudioCaptureEpoch(origin, true)); clock.mapVideoInput(time(9))
        assertTrue(clock.pauseAvailable()); clock.setPaused(true, time(10)); clock.selectAudio(10, 10)
        clock.setPaused(false, time(20)); clock.selectAudio(20, 10)
        clock.requireRetainedAudioFrames(20)
        assertThrows(IllegalStateException::class.java) { clock.requireRetainedAudioFrames(30) }
        val report = clock.report(null, null, null)
        assertEquals(30L, report.capturedPcmFrames); assertEquals(20L, report.sharedPause!!.retainedPcmFrames)
    }
}
