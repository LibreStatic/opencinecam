/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCatalogQueryTest {
    @Test fun queriesOver128CharactersOrWithControlsAreInvalid() {
        assertTrue(validGalleryQuery("a".repeat(128)))
        assertFalse(validGalleryQuery("a".repeat(129)))
        assertFalse(validGalleryQuery("take\n1"))
    }

    @Test fun savedInputIsBoundedButAHugePasteStaysInvalid() {
        val pasted = galleryQueryInput("x".repeat(1_000_000))
        assertEquals(512, pasted.length)
        assertFalse(validGalleryQuery(pasted))
        assertEquals("a".repeat(128), galleryQueryInput("a".repeat(128)))
        assertEquals("a".repeat(129), galleryQueryInput("a".repeat(129)))
    }
}
