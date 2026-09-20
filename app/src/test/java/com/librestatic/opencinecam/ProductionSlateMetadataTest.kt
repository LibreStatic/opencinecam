/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProductionSlateMetadataTest {
    @Test fun defaultsAndEveryEnumRoundTripWithoutDroppingOriginalIntent() {
        for (location in ProductionSlateLocation.entries) for (time in ProductionSlateTimeOfDay.entries) {
            val value = ProductionSlateSettings(project = "Film 📷", camera = "A", scene = "12B", reel = "R1",
                lens = "35mm", takeNumber = 999999, location = location, timeOfDay = time,
                goodTake = true, autoIncrementTake = true)
            assertEquals(value, parseProductionSlateJson(productionSlateJson(value)))
        }
        assertEquals(ProductionSlateSettings(), parseProductionSlateJson(productionSlateJson(ProductionSlateSettings())))
    }

    @Test fun serializationEscapesEditorialTextWithoutMakingItAPathOrIdentity() {
        val value = ProductionSlateSettings(project = "../Project/\"A\"\\B", lens = "é".repeat(128))
        val encoded = productionSlateJson(value).toString()
        assertEquals(value, parseProductionSlateJson(Json.parseToJsonElement(encoded).jsonObject))
        assertFalse(productionSlateJson(value).containsKey("uri"))
        assertFalse(productionSlateJson(value).containsKey("bundleId"))
    }

    @Test fun missingUnknownFutureOrNonObjectFieldsRejectInsteadOfDefaulting() {
        val original = productionSlateJson(ProductionSlateSettings())
        for (key in original.keys) assertNull(parseProductionSlateJson(JsonObject(original - key)))
        assertNull(parseProductionSlateJson(JsonObject(original + ("extra" to JsonPrimitive(1)))))
        for (bad in listOf(JsonPrimitive(2), JsonPrimitive("1"), JsonNull, JsonArray(emptyList()), JsonObject(emptyMap()))) {
            assertNull(parseProductionSlateJson(JsonObject(original + ("schemaVersion" to bad))))
        }
    }

    @Test fun exactNumericAndBooleanTypesAreRequired() {
        val original = productionSlateJson(ProductionSlateSettings())
        for (raw in listOf("0", "-1", "1000000", "9007199254740993", "1.0", "1e0", "true", "null", "\"1\"")) {
            assertNull(parseProductionSlateJson(JsonObject(original + ("takeNumber" to Json.parseToJsonElement(raw)))))
        }
        for (key in listOf("goodTake", "autoIncrementTake")) for (raw in listOf("1", "0", "null", "\"true\"", "{}", "[]")) {
            assertNull(parseProductionSlateJson(JsonObject(original + (key to Json.parseToJsonElement(raw)))))
        }
    }

    @Test fun textAndEnumBoundsUseTheSameDtoValidation() {
        val original = productionSlateJson(ProductionSlateSettings())
        for (key in listOf("project", "camera", "scene", "reel", "lens")) {
            for (bad in listOf(JsonPrimitive("a".repeat(129)), JsonPrimitive("\n"), JsonPrimitive("\uD800"),
                JsonPrimitive("\u202E"), JsonPrimitive(12), JsonNull, JsonObject(emptyMap()))) {
                assertNull(parseProductionSlateJson(JsonObject(original + (key to bad))))
            }
        }
        assertNull(parseProductionSlateJson(JsonObject(original + ("location" to JsonPrimitive("STUDIO")))))
        assertNull(parseProductionSlateJson(JsonObject(original + ("timeOfDay" to JsonPrimitive("day")))))
    }

    @Test fun encodedSnapshotDoesNotFollowLaterSettingsCopy() {
        val original = ProductionSlateSettings(project = "Original", takeNumber = 6)
        val snapshot = productionSlateJson(original)
        val changed = original.copy(project = "Changed", takeNumber = 7)
        assertEquals(original, parseProductionSlateJson(snapshot))
        assertNotEquals(changed, parseProductionSlateJson(snapshot))
    }
}
