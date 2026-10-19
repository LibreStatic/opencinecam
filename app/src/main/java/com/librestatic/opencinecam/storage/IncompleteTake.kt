/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import android.content.ContentResolver
import android.net.Uri
import java.io.FileNotFoundException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * ADR-0011: a take that did not finish cleanly keeps every output that holds bytes and is published
 * marked incomplete; only zero-length outputs are deleted. The marker lives in the take's metadata
 * sidecar as `"takeStatus": "incomplete"` plus stable `"failureReasons"` codes. Complete takes
 * carry neither field.
 */
internal object IncompleteTake {
    const val STATUS_KEY = "takeStatus"
    const val STATUS_INCOMPLETE = "incomplete"
    const val REASONS_KEY = "failureReasons"
    const val NOTE_KEY = "incompleteNote"

    const val AUDIO_START_FAILED = "audio-start-failed"
    const val RECORDING_LUT_UNVERIFIED = "recording-lut-unverified"
    const val NATIVE_RETIREMENT_PENDING = "native-retirement-pending"
    const val LOG_EVIDENCE_MISSING = "log-evidence-missing"
    const val ENCODER_FAILED = "encoder-failed"
    const val MUXER_NOT_FINALIZED = "muxer-not-finalized"
    const val PROCESS_DEATH = "process-death"

    /** Adds the incomplete marker to [json] (or to a minimal recording document when there is none). */
    fun mark(json: String?, reasons: List<String>, note: String? = null): String {
        val original = json?.let { Json.parseToJsonElement(it) as? JsonObject }
        return PRETTY.encodeToString(JsonObject.serializer(), buildJsonObject {
            if (original == null) put("schema", "opencinecam.recording.v1") else original.forEach { (key, value) -> put(key, value) }
            put(STATUS_KEY, STATUS_INCOMPLETE)
            put(REASONS_KEY, JsonArray(reasons.distinct().map(::JsonPrimitive)))
            note?.let { put(NOTE_KEY, it) }
        })
    }

    /** Failure reasons of a sidecar that carries the marker; null for a complete or unreadable sidecar. */
    fun reasonsOf(json: String): List<String>? = try {
        val document = Json.parseToJsonElement(json) as JsonObject
        if ((document[STATUS_KEY] as? JsonPrimitive)?.content != STATUS_INCOMPLETE) null
        else (document[REASONS_KEY] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
    } catch (_: Exception) { null }

    private val PRETTY = Json { prettyPrint = true; prettyPrintIndent = "  " }
}

/** Bytes of an owned, possibly pending, row read from its file: 0 when the row is gone, -1 when unreadable. */
internal fun ContentResolver.ownedRowBytes(uri: Uri): Long = try {
    openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
} catch (_: FileNotFoundException) {
    0L
} catch (_: Exception) {
    -1L
}

/** An unreadable size never authorizes deleting media: treat it as holding bytes. */
internal fun bytesForDecision(measured: Long): Long = if (measured < 0L) 1L else measured
