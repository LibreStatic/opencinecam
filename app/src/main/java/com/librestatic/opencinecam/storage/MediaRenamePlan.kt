/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import java.text.Normalizer
import java.util.Locale
import kotlinx.serialization.json.*

data class MediaRenameTarget(val artifact: LocalMediaArtifact, val newName: String)
data class MediaRenamePreview(val stem: String, val files: List<MediaRenameTarget>)
enum class MediaRenameStatus { RENAMED, UNCHANGED, RESTORED, PARTIAL, UNKNOWN, NOT_ATTEMPTED }
data class MediaRenameFileResult(val artifact: LocalMediaArtifact, val requestedName: String,
    val finalName: String?, val status: MediaRenameStatus, val detail: String? = null)
data class MediaRenameResult(val files: List<MediaRenameFileResult>, val error: String? = null,
    val compensationAttempted: Boolean = false, val compensationComplete: Boolean = false, val linkedPartial: Boolean = false) {
    val complete: Boolean get() = !linkedPartial && error == null && files.isNotEmpty() && files.all { it.status in setOf(MediaRenameStatus.RENAMED, MediaRenameStatus.UNCHANGED) }
    val partial: Boolean get() = !complete && !compensationComplete && (linkedPartial || files.any { it.status in setOf(MediaRenameStatus.RENAMED, MediaRenameStatus.PARTIAL, MediaRenameStatus.UNKNOWN) })
}
fun validateMediaRenameStem(stem: String): String {
    require(stem.length in 1..128 && Charsets.UTF_8.newEncoder().canEncode(stem)) { "Rename stem must contain 1 to 128 valid characters" }
    return Normalizer.normalize(stem, Normalizer.Form.NFC).also { requirePortableShareNames(listOf(it)) }
}
fun mediaRenamePreview(take: LocalMediaTake, stem: String): MediaRenamePreview {
    val normalized = validateMediaRenameStem(stem)
    val all = take.originals + take.metadata
    require(take.originals.size in 1..32 && take.metadata.size <= 4 && take.primary in take.originals)
    require(all.map { it.uri }.distinct().size == all.size && all.all { mediaDeleteIdentity(it.uri) != null })
    val names = mutableMapOf(take.primary.uri to normalized + renameExtension(take.primary))
    val counts = mutableMapOf<String, Int>()
    take.originals.filter { it != take.primary }.sortedWith(compareBy<LocalMediaArtifact> {
        requireNotNull(mediaDeleteIdentity(it.uri)).collection.ordinal
    }.thenBy { requireNotNull(mediaDeleteIdentity(it.uri)).id }).forEach { artifact ->
        val kind = requireNotNull(mediaOriginalIdentity(artifact.uri)).first.name.lowercase(Locale.ROOT)
        val count = (counts[kind] ?: 0) + 1; counts[kind] = count
        names[artifact.uri] = "$normalized-$kind-${count.toString().padStart(2, '0')}" + renameExtension(artifact)
    }
    take.metadata.sortedBy { requireNotNull(mediaDeleteIdentity(it.uri)).id }.forEachIndexed { index, artifact ->
        require(artifact.mimeType == "application/json") { "Only known JSON metadata can be renamed coherently" }
        names[artifact.uri] = "$normalized-metadata-${(index + 1).toString().padStart(2, '0')}" + renameExtension(artifact)
    }
    val files = all.map { MediaRenameTarget(it, names.getValue(it.uri)) }
    requirePortableShareNames(files.map { it.newName })
    return MediaRenamePreview(normalized, files)
}
private fun renameExtension(artifact: LocalMediaArtifact): String {
    val extensions = when (artifact.mimeType.lowercase(Locale.ROOT)) {
        "image/jpeg" -> listOf("jpg", "jpeg")
        "image/x-adobe-dng", "image/dng" -> listOf("dng")
        "image/png" -> listOf("png")
        "image/webp" -> listOf("webp")
        "image/heic" -> listOf("heic")
        "image/heif" -> listOf("heif")
        "image/avif" -> listOf("avif")
        "video/mp4" -> listOf("mp4")
        "audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave" -> listOf("wav")
        "audio/flac", "audio/x-flac" -> listOf("flac")
        "application/json" -> listOf("json")
        else -> error("Unsupported original MIME extension")
    }
    val existing = artifact.name.substringAfterLast('.', "")
    return "." + if (existing.lowercase(Locale.ROOT) in extensions) existing else extensions.first()
}
internal data class MediaRenameMetadata(val artifact: LocalMediaArtifact, val before: ByteArray, val after: ByteArray)
internal data class MediaRenamePlan(val selected: LocalMediaTake, val preview: MediaRenamePreview,
    val rows: List<MediaDeleteRow>, val metadata: List<MediaRenameMetadata>) {
    val changed: Boolean get() = preview.files.any { it.newName != it.artifact.name } || metadata.any { !it.before.contentEquals(it.after) }
}
internal fun mediaRenamePlan(selected: LocalMediaTake, preview: MediaRenamePreview,
    rows: List<MediaDeleteRow>, documents: List<MetadataDocument>): MediaRenamePlan {
    require(preview == mediaRenamePreview(selected, preview.stem))
    val scope = mediaDeletePlan(selected, rows, rows.first().owner)
    require(selected.relationStatus in setOf(LocalMediaRelationStatus.DECLARED, LocalMediaRelationStatus.LEGACY, LocalMediaRelationStatus.MISSING_METADATA)) {
        "Rename requires known coherent metadata or an observed take without metadata"
    }
    require(documents.map { it.artifact }.toSet() == selected.metadata.toSet() && documents.size == selected.metadata.size)
    val names = preview.files.associate { it.artifact.uri to it.newName }
    val originals = selected.originals.associateBy { it.uri }
    val metadata = documents.map { document ->
        val text = requireNotNull(document.text) { "Rename metadata backup unavailable" }
        val before = text.toByteArray(Charsets.UTF_8)
        require(!document.disappeared && before.size in 1..512 * 1024 && jsonNestingWithinBound(text) && jsonObjectKeysUnique(text))
        val value = Json.parseToJsonElement(text) as? JsonObject ?: error("Rename metadata object required")
        fun reference(item: JsonObject, uriKey: String): JsonObject {
            val uri = (item[uriKey] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Metadata URI required")
            val original = originals[uri] ?: error("Metadata refers to an unavailable original")
            return JsonObject(item.mapValues { (key, child) ->
                if (key in setOf("filename", "displayName", "file")) {
                    require((child as? JsonPrimitive)?.let { it.isString && it.content == original.name } == true) { "Ambiguous filename declaration" }
                    JsonPrimitive(names.getValue(uri))
                } else child
            })
        }
        val rewritten = if (scope.namespace?.recording == true) {
            val audio = (value["schema"] as? JsonPrimitive)?.content == "opencinecam-audio-sidecar-v1"
            reference(value, if (audio) "audioUri" else "videoUri")
        } else {
            when {
                value["image"] != null -> JsonObject(value + ("image" to reference(value["image"] as? JsonObject ?: error("Image required"), "uri")))
                else -> {
                    val key = if (value["images"] != null) "images" else "frames"
                    val images = value[key] as? JsonArray ?: error("Image references required")
                    require(images.size in 1..16)
                    JsonObject(value + (key to JsonArray(images.map { reference(it as? JsonObject ?: error("Image required"), "uri") })))
                }
            }
        }
        val after = if (rewritten == value) before else rewritten.toString().toByteArray(Charsets.UTF_8)
        require(after.size in 1..512 * 1024)
        MediaRenameMetadata(document.artifact, before, after)
    }
    if (scope.namespace != null) {
        val sourceRows = rows.filter { it.artifact in selected.originals }.map { row ->
            val identity = requireNotNull(mediaOriginalIdentity(row.artifact.uri))
            CatalogRow(row.artifact, identity.second, identity.first, scope.namespace)
        }
        check(catalogTake(scope.namespace, sourceRows, documents) == selected) { "Rename metadata is corrupt, unknown or changed" }
        val rewrittenRows = sourceRows.map { it.copy(artifact = it.artifact.copy(name = names.getValue(it.artifact.uri))) }
        val rewrittenDocs = metadata.map { change -> MetadataDocument(change.artifact.copy(name = names.getValue(change.artifact.uri), sizeBytes = change.after.size.toLong()), change.after.toString(Charsets.UTF_8)) }
        val after = requireNotNull(catalogTake(scope.namespace, rewrittenRows, rewrittenDocs))
        check(after.slate == selected.slate && after.relationStatus == selected.relationStatus) { "Rename would damage declared relationships" }
    } else require(documents.isEmpty())
    return MediaRenamePlan(selected, preview, rows.toList(), metadata)
}
internal interface MediaRenameAccess {
    fun reserve(): AutoCloseable
    fun preflight(selected: LocalMediaTake, preview: MediaRenamePreview): MediaRenamePlan
    fun checkDestinations(rows: List<MediaDeleteRow>, names: Map<String, String>)
    fun holdOutbox(plan: MediaRenamePlan)
    fun observe(row: MediaDeleteRow): MediaDeleteRow
    fun rename(row: MediaDeleteRow, newName: String): MediaDeleteRow
    fun readMetadata(row: MediaDeleteRow): ByteArray
    fun writeMetadata(row: MediaDeleteRow, expected: ByteArray, replacement: ByteArray): MediaDeleteRow
    fun beginSourceMutation(plan: MediaRenamePlan) {}
    fun applyLinked(plan: MediaRenamePlan) {}
    fun restoreLinked(plan: MediaRenamePlan) {}
    fun verify(plan: MediaRenamePlan, restored: Boolean)
}
internal fun sameRenameScope(original: MediaDeleteRow, current: MediaDeleteRow): Boolean =
    original.artifact.uri == current.artifact.uri && original.relativePath == current.relativePath && original.owner == current.owner &&
        original.artifact.mimeType == current.artifact.mimeType && current.pending == 0 &&
        (mediaOriginalIdentity(original.artifact.uri) == null || original.artifact.sizeBytes == current.artifact.sizeBytes)

internal fun executeMediaRename(selected: LocalMediaTake, preview: MediaRenamePreview, access: MediaRenameAccess): MediaRenameResult {
    val initialResults = preview.files.map { MediaRenameFileResult(it.artifact, it.newName, null, MediaRenameStatus.NOT_ATTEMPTED) }
    var reservation: AutoCloseable? = null
    var plan: MediaRenamePlan? = null
    val witnesses = mutableMapOf<String, MediaDeleteRow>()
    val namesAttempted = linkedSetOf<String>()
    val writesAttempted = linkedSetOf<String>()
    var linkedAttempted = false
    var result: MediaRenameResult
    var cleanupFailure: String? = null
    try {
        reservation = access.reserve()
        val prepared = access.preflight(selected, preview)
        require(prepared.selected == selected && prepared.preview == preview)
        plan = prepared
        witnesses.putAll(prepared.rows.associateBy { it.artifact.uri })
        val names = preview.files.associate { it.artifact.uri to it.newName }
        access.checkDestinations(prepared.rows, names)
        if (prepared.changed) access.holdOutbox(prepared)
        if (prepared.changed) access.beginSourceMutation(prepared)
        for (target in preview.files) {
            val row = witnesses.getValue(target.artifact.uri)
            if (row.artifact.name != target.newName) {
                namesAttempted += row.artifact.uri
                val changed = access.rename(row, target.newName)
                require(sameRenameScope(row, changed)) { "Rename changed file scope" }
                witnesses[row.artifact.uri] = changed
                check(changed.artifact.name == target.newName) { "Provider did not preserve requested filename" }
            }
        }
        for (change in prepared.metadata) {
            if (!change.before.contentEquals(change.after)) {
                writesAttempted += change.artifact.uri
                val row = witnesses.getValue(change.artifact.uri)
                val changed = access.writeMetadata(row, change.before, change.after)
                require(sameRenameScope(row, changed) && changed.artifact.name == row.artifact.name) { "Metadata write changed file identity" }
                witnesses[row.artifact.uri] = changed
            }
        }
        if (prepared.changed) {
            linkedAttempted = true // A failed write may already have changed a linked document.
            access.applyLinked(prepared)
        }
        access.verify(prepared, restored = false)
        result = MediaRenameResult(preview.files.map { target ->
            val documentChanged = prepared.metadata.any { it.artifact.uri == target.artifact.uri && !it.before.contentEquals(it.after) }
            MediaRenameFileResult(target.artifact, target.newName, target.newName,
                if (target.newName != target.artifact.name || documentChanged) MediaRenameStatus.RENAMED else MediaRenameStatus.UNCHANGED)
        })
    } catch (failure: Exception) {
        val prepared = plan
        val touched = namesAttempted + writesAttempted
        if (prepared == null || touched.isEmpty() && !linkedAttempted) {
            result = MediaRenameResult(initialResults, failure.message ?: "Rename preflight failed")
        } else {
            val issues = mutableListOf<String>()
            var linkedRestoreFailed = false
            if (linkedAttempted) try { access.restoreLinked(prepared) }
            catch (error: Exception) { linkedRestoreFailed = true; issues += error.message ?: "Linked proxy restoration failed" }
            val originals = prepared.rows.associateBy { it.artifact.uri }
            val targets = preview.files.associate { it.artifact.uri to it.newName }
            fun observed(uri: String): MediaDeleteRow {
                val original = originals.getValue(uri)
                val current = access.observe(original)
                check(sameRenameScope(original, current)) { "Compensation file scope changed" }
                val allowed = setOf(original.artifact.name, targets.getValue(uri), witnesses.getValue(uri).artifact.name)
                check(current.artifact.name in allowed) { "Compensation filename is not an observed rename identity" }
                return current
            }
            val uncertainDocuments = mutableSetOf<String>()
            for (change in prepared.metadata.asReversed().filter { it.artifact.uri in writesAttempted }) {
                try {
                    val row = observed(change.artifact.uri)
                    val bytes = access.readMetadata(row)
                    when {
                        bytes.contentEquals(change.before) -> witnesses[row.artifact.uri] = row
                        bytes.contentEquals(change.after) -> witnesses[row.artifact.uri] = access.writeMetadata(row, change.after, change.before)
                        else -> error("Metadata bytes are neither original nor planned; compensation did not overwrite them")
                    }
                } catch (error: Exception) { uncertainDocuments += change.artifact.uri; issues += error.message ?: "Metadata compensation failed" }
            }
            for (uri in namesAttempted.toList().asReversed()) {
                if (uri in uncertainDocuments) continue
                try {
                    val row = observed(uri)
                    val originalName = originals.getValue(uri).artifact.name
                    if (row.artifact.name != originalName) {
                        access.checkDestinations(listOf(row), mapOf(uri to originalName))
                        val restored = access.rename(row, originalName)
                        check(sameRenameScope(originals.getValue(uri), restored) && restored.artifact.name == originalName) { "Provider did not restore the original filename" }
                        witnesses[uri] = restored
                    }
                } catch (error: Exception) { issues += error.message ?: "Filename compensation failed" }
            }
            val restored = try { access.verify(prepared, restored = true); true } catch (error: Exception) {
                issues += error.message ?: "Compensation verification failed"; false
            }
            val files = preview.files.map { target ->
                try {
                    val row = observed(target.artifact.uri)
                    val metadata = prepared.metadata.singleOrNull { it.artifact.uri == target.artifact.uri }
                    val originalBytes = metadata == null || access.readMetadata(row).contentEquals(metadata.before)
                    val status = when {
                        originalBytes && row.artifact.name == target.artifact.name -> if (target.artifact.uri in touched) MediaRenameStatus.RESTORED else MediaRenameStatus.UNCHANGED
                        metadata != null && !access.readMetadata(row).contentEquals(metadata.after) -> MediaRenameStatus.UNKNOWN
                        else -> MediaRenameStatus.PARTIAL
                    }
                    MediaRenameFileResult(target.artifact, target.newName, row.artifact.name, status)
                } catch (error: Exception) { MediaRenameFileResult(target.artifact, target.newName, null, MediaRenameStatus.UNKNOWN, error.message) }
            }
            result = MediaRenameResult(files, (listOf(failure.message ?: "Rename failed") + issues).distinct().joinToString("; "),
                compensationAttempted = true, compensationComplete = restored && !linkedRestoreFailed && files.all { it.status in setOf(MediaRenameStatus.RESTORED, MediaRenameStatus.UNCHANGED) },
                linkedPartial = linkedRestoreFailed)
        }
    } finally {
        try { reservation?.close() } catch (error: Exception) { cleanupFailure = error.message ?: "Rename reservation release failed" }
    }
    return cleanupFailure?.let { result.copy(error = listOfNotNull(result.error, it).joinToString("; ")) } ?: result
}
