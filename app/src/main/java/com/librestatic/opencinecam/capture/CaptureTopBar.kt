/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.librestatic.opencinecam.capture

import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.librestatic.opencinecam.service.CaptureService
import com.librestatic.opencinecam.storage.LocalMediaRepository
import com.librestatic.opencinecam.camera.OpenCineLogSourcePath
import com.librestatic.opencinecam.camera.Camera2LogProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.unit.Dp
import com.librestatic.opencinecam.CameraUiState
import com.librestatic.opencinecam.CaptureMode
import com.librestatic.opencinecam.R
import com.librestatic.opencinecam.fold.DisplayOperation
import com.librestatic.opencinecam.fold.DisplaySessionPhase
import com.librestatic.opencinecam.fold.FoldDisplayState
import com.librestatic.opencinecam.fold.LocalFoldDisplayCoordinator
import com.librestatic.opencinecam.settings.CameraSettings
import com.librestatic.opencinecam.settings.TorchSettings
import com.librestatic.opencinecam.subject.OutOfFrameHudChip
import com.librestatic.opencinecam.ui.CineGlyph
import com.librestatic.opencinecam.ui.CineIcon
import com.librestatic.opencinecam.usesLogGraph

@Composable
internal fun CaptureTopBar(
    state: CameraUiState,
    binder: CaptureService.LocalBinder?,
    onOpenMedia: () -> Unit,
    onOpenSettings: () -> Unit,
    settings: CameraSettings,
    onSettingsChanged: (CameraSettings) -> Unit,
    // The exterior display menu opens as a capture pane, beside the frame or as a sheet, like WB and focus.
    onOpenDisplays: () -> Unit,
    modifier: Modifier = Modifier,
    // The side-rail layout stacks the same actions in the start rail instead of across the top.
    vertical: Boolean = false,
    // Compact portrait and the side rails keep the gallery thumbnail beside the shutter, under the thumb.
    showThumbnail: Boolean = true,
    height: androidx.compose.ui.unit.Dp = STACKED_TOP_BAR_HEIGHT_DP.dp,
    // The side layouts show the status lines in their end column, not beside these actions.
    showStatus: Boolean = true,
) {
    val foldCoordinator = LocalFoldDisplayCoordinator.current
    val foldFallback = remember { MutableStateFlow(FoldDisplayState()) }
    val fold by (foldCoordinator?.states ?: foldFallback).collectAsStateWithLifecycle()
    var showLight by remember { mutableStateOf(false) }
    if (showLight) AlertDialog(
        onDismissRequest = { showLight = false },
        confirmButton = { TextButton(onClick = { showLight = false }) { Text(stringResource(android.R.string.ok)) } },
        text = { TorchSettings(state, settings, onSettingsChanged) },
        containerColor = Panel,
    )
    val switchDescription = stringResource(R.string.camera_switch)
    val actions: @Composable () -> Unit = {
        if (state.cameras.size > 1) {
            TopAction(CineIcon.SWITCH_CAMERA, switchDescription) {
                val index = state.cameras.indexOfFirst { it.cameraId == state.selectedCameraId }
                binder?.selectCamera(state.cameras[(index + 1).mod(state.cameras.size)].cameraId)
            }
        }
        if (fold.operation == DisplayOperation.TRANSFER && fold.phase == DisplaySessionPhase.ACTIVE) {
            TopAction(CineIcon.RETURN, stringResource(R.string.fold_return)) { foldCoordinator?.closeSession() }
        } else {
            TopAction(CineIcon.DISPLAYS, stringResource(R.string.fold_settings_title), onClick = onOpenDisplays)
        }
        // An F-key already mapped to the torch is the torch control; a second one up here was a duplicate.
        if (OperatorAction.TORCH !in settings.operation.buttons) {
            TopAction(CineIcon.TORCH, stringResource(R.string.flash_torch)) { showLight = true }
        }
        TopAction(CineIcon.SETTINGS, stringResource(R.string.settings_tab), onOpenSettings)
    }
    if (vertical) {
        Column(
            modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (showThumbnail) MediaThumbnailAction(state.lastSavedUri, onOpenMedia)
            if (showStatus) {
                CaptureStatusLine(state, Modifier.fillMaxWidth().padding(horizontal = 4.dp), maxLines = 3, textAlign = TextAlign.Center)
                ThermalHudChip()
                OutOfFrameHudChip(state)
            }
            androidx.compose.foundation.layout.FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) { actions() }
        }
    } else Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(Panel)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (showThumbnail) 6.dp else 2.dp),
    ) {
        if (showThumbnail) MediaThumbnailAction(state.lastSavedUri, onOpenMedia)
        else Spacer(Modifier.width(4.dp))
        if (showStatus) {
            CaptureStatusLine(state, Modifier.weight(1f))
            ThermalHudChip()
            OutOfFrameHudChip(state)
        } else Spacer(Modifier.weight(1f))
        actions()
    }
}

