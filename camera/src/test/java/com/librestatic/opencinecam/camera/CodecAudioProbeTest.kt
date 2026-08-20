/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import com.librestatic.opencinecam.core.model.CacheValidityKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodecAudioProbeTest {
    private fun key(fingerprint: String = "fingerprint") = CacheValidityKey(
        schemaMajor = 1,
        protocolVersion = "probe-1",
        appProbeVersion = "0.1.0",
        buildFingerprint = fingerprint,
        characteristicsDigest = "digest",
        cameraId = "0",
        physicalCameraId = null,
        codecName = null,
        graphId = com.librestatic.opencinecam.core.model.GraphId("graph"),
    )

    @Test
    fun cacheHitsOnlyForExactValidityKey() {
        var calls = 0
        val source = CodecAudioMetadataSource {
            calls += 1
            CodecAudioMetadata(emptyList(), AudioMetadata(true, false, false, false))
        }
        val cache = CodecAudioReportCache()
        val probe = CodecAudioProbe(source, cache)
        probe.probe(key())
        probe.probe(key())
        assertEquals(1, calls)
        probe.probe(key("changed"))
        assertEquals(2, calls)
        assertNull(cache.read(key("other")))
    }

    @Test
    fun codecMetadataOrderingIsDeterministic() {
        val metadata = CodecAudioMetadata(
            codecs = listOf(
                CodecMetadata("codec-b", "video/hevc", true, listOf("1:2"), listOf(1)),
                CodecMetadata("codec-a", "video/avc", false, emptyList(), emptyList()),
            ),
            audio = AudioMetadata(null, null, null, null),
        )
        assertNotEquals(metadata.codecs[0].mime, metadata.codecs[1].mime)
    }
}
