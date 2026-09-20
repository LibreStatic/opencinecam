/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

/** Absence is observed, not proof this operation removed bytes or an atomic-take guarantee. */
enum class MediaDeleteStatus { ABSENT_VERIFIED, RETAINED, UNKNOWN, NOT_ATTEMPTED }
data class MediaDeleteFileResult(val artifact: LocalMediaArtifact, val status: MediaDeleteStatus, val detail: String? = null)
data class MediaDeleteResult(val files: List<MediaDeleteFileResult>, val error: String? = null) {
    val complete: Boolean get() = error == null && files.isNotEmpty() && files.all { it.status == MediaDeleteStatus.ABSENT_VERIFIED }
    val partial: Boolean get() = !complete && files.any { it.status == MediaDeleteStatus.ABSENT_VERIFIED }
}
internal enum class MediaDeleteCollection { PHOTO, VIDEO, AUDIO, METADATA }
internal data class MediaDeleteIdentity(val collection: MediaDeleteCollection, val id: Long)
internal data class MediaDeleteRow(val artifact: LocalMediaArtifact, val relativePath: String, val owner: String, val pending: Int)
internal data class MediaDeletePlan(val selected: LocalMediaTake, val ordered: List<MediaDeleteRow>, val namespace: MediaNamespace?)

internal fun mediaDeleteIdentity(uri: String): MediaDeleteIdentity? {
    val original = mediaOriginalIdentity(uri)
    if (original != null) return MediaDeleteIdentity(MediaDeleteCollection.valueOf(original.first.name), original.second)
    val match = Regex("content://media/external_primary/downloads/([1-9][0-9]*)").matchEntire(uri) ?: return null
    return match.groupValues[1].toLongOrNull()?.let { MediaDeleteIdentity(MediaDeleteCollection.METADATA, it) }
}

/** Every member comes from actual provider enumeration, never from relationship JSON references. */
internal fun mediaDeletePlan(selected: LocalMediaTake, observed: List<MediaDeleteRow>, owner: String): MediaDeletePlan {
    require(selected.originals.size in 1..32 && selected.metadata.size <= 4 && selected.primary in selected.originals)
    val confirmed = selected.originals + selected.metadata
    require(confirmed.map { it.uri }.distinct().size == confirmed.size) { "Duplicate confirmed deletion member" }
    require(observed.size == confirmed.size && observed.map { it.artifact.uri }.distinct().size == observed.size) { "Deletion membership changed or enumeration was incomplete" }
    require(observed.map { it.artifact }.toSet() == confirmed.toSet()) { "Deletion selection changed; refresh gallery" }
    val primary = observed.single { it.artifact == selected.primary }
    val namespace = mediaNamespace(primary.relativePath)
    require(mediaDeleteIdentity(primary.artifact.uri)?.collection?.name == selected.kind.name)
    val anchor = catalogPrimary(observed.filter { it.artifact in selected.originals }.map { row ->
        val identity = requireNotNull(mediaOriginalIdentity(row.artifact.uri))
        CatalogRow(row.artifact, identity.second, identity.first, namespace)
    })
    require(anchor?.artifact == selected.primary) { "Deletion primary anchor changed" }
    for (row in observed) {
        val identity = requireNotNull(mediaDeleteIdentity(row.artifact.uri)) { "Untrusted deletion URI" }
        require(row.owner == owner && row.pending == 0 && isCatalogPath(row.relativePath)) { "Deletion ownership, pending state or path changed" }
        require(row.artifact.sizeBytes >= 0 && row.artifact.modifiedSeconds >= 0)
        require(row.artifact.name.isNotEmpty() && row.artifact.mimeType.isNotEmpty())
        require(mediaNamespace(row.relativePath) == namespace) { "Deletion rows span namespaces" }
        if (row.artifact in selected.metadata) require(identity.collection == MediaDeleteCollection.METADATA)
        else require(identity.collection != MediaDeleteCollection.METADATA)
        val expectedRoot = when (identity.collection) { MediaDeleteCollection.PHOTO, MediaDeleteCollection.VIDEO -> "DCIM"; MediaDeleteCollection.AUDIO -> "Music"; MediaDeleteCollection.METADATA -> "Download" }
        require(row.relativePath.startsWith("$expectedRoot/OpenCineCam/"))
        val compatible = when (identity.collection) {
            MediaDeleteCollection.PHOTO -> namespace?.recording != true && row.artifact.mimeType.startsWith("image/")
            MediaDeleteCollection.VIDEO -> namespace?.recording != false && row.artifact.mimeType.startsWith("video/")
            MediaDeleteCollection.AUDIO -> namespace?.recording != false && row.artifact.mimeType.startsWith("audio/")
            MediaDeleteCollection.METADATA -> true
        }
        require(compatible) { "Deletion collection/type mismatch" }
    }
    if (namespace == null) require(confirmed.size == 1 && selected.metadata.isEmpty() && selected.id == "legacy:${primary.artifact.uri}")
    else require(selected.id == namespace.key)
    val byUri = observed.associateBy { it.artifact.uri }
    val order = selected.originals.filter { it != selected.primary } + selected.metadata + selected.primary
    return MediaDeletePlan(selected, order.map { byUri.getValue(it.uri) }, namespace)
}

