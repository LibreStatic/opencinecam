/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The stored form of an [EqCurve] (PLAN-074 decision 6): `{"enabled", "trimDb", "points": [{"type",
 * "frequencyHz", "gainDb", "q", "enabled"}]}`. The settings store and the take record use it.
 */
fun EqCurve.toJsonObject(): JsonObject = buildJsonObject {
    put("enabled", enabled)
    put("trimDb", trimDb)
    put("points", JsonArray(points.map { point ->
        buildJsonObject {
            put("type", point.type.name)
            put("frequencyHz", point.frequencyHz)
            put("gainDb", point.gainDb)
            put("q", point.q)
            put("enabled", point.enabled)
        }
    }))
}

fun EqCurve.toJson(): String = toJsonObject().toString()

/**
 * Reads [toJson]'s form back, or null when [json] is not an equaliser curve. Values out of range are
 * clamped into it, points of an unknown type are skipped and only the first [EqCurve.MAX_POINTS]
 * are kept, so a curve from a newer or edited file still loads.
 */
fun decodeEqCurve(json: String): EqCurve? = runCatching {
    decodeEqCurve(Json.parseToJsonElement(json).jsonObject)
}.getOrNull()

fun decodeEqCurve(json: JsonObject): EqCurve? = runCatching {
    val points = (json["points"] as? JsonArray).orEmpty().mapNotNull { element ->
        val point = element as? JsonObject ?: return@mapNotNull null
        val type = point["type"]?.jsonPrimitive?.contentOrNull
            ?.let { name -> EqFilterType.entries.firstOrNull { it.name == name } } ?: return@mapNotNull null
        EqPoint.coerced(
            type,
            point.number("frequencyHz") ?: return@mapNotNull null,
            point.number("gainDb") ?: 0.0,
            point.number("q") ?: DEFAULT_Q,
            point.flag("enabled") ?: true,
        )
    }.take(EqCurve.MAX_POINTS)
    val trim = json.number("trimDb") ?: 0.0
    EqCurve(
        points = points,
        trimDb = if (trim.isNaN()) 0.0 else trim.coerceIn(EqCurve.TRIM_RANGE_DB),
        enabled = json.flag("enabled") ?: true,
    )
}.getOrNull()

/** The Q a stored point without one gets: a Butterworth width. */
private const val DEFAULT_Q = 0.7071

private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull

private fun JsonObject.flag(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
