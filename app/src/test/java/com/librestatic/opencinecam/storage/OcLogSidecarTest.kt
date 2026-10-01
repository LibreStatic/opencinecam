/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import org.junit.Assert.*
import org.junit.Test

class OcLogSidecarTest {
    private val uri = "content://media/external_primary/video/media/117362"

    private fun sidecar(schema: String = "opencinecam-oclog-sidecar-v2", videoUri: String = uri, curve: String = "OCLog2",
        gamut: String = "BT.2020", range: String = "full") = """
        {"schema":"$schema","videoUri":"$videoUri",
         "transform":{"curve":"$curve","version":"2","gamut":"$gamut","ycbcrConversion":"BT2020/limited"},
         "encoding":{"codecName":"c2.qti.hevc.encoder","profile":"HEVCProfileMain10","range":"$range"}}
    """.trimIndent()

    @Test fun parsesAnOcLog2SidecarForThisVideo() {
        val clip = parseOcLogSidecar(sidecar(), uri)!!
        assertEquals("OCLog2", clip.curve)
        assertEquals("BT.2020", clip.gamut)
        assertTrue(clip.fullRange)
        assertEquals(PreciseLogSignal(fullRange = true), clip.signal)
        assertEquals("HEVCProfileMain10", clip.profile)
    }

    @Test fun encodedRangeWinsOverTheCameraInputConversion() {
        assertFalse(parseOcLogSidecar(sidecar(range = "limited"), uri)!!.fullRange)
        assertNull(parseOcLogSidecar(sidecar(range = "studio"), uri))
    }

    @Test fun rejectsSidecarsThatDoNotDescribeThisOcLog2Video() {
        assertNull(parseOcLogSidecar(sidecar(videoUri = "content://media/external_primary/video/media/1"), uri))
        assertNull(parseOcLogSidecar(sidecar(schema = "opencinecam-oclog-sidecar-v1"), uri))
        assertNull(parseOcLogSidecar(sidecar(curve = "OCLog"), uri))
        assertNull(parseOcLogSidecar(sidecar(gamut = "BT.709"), uri))
        assertNull(parseOcLogSidecar("not json", uri))
        assertNull(parseOcLogSidecar("[]", uri))
    }
}
