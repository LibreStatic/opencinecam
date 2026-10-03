/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.librestatic.opencinecam.ui.viewfinder.chromePanel
import kotlinx.coroutines.launch

/**
 * The five exposure slots under the viewfinder. Each mode has the same five places, so the
 * operator's thumb learns where shutter or focus is once: a slot the camera cannot drive stays in
 * place, greyed, and says why when tapped, instead of disappearing and shifting the rest.
 */
internal enum class CaptureSlot { FPS, INTERVAL, SHUTTER, ISO, EV, WB, FOCUS }

/** Video and LOG: FPS · SHUTTER · ISO · WB · FOCUS. Stills: SHUTTER · ISO · EV · WB · FOCUS. Time-lapse swaps FPS for the interval. */
internal fun captureSlots(mode: CaptureMode): List<CaptureSlot> = when {
    mode == CaptureMode.TIME_LAPSE ->
        listOf(CaptureSlot.INTERVAL, CaptureSlot.SHUTTER, CaptureSlot.ISO, CaptureSlot.WB, CaptureSlot.FOCUS)
    mode in CameraUiState.frameRateModes || mode == CaptureMode.SLOW_MOTION ->
        listOf(CaptureSlot.FPS, CaptureSlot.SHUTTER, CaptureSlot.ISO, CaptureSlot.WB, CaptureSlot.FOCUS)
    else -> listOf(CaptureSlot.SHUTTER, CaptureSlot.ISO, CaptureSlot.EV, CaptureSlot.WB, CaptureSlot.FOCUS)
}

/** Why a slot is greyed out. */
internal enum class SlotUnavailableReason { NOT_READY, HIGH_SPEED, NO_MANUAL_EXPOSURE, NO_EV }

/**
 * Whether a slot can be driven now. A high-speed session runs exposure, white balance and focus
 * itself, so only its rate stays adjustable.
 */
internal fun captureSlotUnavailableReason(
    slot: CaptureSlot,
    cameraReady: Boolean,
    highSpeed: Boolean,
    manualIso: Boolean,
    manualShutter: Boolean,
    evSupported: Boolean,
): SlotUnavailableReason? = when {
    !cameraReady -> SlotUnavailableReason.NOT_READY
    highSpeed && slot != CaptureSlot.FPS && slot != CaptureSlot.INTERVAL -> SlotUnavailableReason.HIGH_SPEED
    slot == CaptureSlot.ISO && !manualIso -> SlotUnavailableReason.NO_MANUAL_EXPOSURE
    slot == CaptureSlot.SHUTTER && !manualShutter -> SlotUnavailableReason.NO_MANUAL_EXPOSURE
    slot == CaptureSlot.EV && !evSupported -> SlotUnavailableReason.NO_EV
    else -> null
}

/** The short cell title. */
internal fun CaptureSlot.labelRes(): Int = when (this) {
    CaptureSlot.FPS -> R.string.capture_slot_fps
    CaptureSlot.INTERVAL -> R.string.capture_slot_interval
    CaptureSlot.SHUTTER -> R.string.capture_slot_shutter
    CaptureSlot.ISO -> R.string.capture_slot_iso
    CaptureSlot.EV -> R.string.capture_slot_ev
    CaptureSlot.WB -> R.string.capture_slot_wb
    CaptureSlot.FOCUS -> R.string.capture_slot_focus
}

/** The full name, for screen readers and tooltips. */
internal fun CaptureSlot.nameRes(): Int = when (this) {
    CaptureSlot.FPS -> R.string.capture_slot_fps_name
    CaptureSlot.INTERVAL -> R.string.capture_slot_interval_name
    CaptureSlot.SHUTTER -> R.string.capture_slot_shutter_name
    CaptureSlot.ISO -> R.string.capture_slot_iso_name
    CaptureSlot.EV -> R.string.capture_slot_ev_name
    CaptureSlot.WB -> R.string.capture_slot_wb_name
    CaptureSlot.FOCUS -> R.string.capture_slot_focus_name
}

internal fun SlotUnavailableReason.textRes(): Int = when (this) {
    SlotUnavailableReason.NOT_READY -> R.string.capture_slot_reason_not_ready
    SlotUnavailableReason.HIGH_SPEED -> R.string.capture_slot_reason_high_speed
    SlotUnavailableReason.NO_MANUAL_EXPOSURE -> R.string.capture_slot_reason_no_manual
    SlotUnavailableReason.NO_EV -> R.string.capture_slot_reason_no_ev
}

/**
 * One slot as drawn: [label] is the short cell title ("ISO"), [name] the spoken and tooltip name,
 * [detail] an optional second value (the focus distance, "HS" in a high-speed session).
 */
internal data class CaptureSlotModel(
    val slot: CaptureSlot,
    val label: String,
    val name: String,
    val value: String,
    val detail: String? = null,
    val unavailableReason: String? = null,
    val shortcut: ShortcutAction? = null,
)

private val SlotShape = RoundedCornerShape(10.dp)

