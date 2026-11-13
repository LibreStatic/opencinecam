/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingVolumeMonitorTest {
    @Test
    fun stateStringsMapToMountedLeavingOrIgnore() {
        assertEquals(VolumeStateKind.MOUNTED, volumeStateKind("mounted"))
        for (state in listOf("ejecting", "unmounted", "bad_removal", "removed", "mounted_ro")) {
            assertEquals(state, VolumeStateKind.LEAVING, volumeStateKind(state))
        }
        for (state in listOf("checking", "nofs", "unmountable", "shared", "unknown", null)) {
            assertEquals(state, VolumeStateKind.IGNORE, volumeStateKind(state))
        }
    }

    @Test
    fun unplugEmitsOneLeavingAndReplugOneMounted() {
        val tracker = VolumeEventTracker(setOf("fff1-7571"))
        assertEquals(setOf("FFF1-7571"), tracker.mountedVolumes)
        assertNull(tracker.update("FFF1-7571", "mounted"))
        assertEquals(RecordingVolumeEvent.Leaving("FFF1-7571"), tracker.update("FFF1-7571", "ejecting"))
        assertNull(tracker.update("FFF1-7571", "unmounted"))
        assertNull(tracker.update("fff1-7571", "bad_removal"))
        assertEquals(emptySet<String>(), tracker.mountedVolumes)
        assertNull(tracker.update("FFF1-7571", "checking"))
        assertEquals(RecordingVolumeEvent.Mounted("FFF1-7571"), tracker.update("fff1-7571", "mounted"))
        assertNull(tracker.update("FFF1-7571", "mounted"))
        assertEquals(setOf("FFF1-7571"), tracker.mountedVolumes)
    }

    @Test
    fun ejectBroadcastLeavesBeforeTheStateChanges() {
        val tracker = VolumeEventTracker(setOf("FFF1-7571"))
        assertEquals(RecordingVolumeEvent.Leaving("FFF1-7571"), tracker.apply("FFF1-7571", VolumeStateKind.LEAVING))
        assertNull(tracker.update("FFF1-7571", "unmounted"))
    }

    @Test
    fun unknownVolumeOnlyReportsItsMounting() {
        val tracker = VolumeEventTracker()
        assertNull(tracker.update("1234-ABCD", "unmounted"))
        assertNull(tracker.update("1234-ABCD", "ejecting"))
        assertEquals(RecordingVolumeEvent.Mounted("1234-ABCD"), tracker.update("1234-abcd", "mounted"))
    }

    @Test
    fun aRescanAppliesToEveryVolumeTheListenerDidNotReportDuringTheRead() {
        // The read started at counter 5. AAAA was last reported at 3 (before the read), BBBB at 6 (during it).
        val volumes = listOf("AAAA-0001" to "mounted", "BBBB-0002" to "unmounted", "CCCC-0003" to "mounted")
        val changes = rescanChanges(volumes, mounted = setOf("BBBB-0002", "DDDD-0004", "EEEE-0005"),
            lastReported = mapOf("AAAA-0001" to 3L, "BBBB-0002" to 6L, "EEEE-0005" to 7L), started = 5L)
        // BBBB keeps the listener's state and so does EEEE, absent from the read but reported during it; DDDD, absent and
        // never reported, has been removed.
        assertEquals(listOf("AAAA-0001" to VolumeStateKind.MOUNTED, "CCCC-0003" to VolumeStateKind.MOUNTED,
            "DDDD-0004" to VolumeStateKind.LEAVING), changes)
    }

    @Test
    fun aRescanWithNoReportDuringTheReadAppliesAllOfIt() {
        val changes = rescanChanges(listOf("AAAA-0001" to "ejecting"), mounted = setOf("AAAA-0001", "BBBB-0002"),
            lastReported = mapOf("AAAA-0001" to 5L), started = 5L)
        assertEquals(listOf("AAAA-0001" to VolumeStateKind.LEAVING, "BBBB-0002" to VolumeStateKind.LEAVING), changes)
    }

    @Test
    fun volumesAreTrackedIndependently() {
        val tracker = VolumeEventTracker(setOf("AAAA-0001", "BBBB-0002"))
        assertEquals(RecordingVolumeEvent.Leaving("AAAA-0001"), tracker.update("AAAA-0001", "bad_removal"))
        assertEquals(setOf("BBBB-0002"), tracker.mountedVolumes)
    }
}
