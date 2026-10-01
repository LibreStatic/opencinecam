/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import android.media.MediaFormat
import org.junit.Assert.*
import org.junit.Test

class PreciseVideoColorTest {
    private val strict = PreciseVideoColorPolicy.STRICT
    private val interpreted = PreciseVideoColorPolicy.INTERPRET_TRACK_SDR

    @Test fun strictModeRejectsVendorColorDespiteKnownTrack() {
        val failure = assertThrows(PreciseVideoColorException::class.java) {
            resolvePreciseVideoColor(130817, 2, 1, 2, 3)
        }
        assertTrue(failure.trackSdrAvailable)
        assertTrue(failure.message!!.contains("reported=130817/2"))
        assertTrue(failure.message!!.contains("declared=1/2/3"))
    }

    @Test fun strictModeStillRejectsBt2020WithoutAnOcLog2Sidecar() {
        // OCLog2 review bypasses this resolver only when the sidecar declares the clip.
        assertThrows(PreciseVideoColorException::class.java) {
            resolvePreciseVideoColor(MediaFormat.COLOR_STANDARD_BT2020, MediaFormat.COLOR_RANGE_FULL,
                MediaFormat.COLOR_STANDARD_BT2020, MediaFormat.COLOR_RANGE_FULL, 2, strict)
        }
    }

    @Test fun manualChoiceUsesTrackTagsAndPreservesReportedEvidence() {
        assertEquals(PreciseVideoColor(1, 2, true, 130817, 2), resolvePreciseVideoColor(130817, 2, 1, 2, 3, interpreted))
        assertThrows(PreciseVideoColorException::class.java) { resolvePreciseVideoColor(130817, 2, 1, 2, 3, strict) }
    }

    @Test fun supportedSdrStandardsAndRangesRequireExplicitInterpretation() {
        for (standard in listOf(MediaFormat.COLOR_STANDARD_BT709, MediaFormat.COLOR_STANDARD_BT601_NTSC, MediaFormat.COLOR_STANDARD_BT601_PAL)) {
            for (range in listOf(MediaFormat.COLOR_RANGE_FULL, MediaFormat.COLOR_RANGE_LIMITED)) {
                assertEquals(PreciseVideoColor(standard, range, true, 130820, 99), resolvePreciseVideoColor(130820, 99, standard, range, 3, interpreted))
            }
        }
    }

    @Test fun missingOrUnknownTrackStandardNeverOffersInterpretation() {
        for (standard in listOf(0, -1, 6, 130817)) {
            val failure = assertThrows(PreciseVideoColorException::class.java) { resolvePreciseVideoColor(130817, 2, standard, 2, 3) }
            assertFalse(failure.trackSdrAvailable)
            assertThrows(PreciseVideoColorException::class.java) { resolvePreciseVideoColor(1, 2, standard, 2, 3, interpreted) }
        }
    }

    @Test fun missingOrUnknownTrackRangeNeverOffersInterpretation() {
        for (range in listOf(0, -1, 3, 99)) {
            val failure = assertThrows(PreciseVideoColorException::class.java) { resolvePreciseVideoColor(130817, 2, 1, range, 3) }
            assertFalse(failure.trackSdrAvailable)
            assertThrows(PreciseVideoColorException::class.java) { resolvePreciseVideoColor(1, 2, 1, range, 3, interpreted) }
        }
    }

    @Test fun unspecifiedHdrLinearAndUnknownTransfersNeverQualify() {
        for (transfer in listOf(0, -1, 1, 6, 7, 99)) {
            val failure = assertThrows(PreciseVideoColorException::class.java) { resolvePreciseVideoColor(130817, 2, 1, 2, transfer) }
            assertFalse(failure.trackSdrAvailable)
            assertThrows(PreciseVideoColorException::class.java) { resolvePreciseVideoColor(1, 2, 1, 2, transfer, interpreted) }
        }
    }

    @Test fun strictModeHonorsKnownDecoderTagsBeforeTrackTags() {
        assertEquals(PreciseVideoColor(4, 1, false, 4, 1), resolvePreciseVideoColor(4, 1, 1, 2, 3))
    }

    @Test fun unspecifiedDecoderRetainsTrackOrExistingStrictDefault() {
        assertEquals(PreciseVideoColor(1, 1, false, 0, 0), resolvePreciseVideoColor(0, 0, 1, 1, 3))
        assertEquals(PreciseVideoColor(4, 2, false, 0, 0), resolvePreciseVideoColor(0, 0, 0, 0, 0))
    }

    @Test fun unsupportedDecoderRangeRemainsExplicitErrorInStrictMode() {
        val failure = assertThrows(PreciseVideoColorException::class.java) { resolvePreciseVideoColor(1, 99, 1, 2, 3) }
        assertTrue(failure.trackSdrAvailable)
    }
}
