/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RawThroughputTest {
    @Test
    fun qualifiesSixtySecondWindowWithP01MarginAndZeroLoss() {
        val samples = listOf(
            RawThroughputSample(1000, 1_300_000, 0),
            RawThroughputSample(1000, 1_260_000, 0),
            RawThroughputSample(1000, 1_280_000, 0),
        )
        val result = RawThroughputBenchmark.qualify(60, 1_000_000, samples)
        assertEquals(RawBenchmarkStatus.PASS, result.status)
        assertEquals(1_260_000L, result.p01BytesPerSecond)
        assertEquals(1_250_000L, result.requiredBytesPerSecond)
        assertEquals(0, result.droppedFrames)
    }

    @Test
    fun lossAndShortWindowFailWhileUnknownStaysDistinct() {
        val sample = RawThroughputSample(1000, 1_000, 1)
        val loss = RawThroughputBenchmark.qualify(60, 1_000, listOf(sample))
        assertEquals(RawBenchmarkStatus.FAIL, loss.status)
        assertEquals("raw-frame-loss", loss.reasonCode)
        val short = RawThroughputBenchmark.qualify(10, 1_000, listOf(sample.copy(droppedFrames = 0)))
        assertEquals(RawBenchmarkStatus.FAIL, short.status)
        val unknown = RawThroughputBenchmark.qualify(60, null, listOf(sample))
        assertEquals(RawBenchmarkStatus.UNKNOWN, unknown.status)
        assertNull(unknown.p01BytesPerSecond)
    }

    @Test
    fun benchmarkLifecycleExposesCancellationDuplicateAndCleanup() {
        val session = RawBenchmarkSession()
        val sample = listOf(RawThroughputSample(1000, 1000, 0))
        assertEquals(RawBenchmarkCommandStatus.CANCELLED, session.cancel("cancel"))
        assertEquals(RawBenchmarkCommandStatus.CANCELLED, session.run("cancel", 60, 1000, sample))
        assertEquals(RawBenchmarkCommandStatus.STARTED, session.run("one", 60, 1000, sample))
        assertEquals(RawBenchmarkCommandStatus.DUPLICATE, session.run("one", 60, 1000, sample))
        assertTrue(session.result("one") != null)
        session.close()
        assertEquals(RawBenchmarkCommandStatus.CLOSED, session.run("two", 60, 1000, sample))
    }

    @Test
    fun qualifyWithoutBufferMatchesTheOldRule() {
        val samples = List(60) { RawThroughputSample(1000, 1_300_000, 0) }
        assertEquals(
            RawThroughputBenchmark.qualify(60, 1_000_000, samples),
            RawThroughputBenchmark.qualifyWithoutBuffer(60, 1_000_000, samples),
        )
        val dip = samples.toMutableList().apply { this[0] = RawThroughputSample(1000, 500_000, 0) }
        assertEquals("raw-throughput-margin-fail", RawThroughputBenchmark.qualifyWithoutBuffer(60, 1_000_000, dip).reasonCode)
    }

    @Test
    fun configDerivesRaw10FrameAndStreamRates() {
        val full = RawVideoConfig("0", 4080, 3060, 4080, 3060, 30)
        assertEquals(15_606_000L, full.bytesPerFrame)
        assertEquals(468_180_000L, full.streamBytesPerSecond)
        val crop = RawVideoConfig("0", 4080, 3060, 1920, 1080, 30)
        assertEquals(2_592_000L, crop.bytesPerFrame)
        assertEquals(77_760_000L, crop.streamBytesPerSecond)
        assertEquals("raw10", crop.encoding)
    }

    @Test
    fun sweepReportsMedianP01AndMinimum() {
        val sweep = RawStorageSweep("docs", (1..100).map { RawThroughputSample(1000, it * 1_000L, 0) })
        assertEquals(50_500L, sweep.medianBytesPerSecond)
        assertEquals(1_000L, sweep.p01BytesPerSecond)
        assertEquals(1_000L, sweep.minBytesPerSecond)
        val odd = RawStorageSweep("docs", listOf(3L, 1L, 2L).map { RawThroughputSample(1000, it, 0) })
        assertEquals(2L, odd.medianBytesPerSecond)
        assertNull(RawStorageSweep("docs", emptyList()).medianBytesPerSecond)
    }

    @Test
    fun steadyStorageAboveTheStreamNeverBuildsABacklog() {
        val result = RawThroughputBenchmark.simulateBacklog(STREAM, RING, rates(List(60) { 2 * STREAM }))
        assertEquals(0L, result.maxBacklogBytes)
        assertEquals(0L, result.worstCaseMaxBacklogBytes)
        assertFalse(result.overflowed)
        assertFalse(result.worstCaseOverflowed)
    }

    @Test
    fun singleDipIsAbsorbedAndDrained() {
        val list = MutableList(60) { 2 * STREAM }.apply { this[10] = STREAM - 100 }
        val result = RawThroughputBenchmark.simulateBacklog(STREAM, RING, rates(list))
        assertEquals(100L, result.maxBacklogBytes)
        assertEquals(100.0 / RING, result.maxBacklogFraction, 1e-12)
        assertFalse(result.overflowed)
    }

    @Test
    fun sustainedDeficitOverflowsTheRing() {
        val stream = 100_000_000L
        val result = RawThroughputBenchmark.simulateBacklog(stream, RING, rates(List(60) { stream / 2 }))
        assertEquals(60 * (stream / 2), result.maxBacklogBytes)
        assertTrue(result.overflowed)
        assertTrue(result.maxBacklogFraction > 1.0)
    }

    @Test
    fun sortedWorstCaseChainsEveryDip() {
        // Dips alternate with recoveries in measured order, so they never accumulate there.
        val list = List(60) { if (it % 2 == 0) STREAM - 100 else STREAM + 100 }
        val result = RawThroughputBenchmark.simulateBacklog(STREAM, RING, rates(list))
        assertEquals(100L, result.maxBacklogBytes)
        assertEquals(3_000L, result.worstCaseMaxBacklogBytes)
        assertEquals(3_000.0 / RING, result.worstCaseMaxBacklogFraction, 1e-12)
    }

    @Test
    fun allChecksPassForAQualifiedConfiguration() {
        val result = RawThroughputBenchmark.qualifyWithBuffer(CONFIG, RING, goodSweep(), goodLive())
        assertEquals(RawBenchmarkStatus.PASS, result.status)
        assertEquals("raw-video-pass", result.reasonCode)
        assertEquals(listOf("raw-video-pass"), result.reasonCodes)
        assertEquals(CONFIG.streamBytesPerSecond, result.streamBytesPerSecond)
        assertEquals(CONFIG.streamBytesPerSecond * 11_225 / 10_000, result.requiredMedianBytesPerSecond)
        assertEquals(60, result.sweepWindows)
        assertEquals(2.0, result.medianMarginRatio!!, 1e-9)
        assertEquals(RING / 2, result.backlogLimitBytes)
        assertNotNull(result.backlog)
        assertEquals(0.25, result.livePeakFraction!!, 1e-9)
    }

    @Test
    fun shortOrMissingSweepIsInvalid() {
        val short = RawStorageSweep("docs", goodSweep().samples.take(59))
        assertEquals(listOf("sweep-window-invalid"), qualify(sweep = short).reasonCodes)
        val halfSecond = RawStorageSweep("docs", List(60) { RawThroughputSample(500, CONFIG.streamBytesPerSecond, 0) })
        assertEquals("sweep-window-invalid", qualify(sweep = halfSecond).reasonCode)
        val empty = qualify(sweep = RawStorageSweep("docs", emptyList()))
        assertEquals(RawBenchmarkStatus.FAIL, empty.status)
        assertEquals(listOf("sweep-window-invalid", "sweep-median-margin-fail"), empty.reasonCodes)
        assertNull(empty.backlog)
    }

    @Test
    fun medianBelowMarginFails() {
        val stream = CONFIG.streamBytesPerSecond
        val sweep = RawStorageSweep("docs", rates(List(60) { stream * 110 / 100 }))
        val result = qualify(sweep = sweep)
        assertEquals(RawBenchmarkStatus.FAIL, result.status)
        assertEquals(listOf("sweep-median-margin-fail"), result.reasonCodes)
        assertEquals(1.1, result.medianMarginRatio!!, 1e-9)
    }

    @Test
    fun medianMarginIsOnePointOneTwoTwoFiveTimesTheStream() {
        // The Razr's full 4000x3000 RAW10 at 60 fps against its measured sweep median.
        val razr = RawVideoConfig("0", 4000, 3000, 4000, 3000, 60)
        assertEquals(900_000_000L, razr.streamBytesPerSecond)
        val live = goodLive().copy(config = razr, framesWritten = 1_200, completedCaptures = 1_200)
        fun at(median: Long) = RawThroughputBenchmark.qualifyWithBuffer(
            razr, RING * 4, RawStorageSweep("docs", rates(List(60) { median })), live,
        )
        val measured = at(1_048_958_601L)
        assertEquals(RawBenchmarkStatus.PASS, measured.status)
        assertEquals(1_010_250_000L, measured.requiredMedianBytesPerSecond)
        assertEquals(RawBenchmarkStatus.PASS, at(1_010_250_000L).status)
        val below = at(1_010_249_999L)
        assertEquals(RawBenchmarkStatus.FAIL, below.status)
        assertEquals(listOf("sweep-median-margin-fail"), below.reasonCodes)
    }

    @Test
    fun ruleOneKeepsItsOnePointTwoFiveMargin() {
        val samples = List(60) { RawThroughputSample(1000, 1_200_000, 0) }
        val result = RawThroughputBenchmark.qualifyWithoutBuffer(60, 1_000_000, samples)
        assertEquals(RawBenchmarkStatus.FAIL, result.status)
        assertEquals(1_250_000L, result.requiredBytesPerSecond)
        assertEquals("raw-throughput-margin-fail", result.reasonCode)
    }

    @Test
    fun backlogBeyondHalfTheRingFails() {
        val stream = CONFIG.streamBytesPerSecond
        // Three consecutive seconds at a quarter of the stream: 2.25 s of stream is queued, more than half the ring.
        val list = MutableList(60) { 2 * stream }.apply { for (i in 20..22) this[i] = stream / 4 }
        val ring = stream * 4
        val result = RawThroughputBenchmark.qualifyWithBuffer(CONFIG, ring, RawStorageSweep("docs", rates(list)), goodLive())
        assertEquals(listOf("buffer-backlog-exceeded"), result.reasonCodes)
        assertTrue(result.backlog!!.maxBacklogBytes > result.backlogLimitBytes)
    }

    @Test
    fun sortedWorstCaseIsEvidenceOnly() {
        val stream = CONFIG.streamBytesPerSecond
        val list = List(60) { if (it % 2 == 0) stream / 2 else stream * 4 }
        val ring = stream * 2
        val result = RawThroughputBenchmark.qualifyWithBuffer(CONFIG, ring, RawStorageSweep("docs", rates(list)), goodLive())
        assertEquals(RawBenchmarkStatus.PASS, result.status)
        assertTrue(result.backlog!!.worstCaseOverflowed)
    }

    @Test
    fun missingLiveRunAloneIsUnknown() {
        val result = qualify(live = null)
        assertEquals(RawBenchmarkStatus.UNKNOWN, result.status)
        assertEquals(listOf("live-run-missing"), result.reasonCodes)
        val failing = qualify(sweep = RawStorageSweep("docs", emptyList()), live = null)
        assertEquals(RawBenchmarkStatus.FAIL, failing.status)
        assertTrue("live-run-missing" in failing.reasonCodes)
    }

    @Test
    fun everyLiveRunCheckHasItsOwnReason() {
        assertEquals(listOf("live-run-too-short"), qualify(live = goodLive().copy(durationMs = 19_999)).reasonCodes)
        assertEquals(listOf("raw-frame-loss"), qualify(live = goodLive().copy(framesWritten = 599)).reasonCodes)
        assertEquals(listOf("ring-overflow"), qualify(live = goodLive().copy(ringOverflow = true)).reasonCodes)
        assertEquals(listOf("ring-peak-exceeded"), qualify(live = goodLive().copy(peakRingBytes = RING / 2 + 1)).reasonCodes)
        assertEquals(RawBenchmarkStatus.PASS, qualify(live = goodLive().copy(peakRingBytes = RING / 2)).status)
        val other = goodLive().copy(config = CONFIG.copy(fps = 60))
        assertEquals(listOf("live-run-config-mismatch"), qualify(live = other).reasonCodes)
        val all = qualify(live = goodLive().copy(durationMs = 1_000, framesWritten = 1, ringOverflow = true, peakRingBytes = RING))
        assertEquals(RawBenchmarkStatus.FAIL, all.status)
        assertEquals("live-run-too-short", all.reasonCode)
        assertEquals(listOf("live-run-too-short", "raw-frame-loss", "ring-overflow", "ring-peak-exceeded"), all.reasonCodes)
    }

    @Test
    fun qualifiedConfigsIgnoreStaleFailedAndForeignRecords() {
        val pass = qualify()
        val fail = qualify(live = goodLive().copy(ringOverflow = true))
        val other = CONFIG.copy(cropWidth = 1920, cropHeight = 1080)
        val set = RawVideoQualificationSet(
            listOf(
                record(CONFIG, pass, at = 1),
                record(other, pass, at = 1),
                record(other, fail, at = 2),
                record(CONFIG.copy(fps = 60), pass, fingerprint = "old-build"),
                record(CONFIG.copy(fps = 24), pass, protocol = 1),
                record(CONFIG.copy(fps = 15), pass, destination = "app-dir"),
                record(CONFIG.copy(cameraId = "1"), pass),
            ),
        )
        assertEquals(listOf(CONFIG), set.qualifiedConfigs("build", destinationId = "docs", cameraId = "0"))
        assertEquals(listOf(CONFIG.copy(fps = 24)), set.qualifiedConfigs("build", 1, "docs", "0"))
        assertEquals(5, set.pruned("build").records.size)
        val revoked = set.upsert(record(CONFIG, fail, at = 3))
        assertEquals(emptyList<RawVideoConfig>(), revoked.qualifiedConfigs("build", destinationId = "docs", cameraId = "0"))
        assertEquals(set.records.size, revoked.records.size)
    }

    @Test
    fun codecRoundTripsRecordsAndSkipsDamagedOnes() {
        val set = RawVideoQualificationSet(
            listOf(record(CONFIG, qualify(), at = 7), record(CONFIG.copy(fps = 60), qualify(live = null, sweep = RawStorageSweep("docs", emptyList())))),
        )
        val encoded = RawVideoQualificationCodec.encode(set)
        assertEquals(set.records, RawVideoQualificationCodec.decode(encoded).records)
        val damaged = encoded.replaceFirst("\"fingerprint\":\"build\"", "\"fingerprint\":7")
        assertEquals(1, RawVideoQualificationCodec.decode(damaged).records.size)
        assertTrue(RawVideoQualificationCodec.decode("not json").records.isEmpty())
        assertTrue(RawVideoQualificationCodec.decode("{\"schema\":\"other\",\"schemaVersion\":1}").records.isEmpty())
    }

    @Test
    fun codecRoundTripsTheStagedQualificationFields() {
        val staged = record(CONFIG, qualify(live = null)).copy(method = "extrapolated", coveredBy = "full@60", evidence = "{\"fps\":30.0}")
        val decoded = RawVideoQualificationCodec.decode(RawVideoQualificationCodec.encode(RawVideoQualificationSet(listOf(staged)))).records
        assertEquals(listOf(staged), decoded)
        // A record written before the fields decodes with them null.
        val plain = record(CONFIG, qualify())
        val encoded = RawVideoQualificationCodec.encodeRecord(plain)
        assertTrue("method" !in encoded && "coveredBy" !in encoded && "evidence" !in encoded)
        assertEquals(plain, RawVideoQualificationCodec.decodeRecord(encoded))
    }

    @Test
    fun codecRoundTripsBlockPackAndLegacyLj92Records() {
        val blockPack = record(CONFIG.copy(encoding = RawVideoConfig.ENCODING_BLOCK_PACK), qualify(live = null))
            .copy(method = "compressed-extrapolated", coveredBy = "full@60")
        // A record qualified before block-pack: LJ92 encoding, the old method and reason names.
        val oldFail = qualify(live = null).copy(status = RawBenchmarkStatus.FAIL, reasonCode = "lj92-encode-too-slow",
            reasonCodes = listOf("lj92-encode-too-slow"))
        val lj92 = record(CONFIG.copy(encoding = RawVideoConfig.ENCODING_LJ92), oldFail).copy(method = "lj92-extrapolated")
        val decoded = RawVideoQualificationCodec.decode(RawVideoQualificationCodec.encode(RawVideoQualificationSet(listOf(blockPack, lj92)))).records
        assertEquals(listOf(blockPack, lj92), decoded)
        assertEquals("bp", decoded[0].config.encoding)
        assertTrue(decoded.all { it.config.isCompressed })
        assertEquals(listOf("bp", "lj92"), RawVideoConfig.COMPRESSED_ENCODINGS)
    }

    @Test
    fun codecRoundTripsTheBurstSecondsAndOldRecordsLoadWithoutIt() {
        val risky = record(CONFIG, qualify(live = null)).copy(method = "long-run", burstSeconds = 75L)
        val encoded = RawVideoQualificationCodec.encodeRecord(risky)
        assertEquals(75L, encoded["burstSeconds"]!!.jsonPrimitive.long)
        assertEquals(listOf(risky), RawVideoQualificationCodec.decode(RawVideoQualificationCodec.encode(RawVideoQualificationSet(listOf(risky)))).records)
        val plain = record(CONFIG, qualify())
        assertTrue("burstSeconds" !in RawVideoQualificationCodec.encodeRecord(plain))
        // A record stored before the field (or with an explicit null) loads with it null.
        val old = kotlinx.serialization.json.JsonObject(encoded - "burstSeconds")
        assertNull(RawVideoQualificationCodec.decodeRecord(old).burstSeconds)
        val nulled = kotlinx.serialization.json.JsonObject(encoded + ("burstSeconds" to kotlinx.serialization.json.JsonNull))
        assertNull(RawVideoQualificationCodec.decodeRecord(nulled).burstSeconds)
    }

    @Test
    fun codecRoundTripsTheAchievedRateAndOldRecordsLoadWithoutIt() {
        val reduced = record(CONFIG, qualify()).copy(method = "long-run", achievedFps = 51.9)
        val encoded = RawVideoQualificationCodec.encodeRecord(reduced)
        assertEquals(51.9, encoded["achievedFps"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(listOf(reduced), RawVideoQualificationCodec.decode(RawVideoQualificationCodec.encode(RawVideoQualificationSet(listOf(reduced)))).records)
        assertTrue("achievedFps" !in RawVideoQualificationCodec.encodeRecord(record(CONFIG, qualify())))
        // A record stored before the field loads with it null, which reads as reaching its target.
        assertNull(RawVideoQualificationCodec.decodeRecord(kotlinx.serialization.json.JsonObject(encoded - "achievedFps")).achievedFps)
    }

    @Test
    fun theProtocolIsTheStagedOne() {
        assertEquals(3, RawThroughputBenchmark.PROTOCOL_VERSION)
    }

    @Test
    fun aCompressionRatioDividesTheStorageNeedAndDrainsTheRingFaster() {
        // The Razr's full@60 against a 1.0 GB/s sweep: too slow uncompressed, enough at a 2.1x ratio.
        val razr = RawVideoConfig("0", 4000, 3000, 4000, 3000, 60, RawVideoConfig.ENCODING_LJ92)
        val sweep = RawStorageSweep("docs", rates(List(60) { 1_000_000_000L }))
        val plain = RawThroughputBenchmark.qualifyWithBuffer(razr, RING * 4, sweep, null)
        assertEquals(listOf("sweep-median-margin-fail", "live-run-missing"), plain.reasonCodes)
        val compressed = RawThroughputBenchmark.qualifyWithBuffer(razr, RING * 4, sweep, null, compressionRatio = 2.1)
        assertEquals(listOf("live-run-missing"), compressed.reasonCodes)
        // ceil(900 MB/s x 1.1225 / 2.1); the stored stream and median stay the measured ones.
        assertEquals(481_071_429L, compressed.requiredMedianBytesPerSecond)
        assertEquals(900_000_000L, compressed.streamBytesPerSecond)
        assertEquals(1_000_000_000L, compressed.sweepMedianBytesPerSecond)
        assertEquals(1.0 / 0.9 * 2.1, compressed.medianMarginRatio!!, 1e-9)
        assertEquals(0L, compressed.backlog!!.maxBacklogBytes)
        // A dip the plain stream would queue is drained at the compressed rate.
        val dip = RawStorageSweep("docs", rates(MutableList(60) { 1_000_000_000L }.apply { this[10] = 400_000_000L }))
        assertTrue(RawThroughputBenchmark.qualifyWithBuffer(razr, RING * 4, dip, null).backlog!!.maxBacklogBytes > 0)
        assertEquals(100_000_000L, RawThroughputBenchmark.qualifyWithBuffer(razr, RING * 4, dip, null, 2.0).backlog!!.maxBacklogBytes)
    }

    private fun qualify(
        sweep: RawStorageSweep = goodSweep(),
        live: RawLiveRun? = goodLive(),
    ) = RawThroughputBenchmark.qualifyWithBuffer(CONFIG, RING, sweep, live)

    private fun record(
        config: RawVideoConfig,
        qualification: RawVideoQualification,
        at: Long = 0,
        fingerprint: String = "build",
        protocol: Int = RawThroughputBenchmark.PROTOCOL_VERSION,
        destination: String = "docs",
    ) = RawVideoQualificationRecord(fingerprint, protocol, destination, config, RING, qualification, at)

    private fun goodSweep() = RawStorageSweep("docs", rates(List(60) { 2 * CONFIG.streamBytesPerSecond }))

    private fun goodLive() = RawLiveRun(CONFIG, 20_000, 600, 600, false, RING / 4, RING, 12.5)

    private fun rates(bytesPerSecond: List<Long>) = bytesPerSecond.map { RawThroughputSample(1000, it, 0) }

    private companion object {
        const val STREAM = 1_000_000L
        const val RING = 1_000_000_000L
        val CONFIG = RawVideoConfig("0", 4080, 3060, 3840, 2160, 30)
    }
}
