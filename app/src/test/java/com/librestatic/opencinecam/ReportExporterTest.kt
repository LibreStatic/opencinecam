/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportExporterTest {
    @Test
    fun fullExportRequiresExplicitConsentButRedactedDoesNot() {
        assertTrue(canExport(ReportExportMode.REDACTED, fullConsent = false))
        assertFalse(canExport(ReportExportMode.FULL, fullConsent = false))
        assertTrue(canExport(ReportExportMode.FULL, fullConsent = true))
    }
}
