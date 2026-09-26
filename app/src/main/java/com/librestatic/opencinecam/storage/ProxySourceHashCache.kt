/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

/** Provider observation that changes whenever MediaStore records a write to the source. */
internal data class ProxySourceStamp(val sizeBytes: Long, val modifiedSeconds: Long, val generation: Long)

/** Reuses a full-content hash only while the stamp is identical before and after hashing.
 * An unknown stamp (API 29, failed query) always hashes; a changed stamp always re-hashes. */
internal class ProxySourceHashCache(private val capacity: Int = 64) {
    private val entries = LinkedHashMap<String, Pair<ProxySourceStamp, String>>(16, 0.75f, true)

    suspend fun hash(key: String, stamp: () -> ProxySourceStamp?, compute: suspend () -> String): String {
        val before = stamp()
        if (before != null) synchronized(entries) { entries[key] }?.let { (seen, hash) -> if (seen == before) return hash }
        val hash = compute()
        val after = if (before == null) null else stamp()
        synchronized(entries) {
            if (after != null && after == before) {
                entries[key] = after to hash
                while (entries.size > capacity) entries.remove(entries.keys.first())
            } else entries.remove(key)
        }
        return hash
    }
}
