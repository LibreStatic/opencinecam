/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import com.librestatic.opencinecam.camera.AudioMetadata
import com.librestatic.opencinecam.camera.CodecAudioMetadata
import com.librestatic.opencinecam.camera.CodecAudioProbe
import com.librestatic.opencinecam.camera.CodecAudioReportCache
import com.librestatic.opencinecam.camera.CodecMetadata
import com.librestatic.opencinecam.camera.codecAudioValidityKey
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AtomicFileCodecAudioReportStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private val metadata = CodecAudioMetadata(
        listOf(CodecMetadata("c2.hevc.encoder", "video/hevc", true, listOf("1:256"), listOf(21))),
        AudioMetadata(true, false, null, false),
    )
    private fun file() = File(folder.root, "codec-audio-report.json")
    private fun store() = AtomicFileCodecAudioReportStore(file())
    private fun key(fingerprint: String = "fp") = codecAudioValidityKey(fingerprint, "digest", listOf("c2.hevc.encoder|video/hevc"))

    @Test fun aSecondRunReadsTheReportFromDiskWithoutProbing() {
        CodecAudioReportCache(store()).write(key(), metadata)
        var probed = 0
        val probe = CodecAudioProbe({ probed++; metadata }, CodecAudioReportCache(store()))
        assertEquals(metadata, probe.probe(key()))
        assertEquals(0, probed)
    }

    @Test fun aNewBuildInvalidatesTheFileAndTheNextProbeReplacesIt() {
        CodecAudioReportCache(store()).write(key(), metadata)
        val cache = CodecAudioReportCache(store())
        assertNull(cache.read(key("next-build")))
        assertFalse(file().exists())
        val fresh = metadata.copy(codecs = emptyList())
        CodecAudioProbe({ fresh }, cache).probe(key("next-build"))
        assertEquals(fresh, CodecAudioReportCache(store()).read(key("next-build")))
    }

    @Test fun missingOrCorruptFilesReadAsNoCache() {
        assertNull(store().load())
        file().writeText("{not json")
        assertNull(store().load())
    }
}