/** The slots across the deck of the compact and stacked layouts: five equal cells, never scrolling. */
@Composable
internal fun CaptureSlotStrip(
    slots: List<CaptureSlotModel>,
    active: CaptureSlot?,
    onSelect: (CaptureSlot) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(SlotShape)
            .background(colors.surfaceContainerHigh.chromePanel())
            .border(1.dp, colors.outlineVariant, SlotShape)
            .testTag("capture-slot-strip"),
    ) {
        slots.forEachIndexed { index, model ->
            if (index > 0) Box(Modifier.width(1.dp).fillMaxHeight().padding(vertical = 10.dp).background(colors.outlineVariant))
            SlotCell(model, model.slot == active, onSelect, Modifier.weight(1f).height(56.dp), stacked = true)
        }
    }
}

/** The slots as one column, for the end column of the side-rail layout. Scrolls only if the window is shorter than five cells. */
@Composable
internal fun CaptureSlotColumn(
    slots: List<CaptureSlotModel>,
    active: CaptureSlot?,
    onSelect: (CaptureSlot) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier
            .clip(SlotShape)
            .background(colors.surfaceContainerHigh.chromePanel())
            .border(1.dp, colors.outlineVariant, SlotShape)
            .verticalScroll(rememberScrollState())
            .testTag("capture-slot-column"),
    ) {
        slots.forEachIndexed { index, model ->
            if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).padding(horizontal = 10.dp).background(colors.outlineVariant))
            SlotCell(model, model.slot == active, onSelect, Modifier.fillMaxWidth().height(56.dp), stacked = true)
        }
    }
}

/** The slots as labelled rows, for the inspector: the name on the left, the value and its detail on the right. */
@Composable
internal fun CaptureSlotRows(
    slots: List<CaptureSlotModel>,
    active: CaptureSlot?,
    onSelect: (CaptureSlot) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier
            .fillMaxWidth()
            .clip(SlotShape)
            .background(colors.surfaceContainerHigh.chromePanel())
            .border(1.dp, colors.outlineVariant, SlotShape)
            .testTag("capture-slot-rows"),
    ) {
        slots.forEachIndexed { index, model ->
            if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).padding(horizontal = 12.dp).background(colors.outlineVariant))
            SlotCell(model, model.slot == active, onSelect, Modifier.fillMaxWidth().heightIn(min = 56.dp), stacked = false)
        }
    }
}

/**
 * One slot. Its label and value stay plain text so the cell reads as "ISO, AUTO"; the active slot
 * is marked by amber, not by a selected state, which belongs to the mode wheel alone. A greyed slot
 * still answers a tap, with a tooltip saying why; a long press shows the name and its shortcut.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun SlotCell(
    model: CaptureSlotModel,
    active: Boolean,
    onSelect: (CaptureSlot) -> Unit,
    modifier: Modifier,
    stacked: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    val tooltipState = rememberTooltipState()
    val scope = rememberCoroutineScope()
    val available = model.unavailableReason == null
    val title = keyHint(model.name, model.shortcut)
    val labelColor = if (available) colors.onSurfaceVariant else colors.onSurfaceVariant.copy(alpha = 0.6f)
    val valueColor = when {
        !available -> colors.onSurface.copy(alpha = 0.45f)
        active -> colors.primary
        else -> colors.onSurface
    }
    CaptureTooltip(title, model.unavailableReason, modifier, tooltipState) {
        val cell = Modifier
            .combinedClickable(
                role = Role.Button,
                onClick = { if (available) onSelect(model.slot) else scope.launch { tooltipState.show() } },
                onLongClick = { scope.launch { tooltipState.show() } },
            )
            .semantics { model.unavailableReason?.let { stateDescription = it } }
            .testTag("capture-slot-${model.slot.name.lowercase()}")
        if (stacked) Column(
            cell.fillMaxSize().padding(horizontal = 2.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            SlotLabel(model.label + if (model.detail != null && model.slot != CaptureSlot.FOCUS) " · ${model.detail}" else "", labelColor)
            SlotValue(model.value, valueColor, TextAlign.Center)
            if (active) Box(Modifier.padding(top = 2.dp).width(18.dp).height(2.dp).background(colors.primary))
        } else Row(
            cell.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (active) Box(Modifier.width(3.dp).height(24.dp).background(colors.primary))
            Column(Modifier.weight(1f)) {
                Text(model.name, color = colors.onSurface, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // The strip's short code helps only where it differs from the name: "WB", not "SHUTTER".
                if (!model.label.equals(model.name, ignoreCase = true)) SlotLabel(model.label, labelColor, TextAlign.Start)
            }
            Column(horizontalAlignment = Alignment.End) {
                SlotValue(model.value, valueColor, TextAlign.End)
                model.detail?.let { Text(it, color = labelColor, fontSize = 12.sp, maxLines = 1) }
            }
        }
    }
}

@Composable
private fun SlotLabel(text: String, color: Color, align: TextAlign = TextAlign.Center) {
    BasicText(
        text,
        style = TextStyle(color = color, fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.4.sp, textAlign = align),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = 12.sp),
    )
}

/** Values stay between 14 and 16 sp: the size steps down before a value such as 1/8000 is cut. */
@Composable
private fun SlotValue(text: String, color: Color, align: TextAlign) {
    BasicText(
        text,
        style = TextStyle(color = color, fontSize = 16.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold, textAlign = align),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        autoSize = TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = 16.sp),
    )
}
