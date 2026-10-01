/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import android.content.ContentResolver
import androidx.core.net.toUri
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val OCLOG_SIDECAR_SCHEMA = "opencinecam-oclog-sidecar-v2"
private const val OCLOG_SIDECAR_BYTES = 512 * 1024

/**
 * The review-relevant part of an OCLog2 sidecar. The container leaves the transfer unspecified,
 * so only this declaration says the samples are OCLog2 codes rather than SDR or HLG video.
 */
data class OcLogClip(val curve: String, val version: String?, val gamut: String, val fullRange: Boolean, val codecName: String?, val profile: String?) {
    val signal: PreciseLogSignal get() = PreciseLogSignal(fullRange)
}

/** Null unless the document is an OCLog2 sidecar that names exactly this video URI. */
fun parseOcLogSidecar(text: String, videoUri: String): OcLogClip? = runCatching {
    val root = Json.parseToJsonElement(text) as? JsonObject ?: return null
    fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (root.string("schema") != OCLOG_SIDECAR_SCHEMA || root.string("videoUri") != videoUri) return null
    val transform = root["transform"] as? JsonObject ?: return null
    val encoding = root["encoding"] as? JsonObject ?: return null
    if (transform.string("curve") != "OCLog2" || transform.string("gamut") != "BT.2020") return null
    val range = when (encoding.string("range")) { "full" -> true; "limited" -> false; else -> return null }
    OcLogClip("OCLog2", transform.string("version"), "BT.2020", range, encoding.string("codecName"), encoding.string("profile"))
}.getOrNull()

/** Reads the take's JSON metadata on the caller's (non-main) thread and returns the clip's OCLog2 declaration. */
fun readOcLogClip(resolver: ContentResolver, take: LocalMediaTake, video: LocalMediaArtifact): OcLogClip? {
    if (!video.mimeType.startsWith("video/")) return null
    for (document in take.metadata) {
        if (document.mimeType != "application/json" || document.sizeBytes !in 1..OCLOG_SIDECAR_BYTES.toLong()) continue
        val text = runCatching {
            resolver.openInputStream(document.uri.toUri())?.use { stream ->
                val bytes = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (bytes.size() <= OCLOG_SIDECAR_BYTES) {
                    val count = stream.read(buffer, 0, minOf(buffer.size, OCLOG_SIDECAR_BYTES + 1 - bytes.size()))
                    if (count <= 0) break
                    bytes.write(buffer, 0, count)
                }
                if (bytes.size() > OCLOG_SIDECAR_BYTES) null
                else Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString()
            }
        }.getOrNull() ?: continue
        parseOcLogSidecar(text, video.uri)?.let { return it }
    }
    return null
}
