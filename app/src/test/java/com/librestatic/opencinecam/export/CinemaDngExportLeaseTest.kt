// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 OpenCineCam contributors

package com.librestatic.opencinecam.export

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.librestatic.opencinecam.storage.CINEMADNG_EXPORT_ACTIVE_TREES_KEY
import com.librestatic.opencinecam.storage.CINEMADNG_EXPORT_PREFS
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CinemaDngExportLeaseTest {
    private val authority = "com.android.externalstorage.documents"

    @Test fun aDriveTakeIsReadThroughItsPickedFolder() {
        val take = "content://$authority/tree/1A2B-3C4D%3ATakes/document/1A2B-3C4D%3ATakes%2FOpenCineCam%2FA001%2FA001.ocraw"
        assertEquals("content://$authority/tree/1A2B-3C4D%3ATakes", CinemaDngExportRuntime.sourceTree(take))
    }

    @Test fun aPhoneTakeNeedsNoFolder() {
        assertNull(CinemaDngExportRuntime.sourceTree("content://media/external/file/42"))
        assertNull(CinemaDngExportRuntime.sourceTree("content://$authority/document/1A2B-3C4D%3ATakes%2FA001.ocraw"))
    }

    @Test fun anExportKeptFromAnEarlierProcessRefusesANewOne() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        // The earlier process's export, still waiting: the state of this process knows nothing of it.
        WorkManager.getInstance(context).enqueueUniqueWork(CinemaDngExportRuntime.UNIQUE_WORK, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<CinemaDngExportWorker>().setInitialDelay(1, TimeUnit.HOURS).build()).result.get()
        val before = CinemaDngExportRuntime.states.value
        assertFalse(before is CinemaDngExportState.Running)

        val request = CinemaDngExportRequest("content://media/external/file/42", "B001", "content://$authority/tree/1A2B-3C4D%3AExports", false)
        assertFalse(CinemaDngExportRuntime.start(context, request))

        assertEquals(before, CinemaDngExportRuntime.states.value)
        assertFalse(context.getSharedPreferences(CINEMADNG_EXPORT_PREFS, Context.MODE_PRIVATE).contains(CINEMADNG_EXPORT_ACTIVE_TREES_KEY))
    }

    @Test fun cancelMarksTheExportAtOnceAndEndsItsLeaseInTheBackground() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        val preferences = context.getSharedPreferences(CINEMADNG_EXPORT_PREFS, Context.MODE_PRIVATE)
        val runtime = CinemaDngExportRuntime
        val deferred = mutableListOf<Runnable>()
        val background = runtime.background
        runtime.background = { deferred += it }
        try {
            runtime.appContext = context
            // An export admitted and queued, its worker not started: an empty lease still marks it queued.
            assertTrue(runtime.store.begin("content://media/external/file/42", "B001"))
            preferences.edit().putStringSet(CINEMADNG_EXPORT_ACTIVE_TREES_KEY, emptySet()).commit()

            runtime.cancel()
            val marked = runtime.states.value
            assertTrue(marked is CinemaDngExportState.Running && marked.cancelling)
            // The lease end (folder-grant lock, a commit, journal reads) did not run on the calling thread.
            assertTrue(preferences.contains(CINEMADNG_EXPORT_ACTIVE_TREES_KEY))
            assertEquals(1, deferred.size)

            deferred.removeAt(0).run()
            val ended = runtime.states.value
            assertTrue(ended is CinemaDngExportState.Finished &&
                (ended.outcome as? CinemaDngExportOutcome.Stopped)?.reason == CinemaDngStopReason.CANCELLED)
            assertFalse(preferences.contains(CINEMADNG_EXPORT_ACTIVE_TREES_KEY))

            // A cancellation whose background step runs after its export ended and a new one began leaves the new one alone.
            runtime.acknowledge()
            assertTrue(runtime.store.begin("content://media/external/file/43", "B002"))
            runtime.cancel()
            runtime.store.finish("content://media/external/file/43", "B002", CinemaDngExportOutcome.Stopped(CinemaDngStopReason.FAILED, cleaned = true))
            runtime.acknowledge()
            assertTrue(runtime.store.begin("content://media/external/file/44", "B003"))
            deferred.removeAt(0).run()
            val untouched = runtime.states.value
            assertTrue(untouched is CinemaDngExportState.Running && !untouched.cancelling && untouched.takeUri.endsWith("/44"))
        } finally {
            runtime.background = background
            runtime.store.finish("", "", CinemaDngExportOutcome.Stopped(CinemaDngStopReason.CANCELLED, cleaned = true))
            runtime.acknowledge()
            preferences.edit().clear().commit()
        }
    }
}
