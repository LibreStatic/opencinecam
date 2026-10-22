/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.transfers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.BackoffPolicy
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class WebDavAutoUploadSchedulerTest {
    private lateinit var workManager: WorkManager
    private lateinit var scheduler: WebDavAutoUploadScheduler
    private val first = id(1)
    private val second = id(2)

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        workManager = WorkManager.getInstance(context)
        scheduler = WebDavAutoUploadScheduler(workManager)
    }

    private fun infos(bundleId: String) = workManager.getWorkInfosForUniqueWork("webdav-auto-$bundleId").get()
    private fun live(bundleId: String) = infos(bundleId).filter { !it.state.isFinished }

    @Test fun requestIsUniquePerBundleTaggedBackedOffAndCarriesTheBundleId() {
        scheduler.enqueue(first, allowCellular = false)
        val info = infos(first).single()
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
        assertTrue(info.tags.containsAll(listOf("webdav-auto", "bundle:$first")))
        assertEquals(NetworkType.UNMETERED, info.constraints.requiredNetworkType)
        val spec = WebDavAutoUploadScheduler.request(first, false).workSpec
        assertEquals(BackoffPolicy.EXPONENTIAL, spec.backoffPolicy)
        assertEquals(60_000L, spec.backoffDelayDuration)
        assertEquals(first, spec.input.getString("bundleId"))
        assertEquals(WebDavAutoUploadWorker::class.java.name, spec.workerClassName)
    }

    @Test fun cellularConsentRelaxesTheConstraintToAnyConnection() {
        scheduler.enqueue(first, allowCellular = true)
        assertEquals(NetworkType.CONNECTED, infos(first).single().constraints.requiredNetworkType)
    }

    @Test fun enqueuingTheSameBundleAgainKeepsTheExistingWork() {
        scheduler.enqueue(first, allowCellular = false)
        val original = infos(first).single().id
        scheduler.enqueue(first, allowCellular = true)
        val after = infos(first).single()
        assertEquals(original, after.id)
        assertEquals(NetworkType.UNMETERED, after.constraints.requiredNetworkType)
    }

    @Test fun anInvalidBundleIdIsLoggedNotThrownOrScheduled() {
        scheduler.enqueue("../not-a-uuid", allowCellular = false)
        assertTrue(workManager.getWorkInfosByTag("webdav-auto").get().isEmpty())
    }

    @Test fun cancelAllCancelsEveryAutomaticUpload() {
        scheduler.enqueue(first, false); scheduler.enqueue(second, false)
        scheduler.cancelAll()
        assertEquals(listOf(WorkInfo.State.CANCELLED), infos(first).map { it.state })
        assertEquals(listOf(WorkInfo.State.CANCELLED), infos(second).map { it.state })
    }

    @Test fun cellularChangeReplacesOnlyPendingWorkWithTheNewConstraint() {
        scheduler.enqueue(first, false); scheduler.enqueue(second, false)
        workManager.cancelUniqueWork("webdav-auto-$second").result.get()
        scheduler.reschedulePending(allowCellular = true)
        val replaced = live(first).single()
        assertEquals(NetworkType.CONNECTED, replaced.constraints.requiredNetworkType)
        assertEquals(WorkInfo.State.ENQUEUED, replaced.state)
        assertTrue(live(second).isEmpty()) // A cancelled bundle is not resurrected.
        scheduler.reschedulePending(allowCellular = false)
        assertEquals(NetworkType.UNMETERED, live(first).single().constraints.requiredNetworkType)
    }

    @Test fun revocationRulesOnSettingsChanges() {
        val endpoint = WebDavQueueEndpoint(id(7), "https://dav.example/takes/")
        val other = WebDavQueueEndpoint(id(8), "https://other.example/takes/")
        val on = WebDavQueuePreferences(3, true, false, endpoint.id, listOf(endpoint, other), autoUpload = true)
        fun pending() = scheduler.also { it.enqueue(first, false) }
        // Turning automatic upload on never cancels what is already queued.
        applyAutoUploadSettingsChange(on.updated(endpoint.url, true, false, autoUpload = false), on, pending())
        assertEquals(1, live(first).size)
        // Queue disabled, automatic upload off, or another endpoint revoke them all.
        val revokers = listOf(
            on.updated(endpoint.url, false, false),
            on.updated(endpoint.url, true, false, autoUpload = false),
            on.updated(other.url, true, false),
        )
        for (next in revokers) {
            val s = pending()
            applyAutoUploadSettingsChange(on, next, s)
            assertTrue(live(first).isEmpty())
        }
    }

    @Test fun cellularOnlyChangeReschedulesAndDoesNotCancel() {
        val endpoint = WebDavQueueEndpoint(id(7), "https://dav.example/takes/")
        val on = WebDavQueuePreferences(3, true, false, endpoint.id, listOf(endpoint), autoUpload = true)
        scheduler.enqueue(first, false)
        applyAutoUploadSettingsChange(on, on.updated(endpoint.url, true, true), scheduler)
        assertEquals(NetworkType.CONNECTED, live(first).single().constraints.requiredNetworkType)
    }

    private fun id(value: Int) = "00000000-0000-0000-0000-${value.toString().padStart(12, '0')}"
}