/** A round secondary action beside the shutter, sized to balance the gallery thumbnail. */
@Composable
internal fun TransportSideAction(icon: CineIcon, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(52.dp)
            .semantics { contentDescription = description }
            .clip(CircleShape)
            .border(1.dp, Color(0xFF41494C), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        CineGlyph(icon, Color.White, Modifier.size(24.dp))
    }
}

@Composable
internal fun TopAction(icon: CineIcon, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .semantics { contentDescription = description }
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        CineGlyph(icon, Color.White, Modifier.size(24.dp))
    }
}

@Composable
internal fun MediaThumbnailAction(lastSavedUri: String?, onClick: () -> Unit, size: androidx.compose.ui.unit.Dp = 48.dp) {
    val context = LocalContext.current
    val description = stringResource(R.string.media_tab)
    var thumbnail by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    // Reload after every published capture, not only once: the newest take belongs here.
    LaunchedEffect(lastSavedUri) {
        thumbnail = withContext(Dispatchers.IO) {
            runCatching { LocalMediaRepository(context.applicationContext).recent(1).firstOrNull()?.thumbnail }.getOrNull()
        }
    }
    Box(
        Modifier
            .size(size)
            .testTag("media-action")
            .semantics { contentDescription = description }
            .clip(RoundedCornerShape(if (size > 48.dp) 12.dp else 8.dp))
            .background(Color(0xFF303638))
            .border(1.dp, Color(0xFF41494C), RoundedCornerShape(if (size > 48.dp) 12.dp else 8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        thumbnail?.let {
            Image(it.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize())
        } ?: CineGlyph(CineIcon.MEDIA, Color.White, Modifier.size(24.dp))
    }
}

@Composable
internal fun LogSourceBadge(state: CameraUiState, settings: CameraSettings, modifier: Modifier = Modifier) {
    if (!state.selectedMode.usesLogGraph) return
    val profile = state.activeLogProfile
    val sourcePath = profile?.sourcePath
    val qualification = ocLogQualificationLabel(profile)?.let { " · $it" }.orEmpty()
    val text = when {
        state.selectedMode == CaptureMode.HLG -> "HLG · BT.2020 10-BIT · VIEW REC.709"
        sourcePath == OpenCineLogSourcePath.SDR_BT709_ISP && settings.logViewAssistEnabled ->
            "OCLOG2 HFR · ISP SDR · VIEW ASSIST$qualification"
        sourcePath == OpenCineLogSourcePath.SDR_BT709_ISP ->
            "OCLOG2 HFR · ISP-DERIVED SDR · MAIN10$qualification"
        settings.logViewAssistEnabled -> "OCLOG2 · HLG10 · VIEW ASSIST REC.709$qualification"
        else -> "OCLOG2 · HLG-DERIVED 10-BIT · FLAT$qualification"
    }
    Text(
        text,
        color = if (profile?.isVerified == true) VerifiedCyan else Color.White,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .testTag("log-source-badge")
            .background(Panel, RoundedCornerShape(5.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/** Same chip style as [LogSourceBadge]; shown only while CONTROL_LOW_LIGHT_BOOST_STATE reports ACTIVE. */
@Composable
internal fun LowLightBoostBadge(modifier: Modifier = Modifier) {
    Text(
        stringResource(R.string.hud_low_light_boost),
        color = Color.White,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        modifier = modifier
            .testTag("low-light-boost-badge")
            .background(Panel, RoundedCornerShape(5.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/** VERIFIED only for a profile tied to exact evidence; an unqualified profile makes no claim either way. */
internal fun ocLogQualificationLabel(profile: Camera2LogProfile?): String? =
    if (profile?.isVerified == true) "VERIFIED" else null
