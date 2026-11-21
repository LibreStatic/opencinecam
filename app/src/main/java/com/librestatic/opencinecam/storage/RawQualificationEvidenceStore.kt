/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.storage

import android.content.Context
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import com.librestatic.opencinecam.camera.RawThroughputBenchmark
import com.librestatic.opencinecam.rawvideo.RawQualificationEvidence
import com.librestatic.opencinecam.rawvideo.RawQualificationEvidenceRules
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * PLAN-071 (2026-11-20): the camera checks and encode benchmarks earlier qualifications measured
 * ([RawQualificationEvidence]), one small versioned JSON file beside the qualification records
 * ([RawVideoQualificationStore]), so a later run reuses them instead of measuring again. Only this build's
 * ([fingerprint], [appVersion]) and [protocolVersion]'s entries count, each for
 * [RawQualificationEvidenceRules.MAX_AGE_MS]; [put] refuses results that are not reusable
 * ([RawQualificationEvidenceRules.reusableResult]), and drops other builds' and expired entries. Writes go to a temporary
 * file that is synced and renamed over the store; a missing, unreadable, malformed or unknown-schema file reads as empty,
 * so the next [put] replaces it. Thread-safe within the process, across instances too.
 */
class RawQualificationEvidenceStore(
    private val file: File,
    val fingerprint: String,
    val appVersion: Long,
    val protocolVersion: Int = RawThroughputBenchmark.PROTOCOL_VERSION,
) {
    init {
        require(fingerprint.isNotBlank()) { "fingerprint must not be blank" }
    }

    private val temp = File(file.parentFile, file.name + ".tmp")

    /** Every stored entry, other builds' and expired ones included. */
    fun load(): List<RawQualificationEvidence> = synchronized(LOCK) {
        if (!file.isFile) return emptyList()
        val raw = try { file.readText(Charsets.UTF_8) } catch (_: IOException) { return emptyList() }
        return decode(raw)
    }

    /** The usable entry of [kind] and [key] at [nowMs] ([RawQualificationEvidenceRules.usable]), or null. */
    fun find(kind: String, key: String, nowMs: Long, ringSlots: Int? = null): RawQualificationEvidence? = synchronized(LOCK) {
        load().lastOrNull {
            it.kind == kind && it.key == key && RawQualificationEvidenceRules.usable(it, fingerprint, appVersion, protocolVersion, nowMs, ringSlots)
        }
    }

    /**
     * Stores [entry] in place of any entry of the same kind and key, and drops the entries that can no longer be used
     * (another build or protocol, expired). Returns false, storing nothing, when [entry] itself is not usable on this
     * build at [nowMs]: a thermal skip or limit, an exception, a run with no ring or a cancelled one is never kept.
     */
    fun put(entry: RawQualificationEvidence, nowMs: Long): Boolean = synchronized(LOCK) {
        if (!RawQualificationEvidenceRules.usable(entry, fingerprint, appVersion, protocolVersion, nowMs)) return false
        val kept = load().filter {
            !(it.kind == entry.kind && it.key == entry.key) &&
                it.fingerprint == fingerprint && it.appVersion == appVersion && it.protocolVersion == protocolVersion &&
                RawQualificationEvidenceRules.fresh(it.measuredAtEpochMs, nowMs)
        }
        save(kept + entry)
        return true
    }

    private fun save(entries: List<RawQualificationEvidence>) {
        file.parentFile?.mkdirs()
        val bytes = encode(entries).toByteArray(Charsets.UTF_8)
        FileOutputStream(temp).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
        try {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            if (!temp.renameTo(file)) throw IOException("could not replace ${file.name}")
        }
    }

    companion object {
        /** One lock for every instance: the store is one file per process. */
        private val LOCK = Any()

        const val FILE_NAME = "raw-qualification-evidence.json"

        /** The file format's version; another reads as empty. */
        const val SCHEMA = 1

        private val json = Json { ignoreUnknownKeys = true }

        fun encode(entries: List<RawQualificationEvidence>): String = buildJsonObject {
            put("schema", SCHEMA)
            put("entries", JsonArray(entries.map { entry ->
                buildJsonObject {
                    put("kind", entry.kind)
                    put("key", entry.key)
                    put("fingerprint", entry.fingerprint)
                    put("appVersion", entry.appVersion)
                    put("protocolVersion", entry.protocolVersion)
                    put("measuredAtEpochMs", entry.measuredAtEpochMs)
                    put("status", entry.status)
                    put("reasonCodes", JsonArray(entry.reasonCodes.map(::JsonPrimitive)))
                    put("ringSlots", entry.ringSlots)
                    put("achievedFps", entry.achievedFps)
                    put("row", entry.row)
                }
            }))
        }.toString()

        /**
         * The entries of [raw]; empty for an unreadable file, another [SCHEMA] or a malformed root, schema or entry list
         * (a schema that is an object, entries that are not an array), so the next [put] rewrites the file instead of
         * failing on it. An entry that does not parse is dropped.
         */
        fun decode(raw: String): List<RawQualificationEvidence> {
            val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return emptyList()
            if ((root["schema"] as? JsonPrimitive)?.intOrNull != SCHEMA) return emptyList()
            val entries = root["entries"] as? JsonArray ?: return emptyList()
            return entries.mapNotNull { element -> runCatching { entryOf(element.jsonObject) }.getOrNull() }
        }

        private fun entryOf(o: JsonObject) = RawQualificationEvidence(
            kind = o.getValue("kind").jsonPrimitive.content,
            key = o.getValue("key").jsonPrimitive.content,
            fingerprint = o.getValue("fingerprint").jsonPrimitive.content,
            appVersion = o.getValue("appVersion").jsonPrimitive.long,
            protocolVersion = o.getValue("protocolVersion").jsonPrimitive.int,
            measuredAtEpochMs = o.getValue("measuredAtEpochMs").jsonPrimitive.long,
            status = o.getValue("status").jsonPrimitive.content,
            reasonCodes = o.getValue("reasonCodes").jsonArray.map { it.jsonPrimitive.content },
            ringSlots = o.getValue("ringSlots").jsonPrimitive.int,
            achievedFps = o["achievedFps"]?.jsonPrimitive?.doubleOrNull,
            row = o.getValue("row").jsonPrimitive.content,
        )

        fun forContext(context: Context): RawQualificationEvidenceStore {
            val app = context.applicationContext
            val version = runCatching { PackageInfoCompat.getLongVersionCode(app.packageManager.getPackageInfo(app.packageName, 0)) }.getOrDefault(0L)
            return RawQualificationEvidenceStore(File(app.filesDir, FILE_NAME), Build.FINGERPRINT, version)
        }
    }
}
