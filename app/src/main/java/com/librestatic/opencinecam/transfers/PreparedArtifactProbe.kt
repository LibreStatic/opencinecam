/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.transfers

import com.librestatic.opencinecam.storage.GyroflowLog
import com.librestatic.opencinecam.storage.PreparedCaptureArtifact

/** Fresh descriptor-backed observation; pending SIZE is not used as the byte count. No hash claim. */
fun interface PreparedArtifactProbe {
    fun inspect(artifact: PreparedCaptureArtifact, pending: Boolean): WebDavClipSnapshot
}

/**
 * The MIME types a Gyroflow log (`.gcsv`) may be listed with: the one it is created with, CSV as providers name it,
 * and the opaque type a provider that knows no `.gcsv` reports (the USB drive's does).
 */
internal fun motionLogMimeTypes(extension: String): Set<String> = if (extension == GyroflowLog.EXTENSION)
    setOf(GyroflowLog.MIME_TYPE, "text/csv", "text/comma-separated-values", "application/octet-stream") else emptySet()
