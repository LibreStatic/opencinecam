/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import com.librestatic.opencinecam.GallerySettings
import com.librestatic.opencinecam.GalleryMediaKind
import com.librestatic.opencinecam.ProductionSlateSettings
import com.librestatic.opencinecam.parseProductionSlateJson
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.json.*

/** [RAW_VIDEO] is an OpenCine RAW take (PLAN-071): one `.ocraw` file in MediaStore.Files, never grouped; it plays as a developed preview in the review (PLAN-071 §7). */
enum class LocalMediaKind { PHOTO, VIDEO, AUDIO, RAW_VIDEO }
enum class LocalMediaRelationStatus { DECLARED, LEGACY, MISSING_METADATA, INVALID_METADATA, INCOMPLETE }
data class LocalMediaArtifact(val uri: String, val name: String, val mimeType: String,
    val sizeBytes: Long, val modifiedSeconds: Long)
data class LocalMediaTake(val id: String, val primary: LocalMediaArtifact,
    val originals: List<LocalMediaArtifact>, val metadata: List<LocalMediaArtifact>,
    val kind: LocalMediaKind, val slate: ProductionSlateSettings?, val relationStatus: LocalMediaRelationStatus,
    /** Files owned by the take that are never relationship metadata: its Gyroflow `.gcsv` log and its `.clip.json` sidecar. */
    val attachments: List<LocalMediaArtifact> = emptyList())
/** Codec facts a take's sidecars declare. They stay out of [LocalMediaTake], whose equality is the
 * identity check behind sharing, renaming and proxy jobs. */
data class LocalMediaEncoding(val videoMime: String? = null, val videoProfile: String? = null,
    val audioContainer: String? = null, val audioSamples: String? = null,
    /** Stable failure reasons of a take whose sidecar says `takeStatus: incomplete` (ADR-0011); empty otherwise. */
    val incompleteReasons: List<String> = emptyList())
/** [encodings] holds, by take id, what the page's sidecars declared; takes without one are absent. */
data class LocalMediaPage(val takes: List<LocalMediaTake>, val next: LocalMediaCursor?,
    val encodings: Map<String, LocalMediaEncoding> = emptyMap())

/**
 * Opaque, constant-size keyset state. No accumulated take IDs or offset-based pagination. [drive] is the
 * last drive take consumed (PLAN-072); [driveExhausted] says the drive has nothing left for this filter.
 */
class LocalMediaCursor internal constructor(internal val filter: String,
    internal val positions: Map<LocalMediaKind, MediaPosition>, internal val exhausted: Set<LocalMediaKind>,
    internal val drive: DrivePosition? = null, internal val driveExhausted: Boolean = false)
internal data class MediaPosition(val modified: Long, val id: Long)
/**
 * [driveFolder] is the document id of a drive take's folder (PLAN-072), null for MediaStore namespaces. A drive
 * take is keyed by its sidecars' `bundleId` like a phone take; one whose sidecars declare none has an empty [id]
 * and is keyed by its folder instead.
 */
internal data class MediaNamespace(val id: String, val recording: Boolean, val driveFolder: String? = null) {
    val key: String get() = when {
        driveFolder != null && id.isEmpty() -> "drive:$driveFolder"
        recording -> "take:$id"
        else -> "still:$id"
    }
    fun path(root: String): String = "$root/OpenCineCam/${if (recording) "OCC_TAKE_" else "OCC_"}$id/"
}
internal data class CatalogRow(val artifact: LocalMediaArtifact, val id: Long,
    val kind: LocalMediaKind?, val namespace: MediaNamespace?, val eligible: Boolean = true)
/** A take attachment is a plain-text motion log in the take's Download namespace, never a JSON sidecar. */
internal fun isMotionLogAttachment(artifact: LocalMediaArtifact): Boolean =
    artifact.name.endsWith("." + GyroflowLog.EXTENSION, ignoreCase = true) && artifact.mimeType != "application/json"
/**
 * The canonical `<stem>.clip.json` sidecar. It is carried as an opaque take attachment: it declares no
 * `schema`/`videoUri` relationship, so it is neither relationship metadata (which the catalog validates and the
 * proxy writer counts as exactly two documents) nor rewritten when the take is renamed.
 */
internal fun isClipSidecarAttachment(artifact: LocalMediaArtifact): Boolean =
    artifact.name.endsWith(CLIP_SIDECAR_SUFFIX, ignoreCase = true) && artifact.mimeType == "application/json"
