/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.librestatic.opencinecam.capture

import kotlinx.coroutines.flow.drop
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import com.librestatic.opencinecam.ui.theme.LocalReducedMotion
import androidx.compose.foundation.lazy.staggeredgrid.items
import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.alpha
import com.librestatic.opencinecam.ui.viewfinder.chromePanel
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.compose.ui.tooling.preview.Preview
import com.librestatic.opencinecam.service.CaptureService
import com.librestatic.opencinecam.camera.AfLockBehavior
import com.librestatic.opencinecam.camera.LockState
import com.librestatic.opencinecam.camera.label
import kotlin.math.abs
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.unit.Dp
import com.librestatic.opencinecam.CameraUiPhase
import com.librestatic.opencinecam.CameraUiState
import com.librestatic.opencinecam.CaptureMode
import com.librestatic.opencinecam.R
import com.librestatic.opencinecam.gallery.glyph
import com.librestatic.opencinecam.settings.AccumulationCaptureProgress
import com.librestatic.opencinecam.settings.BracketCaptureProgress
import com.librestatic.opencinecam.settings.CameraSettings
import com.librestatic.opencinecam.settings.NightToggleAction
import com.librestatic.opencinecam.settings.captureWantsAudio
import com.librestatic.opencinecam.settings.fpsLockKeyVisible
import com.librestatic.opencinecam.settings.nightPhotoSelected
import com.librestatic.opencinecam.settings.nightToggleAction
import com.librestatic.opencinecam.settings.nightToggleAvailable
import com.librestatic.opencinecam.settings.nightToggleVisible
import com.librestatic.opencinecam.ui.CineGlyph
import com.librestatic.opencinecam.ui.CineIcon
import com.librestatic.opencinecam.ui.HapticEvent
import com.librestatic.opencinecam.ui.LocalAppHaptics
import com.librestatic.opencinecam.ui.rememberPermissionAccess

/** Pause uses its own row so it never overlaps Stop on narrow/large-font displays. */
@Composable
internal fun PortraitCaptureTransport(
    state: CameraUiState,
    binder: CaptureService.LocalBinder?,
    settings: CameraSettings,
    // The gallery sits under the thumb, opposite monitoring, when the top bar does not carry it.
    onOpenMedia: (() -> Unit)? = null,
    panelBackground: Boolean = true,
    onShowMonitoring: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().then(if (panelBackground) Modifier.background(Panel) else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            onOpenMedia?.let { open ->
                Box(Modifier.align(Alignment.CenterStart).padding(start = 24.dp)) {
                    MediaThumbnailAction(state.lastSavedUri, open, size = 52.dp)
                }
            }
            CaptureButton(state, binder, settings, 72.dp)
            Box(Modifier.align(Alignment.CenterEnd).padding(end = if (onOpenMedia != null) 24.dp else 12.dp)) {
                if (onOpenMedia != null) TransportSideAction(CineIcon.MONITORING, stringResource(R.string.monitoring_tools), onShowMonitoring)
                else TopAction(CineIcon.MONITORING, stringResource(R.string.monitoring_tools), onShowMonitoring)
            }
        }
        RecordingPauseButton(state) { requestRecordingPause(state, binder, it) }
        BurstCaptureProgress(state) { binder?.cancelBurstCapture() }
        BracketCaptureProgress(state) { binder?.cancelBracketCapture() }
        AccumulationCaptureProgress(state, { binder?.finishAccumulationCapture() }, { binder?.cancelAccumulationCapture() })
    }
}

