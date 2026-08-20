/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import org.junit.Assert.assertEquals
import org.junit.Test

class BuildBaselineTest {
    @Test
    fun productIdentityIsStable() {
        assertEquals("com.librestatic.opencinecam", "com.librestatic.opencinecam")
    }
}
