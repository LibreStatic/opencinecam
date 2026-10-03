/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import com.librestatic.opencinecam.GallerySettings
import com.librestatic.opencinecam.GalleryMediaKind
import com.librestatic.opencinecam.ProductionSlateSettings
import com.librestatic.opencinecam.parseProductionSlateJson
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.json.*

enum class LocalMediaKind { PHOTO, VIDEO, AUDIO }
enum class LocalMediaRelationStatus { DECLARED, LEGACY, MISSING_METADATA, INVALID_METADATA, INCOMPLETE }
data class LocalMediaArtifact(val uri: String, val name: String, val mimeType: String,
    val sizeBytes: Long, val modifiedSeconds: Long)
data class LocalMediaTake(val id: String, val primary: LocalMediaArtifact,
    val originals: List<LocalMediaArtifact>, val metadata: List<LocalMediaArtifact>,
    val kind: LocalMediaKind, val slate: ProductionSlateSettings?, val relationStatus: LocalMediaRelationStatus)
/** Codec facts a take's sidecars declare. They stay out of [LocalMediaTake], whose equality is the
 * identity check behind sharing, renaming and proxy jobs. */
data class LocalMediaEncoding(val videoMime: String? = null, val videoProfile: String? = null,
    val audioContainer: String? = null, val audioSamples: String? = null)
/** [encodings] holds, by take id, what the page's sidecars declared; takes without one are absent. */
data class LocalMediaPage(val takes: List<LocalMediaTake>, val next: LocalMediaCursor?,
    val encodings: Map<String, LocalMediaEncoding> = emptyMap())

/** Opaque, constant-size keyset state. No accumulated take IDs or offset-based pagination. */
class LocalMediaCursor internal constructor(internal val filter: String,
    internal val positions: Map<LocalMediaKind, MediaPosition>, internal val exhausted: Set<LocalMediaKind>)
internal data class MediaPosition(val modified: Long, val id: Long)
internal data class MediaNamespace(val id: String, val recording: Boolean) {
    val key: String get() = if (recording) "take:$id" else "still:$id"
    fun path(root: String): String = "$root/OpenCineCam/${if (recording) "OCC_TAKE_" else "OCC_"}$id/"
}
internal data class CatalogRow(val artifact: LocalMediaArtifact, val id: Long,
    val kind: LocalMediaKind?, val namespace: MediaNamespace?, val eligible: Boolean = true)
internal data class MetadataDocument(val artifact: LocalMediaArtifact, val text: String?, val disappeared: Boolean = false)

/** Only exact application roots and canonical UUID namespaces carry identity. Names never do. */
internal fun mediaNamespace(path: String): MediaNamespace? {
    val root = listOf("DCIM", "Music", "Download").firstOrNull { path.startsWith("$it/OpenCineCam/") } ?: return null
    val rest = path.removePrefix("$root/OpenCineCam/")
    val directory = rest.substringBefore('/')
    if (!rest.endsWith('/') || rest.split('/').any { it == "." || it == ".." }) return null
    val recording = directory.startsWith("OCC_TAKE_")
    val prefix = if (recording) "OCC_TAKE_" else "OCC_"
    if (!directory.startsWith(prefix)) return null
    val id = directory.removePrefix(prefix)
    if (!canonicalMediaId(id) || !recording && root == "Music") return null
    return MediaNamespace(id, recording)
}
internal fun canonicalMediaId(id: String): Boolean = id.length == 36 &&
    runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
internal fun isCatalogPath(path: String): Boolean =
    path in setOf("DCIM/OpenCineCam/", "Music/OpenCineCam/", "Download/OpenCineCam/") || mediaNamespace(path) != null
internal fun catalogFilter(settings: GallerySettings, query: String): String =
    "${settings.kind}|${settings.newestFirst}|${settings.goodTakesOnly}|${query.trim().lowercase(Locale.ROOT)}"
internal fun catalogComparator(newestFirst: Boolean): Comparator<CatalogRow> {
    val date = if (newestFirst) compareByDescending<CatalogRow> { it.artifact.modifiedSeconds }
        else compareBy { it.artifact.modifiedSeconds }
    return date.thenBy { it.kind?.ordinal ?: 3 }.thenBy { it.id }
}
internal fun catalogPrimary(rows: List<CatalogRow>): CatalogRow? =
    rows.filter { it.kind == LocalMediaKind.VIDEO }.minByOrNull { it.id }
        ?: rows.filter { it.kind == LocalMediaKind.PHOTO }.minByOrNull { it.id }
        ?: rows.filter { it.kind == LocalMediaKind.AUDIO }.minByOrNull { it.id }
