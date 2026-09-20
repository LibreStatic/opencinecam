/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

/** Display/filter preferences only; neither grants file access nor changes capture configuration. */
data class GallerySettings(
    val kind: GalleryMediaKind = GalleryMediaKind.ALL,
    val newestFirst: Boolean = true,
    val goodTakesOnly: Boolean = false,
    val showSlate: Boolean = true,
    val showTechnical: Boolean = false,
)
enum class GalleryMediaKind { ALL, PHOTO, VIDEO, AUDIO }
