/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.storage

import com.librestatic.opencinecam.rawvideo.RawVideoCrop
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RawCropStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private fun file() = File(folder.root, "raw-crops.json")
    private fun store() = RawCropStore(file())

    private val anamorphic = RawVideoCrop(1000, 1100, 1920, 816)
    private val slowMotion = RawVideoCrop(1880, 1410, 320, 240)

    @Test fun savedWindowsRoundTripThroughAFreshStore() {
        val first = store().save("  Anamorphic  ", 4080, 3060, anamorphic)
        val second = store().save("Slow motion", 4080, 3060, slowMotion)
        assertEquals("Anamorphic", first.name)
        assertEquals(anamorphic, first.crop)
        assertNotEquals(first.id, second.id)
        assertEquals(listOf(first, second), store().all())
        assertEquals(listOf(first, second), store().list(4080, 3060))
        assertFalse(File(folder.root, "raw-crops.json.tmp").exists())
    }

    @Test fun namesAreTrimmedAndBounded() {
        val store = store()
        assertThrows(IllegalArgumentException::class.java) { store.save("   ", 4080, 3060, anamorphic) }
        assertThrows(IllegalArgumentException::class.java) { store.save("x".repeat(41), 4080, 3060, anamorphic) }
        assertEquals("x".repeat(40), store.save(" " + "x".repeat(40) + " ", 4080, 3060, anamorphic).name)
        assertNull(RawCropStore.validName(""))
        assertEquals("a", RawCropStore.validName(" a "))
    }

    @Test fun aWindowThatCannotBeWrittenIsRefused() {
        val store = store()
        // An odd height, outside the sensor (either way), under the minimum.
        for (crop in listOf(RawVideoCrop(0, 0, 1920, 815), RawVideoCrop(2164, 0, 1920, 816), RawVideoCrop(0, 2246, 1920, 816),
            RawVideoCrop(0, 0, 124, 240))) {
            assertThrows(crop.toString(), IllegalArgumentException::class.java) { store.save("bad", 4080, 3060, crop) }
        }
        assertTrue(store.all().isEmpty())
        assertFalse(file().exists())
    }

    @Test fun renameAndDeleteChangeOnlyTheirEntry() {
        val store = store()
        val first = store.save("One", 4080, 3060, anamorphic)
        val second = store.save("Two", 4080, 3060, slowMotion)
        assertEquals(first.copy(name = "Renamed"), store.rename(first.id, " Renamed "))
        assertNull(store.rename("missing", "Name"))
        assertThrows(IllegalArgumentException::class.java) { store.rename(first.id, "") }
        assertEquals(listOf(first.copy(name = "Renamed"), second), store().all())
        assertTrue(store.delete(first.id))
        assertFalse(store.delete(first.id))
        assertEquals(listOf(second), store().all())
    }

    @Test fun aCorruptFileReadsAsEmptyAndStaysUntilTheNextSave() {
        file().writeText("{ not json", Charsets.UTF_8)
        val store = store()
        assertTrue(store.all().isEmpty())
        assertNull(store.rename("any", "Name"))
        assertFalse(store.delete("any"))
        assertEquals("{ not json", file().readText())
        val saved = store.save("Fresh", 4080, 3060, anamorphic)
        assertEquals(listOf(saved), store().all())
        // Another shape of damage reads as empty too.
        file().writeText("[1, 2, 3]")
        assertTrue(store().all().isEmpty())
    }

    @Test fun entriesThatAreNotAWindowOnTheirOwnSensorAreDroppedOnRead() {
        file().writeText(
            """
            {"version":1,"crops":[
              {"id":"a","name":"Good","sensorWidth":4080,"sensorHeight":3060,"left":1000,"top":1100,"width":1920,"height":816},
              {"id":"b","name":"Odd top","sensorWidth":4080,"sensorHeight":3060,"left":1000,"top":1101,"width":1920,"height":816},
              {"id":"c","name":"Outside","sensorWidth":4080,"sensorHeight":3060,"left":3000,"top":0,"width":1920,"height":816},
              {"id":"d","name":"","sensorWidth":4080,"sensorHeight":3060,"left":0,"top":0,"width":1920,"height":816},
              {"id":"e","name":"Text size","sensorWidth":"4080","sensorHeight":3060,"left":0,"top":0,"width":1920,"height":816},
              {"id":"f","name":"Tiny","sensorWidth":4080,"sensorHeight":3060,"left":0,"top":0,"width":64,"height":48},
              {"id":"a","name":"Duplicate id","sensorWidth":4080,"sensorHeight":3060,"left":0,"top":0,"width":1920,"height":816},
              "not an entry"
            ]}
            """.trimIndent(),
        )
        assertEquals(listOf(RawSavedCrop("a", "Good", 4080, 3060, 1000, 1100, 1920, 816)), store().all())
    }

    @Test fun listKeepsTheWindowsOfOneSensorSize() {
        val store = store()
        val big = store.save("Big", 4080, 3060, anamorphic)
        val small = store.save("Small", 4000, 3000, RawVideoCrop(0, 0, 1920, 1080))
        assertEquals(listOf(big), store.list(4080, 3060))
        assertEquals(listOf(small), store.list(4000, 3000))
        assertTrue(store.list(1920, 1080).isEmpty())
    }
}
