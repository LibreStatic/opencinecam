/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.transfers

import com.librestatic.opencinecam.transfers.WebDavTransferMessage.*
import org.junit.Assert.assertEquals
import org.junit.Test

/** Builds only artifacts the model itself accepts, so each case is a state the outbox can really hold. */
internal object AutoUploadArtifacts {
    private const val TOKEN = "00000000-0000-0000-0000-0000000000aa"
    private const val HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    private val admission = WebDavOutboxAdmission(TOKEN, 1, WebDavUploadPolicy(enabled = true, network = WebDavNetwork.WIFI))
    private val spec = WebDavArtifactSpec("00000000-0000-0000-0000-000000000001", WebDavArtifactRole.VIDEO,
        "content://media/external/video/media/1", "take.mp4", 10)

    fun queued() = WebDavOutboxArtifact(spec)
    fun verified() = remote(WebDavArtifactState.VERIFIED)
    fun conflict() = remote(WebDavArtifactState.CONFLICT)
    fun uncertainWithoutAttempt() = remote(WebDavArtifactState.UNCERTAIN)
    fun sourceUnavailable() = WebDavOutboxArtifact(spec, modifiedSeconds = 1, state = WebDavArtifactState.SOURCE_UNAVAILABLE,
        sourceFailure = WebDavSourceFailure(spec.sourceUri, WebDavSourceFailureReason.MISSING))
    fun uploading() = remote(WebDavArtifactState.UPLOADING,
        WebDavOutboxAttempt("00000000-0000-0000-0000-0000000000bb", WebDavAttemptKind.PUT, TOKEN))
    fun reconciling() = remote(WebDavArtifactState.UNCERTAIN,
        WebDavOutboxAttempt("00000000-0000-0000-0000-0000000000cc", WebDavAttemptKind.RECONCILE, TOKEN))

    private fun remote(state: WebDavArtifactState, attempt: WebDavOutboxAttempt? = null) = WebDavOutboxArtifact(spec,
        revision = 1, modifiedSeconds = 1, sha256 = HASH, state = state, attempt = attempt, lastAdmission = admission, remoteMayExist = true)
}

class AutoUploadOutcomeTest {
    private fun outcome(message: WebDavTransferMessage, artifacts: List<WebDavOutboxArtifact> = listOf(AutoUploadArtifacts.queued()),
        attempts: Int = 0, busy: Boolean = false) = autoUploadOutcome(message, artifacts, attempts, busy)

    @Test fun completeIsSuccess() {
        assertEquals(AutoUploadOutcome.SUCCESS, outcome(COMPLETE, listOf(AutoUploadArtifacts.verified())))
    }

    @Test fun messagesThatNeedTheUserStopWithoutRetry() {
        for (message in listOf(AUTHENTICATION, CONFLICT, SOURCE_UNAVAILABLE, ERROR, DISABLED, CANCELLED)) {
            assertEquals(message.name, AutoUploadOutcome.STOP, outcome(message))
        }
    }

    @Test fun conflictOrMissingSourceArtifactsStopEvenUnderATransientMessage() {
        for (artifact in listOf(AutoUploadArtifacts.conflict(), AutoUploadArtifacts.sourceUnavailable())) {
            assertEquals(AutoUploadOutcome.STOP, outcome(NETWORK_UNAVAILABLE, listOf(AutoUploadArtifacts.queued(), artifact)))
            assertEquals(AutoUploadOutcome.STOP, outcome(UNCERTAIN, listOf(artifact), busy = true))
        }
    }

    @Test fun anInFlightAttemptNeedsReconciliationAndIsNeverRetriedBlindly() {
        for (artifact in listOf(AutoUploadArtifacts.uploading(), AutoUploadArtifacts.reconciling())) {
            for (message in listOf(UNCERTAIN, NETWORK_UNAVAILABLE, WAITING_RECORDING, WAITING_MEDIA)) {
                assertEquals(AutoUploadOutcome.STOP, outcome(message, listOf(artifact)))
            }
            assertEquals(AutoUploadOutcome.STOP, outcome(WAITING_MEDIA, listOf(artifact), busy = true))
        }
    }

    @Test fun transientMessagesRetryWhileArtifactsAreQueuedOrVerifiable() {
        val queued = listOf(AutoUploadArtifacts.verified(), AutoUploadArtifacts.queued())
        val verifiable = listOf(AutoUploadArtifacts.uncertainWithoutAttempt())
        for (message in listOf(WAITING_RECORDING, WAITING_MEDIA, NETWORK_UNAVAILABLE, CELLULAR_CONSENT_REQUIRED, UNCERTAIN)) {
            assertEquals(message.name, AutoUploadOutcome.RETRY, outcome(message, queued))
            assertEquals(message.name, AutoUploadOutcome.RETRY, outcome(message, verifiable))
        }
    }

    @Test fun aBusyRuntimeIsTransientEvenWithoutArtifactsOrAMessageThatIsTransient() {
        assertEquals(AutoUploadOutcome.RETRY, outcome(IDLE, emptyList(), busy = true))
        assertEquals(AutoUploadOutcome.RETRY, outcome(SENDING, emptyList(), busy = true))
    }

    @Test fun retriesAreBoundedByTheAttemptCap() {
        assertEquals(AutoUploadOutcome.RETRY, outcome(NETWORK_UNAVAILABLE, attempts = AUTO_UPLOAD_MAX_RUN_ATTEMPTS - 1))
        assertEquals(AutoUploadOutcome.STOP, outcome(NETWORK_UNAVAILABLE, attempts = AUTO_UPLOAD_MAX_RUN_ATTEMPTS))
        assertEquals(AutoUploadOutcome.STOP, outcome(IDLE, emptyList(), attempts = AUTO_UPLOAD_MAX_RUN_ATTEMPTS, busy = true))
    }

    @Test fun nonFinalMessagesAreNotRetriedAsIfTransient() {
        for (message in listOf(IDLE, SENDING, VERIFYING)) assertEquals(message.name, AutoUploadOutcome.STOP, outcome(message))
    }
}
