/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import com.librestatic.opencinecam.camera.AacCodecCalibration
import kotlinx.serialization.json.*

/** Historical source evidence: catalog access must never require original source availability. */
internal fun proxySidecarReceipt(source: ProxySidecarSource, calibration: AacCodecCalibration): JsonObject = buildJsonObject {
    put("schema", "opencinecam.proxy-sidecar.v2")
    put("audioUri", source.selection.audio.uri)
    put("sampleRateHz", source.pcm.sampleRateHz); put("channels", source.pcm.channels)
    put("sourceFrames", source.pcm.frames); put("decodedPcmSha256", source.pcm.decodedSha256)
    put("decodedPcmEncoding", proxyPcmSampleType(source.pcm.pcmEncoding).name)
    put("exportPcm16Sha256", source.pcm.exportPcm16Sha256)
    put("normalization", "MEDIA3_1_11_PCM16_TRUNCATE_CLAMP_V1")
    put("audioStartUs", source.selection.timeline.audioStartUs)
    put("primingFrames", calibration.primingFrames); put("drainPaddingFrames", calibration.drainPaddingFrames)
    put("encoder", calibration.config.codecName); put("codecDataSha256", calibration.codecSpecificDataSha256)
    putJsonObject("sourceSha256") { source.sourceSha256.forEach { (uri, hash) -> put(uri, hash) } }
}.also(::validateProxySidecarReceipt)

internal fun validateProxySidecarReceipt(value: JsonObject) {
    val baseKeys = setOf("schema", "audioUri", "sampleRateHz", "channels", "sourceFrames",
        "decodedPcmSha256", "audioStartUs", "primingFrames", "drainPaddingFrames", "encoder", "codecDataSha256", "sourceSha256")
    fun text(key: String): String = value.getValue(key).jsonPrimitive.let { require(it.isString); it.content }
    fun number(key: String): Long = value.getValue(key).jsonPrimitive.let {
        require(!it.isString); val result = requireNotNull(it.longOrNull); require(it.content == result.toString()); result
    }
    val digest = Regex("[0-9a-f]{64}")
    val modern = text("schema") == "opencinecam.proxy-sidecar.v2"
    require(modern || text("schema") == "opencinecam.proxy-sidecar.v1")
    require(value.keys == baseKeys + if (modern) setOf("decodedPcmEncoding", "exportPcm16Sha256", "normalization") else emptySet())
    if (modern) {
        require(text("decodedPcmEncoding") in ProxyPcmSampleType.entries.map { it.name })
        require(digest.matches(text("exportPcm16Sha256")))
        require(text("normalization") == "MEDIA3_1_11_PCM16_TRUNCATE_CLAMP_V1")
        if (text("decodedPcmEncoding") == "S16_LE") require(text("decodedPcmSha256") == text("exportPcm16Sha256"))
    }
    require(mediaOriginalIdentity(text("audioUri"))?.first == LocalMediaKind.AUDIO)
    require(number("sampleRateHz") in 8000..192000 && number("channels") in 1..2 && number("sourceFrames") > 0)
    require(number("audioStartUs") >= 0 && number("primingFrames") in 0..8192 && number("drainPaddingFrames") in setOf(4096L, 8192L, 16384L))
    require(digest.matches(text("decodedPcmSha256")) && digest.matches(text("codecDataSha256")))
    require(text("encoder").isNotBlank() && text("encoder").length <= 256)
    val hashes = value.getValue("sourceSha256").jsonObject
    require(hashes.size == 4 && text("audioUri") in hashes)
    val collections = hashes.keys.map { requireNotNull(mediaDeleteIdentity(it)).collection }
    require(collections.count { it == MediaDeleteCollection.AUDIO } == 1 &&
        collections.count { it == MediaDeleteCollection.VIDEO } == 1 &&
        collections.count { it == MediaDeleteCollection.METADATA } == 2)
    for ((uri, hash) in hashes) {
        require(mediaDeleteIdentity(uri) != null && hash.jsonPrimitive.isString && digest.matches(hash.jsonPrimitive.content))
    }
}
