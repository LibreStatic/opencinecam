/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.storage

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract

/** The CinemaDNG export's preferences and the key of the folder it remembers. */
internal const val CINEMADNG_EXPORT_PREFS = "cinemadng_export"
internal const val CINEMADNG_EXPORT_TREE_KEY = "tree"

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
    return runCatching {
        val probe = driveVolumeProbes[key] ?: SafDocuments(resolver, tree).let { docs ->
            val base = docs.directory(docs.root, SafRecordingRows.BASE_FOLDER)
            (docs.child(base, DRIVE_VOLUME_PROBE)?.uri ?: docs.newFile(base, DRIVE_VOLUME_PROBE).uri).also { driveVolumeProbes[key] = it }
        }
        requireNotNull(resolver.openFileDescriptor(probe, "r")).use { descriptor ->
            val stat = android.system.Os.fstatvfs(descriptor.fileDescriptor)
            stat.f_bavail * stat.f_frsize
        }
    }.onFailure { driveVolumeProbes.remove(key) }.getOrNull()
}

/** The hidden document [driveFreeBytes] reads the drive's free space through. */
internal const val DRIVE_VOLUME_PROBE = ".occ-volume"

private val driveVolumeProbes = java.util.concurrent.ConcurrentHashMap<String, Uri>()

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
