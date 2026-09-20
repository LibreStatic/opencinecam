/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.transfers

import com.librestatic.opencinecam.storage.CaptureArtifactRole
import com.librestatic.opencinecam.storage.PreparedCaptureArtifact
import java.net.URI
import java.util.Collections
import java.util.UUID
import kotlinx.serialization.json.*

enum class CapturePublicationState { PREPARED, COMMITTED, ABORTED }

/** Private identities only. A receipt describes publication callbacks, not upload or media validity. */
class CapturePublicationReceipt(
    val bundleId: String,
    val state: CapturePublicationState,
    artifacts: List<PreparedCaptureArtifact>,
) {
    val artifacts: List<PreparedCaptureArtifact> = Collections.unmodifiableList(artifacts.sortedBy { it.role.name })
    init {
        requirePublicationUuid(bundleId)
        if (this.artifacts.isEmpty()) require(state == CapturePublicationState.ABORTED)
        else validatePublicationArtifacts(this.artifacts)
    }
    override fun equals(other: Any?): Boolean = other is CapturePublicationReceipt &&
        bundleId == other.bundleId && state == other.state && artifacts == other.artifacts
    override fun hashCode(): Int = 31 * (31 * bundleId.hashCode() + state.hashCode()) + artifacts.hashCode()
    override fun toString(): String = "CapturePublicationReceipt(bundleId=$bundleId, state=$state, artifactCount=${artifacts.size})"
}

class CapturePublicationJournalCorruptData : IllegalStateException("Capture publication journal requires recovery")
class CapturePublicationJournalCapacity : IllegalStateException("Capture publication journal is full")

/** No row inspection can synthesize COMMITTED, and a terminal state cannot be reversed. */
internal object CapturePublicationTransitions {
    fun prepared(id: String, previous: CapturePublicationReceipt?, artifacts: List<PreparedCaptureArtifact>): CapturePublicationReceipt {
        val next = CapturePublicationReceipt(id, CapturePublicationState.PREPARED, artifacts)
        if (previous == null) return next
        require(previous.bundleId == id && previous.state != CapturePublicationState.ABORTED)
        require(previous.artifacts == next.artifacts) { "Publication artifact set is already bound" }
        return previous
    }

    fun published(id: String, previous: CapturePublicationReceipt?, artifacts: List<PreparedCaptureArtifact>): CapturePublicationReceipt {
        val next = CapturePublicationReceipt(id, CapturePublicationState.COMMITTED, artifacts)
        require(previous != null && previous.bundleId == id && previous.state != CapturePublicationState.ABORTED)
        require(previous.artifacts == next.artifacts) { "Published artifacts differ from the prepared set" }
        return if (previous.state == CapturePublicationState.COMMITTED) previous else next
    }

    fun aborted(id: String, previous: CapturePublicationReceipt?): CapturePublicationReceipt {
        require(previous == null || (previous.bundleId == id && previous.state != CapturePublicationState.COMMITTED))
        return CapturePublicationReceipt(id, CapturePublicationState.ABORTED, previous?.artifacts.orEmpty())
    }
}

/** Canonical, strictly bounded private format. Duplicate fields and coercible values are rejected. */
internal object CapturePublicationJournalCodec {
    const val MAX_BYTES = 16_384

    fun encode(receipt: CapturePublicationReceipt): ByteArray = buildJsonObject {
        put("version", 1)
        put("bundleId", receipt.bundleId)
        put("state", receipt.state.name)
        put("artifacts", buildJsonArray {
            for (artifact in receipt.artifacts) add(buildJsonObject {
                put("role", artifact.role.name)
                put("uri", artifact.uri)
                put("displayName", artifact.displayName)
            })
        })
    }.toString().toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_BYTES) }

    fun decode(bytes: ByteArray): CapturePublicationReceipt = try {
        require(bytes.size <= MAX_BYTES)
        val document = bytes.decodeToString(throwOnInvalidSequence = true)
        requireShallowJson(document)
        val json = Json.parseToJsonElement(document).jsonObject
        require(json.keys == setOf("version", "bundleId", "state", "artifacts"))
        val version = json.getValue("version").jsonPrimitive
        require(!version.isString && version.content == "1")
        fun text(value: JsonElement): String = value.jsonPrimitive.also { require(it.isString) }.content
        val artifacts = json.getValue("artifacts").jsonArray
        require(artifacts.size <= 4)
        val receipt = CapturePublicationReceipt(text(json.getValue("bundleId")), CapturePublicationState.valueOf(text(json.getValue("state"))),
            artifacts.map { value ->
                val artifact = value.jsonObject
                require(artifact.keys == setOf("role", "uri", "displayName"))
                PreparedCaptureArtifact(CaptureArtifactRole.valueOf(text(artifact.getValue("role"))),
                    text(artifact.getValue("uri")), text(artifact.getValue("displayName")))
            })
        // Writer-owned data has a single encoding. This also rejects duplicate JSON object keys.
        require(bytes.contentEquals(encode(receipt)))
        receipt
    } catch (_: Exception) {
        throw CapturePublicationJournalCorruptData()
    }

    /** Check before the recursive parser: a short corrupt file can still contain thousands of levels. */
    private fun requireShallowJson(document: String) {
        var depth = 0
        var quoted = false
        var escaped = false
        for (character in document) {
            if (quoted) {
                if (escaped) escaped = false
                else if (character == '\\') escaped = true
                else if (character == '"') quoted = false
            } else when (character) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= 3) }
                '}', ']' -> { depth--; require(depth >= 0) }
            }
        }
        require(depth == 0 && !quoted)
    }
}

internal fun requirePublicationUuid(value: String) {
    require(value.length == 36 && runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false))
}

private fun validatePublicationArtifacts(artifacts: List<PreparedCaptureArtifact>) {
    require(artifacts.size in 1..4)
    require(artifacts.map { it.role }.toSet().size == artifacts.size)
    require(artifacts.map { it.uri }.toSet().size == artifacts.size)
    require(artifacts.any { it.role == CaptureArtifactRole.VIDEO })
    require(artifacts.none { it.role == CaptureArtifactRole.AUDIO_METADATA } || artifacts.any { it.role == CaptureArtifactRole.AUDIO })
    for (artifact in artifacts) {
        val name = artifact.displayName
        require(name.isNotBlank() && name != "." && name != "..")
        require(name.none { it == '/' || it == '\\' || it.code < 32 || it.code == 127 })
        require(Charsets.UTF_8.newEncoder().canEncode(name) && name.toByteArray(Charsets.UTF_8).size <= 255)
        require(artifact.uri.length <= 1024)
        val uri = try { URI(artifact.uri) } catch (_: Exception) { throw IllegalArgumentException("Invalid publication item") }
        require(uri.scheme == "content" && uri.rawAuthority == "media" && uri.rawQuery == null && uri.rawFragment == null)
        val path = requireNotNull(uri.path)
        require(uri.rawPath == path && path.startsWith('/') && !path.endsWith('/'))
        val parts = path.substring(1).split('/')
        require(parts.size in 3..4 && parts.first().matches(Regex("[A-Za-z0-9_-]+")))
        val id = parts.last().toLongOrNull()
        require(id != null && id > 0L && id.toString() == parts.last())
        require(parts.subList(1, parts.lastIndex) in listOf(listOf("video", "media"), listOf("images", "media"),
            listOf("audio", "media"), listOf("downloads"), listOf("file")))
    }
}
