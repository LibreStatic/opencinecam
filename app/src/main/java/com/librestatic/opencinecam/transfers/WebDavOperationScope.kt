/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.transfers

import java.net.HttpURLConnection

/** Borrowed serial operation owner. Only the outer coordinator may enter/leave the attempt. */
internal class WebDavOperationScope(private val attempt: WebDavUploadControl.Attempt) {
    val stopReason: WebDavStopReason? get() = attempt.reason
    fun checkRunning() = attempt.checkRunning()
    fun bind(connection: HttpURLConnection) = attempt.bind(connection)
    fun unbind() = attempt.unbind()
}
