/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Observed identity of a derived row only. Original URIs are never deletion members. */
internal data class ProxyDeletionMember(
    val uri: String, val name: String, val mimeType: String, val relativePath: String,
    val owner: String, val bytes: Long, val sha256: String,
)
internal data class ProxyDeletionIntent(
    val jobId: String, val takeId: String, val originalUri: String, val originalSha256: String,
    val video: ProxyDeletionMember, val metadata: ProxyDeletionMember,
)

/** Caller holds its platform/media ownership across the entire journal operation.
 * Validation observes BOTH surviving identities before the first deletion. Missing rows are
 * permitted only after unfiltered URI absence checks; changed/unknown rows must throw.
 * Recovery need not require the original to remain present, and must never mutate it.
 */
internal interface ProxyDeletionAccess {
    suspend fun validate(intent: ProxyDeletionIntent)
    suspend fun isAbsent(member: ProxyDeletionMember): Boolean
    suspend fun delete(member: ProxyDeletionMember)
    suspend fun verifyEmpty(intent: ProxyDeletionIntent)
    /** Idempotently remove the receipt and persist removal of the terminal queue job. No enqueue.
     * Return only after both durable transitions complete; any failure retains the tombstone. */
    suspend fun commitDeletion(intent: ProxyDeletionIntent)
}

/** One immutable tombstone per confirmed pair. Synced bytes + same-directory atomic rename precede
 * effects; process death at any later boundary leaves the exact intent for idempotent recovery.
 * This is not cross-provider atomic deletion. Failed/partial operations retain their tombstone.
 */
internal class ProxyDeletionJournal(directory: File) {
    // Android may expose filesDir through /data/user/0 -> /data/data. Resolve the trusted
    // parent once, while still rejecting a symlink at our own journal/entry boundary.
    private val directory = File(requireNotNull(directory.absoluteFile.parentFile).canonicalFile, directory.name)
    private val lock = locks.computeIfAbsent(this.directory.path) { Mutex() }

    fun read(jobId: String): ProxyDeletionIntent? {
        require(canonicalMediaId(jobId)); checkDirectory()
        val file = entry(jobId)
        checkFile(file)
        if (!file.exists()) return null
        return decode(readBounded(file)).also { check(it.jobId == jobId) { "Deletion filename identity differs" } }
    }

    /** Validate every committed tombstone before returning any; unknown/corrupt entries halt.
     * A lone .tmp is an uncommitted staging file: no deletion could have started from it.
     * Validate its bytes but never interpret it as a confirmed operation or delete its rows. */
    fun pending(): List<ProxyDeletionIntent> {
        checkDirectory()
        if (!directory.exists()) return emptyList()
        val files = mutableListOf<File>()
        Files.newDirectoryStream(directory.toPath()).use { entries ->
            for (path in entries) {
                check(files.size < MAX_JOBS * 2) { "Proxy deletion journal exceeds entry bound" }
                files += path.toFile()
            }
        }
        val intents = mutableListOf<ProxyDeletionIntent>()
        for (file in files.sortedBy { it.name }) {
            checkFile(file)
            val match = requireNotNull(Regex("([0-9a-f-]{36})\\.(json|tmp)").matchEntire(file.name)) { "Unknown proxy deletion journal entry" }
            val id = match.groupValues[1]
            require(canonicalMediaId(id))
            val intent = decode(readBounded(file))
            check(intent.jobId == id) { "Deletion filename identity differs" }
            if (match.groupValues[2] == "json") intents += intent
        }
        check(intents.size <= MAX_JOBS)
        return intents
    }

    suspend fun begin(intent: ProxyDeletionIntent, access: ProxyDeletionAccess) = lock.withLock {
        validateIdentity(intent)
        val existing = read(intent.jobId)
        check(existing == null || existing == intent) { "A different proxy deletion owns this job" }
        // The platform adapter must revalidate the frozen confirmation before committing intent.
        access.validate(intent)
        withContext(NonCancellable) {
            if (existing == null) persist(intent)
            finish(intent, access)
        }
    }

    /** Returns false only when no committed tombstone exists; no callbacks run in that case. */
    suspend fun recover(jobId: String, access: ProxyDeletionAccess): Boolean = lock.withLock {
        val intent = read(jobId) ?: return@withLock false
        access.validate(intent)
        withContext(NonCancellable) { finish(intent, access) }
        true
    }

    private suspend fun finish(intent: ProxyDeletionIntent, access: ProxyDeletionAccess) {
        for (member in listOf(intent.video, intent.metadata)) {
            if (!access.isAbsent(member)) access.delete(member)
            check(access.isAbsent(member)) { "Proxy deletion member remains or absence is unknown" }
        }
        access.verifyEmpty(intent)
        access.commitDeletion(intent)
        // Queue/receipt transition may complete twice after a crash here; adapter is idempotent.
        check(read(intent.jobId) == intent) { "Proxy deletion intent changed during operation" }
        val temporary = staging(intent.jobId)
        checkFile(temporary)
        check(!temporary.exists() || temporary.delete()) { "Proxy deletion staging cleanup failed" }
        check(entry(intent.jobId).delete()) { "Proxy deletion tombstone retirement failed" }
        check(read(intent.jobId) == null) { "Proxy deletion tombstone remains" }
    }

