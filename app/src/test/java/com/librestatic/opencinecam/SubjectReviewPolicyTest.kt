/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import com.librestatic.opencinecam.storage.OcLogClip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class SubjectReviewPolicyTest {
    private val presenting = FoldDisplayState(phase = DisplaySessionPhase.ACTIVE, operation = DisplayOperation.PRESENT, visible = true)
    private val idle = CameraUiState(phase = CameraUiPhase.PREVIEWING, selectedMode = CaptureMode.VIDEO)
    private val clip = SubjectReviewPick("content://media/1", "A001.mp4", "video/mp4")
    private val photo = SubjectReviewPick("content://media/2", "A002.jpg", "image/jpeg")

    @Test fun showRemembersTheLiveModeAndSwitchesToReview() {
        val step = SubjectReviewPolicy.show(SubjectReviewState(), clip, SubjectDisplayMode.PREVIEW, presenting, idle)!!
        assertEquals(SubjectDisplayMode.REVIEW, step.mode)
        assertEquals(clip, step.state.pick)
        assertEquals(SubjectDisplayMode.PREVIEW, step.state.returnMode)
    }

    @Test fun aNewPickWhileReviewingKeepsTheOriginalReturnMode() {
        val first = SubjectReviewPolicy.show(SubjectReviewState(), clip, SubjectDisplayMode.TELEPROMPTER, presenting, idle)!!.state
        val second = SubjectReviewPolicy.show(first, photo, SubjectDisplayMode.REVIEW, presenting, idle)!!
        assertEquals(photo, second.state.pick)
        assertEquals(SubjectDisplayMode.TELEPROMPTER, second.state.returnMode)
    }

    @Test fun reviewNeedsAnActivePresentationAndNoTake() {
        val transfer = presenting.copy(operation = DisplayOperation.TRANSFER)
        val starting = presenting.copy(phase = DisplaySessionPhase.STARTING)
        for (display in listOf(FoldDisplayState(), transfer, starting))
            assertNull(SubjectReviewPolicy.show(SubjectReviewState(), clip, SubjectDisplayMode.STATUS, display, idle))
        for (camera in listOf(idle.copy(phase = CameraUiPhase.RECORDING), idle.copy(phase = CameraUiPhase.CAPTURING),
            idle.copy(recordingFinalizing = true)))
            assertNull(SubjectReviewPolicy.show(SubjectReviewState(), clip, SubjectDisplayMode.STATUS, presenting, camera))
        val audio = clip.copy(mimeType = "audio/mp4")
        assertNull(SubjectReviewPolicy.show(SubjectReviewState(), audio, SubjectDisplayMode.STATUS, presenting, idle))
    }

    @Test fun aStillCaptureDoesNotCountAsATake() {
        val still = idle.copy(phase = CameraUiPhase.CAPTURING, selectedMode = CaptureMode.PHOTO)
        assertFalse(still.reviewBlockedByTake())
        assertTrue(idle.copy(phase = CameraUiPhase.CAPTURING, selectedMode = CaptureMode.LOG).reviewBlockedByTake())
    }

    @Test fun recStartStopsReviewAndRestoresThePreviousMode() {
        val reviewing = SubjectReviewPolicy.show(SubjectReviewState(), clip, SubjectDisplayMode.PREVIEW, presenting, idle)!!.state
        for (camera in listOf(idle.copy(phase = CameraUiPhase.CAPTURING), idle.copy(phase = CameraUiPhase.RECORDING))) {
            val step = SubjectReviewPolicy.camera(reviewing, SubjectDisplayMode.REVIEW, camera)
            assertEquals(SubjectReviewState(), step.state)
            assertEquals(SubjectDisplayMode.PREVIEW, step.mode)
        }
        // Without a take nothing changes.
        assertEquals(SubjectReviewStep(reviewing), SubjectReviewPolicy.camera(reviewing, SubjectDisplayMode.REVIEW, idle))
    }

    @Test fun decoderFailureReturnsTheSubjectToStatus() {
        val reviewing = SubjectReviewPolicy.show(SubjectReviewState(), clip, SubjectDisplayMode.TELEPROMPTER, presenting, idle)!!.state
        val step = SubjectReviewPolicy.failed(reviewing, SubjectDisplayMode.REVIEW, clip.uri)
        assertEquals(SubjectReviewState(), step.state)
        assertEquals(SubjectDisplayMode.STATUS, step.mode)
        // A late failure from an earlier pick is ignored.
        assertEquals(SubjectReviewStep(reviewing), SubjectReviewPolicy.failed(reviewing, SubjectDisplayMode.REVIEW, photo.uri))
    }

    @Test fun sessionEndClearsThePickAndRestoresTheMode() {
        val reviewing = SubjectReviewPolicy.show(SubjectReviewState(), photo, SubjectDisplayMode.SLATE, presenting, idle)!!.state
        val step = SubjectReviewPolicy.sessionEnded(reviewing, SubjectDisplayMode.REVIEW)
        assertNull(step.state.pick)
        assertEquals(SubjectDisplayMode.SLATE, step.mode)
        assertEquals(SubjectReviewStep(SubjectReviewState()), SubjectReviewPolicy.sessionEnded(SubjectReviewState(), SubjectDisplayMode.STATUS))
    }

    @Test fun aHandChosenModeIsKeptWhenReviewStops() {
        val reviewing = SubjectReviewPolicy.show(SubjectReviewState(), clip, SubjectDisplayMode.PREVIEW, presenting, idle)!!.state
        val manual = SubjectReviewPolicy.modeChanged(reviewing, SubjectDisplayMode.FILL_LIGHT)
        assertEquals(SubjectReviewStep(SubjectReviewState()), manual)
        assertNull(SubjectReviewPolicy.stop(reviewing, SubjectDisplayMode.FILL_LIGHT).mode)
    }

    @Test fun controllerPublishesTheCueAndStoresModeThroughSettings() {
        val repository = SettingsRepository(MemorySettings(CameraSettings(subjectDisplay = SubjectDisplaySettings(mode = SubjectDisplayMode.PREVIEW))))
        val cues = mutableListOf<String?>()
        val controller = SubjectReviewController(repository, scope) { cues += it }
        val log = clip.copy(log = OcLogClip("OCLog2", "2", "BT.2020", false, null, null))
        assertTrue(controller.show(log, presenting))
        assertEquals(SubjectDisplayMode.REVIEW, repository.states.value.subjectDisplay.mode)
        assertEquals(log, controller.picks.value)
        controller.onCameraState(idle.copy(phase = CameraUiPhase.RECORDING))
        assertTrue(controller.takeBusy.value)
        assertNull(controller.picks.value)
        assertEquals(SubjectDisplayMode.PREVIEW, repository.states.value.subjectDisplay.mode)
        assertEquals(listOf(clip.uri, null), cues)
        // Refused while the take runs.
        assertFalse(controller.show(clip, presenting))
    }

    @Test fun controllerForgetsThePickWhenTheOperatorChangesMode() {
        val repository = SettingsRepository(MemorySettings(CameraSettings()))
        val cues = mutableListOf<String?>()
        val controller = SubjectReviewController(repository, scope) { cues += it }
        controller.onCameraState(idle)
        assertTrue(controller.show(photo, presenting))
        repository.update { it.copy(subjectDisplay = it.subjectDisplay.copy(mode = SubjectDisplayMode.TELEPROMPTER)) }
        assertNull(controller.picks.value)
        assertEquals(SubjectDisplayMode.TELEPROMPTER, repository.states.value.subjectDisplay.mode)
        assertEquals(listOf(photo.uri, null), cues)
        controller.playbackFailed(photo.uri)
        assertEquals(SubjectDisplayMode.TELEPROMPTER, repository.states.value.subjectDisplay.mode)
    }

    private val scope = CoroutineScope(Dispatchers.Unconfined)
    @After fun cancelScope() = scope.cancel()

    private class MemorySettings(private var value: CameraSettings) : SettingsPersistence {
        override fun load() = value
        override fun save(settings: CameraSettings) { value = settings }
    }
}
