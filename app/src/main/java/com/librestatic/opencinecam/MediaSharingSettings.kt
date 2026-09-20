/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

/** Export intent only; preparing or opening a chooser does not prove delivery to another app. */
data class MediaSharingSettings(
    val content: MediaShareContent = MediaShareContent.ORIGINALS_AND_METADATA,
    val metadata: MediaShareMetadata = MediaShareMetadata.BOTH,
    val includeReferencedLut: Boolean = true,
)
enum class MediaShareContent { ORIGINALS_AND_METADATA, ORIGINALS_ONLY, METADATA_ONLY }
enum class MediaShareMetadata { PRODUCTION, TECHNICAL, BOTH }
