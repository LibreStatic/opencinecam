/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0003 (amended 2026-10-19): the Activity may only pre-open, adopt and drop the camera device. */
class CaptureStartupSurfaceTest {
    @Test
    fun exposesOnlyPreopenAdoptAndDrop() {
        val allowed = setOf("begin", "adoptEngine", "descriptors", "released", "stopped", "previewStarted")
        val names = CaptureStartup::class.java.declaredMethods
            .filter { !it.isSynthetic && java.lang.reflect.Modifier.isPublic(it.modifiers) }
            .map { it.name }
            .toSet()
        assertTrue("Unexpected CaptureStartup entry points: ${names - allowed}", allowed.containsAll(names))
        assertTrue(names.none { it.contains("start", ignoreCase = true) && it != "previewStarted" })
        assertTrue(names.none { it.contains("record", ignoreCase = true) })
        assertEquals(true, names.containsAll(setOf("begin", "adoptEngine", "stopped")))
    }
}
