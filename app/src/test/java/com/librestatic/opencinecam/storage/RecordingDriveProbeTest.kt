/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.storage

import android.system.ErrnoException
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RecordingDriveProbeTest {
    private class FakeTarget(
        val created: Boolean = true,
        val createFailure: Exception? = null,
        val openFailure: Exception? = null,
        val writeFailure: Exception? = null,
        val corrupt: Boolean = false,
        val truncateFailure: Exception? = null,
        val deleteFailure: Exception? = null,
    ) : DriveProbeTarget {
        var deletes = 0
        var closed = false
        var truncatedTo: Long? = null

        override fun create(): Boolean { createFailure?.let { throw it }; return created }

        override fun open(): DriveProbeFile {
            openFailure?.let { throw it }
            return object : DriveProbeFile {
                override fun writeAndReadBack(bytes: ByteArray): ByteArray {
                    writeFailure?.let { throw it }
                    return if (corrupt) bytes.copyOf(bytes.size - 1) else bytes.copyOf()
                }
                override fun truncate(length: Long) { truncateFailure?.let { throw it }; truncatedTo = length }
                override fun close() { closed = true }
            }
        }

        override fun delete() { deletes++; deleteFailure?.let { throw it } }
    }

    @Test
    fun exfatDrivePassesAndGrowsTheFilePastFourGiB() {
        val target = FakeTarget()
        assertEquals(DriveProbeResult.OK, runDriveProbe(target))
        assertEquals((4L shl 30) + (1L shl 20), target.truncatedTo)
        assertTrue(PROBE_LENGTH > 0xFFFF_FFFFL)
        assertEquals(1, target.deletes)
        assertTrue(target.closed)
    }

    @Test
    fun noSpaceIsNotEnoughSpace() {
        val target = FakeTarget(truncateFailure = ErrnoException("ftruncate", OsConstants.ENOSPC))
        assertEquals(DriveProbeResult.NOT_ENOUGH_SPACE, runDriveProbe(target))
        assertEquals(1, target.deletes)
        assertTrue(target.closed)
    }

    @Test
    fun anyOtherTruncateErrnoIsTheFileSizeLimit() {
        for (errno in listOf(OsConstants.EFBIG, OsConstants.EINVAL, OsConstants.EIO)) {
            val target = FakeTarget(truncateFailure = ErrnoException("ftruncate", errno))
            assertEquals(DriveProbeResult.FILE_SIZE_LIMIT, runDriveProbe(target))
            assertEquals(1, target.deletes)
        }
        assertEquals(RecordingFolderRejection.FILE_SIZE_LIMIT, DriveProbeResult.FILE_SIZE_LIMIT.rejection())
        assertEquals(null, DriveProbeResult.OK.rejection())
    }

    @Test
    fun refusedCreationIsNotWritableAndDeletesNothing() {
        val refused = FakeTarget(created = false)
        assertEquals(DriveProbeResult.NOT_WRITABLE, runDriveProbe(refused))
        assertEquals(0, refused.deletes)
        val thrown = FakeTarget(createFailure = SecurityException("read only"))
        assertEquals(DriveProbeResult.NOT_WRITABLE, runDriveProbe(thrown))
        assertEquals(0, thrown.deletes)
    }

    @Test
    fun openWriteAndReadBackFailuresAreNotWritableAndStillDelete() {
        val targets = listOf(
            FakeTarget(openFailure = IOException("no fd")),
            FakeTarget(writeFailure = ErrnoException("pwrite", OsConstants.EROFS)),
            FakeTarget(corrupt = true),
            FakeTarget(truncateFailure = IllegalStateException("odd")),
        )
        for (target in targets) {
            assertEquals(DriveProbeResult.NOT_WRITABLE, runDriveProbe(target))
            assertEquals(1, target.deletes)
        }
        assertFalse(targets[0].closed)
        assertTrue(targets[1].closed)
    }

    @Test
    fun aFailedDeleteDoesNotChangeTheOutcome() {
        val target = FakeTarget(deleteFailure = IOException("busy"))
        assertEquals(DriveProbeResult.OK, runDriveProbe(target))
        assertEquals(1, target.deletes)
    }
}
