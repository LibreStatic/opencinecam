/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.gallery

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.librestatic.opencinecam.storage.LocalMediaCursor
import com.librestatic.opencinecam.storage.LocalMediaEncoding
import com.librestatic.opencinecam.storage.LocalMediaTake

/**
 * What the gallery has already read, kept for the life of the process so that reopening the gallery or going back to a
 * filter shows its takes at once instead of reading the catalog again. A list is reused as it is only while its
 * [MediaCatalogSource.freshness] token still matches and nothing has called [invalidate] since it was read; otherwise
 * the gallery shows it while it reads the catalog again. Main thread only.
 */
internal class GalleryCatalogCache {
    internal data class Key(val kind: GalleryMediaKind, val newestFirst: Boolean, val goodTakesOnly: Boolean, val query: String)
    internal data class Entry(val takes: List<LocalMediaTake>, val next: LocalMediaCursor?,
        val encodings: Map<String, LocalMediaEncoding>, val token: Any?, val generation: Int)

    /** Bumped by [invalidate]: a list read under an older generation is never reused as it is. */
    var generation by mutableIntStateOf(0)
        private set
    private val entries = object : LinkedHashMap<Key, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Entry>) = size > MAX_ENTRIES
    }
    val thumbnails = GalleryThumbnailCache()
    val facts = mutableMapOf<String, GalleryFacts>()
    val probedCodecs = mutableMapOf<String, CodecBadge?>()
    val takeDetails = mutableMapOf<String, TakeTechnicalDetails?>()

    operator fun get(key: Key): Entry? = entries[key]
    operator fun set(key: Key, entry: Entry) { entries[key] = entry }

    /** Something changed the catalog (refresh, delete, rename, a drive coming or going): every list is read again. */
    fun invalidate() {
        generation++
        // A rename changes the paths and names the details show; thumbnails and facts follow the file's URI.
        takeDetails.clear()
    }

    companion object {
        private const val MAX_ENTRIES = 8
        /** The production gallery's cache; tests and Compose Driver screens make their own. */
        val shared by lazy { GalleryCatalogCache() }
    }
}
