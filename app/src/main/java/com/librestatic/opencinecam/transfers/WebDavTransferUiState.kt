/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.transfers

enum class WebDavTransferMessage {
    IDLE, SENDING, VERIFYING, COMPLETE, WAITING_RECORDING, WAITING_MEDIA, DISABLED, NETWORK_UNAVAILABLE,
    CELLULAR_CONSENT_REQUIRED, AUTHENTICATION, CONFLICT, SOURCE_UNAVAILABLE, UNCERTAIN, ERROR, CANCELLED,
    /** A take on the USB drive waits for the drive to be connected again (PLAN-072); nothing was marked failed. */
    WAITING_DRIVE,
}

data class WebDavTransferUiState(
    val busy: Boolean = false,
    val bundles: List<WebDavOutboxBundle> = emptyList(),
    val message: WebDavTransferMessage = WebDavTransferMessage.IDLE,
    val activeBundleId: String? = null,
)
