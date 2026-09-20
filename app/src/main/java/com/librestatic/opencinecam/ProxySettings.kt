/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

/** Requested parameters for manual post-capture proxies, not automatic capture behavior. */
data class ProxySettings(
    val maxLongEdge: Int = 1280,
    val videoBitrateMbps: Int = 3,
) {
    init {
        require(maxLongEdge in listOf(640, 1280, 1920)) { "Unsupported proxy long-edge limit" }
        require(videoBitrateMbps in listOf(1, 2, 3, 5, 8)) { "Unsupported requested proxy video bitrate" }
    }
}
