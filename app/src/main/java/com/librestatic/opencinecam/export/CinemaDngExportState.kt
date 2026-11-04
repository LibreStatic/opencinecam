/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.export

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What the gallery shows about the CinemaDNG export: one export at a time. */
internal sealed interface CinemaDngExportState {
    data object Idle : CinemaDngExportState

    /** [progress] is null until the take is indexed. */
    data class Running(
        val takeUri: String,
        val takeName: String,
        val progress: CinemaDngExportProgress? = null,
        val cancelling: Boolean = false,
    ) : CinemaDngExportState

    /** The last export's result, until the operator dismisses the notice. */
    data class Finished(val takeUri: String, val takeName: String, val outcome: CinemaDngExportOutcome) : CinemaDngExportState
}

/** What the gallery may ask of the export: its state, cancel and dismissing the notice. */
internal interface CinemaDngExportControl {
    val states: StateFlow<CinemaDngExportState>
    fun cancel()
    fun acknowledge()
}

/**
 * The process-wide export state the worker writes and the gallery observes. The transitions are
 * pure so host tests drive them; [CinemaDngExportRuntime] adds WorkManager.
 */
internal class CinemaDngExportStates {
    private val mutable = MutableStateFlow<CinemaDngExportState>(CinemaDngExportState.Idle)
    val states: StateFlow<CinemaDngExportState> = mutable.asStateFlow()

    /** Claims the single export slot for [takeUri]; false while another export runs. */
    fun begin(takeUri: String, takeName: String): Boolean {
        var claimed = false
        mutable.update { current ->
            claimed = false // update may retry the lambda
            if (current is CinemaDngExportState.Running) current
            else CinemaDngExportState.Running(takeUri, takeName).also { claimed = true }
        }
        return claimed
    }

    fun progress(takeUri: String, takeName: String, progress: CinemaDngExportProgress) = mutable.update { current ->
        val cancelling = (current as? CinemaDngExportState.Running)?.takeIf { it.takeUri == takeUri }?.cancelling ?: false
        CinemaDngExportState.Running(takeUri, takeName, progress, cancelling)
    }

    /** Marks the running export as cancelling; false when none runs. */
    fun cancelling(): Boolean {
        var marked = false
        mutable.update { current ->
            marked = false
            if (current is CinemaDngExportState.Running) current.copy(cancelling = true).also { marked = true } else current
        }
        return marked
    }

    fun finish(takeUri: String, takeName: String, outcome: CinemaDngExportOutcome) {
        mutable.value = CinemaDngExportState.Finished(takeUri, takeName, outcome)
    }

    /** Dismisses the notice of a finished export; a running one stays. */
    fun acknowledge() = mutable.update { if (it is CinemaDngExportState.Finished) CinemaDngExportState.Idle else it }
}
