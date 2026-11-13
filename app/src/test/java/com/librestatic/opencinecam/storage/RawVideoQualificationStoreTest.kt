/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.storage

import com.librestatic.opencinecam.camera.RawBenchmarkStatus
import com.librestatic.opencinecam.camera.RawThroughputBenchmark
import com.librestatic.opencinecam.camera.RawVideoConfig
import com.librestatic.opencinecam.camera.RawVideoQualification
import com.librestatic.opencinecam.camera.RawVideoQualificationRecord
import com.librestatic.opencinecam.camera.RawVideoQualificationSet
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RawVideoQualificationStoreTest {
    private lateinit var dir: File
    private lateinit var file: File

    @Before fun setUp() {
        dir = Files.createTempDirectory("raw-qualification").toFile()
        file = File(dir, RawVideoQualificationStore.FILE_NAME)
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    private fun store(fingerprint: String = FP, protocol: Int = RawThroughputBenchmark.PROTOCOL_VERSION) =
        RawVideoQualificationStore(file, fingerprint, protocol)

    private fun record(
        config: RawVideoConfig = UHD30,
        status: RawBenchmarkStatus = RawBenchmarkStatus.PASS,
        fingerprint: String = FP,
        protocol: Int = RawThroughputBenchmark.PROTOCOL_VERSION,
        destination: String = RawVideoQualificationStore.DESTINATION_MEDIASTORE_DOCUMENTS,
        at: Long = 1_000,
    ) = RawVideoQualificationRecord(
        fingerprint = fingerprint,
        protocolVersion = protocol,
        destinationId = destination,
        config = config,
        ringBytes = RING,
        qualification = RawVideoQualification(
            status = status, reasonCode = if (status == RawBenchmarkStatus.PASS) "ok" else "sweep-median-too-low",
            reasonCodes = emptyList(), streamBytesPerSecond = config.streamBytesPerSecond,
            requiredMedianBytesPerSecond = config.streamBytesPerSecond * 5 / 4, sweepWindows = 60,
            sweepMedianBytesPerSecond = 1_500_000_000, sweepP01BytesPerSecond = 500_000_000, sweepMinBytesPerSecond = 400_000_000,
            medianMarginRatio = 4.0, p01MarginRatio = 1.5, ringBytes = RING, backlogLimitBytes = RING / 2,
            backlog = null, live = null, livePeakFraction = 0.1,
        ),
        measuredAtEpochMs = at,
    )

    @Test
    fun restReferenceOnlyRisesAndStaysPerBuildAndDestination() {
        val docs = RawVideoQualificationStore.DESTINATION_MEDIASTORE_DOCUMENTS
        assertEquals(null, store().restReference(docs))
        assertEquals(1_161_875_296L, store().raiseRestReference(docs, 1_161_875_296L))
        // A lower rested median never lowers it; a higher one raises it.
        assertEquals(1_161_875_296L, store().raiseRestReference(docs, 900_000_000L))
        assertEquals(1_161_875_296L, store().restReference(docs))
        assertEquals(1_200_000_000L, store().raiseRestReference(docs, 1_200_000_000L))
        assertEquals(1_200_000_000L, store().restReference(docs))
        assertEquals(null, store().restReference("app-dir"))
        // Another build has no reference, and raising its own drops the old build's line.
        assertEquals(null, store("other").restReference(docs))
        store("other").raiseRestReference(docs, 500_000_000L)
        assertEquals(null, store().restReference(docs))
        // Records are untouched by the reference, and a corrupt file reads as none.
        store().upsert(record(UHD30))
        assertEquals(1, store().load().records.size)
        File(dir, RawVideoQualificationStore.REST_FILE_NAME).writeText("garbage\n")
        assertEquals(null, store().restReference(docs))
    }

    @Test
    fun missingOrCorruptFileReadsEmpty() {
        assertTrue(store().load().records.isEmpty())
        file.writeText("{not json")
        assertTrue(store().load().records.isEmpty())
        assertTrue(store().qualifiedConfigs("0").isEmpty())
    }

    @Test
    fun upsertRoundTripsAndQueriesQualifiedConfigs() {
        val store = store()
        store.upsert(record(UHD30))
        store.upsert(record(FULL60, status = RawBenchmarkStatus.FAIL))
        store.upsert(record(UHD30.copy(cameraId = "2")))
        store.upsert(record(HD30, destination = "app-dir"))

        val reopened = store()
        assertEquals(4, reopened.load().records.size)
        assertEquals(listOf(UHD30), reopened.qualifiedConfigs("0"))
        assertEquals(listOf(UHD30.copy(cameraId = "2")), reopened.qualifiedConfigs("2"))
        assertEquals(listOf(HD30), reopened.qualifiedConfigs("0", destinationId = "app-dir"))

        // A newer failure for the same configuration replaces the pass.
        reopened.upsert(record(UHD30, status = RawBenchmarkStatus.FAIL, at = 2_000))
        assertTrue(store().qualifiedConfigs("0").isEmpty())
        assertEquals(4, store().load().records.size)
    }

    @Test
    fun aDrivesRecordsAreItsOwnAndThePhonesAreKept() {
        // PLAN-072 Delivery 3: a drive is keyed "saf:<VOLUME>", whatever the case Android reports its volume id in.
        val drive = RawVideoQualificationStore.destinationIdForDrive("fff1-7571")
        assertEquals("saf:FFF1-7571", drive)
        assertEquals(drive, RawVideoQualificationStore.destinationIdForDrive("FFF1-7571"))
        // A drive vold mounts under its stable name (`-01`) keeps its records.
        assertEquals(drive, RawVideoQualificationStore.destinationIdForDrive("FFF1-7571-01"))
        val docs = RawVideoQualificationStore.DESTINATION_MEDIASTORE_DOCUMENTS
        store().upsert(record(UHD30))
        // A slow drive qualifies only the small crop; the phone's records are untouched by the drive's run.
        store().upsertAll(listOf(record(HD30, destination = drive), record(UHD30, status = RawBenchmarkStatus.FAIL, destination = drive, at = 2_000)))
        assertEquals(listOf(UHD30), store().qualifiedConfigs("0", destinationId = docs))
        assertEquals(listOf(HD30), store().qualifiedConfigs("0", destinationId = drive))
        assertTrue(store().qualifiedConfigs("0", destinationId = RawVideoQualificationStore.destinationIdForDrive("AAAA-0001")).isEmpty())
        // Requalifying the phone leaves the drive's records alone.
        store().upsertAll(listOf(record(FULL60, destination = docs, at = 3_000)))
        assertEquals(listOf(HD30), store().qualifiedConfigs("0", destinationId = drive))
        assertEquals(setOf(UHD30, FULL60), store().qualifiedConfigs("0", destinationId = docs).toSet())
    }

    @Test
    fun staleRecordsAreIgnoredAndPruned() {
        // Written by an older build and protocol.
        store("old-fp").save(RawVideoQualificationSet(listOf(
            record(UHD30, fingerprint = "old-fp"),
            record(HD30, protocol = RawThroughputBenchmark.PROTOCOL_VERSION - 1),
        )))
        val store = store()
        assertEquals(2, store.load().records.size)
        assertTrue(store.qualifiedConfigs("0").isEmpty())
        assertTrue(store.currentRecords().isEmpty())
        assertEquals(2, store.pruneStale())
        assertTrue(store.load().records.isEmpty())
        assertEquals(0, store.pruneStale())

        store("old-fp").save(RawVideoQualificationSet(listOf(record(UHD30, fingerprint = "old-fp"))))
        store.upsert(record(FULL60))
        assertEquals(listOf(FULL60), store.load().records.map { it.config })
    }

    @Test
    fun saveReplacesTheFileAtomicallyAndLeavesNoTemporary() {
        val store = store()
        store.upsert(record(UHD30))
        val before = file.readText()
        // A crash after the temporary was written but before the rename leaves the old store intact.
        File(dir, file.name + ".tmp").writeText("{half")
        assertEquals(before, file.readText())
        assertEquals(listOf(UHD30), store.qualifiedConfigs("0"))
        store.upsert(record(HD30))
        assertFalse(File(dir, file.name + ".tmp").exists())
        assertEquals(setOf(UHD30, HD30), store().qualifiedConfigs("0").toSet())
    }

    @Test
    fun concurrentUpsertsFromSeveralInstancesKeepEveryRecord() {
        val pool = Executors.newFixedThreadPool(4)
        val configs = (1..24).map { RawVideoConfig("0", 4080, 3060, 4080 - it * 8, 2160, 30) }
        configs.forEach { config -> pool.execute { store().upsert(record(config)) } }
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
        assertEquals(configs.toSet(), store().qualifiedConfigs("0").toSet())
    }

    private companion object {
        const val FP = "google/razr/blanc:15/V1/1:user/release-keys"
        const val RING = 1L shl 30
        val UHD30 = RawVideoConfig("0", 4080, 3060, 3840, 2160, 30)
        val FULL60 = RawVideoConfig("0", 4080, 3060, 4080, 3060, 60)
        val HD30 = RawVideoConfig("0", 4080, 3060, 1920, 1080, 30)
    }
}
