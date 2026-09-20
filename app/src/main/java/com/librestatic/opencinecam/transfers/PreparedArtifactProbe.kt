/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.transfers

import com.librestatic.opencinecam.storage.PreparedCaptureArtifact

/** Fresh descriptor-backed observation; pending SIZE is not used as the byte count. No hash claim. */
fun interface PreparedArtifactProbe {
    fun inspect(artifact: PreparedCaptureArtifact, pending: Boolean): WebDavClipSnapshot
}
