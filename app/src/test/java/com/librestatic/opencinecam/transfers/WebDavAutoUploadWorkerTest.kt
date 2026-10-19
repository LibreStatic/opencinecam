/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.transfers

import android.content.Context
import androidx.concurrent.futures.ResolvableFuture
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker.Result
import androidx.work.workDataOf
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class WebDavAutoUploadWorkerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val bundle = id(1)
    private val endpoint = WebDavQueueEndpoint(id(7), "https://dav.example/takes/")
    private val automatic = WebDavQueuePreferences(3, true, false, endpoint.id, listOf(endpoint), autoUpload = true)
    private val host = FakeHost(automatic)

    @Before fun install() { WebDavAutoUploadWorker.hostFactory = { host } }
    @After fun restore() { WebDavAutoUploadWorker.hostFactory = { error("Reset by the next test") } }

    private fun worker(attempts: Int = 0, foregroundFails: Boolean = false) = TestListenableWorkerBuilder<WebDavAutoUploadWorker>(context)
        .setInputData(workDataOf("bundleId" to bundle))
        .setRunAttemptCount(attempts)
        .also { builder ->
            if (foregroundFails) builder.setForegroundUpdater { _, _, _ ->
                ResolvableFuture.create<Void?>().also { it.setException(IllegalStateException("Foreground start is not allowed")) }
            }
        }.build()

    private fun run(attempts: Int = 0, foregroundFails: Boolean = false): Result = runBlocking { worker(attempts, foregroundFails).doWork() }

    @Test fun completeIsSuccessAndSendsOnlyTheScheduledBundle() {
        host.result = AutoSendResult(WebDavTransferMessage.COMPLETE, listOf(AutoUploadArtifacts.verified()))
        assertEquals(Result.success(), run())
        assertEquals(listOf(bundle), host.sent)
    }

    @Test fun theRecordingFenceRetriesWithoutTouchingTheBundle() {
        host.result = AutoSendResult(WebDavTransferMessage.WAITING_RECORDING, busy = true)
        assertEquals(Result.retry(), run())
        host.result = AutoSendResult(WebDavTransferMessage.WAITING_RECORDING, listOf(AutoUploadArtifacts.queued()))
        assertEquals(Result.retry(), run())
    }

    @Test fun anInFlightAttemptFailsInsteadOfRetrying() {
        host.result = AutoSendResult(WebDavTransferMessage.UNCERTAIN, listOf(AutoUploadArtifacts.uploading()))
        assertEquals(Result.failure(), run())
    }

    @Test fun conflictsAndAuthenticationStopForManualAction() {
        host.result = AutoSendResult(WebDavTransferMessage.CONFLICT, listOf(AutoUploadArtifacts.conflict()))
        assertEquals(Result.failure(), run())
        host.result = AutoSendResult(WebDavTransferMessage.AUTHENTICATION, listOf(AutoUploadArtifacts.queued()))
        assertEquals(Result.failure(), run())
    }

    @Test fun retriesAreCappedByTheRunAttemptCount() {
        host.result = AutoSendResult(WebDavTransferMessage.NETWORK_UNAVAILABLE, listOf(AutoUploadArtifacts.queued()))
        assertEquals(Result.retry(), run(attempts = 3))
        assertEquals(Result.failure(), run(attempts = AUTO_UPLOAD_MAX_RUN_ATTEMPTS))
    }

    @Test fun revokedSettingsFailBeforeAnyNetworkWork() {
        val endpointB = WebDavQueueEndpoint(id(8), "https://other.example/takes/")
        val revoked = listOf(
            WebDavQueueSettingsState(automatic, storageFailed = true),
            WebDavQueueSettingsState(automatic.updated(endpoint.url, false, false)),
            WebDavQueueSettingsState(automatic.updated(endpoint.url, true, false, autoUpload = false)),
            WebDavQueueSettingsState(automatic.updated(endpointB.url, true, false)),
        )
        for (state in revoked) {
            host.settingsState = state
            assertEquals(Result.failure(), run())
        }
        assertTrue(host.sent.isEmpty())
    }

    @Test fun takesWithoutAnAutomaticEnrollmentAreNeverUploaded() {
        host.enrollment = host.enrollment?.copy(autoUpload = false)
        assertEquals(Result.failure(), run())
        host.enrollment = null
        assertEquals(Result.failure(), run())
        host.enrollmentFails = true
        assertEquals(Result.failure(), run())
        assertTrue(host.sent.isEmpty())
    }

    @Test fun aForegroundStartFailureDoesNotStopTheUpload() {
        host.result = AutoSendResult(WebDavTransferMessage.COMPLETE, listOf(AutoUploadArtifacts.verified()))
        assertEquals(Result.success(), run(foregroundFails = true))
        assertEquals(listOf(bundle), host.sent)
    }

    @Test fun stoppingTheWorkerCancelsOnlyItsOwnBundle() = runBlocking {
        host.hang = CompletableDeferred()
        val job = async(start = CoroutineStart.UNDISPATCHED) { worker().doWork() }
        host.sendStarted.await()
        job.cancelAndJoin()
        assertEquals(listOf(bundle), host.cancelled)
    }

    @Test fun anInvalidInputFailsImmediately() {
        val bad = TestListenableWorkerBuilder<WebDavAutoUploadWorker>(context).setInputData(workDataOf("bundleId" to "x")).build()
        assertEquals(Result.failure(), runBlocking { bad.doWork() })
        val missing = TestListenableWorkerBuilder<WebDavAutoUploadWorker>(context).build()
        assertEquals(Result.failure(), runBlocking { missing.doWork() })
        assertTrue(host.sent.isEmpty())
    }

    private inner class FakeHost(preferences: WebDavQueuePreferences) : WebDavAutoUploadHost {
        var settingsState = WebDavQueueSettingsState(preferences)
        var enrollment: CaptureTransferEnrollment? = CaptureTransferEnrollment(bundle, endpoint.id, 0, preferences.revision, autoUpload = true)
        var enrollmentFails = false
        var result = AutoSendResult(WebDavTransferMessage.COMPLETE)
        var hang: CompletableDeferred<Unit>? = null
        val sendStarted = CompletableDeferred<Unit>()
        val sent = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
        override fun settings() = settingsState
        override fun enrollment(bundleId: String): CaptureTransferEnrollment? {
            if (enrollmentFails) throw CaptureTransferEnrollmentCorruptData()
            return enrollment
        }
        override suspend fun send(bundleId: String): AutoSendResult {
            sent.add(bundleId); sendStarted.complete(Unit)
            hang?.await()
            return result
        }
        override fun cancel(bundleId: String) { cancelled.add(bundleId) }
    }

    private fun id(value: Int) = "00000000-0000-0000-0000-${value.toString().padStart(12, '0')}"
}
