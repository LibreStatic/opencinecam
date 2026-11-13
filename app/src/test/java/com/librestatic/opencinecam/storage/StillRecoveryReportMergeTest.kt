/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** PLAN-072: every still recovery pass updates the notice, the startup one and each one a drive mount runs. */
class StillRecoveryReportMergeTest {
    @Test fun theStartupPassReplacesWhateverIsShown() {
        val shown = StillRecoveryReport(discardedGroups = 4, unresolvedGroups = 2)
        val pass = StillRecoveryReport(discardedGroups = 1, deferredGroups = 3)
        assertEquals(pass, mergedStillRecoveryReport(shown, pass, startup = true))
        assertEquals(StillRecoveryReport(), mergedStillRecoveryReport(shown, StillRecoveryReport(), startup = true))
    }

    @Test fun aLaterPassReplacesThePendingCountsAndAddsItsDiscards() {
        val shown = StillRecoveryReport(discardedGroups = 2, unresolvedGroups = 1, deferredGroups = 3)
        // The drive came back: the deferred groups are resolved, one of them discarded, and the unresolved one too.
        val pass = StillRecoveryReport(discardedGroups = 1)
        assertEquals(StillRecoveryReport(discardedGroups = 3), mergedStillRecoveryReport(shown, pass, startup = false))
    }

    @Test fun aLaterPassThatResolvesAnUnresolvedGroupClearsIt() {
        val shown = StillRecoveryReport(unresolvedGroups = 1)
        assertEquals(StillRecoveryReport(), mergedStillRecoveryReport(shown, StillRecoveryReport(), startup = false))
    }

    @Test fun anEmptyLaterPassWithNothingShownShowsNothing() {
        assertNull(mergedStillRecoveryReport(null, StillRecoveryReport(), startup = false))
    }

    @Test fun aLaterPassWithNothingShownShowsWhatItFound() {
        val pass = StillRecoveryReport(discardedGroups = 2, unresolvedGroups = 1)
        assertEquals(pass, mergedStillRecoveryReport(null, pass, startup = false))
    }
}