internal fun isTakeAttachment(artifact: LocalMediaArtifact): Boolean =
    isMotionLogAttachment(artifact) || isClipSidecarAttachment(artifact)
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
internal fun isCatalogPath(path: String): Boolean = !isCinemaDngExportPath(path) &&
    (path in setOf("DCIM/OpenCineCam/", "Music/OpenCineCam/", "Download/OpenCineCam/", RAW_VIDEO_PATH) || mediaNamespace(path) != null)

/** Where the in-app CinemaDNG export writes its take folders (PLAN-071 §8); the gallery never lists them. */
internal const val CINEMADNG_EXPORT_PATH = "Documents/OpenCineCam/CinemaDNG/"

/** True for the export folder and everything under it, whatever the case MediaStore reports it in. */
internal fun isCinemaDngExportPath(path: String): Boolean = path.startsWith(CINEMADNG_EXPORT_PATH, ignoreCase = true)

/** Where RAW takes live (PLAN-071 decision 7); mirrors `RawVideoOutput.RELATIVE_PATH`. */
internal const val RAW_VIDEO_PATH = "Documents/OpenCineCam/RAW/"
internal const val RAW_VIDEO_EXTENSION = ".ocraw"

/** A Files row the gallery lists as a RAW take: an `.ocraw` directly in [RAW_VIDEO_PATH]. Ownership and pending are checked by the caller. */
internal fun isRawVideoRow(path: String, name: String): Boolean =
    path == RAW_VIDEO_PATH && name.length > RAW_VIDEO_EXTENSION.length && name.endsWith(RAW_VIDEO_EXTENSION, ignoreCase = true)

/** A RAW take is its one file: no sidecar, no grouping, the relationship of a legacy file. */
internal fun rawVideoTake(artifact: LocalMediaArtifact): LocalMediaTake =
    LocalMediaTake("raw:${artifact.uri}", artifact, listOf(artifact), emptyList(), LocalMediaKind.RAW_VIDEO, null, LocalMediaRelationStatus.LEGACY)
internal fun catalogFilter(settings: GallerySettings, query: String): String =
    "${settings.kind}|${settings.newestFirst}|${settings.goodTakesOnly}|${query.trim().lowercase(Locale.ROOT)}"
internal fun catalogComparator(newestFirst: Boolean): Comparator<CatalogRow> {
    val date = if (newestFirst) compareByDescending<CatalogRow> { it.artifact.modifiedSeconds }
        else compareBy { it.artifact.modifiedSeconds }
    return date.thenBy { it.kind?.ordinal ?: LocalMediaKind.entries.size }.thenBy { it.id }
}
internal fun catalogPrimary(rows: List<CatalogRow>): CatalogRow? =
    rows.filter { it.kind == LocalMediaKind.VIDEO }.minByOrNull { it.id }
        ?: rows.filter { it.kind == LocalMediaKind.PHOTO }.minByOrNull { it.id }
        ?: rows.filter { it.kind == LocalMediaKind.AUDIO }.minByOrNull { it.id }
internal fun catalogMatches(take: LocalMediaTake, settings: GallerySettings, query: String): Boolean {
    // A RAW take is a video to the gallery filter.
    val kindName = if (take.kind == LocalMediaKind.RAW_VIDEO) LocalMediaKind.VIDEO.name else take.kind.name
    if (settings.kind != GalleryMediaKind.ALL && settings.kind.name != kindName) return false
    if (settings.goodTakesOnly && take.slate?.goodTake != true) return false
    val needle = query.trim().lowercase(Locale.ROOT)
    if (needle.isEmpty()) return true
    val slate = take.slate
    val fields = take.originals.map { it.name } + take.metadata.map { it.name } + take.attachments.map { it.name } + listOf(take.id) +
        (slate?.let { listOf(it.project, it.camera, it.scene, it.reel, it.lens, it.takeNumber.toString()) } ?: emptyList())
    return fields.any { it.lowercase(Locale.ROOT).contains(needle) }
}

/** MediaStore rows read per kind and page; a shorter batch, wholly consumed, exhausts its kind. */
internal const val CATALOG_CANDIDATE_BATCH = 64
/** MediaStore candidates examined per page; drive takes, already listed, do not count against it. */
internal const val CATALOG_SCAN_BUDGET = 120
/**
 * Drive takes loaded per page (PLAN-072): each load lists a take folder and reads its sidecars over USB, so a filter
 * that matches nothing must not read the whole drive in one page. Spent, the page ends where the drive stopped.
 */
internal const val CATALOG_DRIVE_LOAD_BUDGET = 100

