/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

/** Original fields are historical references, not a statement of current presence or integrity.
 * The receipt digest binds even non-visible technical fields to the selected revision. */
internal data class ProxyCatalogEntry(val takeId: String, val originalDisplayName: String,
    val result: MediaProxyResult, val receiptSha256: String)

private val RECEIPT_RESIDUE = Regex("([0-9a-f]{64}\\.json)\\.(bak|new)")

/** Resolves receipt residue left by process death the way AtomicFile.openRead does: a legacy `.bak`
 * is the committed state and replaces its base; a `.new` is an unfinished write, never evidence.
 * Other names are left for the catalog to reject. Callers must exclude receipt writers. */
internal fun recoverProxyReceiptResidue(directory: java.io.File) {
    val root = directory.canonicalFile
    val names = root.list() ?: return
    for (name in names.sorted()) {
        val match = RECEIPT_RESIDUE.matchEntire(name) ?: continue
        val residue = java.io.File(root, name)
        val base = java.io.File(root, match.groupValues[1])
        check(residue.canonicalFile == residue.absoluteFile && residue.isFile &&
            (!base.exists() || base.canonicalFile == base.absoluteFile && base.isFile)) { "Proxy receipt residue identity differs" }
        // An unlocked AtomicFile.openRead may resolve the same residue concurrently; only the outcome matters.
        if (match.groupValues[2] == "bak") { if (base.exists()) base.delete(); residue.renameTo(base) }
        else residue.delete()
        check(!residue.exists()) { "Proxy receipt residue cleanup failed" }
    }
}
