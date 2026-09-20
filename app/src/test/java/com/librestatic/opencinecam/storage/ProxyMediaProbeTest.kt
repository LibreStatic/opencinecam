/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import com.librestatic.opencinecam.ProxySettings
import org.junit.Assert.*
import org.junit.Test

class ProxyMediaProbeTest {
    private val geometry = VideoDisplayGeometry(128, 96)
    private val video = ProxyTrack("video/avc", listOf(0, 66666, 33333), 100000, "video-original", emptyList(), 0, 0)
    private val audio = ProxyTrack("audio/mp4a-latm", listOf(0, 21333, 42666, 64000, 85333), 106666, "audio", listOf("csd"), 48000, 2)
    private val source = ProxyMediaProbe(video, audio, geometry)
    @Test fun dimensionsNeverUpscaleAndRoundEvenWithFit() {
        assertEquals(geometry, proxyDimensions(geometry, ProxySettings()))
        assertEquals(VideoDisplayGeometry(1280, 720), proxyDimensions(VideoDisplayGeometry(3840, 2160), ProxySettings()))
        assertEquals(VideoDisplayGeometry(720, 1280), proxyDimensions(VideoDisplayGeometry(2160, 3840), ProxySettings()))
        assertEquals(VideoDisplayGeometry(640, 412), proxyDimensions(VideoDisplayGeometry(1920, 1240), ProxySettings(640)))
    }
    @Test fun anamorphicDarIsNotMistakenForAnUpscaledRaster() {
        val dar = videoDisplayGeometry(720,576,sarWidth=16,sarHeight=15)
        assertEquals(VideoDisplayGeometry(720,540),proxyDimensions(dar,ProxySettings(),VideoDisplayGeometry(720,576)))
        assertEquals(VideoDisplayGeometry(540,720),proxyDimensions(VideoDisplayGeometry(dar.height,dar.width),ProxySettings(),VideoDisplayGeometry(576,720)))
    }
    @Test fun transcodedVideoMayChangePacketsButNotTimingOrAudio() {
        verifyProxyCorrespondence(source, source.copy(video = video.copy(timestampsUs = listOf(0,33333,66666), packetDigest = "transcoded")), geometry)
    }
    @Test fun rejectedChangesCoverFramesDurationAudioPacketsCsdRateAndChannels() {
        val invalid = listOf(source.copy(video=video.copy(timestampsUs=listOf(0,33333))),
            source.copy(video=video.copy(timestampsUs=listOf(0,33334,66666))),
            source.copy(video=video.copy(durationUs=102000)),
            source.copy(video=video.copy(mime="video/hevc")), source.copy(geometry=VideoDisplayGeometry(96,128)),
            source.copy(audio=null), source.copy(audio=audio.copy(packetDigest="changed")),
            source.copy(audio=audio.copy(codecData=listOf("other"))), source.copy(audio=audio.copy(sampleRate=44100)),
            source.copy(audio=audio.copy(channels=1)), source.copy(audio=audio.copy(timestampsUs=listOf(0,22000))),
            source.copy(audio=audio.copy(durationUs=100000)))
        invalid.forEach { candidate -> assertThrows(IllegalArgumentException::class.java) { verifyProxyCorrespondence(source,candidate,geometry) } }
    }
    @Test fun silentOriginalMustRemainSilent() {
        val silent = source.copy(audio=null)
        verifyProxyCorrespondence(silent,silent,geometry)
        assertThrows(IllegalArgumentException::class.java) { verifyProxyCorrespondence(silent,source,geometry) }
    }
}
