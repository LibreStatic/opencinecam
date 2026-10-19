/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonReportRedactorTest {
    @Test
    fun redactionReplacesSensitiveValuesRecursivelyAndKeepsTheKeys() {
        val report = Json.parseToJsonElement("""{"device":{"buildFingerprint":"secret","sdkInt":37},"nested":[{"uri":"content://private","ok":true}]}""")
        val redacted = JsonReportRedactor.redact(report).jsonObject
        val device = redacted["device"]!!.jsonObject
        assertEquals(JsonPrimitive(JsonReportRedactor.REDACTED), device["buildFingerprint"])
        assertFalse(redacted.toString().contains("secret"))
        assertFalse(redacted["nested"]!!.toString().contains("content://private"))
        assertTrue(redacted["nested"]!!.toString().contains("\"ok\":true"))
        assertEquals(JsonPrimitive(37), device["sdkInt"])
    }

    @Test
    fun fingerprintManufacturerAndStructuredValuesAreRedactedWholesale() {
        val report = Json.parseToJsonElement("""{"device":{"fingerprint":"f","manufacturer":"m","model":"x"},"diagnostics":{"a":[1,2]}}""")
        val redacted = JsonReportRedactor.redact(report).jsonObject
        assertEquals(JsonPrimitive(JsonReportRedactor.REDACTED), redacted["diagnostics"])
        listOf("fingerprint", "manufacturer", "model").forEach {
            assertEquals(JsonPrimitive(JsonReportRedactor.REDACTED), redacted["device"]!!.jsonObject[it])
        }
    }

    @Test
    fun redactedCapabilityReportStillDecodesAsADocument() {
        val raw = """{"schemaVersion":"1.0.0","reportId":"r","createdAt":"2026-01-01T00:00:00Z","device":{"buildFingerprint":"secret","sdkInt":37,"characteristicsDigest":"sha256:x"},"cameras":[],"codecs":[],"audio":{},"evidence":[]}"""
        val redacted = JsonReportRedactor.redact(Json.parseToJsonElement(raw))
        val document = Json.decodeFromJsonElement(CapabilityReportDocument.serializer(), redacted)
        assertEquals(JsonPrimitive(JsonReportRedactor.REDACTED), document.device["buildFingerprint"])
    }
}
