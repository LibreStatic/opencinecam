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

/** The key under which the physical camera id travels through [HardwareTruthHudState.requested] and `reported`. */
const val ROUTE_KEY = "physicalCameraId"

/** The physical lens serving the logical preview streams; informational, it follows zoom. */
const val ACTIVE_LENS_KEY = "activeLens"

/** Oldest discrepancies fall off, so a long session cannot grow the state (or the published UI state) without bound. */
const val MAX_DISCREPANCY_HISTORY = 20

/** Oldest warnings fall off too; the list is also deduplicated, so a repeating condition takes one slot. */
const val MAX_HUD_WARNINGS = 10

/** One requested/reported pair a control can be judged on; [withinTolerance] is the control's own verdict. */
data class ReadbackSample(
    val key: String,
    val requested: String,
    val reported: String,
    val withinTolerance: Boolean,
    val explanation: String = "Requested and reported values differ.",
)

/**
 * What the engine asked for against what the camera reported. No physical request is the logical
 * route; a physical request nobody reported on stays unconfirmed rather than assumed.
 */
fun routeTruthOf(requestedPhysicalId: String?, reportedPhysicalId: String?): RouteTruth = when {
    requestedPhysicalId == null -> RouteTruth.LOGICAL
    reportedPhysicalId == null -> RouteTruth.PHYSICAL_UNKNOWN
    requestedPhysicalId == reportedPhysicalId -> RouteTruth.PHYSICAL_CONFIRMED
    else -> RouteTruth.MISMATCH
}

data class HardwareTruthHudState(
    val requested: Map<String, String> = emptyMap(),
    val reported: Map<String, String> = emptyMap(),
    val routeTruth: RouteTruth = RouteTruth.LOGICAL,
    val warnings: List<String> = emptyList(),
    val lockedControls: Set<String> = emptySet(),
    val discrepancyHistory: List<HudDiscrepancy> = emptyList(),
    /** Keys whose requested and reported values differ right now; history grows only when a key enters this set. */
    val openDiscrepancies: Set<String> = emptySet(),
) {
    init {
        require(requested.keys.none { it.isBlank() } && reported.keys.none { it.isBlank() })
        require(warnings.none { it.isBlank() } && lockedControls.none { it.isBlank() })
    }

    /**
     * Records one reported value against its request. A mismatch adds a history entry only when it
     * opens (the key was not already differing), and a matching report closes it, so a value that
     * stays wrong for a thousand frames is one entry, not a thousand.
     */
    fun withReport(key: String, value: String, explanation: String = "Requested and reported values differ."): HardwareTruthHudState {
        require(key.isNotBlank() && value.isNotBlank())
        val requestedValue = requested[key]
        val mismatch = requestedValue != null && requestedValue != value
        val nextHistory = if (mismatch && key !in openDiscrepancies) {
            (discrepancyHistory + HudDiscrepancy(key, requestedValue.orEmpty(), value, explanation)).takeLast(MAX_DISCREPANCY_HISTORY)
        } else discrepancyHistory
        return copy(
            reported = reported + (key to value),
            discrepancyHistory = nextHistory,
            openDiscrepancies = if (mismatch) openDiscrepancies + key else openDiscrepancies - key,
        )
    }

    /**
     * Folds the current requested-versus-reported pairs into the state. A pair within its tolerance
     * counts as the requested value; a control with no pair any more (request cleared, no report)
     * closes its discrepancy.
     */
    fun withReadback(samples: List<ReadbackSample>): HardwareTruthHudState {
        var next = this
        samples.forEach { sample ->
            next = next.copy(requested = next.requested + (sample.key to sample.requested))
                .withReport(sample.key, if (sample.withinTolerance) sample.requested else sample.reported, sample.explanation)
        }
        val gone = openDiscrepancies - samples.map { it.key }.toSet()
        return next.copy(openDiscrepancies = next.openDiscrepancies - gone)
    }

    /** One auditor warning, bounded and deduplicated; the same text again changes nothing. */
    fun withWarning(message: String): HardwareTruthHudState {
        if (message.isBlank() || message in warnings) return this
        return copy(warnings = (warnings + message).takeLast(MAX_HUD_WARNINGS))
    }

    /**
     * Records the physical camera the engine routed the still to and the one the still's own result confirms. The
     * same pair twice changes nothing, so a stream of identical results adds no history; a mismatch
     * adds one entry.
     */
    fun withRoute(requestedPhysicalId: String?, reportedPhysicalId: String?): HardwareTruthHudState {
        val truth = routeTruthOf(requestedPhysicalId, reportedPhysicalId)
        val nextRequested = if (requestedPhysicalId == null) requested - ROUTE_KEY else requested + (ROUTE_KEY to requestedPhysicalId)
        val nextReported = if (reportedPhysicalId == null) reported - ROUTE_KEY else reported + (ROUTE_KEY to reportedPhysicalId)
        if (nextRequested == requested && nextReported == reported && truth == routeTruth) return this
        val entry = HudDiscrepancy(
            ROUTE_KEY, requestedPhysicalId.orEmpty(), reportedPhysicalId.orEmpty(),
            "The still's result names a different physical camera than the one it was routed to.",
        )
        val nextHistory = if (truth == RouteTruth.MISMATCH && entry != discrepancyHistory.lastOrNull()) {
            (discrepancyHistory + entry).takeLast(MAX_DISCREPANCY_HISTORY)
        } else discrepancyHistory
        return copy(requested = nextRequested, reported = nextReported, routeTruth = truth, discrepancyHistory = nextHistory)
    }

    /** The preview's active lens, reported only: it legitimately follows zoom, so it never makes a discrepancy. */
    fun withActiveLens(physicalId: String?): HardwareTruthHudState {
        val next = if (physicalId == null) reported - ACTIVE_LENS_KEY else reported + (ACTIVE_LENS_KEY to physicalId)
        return if (next == reported) this else copy(reported = next)
    }

    fun lock(key: String): HardwareTruthHudState = copy(lockedControls = lockedControls + key)
}
