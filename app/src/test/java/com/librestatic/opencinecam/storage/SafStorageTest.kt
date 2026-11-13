/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], shadows = [ShadowDocumentsProviderQuery::class])
class SafStorageTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun twoFirstCallersShareOneVolumeProbe() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // A volume of its own: the probe cache outlives the test.
        val drive = FakeExternalStorageProvider.install(temp.newFolder("drive"), volume = "5AFE-0001")
        drive.reportsAvailableBytes = false
        // The folder exists, so the only document created is the probe.
        check(drive.file(SafRecordingRows.BASE_FOLDER).mkdir())
        val tree = drive.tree()
        FakeExternalStorageProvider.grant(context, tree)
        val threads = Executors.newFixedThreadPool(2)
        try {
            val secondStarted = CountDownLatch(1)
            var secondThread: Thread? = null
            var second: java.util.concurrent.Future<Long?>? = null
            // The first caller is about to create the probe when the second one asks: it must not create one too. The
            // first holds on until the second is parked on the probe-creation lock, so the two always overlap there.
            drive.beforeCreate = {
                if (second == null) {
                    second = threads.submit<Long?> {
                        secondThread = Thread.currentThread()
                        secondStarted.countDown()
                        driveFreeBytes(context.contentResolver, tree)
                    }
                    check(secondStarted.await(10, TimeUnit.SECONDS))
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    while (requireNotNull(secondThread).state != Thread.State.WAITING) {
                        check(System.nanoTime() < deadline) { "The second caller never waited for the probe" }
                        Thread.sleep(1)
                    }
                }
            }
            val first = threads.submit<Long?> { driveFreeBytes(context.contentResolver, tree) }
            val firstBytes = first.get(30, TimeUnit.SECONDS)
            val secondBytes = requireNotNull(second).get(30, TimeUnit.SECONDS)
            // One probe created, once: the second caller found the first one's (a second creation would have been named
            // `.occ-volume (1)` and failed). The host's fstatvfs may not answer, so both read the same, possibly nothing.
            assertEquals(1, drive.creates.get())
            assertEquals(firstBytes, secondBytes)
            assertEquals(listOf(DRIVE_VOLUME_PROBE), drive.file(SafRecordingRows.BASE_FOLDER).list()!!.toList())
        } finally {
            drive.beforeCreate = null
            threads.shutdownNow()
        }
    }
}
