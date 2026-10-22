/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import org.json.JSONArray
import org.json.JSONObject

const val HARDWARE_TRUTH_SCHEMA = "opencinecam.hardware-truth.v1"

/** One requested/reported mismatch that was open at the take start or opened during it; [open] is its state at the take end. */
data class TakeDiscrepancy(val field: String, val requested: String, val reported: String, val explanation: String, val open: Boolean)

/** The encoder of a take that recorded with a software codec (Adaptive); never hardware qualification evidence. */
data class TakeVideoEncoder(val codecName: String, val hardwareAccelerated: Boolean = false)

/** What the hardware reported differently from the request over one take. */
data class HardwareTruthTake(
    val route: RouteTruth,
    val requestedRoute: String?,
    val reportedRoute: String?,
    val discrepancies: List<TakeDiscrepancy>,
    val warnings: List<String>,
    val videoEncoder: TakeVideoEncoder? = null,
)

/**
 * The part of the HUD state that belongs to the take between [start] and [end]. The bounded history shifts as old
 * entries fall off, so the entries new in [end] are those after the longest tail of [start] that heads [end].
 * Null when nothing was discrepant and nothing was warned, so a clean take gets no extra sidecar.
 */
fun hardwareTruthTake(start: HardwareTruthHudState, end: HardwareTruthHudState, videoEncoder: TakeVideoEncoder? = null): HardwareTruthTake? {
    val overlap = (minOf(start.discrepancyHistory.size, end.discrepancyHistory.size) downTo 0)
        .first { k -> start.discrepancyHistory.takeLast(k) == end.discrepancyHistory.take(k) }
    val opened = end.discrepancyHistory.drop(overlap)
    val carried = end.discrepancyHistory.filter { it.key in start.openDiscrepancies }
        .groupBy { it.key }.mapNotNull { it.value.lastOrNull() }
    val discrepancies = (carried + opened).distinct().map { TakeDiscrepancy(it.key, it.requested, it.reported, it.explanation, it.key in end.openDiscrepancies) }
    val warnings = end.warnings.filterNot { it in start.warnings }
    if (discrepancies.isEmpty() && warnings.isEmpty() && videoEncoder == null) return null
    return HardwareTruthTake(end.routeTruth, end.requested[ROUTE_KEY], end.reported[ROUTE_KEY], discrepancies, warnings, videoEncoder)
}

/** The `hardwareTruth` object as plain maps and lists, so the shape is host-testable; [hardwareTruthJson] wraps it for the sidecar. */
fun hardwareTruthFields(take: HardwareTruthTake): Map<String, Any?> = mapOf(
    "route" to mapOf("truth" to take.route.name, "requested" to take.requestedRoute, "reported" to take.reportedRoute),
    "discrepancies" to take.discrepancies.map { mapOf("field" to it.field, "requested" to it.requested, "reported" to it.reported, "explanation" to it.explanation, "open" to it.open) },
    "warnings" to take.warnings,
) + (take.videoEncoder?.let { mapOf("videoEncoder" to mapOf("codecName" to it.codecName, "hardwareAccelerated" to it.hardwareAccelerated)) } ?: emptyMap())

fun hardwareTruthJson(take: HardwareTruthTake): JSONObject = JSONObject(hardwareTruthFields(take).mapValues { (_, v) -> toJson(v) })

private fun toJson(value: Any?): Any = when (value) {
    null -> JSONObject.NULL
    is Map<*, *> -> JSONObject(value.entries.associate { (k, v) -> k.toString() to toJson(v) })
    is List<*> -> JSONArray(value.map(::toJson))
    else -> value
}

/** Adds `hardwareTruth` to an existing sidecar, or makes a sidecar of its own; unchanged when [take] is null. */
fun withHardwareTruth(sidecar: String?, take: HardwareTruthTake?): String? =
    if (take == null) sidecar
    else (sidecar?.let(::JSONObject) ?: JSONObject().put("schema", HARDWARE_TRUTH_SCHEMA)).put("hardwareTruth", hardwareTruthJson(take)).toString(2)
