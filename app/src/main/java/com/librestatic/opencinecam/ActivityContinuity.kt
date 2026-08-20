/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

enum class RecordingContinuity { IDLE, RECORDING }
enum class ActivityContinuityAction { KEEP_RECORDING, REATTACH_PREVIEW, DETACH_PREVIEW, REFLOW_UI, REQUIRE_CAMERA_SELECTION, IGNORE_STALE_SURFACE }

data class ActivityContinuityState(
    val selectedCameraId: String? = null,
    val recording: RecordingContinuity = RecordingContinuity.IDLE,
    val previewSurfaceId: String? = null,
    val layout: ProbeLayoutMode = ProbeLayoutMode.PORTRAIT,
    val foldSeparating: Boolean = false,
) {
    init {
        require(selectedCameraId == null || selectedCameraId.isNotBlank())
        require(previewSurfaceId == null || previewSurfaceId.isNotBlank())
    }
}

data class ActivityContinuityEffect(
    val action: ActivityContinuityAction,
    val cameraId: String? = null,
    val surfaceId: String? = null,
)

sealed interface ActivityContinuityCommand {
    data class SelectCamera(val cameraId: String) : ActivityContinuityCommand
    data object StartRecording : ActivityContinuityCommand
    data object StopRecording : ActivityContinuityCommand
    data class SurfaceCreated(val surfaceId: String) : ActivityContinuityCommand
    data class SurfaceDestroyed(val surfaceId: String) : ActivityContinuityCommand
    data class ActivityRecreated(val widthDp: Int, val heightDp: Int, val foldSeparating: Boolean) : ActivityContinuityCommand
    data object Backgrounded : ActivityContinuityCommand
}

/** Keeps selected route and recording ownership stable while the Activity/foldable UI recreates. */
class ActivityContinuityCoordinator(
    initial: ActivityContinuityState = ActivityContinuityState(),
) {
    var state: ActivityContinuityState = initial
        private set

    fun dispatch(command: ActivityContinuityCommand): List<ActivityContinuityEffect> = when (command) {
        is ActivityContinuityCommand.SelectCamera -> {
            require(command.cameraId.isNotBlank())
            state = state.copy(selectedCameraId = command.cameraId)
            emptyList()
        }
        ActivityContinuityCommand.StartRecording -> {
            check(state.selectedCameraId != null) { "camera must be selected before recording" }
            state = state.copy(recording = RecordingContinuity.RECORDING)
            listOf(ActivityContinuityEffect(ActivityContinuityAction.KEEP_RECORDING, state.selectedCameraId))
        }
        ActivityContinuityCommand.StopRecording -> {
            state = state.copy(recording = RecordingContinuity.IDLE)
            emptyList()
        }
        is ActivityContinuityCommand.SurfaceCreated -> {
            require(command.surfaceId.isNotBlank())
            state = state.copy(previewSurfaceId = command.surfaceId)
            listOf(ActivityContinuityEffect(ActivityContinuityAction.REATTACH_PREVIEW, state.selectedCameraId, command.surfaceId))
        }
        is ActivityContinuityCommand.SurfaceDestroyed -> if (state.previewSurfaceId == command.surfaceId) {
            state = state.copy(previewSurfaceId = null)
            listOf(ActivityContinuityEffect(ActivityContinuityAction.DETACH_PREVIEW, state.selectedCameraId, command.surfaceId))
        } else listOf(ActivityContinuityEffect(ActivityContinuityAction.IGNORE_STALE_SURFACE, state.selectedCameraId, command.surfaceId))
        is ActivityContinuityCommand.ActivityRecreated -> {
            state = state.copy(layout = probeLayoutMode(command.widthDp, command.heightDp, command.foldSeparating), foldSeparating = command.foldSeparating)
            buildList {
                add(ActivityContinuityEffect(ActivityContinuityAction.REFLOW_UI, state.selectedCameraId, state.previewSurfaceId))
                if (state.recording == RecordingContinuity.RECORDING) add(ActivityContinuityEffect(ActivityContinuityAction.KEEP_RECORDING, state.selectedCameraId))
                if (state.selectedCameraId == null) add(ActivityContinuityEffect(ActivityContinuityAction.REQUIRE_CAMERA_SELECTION))
            }
        }
        ActivityContinuityCommand.Backgrounded -> if (state.recording == RecordingContinuity.RECORDING) {
            listOf(ActivityContinuityEffect(ActivityContinuityAction.KEEP_RECORDING, state.selectedCameraId))
        } else emptyList()
    }
}