internal fun catalogMatches(take: LocalMediaTake, settings: GallerySettings, query: String): Boolean {
    if (settings.kind != GalleryMediaKind.ALL && settings.kind.name != take.kind.name) return false
    if (settings.goodTakesOnly && take.slate?.goodTake != true) return false
    val needle = query.trim().lowercase(Locale.ROOT)
    if (needle.isEmpty()) return true
    val slate = take.slate
    val fields = take.originals.map { it.name } + take.metadata.map { it.name } + listOf(take.id) +
        (slate?.let { listOf(it.project, it.camera, it.scene, it.reel, it.lens, it.takeNumber.toString()) } ?: emptyList())
    return fields.any { it.lowercase(Locale.ROOT).contains(needle) }
}

/** Validates declarations against available owned namespace members, not filename similarity.
 * DECLARED means relationship identity was checked, never that content hashes were verified.
 * [onEncoding] receives the codec facts of a valid take, read from the same parse. */
internal fun catalogTake(namespace: MediaNamespace, members: List<CatalogRow>,
    documents: List<MetadataDocument>, incomplete: Boolean = false,
    onEncoding: ((LocalMediaEncoding) -> Unit)? = null): LocalMediaTake? {
    val originals = members.filter { it.kind != null }
    val primary = catalogPrimary(originals) ?: return null
    val known = originals.associateBy { it.artifact.uri }
    var invalid = false
    var missing = incomplete
    val references = mutableSetOf<String>()
    val slates = mutableSetOf<ProductionSlateSettings>()
    var slateAbsent = false
    var encoding = LocalMediaEncoding()
    for (document in documents) {
        if (document.disappeared) { missing = true; continue }
        try {
            val text = requireNotNull(document.text)
            // Cap nesting before recursive JSON parsing; byte and member caps belong to the reader.
            require(jsonNestingWithinBound(text) && jsonObjectKeysUnique(text))
            val obj = Json.parseToJsonElement(text) as? JsonObject ?: error("Metadata object required")
            val schemaVersion = obj["schemaVersion"] as? JsonPrimitive
            val schema = (obj["schema"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val still = !namespace.recording
            if (still) require(schemaVersion?.isString == false && schemaVersion.intOrNull == 1)
            else require(schema in recordingSchemas)
            val bundle = obj["bundleId"]
            if (still || bundle != null) require((bundle as? JsonPrimitive)?.let { it.isString && it.content == namespace.id } == true)
            val declared = mutableListOf<Pair<String, JsonObject?>>()
            fun reference(value: JsonElement, details: JsonObject? = null) {
                val uri = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("URI string required")
                require(uri.matches(Regex("content://media/external_primary/(images/media|video/media|audio/media)/[1-9][0-9]*")))
                declared += uri to details
            }
            if (still) {
                when {
                    obj["image"] != null -> {
                        val item = obj["image"] as? JsonObject ?: error("Image object required")
                        reference(requireNotNull(item["uri"]), item)
                    }
                    else -> {
                        val entries = (obj["images"] ?: obj["frames"]) as? JsonArray ?: error("Image list required")
                        require(entries.size in 1..16)
                        entries.forEach { entry ->
                            val item = entry as? JsonObject ?: error("Image object required")
                            reference(requireNotNull(item["uri"]), item)
                        }
                    }
                }
            } else {
                if (schema == "opencinecam-audio-sidecar-v1") reference(requireNotNull(obj["audioUri"]))
                else reference(requireNotNull(obj["videoUri"]))
            }
            require(declared.isNotEmpty() && declared.map { it.first }.distinct().size == declared.size)
            for ((uri, details) in declared) {
                val expectedKind = if (still) LocalMediaKind.PHOTO else if (schema == "opencinecam-audio-sidecar-v1") LocalMediaKind.AUDIO else LocalMediaKind.VIDEO
                val segment = when (expectedKind) { LocalMediaKind.PHOTO -> "/images/media/"; LocalMediaKind.VIDEO -> "/video/media/"; LocalMediaKind.AUDIO -> "/audio/media/" }
                require(segment in uri)
                val existing = known[uri]
                if (existing == null) missing = true else {
                    require(existing.namespace == namespace && existing.kind == expectedKind)
                    details?.get("displayName")?.let { require((it as? JsonPrimitive)?.let { v -> v.isString && v.content == existing.artifact.name } == true) }
                    details?.get("mimeType")?.let { require((it as? JsonPrimitive)?.let { v -> v.isString && v.content == existing.artifact.mimeType } == true) }
                    details?.get("bytes")?.let { require((it as? JsonPrimitive)?.let { v -> !v.isString && v.longOrNull == existing.artifact.sizeBytes } == true) }
                }
            }
            val node = obj["productionSlate"]
            val slate = if (node == null) null else requireNotNull(parseProductionSlateJson(node as? JsonObject ?: error("Slate object required")))
            if (slate == null) slateAbsent = true else slates += slate
            references += declared.map { it.first }
            when (schema) {
                "opencinecam-oclog-sidecar-v2" -> (obj["encoding"] as? JsonObject)?.let {
                    encoding = encoding.copy(videoMime = it.label("mime"), videoProfile = it.label("profile"))
                }
                "opencinecam-audio-sidecar-v1" -> encoding = encoding.copy(audioContainer = obj.label("container"), audioSamples = obj.label("encoding"))
            }
        } catch (_: Exception) { invalid = true }
    }
    if (slates.size > 1 || slates.isNotEmpty() && slateAbsent) invalid = true
    if (documents.isNotEmpty() && known.keys.any { it !in references }) missing = true
    if (!invalid && encoding != LocalMediaEncoding()) onEncoding?.invoke(encoding)
    val status = when {
        invalid -> LocalMediaRelationStatus.INVALID_METADATA
        missing -> LocalMediaRelationStatus.INCOMPLETE
        documents.isEmpty() -> LocalMediaRelationStatus.MISSING_METADATA
        else -> LocalMediaRelationStatus.DECLARED
    }
    return LocalMediaTake(namespace.key, primary.artifact, originals.sortedWith(catalogComparator(false)).map { it.artifact },
        documents.map { it.artifact }, requireNotNull(primary.kind), slates.singleOrNull().takeUnless { invalid }, status)
}
/** A short string value; anything longer is no codec name and is dropped. */
private fun JsonObject.label(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString && it.content.length <= 32 }?.content
private val recordingSchemas = setOf("opencinecam-audio-sidecar-v1", "opencinecam.recording.v1",
    "opencinecam.timecode.v1", "opencinecam.av-timing.v1", "opencinecam.recording-color.v1",
    "opencinecam.recording-audio.v1", "opencinecam.recording-timing.v1", "opencinecam-oclog-sidecar-v2",
    "opencinecam.timelapse-timing.v1")
internal fun jsonNestingWithinBound(text: String): Boolean {
    var depth = 0; var quoted = false; var escaped = false
    for (char in text) {
        if (quoted) {
            if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
        } else when (char) {
            '"' -> quoted = true
            '{', '[' -> { depth++; if (depth > 32) return false }
            '}', ']' -> { depth--; if (depth < 0) return false }
        }
    }
    return depth == 0 && !quoted
}

/** JSON's tree parser keeps only the final duplicate key. Check each object's original tokens
 * first, decoding key strings so \u escapes cannot conceal an ambiguous identity or slate.
 * Full value/grammar validation remains with Json; arrays and sibling objects have distinct scopes. */
internal fun jsonObjectKeysUnique(text: String): Boolean {
    val scopes = mutableListOf<MutableSet<String>?>()
    var index = 0
    while (index < text.length) {
        when (text[index]) {
            '{' -> { scopes += mutableSetOf<String>(); if (scopes.size > 32) return false; index++ }
            '[' -> { scopes.add(null); if (scopes.size > 32) return false; index++ }
            '}', ']' -> { if (scopes.isEmpty()) return false; scopes.removeAt(scopes.lastIndex); index++ }
            '"' -> {
                val start = index++
                var closed = false
                while (index < text.length) {
                    val char = text[index++]
                    if (char == '\\') { if (index == text.length) return false; index++ }
                    else if (char == '"') { closed = true; break }
                }
                if (!closed) return false
                var next = index
                while (next < text.length && text[next] in " \t\r\n") next++
                if (next < text.length && text[next] == ':') {
                    val keys = scopes.lastOrNull() ?: return false
                    val key = try {
                        (Json.parseToJsonElement(text.substring(start, index)) as JsonPrimitive).content
                    } catch (_: Exception) { return false }
                    if (!keys.add(key)) return false
                }
            }
            else -> index++
        }
    }
    return scopes.isEmpty()
}
