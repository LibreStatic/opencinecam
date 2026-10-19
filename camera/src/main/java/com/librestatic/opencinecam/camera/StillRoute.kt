/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

/**
 * The physical camera a still's own result confirms. The routed id when the result's physical results
 * contain it; null when the result carries none (unconfirmed, not a mismatch); otherwise one of the
 * ids it does carry (smallest, so the answer is stable), which is a real mismatch.
 */
fun stillRouteReportedId(requestedPhysicalId: String?, physicalResultIds: Set<String>): String? = when {
    requestedPhysicalId == null || physicalResultIds.isEmpty() -> null
    requestedPhysicalId in physicalResultIds -> requestedPhysicalId
    else -> physicalResultIds.min()
}
