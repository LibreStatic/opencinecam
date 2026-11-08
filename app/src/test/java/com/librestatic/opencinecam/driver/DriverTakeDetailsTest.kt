/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.driver

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.librestatic.opencinecam.DetailGroupKind
import com.librestatic.opencinecam.buildTakeDetails
import com.librestatic.opencinecam.detailText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The driver's sample details build through the real code, so a bad fake fails here rather than as an empty inspector. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DriverTakeDetailsTest {
    @Test fun everySampleTakeHasDetails() {
        val text = detailText(ApplicationProvider.getApplicationContext())
        val built = (SampleRawTakes + SampleTakes).filter { it.details != null }
            .associate { it.take.id to buildTakeDetails(it.details!!(it.take), text) }
        assertEquals(setOf(SampleRawTakes[0].take.id, SampleRawTakes[1].take.id, "1", "3", "4", "5", "6", "7"), built.keys)
        built.forEach { (id, details) -> assertTrue("$id has a File section", details.group(DetailGroupKind.FILE) != null) }
        assertTrue(built.getValue(SampleRawTakes[0].take.id).group(DetailGroupKind.RAW) != null)
        assertTrue(built.getValue("1").group(DetailGroupKind.CAMERA)!!.rows.size > 5)
        assertTrue(built.getValue("3").group(DetailGroupKind.CAMERA) != null)
    }
}
