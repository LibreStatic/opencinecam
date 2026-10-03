/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.librestatic.opencinecam.ui.theme.LocalCineColors
import com.librestatic.opencinecam.ui.viewfinder.chromePanel
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

/**
 * Whether the thermal chip is worth the space. A cool device shows nothing. A rising forecast
 * (headroom only) matters while recording, when throttling would cost the take. Once the platform
 * itself reports moderate heat or worse, the chip is always shown.
 */
fun thermalHudVisible(status: Int, reading: ThermalHudReading, recording: Boolean): Boolean = when {
    reading.level == ThermalHudLevel.NORMAL -> false
    status >= PowerManager.THERMAL_STATUS_MODERATE -> true
    else -> recording
}

/**
 * Thermal load chip, shown only when the heat matters (see [thermalHudVisible]); pass [recording]
 * while a take runs. Headroom is polled every 2 s (the platform asks for >= 1 s).
 */
@Composable
fun ThermalHudChip(modifier: Modifier = Modifier, recording: Boolean = false) {
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
    if (!thermalHudVisible(status, reading, recording)) return
    val levelLabel = stringResource(when (reading.level) {
        ThermalHudLevel.NORMAL -> R.string.thermal_hud_normal
        ThermalHudLevel.ELEVATED -> R.string.thermal_hud_elevated
        ThermalHudLevel.HIGH -> R.string.thermal_hud_high
        ThermalHudLevel.CRITICAL -> R.string.thermal_hud_critical
    })
    // Amber warns; the error colour is kept for heat that is about to stop the camera.
    val color = when (reading.level) {
        ThermalHudLevel.NORMAL -> MaterialTheme.colorScheme.onSurfaceVariant
        ThermalHudLevel.ELEVATED -> LocalCineColors.current.pending
        ThermalHudLevel.HIGH, ThermalHudLevel.CRITICAL -> MaterialTheme.colorScheme.error
    }
    val background = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.85f).chromePanel()
    val value = if (reading.showsPercent) "${reading.loadPercent}%" else levelLabel
    val description = stringResource(R.string.thermal_hud_description, value, levelLabel)
    Text(
        "TEMP ${value.uppercase()}",
        color = color,
        fontSize = 12.sp,
        lineHeight = 14.sp,
        fontWeight = if (reading.level >= ThermalHudLevel.HIGH) FontWeight.Bold else FontWeight.SemiBold,
        maxLines = 1,
        modifier = modifier
            .background(background, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp)
            .semantics { contentDescription = description }
            .testTag("thermal-hud"),
    )
}
