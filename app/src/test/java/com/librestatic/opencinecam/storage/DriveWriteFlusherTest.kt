/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.storage

import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FileDescriptor
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** PLAN-072, ADR-0037: the once-a-second fsync of a take's drive outputs. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DriveWriteFlusherTest {
    private val volume = "E5F6-" + UUID.randomUUID().toString().take(4).uppercase()
    private val files = mutableListOf<File>()
    private val synced = mutableListOf<FileDescriptor>()
    private var failing = setOf<FileDescriptor>()

    // A long interval keeps the background thread out of the way: the tests drive flushOnce themselves.
    private val flusher = DriveWriteFlusher(intervalMs = 3_600_000L, sync = {
        if (it in failing) throw java.io.IOException("EIO")
        synced += it
    })

    @Before fun setUp() { DriveDescriptors.releaseVolume(volume) }

    @After fun tearDown() {
        DriveDescriptors.releaseVolume(volume)
        files.forEach { it.delete() }
    }

    private fun driveDocument(name: String = "take"): Uri =
        DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "$volume:Takes/$name.mp4")

    private fun open(): ParcelFileDescriptor {
        val file = File.createTempFile("flush", ".bin").also { files += it }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE)
    }

    @Test fun syncsARegisteredTargetOnEveryPass() {
        val original = open()
        val target = requireNotNull(flusher.add(driveDocument(), original))
        flusher.flushOnce()
        flusher.flushOnce()
        assertEquals(2, synced.size)
        target.close()
        original.close()
    }

    @Test fun aFailingTargetIsDroppedAndTheOthersKeepFlushing() {
        val a = open()
        val b = open()
        val bad = requireNotNull(flusher.add(driveDocument("a"), a))
        val good = requireNotNull(flusher.add(driveDocument("b"), b))
        failing = setOf(bad.fileDescriptorForTest())
        flusher.flushOnce()
        assertEquals(1, flusher.count)
        synced.clear()
        flusher.flushOnce()
        assertEquals(1, synced.size)
        good.close(); bad.close(); a.close(); b.close()
    }

    @Test fun releasingTheVolumeClosesTheDuplicateAndItIsDroppedQuietly() {
        val original = open()
        val target = requireNotNull(flusher.add(driveDocument(), original))
        assertEquals(1, DriveDescriptors.registered(volume))
        assertEquals(1, DriveDescriptors.releaseVolume(volume))
        assertEquals(0, DriveDescriptors.registered(volume))
        assertTrue(target.closed)
        flusher.flushOnce()
        assertEquals(0, flusher.count)
        assertTrue(synced.isEmpty())
        original.close()
    }

    @Test fun closingATargetClosesAndUnregistersItsDuplicate() {
        val original = open()
        val target = requireNotNull(flusher.add(driveDocument(), original))
        target.close()
        target.close()
        assertTrue(target.closed)
        assertEquals(0, DriveDescriptors.registered(volume))
        assertEquals(0, flusher.count)
        flusher.flushOnce()
        assertTrue(synced.isEmpty())
        original.close()
    }

    @Test fun aMediaStoreOutputAddsNoTarget() {
        val original = open()
        val row = Uri.parse("content://media/external/video/media/42")
        assertNull(flusher.add(row, original))
        assertEquals(0, flusher.count)
        assertFalse(original.fileDescriptor == null)
        original.close()
    }
}
