/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StillRouteTest {
    @Test fun routedIdInThePhysicalResultsConfirmsTheRoute() {
        assertEquals("3", stillRouteReportedId("3", setOf("2", "3")))
    }

    @Test fun resultsWithoutTheRoutedIdReportAnotherCamera() {
        assertEquals("2", stillRouteReportedId("3", setOf("4", "2")))
    }

    @Test fun noPhysicalResultsLeavesTheRouteUnconfirmed() {
        assertNull(stillRouteReportedId("3", emptySet()))
    }

    @Test fun theLogicalRouteHasNothingToConfirm() {
        assertNull(stillRouteReportedId(null, setOf("2")))
    }
}
