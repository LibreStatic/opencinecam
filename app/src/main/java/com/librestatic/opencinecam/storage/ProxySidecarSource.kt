/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import android.content.Context
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class ProxySidecarSource(val selection: ProxySidecarSelection,
    val pcm: ProxyPcmObservation, val sourceSha256: Map<String, String>, val snapshot: MediaShareSnapshot)

/** Caller holds the same media mutation reservation as proxy creation through export/publication.
 * This observes source evidence only; it neither creates a candidate nor certifies an AAC result.
 * Hash every selected source/document before and after decoding, not merely file names and sizes. */
internal suspend fun prepareProxySidecarSource(context: Context, take: LocalMediaTake,
    videoFirstPtsUs: Long, videoEndUs: Long): ProxySidecarSource = withContext(Dispatchers.IO) {
    val repository = LocalMediaRepository(context)
    val before = repository.freshSnapshot(take)
    val audio = take.originals.single { it.mimeType in setOf("audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave", "audio/flac") }
    val members = take.originals + take.metadata
    val hashes = members.associate { it.uri to proxyHash(context, it.uri.toUri()) }
    val pcm = probeProxyPcm(context, audio.uri.toUri())
    val selected = selectProxySidecar(take, before.documents, pcm.sampleRateHz, pcm.channels, pcm.frames,
        videoFirstPtsUs, videoEndUs)
    check(selected.audio == audio) { "Decoded sidecar differs from declared composition source" }
    check(repository.freshSnapshot(take) == before) { "Sidecar source metadata changed while decoding" }
    for (member in members) check(proxyHash(context, member.uri.toUri()) == hashes.getValue(member.uri)) {
        "Sidecar composition source bytes changed while decoding"
    }
    ProxySidecarSource(selected, pcm, hashes.toMap(), before)
}

internal suspend fun verifyProxySidecarSource(context: Context, take: LocalMediaTake, source: ProxySidecarSource) {
    check(LocalMediaRepository(context).freshSnapshot(take) == source.snapshot) { "Sidecar source snapshot changed" }
    for ((uri, hash) in source.sourceSha256) check(proxyHash(context, uri.toUri()) == hash) {
        "Sidecar composition source bytes changed"
    }
}
