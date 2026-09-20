/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import kotlinx.serialization.json.*
import org.junit.Assert.assertThrows
import org.junit.Test

class ProxySidecarReceiptTest {
    private val audio = "content://media/external_primary/audio/media/2"
    private val video = "content://media/external_primary/video/media/1"
    private val hash = "a".repeat(64)
    private fun receipt() = buildJsonObject {
        put("schema", "opencinecam.proxy-sidecar.v1"); put("audioUri", audio)
        put("sampleRateHz", 48000); put("channels", 2); put("sourceFrames", 48347)
        put("decodedPcmSha256", hash); put("audioStartUs", 125000)
        put("primingFrames", 2048); put("drainPaddingFrames", 4096)
        put("encoder", "observed.codec"); put("codecDataSha256", hash)
        putJsonObject("sourceSha256") {
            put(video, hash); put(audio, hash)
            put("content://media/external_primary/downloads/3", hash)
            put("content://media/external_primary/downloads/4", hash)
        }
    }
    private fun rejects(value: JsonObject) {
        assertThrows(Exception::class.java) { validateProxySidecarReceipt(value) }
    }
    @Test fun completeHistoricalEvidenceNeedsNoSourceIo() { validateProxySidecarReceipt(receipt()) }
    @Test fun unknownMissingAndMistypedFieldsReject() {
        val valid = receipt()
        rejects(JsonObject(valid + ("extra" to JsonPrimitive(1))))
        for (key in valid.keys) rejects(JsonObject(valid - key))
        for (key in listOf("sampleRateHz", "sourceFrames", "audioStartUs", "channels", "primingFrames", "drainPaddingFrames"))
            rejects(JsonObject(valid + (key to JsonPrimitive(valid.getValue(key).jsonPrimitive.content))))
    }
    @Test fun invalidGridAndCalibrationBoundsReject() {
        for ((key, value) in listOf("sourceFrames" to 0L, "audioStartUs" to -1L, "channels" to 3L,
            "sampleRateHz" to 0L, "primingFrames" to 8193L, "drainPaddingFrames" to 123L))
            rejects(JsonObject(receipt() + (key to JsonPrimitive(value))))
    }
    @Test fun exactFourRoleHashesAndSelectedAudioRequired() {
        val valid = receipt(); val hashes = valid.getValue("sourceSha256").jsonObject
        rejects(JsonObject(valid + ("sourceSha256" to JsonObject(hashes - video))))
        rejects(JsonObject(valid + ("audioUri" to JsonPrimitive("content://media/external_primary/audio/media/9"))))
        rejects(JsonObject(valid + ("sourceSha256" to JsonObject(hashes + (video to JsonPrimitive("bad"))))))
        val wrongRoles = JsonObject(hashes - video + ("content://media/external_primary/audio/media/9" to JsonPrimitive(hash)))
        rejects(JsonObject(valid + ("sourceSha256" to wrongRoles)))
    }
    private fun modern(type: String = "S24_LE") = JsonObject(receipt() + mapOf(
        "schema" to JsonPrimitive("opencinecam.proxy-sidecar.v2"),
        "decodedPcmEncoding" to JsonPrimitive(type),
        "exportPcm16Sha256" to JsonPrimitive(hash),
        "normalization" to JsonPrimitive("MEDIA3_1_11_PCM16_TRUNCATE_CLAMP_V1")))
    @Test fun normalizedEvidenceDisclosesSourceEncodingAndSeparateExportHash() {
        for (type in ProxyPcmSampleType.entries) validateProxySidecarReceipt(modern(type.name))
        validateProxySidecarReceipt(JsonObject(modern() + ("exportPcm16Sha256" to JsonPrimitive("b".repeat(64)))))
        rejects(JsonObject(modern("S16_LE") + ("exportPcm16Sha256" to JsonPrimitive("b".repeat(64)))))
    }
    @Test fun normalizationSchemaRequiresExactFieldsEncodingAndPolicy() {
        val valid = modern()
        for (key in listOf("decodedPcmEncoding","exportPcm16Sha256","normalization")) rejects(JsonObject(valid-key))
        rejects(modern("UNKNOWN"))
        rejects(JsonObject(valid + ("normalization" to JsonPrimitive("implicit"))))
        rejects(JsonObject(valid + ("exportPcm16Sha256" to JsonPrimitive("bad"))))
    }

}
