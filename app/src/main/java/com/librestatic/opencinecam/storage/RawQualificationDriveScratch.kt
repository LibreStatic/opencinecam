/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.storage

import android.content.ContentResolver
import android.net.Uri

/**
 * PLAN-072 Delivery 3: the scratch files of a RAW video qualification of a USB drive, the drive's twin of
 * [RawVideoQualificationRows]. The storage sweep and every long run write an `occ-raw-qual-*.ocraw` document in
 * `<tree>/OpenCineCam/.occ-qualify/`, through SAF as a drive take is written, so the measurement covers the same
 * path. The folder is hidden, so the gallery, the network drive and the outbox never list it. Each document is
 * registered live with [RawVideoRecovery] while written and deleted after its step; [purgeOrphans] removes what a
 * killed process left, and touches nothing outside that folder. Every call blocks on the provider.
 */
internal class RawQualificationDriveScratch(private val resolver: ContentResolver, val tree: Uri) {
    private val docs = SafDocuments(resolver, tree)

    /** A new empty scratch document labelled [label], registered live. */
    fun create(label: String): Uri = RawVideoRecovery.insertLive {
        docs.newFile(folder(), "${RawVideoQualificationRows.NAME_PREFIX}$label-${System.currentTimeMillis()}${RawVideoOutput.EXTENSION}").uri
    }

    /** Deletes [uri] (failures ignored: [purgeOrphans] retries) and unregisters it. */
    fun delete(uri: Uri) {
        runCatching { docs.delete(uri) }
        RawVideoRecovery.release(uri)
    }

    /** The drive's free bytes, or null when its provider gives no figure. */
    fun freeBytes(): Long? = driveFreeBytes(resolver, tree)

    /**
     * Deletes the scratch documents nothing in this process writes; returns how many went. Only the children of
     * `OpenCineCam/.occ-qualify` are candidates; a drive that does not answer purges nothing.
     */
    fun purgeOrphans(): Int = runCatching {
        val base = docs.child(docs.root, SafRecordingRows.BASE_FOLDER) ?: return 0
        val scratch = docs.child(base.uri, FOLDER)?.takeIf { it.mime == android.provider.DocumentsContract.Document.MIME_TYPE_DIR } ?: return 0
        docs.children(scratch.uri).count { doc -> !RawVideoRecovery.isLive(doc.uri) && runCatching { docs.delete(doc.uri) }.getOrDefault(false) }
    }.getOrDefault(0)

    private fun folder(): Uri = docs.directory(docs.directory(docs.root, SafRecordingRows.BASE_FOLDER), FOLDER)

    companion object {
        /** The hidden scratch folder inside the drive's `OpenCineCam` folder. */
        const val FOLDER = ".occ-qualify"
    }
}
