/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.hardware.camera2.CameraCharacteristics
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.librestatic.opencinecam.camera.*

internal fun MonitorColor.composeColor(): Color = when (this) {
    MonitorColor.CYAN -> Color.Cyan; MonitorColor.YELLOW -> Color.Yellow
    MonitorColor.RED -> Color.Red; MonitorColor.GREEN -> Color.Green; MonitorColor.WHITE -> Color.White
}
internal fun FalseColorBand.composeColor(palette: FalseColorPalette): Color = when (this) {
    FalseColorBand.BLACK -> if (palette == FalseColorPalette.CLASSIC) Color(0xff6c42c1) else Color.Black
    FalseColorBand.SHADOW -> Color(0xff168cff)
    FalseColorBand.MID -> if (palette == FalseColorPalette.CLASSIC) Color(0xff40b66a) else Color.White
    FalseColorBand.HIGHLIGHT -> Color.Yellow
    FalseColorBand.CLIP -> Color.Red
}

/** Operator only: never mutates the encoder, subject output, crop or stored image. */
@Composable internal fun ProfessionalScopeImage(state: CameraUiState, options: MonitoringOptions,
    fresh: Boolean, displayDegrees: Int, sourceWidth: Int, sourceHeight: Int, squeezeFactor: Float, modifier: Modifier = Modifier) {
    Canvas(modifier.testTag("monitoring-image-guides")) {
        val scale = if (state.gpuViewfinder || state.selectedMode == CaptureMode.LOG)
            monitoringPreviewScale(sourceWidth, sourceHeight, size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1),
                state.descriptor?.sensorOrientation ?: 0, displayDegrees,
                state.descriptor?.lensFacing == CameraCharacteristics.LENS_FACING_FRONT, squeezeFactor)
            else 1f to 1f
        val frame = state.monitoringScopes
        if (options.falseColorEnabled && fresh && frame != null && frame.options == options) {
            fun point(x: Float, y: Float): Offset {
                val p = monitoringDisplayPoint(x, y, frame.domain, state.descriptor?.sensorOrientation ?: 0,
                    displayDegrees, state.descriptor?.lensFacing == CameraCharacteristics.LENS_FACING_FRONT)
                return Offset((.5f + (p.first - .5f) * scale.first) * size.width, (.5f + (p.second - .5f) * scale.second) * size.height)
            }
            frame.falseColorBands.forEachIndexed { index, band ->
                val a = point((index % 64) / 64f, (index / 64) / 36f)
                val b = point((index % 64 + 1) / 64f, (index / 64 + 1) / 36f)
                drawRect(band.composeColor(options.falseColorPalette).copy(alpha = options.opacityPercent / 100f),
                    Offset(minOf(a.x, b.x), minOf(a.y, b.y)), Size(kotlin.math.abs(b.x - a.x), kotlin.math.abs(b.y - a.y)))
            }
        }
        val guide = options.aspectGuide
        var w = size.width * scale.first
        var h = size.height * scale.second
        if (guide != MonitorAspectGuide.NONE) {
            val ratio = guide.width.toFloat() / guide.height
            w = minOf(w, h * ratio); h = w / ratio
            drawRect(Color.White, Offset((size.width - w) / 2, (size.height - h) / 2), Size(w, h), style = Stroke(1.dp.toPx()))
        }
        if (options.safeAreaEnabled) {
            w *= options.safeAreaPercent / 100f; h *= options.safeAreaPercent / 100f
            drawRect(Color.Yellow, Offset((size.width - w) / 2, (size.height - h) / 2), Size(w, h), style = Stroke(1.dp.toPx()))
        }
    }
}

