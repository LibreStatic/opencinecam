/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.core.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Replaces device/file identifiers with [REDACTED] before a report leaves the app. The keys stay,
 * so a redacted report still validates against the schema (`buildFingerprint` is required).
 */
object JsonReportRedactor {
    const val REDACTED = "[redacted]"

    private val sensitiveKeys = setOf(
        "buildFingerprint",
        "fingerprint",
        "manufacturer",
        "model",
        "serial",
        "deviceId",
        "uri",
        "fileName",
        "diagnostics",
    )

    fun redact(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.mapValues { (key, nested) -> if (key in sensitiveKeys) JsonPrimitive(REDACTED) else redact(nested) })
        is JsonArray -> JsonArray(value.map(::redact))
        else -> value
    }
}
