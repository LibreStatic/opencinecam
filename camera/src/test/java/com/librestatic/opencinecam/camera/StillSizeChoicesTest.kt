/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class StillSizeChoicesTest {

    @Test fun keepsTheNativeAspectLargestFirstFromTwoMegapixels() {
        val advertised = listOf(
            1920 to 1080, 4000 to 3000, 4000 to 2250, 640 to 480, 3264 to 2448, 1600 to 1200, 2592 to 1944, 3000 to 3000, 1440 to 1080,
        )
        assertEquals(listOf(4000 to 3000, 3264 to 2448, 2592 to 1944, 1600 to 1200), stillSizeChoices(advertised))
    }

    @Test fun dropsSizesThatReadAsTheSameMegapixels() {
        assertEquals(listOf(4000 to 3000), stillSizeChoices(listOf(4000 to 3000, 3968 to 2976)))
    }

    @Test fun aSingleOrNoOutputStaysThatWay() {
        assertEquals(listOf(4000 to 3000), stillSizeChoices(listOf(4000 to 3000)))
        assertEquals(emptyList<Pair<Int, Int>>(), stillSizeChoices(emptyList()))
    }
}
