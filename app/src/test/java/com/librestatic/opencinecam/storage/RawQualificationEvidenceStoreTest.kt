/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.storage

import com.librestatic.opencinecam.camera.RawThroughputBenchmark
import com.librestatic.opencinecam.rawvideo.RawQualificationEvidence
import com.librestatic.opencinecam.rawvideo.RawQualificationEvidenceRules
import com.librestatic.opencinecam.rawvideo.RawQualificationStaging
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RawQualificationEvidenceStoreTest {
    private lateinit var dir: File
    private lateinit var file: File

    @Before fun setUp() {
        dir = Files.createTempDirectory("raw-qualification-evidence").toFile()
        file = File(dir, RawQualificationEvidenceStore.FILE_NAME)
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    private fun store(fingerprint: String = FP, app: Long = APP, protocol: Int = RawThroughputBenchmark.PROTOCOL_VERSION) =
        RawQualificationEvidenceStore(file, fingerprint, app, protocol)

    private fun entry(
        key: String = "camera|0:full@30",
        kind: String = RawQualificationEvidenceRules.KIND_CAMERA_CHECK,
        status: String = "PASS",
        codes: List<String> = emptyList(),
        at: Long = NOW - 1_000,
        fingerprint: String = FP,
        app: Long = APP,
        protocol: Int = RawThroughputBenchmark.PROTOCOL_VERSION,
        slots: Int = 24,
        fps: Double? = 29.97,
        row: String = """{"stage":"camera","status":"PASS","achievedFps":29.97}""",
    ) = RawQualificationEvidence(kind, key, fingerprint, app, protocol, at, status, codes, slots, fps, row)

    @Test fun anEntryRoundTripsThroughTheFile() {
        val camera = entry(codes = listOf(RawQualificationStaging.REASON_FPS_REDUCED), fps = 26.0)
        val bench = entry(key = "encode-bench|0:full@60", kind = RawQualificationEvidenceRules.KIND_ENCODE_BENCH, status = "FAIL",
            codes = listOf(RawQualificationStaging.REASON_COMPRESSED_CAMERA_STARVED), fps = null)
        assertTrue(store().put(camera, NOW))
        assertTrue(store().put(bench, NOW))
        assertEquals(listOf(camera, bench), store().load())
        assertEquals(camera, store().find(camera.kind, camera.key, NOW))
        assertEquals(bench, store().find(bench.kind, bench.key, NOW))
        // The kind is part of the lookup.
        assertNull(store().find(RawQualificationEvidenceRules.KIND_ENCODE_BENCH, camera.key, NOW))
        assertFalse(File(dir, RawQualificationEvidenceStore.FILE_NAME + ".tmp").exists())
        assertEquals(listOf(camera, bench), RawQualificationEvidenceStore.decode(RawQualificationEvidenceStore.encode(listOf(camera, bench))))
    }

    @Test fun anEntryExpiresAfterThirtyDays() {
        assertTrue(store().put(entry(), NOW))
        assertEquals(entry(), store().find(entry().kind, entry().key, NOW - 1_000 + RawQualificationEvidenceRules.MAX_AGE_MS))
        assertNull(store().find(entry().kind, entry().key, NOW + RawQualificationEvidenceRules.MAX_AGE_MS))
        // An expired entry is not stored, and the next write drops the old one.
        assertFalse(store().put(entry(key = "old", at = NOW - RawQualificationEvidenceRules.MAX_AGE_MS - 1), NOW))
        val later = NOW + RawQualificationEvidenceRules.MAX_AGE_MS
        assertTrue(store().put(entry(key = "new", at = later), later))
        assertEquals(listOf("new"), store().load().map { it.key })
    }

    @Test fun anotherBuildOrProtocolFindsNothingAndDropsTheOldEntries() {
        assertTrue(store().put(entry(), NOW))
        assertNull(store(fingerprint = "other").find(entry().kind, entry().key, NOW))
        assertNull(store(app = APP + 1).find(entry().kind, entry().key, NOW))
        assertNull(store(protocol = RawThroughputBenchmark.PROTOCOL_VERSION + 1).find(entry().kind, entry().key, NOW))
        // An entry of another build is never written by this one.
        assertFalse(store().put(entry(key = "foreign", fingerprint = "other"), NOW))
        // The new build's first write drops the old build's entries.
        assertTrue(store(app = APP + 1).put(entry(key = "fresh", app = APP + 1), NOW))
        assertEquals(listOf("fresh"), store(app = APP + 1).load().map { it.key })
        assertNull(store().find(entry().kind, entry().key, NOW))
    }

    @Test fun inconclusiveOrHotResultsAreNeverStored() {
        val camera = RawQualificationEvidenceRules.KIND_CAMERA_CHECK
        val bench = RawQualificationEvidenceRules.KIND_ENCODE_BENCH
        assertFalse(store().put(entry(status = "UNKNOWN", codes = listOf("thermal-wait-timeout")), NOW))
        assertFalse(store().put(entry(status = "FAIL", codes = listOf(RawQualificationStaging.REASON_THERMAL_LIMIT)), NOW))
        assertFalse(store().put(entry(status = "FAIL", codes = listOf("fps-not-reached")), NOW))
        assertFalse(store().put(entry(status = "UNKNOWN", codes = listOf("exception")), NOW))
        assertFalse(store().put(entry(slots = 0), NOW))
        assertFalse(store().put(entry(kind = bench, status = "FAIL", codes = listOf(RawQualificationStaging.REASON_THERMAL_LIMIT)), NOW))
        assertFalse(store().put(entry(kind = bench, status = "FAIL", codes = listOf("encoder-error")), NOW))
        assertFalse(store().put(entry(kind = "live"), NOW))
        assertTrue(store().load().isEmpty())
        assertFalse(file.exists())
        assertTrue(store().put(entry(kind = camera), NOW))
    }

    @Test fun aNewResultReplacesTheOldOneOfTheSameKey() {
        assertTrue(store().put(entry(fps = 29.0), NOW))
        assertTrue(store().put(entry(key = "other"), NOW))
        assertTrue(store().put(entry(fps = 30.0, at = NOW), NOW))
        assertEquals(2, store().load().size)
        assertEquals(30.0, store().find(entry().kind, entry().key, NOW)!!.achievedFps!!, 0.0)
    }

    @Test fun aCheckWithMoreRingSlotsThanNowIsNotReused() {
        assertTrue(store().put(entry(slots = 24), NOW))
        assertEquals(entry(slots = 24), store().find(entry().kind, entry().key, NOW, ringSlots = 24))
        assertNull(store().find(entry().kind, entry().key, NOW, ringSlots = 12))
    }

    @Test fun aMissingCorruptOrUnknownSchemaFileReadsEmpty() {
        assertTrue(store().load().isEmpty())
        file.writeText("{not json")
        assertTrue(store().load().isEmpty())
        file.writeText("""{"schema":99,"entries":[]}""")
        assertTrue(store().load().isEmpty())
        // A bad entry is dropped, the good ones kept.
        val good = RawQualificationEvidenceStore.encode(listOf(entry()))
        file.writeText(good.replace("\"entries\":[", "\"entries\":[{\"kind\":\"camera\"},"))
        assertEquals(listOf(entry()), store().load())
        // A corrupt file is replaced by the next write.
        file.writeText("garbage")
        assertTrue(store().put(entry(), NOW))
        assertEquals(listOf(entry()), store().load())
    }

    private companion object {
        const val FP = "google/razr/blanc:15/V1/1:user/release-keys"
        const val APP = 125L
        const val NOW = 100L * 24 * 60 * 60 * 1_000
    }
}
