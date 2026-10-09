package com.librestatic.opencinecam

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.staticCompositionLocalOf

/** The latest [CameraUiState] including live samples; null where none is provided (previews, tests). */
val LocalLiveCameraState = staticCompositionLocalOf<State<CameraUiState>?> { null }

/** The latest state including live samples; read it only in the leaf that draws them, so only that leaf recomposes per sample. */
@Composable
internal fun liveCameraState(state: CameraUiState): CameraUiState = LocalLiveCameraState.current?.value ?: state
