/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

/** Literal editable stem; the proxy namespace and URI identity remain independent. */
internal fun proxyFilename(stem: String): String = validateMediaRenameStem(stem) + ".mp4"

/** Persisted names must already be canonical, rather than being silently normalized on read. */
internal fun requireProxyFilename(name: String) {
    require(name.endsWith(".mp4")) { "Proxy filename requires the exact .mp4 suffix" }
    require(proxyFilename(name.removeSuffix(".mp4")) == name) { "Proxy filename is not canonical" }
}

/** Deliberately separate from original-catalog mutation admission; only one frozen proxy namespace. */
internal fun proxyRenameCondition(row: MediaDeleteRow, proxyId: String, owner: String): MediaDeleteCondition {
    require(canonicalMediaId(proxyId) && owner.isNotBlank() && row.owner == owner && row.pending == 0)
    val identity = requireNotNull(mediaDeleteIdentity(row.artifact.uri))
    require(identity.collection == MediaDeleteCollection.VIDEO && row.artifact.mimeType == "video/mp4" &&
        row.relativePath == "Movies/OpenCineCamProxies/$proxyId/" && row.artifact.sizeBytes > 0 && row.artifact.modifiedSeconds >= 0)
    requireProxyFilename(row.artifact.name)
    return MediaDeleteCondition(
        "_id = ? AND owner_package_name = ? AND relative_path = ? AND _display_name = ? AND mime_type = ? AND _size = ? AND date_modified = ? AND is_pending = 0",
        listOf(identity.id.toString(), owner, row.relativePath, row.artifact.name, row.artifact.mimeType,
            row.artifact.sizeBytes.toString(), row.artifact.modifiedSeconds.toString()),
    )
}
