/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import com.librestatic.opencinecam.playback.codecLabel
import com.librestatic.opencinecam.playback.profileBitDepth
import com.librestatic.opencinecam.storage.LocalMediaArtifact
import com.librestatic.opencinecam.storage.LocalMediaEncoding
import com.librestatic.opencinecam.storage.LocalMediaKind
import com.librestatic.opencinecam.storage.LocalMediaRelationStatus
import com.librestatic.opencinecam.storage.LocalMediaTake
import com.librestatic.opencinecam.storage.ProxyJob
import com.librestatic.opencinecam.storage.ProxyJobStatus
import com.librestatic.opencinecam.storage.ProxyJobStatus.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaBadgesTest {
    @Test fun videoBadgeComesFromTheDeclaredEncodingOnly() {
        assertEquals(CodecBadge("HEVC", 10), codecBadge(LocalMediaKind.VIDEO, "video/mp4", "a.mp4", LocalMediaEncoding("video/hevc", "Main10")))
        // AVC_8 is CodecProfileLevel.AVCProfileHigh; AVC_16 is High 10.
        assertEquals(CodecBadge("H.264", 8), videoCodecBadge("video/avc", "AVC_8"))
        assertEquals(CodecBadge("H.264", 10), videoCodecBadge("video/avc", "AVC_16"))
        assertEquals(CodecBadge("HEVC", 8), videoCodecBadge("video/hevc", "Main"))
        assertEquals(CodecBadge("HEVC"), videoCodecBadge("video/hevc", null))
        assertEquals(CodecBadge("H.264"), videoCodecBadge("video/avc", "AVC_x"))
        assertNull(videoCodecBadge("video/av01", "Main10"))
        // A plain recording has only its MP4 container type: no guess.
        assertNull(codecBadge(LocalMediaKind.VIDEO, "video/mp4", "a.mp4", null))
    }

    @Test fun probedBadgeRenamesTheProbeLabelAndKeepsOnlyTheDepthItKnows() {
        assertEquals(CodecBadge("H.264", 8), probedCodecBadge("AVC 8-bit", 8))
        assertEquals(CodecBadge("HEVC", 10), probedCodecBadge("HEVC 10-bit", 10))
        assertEquals(CodecBadge("AV1", 10), probedCodecBadge("AV1 10-bit", 10))
        assertEquals(CodecBadge("HEVC"), probedCodecBadge("HEVC", null))
        assertNull(probedCodecBadge("VP9 8-bit", 8))
        assertNull(probedCodecBadge("MPEG-4", null))
        assertNull(probedCodecBadge(null, null))
        // What the probe builds from a MediaRecorder track: AVC High (8) and HEVC Main (1), then an unknown profile.
        assertEquals(CodecBadge("H.264", 8), probedCodecBadge(codecLabel("video/avc", 8, null), profileBitDepth("video/avc", 8)))
        assertEquals(CodecBadge("HEVC", 8), probedCodecBadge(codecLabel("video/hevc", 1, null), profileBitDepth("video/hevc", 1)))
        assertEquals(CodecBadge("HEVC"), probedCodecBadge(codecLabel("video/hevc", null, null), profileBitDepth("video/hevc", null)))
    }

    @Test fun photoBadgeComesFromMimeThenExtension() {
        assertEquals(CodecBadge("DNG"), photoCodecBadge("image/x-adobe-dng", "a.dng"))
        assertEquals(CodecBadge("JPEG"), photoCodecBadge("image/jpeg", "a.jpg"))
        assertEquals(CodecBadge("HEIF"), photoCodecBadge("image/heic", "a.heic"))
        assertEquals(CodecBadge("HEIF"), photoCodecBadge("image/heif", "a.heif"))
        assertEquals(CodecBadge("DNG"), photoCodecBadge("application/octet-stream", "A.DNG"))
        assertEquals(CodecBadge("JPEG"), photoCodecBadge("image/jpeg", "a.dng"))
        assertNull(photoCodecBadge("image/png", "a.png"))
    }

    @Test fun audioBadgeUsesTheSidecarFormatThenMime() {
        assertEquals(CodecBadge("WAV", 24), audioCodecBadge("audio/wav", "a.wav", "WAV", "PCM_24"))
        assertEquals(CodecBadge("FLAC", 16), audioCodecBadge("audio/flac", "a.flac", "FLAC", "PCM_16"))
        assertEquals(CodecBadge("WAV", 32, float = true), audioCodecBadge("audio/x-wav", "a.wav", null, "PCM_FLOAT"))
        assertEquals(CodecBadge("WAV"), audioCodecBadge("audio/vnd.wave", "a.wav", null, null))
        assertEquals(CodecBadge("FLAC"), audioCodecBadge("audio/x-flac", "a.flac", null, null))
        assertEquals(CodecBadge("AAC"), audioCodecBadge("audio/mp4", "a.m4a", null, null))
        assertEquals(CodecBadge("AAC"), audioCodecBadge("audio/mp4a-latm", "a.m4a", null, "PCM_16"))
        assertNull(audioCodecBadge("audio/ogg", "a.ogg", null, null))
    }

    @Test fun proxyStatePrefersWorkInFlightThenAnExistingProxy() {
        assertEquals(TakeProxyState.NONE, takeProxyState(emptyList(), committed = false))
        assertEquals(TakeProxyState.READY, takeProxyState(emptyList(), committed = true))
        assertEquals(TakeProxyState.QUEUED, takeProxyState(listOf(QUEUED), committed = false))
        assertEquals(TakeProxyState.MAKING, takeProxyState(listOf(FAILED, RUNNING), committed = true))
        assertEquals(TakeProxyState.READY, takeProxyState(listOf(SUCCEEDED), committed = false))
        assertEquals(TakeProxyState.READY, takeProxyState(listOf(FAILED), committed = true))
        assertEquals(TakeProxyState.FAILED, takeProxyState(listOf(CANCELLED, FAILED), committed = false))
        assertEquals(TakeProxyState.NONE, takeProxyState(listOf(FAILED, CANCELLED), committed = false))
        assertEquals(TakeProxyState.NONE, takeProxyState(listOf(CANCELLING), committed = false))
    }

    @Test fun proxyStatesCoverOnlyTheAskedTakesThatHaveOne() {
        val jobs = listOf(job("a", RUNNING), job("b", FAILED), job("x", SUCCEEDED))
        assertEquals(mapOf("a" to TakeProxyState.MAKING, "b" to TakeProxyState.FAILED, "c" to TakeProxyState.READY),
            takeProxyStates(setOf("a", "b", "c", "d"), jobs, committed = setOf("c")))
    }

    @Test fun proxyLabelsAreShownForEveryStateButNone() {
        assertNull(TakeProxyState.NONE.label())
        assertEquals(R.string.media_badge_proxy_ready, TakeProxyState.READY.label())
        assertEquals(R.string.media_badge_proxy_making, TakeProxyState.MAKING.label())
        assertEquals(R.string.media_badge_proxy_queued, TakeProxyState.QUEUED.label())
        assertEquals(R.string.media_badge_proxy_failed, TakeProxyState.FAILED.label())
    }

    private fun job(takeId: String, status: ProxyJobStatus): ProxyJob {
        val artifact = LocalMediaArtifact("content://media/external_primary/video/media/1", "a.mp4", "video/mp4", 1, 1)
        val take = LocalMediaTake(takeId, artifact, listOf(artifact), emptyList(), LocalMediaKind.VIDEO, null, LocalMediaRelationStatus.DECLARED)
        return ProxyJob("job-$takeId", take, ProxySettings(), status)
    }
}
