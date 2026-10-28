/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.librestatic.opencinecam.ui.theme.LocalCineColors
import com.librestatic.opencinecam.ui.viewfinder.chromePanel
import java.util.Locale
import kotlin.math.roundToInt

/** What a lock option is called, from where it sits on the logical zoom scale (PLAN-070 S3). */
internal enum class LensLockKind { ULTRA_WIDE, WIDE, TELE }

/** Below 0.9 is the ultra-wide, 0.9 to 1.5 the wide, above 1.5 the tele. */
internal fun lensLockKind(anchorRatio: Float): LensLockKind = when {
    anchorRatio < 0.9f -> LensLockKind.ULTRA_WIDE
    anchorRatio <= 1.5f -> LensLockKind.WIDE
    else -> LensLockKind.TELE
}

/** The anchor as the Settings row names it: up to two decimals, no trailing zeros ("0.55×", "1×", "3×"). */
internal fun lensLockRatioText(anchorRatio: Float): String {
    val hundredths = (anchorRatio * 100f).roundToInt()
    val text = when {
        hundredths % 100 == 0 -> (hundredths / 100).toString()
        hundredths % 10 == 0 -> String.format(Locale.US, "%.1f", hundredths / 100f)
        else -> String.format(Locale.US, "%.2f", hundredths / 100f)
    }
    return "$text×"
}

/**
 * The lens the LENS LOCK key locks to when it is tapped unlocked: the lens serving the preview
 * right now, else the longest lens whose anchor the zoom has reached, else the widest lens.
 * Null when there is nothing to lock to.
 */
internal fun lensLockToggleTarget(state: CameraUiState): String? {
    val options = state.lensLock.options
    if (options.isEmpty()) return null
    state.hardwareTruth.reported[ACTIVE_LENS_KEY]?.let { active ->
        options.firstOrNull { it.physicalId == active }?.let { return it.physicalId }
    }
    options.filter { it.anchorRatio <= state.zoomRatio + 0.005f }.maxByOrNull { it.anchorRatio }?.let { return it.physicalId }
    return options.minBy { it.anchorRatio }.physicalId
}

/** The key is offered when the camera has lenses to lock to, outside the high-speed sessions. */
internal fun lensLockKeyVisible(state: CameraUiState): Boolean =
    state.lensLock.available && !state.operatorHighSpeed()

/** Changing the lock restarts the session: never during a take or while the controls are held. */
internal fun lensLockChangeAllowed(state: CameraUiState): Boolean =
    !state.captureControlsLocked && !state.structuralSettingsFrozen

/** The zoom anchors that bound pinch, rocker and detents: none while locked, where every ratio is digital. */
internal fun zoomSectorAnchors(state: CameraUiState) =
    if (state.lensLock.locked) emptyList() else state.opticalAnchors

/** The notice the instrument stack shows for the lock, or null when it has nothing to say. */
internal fun lensLockNoticeText(lock: LensLockUiState): Int? = when {
    !lock.locked -> null
    lock.lensSwitchedWarning -> R.string.lens_lock_notice_switched
    lock.route == LensLockRoute.GPU_CROP -> R.string.lens_lock_notice_crop
    else -> null
}

@Composable
internal fun lensLockOptionLabel(option: LensLockOption): String {
    val ratio = lensLockRatioText(option.anchorRatio)
    return stringResource(when (lensLockKind(option.anchorRatio)) {
        LensLockKind.ULTRA_WIDE -> R.string.lens_lock_ultra_wide
        LensLockKind.WIDE -> R.string.lens_lock_wide
        LensLockKind.TELE -> R.string.lens_lock_tele
    }, ratio)
}

/** Placed like the "Scopes paused" notice: under the zoom, or on its own line under a row layout. */
@Composable
internal fun LensLockNotice(lock: LensLockUiState, modifier: Modifier = Modifier) {
    val text = lensLockNoticeText(lock) ?: return
    val warning = lock.lensSwitchedWarning
    val stripe = if (warning) LocalCineColors.current.pending else MaterialTheme.colorScheme.primary
    Row(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = .85f).chromePanel())
            .drawBehind { drawRect(stripe, size = Size(3.dp.toPx(), size.height)) }
            .padding(start = 9.dp, end = 10.dp, top = 5.dp, bottom = 5.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
            .testTag(if (warning) "lens-lock-switched" else "lens-lock-crop"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CineGlyph(if (warning) CineIcon.WARNING else CineIcon.LOCK, stripe, Modifier.size(14.dp))
        Text(stringResource(text), color = MaterialTheme.colorScheme.onSurface,
            fontSize = 12.sp, lineHeight = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Settings > Capture: Off, then one choice per lens. Selecting one asks the service to (un)lock. */
@Composable
internal fun LensLockSettings(state: CameraUiState, onSetLensLock: (String?) -> Unit) {
    val lock = state.lensLock
    val choices: List<String?> = listOf<String?>(null) + lock.options.map { it.physicalId }
    val off = stringResource(R.string.lens_lock_off)
    val labels = lock.options.associate { it.physicalId to lensLockOptionLabel(it) }
    SettingsOptionRow(
        title = stringResource(R.string.lens_lock_title),
        summary = stringResource(R.string.lens_lock_summary),
        choices = choices,
        selected = lock.lockedPhysicalId,
        label = { id -> if (id == null) off else labels[id] ?: id },
        tag = { id -> "lens-lock-${id ?: "off"}" },
        onSelect = onSetLensLock,
        modifier = Modifier.testTag("settings-lens-lock"),
        enabled = lensLockChangeAllowed(state),
    )
}
