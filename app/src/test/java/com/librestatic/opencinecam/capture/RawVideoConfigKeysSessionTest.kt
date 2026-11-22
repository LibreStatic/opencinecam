/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PLAN-076: moving a RAW window re-arms the ring without another camera session; anything else reopens it. */
class RawVideoConfigKeysSessionTest {
    @Test
    fun `the origin is dropped and every other part kept`() {
        assertEquals("1920x816@24", RawVideoConfigKeys.withoutOrigin("1920x816+1080+1122@24"))
        assertEquals("1920x816@24+bp", RawVideoConfigKeys.withoutOrigin("1920x816+0+0@24+bp"))
        assertEquals("1920x816@24+lj92", RawVideoConfigKeys.withoutOrigin("1920x816+4+2@24+lj92"))
        for (key in listOf("", "full@30", "1920x1080@60", "3840x2160@30+bp")) assertEquals(key, RawVideoConfigKeys.withoutOrigin(key))
    }

    @Test
    fun `a moved window keeps the session`() {
        assertFalse(RawVideoConfigKeys.sessionChanged("1920x816+1080+1122@24", "1920x816+0+0@24"))
        assertFalse(RawVideoConfigKeys.sessionChanged("1920x816@24", "1920x816+1080+1122@24"))
        assertFalse(RawVideoConfigKeys.sessionChanged("1920x816+8+2@24+bp", "1920x816+1080+1122@24+bp"))
        assertFalse(RawVideoConfigKeys.sessionChanged("full@30", "full@30"))
        assertFalse(RawVideoConfigKeys.sessionChanged(null, null))
    }

    @Test
    fun `a size, rate or encoding change, or a selection made or cleared, needs another session`() {
        assertTrue(RawVideoConfigKeys.sessionChanged("1920x816+1080+1122@24", "1920x820+1080+1122@24"))
        assertTrue(RawVideoConfigKeys.sessionChanged("1920x816+1080+1122@24", "1920x816+1080+1122@30"))
        assertTrue(RawVideoConfigKeys.sessionChanged("1920x816+1080+1122@24", "1920x816+1080+1122@24+bp"))
        assertTrue(RawVideoConfigKeys.sessionChanged("full@30", "1920x816+1080+1122@30"))
        assertTrue(RawVideoConfigKeys.sessionChanged(null, "1920x816+1080+1122@24"))
        assertTrue(RawVideoConfigKeys.sessionChanged("1920x816+1080+1122@24", null))
    }
}
