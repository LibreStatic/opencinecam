/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Main10FileCheckTest {
    private val hlg = Main10FileSignal("video/hevc", "Main10", "BT.2020", "HLG", 10)
    private val log = Main10FileSignal("video/hevc", "Main10", "BT.2020", null, 10)

    private class MemoryStore : Main10FileCheckStore {
        var saved: Main10FileCheck? = null
        override fun load() = saved
        override fun save(check: Main10FileCheck) { saved = check }
    }

    @Test
    fun hlgPassesOnSignallingButPrecisionIsNeverClaimed() {
        val check = Main10FileChecks.evaluate(Main10TakeKind.HLG, hlg, "take.mp4", 5L)
        assertEquals(QualificationStatus.PASS, check.fileSignal.status)
        assertEquals(QualificationStatus.UNKNOWN, check.effectivePrecision.status)
        assertEquals(PRECISION_REQUIRES_CONTROLLED_CAPTURE, check.effectivePrecision.reasonCode)
        assertNull(check.effectivePrecision.measuredBits)
    }

    @Test
    fun hlgFailsWhenTheTransferIsMissingTheHlgTag() {
        val check = Main10FileChecks.evaluate(Main10TakeKind.HLG, hlg.copy(colorTransfer = "SDR"), null, 5L)
        assertEquals(QualificationStatus.FAIL, check.fileSignal.status)
        assertEquals("color-signaling-mismatch", check.fileSignal.reasonCode)
    }

    @Test
    fun logIsJudgedOnMain10WithoutAnHlgTag() {
        assertEquals(QualificationStatus.PASS, Main10FileChecks.evaluate(Main10TakeKind.LOG, log, null, 1L).fileSignal.status)
        assertEquals("profile-not-main10",
            Main10FileChecks.evaluate(Main10TakeKind.LOG, log.copy(profile = "Main"), null, 1L).fileSignal.reasonCode)
        assertEquals(QualificationStatus.UNKNOWN, Main10FileChecks.evaluate(Main10TakeKind.LOG, log.copy(bitDepth = null), null, 1L).fileSignal.status)
    }

    @Test
    fun anUnreadableFileIsUnknownNeverAPass() {
        val check = Main10FileChecks.evaluate(Main10TakeKind.HLG, null, null, 1L)
        assertEquals(QualificationStatus.UNKNOWN, check.fileSignal.status)
    }

    @Test
    fun codecRoundTripsAndRejectsForeignText() {
        val check = Main10FileChecks.evaluate(Main10TakeKind.HLG, hlg, "take.mp4", 42L)
        assertEquals(check, Main10FileCheckCodec.decode(Main10FileCheckCodec.encode(check)))
        val bare = Main10FileChecks.evaluate(Main10TakeKind.LOG, log, null, 42L)
        assertEquals(bare, Main10FileCheckCodec.decode(Main10FileCheckCodec.encode(bare)))
        assertNull(Main10FileCheckCodec.decode("{\"schema\":\"other\"}"))
        assertNull(Main10FileCheckCodec.decode("not json"))
    }

    @Test
    fun runnerChecksOffTheCallingThreadAndPersistsTheResult() {
        val queued = ArrayList<Runnable>()
        val store = MemoryStore()
        val runner = Main10FileCheckRunner(store, { hlg }, Executor { queued += it }, { 7L })
        runner.schedule("content://take", Main10TakeKind.HLG, "take.mp4")
        assertNull(store.saved)
        assertEquals(1, queued.size)
        queued.single().run()
        assertEquals(QualificationStatus.PASS, store.saved?.fileSignal?.status)
        assertEquals(7L, store.saved?.checkedAtMs)
    }

    @Test
    fun aFailingProbeOrStoreNeverEscapesAndAProbeFailureIsUnknown() {
        val store = MemoryStore()
        Main10FileCheckRunner(store, { error("boom") }, Executor { it.run() }).schedule("u", Main10TakeKind.LOG, null)
        assertEquals(QualificationStatus.UNKNOWN, store.saved?.fileSignal?.status)
        val failingStore = object : Main10FileCheckStore {
            override fun load() = null
            override fun save(check: Main10FileCheck) = error("disk full")
        }
        Main10FileCheckRunner(failingStore, { hlg }, Executor { it.run() }).schedule("u", Main10TakeKind.HLG, null)
    }

    @Test
    fun aRejectedExecutorLeavesTheTakeAlone() {
        val store = MemoryStore()
        Main10FileCheckRunner(store, { hlg }, Executor { throw RejectedExecutionException() }).schedule("u", Main10TakeKind.HLG, null)
        assertNull(store.saved)
    }
}
