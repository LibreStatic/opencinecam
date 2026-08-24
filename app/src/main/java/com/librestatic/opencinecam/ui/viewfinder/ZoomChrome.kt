/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.ui.viewfinder

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.librestatic.opencinecam.R
import com.librestatic.opencinecam.camera.ZoomAnchor
import java.util.Locale

private val ZoomPanel = Color(0xD914181A)
private val ZoomAccent = Color(0xFFFFB300)
private val ZoomHandle = Color(0xFF45D6E8)

/**
 * Optical-anchor buttons (0.5x / 1x / 2x ...) derived from the active descriptor anchors.
 * Tapping selects the nearest anchor. The active anchor is highlighted.
 */
@Composable
fun ZoomAnchorBar(
    anchors: List<ZoomAnchor>,
    activeRatio: Float,
    onSelect: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (anchors.isEmpty()) return
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(ZoomPanel)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        anchors.forEach { anchor ->
            val isActive = (activeRatio - anchor.ratio).let { it >= -0.05f && it <= 0.05f }
            val label = String.format(Locale.US, "%.1f", anchor.ratio)
            val description = stringResource(
                if (isActive) com.librestatic.opencinecam.R.string.zoom_anchor_active
                else com.librestatic.opencinecam.R.string.zoom_anchor,
                anchor.ratio,
            )
            Box(
                Modifier
                    .testTag("zoom-anchor-$label")
                    .semantics { contentDescription = description }
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .clip(CircleShape)
                    .background(if (isActive) ZoomAccent else Color.Transparent)
                    .border(1.dp, if (isActive) ZoomAccent else Color(0xFF4A5258), CircleShape)
                    .clickable { onSelect(anchor.ratio) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (isActive) Color.Black else Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/**
 * Lateral spring-return rocker. The handle rests at center; dragging up/down
 * increases/decreases zoom speed using a quadratic curve. On release the handle returns to
 * center but the zoom is kept. Changes are applied multiplicatively at up to 30 Hz.
 *
 * [onSpeed] receives the normalized offset in -1..1 (positive = zoom in) and is throttled
 * to ~30 Hz. [onRelease] is called when the drag ends or is cancelled.
 */
@Composable
fun ZoomRocker(
    onSpeed: (Float) -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragPx by remember { mutableFloatStateOf(0f) }
    var lastEmitMs by remember { mutableLongStateOf(0L) }
    var trackHeightPx by remember { mutableFloatStateOf(1f) }
    val description = stringResource(R.string.zoom_rocker)
    Box(
        modifier = modifier
            .testTag("zoom-rocker")
            .semantics { contentDescription = description }
            .onSizeChanged { trackHeightPx = it.height.toFloat().coerceAtLeast(1f) }
            .clip(RoundedCornerShape(8.dp))
            .background(ZoomPanel)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { dragPx = 0f },
                    onDragEnd = { dragPx = 0f; onRelease() },
                    onDragCancel = { dragPx = 0f; onRelease() },
                ) { change, drag ->
                    change.consume()
                    dragPx += drag.y
                    // Up is negative y in Compose; invert so up = zoom in (positive speed).
                    val normalized = (-dragPx / (trackHeightPx / 2f)).coerceIn(-1f, 1f)
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastEmitMs >= 33L) {
                        lastEmitMs = now
                        onSpeed(normalized)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 22.dp, height = 6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(if (dragPx != 0f) ZoomHandle else Color(0xFF9CA6AA)),
        )
    }
}