    private fun persist(intent: ProxyDeletionIntent) {
        checkDirectory()
        check(directory.isDirectory || directory.mkdirs()) { "Proxy deletion journal unavailable" }
        val pending = pending()
        check(pending.size < MAX_JOBS) { "Proxy deletion journal exceeds job bound" }
        val file = entry(intent.jobId); val temporary = staging(intent.jobId)
        checkFile(file); checkFile(temporary)
        check(!file.exists()) { "Proxy deletion intent already committed" }
        val bytes = encode(intent).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES)
        FileOutputStream(temporary).use { output -> output.write(bytes); output.fd.sync() }
        // No non-atomic fallback. Failure precedes any member effect, retaining staging evidence.
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
        check(read(intent.jobId) == intent) { "Proxy deletion intent readback differs" }
    }

    private fun checkDirectory() {
        check(directory.canonicalFile == directory) { "Proxy deletion journal symlink rejected" }
        check(!directory.exists() || directory.isDirectory) { "Proxy deletion journal is not a directory" }
    }
    private fun checkFile(file: File) {
        check(file.canonicalFile == file.absoluteFile) { "Proxy deletion entry symlink rejected" }
        check(!file.exists() || file.isFile) { "Proxy deletion entry is not a file" }
    }
    private fun entry(id: String) = File(directory, "$id.json")
    private fun staging(id: String) = File(directory, "$id.tmp")
    private fun readBounded(file: File): ByteArray {
        require(file.length() in 1..MAX_BYTES.toLong()) { "Proxy deletion intent exceeds byte bound or is empty" }
        return file.inputStream().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_BYTES)
                output.write(buffer, 0, count)
            }
            require(output.size() > 0)
            output.toByteArray()
        }
    }

    private fun validateIdentity(intent: ProxyDeletionIntent) {
        require(canonicalMediaId(intent.jobId))
        require(mediaOriginalIdentity(intent.originalUri)?.first == LocalMediaKind.VIDEO)
        require(intent.takeId == "legacy:${intent.originalUri}" ||
            intent.takeId.startsWith("take:") && canonicalMediaId(intent.takeId.removePrefix("take:")))
        require(SHA.matches(intent.originalSha256))
        require(setOf(intent.originalUri, intent.video.uri, intent.metadata.uri).size == 3)
        require(intent.video.owner == intent.metadata.owner)
        for ((member, video) in listOf(intent.video to true, intent.metadata to false)) {
            val identity = requireNotNull(mediaDeleteIdentity(member.uri))
            require(identity.collection == if (video) MediaDeleteCollection.VIDEO else MediaDeleteCollection.METADATA)
            if (video) requireProxyFilename(member.name) else require(member.name == "proxy-${intent.jobId}.json")
            require(member.relativePath == "${if (video) "Movies" else "Download"}/OpenCineCamProxies/${intent.jobId}/")
            require(member.mimeType == if (video) "video/mp4" else "application/json")
            require(member.owner.length in 1..255 && member.owner.matches(Regex("[A-Za-z0-9_.]+")))
            require(member.bytes > 0 && SHA.matches(member.sha256))
            if (!video) require(member.bytes <= 64 * 1024)
        }
    }
    private fun encode(intent: ProxyDeletionIntent) = buildJsonObject {
        fun member(value: ProxyDeletionMember) = buildJsonObject {
            put("uri", value.uri); put("name", value.name); put("mimeType", value.mimeType)
            put("relativePath", value.relativePath); put("owner", value.owner)
            put("bytes", value.bytes); put("sha256", value.sha256)
        }
        put("version", 1); put("jobId", intent.jobId); put("takeId", intent.takeId)
        put("originalUri", intent.originalUri); put("originalSha256", intent.originalSha256)
        put("video", member(intent.video)); put("metadata", member(intent.metadata))
    }
    private fun decode(bytes: ByteArray): ProxyDeletionIntent {
        val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        require(jsonNestingWithinBound(text) && jsonObjectKeysUnique(text)) { "Ambiguous proxy deletion JSON" }
        fun JsonObject.exact(vararg names: String) { require(keys == names.toSet()) { "Unexpected proxy deletion fields" } }
        fun JsonObject.string(key: String) = (getValue(key) as JsonPrimitive).also { require(it.isString) }.content
        fun JsonObject.number(key: String): Long {
            val node = getValue(key) as JsonPrimitive
            require(!node.isString && node.content.matches(Regex("0|[1-9][0-9]*")))
            return node.content.toLong()
        }
        fun member(node: JsonElement): ProxyDeletionMember {
            val value = node.jsonObject
            value.exact("uri", "name", "mimeType", "relativePath", "owner", "bytes", "sha256")
            return ProxyDeletionMember(value.string("uri"), value.string("name"), value.string("mimeType"),
                value.string("relativePath"), value.string("owner"), value.number("bytes"), value.string("sha256"))
        }
        val root = Json.parseToJsonElement(text).jsonObject
        root.exact("version", "jobId", "takeId", "originalUri", "originalSha256", "video", "metadata")
        require(root.number("version") == 1L) { "Unsupported proxy deletion schema" }
        return ProxyDeletionIntent(root.string("jobId"), root.string("takeId"), root.string("originalUri"),
            root.string("originalSha256"), member(root.getValue("video")), member(root.getValue("metadata"))).also(::validateIdentity)
    }
    private companion object {
        const val MAX_BYTES = 16 * 1024
        const val MAX_JOBS = 1024
        val SHA = Regex("[0-9a-f]{64}")
        val locks = ConcurrentHashMap<String, Mutex>()
    }
}
