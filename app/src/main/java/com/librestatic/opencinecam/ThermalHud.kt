/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Risk of the platform throttling or shutting the device down, as shown on the HUD. */
enum class ThermalHudLevel { NORMAL, ELEVATED, HIGH, CRITICAL }

/**
 * [loadPercent] is the platform thermal headroom forecast (100 % = the point where Android
 * starts severe throttling), or null when the device does not report headroom.
 */
data class ThermalHudReading(val level: ThermalHudLevel, val loadPercent: Int?) {
    /** A percentage calmer than the platform status would understate the risk; show the level then. */
    val showsPercent: Boolean get() = loadPercent != null && headroomLevel(loadPercent / 100f) >= level
}

private fun headroomLevel(headroom: Float): ThermalHudLevel = when {
    headroom >= 1f -> ThermalHudLevel.HIGH
    headroom >= HEADROOM_WARNING -> ThermalHudLevel.ELEVATED
    else -> ThermalHudLevel.NORMAL
}

/** Headroom at or above this share of the severe threshold is flagged before the status changes. */
private const val HEADROOM_WARNING = 0.85f

/**
 * Maps [PowerManager] thermal status plus the optional headroom forecast to a HUD reading.
 * Status wins when it is worse than the forecast; headroom only raises NORMAL to ELEVATED early.
 */
fun thermalHudReading(status: Int, headroom: Float?): ThermalHudReading {
    val load = headroom?.takeIf { it.isFinite() && it >= 0f }
    val byStatus = when {
        status >= PowerManager.THERMAL_STATUS_CRITICAL -> ThermalHudLevel.CRITICAL
        status >= PowerManager.THERMAL_STATUS_SEVERE -> ThermalHudLevel.HIGH
        status >= PowerManager.THERMAL_STATUS_MODERATE -> ThermalHudLevel.ELEVATED
        else -> ThermalHudLevel.NORMAL
    }
    val level = if (byStatus == ThermalHudLevel.NORMAL && load != null && load >= HEADROOM_WARNING)
        ThermalHudLevel.ELEVATED else byStatus
    return ThermalHudReading(level, load?.let { (it * 100f).roundToInt().coerceAtMost(999) })
}

/** Always-on thermal load chip. Headroom is polled every 2 s (the platform asks for >= 1 s). */
@Composable
fun ThermalHudChip(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val power = remember(context) { context.getSystemService(Context.POWER_SERVICE) as? PowerManager }
    var status by remember { mutableIntStateOf(power?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE) }
    var headroom by remember { mutableStateOf<Float?>(null) }
    DisposableEffect(power) {
        val listener = PowerManager.OnThermalStatusChangedListener { status = it }
        power?.addThermalStatusListener(context.mainExecutor, listener)
        onDispose { power?.removeThermalStatusListener(listener) }
    }
    LaunchedEffect(power) {
        while (true) {
            if (power != null) {
                status = power.currentThermalStatus
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    headroom = power.getThermalHeadroom(10).takeIf { it.isFinite() }
                }
            }
            delay(2_000)
        }
    }
    val reading = thermalHudReading(status, headroom)
    val levelLabel = stringResource(when (reading.level) {
        ThermalHudLevel.NORMAL -> R.string.thermal_hud_normal
        ThermalHudLevel.ELEVATED -> R.string.thermal_hud_elevated
        ThermalHudLevel.HIGH -> R.string.thermal_hud_high
        ThermalHudLevel.CRITICAL -> R.string.thermal_hud_critical
    })
    val color = when (reading.level) {
        ThermalHudLevel.NORMAL -> Color(0xFF9CA6AA)
        ThermalHudLevel.ELEVATED -> Color(0xFFFFB300)
        ThermalHudLevel.HIGH, ThermalHudLevel.CRITICAL -> Color(0xFFE23A3A)
    }
    val value = if (reading.showsPercent) "${reading.loadPercent}%" else levelLabel
    val description = stringResource(R.string.thermal_hud_description, value, levelLabel)
    Text(
        "TEMP ${value.uppercase()}",
        color = color,
        fontSize = 10.sp,
        fontWeight = if (reading.level >= ThermalHudLevel.HIGH) FontWeight.Bold else FontWeight.Normal,
        maxLines = 1,
        modifier = modifier
            .background(Color(0xD914181A), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .semantics { contentDescription = description }
            .testTag("thermal-hud"),
    )
}
