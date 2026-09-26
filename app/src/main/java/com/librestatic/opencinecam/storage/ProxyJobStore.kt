/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import com.librestatic.opencinecam.ProxySettings
import com.librestatic.opencinecam.parseProductionSlateJson
import com.librestatic.opencinecam.productionSlateJson
import java.io.File
import java.io.FileOutputStream
import kotlinx.serialization.json.*

enum class ProxyJobStatus { QUEUED, RUNNING, CANCELLING, SUCCEEDED, FAILED, CANCELLED }
data class ProxyJob(val id: String, val take: LocalMediaTake, val settings: ProxySettings,
    val status: ProxyJobStatus, val attempts: Int = 0, val error: String? = null)

/** The snapshot was refused before any storage was touched; the committed state is unchanged. */
class ProxyJobRejectedException(message: String, cause: Throwable) : IllegalArgumentException(message, cause)

/** Single-writer snapshot. A synced staging file precedes same-directory atomic rename.
 * Backup is the previous committed snapshot, never an excuse to hide a corrupt current one.
 * An interrupted main→backup→main rename is recovered only when main is absent.
 */
class ProxyJobStore(directory: File) {
    private val directory = directory.canonicalFile
    private val main = File(this.directory, "jobs.json")
    private val backup = File(this.directory, "jobs.json.bak")
    private val staging = File(this.directory, "jobs.json.tmp")

    @Synchronized fun read(): List<ProxyJob> {
        checkPaths()
        if (main.exists()) return decode(readBytes(main))
        if (backup.exists()) {
            val bytes = readBytes(backup)
            val jobs = decode(bytes)
            stage(bytes)
            check(staging.renameTo(main)) { "Proxy queue backup recovery rename failed" }
            return jobs
        }
        check(!staging.exists()) { "Proxy queue has interrupted staging without a committed snapshot" }
        return emptyList()
    }