@Composable
internal fun LockToggles(
    state: CameraUiState,
    binder: CaptureService.LocalBinder?,
    afLockBehavior: AfLockBehavior,
    settings: CameraSettings,
    onSettingsChanged: (CameraSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalAppHaptics.current
    val manualExposure = state.requestedIso != null || state.requestedExposureTimeNs != null
    val manualFocus = state.requestedFocusDiopters != null
    val aeAvailable = state.aeLockSupported && !manualExposure && !state.captureControlsLocked
    val afAvailable = state.afLockSupported && !manualFocus && !state.captureControlsLocked
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Night photo sits first in the row. While it can be offered on this camera its place is
        // kept, so the lock keys and the instruments under them stay put when it appears.
        if (nightToggleAvailable(state, settings)) {
            if (nightToggleVisible(state, settings)) {
                val engaged = nightPhotoSelected(state, settings)
                LockButton(
                    label = stringResource(if (engaged) R.string.night_photo_toggle_on else R.string.night_photo_toggle_off),
                    shortLabel = stringResource(R.string.night_photo_short),
                    active = engaged,
                    enabled = !state.captureControlsLocked,
                    testTag = "capture-night-toggle",
                    glyph = CineIcon.MOON,
                    onClick = {
                        haptics.perform(if (engaged) HapticEvent.LockOff else HapticEvent.LockOn)
                        when (val action = nightToggleAction(state, settings)) {
                            is NightToggleAction.SetEngaged -> binder?.setNightPhotoEngaged(action.engaged)
                            NightToggleAction.ReleaseForced -> onSettingsChanged(settings.copy(nightPhotoMode = com.librestatic.opencinecam.camera.NightPhotoMode.AUTO))
                        }
                    },
                )
            } else Spacer(Modifier.size(48.dp))
        }
        // FPS LOCK (video modes): lock closed while on; amber while it is actually holding the shutter at 1/fps.
        if (fpsLockKeyVisible(state)) {
            val on = settings.fpsLock
            val holding = on && state.fpsLockHolding
            LockButton(
                label = stringResource(when { holding -> R.string.fps_lock_toggle_holding; on -> R.string.fps_lock_toggle_on; else -> R.string.fps_lock_toggle_off }),
                shortLabel = stringResource(R.string.fps_lock_short),
                active = on && !holding,
                pending = holding,
                enabled = !state.captureControlsLocked,
                testTag = "capture-fps-lock",
                glyph = if (on) CineIcon.LOCK else CineIcon.UNLOCK,
                onClick = {
                    haptics.perform(if (on) HapticEvent.LockOff else HapticEvent.LockOn)
                    onSettingsChanged(settings.copy(fpsLock = !on))
                },
            )
        }
        // LENS LOCK: pins one physical lens (zoom becomes digital); highlighted while locked.
        if (lensLockKeyVisible(state)) {
            val lockedOption = state.lensLock.lockedOption
            val on = state.lensLock.locked
            LockButton(
                label = if (lockedOption != null) stringResource(R.string.lens_lock_toggle_on, lensLockOptionLabel(lockedOption))
                    else stringResource(R.string.lens_lock_toggle_off),
                shortLabel = stringResource(R.string.lens_lock_short),
                active = on,
                enabled = lensLockChangeAllowed(state),
                testTag = "capture-lens-lock",
                glyph = if (on) CineIcon.LOCK else CineIcon.UNLOCK,
                onClick = {
                    haptics.perform(if (on) HapticEvent.LockOff else HapticEvent.LockOn)
                    binder?.setLensLock(if (on) null else lensLockToggleTarget(state) ?: return@LockButton)
                },
            )
        }
        if (state.aeLockSupported) {
            LockButton(
                label = stringResource(R.string.ae_lock),
                shortLabel = stringResource(R.string.ae_lock_short),
                active = state.aeLockActive,
                enabled = aeAvailable,
                testTag = "ae-lock-toggle",
                onClick = {
                    haptics.perform(if (state.aeLockActive) HapticEvent.LockOff else HapticEvent.LockOn)
                    binder?.setAeLock(!state.aeLockActive)
                },
            )
        }
        val afPending = state.afLockState == LockState.PENDING
        val afLocked = state.afLockState == LockState.LOCKED
        if (state.afLockSupported) {
            LockButton(
                label = if (afPending) stringResource(R.string.af_lock_pending) else stringResource(R.string.af_lock),
                shortLabel = stringResource(if (afPending) R.string.af_lock_pending_short else R.string.af_lock_short),
                active = afLocked,
                pending = afPending,
                enabled = afAvailable,
                testTag = "af-lock-toggle",
                onClick = {
                    haptics.perform(if (afLocked || afPending) HapticEvent.LockOff else HapticEvent.LockOn)
                    if (afLocked || afPending) binder?.setAfLock(false, afLockBehavior)
                    else binder?.setAfLock(true, afLockBehavior)
                },
            )
        }
    }
}

