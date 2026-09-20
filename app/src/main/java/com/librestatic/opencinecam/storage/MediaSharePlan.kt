/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import com.librestatic.opencinecam.MediaSharingSettings
import com.librestatic.opencinecam.MediaShareContent
import com.librestatic.opencinecam.MediaShareMetadata
import com.librestatic.opencinecam.productionSlateJson
import kotlinx.serialization.json.*
import java.util.concurrent.CancellationException
import java.text.Normalizer
import java.util.Locale

enum class MediaShareRole { ORIGINAL, RELATIONSHIP, PRODUCTION, TECHNICAL, LUT }
data class PreparedMediaShareFile(val uri: String, val name: String, val mimeType: String, val role: MediaShareRole)
internal data class MediaShareSnapshot(val take: LocalMediaTake, val documents: List<MetadataDocument>)
internal data class MediaShareText(val name: String, val role: MediaShareRole, val text: String)
internal data class MediaSharePlan(val take: LocalMediaTake, val settings: MediaSharingSettings,
    val originals: List<LocalMediaArtifact>, val generated: List<MediaShareText>, val referencedLuts: List<String>)
internal data class MediaShareLut(val hash: String, val name: String?, val bytes: Long?, val included: Boolean)
internal const val MEDIA_SHARE_EXPIRY_MS = 24 * 60 * 60 * 1000L
internal const val MEDIA_SHARE_MAX_LUTS = 8
internal const val MEDIA_SHARE_MAX_LUT_BYTES = 16 * 1024 * 1024
internal const val MEDIA_SHARE_MAX_CACHE_BYTES = 256 * 1024 * 1024L
internal const val MEDIA_SHARE_MAX_SESSIONS = 128

