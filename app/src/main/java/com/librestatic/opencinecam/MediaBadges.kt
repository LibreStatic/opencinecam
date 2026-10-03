/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.librestatic.opencinecam.playback.profileBitDepth
import com.librestatic.opencinecam.storage.LocalMediaEncoding
import com.librestatic.opencinecam.storage.LocalMediaKind
import com.librestatic.opencinecam.storage.LocalMediaTake
import com.librestatic.opencinecam.storage.ProxyJob
import com.librestatic.opencinecam.storage.ProxyJobStatus
import com.librestatic.opencinecam.storage.proxyReceiptName
import com.librestatic.opencinecam.ui.theme.LocalCineColors
import java.io.File
import java.util.Locale

/** A codec name and, when the take declares it, the sample depth ("HEVC" 10, "WAV" 32 float). */
internal data class CodecBadge(val name: String, val bits: Int? = null, val float: Boolean = false)

/** What a card and the inspector say about a take beyond its MediaStore facts. */
internal data class TakeBadges(val codec: CodecBadge? = null, val proxy: TakeProxyState = TakeProxyState.NONE)

internal fun codecBadge(take: LocalMediaTake, encoding: LocalMediaEncoding?): CodecBadge? =
    codecBadge(take.kind, take.primary.mimeType, take.primary.name, encoding)

/**
 * The sidecar's declaration first, then the primary file's MIME type or extension. A plain video
 * has only an MP4 container type (MediaRecorder writes no encoding into its sidecars), so it gets
 * no badge rather than a guess.
 */
internal fun codecBadge(kind: LocalMediaKind, mime: String, name: String, encoding: LocalMediaEncoding?): CodecBadge? = when (kind) {
    LocalMediaKind.VIDEO -> videoCodecBadge(encoding?.videoMime, encoding?.videoProfile)
    LocalMediaKind.PHOTO -> photoCodecBadge(mime, name)
    LocalMediaKind.AUDIO -> audioCodecBadge(mime, name, encoding?.audioContainer, encoding?.audioSamples)
}

/** The OCLog sidecar names the profile as "Main10" for HEVC or "AVC_<CodecProfileLevel>" for H.264. */
internal fun videoCodecBadge(mime: String?, profile: String?): CodecBadge? {
    val normalized = mime?.lowercase(Locale.ROOT)
    val name = when (normalized) { "video/hevc" -> "HEVC"; "video/avc" -> "H.264"; else -> return null }
    val bits = when {
        profile == null -> null
        profile.equals("Main10", ignoreCase = true) -> 10
        profile.equals("Main", ignoreCase = true) -> 8
        profile.startsWith("AVC_") -> profileBitDepth(normalized, profile.removePrefix("AVC_").toIntOrNull())
        else -> null
    }
    return CodecBadge(name, bits)
}

internal fun photoCodecBadge(mime: String, name: String): CodecBadge? {
    val format = when (mime.lowercase(Locale.ROOT)) {
        "image/x-adobe-dng" -> "DNG"
        "image/jpeg" -> "JPEG"
        "image/heic", "image/heif" -> "HEIF"
        else -> when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "dng" -> "DNG"
            "jpg", "jpeg" -> "JPEG"
            "heic", "heif" -> "HEIF"
            else -> null
        }
    }
    return format?.let { CodecBadge(it) }
}

/** WAV and FLAC carry their sample format in the audio sidecar; AAC is compressed and has none. */
internal fun audioCodecBadge(mime: String, name: String, container: String?, samples: String?): CodecBadge? {
    val format = when (container?.uppercase(Locale.ROOT)) { "WAV" -> "WAV"; "FLAC" -> "FLAC"; else -> null }
        ?: when (mime.lowercase(Locale.ROOT)) {
            "audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave" -> "WAV"
            "audio/flac", "audio/x-flac" -> "FLAC"
            "audio/mp4", "audio/mp4a-latm", "audio/aac", "audio/x-m4a" -> "AAC"
            else -> when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
                "wav" -> "WAV"; "flac" -> "FLAC"; "m4a", "aac" -> "AAC"; else -> null
            }
        } ?: return null
    if (format == "AAC") return CodecBadge(format)
    return when (samples) {
        "PCM_16" -> CodecBadge(format, 16)
        "PCM_24" -> CodecBadge(format, 24)
        "PCM_FLOAT" -> CodecBadge(format, 32, float = true)
        else -> CodecBadge(format)
    }
}

@Composable
@ReadOnlyComposable
internal fun codecBadgeText(badge: CodecBadge): String = when {
    badge.bits == null -> badge.name
    badge.float -> stringResource(R.string.media_badge_depth_float, badge.name, badge.bits)
    else -> stringResource(R.string.media_badge_depth, badge.name, badge.bits)
}

/** A take's proxy as the operator needs it. The queue reports states, not progress, so none is shown. */
internal enum class TakeProxyState { NONE, QUEUED, MAKING, READY, FAILED }

/**
 * [statuses] are the queue's jobs for one take, oldest first; [committed] means a commit receipt
 * exists. Work in flight wins, then a proxy that exists, then the latest attempt's failure.
 * A cancelled attempt leaves nothing to show.
 */
internal fun takeProxyState(statuses: List<ProxyJobStatus>, committed: Boolean): TakeProxyState = when {
    ProxyJobStatus.RUNNING in statuses -> TakeProxyState.MAKING
    ProxyJobStatus.QUEUED in statuses -> TakeProxyState.QUEUED
    committed || ProxyJobStatus.SUCCEEDED in statuses -> TakeProxyState.READY
    statuses.lastOrNull { it == ProxyJobStatus.FAILED || it == ProxyJobStatus.CANCELLED } == ProxyJobStatus.FAILED -> TakeProxyState.FAILED
    else -> TakeProxyState.NONE
}

/** The states worth showing for [ids]; a take with no proxy is left out. */
internal fun takeProxyStates(ids: Set<String>, jobs: List<ProxyJob>, committed: Set<String>): Map<String, TakeProxyState> {
    val statuses = jobs.filter { it.take.id in ids }.groupBy({ it.take.id }, { it.status })
    return ids.associateWith { takeProxyState(statuses[it].orEmpty(), it in committed) }.filterValues { it != TakeProxyState.NONE }
}

/** Takes among [ids] with a committed proxy: one directory listing, no MediaStore query or hashing of media. */
internal fun committedProxyTakes(context: Context, ids: Set<String>): Set<String> {
    val names = File(context.filesDir, "media-proxies").list()?.toHashSet() ?: return emptySet()
    return ids.filterTo(HashSet()) { proxyReceiptName(it) in names }
}

@StringRes
internal fun TakeProxyState.label(): Int? = when (this) {
    TakeProxyState.NONE -> null
    TakeProxyState.QUEUED -> R.string.media_badge_proxy_queued
    TakeProxyState.MAKING -> R.string.media_badge_proxy_making
    TakeProxyState.READY -> R.string.media_badge_proxy_ready
    TakeProxyState.FAILED -> R.string.media_badge_proxy_failed
}

/** Green once the proxy exists, amber while it is pending or after it failed; never the recording red. */
internal val TakeProxyState.tint: Color
    @Composable @ReadOnlyComposable get() =
        if (this == TakeProxyState.READY) LocalCineColors.current.ok else LocalCineColors.current.pending