/**
 * One gallery page: the MediaStore candidates of every kind and the drive's takes (PLAN-072), merged by
 * modified time. A drive take comes out just before the first MediaStore candidate it is newer than (older
 * than, oldest first), so at an equal second the phone's comes first; drive takes past the last candidate
 * read wait for the next page, unless MediaStore is exhausted. At most [CATALOG_DRIVE_LOAD_BUDGET] drive takes
 * are loaded per page, so a page may end short, even empty, with a cursor. [candidates], [drive] and [readTake] do the
 * IO; [blocked] reads the recovery journals and runs after the candidates are collected.
 */
internal fun catalogPage(settings: GallerySettings, query: String, cursor: LocalMediaCursor?, limit: Int,
    candidates: (LocalMediaKind, MediaPosition?) -> List<CatalogRow>,
    drive: () -> List<DriveCatalogEntry>,
    blocked: () -> Set<String>,
    readTake: (MediaNamespace, CatalogRow, (LocalMediaEncoding) -> Unit) -> LocalMediaTake?): LocalMediaPage {
    val filter = catalogFilter(settings, query)
    require(cursor == null || cursor.filter == filter) { "Gallery cursor belongs to a different filter" }
    val positions = cursor?.positions.orEmpty().toMutableMap()
    val exhausted = cursor?.exhausted.orEmpty().toMutableSet()
    // Collect provider candidates BEFORE observing committed journal state. No writer lock is
    // held while doing provider/metadata IO; the durable SQLite receipt snapshot is sufficient.
    val batches = LocalMediaKind.entries.filter { it !in exhausted }.associateWith { kind -> candidates(kind, positions[kind]) }
    var drivePosition = cursor?.drive
    val pendingDrive = ArrayDeque(if (cursor?.driveExhausted == true) emptyList() else drive().after(drivePosition, settings.newestFirst))
    val blockedKeys = blocked()
    val ordered = batches.values.flatten().sortedWith(catalogComparator(settings.newestFirst))
    val consumed = mutableMapOf<LocalMediaKind, Int>()
    val groups = mutableMapOf<MediaNamespace, LocalMediaTake?>()
    val encodings = mutableMapOf<String, LocalMediaEncoding>()
    val result = mutableListOf<LocalMediaTake>()
    var driveLoads = 0
    /**
     * Emits the drive takes ahead of a candidate modified at [seconds] (all of them when null); true once the page is
     * full or the drive's load budget is spent with a take still due, which ends the page before that candidate.
     */
    fun drainDrive(seconds: Long?): Boolean {
        while (result.size < limit && pendingDrive.isNotEmpty() &&
            (seconds == null || pendingDrive.first().position.ahead(seconds, settings.newestFirst))) {
            if (driveLoads == CATALOG_DRIVE_LOAD_BUDGET) return true
            driveLoads++
            val entry = pendingDrive.removeFirst()
            drivePosition = entry.position
            val found = entry.load() ?: continue
            if (found.take.id in blockedKeys || !catalogMatches(found.take, settings, query)) continue
            result += found.take
            found.encoding?.let { encodings[found.take.id] = it }
        }
        return result.size == limit
    }
    var full = false
    for (candidate in ordered.take(CATALOG_SCAN_BUDGET)) {
        if (drainDrive(candidate.artifact.modifiedSeconds)) { full = true; break }
        val kind = requireNotNull(candidate.kind)
        positions[kind] = MediaPosition(candidate.artifact.modifiedSeconds, candidate.id)
        consumed[kind] = (consumed[kind] ?: 0) + 1
        if (!candidate.eligible || candidate.namespace?.key in blockedKeys) continue
        val take = candidate.namespace?.let { namespace ->
            if (!groups.containsKey(namespace)) groups[namespace] = readTake(namespace, candidate) { encodings[namespace.key] = it }
            groups[namespace]
        } ?: if (kind == LocalMediaKind.RAW_VIDEO) rawVideoTake(candidate.artifact)
        else if (candidate.namespace == null) LocalMediaTake(
            "legacy:${candidate.artifact.uri}", candidate.artifact, listOf(candidate.artifact), emptyList(),
            kind, null, LocalMediaRelationStatus.LEGACY,
        ) else null
        // Only the durable primary's candidate may emit the group. Secondary members are
        // consumed without emitting, so keyset pages never need an ever-growing seen-ID set.
        if (take != null && take.primary.uri == candidate.artifact.uri && catalogMatches(take, settings, query)) result += take
        if (result.size == limit) { full = true; break }
    }
    batches.forEach { (kind, batch) ->
        if (batch.size < CATALOG_CANDIDATE_BATCH && (consumed[kind] ?: 0) == batch.size) exhausted += kind
    }
    val mediaStoreDone = exhausted.size == LocalMediaKind.entries.size
    if (!full && mediaStoreDone) drainDrive(null)
    val driveExhausted = pendingDrive.isEmpty()
    val next = if (mediaStoreDone && driveExhausted) null
        else LocalMediaCursor(filter, positions.toMap(), exhausted.toSet(), drivePosition, driveExhausted)
    val ids = result.mapTo(HashSet()) { it.id }
    return LocalMediaPage(result.toList(), next, encodings.filterKeys { it in ids })
}

