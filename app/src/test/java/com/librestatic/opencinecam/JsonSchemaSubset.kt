/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The part of JSON Schema `docs/schemas` uses (type, const, enum, required, properties, items,
 * minItems, pattern, uuid and date-time formats, local `$ref`), enough to fail a test when a
 * shipped document stops matching its schema. Unlisted keywords are ignored, like additional properties.
 */
internal object JsonSchemaSubset {
    private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val dateTime = Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})")

    /** Every violation of [schema] by [value], as `path: message`; empty when it conforms. */
    fun violations(schema: JsonObject, value: JsonElement): List<String> =
        buildList { check(schema, schema, value, "$", this) }

    private fun check(root: JsonObject, schema: JsonObject, value: JsonElement, path: String, out: MutableList<String>) {
        (schema["\$ref"] as? JsonPrimitive)?.content?.let { ref ->
            var target: JsonElement = root
            ref.removePrefix("#/").split('/').forEach { target = (target as JsonObject)[it] ?: error("unresolved $ref") }
            check(root, target as JsonObject, value, path, out)
            return
        }
        schema["const"]?.let { if (it != value) out += "$path: expected constant $it" }
        (schema["enum"] as? JsonArray)?.let { if (value !in it) out += "$path: $value is not one of $it" }
        (schema["type"] as? JsonPrimitive)?.content?.let { type ->
            val ok = when (type) {
                "object" -> value is JsonObject
                "array" -> value is JsonArray
                "string" -> value is JsonPrimitive && value.isString
                "boolean" -> value is JsonPrimitive && !value.isString && value.content in setOf("true", "false")
                else -> true
            }
            if (!ok) {
                out += "$path: expected $type"
                return
            }
        }
        if (value is JsonPrimitive && value.isString) {
            (schema["pattern"] as? JsonPrimitive)?.content?.let { if (!Regex(it).containsMatchIn(value.content)) out += "$path: does not match $it" }
            when ((schema["format"] as? JsonPrimitive)?.content) {
                "uuid" -> if (!uuid.matches(value.content)) out += "$path: not a uuid"
                "date-time" -> if (!dateTime.matches(value.content)) out += "$path: not a date-time"
            }
        }
        if (value is JsonObject) {
            (schema["required"] as? JsonArray)?.forEach { name ->
                val key = (name as JsonPrimitive).content
                if (key !in value) out += "$path: missing required '$key'"
            }
            (schema["properties"] as? JsonObject)?.forEach { (name, sub) ->
                value[name]?.let { check(root, sub as JsonObject, it, "$path.$name", out) }
            }
        }
        if (value is JsonArray) {
            (schema["minItems"] as? JsonPrimitive)?.content?.toInt()?.let { if (value.size < it) out += "$path: fewer than $it items" }
            (schema["items"] as? JsonObject)?.let { items -> value.forEachIndexed { i, item -> check(root, items, item, "$path[$i]", out) } }
        }
    }
}
