/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import com.librestatic.opencinecam.camera.AudioSpectrum
import com.librestatic.opencinecam.camera.EqCurve
import com.librestatic.opencinecam.camera.EqFilterType
import com.librestatic.opencinecam.camera.EqPoint
import com.librestatic.opencinecam.camera.EqProfile
import com.librestatic.opencinecam.service.EqAuditionPhase
import com.librestatic.opencinecam.service.EqAuditionState
import com.librestatic.opencinecam.settings.CameraSettings
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class InputEqualizerModelTest {
    private val reference = EqProfile(
        "bundled-motorola-blanc", "Razr Fold microphones",
        EqCurve(listOf(EqPoint(EqFilterType.BELL, 10_000.0, -9.5, 1.1), EqPoint(EqFilterType.BELL, 8_100.0, -9.5, 1.8),
            EqPoint(EqFilterType.HIGH_SHELF, 13_500.0, -3.5, 0.9))),
        bundled = true, device = "blanc",
    )
    private val voice = EqProfile("u-voice", "Voice", EqCurve(listOf(EqPoint(EqFilterType.HIGH_PASS, 80.0, 0.0, 0.7))))
    private val bellAt2k = EqPoint(EqFilterType.BELL, 2_000.0, 3.0, 1.0)

    @Test fun simpleShapeIsTheThreeFixedPointsInOrderAndOn() {
        val simple = EqCurve(simpleEqPoints())
        assertTrue(isSimpleShape(simple))
        assertTrue(isSimpleShape(simple.withSimpleGain(SimpleEqBand.MID, 4.5)))
        assertFalse(isSimpleShape(EqCurve()))
        assertFalse(isSimpleShape(simple.copy(points = simple.points.reversed())))
        assertFalse(isSimpleShape(simple.withPoint(0, simple.points[0].copy(enabled = false))))
        assertFalse(isSimpleShape(simple.withPoint(1, simple.points[1].copy(frequencyHz = 1_010.0))))
        assertFalse(isSimpleShape(simple.withPoint(2, simple.points[2].copy(q = 1.0))))
        assertFalse(isSimpleShape(simple.withAddedPoint(bellAt2k)!!))
        assertFalse(isSimpleShape(reference.curve))
    }

    @Test fun simpleModeIsOfferedOnAnEmptyCurveOrItsOwnShape() {
        assertTrue(simpleModeOffered(EqCurve()))
        assertEquals(EqEditorMode.SIMPLE, initialEditorMode(EqCurve()))
        assertEquals(EqEditorMode.SIMPLE, initialEditorMode(EqCurve(simpleEqPoints())))
        assertFalse(simpleModeOffered(reference.curve))
        assertEquals(EqEditorMode.FULL, initialEditorMode(reference.curve))
    }

    @Test fun aSimpleGainOnAnEmptyCurvePutsTheThreePointsIn() {
        val curve = EqCurve(trimDb = 2.0).withSimpleGain(SimpleEqBand.HIGH, -4.0)
        assertTrue(isSimpleShape(curve))
        assertEquals(listOf(0.0, 0.0, -4.0), curve.points.map { it.gainDb })
        assertEquals(2.0, curve.trimDb, 0.0)
        assertEquals(-4.0, curve.simpleGainDb(SimpleEqBand.HIGH), 0.0)
        assertEquals(0.0, EqCurve().simpleGainDb(SimpleEqBand.HIGH), 0.0)
        // Clamped like any point.
        assertEquals(EqPoint.MAX_GAIN_DB, curve.withSimpleGain(SimpleEqBand.LOW, 40.0).simpleGainDb(SimpleEqBand.LOW), 0.0)
    }

    @Test fun pointsAreAddedUpToTheCapAndEditedByIndex() {
        var curve = EqCurve()
        repeat(EqCurve.MAX_POINTS) { curve = curve.withAddedPoint(bellAt2k)!! }
        assertNull(curve.withAddedPoint(bellAt2k))
        val moved = curve.withPoint(3, bellAt2k.copy(gainDb = -6.0))
        assertEquals(-6.0, moved.points[3].gainDb, 0.0)
        assertEquals(EqCurve.MAX_POINTS - 1, moved.withoutPoint(0).points.size)
        assertSame(curve, curve.withPoint(99, bellAt2k))
        assertSame(curve, curve.withoutPoint(-1))
        assertFalse(EqFilterType.HIGH_PASS.hasGain)
        assertFalse(EqFilterType.NOTCH.hasGain)
        assertTrue(EqFilterType.LOW_SHELF.hasGain)
    }

    @Test fun theActiveProfileIsReferenceUserCustomOrFlat() {
        val users = listOf(voice)
        assertEquals(ActiveEqProfile.Reference(reference), activeEqProfile(reference.id, reference.curve, reference, users))
        assertEquals(ActiveEqProfile.User(voice), activeEqProfile(voice.id, voice.curve, reference, users))
        // A missing user profile, or another phone's bundled one, reads as custom.
        assertEquals(ActiveEqProfile.Custom, activeEqProfile("u-gone", voice.curve, reference, users))
        assertEquals(ActiveEqProfile.Custom, activeEqProfile("bundled-other", reference.curve, null, users))
        assertEquals(ActiveEqProfile.Custom, activeEqProfile(null, voice.curve, reference, users))
        assertEquals(ActiveEqProfile.Custom, activeEqProfile(null, EqCurve(trimDb = 1.0), reference, users))
        assertEquals(ActiveEqProfile.Flat, activeEqProfile(null, EqCurve(), reference, users))
        assertEquals(ActiveEqProfile.Flat, activeEqProfile(null, EqCurve(enabled = false), reference, users))
    }

    @Test fun onlyTheBypassKeepsTheReferenceProfile() {
        val settings = CameraSettings().withEqProfile(reference)
        val bypassed = settings.withEditedEqCurve(settings.audioEqCurve.copy(enabled = false), listOf(voice))
        assertEquals(reference.id, bypassed.settings.audioEqProfileId)
        assertFalse(bypassed.settings.audioEqCurve.enabled)
        assertNull(bypassed.save)
        val edited = settings.withEditedEqCurve(settings.audioEqCurve.withPoint(0, reference.curve.points[0].copy(gainDb = -3.0)), listOf(voice))
        assertNull(edited.settings.audioEqProfileId)
        assertEquals(-3.0, edited.settings.audioEqCurve.points[0].gainDb, 0.0)
        assertNull(edited.save)
        // Even before the store is read.
        assertNull(settings.withEditedEqCurve(settings.audioEqCurve.copy(trimDb = 1.0), null).settings.audioEqProfileId)
    }

    @Test fun aUserProfileFollowsItsEditsAndIsSavedOn() {
        val settings = CameraSettings().withEqProfile(voice)
        val curve = settings.audioEqCurve.withAddedPoint(bellAt2k)!!
        val edit = settings.withEditedEqCurve(curve, listOf(voice))
        assertEquals(voice.id, edit.settings.audioEqProfileId)
        assertEquals(curve, edit.settings.audioEqCurve)
        assertEquals(voice.copy(curve = curve), edit.save)
        // A bypassed edit saves the profile switched on: the bypass belongs to the settings.
        val off = edit.settings.withEditedEqCurve(curve.copy(enabled = false), listOf(voice.copy(curve = curve)))
        assertNull(off.save)
        val offEdit = off.settings.withEditedEqCurve(curve.copy(enabled = false, trimDb = -2.0), listOf(voice.copy(curve = curve)))
        assertTrue(offEdit.save!!.curve.enabled)
        assertEquals(-2.0, offEdit.save!!.curve.trimDb, 0.0)
    }

    @Test fun aMissingUserProfileDetachesAndAnUnreadStoreKeepsTheLink() {
        val settings = CameraSettings().withEqProfile(voice)
        val curve = settings.audioEqCurve.copy(trimDb = 3.0)
        val missing = settings.withEditedEqCurve(curve, emptyList())
        assertNull(missing.settings.audioEqProfileId)
        assertNull(missing.save)
        val unread = settings.withEditedEqCurve(curve, null)
        assertEquals(voice.id, unread.settings.audioEqProfileId)
        assertNull(unread.save)
        // With no profile the curve just changes.
        val free = CameraSettings().withEditedEqCurve(curve, listOf(voice))
        assertEquals(EqEdit(CameraSettings(audioEqCurve = curve)), free)
    }

    @Test fun pickSaveDeleteAndReset() {
        val bypassed = CameraSettings(audioEqCurve = EqCurve(enabled = false))
        val picked = bypassed.withEqProfile(voice.copy(curve = voice.curve.copy(enabled = false)))
        assertEquals(voice.id, picked.audioEqProfileId)
        assertTrue(picked.audioEqCurve.enabled)
        val flat = picked.withEqProfile(null)
        assertEquals(EqCurve(), flat.audioEqCurve)
        assertNull(flat.audioEqProfileId)
        val saved = CameraSettings(audioEqCurve = voice.curve.copy(enabled = false)).withSavedEqProfile(voice, null)
        assertEquals(voice.id, saved.audioEqProfileId)
        assertFalse(saved.audioEqCurve.enabled)
        // A profile picked, or the curve edited, while Save as was storing keeps the newer choice.
        val repicked = CameraSettings().withEqProfile(reference)
        assertSame(repicked, repicked.withSavedEqProfile(voice, null))
        val edited = CameraSettings(audioEqCurve = voice.curve.copy(trimDb = voice.curve.trimDb + 1.0))
        assertSame(edited, edited.withSavedEqProfile(voice, null))
        assertTrue(voice.curve.copy(enabled = false).forProfile().enabled)
        assertEquals(picked.copy(audioEqProfileId = null), picked.withDeletedEqProfile(voice.id))
        assertSame(picked, picked.withDeletedEqProfile("u-other"))
        assertEquals(reference.id, picked.withResetEq(reference).audioEqProfileId)
        assertEquals(reference.curve, picked.withResetEq(reference).audioEqCurve)
        assertEquals(flat, picked.withResetEq(null))
    }

    @Test fun profileNamesAreOneToSixtyFourReadableCharacters() {
        assertEquals(EqNameProblem.EMPTY, eqProfileNameProblem(""))
        assertEquals(EqNameProblem.EMPTY, eqProfileNameProblem("   "))
        assertNull(eqProfileNameProblem("  Lavalier  "))
        assertNull(eqProfileNameProblem("x".repeat(64)))
        assertEquals(EqNameProblem.TOO_LONG, eqProfileNameProblem("x".repeat(65)))
        assertEquals(EqNameProblem.UNREADABLE, eqProfileNameProblem("Voice\u0007"))
        assertNull(eqProfileNameProblem("Entrevista ñandú 🎤"))
    }

    @Test fun theSettingsRowSummary() {
        assertEquals(InputEqSummary.Off, inputEqSummary(voice.id, voice.curve.copy(enabled = false), "Voice"))
        assertEquals(InputEqSummary.Reference, inputEqSummary(reference.id, reference.curve, null))
        assertEquals(InputEqSummary.User("Voice"), inputEqSummary(voice.id, voice.curve, "Voice"))
        assertEquals(InputEqSummary.Custom, inputEqSummary(voice.id, voice.curve, null))
        assertEquals(InputEqSummary.Custom, inputEqSummary(null, voice.curve, null))
        assertEquals(InputEqSummary.Flat, inputEqSummary(null, EqCurve(), null))
    }

    @Test fun logScalesRoundTrip() {
        assertEquals(0f, frequencyFraction(20.0), 1e-6f)
        assertEquals(1f, frequencyFraction(20_000.0), 1e-6f)
        assertEquals(0.5f, frequencyFraction(632.4555), 1e-4f)
        assertEquals(1_000.0, fractionFrequency(frequencyFraction(1_000.0)), 1e-3)
        assertEquals(20.0, fractionFrequency(-1f), 1e-9)
        assertEquals(0f, qFraction(0.1), 1e-6f)
        assertEquals(0.5f, qFraction(1.0), 1e-6f)
        assertEquals(10.0, fractionQ(1f), 1e-9)
    }

    @Test fun roundingKeepsReadoutsAndValuesInStep() {
        assertEquals(1_250.0, roundEqFrequency(1_253.7), 0.0)
        assertEquals(63.5, roundEqFrequency(63.49), 0.0)
        assertEquals(20_000.0, roundEqFrequency(20_400.0), 0.0)
        assertEquals(-9.5, roundEqGain(-9.46), 0.0)
        assertEquals("0.0", roundEqGain(-0.04).toString())
        assertEquals(18.0, roundEqGain(30.0), 0.0)
        assertEquals(1.1, roundEqQ(1.149), 0.0)
        assertEquals(0.1, roundEqQ(0.01), 0.0)
    }

    @Test fun readouts() {
        assertEquals("63.5 Hz", formatEqFrequency(63.5, Locale.US))
        assertEquals("120 Hz", formatEqFrequency(120.0, Locale.US))
        assertEquals("1.25 kHz", formatEqFrequency(1_253.0, Locale.US))
        assertEquals("10 kHz", formatEqFrequency(10_000.0, Locale.US))
        assertEquals("1 kHz", formatEqFrequency(999.9, Locale.US))
        assertEquals("1,25 kHz", formatEqFrequency(1_250.0, Locale.GERMANY))
        assertEquals("+3.0 dB", formatEqGain(3.0, Locale.US))
        assertEquals("-9.5 dB", formatEqGain(-9.5, Locale.US))
        assertEquals("0.0 dB", formatEqGain(-0.01, Locale.US))
        assertEquals("-9,5 dB", formatEqGain(-9.5, Locale.GERMANY))
        assertEquals("0.70", formatEqQ(0.7, Locale.US))
        assertEquals("10.00", formatEqQ(10.0, Locale.US))
    }

    @Test fun graphGeometryMapsBothAxes() {
        val geometry = EqGraphGeometry(left = 10f, top = 20f, width = 300f, height = 180f)
        assertEquals(10f, geometry.x(20.0), 1e-3f)
        assertEquals(310f, geometry.x(20_000.0), 1e-3f)
        assertEquals(20f, geometry.y(18.0), 1e-3f)
        assertEquals(110f, geometry.y(0.0), 1e-3f)
        assertEquals(200f, geometry.y(-18.0), 1e-3f)
        assertEquals(200f, geometry.y(-40.0), 1e-3f)
        assertEquals(6.0, geometry.gain(geometry.y(6.0)), 1e-4)
        assertEquals(1_000.0, geometry.frequency(geometry.x(1_000.0)), 1e-3)
        assertEquals(20f, geometry.spectrumY(0f), 1e-3f)
        assertEquals(200f, geometry.spectrumY(-120f), 1e-3f)
        assertEquals(110f, geometry.spectrumY(-45f), 1e-3f)
        // A type with no gain sits on the 0 dB line whatever gain it keeps.
        assertEquals(110f, geometry.nodeY(EqPoint(EqFilterType.HIGH_PASS, 80.0, 12.0, 0.7)), 1e-3f)
    }

    @Test fun hitTestingPicksTheNearestNodeWithinReach() {
        val geometry = EqGraphGeometry(0f, 0f, 300f, 180f)
        val points = listOf(bellAt2k, EqPoint(EqFilterType.BELL, 2_000.0, 3.0, 2.0), EqPoint(EqFilterType.BELL, 100.0, -6.0, 1.0))
        val x = geometry.nodeX(bellAt2k)
        val y = geometry.nodeY(bellAt2k)
        // Two nodes on the same spot: the later one, drawn on top, wins.
        assertEquals(1, geometry.nodeAt(points, x + 3f, y, radius = 20f))
        assertEquals(2, geometry.nodeAt(points, geometry.nodeX(points[2]) - 10f, geometry.nodeY(points[2]) + 10f, radius = 20f))
        assertNull(geometry.nodeAt(points, x + 30f, y, radius = 20f))
        assertNull(geometry.nodeAt(emptyList(), x, y, radius = 20f))
    }

    @Test fun aMovedNodeIsRoundedAndKeepsGainOnlyWhereItHasOne() {
        val geometry = EqGraphGeometry(0f, 0f, 300f, 180f)
        val moved = geometry.moved(bellAt2k, geometry.x(503.7), geometry.y(-4.26))
        assertEquals(504.0, moved.frequencyHz, 0.0)
        assertEquals(-4.3, moved.gainDb, 0.0)
        assertEquals(bellAt2k.q, moved.q, 0.0)
        val highPass = EqPoint(EqFilterType.HIGH_PASS, 80.0, 0.0, 0.7)
        val dragged = geometry.moved(highPass, geometry.x(120.0), geometry.y(9.0))
        assertEquals(120.0, dragged.frequencyHz, 0.0)
        assertEquals(0.0, dragged.gainDb, 0.0)
        // Off the plot clamps to the ends.
        assertEquals(20_000.0, geometry.moved(bellAt2k, 900f, -50f).frequencyHz, 0.0)
        assertEquals(18.0, geometry.moved(bellAt2k, 900f, -50f).gainDb, 0.0)
    }

    private fun spectrum(output: List<Float>, bands: List<Float> = listOf(100f, 1_000f, 10_000f)) =
        AudioSpectrum(bands, List(bands.size) { -60f }, output, capturedAtElapsedRealtimeMs = 0L)

    @Test fun peaksHoldTheLoudestLevelPerBandAndStartOverOnANewBandLayout() {
        val first = null.including(spectrum(listOf(-40f, -30f, -50f)))
        assertEquals(listOf(-40f, -30f, -50f), first.peakDb)
        val second = first.including(spectrum(listOf(-45f, -20f, -50f)))
        assertEquals(listOf(-40f, -20f, -50f), second.peakDb)
        assertEquals(listOf(100f, 1_000f, 10_000f), second.bandCentresHz)
        // Another band layout: the old peaks belong to other frequencies.
        val moved = second.including(spectrum(listOf(-70f, -70f), bands = listOf(200f, 2_000f)))
        assertEquals(EqPeakHold(listOf(200f, 2_000f), listOf(-70f, -70f)), moved)
        // An output shorter than the bands keeps only the bands it has.
        assertEquals(listOf(-10f, -10f), null.including(spectrum(listOf(-10f, -10f))).peakDb)
    }

    @Test fun clipNoticeNamesWhichSideClipped() {
        assertNull(eqClipNotice(inputClipped = false, recordedClipped = false))
        assertEquals(EqClipNotice.INPUT, eqClipNotice(inputClipped = true, recordedClipped = false))
        assertEquals(EqClipNotice.RECORDED, eqClipNotice(inputClipped = false, recordedClipped = true))
        assertEquals(EqClipNotice.BOTH, eqClipNotice(inputClipped = true, recordedClipped = true))
    }

    @Test fun theSnippetPausesALiveRoute() {
        val usb = AudioListeningDevice(7, "USB-C Headset", AudioListeningOutput.WIRED_USB)
        val on = CameraSettings().withListenStart(usb)
        fun status(phase: AudioListeningPhase, playing: Boolean) =
            listenStatus(eqListenUiState(on, AudioListeningStatus(phase, requestedDeviceId = 7), listOf(usb), snippetPlaying = playing))
        // The snippet rests the microphone, so a live route has nothing to play meanwhile.
        assertEquals(ListenStatus.PAUSED, status(AudioListeningPhase.ACTIVE, playing = true))
        assertEquals(ListenStatus.PAUSED, status(AudioListeningPhase.CONNECTING, playing = true))
        assertEquals(ListenStatus.ACTIVE, status(AudioListeningPhase.ACTIVE, playing = false))
        // Only a live route rests: a lost one still says what happened.
        assertEquals(ListenStatus.FAILED, status(AudioListeningPhase.FAILED, playing = true))
    }

    @Test fun snippetStateMapsFromTheServiceAndPicksItsView() {
        val idle = eqSnippetUiState(EqAuditionState())
        assertEquals(EqSnippetUiState(), idle)
        assertEquals(EqSnippetView.EMPTY, eqSnippetView(idle))
        val recording = eqSnippetUiState(EqAuditionState(EqAuditionPhase.RECORDING, snippetMs = 6_000, recordedMs = 0))
        assertEquals(EqSnippetView.RECORDING, eqSnippetView(recording))
        assertEquals(EqSnippetView.READY, eqSnippetView(eqSnippetUiState(EqAuditionState(snippetMs = 7_000))))
        val playing = eqSnippetUiState(EqAuditionState(EqAuditionPhase.PLAYING, snippetMs = 7_000, failed = true))
        assertEquals(EqSnippetView.PLAYING, eqSnippetView(playing))
        assertTrue(playing.failed)
        assertEquals(10_000, playing.maxMs)
    }

    @Test fun snippetSecondsAreWholeAndBounded() {
        val recording = EqSnippetUiState(EqSnippetPhase.RECORDING, recordedMs = 3_999)
        assertEquals(3, eqSnippetRecordedSeconds(recording))
        assertEquals(0, eqSnippetRecordedSeconds(recording.copy(recordedMs = 400)))
        assertEquals(10, eqSnippetRecordedSeconds(recording.copy(recordedMs = 10_200)))
        assertEquals(10, eqSnippetMaxSeconds(recording))
        assertEquals(7, eqSnippetSeconds(EqSnippetUiState(snippetMs = 6_600)))
        assertEquals(10, eqSnippetSeconds(EqSnippetUiState(snippetMs = 10_000)))
        assertEquals(1, eqSnippetSeconds(EqSnippetUiState(snippetMs = 500)))
    }

    @Test fun peaksStartOverOnlyWhenPlaybackStartsOrStops() {
        assertTrue(eqSnippetSourceChanged(EqSnippetPhase.IDLE, EqSnippetPhase.PLAYING))
        assertTrue(eqSnippetSourceChanged(EqSnippetPhase.PLAYING, EqSnippetPhase.IDLE))
        assertTrue(eqSnippetSourceChanged(EqSnippetPhase.PLAYING, EqSnippetPhase.RECORDING))
        assertFalse(eqSnippetSourceChanged(EqSnippetPhase.IDLE, EqSnippetPhase.RECORDING))
        assertFalse(eqSnippetSourceChanged(EqSnippetPhase.RECORDING, EqSnippetPhase.IDLE))
        assertFalse(eqSnippetSourceChanged(EqSnippetPhase.PLAYING, EqSnippetPhase.PLAYING))
    }
}