    @Synchronized fun write(jobs: List<ProxyJob>) {
        val bytes = try {
            validate(jobs)
            encode(jobs).toString().toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_BYTES) { "Proxy queue exceeds4MiB" } }
        } catch (invalid: IllegalArgumentException) {
            throw ProxyJobRejectedException("Proxy request rejected: ${invalid.message ?: "invalid take or queue limit"}", invalid)
        }
        check(directory.isDirectory || directory.mkdirs()) { "Proxy queue directory unavailable" }
        checkPaths()
        // Validate/recover prior state before touching it; malformed storage is never reset.
        read()
        try {
            stage(bytes)
            if (main.exists()) check(main.renameTo(backup)) { "Proxy queue backup rename failed" }
            check(staging.renameTo(main)) { "Proxy queue commit rename failed; prior state retained in backup" }
        } catch (failure: Throwable) {
            // A reported failure must not leave an uncommitted first snapshot looking like data.
            // When the main rename already happened, the synced backup still owns prior state.
            if (staging.exists() && !staging.delete()) failure.addSuppressed(
                IllegalStateException("Proxy queue failed staging cleanup"))
            throw failure
        }
    }

    private fun checkPaths() {
        check(!directory.exists() || directory.isDirectory) { "Proxy queue path is not a directory" }
        for (file in listOf(main, backup, staging)) {
            check(file.canonicalFile == file.absoluteFile) { "Proxy queue symlink rejected" }
            check(!file.exists() || file.isFile) { "Proxy queue artifact is not a file" }
        }
    }
    private fun stage(bytes: ByteArray) {
        FileOutputStream(staging).use { output -> output.write(bytes); output.fd.sync() }
    }
    private fun readBytes(file: File): ByteArray {
        require(file.length() <= MAX_BYTES) { "Proxy queue exceeds4MiB" }
        return file.inputStream().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer); if (count < 0) break
                require(output.size().toLong() + count <= MAX_BYTES) { "Proxy queue exceeds4MiB" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }
    private fun validate(jobs: List<ProxyJob>) {
        require(jobs.size <= MAX_JOBS) { "Proxy queue exceeds1024jobs" }
        require(jobs.map { it.id }.distinct().size == jobs.size) { "Duplicate proxy job ID" }
        require(jobs.map { it.take.id }.distinct().size == jobs.size) { "Duplicate proxy take ID" }
        for (job in jobs) {
            require(canonicalMediaId(job.id)) { "Invalid proxy job UUID" }
            require(job.attempts >= 0)
            job.error?.let { require(it.length <= 4096 && Charsets.UTF_8.newEncoder().canEncode(it)) }
            ProxySettings(job.settings.maxLongEdge, job.settings.videoBitrateMbps)
            val take = job.take
            require(take.id == "legacy:${take.primary.uri}" ||
                (take.id.startsWith("take:") || take.id.startsWith("still:")) && canonicalMediaId(take.id.substringAfter(':')))
            require(take.originals.size in 1..32 && take.metadata.size <= 4 && take.primary in take.originals)
            val members = take.originals + take.metadata
            require(members.map { it.uri }.distinct().size == members.size)
            for (artifact in members) {
                require(mediaDeleteIdentity(artifact.uri) != null) { "Invalid persisted MediaStore URI" }
                require(artifact.sizeBytes >= 0 && artifact.modifiedSeconds >= 0)
                require(artifact.name.length in 1..255 && artifact.name.none { it.isISOControl() || it == '/' || it == '\\' } &&
                    artifact.name !in setOf(".", "..") && Charsets.UTF_8.newEncoder().canEncode(artifact.name))
                require(artifact.mimeType.length in 3..128 && artifact.mimeType.matches(Regex("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+")))
            }
            require(mediaOriginalIdentity(take.primary.uri)?.first == take.kind)
            require(take.originals.all { mediaOriginalIdentity(it.uri) != null })
            require(take.metadata.all { mediaDeleteIdentity(it.uri)?.collection == MediaDeleteCollection.METADATA })
        }
    }

    private fun artifact(value: LocalMediaArtifact) = buildJsonObject {
        put("uri", value.uri); put("name", value.name); put("mimeType", value.mimeType)
        put("sizeBytes", value.sizeBytes); put("modifiedSeconds", value.modifiedSeconds)
    }
    private fun encode(jobs: List<ProxyJob>) = buildJsonObject {
        put("version", 1)
        put("jobs", JsonArray(jobs.map { job -> buildJsonObject {
            put("id", job.id); put("status", job.status.name); put("attempts", job.attempts)
            put("error", job.error?.let(::JsonPrimitive) ?: JsonNull)
            put("settings", buildJsonObject { put("maxLongEdge", job.settings.maxLongEdge); put("videoBitrateMbps", job.settings.videoBitrateMbps) })
            put("take", buildJsonObject {
                put("id", job.take.id); put("primary", artifact(job.take.primary))
                put("originals", JsonArray(job.take.originals.map(::artifact)))
                put("metadata", JsonArray(job.take.metadata.map(::artifact)))
                put("kind", job.take.kind.name); put("relationStatus", job.take.relationStatus.name)
                put("slate", job.take.slate?.let(::productionSlateJson) ?: JsonNull)
            })
        } }))
    }
    private fun decode(bytes: ByteArray): List<ProxyJob> {
        val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        require(jsonNestingWithinBound(text) && jsonObjectKeysUnique(text)) { "Ambiguous proxy queue JSON" }
        fun JsonObject.keys(vararg expected: String) { require(keys == expected.toSet()) { "Unexpected proxy queue fields" } }
        fun JsonObject.string(key: String) = (getValue(key) as JsonPrimitive).also { require(it.isString) }.content
        fun JsonObject.number(key: String): Long {
            val node = getValue(key) as JsonPrimitive
            require(!node.isString && node.content.matches(Regex("0|[1-9][0-9]*")))
            return node.content.toLong()
        }
        fun JsonObject.integer(key: String): Int = number(key).also { require(it <= Int.MAX_VALUE) }.toInt()
        fun readArtifact(node: JsonElement): LocalMediaArtifact {
            val obj = node.jsonObject; obj.keys("uri", "name", "mimeType", "sizeBytes", "modifiedSeconds")
            return LocalMediaArtifact(obj.string("uri"), obj.string("name"), obj.string("mimeType"), obj.number("sizeBytes"), obj.number("modifiedSeconds"))
        }
        val root = Json.parseToJsonElement(text).jsonObject
        root.keys("version", "jobs"); require(root.integer("version") == 1) { "Unsupported proxy queue version" }
        val rows = root.getValue("jobs").jsonArray
        require(rows.size <= MAX_JOBS)
        return rows.map { node ->
            val obj = node.jsonObject; obj.keys("id", "take", "settings", "status", "attempts", "error")
            val take = obj.getValue("take").jsonObject
            take.keys("id", "primary", "originals", "metadata", "kind", "slate", "relationStatus")
            val originals = take.getValue("originals").jsonArray; val metadata = take.getValue("metadata").jsonArray
            require(originals.size in 1..32 && metadata.size <= 4)
            val settings = obj.getValue("settings").jsonObject; settings.keys("maxLongEdge", "videoBitrateMbps")
            ProxyJob(obj.string("id"), LocalMediaTake(take.string("id"), readArtifact(take.getValue("primary")),
                originals.map(::readArtifact), metadata.map(::readArtifact), LocalMediaKind.valueOf(take.string("kind")),
                take.getValue("slate").let { if (it == JsonNull) null else requireNotNull(parseProductionSlateJson(it.jsonObject)) },
                LocalMediaRelationStatus.valueOf(take.string("relationStatus"))),
                ProxySettings(settings.integer("maxLongEdge"), settings.integer("videoBitrateMbps")),
                ProxyJobStatus.valueOf(obj.string("status")), obj.integer("attempts"),
                if (obj.getValue("error") == JsonNull) null else obj.string("error"))
        }.also(::validate)
    }
    companion object { const val MAX_JOBS = 1024; const val MAX_BYTES = 4 * 1024 * 1024 }
}
