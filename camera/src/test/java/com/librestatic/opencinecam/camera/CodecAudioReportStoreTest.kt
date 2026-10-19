/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodecAudioReportStoreTest {
    /** A file's worth of storage that two cache instances can share, as two runs of the app would. */
    private class MemoryStore : CodecAudioReportStore {
        var text: String? = null
        override fun load() = text?.let(CodecAudioReportCodec::decode)
        override fun save(report: CachedCodecAudioReport) { text = CodecAudioReportCodec.encode(report) }
        override fun delete() { text = null }
    }

    private val metadata = CodecAudioMetadata(
        codecs = listOf(
            CodecMetadata("c2.hevc.encoder", "video/hevc", true, listOf("1:256", "2:512"), listOf(21, 2130708361)),
            CodecMetadata("c2.sw.avc", "video/avc", null, emptyList(), emptyList()),
        ),
        audio = AudioMetadata(unprocessedSource = true, noiseSuppressorAvailable = false, automaticGainControlAvailable = null, acousticEchoCancelerAvailable = false),
    )

    private fun key(fingerprint: String = "fp", digest: String = "d", encoders: List<String> = listOf("a|video/avc")) =
        codecAudioValidityKey(fingerprint, digest, encoders)

    @Test fun reportSurvivesEncodeAndDecodeIncludingUnknownValues() {
        val report = CachedCodecAudioReport(key(), metadata)
        assertEquals(report, CodecAudioReportCodec.decode(CodecAudioReportCodec.encode(report)))
    }

    @Test fun anotherCacheInstanceReadsThePersistedReportWithoutProbing() {
        val store = MemoryStore()
        CodecAudioReportCache(store).write(key(), metadata)
        var probed = 0
        val probe = CodecAudioProbe({ probed++; metadata }, CodecAudioReportCache(store))
        assertEquals(metadata, probe.probe(key()))
        assertEquals(0, probed)
    }

    @Test fun anyChangeToTheKeyInvalidatesAndDropsThePersistedFile() {
        val changes = listOf(
            key(fingerprint = "other"),
            key(digest = "other"),
            key(encoders = listOf("a|video/avc", "b|video/hevc")),
            key().copy(protocolVersion = "99"),
            key().copy(appProbeVersion = "99"),
        )
        changes.forEach { changed ->
            val store = MemoryStore()
            CodecAudioReportCache(store).write(key(), metadata)
            val cache = CodecAudioReportCache(store)
            assertNull(changed.toString(), cache.read(changed))
            assertNull(store.text)
        }
    }

    @Test fun reprobeAfterInvalidationPersistsTheNewReport() {
        val store = MemoryStore()
        CodecAudioReportCache(store).write(key(), metadata)
        val fresh = metadata.copy(codecs = emptyList())
        CodecAudioProbe({ fresh }, CodecAudioReportCache(store)).probe(key("new"))
        assertEquals(fresh, CodecAudioReportCache(store).read(key("new")))
    }

    @Test fun corruptReportsReadAsNoCache() {
        assertNull(CodecAudioReportCodec.decode("{not json"))
        assertNull(CodecAudioReportCodec.decode("""{"validityKey":{"schemaMajor":0}}"""))
    }

    @Test fun keyDigestTracksTheCharacteristicsAndTheEncoderList() {
        assertEquals(key(), key())
        assertNotEquals(key().characteristicsDigest, key(digest = "x").characteristicsDigest)
        assertNotEquals(key().characteristicsDigest, key(encoders = emptyList()).characteristicsDigest)
        // The encoder order the platform returns must not matter.
        assertEquals(key(encoders = listOf("a", "b")), key(encoders = listOf("b", "a")))
        assertTrue(key().characteristicsDigest.startsWith("sha256:"))
    }
}
