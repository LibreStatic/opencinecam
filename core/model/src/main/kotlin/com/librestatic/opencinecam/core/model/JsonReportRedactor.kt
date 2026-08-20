/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.core.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Removes device/file identifiers before a report leaves the app. */
object JsonReportRedactor {
    private val sensitiveKeys = setOf(
        "buildFingerprint",
        "model",
        "serial",
        "deviceId",
        "uri",
        "fileName",
        "diagnostics",
    )

    fun redact(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.filterKeys { it !in sensitiveKeys }.mapValues { redact(it.value) })
        is JsonArray -> JsonArray(value.map(::redact))
        else -> value
    }
}
