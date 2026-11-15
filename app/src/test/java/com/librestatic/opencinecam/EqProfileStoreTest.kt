/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import com.librestatic.opencinecam.camera.EqCurve
import com.librestatic.opencinecam.camera.EqFilterType
import com.librestatic.opencinecam.camera.EqPoint
import com.librestatic.opencinecam.camera.EqProfile
import com.librestatic.opencinecam.camera.decodeEqProfile
import com.librestatic.opencinecam.camera.isValidEqProfileId
import com.librestatic.opencinecam.camera.toJson
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EqProfileStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private val curve = EqCurve(points = listOf(EqPoint(EqFilterType.BELL, 10_000.0, -9.5, 1.1)), trimDb = 1.5)
    private val dir get() = File(folder.root, "eq-profiles")

    private fun store() = EqProfileStore(dir)

    @Test fun createdProfilesRoundTripAndCreateTheDirectory() {
        val store = store()
        assertTrue(store.all().isEmpty())
        val created = store.create("  Lavalier  ", curve)
        assertEquals("Lavalier", created.name)
        assertEquals(curve, created.curve)
        assertFalse(created.bundled)
        assertEquals(created, store.load(created.id))
        assertEquals(listOf(created), store.all())
        assertEquals(listOf("${created.id}.json"), dir.list()!!.toList())
        // A fresh instance reads the same file.
        assertEquals(created, EqProfileStore(dir).load(created.id))
    }

    @Test fun idsAreFreshValidUserIds() {
        val store = store()
        val ids = (1..20).map { store.create("Same name", curve).id }
        assertEquals(20, ids.toSet().size)
        ids.forEach { id ->
            assertTrue(id, isValidEqProfileId(id))
            assertTrue(id, Regex("user-[0-9a-z]{8,}").matches(id))
        }
        assertEquals(20, store.all().size)
    }

    @Test fun saveStoresNoDeviceAndReplaces() {
        val store = store()
        store.save(EqProfile("user-abcdef01", "Shotgun", curve, device = "blanc"))
        assertNull(store.load("user-abcdef01")!!.device)
        store.save(EqProfile("user-abcdef01", "Shotgun", EqCurve()))
        assertEquals(EqCurve(), store.load("user-abcdef01")!!.curve)
        assertEquals(1, store.all().size)
        assertFalse(File(dir, "user-abcdef01.json.tmp").exists())
    }

    @Test fun aFailedReplaceThrowsAndRemovesNothing() {
        val store = store()
        // A path the move cannot replace: a non-empty directory under the profile's file name.
        val blocker = File(dir, "user-abcdef01.json").apply { mkdirs() }
        File(blocker, "keep").writeText("kept")
        assertThrows(java.io.IOException::class.java) { store.save(EqProfile("user-abcdef01", "Shotgun", curve)) }
        assertEquals("kept", File(blocker, "keep").readText())
        assertFalse(File(dir, "user-abcdef01.json.tmp").exists())
    }

    @Test fun bundledProfilesAreRefusedAndIgnored() {
        val store = store()
        assertThrows(IllegalArgumentException::class.java) {
            store.save(EqProfile("bundled-motorola-blanc", "Razr", curve, bundled = true, device = "blanc"))
        }
        assertThrows(IllegalArgumentException::class.java) { store.save(EqProfile("bundled-mine", "Mine", curve)) }
        assertThrows(IllegalArgumentException::class.java) { store.save(EqProfile("user-12345678", "Mine", curve, bundled = true)) }
        // Files that claim to be bundled, placed by hand, are not user profiles.
        dir.mkdirs()
        File(dir, "bundled-motorola-blanc.json").writeText(EqProfile("bundled-motorola-blanc", "Razr", curve, true, "blanc").toJson())
        File(dir, "user-12345678.json").writeText(EqProfile("user-12345678", "Claims bundled", curve, bundled = true).toJson())
        assertTrue(store.all().isEmpty())
        assertNull(store.load("bundled-motorola-blanc"))
        assertNull(store.load("user-12345678"))
        assertFalse(store.delete("bundled-motorola-blanc"))
        assertTrue(File(dir, "bundled-motorola-blanc.json").exists())
    }

    @Test fun aDamagedFileIsSkippedWhileTheOthersLoad() {
        val store = store()
        val kept = store.create("Kept", curve)
        File(dir, "user-damaged1.json").writeText("{\"schema\":")
        File(dir, "user-other01.json").writeText("{\"schema\":\"something-else\"}")
        // A profile stored under another id's name is not that profile.
        File(dir, "user-moved001.json").writeText(EqProfile("user-elsewhere", "Moved", curve).toJson())
        File(dir, "notes.txt").writeText("not a profile")
        assertEquals(listOf(kept), store.all())
        assertNull(store.load("user-damaged1"))
        assertNull(store.load("user-moved001"))
        assertNull(store.load("../escape"))
    }

    @Test fun allSortsByNameIgnoringCaseThenId() {
        val store = store()
        store.save(EqProfile("user-b", "beta", curve))
        store.save(EqProfile("user-a2", "Alpha", curve))
        store.save(EqProfile("user-a1", "alpha", curve))
        store.save(EqProfile("user-c", "Gamma", curve))
        assertEquals(listOf("user-a1", "user-a2", "user-b", "user-c"), store.all().map { it.id })
    }

    @Test fun renameKeepsTheCurveAndRefusesABlankName() {
        val store = store()
        val created = store.create("Old", curve)
        val renamed = store.rename(created.id, "  New name ")!!
        assertEquals("New name", renamed.name)
        assertEquals(created.copy(name = "New name"), store.load(created.id))
        assertNull(store.rename("user-missing1", "Anything"))
        assertThrows(IllegalArgumentException::class.java) { store.rename(created.id, "   ") }
        assertEquals("New name", store.load(created.id)!!.name)
    }

    @Test fun saveCurveKeepsTheStoredNameAndNeverRecreates() {
        val store = store()
        val created = store.create("Old", EqCurve())
        store.rename(created.id, "New")
        // An edit made against the old name only carries its curve.
        assertEquals(EqProfile(created.id, "New", curve), store.saveCurve(created.id, curve))
        assertEquals(EqProfile(created.id, "New", curve), store.load(created.id))
        store.delete(created.id)
        assertNull(store.saveCurve(created.id, EqCurve()))
        assertTrue(store.all().isEmpty())
    }

    @Test fun deleteRemovesOnlyWhatExists() {
        val store = store()
        val created = store.create("Gone", curve)
        assertTrue(store.delete(created.id))
        assertFalse(store.delete(created.id))
        assertNull(store.load(created.id))
        assertTrue(store.all().isEmpty())
    }

    @Test fun createRefusesABlankName() {
        assertThrows(IllegalArgumentException::class.java) { store().create("  ", curve) }
        assertFalse(dir.exists())
    }

    @Test fun theStoreHoldsAtMostMaxProfiles() {
        val store = store()
        repeat(EqProfileStore.MAX_PROFILES) { store.create("Profile $it", curve) }
        assertThrows(IllegalStateException::class.java) { store.create("One too many", curve) }
        assertThrows(IllegalStateException::class.java) { store.save(EqProfile("user-extra001", "Extra", curve)) }
        // Replacing or renaming one that exists still works at the cap.
        val first = store.all().first()
        store.save(first.copy(curve = EqCurve()))
        assertEquals("Renamed", store.rename(first.id, "Renamed")!!.name)
        assertEquals(EqProfileStore.MAX_PROFILES, store.all().size)
        // Each stored file is the codec's own form.
        assertEquals(first.copy(curve = EqCurve(), name = "Renamed"), decodeEqProfile(File(dir, "${first.id}.json").readText()))
    }
}