internal data class MediaDeleteCondition(val selection: String, val arguments: List<String>)
internal fun mediaDeleteCondition(row: MediaDeleteRow): MediaDeleteCondition {
    val identity = requireNotNull(mediaDeleteIdentity(row.artifact.uri))
    require(row.pending == 0 && row.owner.isNotBlank() && isCatalogPath(row.relativePath))
    return MediaDeleteCondition(
        "_id = ? AND owner_package_name = ? AND relative_path = ? AND _display_name = ? AND mime_type = ? AND _size = ? AND date_modified = ? AND is_pending = 0",
        listOf(identity.id.toString(), row.owner, row.relativePath, row.artifact.name, row.artifact.mimeType,
            row.artifact.sizeBytes.toString(), row.artifact.modifiedSeconds.toString()),
    )
}

/** Small IO seam permits testing actual conditional provider deletion without global hooks. */
internal interface MediaDeleteAccess {
    fun reserve(): AutoCloseable
    fun preflight(selected: LocalMediaTake): MediaDeletePlan
    fun beginDeletion(plan: MediaDeletePlan) {}
    fun delete(row: MediaDeleteRow): Int
    fun isAbsent(row: MediaDeleteRow): Boolean
    fun verifyEmpty(plan: MediaDeletePlan)
}

internal fun executeMediaDelete(selected: LocalMediaTake, access: MediaDeleteAccess): MediaDeleteResult {
    val confirmed = selected.originals + selected.metadata
    val results = confirmed.map { MediaDeleteFileResult(it, MediaDeleteStatus.NOT_ATTEMPTED) }.toMutableList()
    fun outcome(error: String? = null) = MediaDeleteResult(results.toList(), error)
    fun record(row: MediaDeleteRow, status: MediaDeleteStatus, detail: String? = null) {
        val index = confirmed.indexOf(row.artifact)
        check(index >= 0)
        results[index] = MediaDeleteFileResult(row.artifact, status, detail)
    }
    var reservation: AutoCloseable? = null
    var result: MediaDeleteResult
    var cleanupFailure: String? = null
    try {
        reservation = access.reserve()
        val plan = access.preflight(selected)
        val expectedOrder = selected.originals.filter { it != selected.primary } + selected.metadata + selected.primary
        require(plan.selected == selected && plan.ordered.map { it.artifact } == expectedOrder &&
            plan.ordered.map { it.artifact }.toSet() == confirmed.toSet() && plan.ordered.size == confirmed.size)
        access.beginDeletion(plan)
        var failure: String? = null
        for (row in plan.ordered) {
            var problem: String? = null
            try {
                val deleted = access.delete(row)
                if (deleted !in 0..1) problem = "Unexpected provider deletion result"
            } catch (error: Exception) { problem = error.message ?: "Provider deletion failed" }
            try {
                val absent = access.isAbsent(row)
                if (!absent && problem == null) problem = "Conditional deletion did not remove the selected identity"
                record(row, if (absent) MediaDeleteStatus.ABSENT_VERIFIED else MediaDeleteStatus.RETAINED, problem)
            } catch (error: Exception) {
                problem = error.message ?: "Deletion absence could not be verified"
                record(row, MediaDeleteStatus.UNKNOWN, problem)
            }
            if (problem != null) { failure = problem; break }
        }
        if (failure == null) {
            // Re-observe every confirmed URI and the whole namespace after the primary's turn.
            for (row in plan.ordered) {
                try {
                    if (!access.isAbsent(row)) { record(row, MediaDeleteStatus.RETAINED); failure = "A confirmed deletion member is present again"; break }
                } catch (error: Exception) {
                    failure = error.message ?: "Final absence verification failed"
                    record(row, MediaDeleteStatus.UNKNOWN, failure); break
                }
            }
            if (failure == null) try { access.verifyEmpty(plan) } catch (error: Exception) { failure = error.message ?: "Deletion namespace is not verifiably empty" }
        }
        result = outcome(failure)
    } catch (error: Exception) { result = outcome(error.message ?: "Deletion preflight failed; no further files were attempted") }
    finally {
        // Guard ownership lasts through all verification; cleanup itself never deletes media.
        try { reservation?.close() } catch (error: Exception) { cleanupFailure = error.message ?: "Deletion reservation release failed" }
    }
    return cleanupFailure?.let { result.copy(error = listOfNotNull(result.error, it).joinToString("; ")) } ?: result
}
