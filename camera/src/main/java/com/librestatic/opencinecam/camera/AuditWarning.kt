/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

/** The text for a WARNING-level audit finding, or null for an event that is no warning. Stable, so repeats deduplicate. */
fun auditWarningText(event: CaptureEvidenceEvent): String? {
    if (event.action != CaptureAuditAction.WARNING) return null
    val failure = event.failure ?: return null
    return "${failure.code.name}: ${failure.userMessage}"
}
