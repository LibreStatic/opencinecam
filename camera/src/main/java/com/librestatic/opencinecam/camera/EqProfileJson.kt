/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** The `schema` value of an [EqProfile] document. */
const val EQ_PROFILE_SCHEMA = "opencinecam-eq-profile-v1"

/**
 * The stored form of an [EqProfile]: `{"schema", "id", "name", "bundled", "device", "curve"}`, the
 * curve in [toJsonObject]'s form. `device` is left out for a profile that has none.
 */
fun EqProfile.toJsonObject(): JsonObject = buildJsonObject {
    put("schema", EQ_PROFILE_SCHEMA)
    put("id", id)
    put("name", name)
    put("bundled", bundled)
    device?.let { put("device", it) }
    put("curve", curve.toJsonObject())
}

fun EqProfile.toJson(): String = toJsonObject().toString()

/**
 * Reads [toJson]'s form back, or null when [json] is not an equaliser profile: another schema, a
 * missing or invalid id or name, or no curve. The curve itself loads as [decodeEqCurve] reads it.
 */
fun decodeEqProfile(json: String): EqProfile? = runCatching {
    decodeEqProfile(Json.parseToJsonElement(json).jsonObject)
}.getOrNull()

fun decodeEqProfile(json: JsonObject): EqProfile? = runCatching {
    if (json.text("schema") != EQ_PROFILE_SCHEMA) return@runCatching null
    val id = json.text("id") ?: return@runCatching null
    val name = json.text("name") ?: return@runCatching null
    val curve = (json["curve"] as? JsonObject)?.let(::decodeEqCurve) ?: return@runCatching null
    val bundled = (json["bundled"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: false
    val device = when (val element = json["device"]) {
        null, JsonNull -> null
        else -> (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@runCatching null
    }
    EqProfile(id, name, curve, bundled, device)
}.getOrNull()

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
