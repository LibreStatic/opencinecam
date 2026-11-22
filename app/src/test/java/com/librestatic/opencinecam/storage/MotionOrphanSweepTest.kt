/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import org.junit.Assert.assertEquals
import org.junit.Test

/** Which leftover motion files [motionOrphans] lets go: only ours, of no journalled take, and old enough. */
class MotionOrphanSweepTest {
    private val now = 10_000_000_000L
    private val age = TakeIndexFiles.ORPHAN_AGE_MS
    private val old = now - age
    private val fresh = now - age + 1
    private val live = "8f1d1c43-8a3e-4c3c-9a52-6a6f3b1c2d01"
    private val orphan = "0c7c9e9d-3a0a-4a8e-b8a8-0e9d5e2b3f02"

    private fun entries(vararg pairs: Pair<String, Long>) = pairs.map { (name, modified) -> MotionFileEntry(name, modified) }

    @Test fun spoolFolderSweepsOnlyOldFilesOfUnknownTakes() {
        val files = entries(
            "$orphan.ocmo" to old, "$orphan.json" to old, "$orphan.json.tmp" to old,
            // A take still journalled (recording, finalising or waiting for recovery) keeps everything, however old.
            "$live.ocmo" to old, "$live.json" to old,
            // Too young: a take may be starting or finalising right now.
            "${orphan}x.ocmo" to fresh,
            // Not ours, or no stem at all.
            "$orphan.txt" to old, ".ocmo" to old,
            // An unreadable time is never old.
            "${orphan}y.ocmo" to 0L,
        )
        assertEquals(listOf("$orphan.ocmo", "$orphan.json", "$orphan.json.tmp"),
            motionOrphans(files, MotionFileFolder.SPOOL, setOf(live), age, now))
    }

    @Test fun gyroflowFolderSweepsRenderedStopAndOldRecorderLogs() {
        val files = entries(
            "$orphan.recovered.gcsv" to old, "$orphan.recovered.gcsv.part" to old, "$orphan.gcsv" to old,
            // The removed MotionLogRecorder named its temporary logs with a random UUID.
            "e1b0c0c1-0d4e-4f55-8d6a-1f2e3d4c5b03.gcsv" to old,
            "$live.gcsv" to old, "$live.recovered.gcsv" to old,
            "$orphan.ocmo" to old,
        )
        assertEquals(listOf("$orphan.recovered.gcsv", "$orphan.recovered.gcsv.part", "$orphan.gcsv",
            "e1b0c0c1-0d4e-4f55-8d6a-1f2e3d4c5b03.gcsv"),
            motionOrphans(files, MotionFileFolder.GYROFLOW, setOf(live), age, now))
    }
}
