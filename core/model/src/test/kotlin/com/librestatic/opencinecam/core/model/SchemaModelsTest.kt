/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.core.model

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SchemaModelsTest {
    private val objectValue = buildJsonObject { put("value", "redacted") }

    private fun repositoryRoot(): Path {
        var current: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (current != null) {
            if (Files.isDirectory(current.resolve("docs/schemas/examples"))) return current
            current = current.parent
        }
        error("repository root not found")
    }

    @Test
    fun goldenExamplesDecodeWithMinorCompatibility() {
        val root = repositoryRoot()
        val capability = Files.readString(root.resolve("docs/schemas/examples/capability-report.example.json"))
        val sidecar = Files.readString(root.resolve("docs/schemas/examples/clip-sidecar.example.json"))
        assertEquals("123e4567-e89b-42d3-a456-426614174000", decodeCanonical<CapabilityReportDocument>(capability).reportId)
        assertEquals("123e4567-e89b-42d3-a456-426614174001", decodeCanonical<ClipSidecarDocument>(sidecar).recordingId)
        assertEquals(SchemaCompatibility.NEWER_MINOR, checkSchemaCompatibility("1.1.0"))
    }

    @Test
    fun allSixModelsRoundTrip() {
        val evidence = SchemaEvidence("Candidate", "pass", "2026-08-20T00:00:00Z")
        val jsonDocuments = listOf(
            encodeCanonical(CapabilityReportDocument("1.0.0", "report", "2026-08-20T00:00:00Z", objectValue, emptyList(), emptyList(), objectValue, listOf(evidence))),
            encodeCanonical(ClipSidecarDocument("1.0.0", "recording", "2026-08-20T00:00:00Z", objectValue, objectValue, emptyList(), listOf(evidence))),
            encodeCanonical(DeviceProfileDocument("1.0.0", "profile", "2026-08-20T00:00:00Z", objectValue, objectValue, emptyList())),
            encodeCanonical(QuirkDocument("1.0.0", "OCC-QUIRK-0001", objectValue, objectValue, listOf(evidence))),
            encodeCanonical(BenchmarkResultDocument("1.0.0", "benchmark", "2026-08-20T00:00:00Z", objectValue, 0, emptyList(), outcome = "pass")),
            encodeCanonical(ValidationResultDocument("1.0.0", "validation", "2026-08-20T00:00:00Z", objectValue, "protocol-1", emptyList(), "Candidate")),
        )
        jsonDocuments.forEach { json -> assertTrue(json.contains("\"schemaVersion\": \"1.0.0\"")) }
    }

    @Test(expected = UnsupportedSchemaMajor::class)
    fun majorVersionIsRejected() {
        checkSchemaCompatibility("2.0.0")
    }
}
