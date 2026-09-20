/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import android.media.MediaFormat

/** An operator choice for one review, never a codec-specific or persisted fallback. */
enum class PreciseVideoColorPolicy { STRICT, INTERPRET_TRACK_SDR }

data class PreciseVideoColor(
    val standard: Int, val range: Int, val interpreted: Boolean,
    val reportedStandard: Int, val reportedRange: Int,
)

class PreciseVideoColorException(message: String, val trackSdrAvailable: Boolean) : IllegalArgumentException(message)

/** Only explicit supported track tags qualify for interpretation. Unknown/HDR transfers do not.
 * Strict mode keeps decoder evidence first, including rejection of explicit vendor values. */
fun resolvePreciseVideoColor(
    reportedStandard: Int, reportedRange: Int,
    declaredStandard: Int, declaredRange: Int, declaredTransfer: Int,
    policy: PreciseVideoColorPolicy = PreciseVideoColorPolicy.STRICT,
): PreciseVideoColor {
    val standards = setOf(MediaFormat.COLOR_STANDARD_BT709, MediaFormat.COLOR_STANDARD_BT601_NTSC, MediaFormat.COLOR_STANDARD_BT601_PAL)
    val ranges = setOf(MediaFormat.COLOR_RANGE_FULL, MediaFormat.COLOR_RANGE_LIMITED)
    val available = declaredStandard in standards && declaredRange in ranges && declaredTransfer == MediaFormat.COLOR_TRANSFER_SDR_VIDEO
    val interpreted = policy == PreciseVideoColorPolicy.INTERPRET_TRACK_SDR
    if (interpreted && !available) throw PreciseVideoColorException("Track interpretation requires explicit supported SDR standard, range and transfer", false)
    val standard = if (interpreted) declaredStandard else reportedStandard.takeUnless { it == 0 }
        ?: declaredStandard.takeUnless { it == 0 } ?: MediaFormat.COLOR_STANDARD_BT601_NTSC
    val range = if (interpreted) declaredRange else reportedRange.takeUnless { it == 0 }
        ?: declaredRange.takeUnless { it == 0 } ?: MediaFormat.COLOR_RANGE_LIMITED
    if (standard !in standards || range !in ranges) throw PreciseVideoColorException(
        "Unsupported precise video color-standard/range: reported=$reportedStandard/$reportedRange declared=$declaredStandard/$declaredRange/$declaredTransfer selected=$standard/$range", available)
    return PreciseVideoColor(standard, range, interpreted, reportedStandard, reportedRange)
}
