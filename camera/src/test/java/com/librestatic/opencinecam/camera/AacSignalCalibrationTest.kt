/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import org.junit.Assert.*
import org.junit.Test

class AacSignalCalibrationTest {
    private fun signal(channels: Int = 1) = aacCalibrationSignal(16384, 48000, channels)
    @Test fun findsMeasuredDelayRatherThanAssuming2048() {
        val source = signal()
        val result = measureAacSignal(source, ShortArray(137) + source + ShortArray(600), 1)
        assertEquals(137, result.lagFrames)
        assertEquals(1.0, result.minimumCorrelation, 0.000001)
    }
    @Test fun equalTotalLengthsCanStillLoseTheSourceTail() {
        val source = signal()
        assertThrows(IllegalArgumentException::class.java) { measureAacSignal(source, ShortArray(137) + source.copyOf(source.size - 137), 1) }
    }
    @Test fun channelsMustHaveTheSameMeasuredDelay() {
        val source = signal(2)
        val shifted = ShortArray(source.size + 600)
        for (frame in 0 until source.size / 2) {
            shifted[(frame + 137) * 2] = source[frame * 2]
            shifted[(frame + 138) * 2 + 1] = source[frame * 2 + 1]
        }
        assertThrows(IllegalArgumentException::class.java) { measureAacSignal(source, shifted, 2) }
    }
    @Test fun zeroDelayAndStereoAreMeasured() {
        val source = signal(2)
        assertEquals(0, measureAacSignal(source, source + ShortArray(600), 2).lagFrames)
    }
    @Test fun silenceIsNotAValidCorrelation() {
        assertThrows(IllegalArgumentException::class.java) { measureAacSignal(signal(), ShortArray(18000), 1) }
    }
    @Test fun droppedBeginningIsRejected() {
        val source = signal()
        assertThrows(IllegalArgumentException::class.java) { measureAacSignal(source, source.copyOfRange(25, source.size) + ShortArray(600), 1) }
    }
    @Test fun malformedPcmAndSignalLimitsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { measureAacSignal(ShortArray(32769), ShortArray(34000), 2) }
        assertThrows(IllegalArgumentException::class.java) { aacCalibrationSignal(5, 48000, 1) }
        assertThrows(IllegalArgumentException::class.java) { aacCalibrationSignal(16384, 0, 1) }
        assertThrows(IllegalArgumentException::class.java) { aacCalibrationSignal(16384, 48000, 3) }
    }
    @Test fun cancellationIsCheckedBeforeAnyNativeCodecCall() {
        val failure = assertThrows(IllegalStateException::class.java) {
            AacCodecCalibrator.qualify(AacCalibrationConfig("never-created", 48000, 1, 96000, 5760), isCancelled = { true })
        }
        assertEquals("AAC calibration was cancelled", failure.message)
    }
    @Test fun configurationAndCodecSpecificDataBoundsAreExplicit() {
        assertThrows(IllegalArgumentException::class.java) { AacCalibrationConfig("", 48000, 1, 96000, 5760) }
        assertThrows(IllegalArgumentException::class.java) { AacCalibrationConfig("test", 48000, 3, 96000, 5760) }
        assertThrows(IllegalArgumentException::class.java) { aacCodecConfigSha256(byteArrayOf()) }
        assertEquals(64, aacCodecConfigSha256(byteArrayOf(0x11, 0x88.toByte())).length)
    }
    @Test fun codecSpecificDataMismatchFailsTheTakeAndEvictsOnlyThatCalibration() {
        val config = AacCalibrationConfig("evict-on-mismatch", 48000, 1, 96000, 5760)
        val calibratedCsd = byteArrayOf(0x11, 0x88.toByte())
        val calibration = AacCodecCalibration(config, "decoder", 2048, 4096, 0.99, 0, 0, 0, 1, aacCodecConfigSha256(calibratedCsd))
        AacCodecCalibrator.remember(calibration)
        AacCodecCalibrator.requireCalibratedCodecConfig(calibration, calibratedCsd)
        assertSame(calibration, AacCodecCalibrator.cached(config))
        val failure = assertThrows(IllegalStateException::class.java) {
            AacCodecCalibrator.requireCalibratedCodecConfig(calibration, byteArrayOf(0x12, 0x10))
        }
        assertEquals("AAC configuration changed after calibration", failure.message)
        assertNull(AacCodecCalibrator.cached(config))
        // A stale take's late mismatch must not evict a newer calibration measured for the same config.
        val newer = calibration.copy(measuredAtElapsedMs = 2, codecSpecificDataSha256 = aacCodecConfigSha256(byteArrayOf(0x12, 0x10)))
        AacCodecCalibrator.remember(newer)
        assertThrows(IllegalStateException::class.java) { AacCodecCalibrator.requireCalibratedCodecConfig(calibration, byteArrayOf(0x13)) }
        assertSame(newer, AacCodecCalibrator.cached(config))
        assertTrue(AacCodecCalibrator.invalidate(newer)); assertNull(AacCodecCalibrator.cached(config))
    }

}