/** Validates declarations against available owned namespace members, not filename similarity.
 * DECLARED means relationship identity was checked, never that content hashes were verified.
 * [onEncoding] receives the codec facts of a valid take, read from the same parse. */
internal fun catalogTake(namespace: MediaNamespace, members: List<CatalogRow>,
    documents: List<MetadataDocument>, incomplete: Boolean = false,
    attachments: List<LocalMediaArtifact> = emptyList(),
    onEncoding: ((LocalMediaEncoding) -> Unit)? = null): LocalMediaTake? {
    val originals = members.filter { it.kind != null }
    val primary = catalogPrimary(originals) ?: return null
    // A drive take's references are document URIs inside its own folder, compared by document id so a
    // re-picked tree over the same folder still matches; a phone take's stay exact MediaStore URIs.
    val drive = namespace.driveFolder
    fun referenceKey(uri: String): String =
        if (drive == null) uri else requireNotNull(driveMemberDocumentId(uri, drive)) { "Reference outside the take folder" }
    val known = originals.associateBy { if (drive == null) it.artifact.uri else driveMemberDocumentId(it.artifact.uri, drive) ?: it.artifact.uri }
    var invalid = false
    var missing = incomplete
    val references = mutableSetOf<String>()
    val slates = mutableSetOf<ProductionSlateSettings>()
    var slateAbsent = false
    var encoding = LocalMediaEncoding()
    var markedIncomplete = false
    val incompleteReasons = linkedSetOf<String>()
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
                if (drive == null) require(uri.matches(Regex("content://media/external_primary/(images/media|video/media|audio/media)/[1-9][0-9]*")))
                declared += referenceKey(uri) to details
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
                if (drive == null) {
                    val segment = when (expectedKind) { LocalMediaKind.PHOTO -> "/images/media/"; LocalMediaKind.VIDEO -> "/video/media/"; LocalMediaKind.AUDIO -> "/audio/media/"; LocalMediaKind.RAW_VIDEO -> error("RAW takes declare no sidecar") }
                    require(segment in uri)
                }
                val existing = known[uri]
                if (existing == null) missing = true else {
                    require(existing.namespace == namespace && existing.kind == expectedKind)
                    details?.get("displayName")?.let { require((it as? JsonPrimitive)?.let { v -> v.isString && v.content == existing.artifact.name } == true) }
                    // A drive reports the MIME its provider derives from the name, which the name check already binds.
                    if (drive == null) details?.get("mimeType")?.let { require((it as? JsonPrimitive)?.let { v -> v.isString && v.content == existing.artifact.mimeType } == true) }
                    details?.get("bytes")?.let { require((it as? JsonPrimitive)?.let { v -> !v.isString && v.longOrNull == existing.artifact.sizeBytes } == true) }
                }
            }
            // ADR-0011: a take kept after a failure says so in its sidecar. It surfaces as INCOMPLETE and is
            // never an authority to delete anything (deletion enumerates the provider itself).
            if (!still && (obj[IncompleteTake.STATUS_KEY] as? JsonPrimitive)?.takeIf { it.isString }?.content == IncompleteTake.STATUS_INCOMPLETE) {
                markedIncomplete = true
                (obj[IncompleteTake.REASONS_KEY] as? JsonArray)?.take(8)?.forEach { reason ->
                    (reason as? JsonPrimitive)?.takeIf { it.isString && it.content.length in 1..48 }?.let { incompleteReasons += it.content }
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
    if (markedIncomplete) {
        missing = true
        encoding = encoding.copy(incompleteReasons = incompleteReasons.toList())
    }
    if (documents.isNotEmpty() && known.keys.any { it !in references }) missing = true
    if (!invalid && encoding != LocalMediaEncoding()) onEncoding?.invoke(encoding)
    val status = when {
        invalid -> LocalMediaRelationStatus.INVALID_METADATA
        missing -> LocalMediaRelationStatus.INCOMPLETE
        documents.isEmpty() -> LocalMediaRelationStatus.MISSING_METADATA
        else -> LocalMediaRelationStatus.DECLARED
    }
    return LocalMediaTake(namespace.key, primary.artifact, originals.sortedWith(catalogComparator(false)).map { it.artifact },
        documents.map { it.artifact }, requireNotNull(primary.kind), slates.singleOrNull().takeUnless { invalid }, status,
        attachments.sortedBy { it.uri })
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
