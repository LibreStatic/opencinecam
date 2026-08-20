/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

enum class ControlAvailability { SUPPORTED, UNSUPPORTED, UNKNOWN }

data class ManualControlAvailability(
    val exposure: ControlAvailability = ControlAvailability.UNKNOWN,
    val focus: ControlAvailability = ControlAvailability.UNKNOWN,
    val whiteBalance: ControlAvailability = ControlAvailability.UNKNOWN,
    val stabilization: ControlAvailability = ControlAvailability.UNKNOWN,
)

data class ManualControl(
    val key: String,
    val label: String,
    val requestedValue: String,
    val availability: ControlAvailability,
    val lockReason: String? = null,
) {
    val enabled: Boolean get() = availability == ControlAvailability.SUPPORTED
}

fun manualControls(
    requestedValues: Map<String, String>,
    availability: ManualControlAvailability,
): List<ManualControl> = listOf(
    ManualControl("exposure", "Exposure", requestedValues["exposure"] ?: "Auto", availability.exposure, availability.exposure.lockReason()),
    ManualControl("focus", "Focus", requestedValues["focus"] ?: "Continuous", availability.focus, availability.focus.lockReason()),
    ManualControl("whiteBalance", "White balance", requestedValues["whiteBalance"] ?: "Auto", availability.whiteBalance, availability.whiteBalance.lockReason()),
    ManualControl("stabilization", "Stabilization", requestedValues["stabilization"] ?: "Off", availability.stabilization, availability.stabilization.lockReason()),
)

private fun ControlAvailability.lockReason(): String? = when (this) {
    ControlAvailability.SUPPORTED -> null
    ControlAvailability.UNSUPPORTED -> "Unsupported by the advertised capability."
    ControlAvailability.UNKNOWN -> "Capability is unknown; control is locked until verified."
}

enum class RouteTruth { LOGICAL, PHYSICAL_CONFIRMED, PHYSICAL_UNKNOWN, MISMATCH }

data class HudDiscrepancy(
    val key: String,
    val requested: String,
    val reported: String,
    val explanation: String,
)

data class HardwareTruthHudState(
    val requested: Map<String, String> = emptyMap(),
    val reported: Map<String, String> = emptyMap(),
    val routeTruth: RouteTruth = RouteTruth.LOGICAL,
    val warnings: List<String> = emptyList(),
    val lockedControls: Set<String> = emptySet(),
    val discrepancyHistory: List<HudDiscrepancy> = emptyList(),
) {
    init {
        require(requested.keys.none { it.isBlank() } && reported.keys.none { it.isBlank() })
        require(warnings.none { it.isBlank() } && lockedControls.none { it.isBlank() })
    }

    fun withReport(key: String, value: String, explanation: String = "Requested and reported values differ."): HardwareTruthHudState {
        require(key.isNotBlank() && value.isNotBlank())
        val nextReported = reported + (key to value)
        val requestedValue = requested[key]
        val nextHistory = if (requestedValue != null && requestedValue != value) {
            discrepancyHistory + HudDiscrepancy(key, requestedValue, value, explanation)
        } else discrepancyHistory
        return copy(reported = nextReported, discrepancyHistory = nextHistory)
    }

    fun lock(key: String): HardwareTruthHudState = copy(lockedControls = lockedControls + key)
}
