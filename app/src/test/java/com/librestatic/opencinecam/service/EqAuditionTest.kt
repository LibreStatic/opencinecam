/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.service

import com.librestatic.opencinecam.camera.DigitalRecordingGain
import com.librestatic.opencinecam.camera.EqCurve
import com.librestatic.opencinecam.camera.PcmMeterEncoding
import java.nio.ByteBuffer
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class EqAuditionTest {
    private class FakeMonitor : EqSnippetSource {
        override val snippetEncoding = PcmMeterEncoding.PCM_16
        override val snippetSampleRateHz = 8_000
        override val snippetChannels = 1
        override var snippetTap: EqSnippetRecorder? = null
        fun feed(ms: Int) = snippetTap!!.append(ByteBuffer.allocateDirect(ms * 16), ms * 16)
    }

    private class FakePlayback : EqSnippetPlayback {
        var closed = false
        var adoptedCurve: EqCurve? = null
        var adoptedGain: DigitalRecordingGain? = null
        override fun setCurve(curve: EqCurve) { adoptedCurve = curve }
        override fun setRecordingGain(gain: DigitalRecordingGain) { adoptedGain = gain }
        override fun close() { closed = true }
    }

    private class Harness {
        val state = MutableStateFlow(EqAuditionState())
        val posted = ArrayDeque<Runnable>()
        var allowed = true
        var monitor: FakeMonitor? = FakeMonitor()
        var monitorEligible = true
        var monitorStops = 0
        var monitorRestarts = 0
        var playbackStops = 0
        var failPlayer = false
        val players = mutableListOf<FakePlayback>()
        var playerFailure: ((Throwable) -> Unit)? = null
        var playerProgress: (() -> Unit)? = null
        val audition = EqAudition(state, { posted.addLast(it) }, object : EqAudition.Hooks {
            override fun allowed() = allowed
            override fun monitor() = monitor
            override fun monitorEligible() = monitorEligible
            override fun stopMonitor() { monitorStops++; monitor = null }
            override fun restartMonitor() { monitorRestarts++ }
            override fun startPlayer(snippet: EqSnippet, onProgress: () -> Unit, onFailure: (Throwable) -> Unit): EqSnippetPlayback {
                if (failPlayer) error("no track")
                playerProgress = onProgress
                playerFailure = onFailure
                return FakePlayback().also { players += it }
            }
            override fun playbackStopped() { playbackStops++ }
        })
        fun drain() { while (posted.isNotEmpty()) posted.removeFirst().run() }
        fun record(ms: Int) {
            audition.startRecording()
            monitor!!.feed(ms)
            drain()
            audition.stopRecording()
        }
    }

    @Test fun refusesToRecordWithoutAMonitor() {
        val harness = Harness().apply { monitor = null; monitorEligible = false }
        harness.audition.startRecording()
        assertEquals(EqAuditionState(failed = true), harness.state.value)
        harness.allowed = false
        harness.monitor = FakeMonitor()
        harness.audition.startRecording()
        assertTrue(harness.state.value.failed)
        assertNull(harness.monitor!!.snippetTap)
    }

    @Test fun recordsThroughTheMonitorsTapAndKeepsTheSnippet() {
        val harness = Harness()
        val monitor = harness.monitor!!
        harness.audition.startRecording()
        assertNotNull(monitor.snippetTap)
        assertEquals(EqAuditionPhase.RECORDING, harness.state.value.phase)
        monitor.feed(600)
        harness.drain()
        assertEquals(EqAuditionPhase.RECORDING, harness.state.value.phase)
        assertEquals(600, harness.state.value.recordedMs)
        harness.audition.stopRecording()
        assertNull(monitor.snippetTap)
        assertEquals(EqAuditionState(snippetMs = 600), harness.state.value)
    }

    @Test fun endsAtTheCapAndDiscardsAShortSnippet() {
        val harness = Harness()
        harness.audition.startRecording()
        repeat(260) { harness.monitor!!.feed(40) }
        harness.drain()
        assertEquals(EqAuditionState(snippetMs = EQ_SNIPPET_MAX_MS), harness.state.value)
        harness.record(300)
        assertEquals(EqAuditionState(), harness.state.value)
    }

    @Test fun aMonitorStoppingEndsTheRecordingWithWhatItHas() {
        val harness = Harness()
        val monitor = harness.monitor!!
        harness.audition.startRecording()
        monitor.feed(800)
        harness.audition.onMonitorStopped(monitor)
        assertNull(monitor.snippetTap)
        assertEquals(EqAuditionState(snippetMs = 800), harness.state.value)
    }

    @Test fun playsWithTheMonitorStoppedAndRestartsItAfter() {
        val harness = Harness()
        harness.record(1_000)
        harness.audition.play()
        assertTrue(harness.audition.playing)
        assertEquals(1, harness.monitorStops)
        assertEquals(EqAuditionState(phase = EqAuditionPhase.PLAYING, snippetMs = 1_000), harness.state.value)
        val curve = EqCurve(trimDb = -3.0)
        harness.audition.updatePlayback(curve, DigitalRecordingGain(true, 4))
        assertEquals(curve, harness.players.single().adoptedCurve)
        assertEquals(DigitalRecordingGain(true, 4), harness.players.single().adoptedGain)
        harness.audition.stopPlayback()
        assertTrue(harness.players.single().closed)
        assertFalse(harness.audition.playing)
        assertEquals(1, harness.playbackStops)
        assertEquals(1, harness.monitorRestarts)
        assertEquals(EqAuditionState(snippetMs = 1_000), harness.state.value)
    }

    @Test fun refusesToPlayWithoutASnippetOrWhenTheTrackFails() {
        val harness = Harness()
        harness.audition.play()
        assertTrue(harness.state.value.failed)
        harness.record(1_000)
        harness.failPlayer = true
        harness.audition.play()
        assertFalse(harness.audition.playing)
        assertTrue(harness.state.value.failed)
        assertEquals(1, harness.monitorRestarts)
    }

    @Test fun aFailedTrackStopsThePlayback() {
        val harness = Harness()
        harness.record(1_000)
        harness.audition.play()
        harness.playerFailure!!(IllegalStateException("write failed"))
        assertTrue(harness.audition.playing)
        harness.drain()
        assertFalse(harness.audition.playing)
        assertEquals(EqAuditionState(snippetMs = 1_000, failed = true), harness.state.value)
        assertEquals(1, harness.monitorRestarts)
    }

    @Test fun playbackStopsWhenTheEditorLosesTheAudio() {
        val harness = Harness()
        harness.record(1_000)
        harness.audition.play()
        harness.playerProgress!!()
        harness.drain()
        assertTrue(harness.audition.playing)
        harness.allowed = false
        harness.playerProgress!!()
        harness.drain()
        assertFalse(harness.audition.playing)
        assertTrue(harness.players.single().closed)
    }

    @Test fun recordingWhilePlayingWaitsForTheMonitor() {
        val harness = Harness()
        harness.record(1_000)
        harness.audition.play()
        harness.audition.startRecording()
        assertTrue(harness.players.single().closed)
        assertEquals(EqAuditionState(phase = EqAuditionPhase.RECORDING), harness.state.value)
        // The stopped playback asked for the monitor once; recording does not ask again.
        assertEquals(1, harness.monitorRestarts)
        val restarted = FakeMonitor()
        harness.audition.onMonitorStarted(restarted)
        assertNotNull(restarted.snippetTap)
        restarted.feed(700)
        harness.audition.stopRecording()
        assertEquals(EqAuditionState(snippetMs = 700), harness.state.value)
    }

    @Test fun aPendingRecordingFailsWhenTheMonitorCannotOpen() {
        val harness = Harness().apply { monitor = null }
        harness.audition.startRecording()
        assertEquals(EqAuditionPhase.RECORDING, harness.state.value.phase)
        harness.audition.onMonitorUnavailable()
        assertEquals(EqAuditionState(failed = true), harness.state.value)
    }

    @Test fun recordingAfterAFailedMonitorAsksForOne() {
        // The last monitor failed and nothing restarted it: the recording must not wait for nothing.
        val harness = Harness().apply { monitor = null }
        harness.audition.startRecording()
        assertEquals(1, harness.monitorRestarts)
        assertEquals(EqAuditionPhase.RECORDING, harness.state.value.phase)
        val started = FakeMonitor()
        harness.audition.onMonitorStarted(started)
        assertNotNull(started.snippetTap)
        started.feed(700)
        harness.audition.stopRecording()
        assertEquals(EqAuditionState(snippetMs = 700), harness.state.value)
    }

    @Test fun endingDropsTheSnippetOnlyWhenAsked() {
        val harness = Harness()
        harness.record(1_000)
        harness.audition.play()
        harness.audition.end(keepSnippet = true)
        assertFalse(harness.audition.playing)
        assertEquals(0, harness.monitorRestarts)
        assertEquals(EqAuditionState(snippetMs = 1_000), harness.state.value)
        harness.audition.end(keepSnippet = false)
        assertEquals(EqAuditionState(), harness.state.value)
        harness.audition.play()
        assertTrue(harness.state.value.failed)
        // With nothing to end, a monitor stop leaves the failure showing.
        harness.audition.end(keepSnippet = true)
        assertTrue(harness.state.value.failed)
    }

    @Test fun discardDropsARecordingInProgress() {
        val harness = Harness()
        val monitor = harness.monitor!!
        harness.audition.startRecording()
        monitor.feed(900)
        harness.audition.discard()
        assertNull(monitor.snippetTap)
        assertEquals(EqAuditionState(), harness.state.value)
    }
}
