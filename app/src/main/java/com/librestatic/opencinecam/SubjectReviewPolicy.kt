/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import com.librestatic.opencinecam.storage.OcLogClip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * OCC-PLAN-068 U4. The take or photo the operator chose for the cover. Runtime only: it is never
 * persisted, and [log] is the sidecar's OCLog2 declaration read on the inner screen.
 */
internal data class SubjectReviewPick(val uri: String, val name: String, val mimeType: String, val log: OcLogClip? = null) {
    val video: Boolean get() = mimeType.startsWith("video/")
    val photo: Boolean get() = mimeType.startsWith("image/")
}

/** The current pick and the live mode the subject returns to when review stops. Never persisted. */
internal data class SubjectReviewState(val pick: SubjectReviewPick? = null, val returnMode: SubjectDisplayMode? = null)

/** The next runtime state and, when non-null, the subject mode to store through the settings path. */
internal data class SubjectReviewStep(val state: SubjectReviewState, val mode: SubjectDisplayMode? = null)

private val REVIEW_STILL_MODES = setOf(CaptureMode.PHOTO, CaptureMode.RAW_PHOTO, CaptureMode.BURST, CaptureMode.BRACKET, CaptureMode.LIGHT_TRAIL)

/** The service reports a take preparing, running or finalizing. Still captures do not stop review. */
internal fun CameraUiState.reviewBlockedByTake(): Boolean = phase == CameraUiPhase.RECORDING || recordingFinalizing ||
    phase == CameraUiPhase.CAPTURING && selectedMode !in REVIEW_STILL_MODES

/** Pure review rules; the controller applies them on the main thread. */
internal object SubjectReviewPolicy {
    /** Only an active simultaneous presentation may show review, and never while a take starts or runs. */
    fun canShow(display: FoldDisplayState, camera: CameraUiState): Boolean =
        display.phase == DisplaySessionPhase.ACTIVE && display.operation == DisplayOperation.PRESENT && !camera.reviewBlockedByTake()

    /** Null when the request is refused. A new pick while already reviewing keeps the original return mode. */
    fun show(state: SubjectReviewState, pick: SubjectReviewPick, mode: SubjectDisplayMode, display: FoldDisplayState,
        camera: CameraUiState): SubjectReviewStep? {
        if (!canShow(display, camera) || !(pick.video || pick.photo)) return null
        val back = if (mode == SubjectDisplayMode.REVIEW) state.returnMode ?: SubjectDisplayMode.STATUS else mode
        return SubjectReviewStep(SubjectReviewState(pick, back), SubjectDisplayMode.REVIEW)
    }

    /** Clears the pick; while the subject is still in REVIEW it returns to [fallback] or the remembered live mode. */
    fun stop(state: SubjectReviewState, mode: SubjectDisplayMode, fallback: SubjectDisplayMode? = null): SubjectReviewStep {
        if (state.pick == null && state.returnMode == null) return SubjectReviewStep(state)
        val next = if (mode == SubjectDisplayMode.REVIEW) fallback ?: state.returnMode ?: SubjectDisplayMode.STATUS else null
        return SubjectReviewStep(SubjectReviewState(), next)
    }

    /** A take that starts (service-confirmed preparing or recording) ends review and restores the live mode. */
    fun camera(state: SubjectReviewState, mode: SubjectDisplayMode, camera: CameraUiState): SubjectReviewStep =
        if (state.pick != null && camera.reviewBlockedByTake()) stop(state, mode) else SubjectReviewStep(state)

    /** A decoder failure for the current pick returns the subject to STATUS; a stale pick's failure is ignored. */
    fun failed(state: SubjectReviewState, mode: SubjectDisplayMode, uri: String): SubjectReviewStep =
        if (state.pick?.uri == uri) stop(state, mode, SubjectDisplayMode.STATUS) else SubjectReviewStep(state)

    /** The presentation ended: drop the pick so a later session never shows it automatically. */
    fun sessionEnded(state: SubjectReviewState, mode: SubjectDisplayMode): SubjectReviewStep = stop(state, mode)

    /** The operator chose another mode by hand: forget the pick without touching that choice. */
    fun modeChanged(state: SubjectReviewState, mode: SubjectDisplayMode): SubjectReviewStep =
        if (mode != SubjectDisplayMode.REVIEW && (state.pick != null || state.returnMode != null)) SubjectReviewStep(SubjectReviewState())
        else SubjectReviewStep(state)
}

/** The narrow view the subject window gets: the pick to play and a failure report. No capture or settings action. */
internal interface SubjectReviewFeed {
    val picks: StateFlow<SubjectReviewPick?>
    fun playbackFailed(uri: String)
}

/**
 * Owned by FoldDisplayCoordinator; main thread only. Operator commands (show, clear) and service state
 * (take start) change the pick; the subject mode is stored through the ordinary settings path, and the
 * pick reaches the subject as [SubjectSessionCues.reviewUri] through [publishCue].
 */
internal class SubjectReviewController(
    private val settings: SettingsRepository,
    scope: CoroutineScope,
    private val publishCue: (String?) -> Unit,
) : SubjectReviewFeed {
    private val mutable = MutableStateFlow(SubjectReviewState())
    val states: StateFlow<SubjectReviewState> = mutable.asStateFlow()
    private val pick = MutableStateFlow<SubjectReviewPick?>(null)
    override val picks: StateFlow<SubjectReviewPick?> = pick.asStateFlow()
    private val camera = MutableStateFlow(CameraUiState())
    /** True while a take prepares, runs or finalizes; the inner action is disabled then. */
    private val busy = MutableStateFlow(false)
    val takeBusy: StateFlow<Boolean> = busy.asStateFlow()
    private val mode get() = settings.states.value.subjectDisplay.mode

    init {
        scope.launch { settings.states.collect { apply(SubjectReviewPolicy.modeChanged(mutable.value, it.subjectDisplay.mode)) } }
    }

    fun show(selection: SubjectReviewPick, display: FoldDisplayState): Boolean {
        val step = SubjectReviewPolicy.show(mutable.value, selection, mode, display, camera.value) ?: return false
        apply(step)
        return true
    }

    fun clear() = apply(SubjectReviewPolicy.stop(mutable.value, mode))

    fun onCameraState(state: CameraUiState) {
        camera.value = state
        busy.value = state.reviewBlockedByTake()
        apply(SubjectReviewPolicy.camera(mutable.value, mode, state))
    }

    fun onSessionEnded() = apply(SubjectReviewPolicy.sessionEnded(mutable.value, mode))

    override fun playbackFailed(uri: String) = apply(SubjectReviewPolicy.failed(mutable.value, mode, uri))

    private fun apply(step: SubjectReviewStep) {
        // Runtime state first: the mode change below re-enters through the settings collector.
        val previous = mutable.value
        mutable.value = step.state
        pick.value = step.state.pick
        if (previous.pick?.uri != step.state.pick?.uri) publishCue(step.state.pick?.uri)
        step.mode?.let { next ->
            settings.update { current ->
                if (current.subjectDisplay.mode == next) current else current.copy(subjectDisplay = current.subjectDisplay.copy(mode = next))
            }
        }
    }
}