internal fun checkShareRunning() {
    if (Thread.currentThread().isInterrupted) throw CancellationException("Media share preparation interrupted")
}
internal fun mediaOriginalIdentity(uri: String): Pair<LocalMediaKind, Long>? {
    val match = Regex("content://media/external_primary/(images|video|audio)/media/([1-9][0-9]*)").matchEntire(uri) ?: return null
    val id = match.groupValues[2].toLongOrNull() ?: return null
    val kind = when (match.groupValues[1]) { "images" -> LocalMediaKind.PHOTO; "video" -> LocalMediaKind.VIDEO; else -> LocalMediaKind.AUDIO }
    return kind to id
}
internal fun requireUnchangedShareTake(selected: LocalMediaTake, fresh: LocalMediaTake) {
    check(selected == fresh) { "Selected take changed; refresh gallery before sharing" }
}
internal fun mediaSharePlan(snapshot: MediaShareSnapshot, settings: MediaSharingSettings): MediaSharePlan {
    val take = snapshot.take
    require(take.originals.size in 1..32 && take.metadata.size <= 4)
    require(take.primary in take.originals && take.originals.map { it.uri }.distinct().size == take.originals.size)
    require(take.originals.all { mediaOriginalIdentity(it.uri) != null && it.sizeBytes >= 0 })
    val originals = if (settings.content == MediaShareContent.METADATA_ONLY) emptyList() else take.originals
    val generated = mutableListOf<MediaShareText>()
    val hashes = linkedSetOf<String>()
    if (settings.content != MediaShareContent.ORIGINALS_ONLY) {
        check(take.relationStatus == LocalMediaRelationStatus.DECLARED) { "Declared consistent metadata required; choose originals only" }
        check(snapshot.documents.isNotEmpty() && snapshot.documents.map { it.artifact } == take.metadata)
        val sources = snapshot.documents.map { document ->
            val text = requireNotNull(document.text) { "Metadata became unavailable" }
            require(text.toByteArray(Charsets.UTF_8).size <= 512 * 1024 && jsonNestingWithinBound(text) && jsonObjectKeysUnique(text))
            requireNotNull(Json.parseToJsonElement(text) as? JsonObject)
        }
        val id = take.id.substringAfter(':')
        require(canonicalMediaId(id) && take.id.substringBefore(':') in setOf("take", "still"))
        val namespace = MediaNamespace(id, take.id.startsWith("take:"))
        val rows = take.originals.map { artifact ->
            val identity = requireNotNull(mediaOriginalIdentity(artifact.uri))
            CatalogRow(artifact, identity.second, identity.first, namespace)
        }
        check(catalogTake(namespace, rows, snapshot.documents) == take) { "Share source declarations changed" }
        if (settings.metadata != MediaShareMetadata.TECHNICAL) {
            val slate = take.slate
            val locations = sources.flatMapIndexed { index, source ->
                sourceCaptureLocations(source).map { (pointer, location) -> buildJsonObject {
                    put("source", mediaIdentity(snapshot.documents[index].artifact))
                    put("pointer", pointer); put("captureLocation", location)
                } }
            }
            require(slate != null || locations.isNotEmpty()) { "Production metadata is absent; choose technical metadata or originals only" }
            generated += MediaShareText("take.production.json", MediaShareRole.PRODUCTION, buildJsonObject {
                put("schema", "opencinecam.share.production.v1"); put("takeId", take.id)
                put("files", mediaIdentityList(take.originals)); slate?.let { put("productionSlate", productionSlateJson(it)) }
                if (locations.isNotEmpty()) put("captureLocations", JsonArray(locations))
            }.toString())
        }
        if (settings.metadata != MediaShareMetadata.PRODUCTION) {
            val technicalSources = sources.map(::stripProductionSlate)
            technicalSources.forEach { collectRecordingLutHashes(it, hashes) }
            require(hashes.size <= MEDIA_SHARE_MAX_LUTS) { "Too many referenced recording LUTs" }
            generated += MediaShareText("take.technical.json", MediaShareRole.TECHNICAL, buildJsonObject {
                put("schema", "opencinecam.share.technical.v1"); put("takeId", take.id)
                put("files", mediaIdentityList(take.originals))
                put("sources", buildJsonArray { technicalSources.forEachIndexed { index, value -> add(buildJsonObject {
                    put("source", mediaIdentity(snapshot.documents[index].artifact))
                    put("metadata", value)
                }) } })
            }.toString())
        }
    }
    requirePortableShareNames(originals.map { it.name } + generated.map { it.name } + "relationships.json" +
        if (settings.includeReferencedLut) hashes.map { "$it.cube" } else emptyList())
    return MediaSharePlan(take, settings, originals.toList(), generated.toList(), hashes.toList())
}
internal fun stripProductionSlate(value: JsonElement): JsonElement = when (value) {
    is JsonObject -> JsonObject(value.filterKeys { it != "productionSlate" && it != "captureLocation" }.mapValues { stripProductionSlate(it.value) })
    is JsonArray -> JsonArray(value.map(::stripProductionSlate))
    else -> value
}
private fun collectRecordingLutHashes(value: JsonElement, hashes: MutableSet<String>) {
    when (value) {
        is JsonObject -> value.forEach { (key, child) ->
            if (key == "recordingLut") {
                val declaration = child as? JsonObject ?: error("Invalid recording LUT declaration")
                val hash = (declaration["originalCubeSha256"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                require(hash != null && hash.matches(Regex("[0-9a-f]{64}"))) { "Invalid recording LUT identity" }
                require((declaration["baked"] as? JsonPrimitive)?.let { !it.isString && it.booleanOrNull == true } == true)
                require((declaration["reapplyInEditor"] as? JsonPrimitive)?.let { !it.isString && it.booleanOrNull == false } == true)
                hashes += hash
                require(hashes.size <= MEDIA_SHARE_MAX_LUTS)
            }
            collectRecordingLutHashes(child, hashes)
        }
        is JsonArray -> value.forEach { collectRecordingLutHashes(it, hashes) }
        else -> Unit
    }
}
internal fun mediaShareManifest(plan: MediaSharePlan, luts: List<MediaShareLut>): String = buildJsonObject {
    put("schema", "opencinecam.share.relationships.v1"); put("takeId", plan.take.id)
    put("relationStatus", plan.take.relationStatus.name)
    put("preparation", "PREPARED_NOT_DELIVERY_RECEIPT")
    put("originalHashIntegrity", "NOT_CHECKED")
    put("relationshipManifest", buildJsonObject {
        put("file", "relationships.json"); put("role", MediaShareRole.RELATIONSHIP.name); put("included", true)
    })
    put("content", plan.settings.content.name); put("metadataSelection", plan.settings.metadata.name)
    put("originals", buildJsonArray { plan.take.originals.forEach { artifact -> add(buildJsonObject {
        put("file", mediaIdentity(artifact)); put("role", MediaShareRole.ORIGINAL.name)
        put("included", artifact in plan.originals)
    }) } })
    put("sourceMetadata", buildJsonArray { plan.take.metadata.forEach { artifact -> add(buildJsonObject {
        put("file", mediaIdentity(artifact)); put("includedRaw", false)
        put("reason", "EDITORIAL_AND_TECHNICAL_CONTENT_EXPORTED_SEPARATELY")
    }) } })
    put("derivedMetadata", buildJsonArray { listOf(MediaShareRole.PRODUCTION, MediaShareRole.TECHNICAL).forEach { role ->
        val file = plan.generated.singleOrNull { it.role == role }
        add(buildJsonObject { put("role", role.name); put("included", file != null); file?.let { put("file", it.name) } })
    } })
    put("recordingLuts", buildJsonArray { luts.forEach { lut -> add(buildJsonObject {
        put("role", MediaShareRole.LUT.name); put("originalCubeSha256", lut.hash); put("included", lut.included)
        put("baked", true); put("reapplyInEditor", false)
        if (lut.included) { put("file", requireNotNull(lut.name)); put("bytes", requireNotNull(lut.bytes)); put("sha256Verified", true) }
        else put("omission", "REFERENCED_LUT_EXPORT_DISABLED")
    }) } })
    put("referencedLutPolicy", if (plan.settings.content == MediaShareContent.ORIGINALS_ONLY || plan.settings.metadata == MediaShareMetadata.PRODUCTION)
        "NOT_INSPECTED_WITHOUT_TECHNICAL_EXPORT" else if (plan.settings.includeReferencedLut) "INCLUDE_EXACT_REFERENCED_ORIGINALS" else "EXPLICITLY_OMITTED")
}.toString()
private fun mediaIdentityList(artifacts: List<LocalMediaArtifact>): JsonArray = JsonArray(artifacts.map(::mediaIdentity))
private fun mediaIdentity(artifact: LocalMediaArtifact): JsonObject = buildJsonObject {
    put("uri", artifact.uri); put("filename", artifact.name); put("mimeType", artifact.mimeType)
    put("observedSizeBytes", artifact.sizeBytes); put("modifiedSeconds", artifact.modifiedSeconds)
}
internal fun mediaShareSessionExpired(name: String, modifiedMs: Long, nowMs: Long): Boolean =
    canonicalMediaId(name) && modifiedMs > 0 && nowMs >= modifiedMs && nowMs - modifiedMs >= MEDIA_SHARE_EXPIRY_MS

/** Validate only delivered names. Never rename originals or let two recipients' filenames alias. */
internal fun requirePortableShareNames(names: List<String>) {
    val seen = mutableSetOf<String>()
    for (name in names) {
        require(name.isNotBlank() && name.length <= 255 && name !in setOf(".", "..") &&
            !name.endsWith('.') && !name.endsWith(' ')) { "Export filename is not portable" }
        require(name.none { it in "<>:\"/\\|?*" || Character.isISOControl(it) ||
            Character.getType(it) in setOf(Character.FORMAT.toInt(), Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt()) }) {
            "Export filename contains path or control characters"
        }
        val key = Normalizer.normalize(name, Normalizer.Form.NFC).lowercase(Locale.ROOT)
        require(seen.add(key)) { "Ambiguous export filenames; originals were not renamed" }
    }
}

/** Production declarations stay associated with their exact metadata source and JSON pointer.
 * Validation never imports a source fix into live settings/state, and technical export never
 * needs to parse coordinates: its recursive redaction removes the entire declaration. */
private fun sourceCaptureLocations(value: JsonElement, pointer: String = ""): List<Pair<String, JsonObject>> = buildList {
    when (value) {
        is JsonObject -> value.forEach { (key, child) ->
            val path = pointer + "/" + key.replace("~", "~0").replace("/", "~1")
            if (key == "captureLocation") add(path to validatedShareCaptureLocation(child))
            else addAll(sourceCaptureLocations(child, path))
        }
        is JsonArray -> value.forEachIndexed { index, child -> addAll(sourceCaptureLocations(child, "$pointer/$index")) }
        else -> Unit
    }
}
private fun validatedShareCaptureLocation(value: JsonElement): JsonObject {
    val declaration = requireNotNull(value as? JsonObject) { "Invalid capture location declaration" }
    fun string(key: String): String = requireNotNull((declaration[key] as? JsonPrimitive)?.takeIf { it.isString }?.content) {
        "Invalid capture location $key"
    }
    fun number(key: String): Double = requireNotNull((declaration[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull)
        .also { require(it.isFinite()) { "Invalid capture location $key" } }
    fun integer(key: String): Long = requireNotNull((declaration[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull)
    require(string("schema") == "opencinecam.capture-location.v1") { "Unknown capture location schema" }
    val status = string("status")
    require(status in setOf("AVAILABLE", "NO_PERMISSION", "INACTIVE", "UNAVAILABLE", "STALE")) { "Unknown capture location status" }
    val base = setOf("schema", "status")
    if (status == "AVAILABLE") {
        require(declaration.keys == base + setOf("latitude", "longitude", "accuracyMeters", "epochMillis", "permissionPrecision", "ageMillis")) {
            "Capture location fix fields are incomplete or unknown"
        }
        require(number("latitude") in -90.0..90.0 && number("longitude") in -180.0..180.0)
        require(number("accuracyMeters") in 0.0..Float.MAX_VALUE.toDouble())
        require(integer("epochMillis") > 0 && integer("ageMillis") in 0..120_000)
        require(string("permissionPrecision") in setOf("APPROXIMATE", "PRECISE"))
    } else require(declaration.keys == base) { "Unavailable capture location must not expose coordinates" }
    return declaration
}
