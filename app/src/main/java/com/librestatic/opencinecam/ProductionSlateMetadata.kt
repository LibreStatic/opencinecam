/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Frozen editorial intent only. Neither the take number nor free text is an artifact identity. */
fun productionSlateJson(value: ProductionSlateSettings): JsonObject = buildJsonObject {
    put("schemaVersion", 1)
    put("project", value.project)
    put("camera", value.camera)
    put("scene", value.scene)
    put("reel", value.reel)
    put("lens", value.lens)
    put("takeNumber", value.takeNumber)
    put("location", value.location.name)
    put("timeOfDay", value.timeOfDay.name)
    put("goodTake", value.goodTake)
    put("autoIncrementTake", value.autoIncrementTake)
}

/** Strict node decoder; unsupported or damaged metadata is not silently replaced by defaults.
 * The caller owns bounded parsing of its enclosing relationship document. This flat node itself
 * permits exactly eleven scalar fields, with textual bounds enforced by the shared DTO. */
fun parseProductionSlateJson(value: JsonObject): ProductionSlateSettings? = try {
    require(value.keys == setOf("schemaVersion", "project", "camera", "scene", "reel", "lens",
        "takeNumber", "location", "timeOfDay", "goodTake", "autoIncrementTake"))
    fun scalar(key: String): JsonPrimitive = value[key] as? JsonPrimitive ?: error("Expected scalar slate field")
    fun string(key: String): String = scalar(key).also { require(it.isString) }.content
    fun integer(key: String): Int {
        val field = scalar(key)
        require(!field.isString && field.content.matches(Regex("[1-9][0-9]{0,5}")))
        return requireNotNull(field.intOrNull)
    }
    fun boolean(key: String): Boolean = scalar(key).also { require(!it.isString) }.booleanOrNull
        ?: error("Expected boolean slate field")
    require(integer("schemaVersion") == 1)
    ProductionSlateSettings(
        project = string("project"), camera = string("camera"), scene = string("scene"),
        reel = string("reel"), lens = string("lens"), takeNumber = integer("takeNumber"),
        location = ProductionSlateLocation.valueOf(string("location")),
        timeOfDay = ProductionSlateTimeOfDay.valueOf(string("timeOfDay")),
        goodTake = boolean("goodTake"), autoIncrementTake = boolean("autoIncrementTake"),
    )
} catch (_: IllegalArgumentException) {
    null
} catch (_: IllegalStateException) {
    null
}
