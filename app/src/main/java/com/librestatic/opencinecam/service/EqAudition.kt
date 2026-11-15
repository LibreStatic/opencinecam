/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.service

import com.librestatic.opencinecam.camera.DigitalRecordingGain
import com.librestatic.opencinecam.camera.EqCurve
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The equaliser editor's snippet audition (PLAN-074 decision 13): records the raw input from the preview monitor,
 * then loops it through the curve being edited, with the monitor stopped so the speaker does not feed the meters.
 * Main thread only; the recorder and the player report through [post]. The snippet is the user's voice, so it is
 * only ever held in memory.
 */
internal class EqAudition(
    private val state: MutableStateFlow<EqAuditionState>,
    private val post: (Runnable) -> Unit,
    private val hooks: Hooks,
) {
    interface Hooks {
        /** The editor alone has the audio: no take, still, qualification, transfer or viewfinder needs it. */
        fun allowed(): Boolean
        /** The preview monitor running now, if any. */
        fun monitor(): EqSnippetSource?
        /** A preview monitor may start (one may be on its way). */
        fun monitorEligible(): Boolean
        /** Retires the preview monitor without ending the audition. */
        fun stopMonitor()
        /** Starts the preview monitor again if it is eligible. */
        fun restartMonitor()
        /** Starts a looping playback; its callbacks run on the playback thread. */
        fun startPlayer(snippet: EqSnippet, onProgress: () -> Unit, onFailure: (Throwable) -> Unit): EqSnippetPlayback
        /** The playback ended: its late levels and spectra must not publish. */
        fun playbackStopped()
    }

    private var recorder: EqSnippetRecorder? = null
    private var recordingSource: EqSnippetSource? = null
    /** Asked to record while no monitor ran yet (one was starting, or the playback had just stopped). */
    private var recordingPending = false
    private var snippet: EqSnippet? = null
    private var player: EqSnippetPlayback? = null

    /** The preview monitor must not run while the snippet plays. */
    val playing: Boolean get() = player != null

    fun startRecording() {
        stopPlayback(restartMonitor = true)
        finishRecording(keep = false)
        recordingPending = false
        if (!hooks.allowed()) return fail()
        val source = hooks.monitor()
        when {
            source != null -> attach(source)
            hooks.monitorEligible() -> {
                snippet = null
                recordingPending = true
                publish(EqAuditionPhase.RECORDING)
            }
            else -> fail()
        }
    }

    fun stopRecording() {
        if (recordingPending) {
            recordingPending = false
            publish(EqAuditionPhase.IDLE)
        } else finishRecording(keep = true)
    }

    fun play() {
        if (recorder != null || recordingPending) stopRecording()
        if (player != null) return
        val kept = snippet ?: return fail()
        if (!hooks.allowed()) return fail()
        hooks.stopMonitor()
        var started: EqSnippetPlayback? = null
        started = try {
            hooks.startPlayer(kept,
                onProgress = { post(Runnable { if (player === started && player != null && !hooks.allowed()) stopPlayback(restartMonitor = true) }) },
                onFailure = { post(Runnable {
                    if (player === started && player != null) {
                        stopPlayback(restartMonitor = true)
                        publish(EqAuditionPhase.IDLE, failed = true)
                    }
                }) },
            )
        } catch (_: Throwable) {
            hooks.playbackStopped()
            hooks.restartMonitor()
            return fail()
        }
        player = started
        publish(EqAuditionPhase.PLAYING)
    }

    fun stopPlayback() = stopPlayback(restartMonitor = true)

    fun discard() {
        end(keepSnippet = false, restartMonitor = true)
    }

    /**
     * Ends whatever runs: a recording keeps what it has unless [keepSnippet] is false, which also drops the kept
     * snippet. [restartMonitor] reopens the microphone after a playback when the caller is not managing it.
     */
    fun end(keepSnippet: Boolean, restartMonitor: Boolean = false) {
        // Called on every monitor stop: with nothing to end, the last state (a failure) stays as it is.
        if (recorder == null && !recordingPending && player == null && (keepSnippet || snippet == null)) return
        recordingPending = false
        finishRecording(keep = keepSnippet)
        stopPlayback(restartMonitor)
        if (!keepSnippet) snippet = null
        publish(EqAuditionPhase.IDLE)
    }

    /** The curve and gain the monitor would apply now; the playback adopts them at its next chunk. */
    fun updatePlayback(curve: EqCurve, gain: DigitalRecordingGain) {
        player?.setCurve(curve)
        player?.setRecordingGain(gain)
    }

    fun onMonitorStarted(source: EqSnippetSource) {
        if (!recordingPending) return
        recordingPending = false
        if (hooks.allowed()) attach(source) else publish(EqAuditionPhase.IDLE)
    }

    /** A monitor stopping or restarting ends the recording it fed with what it has. */
    fun onMonitorStopped(source: EqSnippetSource?) {
        if (source != null && source === recordingSource) finishRecording(keep = true)
    }

    /** The monitor a pending recording waited for could not open. */
    fun onMonitorUnavailable() {
        if (!recordingPending) return
        recordingPending = false
        fail()
    }

    private fun attach(source: EqSnippetSource) {
        var created: EqSnippetRecorder? = null
        created = try {
            EqSnippetRecorder(source.snippetEncoding, source.snippetSampleRateHz, source.snippetChannels) { recordedMs, full ->
                post(Runnable { onRecorderProgress(created, recordedMs, full) })
            }
        } catch (_: Throwable) {
            return fail()
        }
        snippet = null
        recorder = created
        recordingSource = source
        source.snippetTap = created
        publish(EqAuditionPhase.RECORDING)
    }

    private fun onRecorderProgress(source: EqSnippetRecorder?, recordedMs: Int, full: Boolean) {
        if (source == null || source !== recorder) return
        if (full || !hooks.allowed()) finishRecording(keep = true)
        else publish(EqAuditionPhase.RECORDING, recordedMs = recordedMs)
    }

    private fun finishRecording(keep: Boolean) {
        val current = recorder ?: return
        recordingSource?.let { if (it.snippetTap === current) it.snippetTap = null }
        recorder = null
        recordingSource = null
        val finished = current.finish()
        if (keep) snippet = finished
        publish(EqAuditionPhase.IDLE)
    }

    private fun stopPlayback(restartMonitor: Boolean) {
        val current = player ?: return
        player = null
        current.close()
        hooks.playbackStopped()
        publish(EqAuditionPhase.IDLE)
        if (restartMonitor) hooks.restartMonitor()
    }

    private fun fail() = publish(EqAuditionPhase.IDLE, failed = true)

    private fun publish(phase: EqAuditionPhase, recordedMs: Int = 0, failed: Boolean = false) {
        state.value = EqAuditionState(phase = phase, snippetMs = snippet?.durationMs ?: 0, recordedMs = recordedMs, failed = failed)
    }
}
