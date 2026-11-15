/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import android.media.AudioFormat
import com.librestatic.opencinecam.media.audio.AudioSourceSelection
import com.librestatic.opencinecam.media.audio.OpenedPcmInput
import com.librestatic.opencinecam.media.audio.PcmInput
import com.librestatic.opencinecam.media.audio.PcmInputPath
import com.librestatic.opencinecam.media.audio.PcmInputTimestamp
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EqCurveJsonTest {
    private val curve = EqCurve(
        points = listOf(
            EqPoint.coerced(EqFilterType.HIGH_PASS, 80.0, 0.0, 0.7071),
            EqPoint.coerced(EqFilterType.BELL, 3_150.0, -4.5, 2.0),
            EqPoint.coerced(EqFilterType.HIGH_SHELF, 10_000.0, 3.0, 0.8, enabled = false),
        ),
        trimDb = -1.5,
    )

    @Test
    fun roundTripsEveryField() {
        assertEquals(curve, decodeEqCurve(curve.toJson()))
        assertEquals(curve.copy(enabled = false), decodeEqCurve(curve.copy(enabled = false).toJson()))
        assertEquals(EqCurve(), decodeEqCurve(EqCurve().toJson()))
    }

    @Test
    fun clampsValuesOutOfRange() {
        val decoded = requireNotNull(decodeEqCurve(
            """{"trimDb": 40, "points": [{"type": "BELL", "frequencyHz": 5, "gainDb": 99, "q": 50}]}""",
        ))
        assertEquals(EqCurve.MAX_TRIM_DB, decoded.trimDb, 0.0)
        val point = decoded.points.single()
        assertEquals(EqPoint.MIN_FREQUENCY_HZ, point.frequencyHz, 0.0)
        assertEquals(EqPoint.MAX_GAIN_DB, point.gainDb, 0.0)
        assertEquals(EqPoint.MAX_Q, point.q, 0.0)
        assertTrue(decoded.enabled)
    }

    @Test
    fun skipsUnknownPointsAndFillsDefaults() {
        val decoded = requireNotNull(decodeEqCurve(
            """{"points": [{"type": "TILT", "frequencyHz": 1000}, {"type": "BELL"}, "x",
               {"type": "NOTCH", "frequencyHz": 60}]}""",
        ))
        val point = decoded.points.single()
        assertEquals(EqFilterType.NOTCH, point.type)
        assertEquals(0.0, point.gainDb, 0.0)
        assertEquals(0.7071, point.q, 1e-9)
        assertTrue(point.enabled)
        assertEquals(0.0, decoded.trimDb, 0.0)
    }

    @Test
    fun keepsAtMostTheMaximumPoints() {
        val many = (1..30).joinToString(",") { """{"type": "BELL", "frequencyHz": ${it * 100}, "gainDb": 1}""" }
        assertEquals(EqCurve.MAX_POINTS, requireNotNull(decodeEqCurve("""{"points": [$many]}""")).points.size)
    }

    @Test
    fun aPointWithAMalformedTypeIsSkippedNotTheCurve() {
        val kept = (1..EqCurve.MAX_POINTS).joinToString(",") { """{"type": "BELL", "frequencyHz": ${it * 100}, "gainDb": 1}""" }
        // Past the kept points: never read.
        val after = requireNotNull(decodeEqCurve("""{"points": [$kept, {"type": {}}]}"""))
        assertEquals(EqCurve.MAX_POINTS, after.points.size)
        // Among them: skipped like an unknown type.
        val among = requireNotNull(decodeEqCurve(
            """{"points": [{"type": ["BELL"], "frequencyHz": 100}, {"type": "NOTCH", "frequencyHz": 60}]}""",
        ))
        assertEquals(EqFilterType.NOTCH, among.points.single().type)
    }

    @Test
    fun refusesWhatIsNotACurveAndStringNumbers() {
        assertNull(decodeEqCurve("not json"))
        assertNull(decodeEqCurve("[1, 2]"))
        val stringy = requireNotNull(decodeEqCurve(
            """{"enabled": "false", "trimDb": "3", "points": [{"type": "BELL", "frequencyHz": "1000"}]}""",
        ))
        assertTrue(stringy.enabled)
        assertEquals(0.0, stringy.trimDb, 0.0)
        assertTrue(stringy.points.isEmpty())
    }

    @Test
    fun aFlatCurveGetsNoEqualiser() {
        assertNull(inputEqualizerFor(EqCurve(), 48_000, 2))
        assertNull(inputEqualizerFor(curve.copy(enabled = false), 48_000, 2))
        assertEquals(curve, requireNotNull(inputEqualizerFor(curve, 48_000, 2)).curve)
    }

    private class SilentInput(override val path: PcmInputPath) : PcmInput {
        override val sampleRateHz = 48_000
        override val channels = 2
        override val encoding = AudioFormat.ENCODING_PCM_16BIT
        override val audioSessionId: Int? = null
        override val routedDeviceId: Int? = null
        override fun start() = Unit
        override fun read(buffer: ByteBuffer, byteCount: Int) = 0
        override fun timestamp(out: PcmInputTimestamp) = false
        override fun stop() = Unit
        override fun close() = Unit
    }

    @Test
    fun evidenceRecordsThePathAndOnlyACurveThatChangesTheSignal() {
        val fallback = pcmInputEvidence(AudioSourceSelection.LOW_LATENCY.name,
            OpenedPcmInput(SilentInput(PcmInputPath.AUDIO_RECORD), "external-input", 24), EqCurve())
        assertEquals(PcmInputEvidence("LOW_LATENCY", "AUDIO_RECORD", "external-input", 24, null), fallback)
        val native = pcmInputEvidence("LOW_LATENCY", OpenedPcmInput(SilentInput(PcmInputPath.AAUDIO_LOW_LATENCY), null, 16), curve)
        assertEquals("AAUDIO_LOW_LATENCY", native.inputPath)
        assertNull(native.inputFallbackReason)
        assertEquals(16, native.captureBitDepth)
        assertEquals(curve, native.equaliser)
        assertNull(pcmInputEvidence("MIC", OpenedPcmInput(SilentInput(PcmInputPath.AUDIO_RECORD), null, 16),
            curve.copy(enabled = false)).equaliser)
    }

    @Test
    fun evidenceNamesTheProfileOnlyWithTheCurveItApplies() {
        val opened = OpenedPcmInput(SilentInput(PcmInputPath.AAUDIO_LOW_LATENCY), null, 16)
        assertNull(pcmInputEvidence("LOW_LATENCY", opened, curve).equaliserProfileId)
        assertEquals("bundled-blanc", pcmInputEvidence("LOW_LATENCY", opened, curve, "bundled-blanc").equaliserProfileId)
        // A bypassed or flat curve is not applied, so it names no profile either.
        val bypassed = pcmInputEvidence("LOW_LATENCY", opened, curve.copy(enabled = false), "bundled-blanc")
        assertNull(bypassed.equaliser)
        assertNull(bypassed.equaliserProfileId)
        assertNull(pcmInputEvidence("LOW_LATENCY", opened, EqCurve(), "user-0123abcd").equaliserProfileId)
    }

    @Test
    fun aBundledCurveAppliesOnlyOnTheLowLatencyPath() {
        val native = OpenedPcmInput(SilentInput(PcmInputPath.AAUDIO_LOW_LATENCY), null, 16)
        val fallback = OpenedPcmInput(SilentInput(PcmInputPath.AUDIO_RECORD), "aaudio-open-failed", 16)
        val external = OpenedPcmInput(SilentInput(PcmInputPath.AUDIO_RECORD), "external-input", 16)
        assertEquals(curve, pcmInputEvidence("LOW_LATENCY", native, curve, "bundled-blanc").equaliser)
        assertEquals(curve, requireNotNull(pcmInputEvidence("LOW_LATENCY", native, curve, "bundled-blanc").inputEqualizer(48_000, 2)).curve)
        for (opened in listOf(fallback, external)) {
            val evidence = pcmInputEvidence("LOW_LATENCY", opened, curve, "bundled-blanc")
            assertNull(evidence.equaliser)
            assertNull(evidence.equaliserProfileId)
            assertNull(evidence.inputEqualizer(48_000, 2))
        }
        // A curve of the user's own, saved or not, applies on any path.
        assertEquals(curve, pcmInputEvidence("LOW_LATENCY", fallback, curve, "user-0123abcd").equaliser)
        assertEquals("user-0123abcd", pcmInputEvidence("LOW_LATENCY", fallback, curve, "user-0123abcd").equaliserProfileId)
        assertEquals(curve, pcmInputEvidence("LOW_LATENCY", external, curve).equaliser)
        assertEquals(curve, appliedEqCurve(curve, null, PcmInputPath.AUDIO_RECORD))
        assertEquals(EqCurve(), appliedEqCurve(curve, "bundled-blanc", PcmInputPath.AUDIO_RECORD))
    }
}
