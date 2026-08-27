/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

/** Qualification is distinct from runtime capability and is always fail-closed. */
enum class OpenCineLogQualificationStage {
    UNKNOWN,
    EXPERIMENTAL,
    VERIFIED,
    FAILED,
    UNSUPPORTED,
}

internal object OpenCineLogQualificationPolicy {
    const val NOT_RUN_REASON = "oclog2-device-qualification-not-run"

    fun isVerified(stage: OpenCineLogQualificationStage, evidenceId: String?): Boolean =
        stage == OpenCineLogQualificationStage.VERIFIED && !evidenceId.isNullOrBlank()
}
