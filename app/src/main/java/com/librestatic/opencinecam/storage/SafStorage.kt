/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.storage

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import kotlin.concurrent.withLock

/** The CinemaDNG export's preferences and the key of the folder it remembers. */
internal const val CINEMADNG_EXPORT_PREFS = "cinemadng_export"
internal const val CINEMADNG_EXPORT_TREE_KEY = "tree"
/** The folders the export left while an export that may still write to them is queued or running. */
const val CINEMADNG_EXPORT_LEFT_TREES_KEY = "leftTrees"
/** The folders of the exports queued or running; a string set that outlives the process, as the queued work does. */
const val CINEMADNG_EXPORT_ACTIVE_TREES_KEY = "activeTrees"

/** The free bytes of the root holding [tree], when its provider reports them. */
internal fun safFreeBytes(resolver: ContentResolver, tree: Uri): Long? = runCatching {
    val authority = requireNotNull(tree.authority)
    val rootId = DocumentsContract.getTreeDocumentId(tree).substringBefore(':')
    resolver.query(DocumentsContract.buildRootsUri(authority),
        arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_AVAILABLE_BYTES), null, null, null)?.use { cursor ->
        var free: Long? = null
        while (cursor.moveToNext()) {
            if (cursor.getString(0) == rootId && !cursor.isNull(1)) { free = cursor.getLong(1); break }
        }
        free
    }
}.getOrNull()

/**
 * PLAN-072: the free bytes of the drive holding a recording [tree]. The roots query of [safFreeBytes] needs
 * `MANAGE_DOCUMENTS`, which ExternalStorageProvider demands and apps never hold, so on a device it gives null for a USB
 * drive (seen on the Razr Fold); `statfs` on `/storage/<UUID>` is refused too without a storage permission. This falls
 * back to `fstatvfs` on a descriptor of the hidden `<tree>/OpenCineCam/.occ-volume` document, created once and empty;
 * the gallery, the network drive and recovery skip hidden names. Null when the drive does not answer. Blocks on the provider.
 */
internal fun driveFreeBytes(resolver: ContentResolver, tree: Uri): Long? {
    val key = tree.toString()
    // Once the probe exists the roots query is skipped: the HUD asks every second while a take records.
    if (!driveVolumeProbes.containsKey(key)) safFreeBytes(resolver, tree)?.let { return it }
    var probe: Uri? = null
    return runCatching {
        val found = driveVolumeProbes[key] ?: driveVolumeProbeCreation.computeIfAbsent(key) { java.util.concurrent.locks.ReentrantLock() }.withLock {
            // Two first callers would both create it, and the provider would name the second `.occ-volume (1)`.
            driveVolumeProbes[key] ?: SafDocuments(resolver, tree).let { docs ->
                val base = docs.directory(docs.root, SafRecordingRows.BASE_FOLDER)
                (docs.child(base, DRIVE_VOLUME_PROBE)?.uri ?: docs.newFile(base, DRIVE_VOLUME_PROBE).uri).also { driveVolumeProbes[key] = it }
            }
        }
        probe = found
        val mark = DriveDescriptors.releaseMark(found)
        requireNotNull(resolver.openFileDescriptor(found, "r")).useOnDrive(found, mark) { descriptor ->
            val stat = android.system.Os.fstatvfs(descriptor.fileDescriptor)
            stat.f_bavail * stat.f_frsize
        }
    }.onFailure {
        // Only the probe this call read is forgotten: another caller may have cached a newer one meanwhile.
        probe?.let { driveVolumeProbes.remove(key, it) }
    }.getOrNull()
}

/** The hidden document [driveFreeBytes] reads the drive's free space through. */
internal const val DRIVE_VOLUME_PROBE = ".occ-volume"

private val driveVolumeProbes = java.util.concurrent.ConcurrentHashMap<String, Uri>()

/**
 * Serializes finding or creating a tree's probe, one lock per tree so a slow drive never holds up another's; the reads
 * of a cached probe never take it. Creation is rare (once per drive), so the few locks are kept.
 */
private val driveVolumeProbeCreation = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.locks.ReentrantLock>()

/** The tree's document id (`FFF1-7571:RAW`), or null when [tree] is not a tree URI. */
internal fun treeDocumentIdOf(tree: Uri): String? = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull()

/**
 * The volume UUID of an external storage tree, upper case as `StorageVolume.getUuid()` reports it,
 * or null when [tree] is not a tree URI or its document id has no volume part.
 */
internal fun volumeIdOfTree(tree: Uri): String? = volumeIdOfDocumentId(treeDocumentIdOf(tree))

/** The part of an external storage document id before the `:`, upper case; null when there is none. */
internal fun volumeIdOfDocumentId(documentId: String?): String? {
    if (documentId == null) return null
    val colon = documentId.indexOf(':')
    if (colon <= 0) return null
    val volume = documentId.substring(0, colon)
    return if (volume.isBlank() || volume.contains('/')) null else volume.uppercase()
}

/** The document URI of the folder [tree] grants, to create documents in or query. */
internal fun treeDocumentUri(tree: Uri): Uri =
    DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

/** Where a journalled drive take or still group is now: its tree and folder as recorded, or their twins under the drive's mounted name. */
internal data class DriveLocation(val tree: Uri, val folder: Uri?)

/**
 * The location a recovery journal recorded ([tree] on [volume], with [folder] under it) as the drive answers now, or null
 * to leave the entry for a later pass. As recorded while that volume is mounted and its grant is in [held]. Otherwise the
 * same folder picked on the same drive under another volume name ([sameDrive]: vold's `-01` rename, see
 * [driveIdentityOf]), when that tree is in [held] and its volume mounted; [folder] is then rebuilt under that tree with
 * its path unchanged. The journal keeps what it recorded, so the entry also resolves when the old name returns.
 */
internal fun resolveDriveLocation(tree: Uri, volume: String, folder: Uri?, held: Set<Uri>, mounted: (String) -> Boolean): DriveLocation? {
    if (mounted(volume) && tree in held) return DriveLocation(tree, folder)
    val treeId = treeDocumentIdOf(tree) ?: return null
    val treeName = volumeIdOfDocumentId(treeId) ?: return null
    val path = treeId.substringAfter(':')
    for (candidate in held) {
        if (candidate.authority != EXTERNAL_STORAGE_AUTHORITY) continue
        val id = treeDocumentIdOf(candidate) ?: continue
        val name = volumeIdOfDocumentId(id) ?: continue
        if (name == treeName || !sameDrive(name, treeName) || id.substringAfter(':') != path || !mounted(name)) continue
        if (folder == null) return DriveLocation(candidate, null)
        val folderId = runCatching { DocumentsContract.getDocumentId(folder) }.getOrNull() ?: return null
        if (volumeIdOfDocumentId(folderId) != treeName) return null
        return DriveLocation(candidate,
            DocumentsContract.buildDocumentUriUsingTree(candidate, id.substringBefore(':') + ":" + folderId.substringAfter(':')))
    }
    return null
}

/** The trees this app holds a persisted read and write grant for. */
internal fun heldReadWriteTrees(resolver: ContentResolver): Set<Uri> =
    resolver.persistedUriPermissions.filter { it.isReadPermission && it.isWritePermission }.mapTo(LinkedHashSet()) { it.uri }