@Composable internal fun ProfessionalScopesPanel(state: CameraUiState, options: MonitoringOptions,
    fresh: Boolean, modifier: Modifier = Modifier) {
    if (!options.waveformEnabled && !options.vectorscopeEnabled && !options.falseColorEnabled) return
    var enlarged by remember { mutableStateOf(false) }
    val frame = state.monitoringScopes
    val current = fresh && frame != null && frame.options == options
    BoxWithConstraints(modifier) {
        Column(Modifier.width(minOf(maxWidth, if (enlarged) 280.dp else 152.dp))
            .heightIn(max = maxHeight * .65f).background(Color.Black.copy(alpha = .8f))
            .verticalScroll(rememberScrollState()).padding(6.dp).testTag("monitoring-panel")) {
            TextButton(onClick = { enlarged = !enlarged }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .testTag("monitoring-enlarge")) {
                Text(stringResource(if (enlarged) R.string.scope_reduce else R.string.scope_enlarge))
            }
            Text(stringResource(if (current) R.string.scope_live else R.string.scope_suspended),
                color = if (current) Color.Green else Color.Yellow,
                style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag("monitoring-freshness"))
            val domain = when (frame?.domain) {
                MonitoringSignalDomain.ISP_YUV_ESTIMATED_SDR -> R.string.scope_domain_isp
                MonitoringSignalDomain.SDR_BT709_CODE -> R.string.scope_domain_sdr
                MonitoringSignalDomain.OCLOG2_CODE -> R.string.scope_domain_log
                null -> R.string.scope_domain_waiting
            }
            Text(stringResource(domain), color = Color.White, style = MaterialTheme.typography.labelSmall)
            Text(stringResource(R.string.scope_rate, options.refreshHz,
                if (current && state.analysisIntervalMs > 0) (1000f / state.analysisIntervalMs).coerceAtMost(1000f) else 0f),
                color = Color.White, style = MaterialTheme.typography.labelSmall)
            Text(stringResource(R.string.scope_pre_assist), color = Color.White, style = MaterialTheme.typography.labelSmall)
            if (current && frame != null) {
                if (options.waveformEnabled && frame.waveformDensity.isNotEmpty()) {
                    Text(stringResource(R.string.scope_waveform_axes), color = Color.White, style = MaterialTheme.typography.labelSmall)
                    ScopeDensity(frame.waveformDensity, options, false, enlarged)
                }
                if (options.vectorscopeEnabled && frame.vectorscopeCounts.isNotEmpty()) {
                    Text(stringResource(R.string.scope_vector_axes), color = Color.White, style = MaterialTheme.typography.labelSmall)
                    ScopeDensity(frame.vectorscopeCounts, options, true, enlarged)
                }
                if (options.falseColorEnabled) {
                    val bandNames = listOf(R.string.scope_black, R.string.scope_shadow, R.string.scope_mid, R.string.scope_highlight, R.string.scope_clip)
                    val bands = listOf(FalseColorBand.BLACK, FalseColorBand.SHADOW, FalseColorBand.MID, FalseColorBand.HIGHLIGHT, FalseColorBand.CLIP)
                    val cuts = listOf("≤${options.falseColorBlackPercent}", "≤${options.falseColorShadowPercent}", "<${options.falseColorHighlightPercent}", "<${options.falseColorClipPercent}", "≥${options.falseColorClipPercent}")
                    bands.forEachIndexed { index, band ->
                        Row { Box(Modifier.size(12.dp).background(band.composeColor(options.falseColorPalette)))
                            Text(" ${stringResource(bandNames[index])} ${cuts[index]}%", color = Color.White, style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
        }
    }
}

@Composable private fun ScopeDensity(counts: List<Int>, options: MonitoringOptions, vector: Boolean, enlarged: Boolean) {
    Canvas(Modifier.fillMaxWidth().height(if (enlarged) 150.dp else 80.dp)
        .testTag(if (vector) "monitoring-vector-graph" else "monitoring-waveform-graph")) {
        drawRect(Color.Black)
        for (fraction in listOf(0f, .25f, .5f, .75f, 1f)) {
            drawLine(Color.DarkGray, Offset(0f, size.height * fraction), Offset(size.width, size.height * fraction))
        }
        if (vector) {
            drawLine(Color.DarkGray, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height))
            drawOval(Color.DarkGray, style = Stroke(1.dp.toPx()))
        }
        val peak = (counts.maxOrNull() ?: 1).coerceAtLeast(1)
        counts.forEachIndexed { index, count ->
            if (count > 0) drawRect(options.lumaColor.composeColor().copy(alpha =
                (kotlin.math.sqrt(count.toFloat() / peak) * options.opacityPercent / 100f).coerceIn(0f, 1f)),
                Offset((index % 64) * size.width / 64, (index / 64) * size.height / 64), Size(size.width / 64, size.height / 64))
        }
    }
}
