/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.storage

import android.content.Context
import android.os.Build
import com.librestatic.opencinecam.camera.RawThroughputBenchmark
import com.librestatic.opencinecam.camera.RawVideoConfig
import com.librestatic.opencinecam.camera.RawVideoQualificationCodec
import com.librestatic.opencinecam.camera.RawVideoQualificationRecord
import com.librestatic.opencinecam.camera.RawVideoQualificationSet
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * PLAN-071 decision 10: the RAW video qualification records, one JSON file in app storage encoded with
 * [RawVideoQualificationCodec]. Records of another build fingerprint or protocol version are stale:
 * lookups ignore them and [upsert]/[pruneStale] remove them. Writes go to a temporary file that is
 * synced and renamed over the store, so a crash leaves either the old or the new file. A missing or
 * unreadable file reads as empty. All methods are thread-safe within the process, across instances too.
 */
class RawVideoQualificationStore(
    private val file: File,
    val fingerprint: String,
    val protocolVersion: Int = RawThroughputBenchmark.PROTOCOL_VERSION,
) {
    init {
        require(fingerprint.isNotBlank()) { "fingerprint must not be blank" }
    }

    private val temp = File(file.parentFile, file.name + ".tmp")

    /** Every stored record, stale ones included. */
    fun load(): RawVideoQualificationSet = synchronized(LOCK) {
        if (!file.isFile) return RawVideoQualificationSet()
        val raw = try { file.readText(Charsets.UTF_8) } catch (_: IOException) { return RawVideoQualificationSet() }
        return RawVideoQualificationCodec.decode(raw)
    }

    fun save(set: RawVideoQualificationSet): Unit = synchronized(LOCK) {
        file.parentFile?.mkdirs()
        val bytes = RawVideoQualificationCodec.encode(set).toByteArray(Charsets.UTF_8)
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

    /** Adds or replaces [record] and drops stale records; returns the stored set. */
    fun upsert(record: RawVideoQualificationRecord): RawVideoQualificationSet = synchronized(LOCK) {
        val next = load().upsert(record).pruned(fingerprint, protocolVersion)
        save(next)
        return next
    }

    /**
     * Merges a qualification's [records] in one write: each replaces any record for the same build,
     * protocol, destination and configuration, the others stay, and stale records are dropped.
     * Returns the stored set.
     */
    fun upsertAll(records: List<RawVideoQualificationRecord>): RawVideoQualificationSet = synchronized(LOCK) {
        val next = records.fold(load()) { set, record -> set.upsert(record) }.pruned(fingerprint, protocolVersion)
        save(next)
        return next
    }

    /** Removes records of another fingerprint or protocol version; returns how many went. */
    fun pruneStale(): Int = synchronized(LOCK) {
        val current = load()
        val pruned = current.pruned(fingerprint, protocolVersion)
        val removed = current.records.size - pruned.records.size
        if (removed > 0) save(pruned)
        return removed
    }

    /** The current build's records. */
    fun currentRecords(): List<RawVideoQualificationRecord> = synchronized(LOCK) { load() }.current(fingerprint, protocolVersion)

    /** The rest reference file: one `fingerprint<TAB>destination<TAB>bytesPerSecond` line per destination. */
    private val restFile = File(file.absoluteFile.parentFile, REST_FILE_NAME)

    /**
     * The best burst median a sweep judged rested measured on this build and [destinationId]
     * ([com.librestatic.opencinecam.camera.RawStorageRest]), or null before the first. Unreadable reads as none.
     */
    fun restReference(destinationId: String): Long? = synchronized(LOCK) {
        readRest()[fingerprint to destinationId]
    }

    /**
     * Stores [bytesPerSecond] as [destinationId]'s rest reference on this build when it beats the
     * stored one; other builds' lines are dropped. Returns the reference now stored.
     */
    fun raiseRestReference(destinationId: String, bytesPerSecond: Long): Long = synchronized(LOCK) {
        require(bytesPerSecond > 0) { "a rest reference must be positive" }
        val current = readRest().filterKeys { it.first == fingerprint }
        val best = maxOf(current[fingerprint to destinationId] ?: 0L, bytesPerSecond)
        if (best == current[fingerprint to destinationId]) return best
        val next = current + ((fingerprint to destinationId) to best)
        restFile.parentFile?.mkdirs()
        val restTemp = File(restFile.parentFile, restFile.name + ".tmp")
        FileOutputStream(restTemp).use { out ->
            out.write(next.entries.joinToString("") { (key, rate) -> "${key.first}\t${key.second}\t$rate\n" }.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        if (!restTemp.renameTo(restFile)) throw IOException("could not replace ${restFile.name}")
        return best
    }

    private fun readRest(): Map<Pair<String, String>, Long> {
        if (!restFile.isFile) return emptyMap()
        val lines = try { restFile.readLines(Charsets.UTF_8) } catch (_: IOException) { return emptyMap() }
        return lines.mapNotNull { line ->
            val parts = line.split('\t')
            val rate = parts.getOrNull(2)?.toLongOrNull()?.takeIf { it > 0 }
            if (parts.size != 3 || rate == null) null else (parts[0] to parts[1]) to rate
        }.toMap()
    }

    /** Configurations whose newest record for this build, protocol, destination and camera passed. */
    fun qualifiedConfigs(cameraId: String, destinationId: String = DESTINATION_MEDIASTORE_DOCUMENTS): List<RawVideoConfig> =
        synchronized(LOCK) { load().qualifiedConfigs(fingerprint, protocolVersion, destinationId, cameraId) }

    companion object {
        /** One lock for every instance: the store is one file per process. */
        private val LOCK = Any()

        const val FILE_NAME = "raw-video-qualifications.json"

        /** The sweep's rest reference ([restReference]), beside the records. */
        const val REST_FILE_NAME = "raw-storage-rest.tsv"

        /** `Documents/OpenCineCam/RAW` through `MediaStore.Files`, the only RAW video destination. */
        const val DESTINATION_MEDIASTORE_DOCUMENTS = "mediastore-documents"

        fun forContext(context: Context): RawVideoQualificationStore =
            RawVideoQualificationStore(File(context.applicationContext.filesDir, FILE_NAME), Build.FINGERPRINT)
    }
}