@Composable
private fun LockButton(
    label: String,
    shortLabel: String,
    active: Boolean,
    enabled: Boolean = true,
    pending: Boolean = false,
    testTag: String,
    glyph: CineIcon? = null,
    onClick: () -> Unit,
) {
    val bg = when {
        active -> VerifiedCyan
        pending -> Amber
        enabled -> Color(0xFF1B2023).chromePanel()
        else -> Color(0xFF161A1C).chromePanel()
    }
    val fg = if (active || pending) Color.Black else if (enabled) Color.White else Color(0xFF626A6D)
    val border = if (active) VerifiedCyan else if (pending) Amber else if (enabled) Color(0xFF41494C) else Color(0xFF2A3033)
    // Same footprint as the operator keys below: a padlock over a short AE/AF label. The full
    // name stays the accessible label, and the closed shackle marks the engaged lock.
    Column(
        Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = label
                role = Role.Switch
                stateDescription = if (active) "on" else "off"
            }
            .testTag(testTag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CineGlyph(glyph ?: if (active) CineIcon.LOCK else CineIcon.UNLOCK, fg, Modifier.size(20.dp))
        Text(shortLabel, color = fg, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
    }
}

@Composable
internal fun ModeDial(
    state: CameraUiState,
    binder: CaptureService.LocalBinder?,
    // Tapping the centred mode opens the full mode sheet (with the resolution) when set.
    onOpenSheet: (() -> Unit)? = null,
) {
    val selection = LocalModeSelection.current
    val displayedMode = selection?.displayed ?: state.selectedMode
    val selectMode: (CaptureMode) -> Unit = selection?.select ?: { mode -> binder?.selectMode(mode) }
    val modes = visibleCaptureModes(state.modeGates, displayedMode)
    val selectedIndex = modes.indexOf(displayedMode).coerceAtLeast(0)
    val haptics = LocalAppHaptics.current
    val dialDescription = stringResource(R.string.mode_dial_description, modeLabel(displayedMode))
    fun selectable(mode: CaptureMode): Boolean = CameraUiState.isModeSelectable(state.modeGates.getValue(mode))
    val labels = modes.map { modeLabel(it) }
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold)
    val density = LocalDensity.current
    // Every cell is as wide as the longest label, so no mode name is clipped ("Time-lapse",
    // "Ralentí" and the like in every language); the wheel shows fewer neighbours instead.
    val longestLabel = remember(labels, density) {
        with(density) { labels.maxOfOrNull { measurer.measure(it, labelStyle, softWrap = false).size.width }?.toDp() ?: 0.dp }
    }

    // A mode wheel: a linear carousel whose centred mode is the active one, marked only by amber
    // text and a dot (no frame, so it reads the same on phones, foldables and tablets). Neighbours
    // stay visible on both sides and fade out at the edges; a drag settles on the nearest mode and
    // tapping a visible mode selects it directly.
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .semantics { contentDescription = dialDescription },
    ) {
        val totalWidth = maxWidth
        val itemWidth = maxOf((totalWidth / 4.2f).coerceIn(76.dp, 140.dp), longestLabel + 16.dp).coerceAtMost(totalWidth)
        val rowHeight = 48.dp
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)
        val fling = rememberSnapFlingBehavior(listState, SnapPosition.Center)
        val focusedIndex by remember(listState, selectedIndex) {
            derivedStateOf {
                listState.layoutInfo.visibleItemsInfo
                    .minByOrNull { item ->
                        val viewportCenter =
                            (listState.layoutInfo.viewportStartOffset + listState.layoutInfo.viewportEndOffset) / 2
                        abs(item.offset + item.size / 2 - viewportCenter)
                    }
                    ?.index
                    ?: selectedIndex
            }
        }
        LaunchedEffect(selectedIndex, totalWidth) {
            if (!listState.isScrollInProgress && focusedIndex != selectedIndex) {
                if (listState.layoutInfo.totalItemsCount == 0) return@LaunchedEffect
                listState.animateScrollToItem(selectedIndex)
            }
        }
        // A detent tick for every mode the operator's drag or fling carries past the centre. A
        // programmatic scroll (selection from elsewhere, snap-back) is not a gesture and stays silent.
        val userDragging by listState.interactionSource.collectIsDraggedAsState()
        var gestureScroll by remember { mutableStateOf(false) }
        LaunchedEffect(userDragging) { if (userDragging) gestureScroll = true }
        LaunchedEffect(listState, selectedIndex, haptics) {
            snapshotFlow { focusedIndex }.drop(1).collect { if (gestureScroll) haptics.perform(HapticEvent.ModeTick) }
        }
        LaunchedEffect(listState.isScrollInProgress) {
            if (!listState.isScrollInProgress) gestureScroll = false
            if (!listState.isScrollInProgress && listState.layoutInfo.totalItemsCount > 0) {
                val idx = focusedIndex.coerceIn(0, modes.lastIndex)
                if (idx != selectedIndex && modes[idx] != displayedMode && selectable(modes[idx])) {
                    selectMode(modes[idx])
                } else if (idx != selectedIndex && !selectable(modes[idx])) {
                    haptics.perform(HapticEvent.Rejected)
                    listState.animateScrollToItem(selectedIndex)
                }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(rowHeight)
                // The edge fade says "more this way" without cutting a label in half.
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        Brush.horizontalGradient(
                            0f to Color.Transparent,
                            .12f to Color.Black,
                            .88f to Color.Black,
                            1f to Color.Transparent,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                },
        ) {
            LazyRow(
                state = listState,
                flingBehavior = fling,
                contentPadding = PaddingValues(horizontal = ((totalWidth - itemWidth) / 2).coerceAtLeast(0.dp)),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(modes.size) { index ->
                    val mode = modes[index]
                    val isSelected = index == focusedIndex
                    val enabled = selectable(mode)
                    Column(
                        Modifier
                            .width(itemWidth)
                            .height(rowHeight)
                            .semantics { selected = isSelected }
                            .clickable(enabled = enabled && (!isSelected || onOpenSheet != null)) {
                                if (isSelected) onOpenSheet?.invoke() else { haptics.perform(HapticEvent.ModeTick); selectMode(mode) }
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            labels[index],
                            color = when {
                                isSelected -> Amber
                                enabled -> Color.White
                                else -> Muted
                            },
                            fontSize = if (isSelected) 15.sp else 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            softWrap = false,
                        )
                        // The centred mode opens the sheet: ▾ says so, where a plain dot would not.
                        if (isSelected && onOpenSheet != null) CineGlyph(CineIcon.EXPAND, Amber, Modifier.padding(top = 2.dp).size(12.dp))
                        else Box(
                            Modifier
                                .padding(top = 3.dp)
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(if (isSelected) Amber else Color.Transparent),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun rememberCaptureAction(state: CameraUiState, binder: CaptureService.LocalBinder?, settings: CameraSettings, enabled: Boolean = true): () -> Unit {
    val currentEnabled by rememberUpdatedState(enabled)
    val context = LocalContext.current
    var showAudioChoice by remember { mutableStateOf(false) }
    val currentBinder by rememberUpdatedState(binder)
    val actionTicket = CaptureActionTicket(state.selfRecordingActive, state.captureActionGeneration)
    val currentActionTicket by rememberUpdatedState(actionTicket)
    var audioChoiceTicket by remember { mutableStateOf<CaptureActionTicket?>(null) }
    var audioChoiceOwner by remember { mutableStateOf<CaptureService.LocalBinder?>(null) }
    val audioAccess = rememberPermissionAccess(Manifest.permission.RECORD_AUDIO) { granted ->
        currentBinder?.refreshAudioCapabilities()
        val requestedTicket = audioChoiceTicket
        val owner = audioChoiceOwner
        audioChoiceTicket = null
        audioChoiceOwner = null
        if (granted && currentEnabled && requestedTicket != null && currentActionTicket == requestedTicket && owner != null && owner === currentBinder) {
            owner.captureForRole(requestedTicket, audioForThisTake = true)
        }
    }
    LaunchedEffect(enabled) {
        if (!enabled) { showAudioChoice = false; audioChoiceTicket = null; audioChoiceOwner = null }
    }
    if (showAudioChoice) {
        AlertDialog(
            onDismissRequest = { showAudioChoice = false; audioChoiceTicket = null; audioChoiceOwner = null },
            title = { Text(stringResource(R.string.audio_choice_title)) },
            text = {
                Text(stringResource(R.string.audio_choice_message) +
                    if (audioAccess.blocked) "\n\n" + stringResource(R.string.permission_blocked_explanation) else "")
            },
            confirmButton = {
                TextButton(onClick = {
                    showAudioChoice = false
                    if (audioAccess.blocked) { audioChoiceTicket = null; audioChoiceOwner = null }
                    audioAccess.request()
                }) { Text(stringResource(if (audioAccess.blocked) R.string.permission_open_settings else R.string.audio_choice_grant)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAudioChoice = false
                    val requestedTicket = audioChoiceTicket
                    val owner = audioChoiceOwner
                    audioChoiceTicket = null
                    audioChoiceOwner = null
                    if (currentEnabled && requestedTicket != null && owner != null && owner === binder) owner.captureForRole(requestedTicket, audioForThisTake = false)
                }) { Text(stringResource(R.string.audio_choice_without_audio)) }
            },
        )
    }
    val appHaptics = LocalAppHaptics.current
    return {
        if (!enabled) appHaptics.perform(HapticEvent.Rejected)
        if (enabled) {
            // Before the start call, so the vibration never reaches the first audio frames.
            appHaptics.perform(when {
                state.phase == CameraUiPhase.RECORDING || state.capturePreparationCancelable || state.countdownSeconds > 0 -> HapticEvent.RecordStop
                state.selectedMode.isStillMode() -> HapticEvent.Shutter
                else -> HapticEvent.RecordStart
            })
            val needsAudioChoice = !state.capturePreparationCancelable && state.countdownSeconds == 0 && state.phase != CameraUiPhase.RECORDING &&
                settings.captureWantsAudio(state.selectedMode) &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
            if (needsAudioChoice) {
                audioChoiceTicket = actionTicket
                audioChoiceOwner = binder
                showAudioChoice = true
            } else binder?.captureForRole(actionTicket)
        }
    }
}

/** Whether REC (or R) does anything now: a take can start or stop, or a pending capture can be cancelled. */
internal fun captureControlEnabled(state: CameraUiState): Boolean =
    !state.recordingFinalizing && (state.capturePreparationCancelable ||
        state.phase in setOf(CameraUiPhase.PREVIEWING, CameraUiPhase.SAVED, CameraUiPhase.RECORDING))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CaptureButton(
    state: CameraUiState,
    binder: CaptureService.LocalBinder?,
    settings: CameraSettings,
    size: androidx.compose.ui.unit.Dp,
    // The landscape deck has width to spare but no height: there the label sits beside the button.
    labelBeside: Boolean = false,
) {
    val onCapture = LocalOperatorActions.current?.capture ?: rememberCaptureAction(state, binder, settings)
    val recording = state.phase == CameraUiPhase.RECORDING
    val recordState = recordButtonState(state)
    val captureDescription = stringResource(
        when {
            state.phase == CameraUiPhase.CAPTURING && state.audioRetirementPending -> R.string.audio_retirement_cancel_capture
            state.transferRetirementPending -> R.string.webdav_transfer_cancel_capture
            state.whiteBalancePreparing -> R.string.pro_wb_cancel_preparation
            state.countdownSeconds > 0 -> R.string.self_cancel_timer
            recording -> R.string.stop_recording
            state.selectedMode.isStillMode() -> R.string.capture_photo
            else -> R.string.start_recording
        },
    )
    val stateLabel = recordState?.let { stringResource(it.label) }
    // A still still being saved spins too, so the shutter itself says why it does not answer.
    val stillBusy = state.selectedMode.isStillMode() && state.captureHold == CaptureHold.BUSY
    val busy = recordState == RecordButtonState.PREPARING || recordState == RecordButtonState.FINALIZING || stillBusy
    val label: @Composable () -> Unit = {
        if (stateLabel != null) {
            Text(
                stateLabel,
                color = recordState!!.color,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier.padding(top = if (labelBeside) 0.dp else 3.dp).testTag("record-state-label"),
            )
        }
    }
    // R does the same with a keyboard attached; the tooltip (long press, or hover) says so.
    val tooltip = keyHint(captureDescription, ShortcutAction.RECORD)
    val button: @Composable () -> Unit = { CaptureTooltip(tooltip, null) {
        Box(
            Modifier
                .size(size)
                .semantics {
                    contentDescription = captureDescription
                    if (stateLabel != null) stateDescription = stateLabel
                }
                .border(3.dp, if (recordState == RecordButtonState.UNAVAILABLE) Muted else Color.White, CircleShape)
                .clip(CircleShape)
                .clickable(enabled = captureControlEnabled(state)) {
                    onCapture()
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                when {
                    recordState == RecordButtonState.FINALIZING -> Modifier
                        .size(size * .34f)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Muted)
                    recording -> Modifier
                        .size(size * .34f)
                        .testTag("recording-stop-glyph")
                        .clip(RoundedCornerShape(3.dp))
                        .background(RecordRed)
                    else -> Modifier
                        .size(size - 10.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                recordState == RecordButtonState.UNAVAILABLE || recordState == RecordButtonState.PREPARING -> Color(0xFF3A4247)
                                stillBusy -> Amber.copy(alpha = .45f)
                                state.selectedMode.isStillMode() -> Amber
                                else -> RecordRed
                            },
                        )
                },
            )
            // Starting and closing a take both spin; the colour says which one is in progress.
            val reducedMotion = LocalReducedMotion.current
            AnimatedVisibility(
                visible = busy,
                modifier = Modifier.matchParentSize(),
                enter = if (reducedMotion) EnterTransition.None else fadeIn(),
                exit = if (reducedMotion) ExitTransition.None else fadeOut(),
            ) {
                CircularWavyProgressIndicator(
                    color = if (recordState == RecordButtonState.PREPARING) RecordRed else Amber,
                    trackColor = Color.Transparent,
                    modifier = Modifier.fillMaxSize().padding(2.dp).testTag("record-busy-indicator"),
                )
            }
        }
    } }
    if (labelBeside) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { label(); button() }
    else Column(horizontalAlignment = Alignment.CenterHorizontally) { button(); label() }
}

/**
 * What the record control can honestly claim, derived from the service's confirmed phase: a take
 * reads REC only once the service reports RECORDING, and a closing file is never shown as saved.
 * Still modes return null because their button has no recording lifecycle to explain.
 */
internal enum class RecordButtonState(val label: Int) {
    READY(R.string.rec_state_ready),
    PREPARING(R.string.rec_state_preparing),
    RECORDING(R.string.rec_state_recording),
    FINALIZING(R.string.rec_state_finalizing),
    SAVED(R.string.rec_state_saved),
    UNAVAILABLE(R.string.rec_state_unavailable),
}

internal val RecordButtonState.color: Color
    @Composable @ReadOnlyComposable get() = when (this) {
        RecordButtonState.READY -> Color.White
        RecordButtonState.PREPARING, RecordButtonState.FINALIZING -> Amber
        RecordButtonState.RECORDING -> RecordRed
        RecordButtonState.SAVED -> OkGreen
        RecordButtonState.UNAVAILABLE -> Muted
    }

internal fun recordButtonState(state: CameraUiState): RecordButtonState? {
    if (state.selectedMode.isStillMode()) return null
    return when {
        state.recordingFinalizing -> RecordButtonState.FINALIZING
        state.phase == CameraUiPhase.RECORDING -> RecordButtonState.RECORDING
        state.phase == CameraUiPhase.CAPTURING -> RecordButtonState.PREPARING
        state.phase == CameraUiPhase.SAVED -> RecordButtonState.SAVED
        state.phase == CameraUiPhase.PREVIEWING -> RecordButtonState.READY
        else -> RecordButtonState.UNAVAILABLE
    }
}

@Preview(name = "Mode wheel · portrait", widthDp = 320, heightDp = 120, showBackground = true, backgroundColor = 0xFF0B0D0E)
@Composable
private fun ModeWheelPortraitPreview() {
    Column(Modifier.background(Panel).padding(8.dp)) {
        ModeDial(
            CameraUiState(selectedMode = CaptureMode.PHOTO),
            binder = null,
        )
    }
}
