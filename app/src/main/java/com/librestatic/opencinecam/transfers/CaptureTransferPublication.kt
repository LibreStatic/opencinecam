/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.transfers

import android.content.Context
import com.librestatic.opencinecam.storage.CapturePublicationObserver
import java.util.UUID

/** One REC admission owns one immutable enrollment and one optional database handle, never I/O. */
class CaptureTransferPublication private constructor(
    val bundleId: String,
    val observer: CapturePublicationObserver,
    private val bridge: CapturePublicationOutboxBridge?,
    private val outbox: WebDavOutboxStore?,
    val admissionFailure: Exception?,
) : AutoCloseable {
    val registered: Boolean get() = bridge?.lastRegistration?.status in setOf(
        CaptureOutboxRegistrationStatus.REGISTERED, CaptureOutboxRegistrationStatus.ALREADY_REGISTERED)
    val registrationFailed: Boolean get() = admissionFailure != null || bridge?.issues?.isNotEmpty() == true
    private var closed = false

    /** Optional cleanup cannot invalidate published capture bytes. The caller logs returned errors. */
    fun abort(): List<Exception> {
        if (closed) return emptyList()
        val errors = mutableListOf<Exception>()
        try { observer.onAborted() } catch (failure: Exception) { errors.add(failure) }
        try { close() } catch (failure: Exception) { errors.add(failure) }
        return errors
    }

    override fun close() {
        if (closed) return
        outbox?.close()
        closed = true
    }

    companion object {
        /** Freeze before engine.startVideo; changing settings during REC affects only later takes. */
        fun admit(context: Context, state: WebDavQueueSettingsState = WebDavQueueSettings.get(context).snapshotForAdmission(),
            bundleId: String = UUID.randomUUID().toString(), databaseName: String = "webdav-outbox.db"): CaptureTransferPublication {
            val journal = CapturePublicationJournal(context, bundleId)
            var store: WebDavOutboxStore? = null
            return try {
                check(!state.storageFailed) { "Queue preferences require recovery" }
                val preferences = state.preferences
                if (!preferences.enabled) return CaptureTransferPublication(bundleId, journal, null, null, null)
                val endpoint = requireNotNull(preferences.activeEndpoint)
                val enrollments = CaptureTransferEnrollmentFile(context)
                // An endpoint's URL never mutates: a different URL receives a different UUID.
                enrollments.enroll(CaptureTransferEnrollment(bundleId, endpoint.id, 0L, preferences.revision))
                store = WebDavSqliteOutbox(context, databaseName)
                val bridge = CapturePublicationOutboxBridge(bundleId, journal,
                    { CapturePublicationJournal.load(context, it) }, enrollments, store,
                    MediaStorePreparedArtifactProbe(context.contentResolver))
                CaptureTransferPublication(bundleId, bridge, bridge, store, null)
            } catch (failure: Exception) {
                try { store?.close() } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
                // Preserve the independent journal even when optional queue enrollment fails.
                CaptureTransferPublication(bundleId, journal, null, null, failure)
            }
        }
    }
}
