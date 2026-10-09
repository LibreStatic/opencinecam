/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

enum class DisplayCapability { UNKNOWN, UNSUPPORTED, UNAVAILABLE, AVAILABLE, ACTIVE }
enum class DisplayOperation { PRESENT, TRANSFER }
enum class DisplaySessionPhase { IDLE, STARTING, ACTIVE }
enum class FoldPosture { NONE_REPORTED, FLAT, TABLETOP, BOOK, SEPARATING }
enum class SubjectDisplayMode { STATUS, TELEPROMPTER, PREVIEW, FILL_LIGHT, REVIEW, INTERVIEW, SLATE }
enum class SubjectPreviewGuide { NONE, THIRDS, SAFE_AREA }
enum class SubjectSlateField { PROJECT, SCENE, TAKE, CAMERA, REEL, TIMECODE }

const val SUBJECT_INTERVIEW_MAX_QUESTIONS = 50
const val SUBJECT_INTERVIEW_MAX_QUESTION_LENGTH = 300

data class SubjectDisplaySettings(
    val mode: SubjectDisplayMode = SubjectDisplayMode.STATUS,
    val brightness: Float = 0.7f,
    val touchLocked: Boolean = true,
    val showStatus: Boolean = true,
    val operatorCue: String = "",
    val prompterText: String = "",
    val prompterFontSp: Int = 32,
    val prompterSpeedDpPerSecond: Int = 24,
    val prompterPaused: Boolean = true,
    val continueRecordingOnFold: Boolean = true,
    val adaptToHinge: Boolean = true,
    val swapPanes: Boolean = false,
    val previewMirror: Boolean = true,
    val previewViewAssist: Boolean = true,
    val selfTimerSeconds: Int = 0,
    val selfMinimalControls: Boolean = true,
    // Subject self-monitor overlays; they draw on the exterior surface only, never the file.
    val previewRecordedAreaBands: Boolean = true,
    val previewGuide: SubjectPreviewGuide = SubjectPreviewGuide.NONE,
    val previewAudioMeter: Boolean = false,
    val tallyBorder: Boolean = true,
    val giantCountdown: Boolean = true,
    val fillLightKelvin: Int = 5000,
    val fillLightTint: Int = 0,
    // Zero keeps the fill light on until the operator changes mode.
    val fillLightTimeoutSeconds: Int = 0,
    // Kept apart from the teleprompter script; it shares only the font size.
    val interviewQuestions: List<String> = emptyList(),
    val slateFields: Set<SubjectSlateField> = SubjectSlateField.entries.toSet(),
    val slateSyncFlash: Boolean = false,
    // Off by default: the beep also lands in this phone's own recording.
    val slateSyncBeep: Boolean = false,
    val outOfFrameWarning: Boolean = false,
    val outOfFrameDelaySeconds: Int = 2,
    // Modes whose cover screen is split: the mode on top, the rear camera preview below. Never PREVIEW.
    val splitPreviewModes: Set<SubjectDisplayMode> = emptySet(),
    // Percent of the split preview's pixels left visible by the fill-light dither mask.
    val fillLightPreviewLevel: Int = 50,
) {
    init {
        require(brightness.isFinite() && brightness in 0f..1f)
        require(operatorCue.length <= 200)
        require(prompterText.length <= 20_000)
        require(prompterFontSp in 16..72)
        require(prompterSpeedDpPerSecond in 5..120)
        require(selfTimerSeconds in setOf(0, 3, 5, 10))
        require(fillLightKelvin in 2700..6500)
        require(fillLightTint in -50..50)
        require(fillLightTimeoutSeconds in 0..3600)
        require(interviewQuestions.size <= SUBJECT_INTERVIEW_MAX_QUESTIONS)
        require(interviewQuestions.all { it.isNotBlank() && it.length <= SUBJECT_INTERVIEW_MAX_QUESTION_LENGTH })
        require(outOfFrameDelaySeconds in 1..10)
        require(SubjectDisplayMode.PREVIEW !in splitPreviewModes)
        require(fillLightPreviewLevel in 10..100)
    }
}

val SubjectDisplaySettings.splitsPreview: Boolean
    get() = mode != SubjectDisplayMode.PREVIEW && mode in splitPreviewModes

/** True when the cover screen shows the rear camera's live view, whole or split. */
val SubjectDisplaySettings.wantsCameraPreview: Boolean
    get() = mode == SubjectDisplayMode.PREVIEW || splitsPreview

/** Operator-driven subject state that is never persisted: the review pick and the interview position. */
data class SubjectSessionCues(
    val reviewUri: String? = null,
    val interviewIndex: Int = 0,
) {
    init { require(interviewIndex >= 0) }
}

data class FoldHinge(val left: Int, val top: Int, val right: Int, val bottom: Int, val horizontal: Boolean)

data class FoldDisplayState(
    val presentation: DisplayCapability = DisplayCapability.UNKNOWN,
    val transfer: DisplayCapability = DisplayCapability.UNKNOWN,
    val phase: DisplaySessionPhase = DisplaySessionPhase.IDLE,
    val operation: DisplayOperation? = null,
    val visible: Boolean = false,
    val failure: String? = null,
    val posture: FoldPosture = FoldPosture.NONE_REPORTED,
    val hinge: FoldHinge? = null,
)

/** Main-thread state machine. A closed/pending session can never be revived by a late callback. */
class FoldSessionStateMachine {
    var state = FoldDisplayState()
        private set
    private var generation = 0L

    fun capabilities(presentation: DisplayCapability, transfer: DisplayCapability) {
        state = state.copy(presentation = presentation, transfer = transfer)
    }

    fun posture(posture: FoldPosture, hinge: FoldHinge?) { state = state.copy(posture = posture, hinge = hinge) }

    fun begin(operation: DisplayOperation): Long? {
        val capability = if (operation == DisplayOperation.PRESENT) state.presentation else state.transfer
        if (state.phase != DisplaySessionPhase.IDLE || capability != DisplayCapability.AVAILABLE) return null
        state = state.copy(phase = DisplaySessionPhase.STARTING, operation = operation, visible = false, failure = null)
        return ++generation
    }

    fun started(token: Long): Boolean {
        if (token != generation || state.phase != DisplaySessionPhase.STARTING) return false
        state = state.copy(phase = DisplaySessionPhase.ACTIVE)
        return true
    }

    fun visibility(token: Long, visible: Boolean) {
        if (token == generation && state.phase == DisplaySessionPhase.ACTIVE) state = state.copy(visible = visible)
    }

    fun ended(token: Long, failure: String? = null): Boolean {
        if (token != generation) return false
        close(failure)
        return true
    }

    fun close(failure: String? = null) {
        generation++
        state = state.copy(phase = DisplaySessionPhase.IDLE, operation = null, visible = false, failure = failure)
    }
}

/** Hysteresis prevents hinge noise from firing more than once per actual close edge. */
class FoldCloseDetector {
    private var openObserved = false
    fun sample(degrees: Float): Boolean {
        if (!degrees.isFinite() || degrees !in 0f..180f) return false
        if (degrees >= 15f) openObserved = true
        if (degrees <= 5f && openObserved) {
            openObserved = false
            return true
        }
        return false
    }
}
