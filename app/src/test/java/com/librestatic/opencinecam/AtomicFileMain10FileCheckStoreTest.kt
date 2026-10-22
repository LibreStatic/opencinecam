/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import com.librestatic.opencinecam.camera.Main10FileCheckRunner
import com.librestatic.opencinecam.camera.Main10FileSignal
import com.librestatic.opencinecam.camera.Main10TakeKind
import com.librestatic.opencinecam.camera.QualificationStatus
import java.io.File
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AtomicFileMain10FileCheckStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private fun file() = File(folder.root, "main10-file-check.json")

    @Test fun theLatestCheckSurvivesARestartAndReplacesThePreviousOne() {
        val signal = Main10FileSignal("video/hevc", "Main10", "BT.2020", "HLG", 10)
        val runner = Main10FileCheckRunner(AtomicFileMain10FileCheckStore(file()), { signal }, Executor { it.run() }, { 9L })
        runner.schedule("content://a", Main10TakeKind.HLG, "a.mp4")
        runner.schedule("content://b", Main10TakeKind.LOG, "b.mp4")
        val read = AtomicFileMain10FileCheckStore(file()).load()!!
        assertEquals("b.mp4", read.takeName)
        assertEquals(Main10TakeKind.LOG, read.kind)
        assertEquals(QualificationStatus.PASS, read.fileSignal.status)
        assertEquals(QualificationStatus.UNKNOWN, read.effectivePrecision.status)
    }

    @Test fun missingOrCorruptFilesReadAsNoCheck() {
        assertNull(AtomicFileMain10FileCheckStore(file()).load())
        file().writeText("{ broken")
        assertNull(AtomicFileMain10FileCheckStore(file()).load())
    }
}
