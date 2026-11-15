/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import com.librestatic.opencinecam.camera.AudioSpectrum
import com.librestatic.opencinecam.camera.EqCurve
import com.librestatic.opencinecam.camera.EqFilterType
import com.librestatic.opencinecam.camera.EqPoint
import com.librestatic.opencinecam.camera.EqProfile
import com.librestatic.opencinecam.camera.coerced
import com.librestatic.opencinecam.camera.isBundledEqProfileId
import com.librestatic.opencinecam.service.EQ_SNIPPET_MAX_MS
import com.librestatic.opencinecam.service.EqAuditionPhase
import com.librestatic.opencinecam.service.EqAuditionState
import java.math.BigDecimal
import java.math.MathContext
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToLong

/*
 * The input equaliser editor's pure logic (PLAN-074 decisions 7, 8 and 13): the simple mode's shape, what the active
 * profile is, how an edit, a pick, a save, a delete and a reset change the settings, the graph's coordinates, the
 * readouts, the held peaks and the clip line, and when the listen card rests. The page (InputEqualizerScreen.kt) only draws and wires these.
 */

/** The editor's two faces of the same engine: three gain sliders, or the free list of points. */
internal enum class EqEditorMode { SIMPLE, FULL }

/** The width of the simple mode's shelves: 1/sqrt(2), the cookbook's steepest slope without a bump. */
private const val SIMPLE_SHELF_Q = 0.71

/** The simple mode's three fixed points (decision 7); only their gains move. */
internal enum class SimpleEqBand(val type: EqFilterType, val frequencyHz: Double, val q: Double) {
    LOW(EqFilterType.LOW_SHELF, 120.0, SIMPLE_SHELF_Q),
    MID(EqFilterType.BELL, 1_000.0, 0.7),
    HIGH(EqFilterType.HIGH_SHELF, 8_000.0, SIMPLE_SHELF_Q),
}

private const val SHAPE_TOLERANCE = 1e-6

/** True when [curve] is exactly the simple mode's three points, in order and all on, whatever their gains. */
internal fun isSimpleShape(curve: EqCurve): Boolean =
    curve.points.size == SimpleEqBand.entries.size && curve.points.zip(SimpleEqBand.entries).all { (point, band) ->
        point.enabled && point.type == band.type && abs(point.frequencyHz - band.frequencyHz) < SHAPE_TOLERANCE &&
            abs(point.q - band.q) < SHAPE_TOLERANCE
    }

/** Simple mode is offered on an empty curve, which it fills, or on its own shape; any other curve needs the full editor. */
internal fun simpleModeOffered(curve: EqCurve): Boolean = curve.points.isEmpty() || isSimpleShape(curve)

/** The mode the page opens in: simple for its own shape or an empty curve, full for anything else. */
internal fun initialEditorMode(curve: EqCurve): EqEditorMode = if (simpleModeOffered(curve)) EqEditorMode.SIMPLE else EqEditorMode.FULL

/** The simple mode's three points at 0 dB, which change nothing until a gain moves. */
internal fun simpleEqPoints(): List<EqPoint> = SimpleEqBand.entries.map { EqPoint(it.type, it.frequencyHz, 0.0, it.q) }

/** The gain of [band] in a simple-shaped curve; 0 dB on an empty one. */
internal fun EqCurve.simpleGainDb(band: SimpleEqBand): Double = if (isSimpleShape(this)) points[band.ordinal].gainDb else 0.0

/** Sets [band]'s gain, putting the three points in first when the curve is empty. */
internal fun EqCurve.withSimpleGain(band: SimpleEqBand, gainDb: Double): EqCurve {
    val base = if (isSimpleShape(this)) points else simpleEqPoints()
    return copy(points = base.mapIndexed { index, point -> if (index == band.ordinal) point.coerced(gainDb = gainDb) else point })
}

/** The gain types; high-pass, low-pass and notch keep a gain only so a type change keeps a valid point. */
internal val EqFilterType.hasGain: Boolean
    get() = this == EqFilterType.BELL || this == EqFilterType.LOW_SHELF || this == EqFilterType.HIGH_SHELF

/** The point "Add point" and a tap on the graph start from: a bell of width 1 at 0 dB. */
internal const val NEW_POINT_Q = 1.0
internal const val NEW_POINT_FREQUENCY_HZ = 1_000.0

/** Appends [point], or returns null when the curve already holds [EqCurve.MAX_POINTS]. */
internal fun EqCurve.withAddedPoint(point: EqPoint): EqCurve? =
    if (points.size >= EqCurve.MAX_POINTS) null else copy(points = points + point)

internal fun EqCurve.withPoint(index: Int, point: EqPoint): EqCurve =
    if (index !in points.indices) this else copy(points = points.toMutableList().also { it[index] = point })

internal fun EqCurve.withoutPoint(index: Int): EqCurve =
    if (index !in points.indices) this else copy(points = points.filterIndexed { i, _ -> i != index })

/** Which profile the settings' curve is, as the profile row names it. */
internal sealed interface ActiveEqProfile {
    /** This phone's bundled profile: the reference curve for its raw microphones, read-only. */
    data class Reference(val profile: EqProfile) : ActiveEqProfile

    data class User(val profile: EqProfile) : ActiveEqProfile

    /** A curve with points (or trim) and no profile, or one whose profile is gone: "Custom (not saved)". */
    data object Custom : ActiveEqProfile

    /** No points, no trim and no profile. */
    data object Flat : ActiveEqProfile
}

/**
 * What [profileId] and [curve] read as. A user id is looked up in [userProfiles]; one that is not there (deleted, or
 * from another install) reads as custom, and so does a bundled id that is not this phone's [reference].
 */
internal fun activeEqProfile(profileId: String?, curve: EqCurve, reference: EqProfile?, userProfiles: List<EqProfile>): ActiveEqProfile = when {
    profileId != null && profileId == reference?.id -> ActiveEqProfile.Reference(reference)
    profileId != null && !isBundledEqProfileId(profileId) ->
        userProfiles.firstOrNull { it.id == profileId }?.let { ActiveEqProfile.User(it) } ?: ActiveEqProfile.Custom
    profileId == null && curve.points.isEmpty() && curve.trimDb == 0.0 -> ActiveEqProfile.Flat
    else -> ActiveEqProfile.Custom
}

/** An edit applied: the settings to set, and the user profile to write to the store (coalesced) when there is one. */
internal data class EqEdit(val settings: CameraSettings, val save: EqProfile? = null)

/**
 * Applies an edited [curve] to the settings (decision 13):
 * - with no profile the curve just changes;
 * - a bundled profile is read-only, so its bypass is the only edit that keeps it; any other detaches the curve
 *   ("Custom (not saved)");
 * - a user profile follows the edit and is saved. The bypass is the settings' own, never the profile's, so a
 *   bypass alone saves nothing and a saved profile is always on.
 * [userProfiles] null means the store has not been read yet: the link is kept and nothing is saved.
 */
internal fun CameraSettings.withEditedEqCurve(curve: EqCurve, userProfiles: List<EqProfile>?): EqEdit {
    val id = audioEqProfileId ?: return EqEdit(copy(audioEqCurve = curve))
    val bypassOnly = curve == audioEqCurve.copy(enabled = curve.enabled)
    if (isBundledEqProfileId(id)) {
        return EqEdit(if (bypassOnly) copy(audioEqCurve = curve) else copy(audioEqCurve = curve, audioEqProfileId = null))
    }
    if (userProfiles == null) return EqEdit(copy(audioEqCurve = curve))
    val profile = userProfiles.firstOrNull { it.id == id } ?: return EqEdit(copy(audioEqCurve = curve, audioEqProfileId = null))
    return EqEdit(copy(audioEqCurve = curve), if (bypassOnly) null else profile.copy(curve = curve.copy(enabled = true)))
}

/** Picking a profile copies its curve, switched on; null is Flat: an empty curve and no profile. */
internal fun CameraSettings.withEqProfile(profile: EqProfile?): CameraSettings =
    if (profile == null) copy(audioEqCurve = EqCurve(), audioEqProfileId = null)
    else copy(audioEqCurve = profile.curve.copy(enabled = true), audioEqProfileId = profile.id)

/** Save as made [created] from the current curve: it becomes the active profile; the curve, bypass included, stays. */
internal fun CameraSettings.withSavedEqProfile(created: EqProfile): CameraSettings = copy(audioEqProfileId = created.id)

/** Deleting the active profile keeps its curve in use as a custom one; deleting another changes nothing. */
internal fun CameraSettings.withDeletedEqProfile(id: String): CameraSettings =
    if (audioEqProfileId == id) copy(audioEqProfileId = null) else this

/** Reset goes back to the phone's reference profile where one ships, else to flat with no profile. */
internal fun CameraSettings.withResetEq(reference: EqProfile?): CameraSettings = withEqProfile(reference)

/** The curve a profile is saved with: the bypass stays in the settings. */
internal fun EqCurve.forProfile(): EqCurve = copy(enabled = true)

internal enum class EqNameProblem { EMPTY, TOO_LONG, UNREADABLE }

/** Why [raw] cannot name a profile once trimmed, or null when it can: 1 to 64 characters, none of them control codes. */
internal fun eqProfileNameProblem(raw: String): EqNameProblem? {
    val name = raw.trim()
    return when {
        name.isEmpty() -> EqNameProblem.EMPTY
        name.length > EqProfile.MAX_NAME_LENGTH -> EqNameProblem.TOO_LONG
        name.any(Char::isISOControl) -> EqNameProblem.UNREADABLE
        else -> null
    }
}

/** What the Settings row says about the equaliser. */
internal sealed interface InputEqSummary {
    data object Off : InputEqSummary
    data object Reference : InputEqSummary
    data class User(val name: String) : InputEqSummary
    data object Custom : InputEqSummary
    data object Flat : InputEqSummary
}

/**
 * The Settings row's summary: "Off" when bypassed, else the profile. [userProfileName] is the stored name of the
 * active user profile, null when it is not known (not read yet, or deleted), which reads as custom.
 */
internal fun inputEqSummary(profileId: String?, curve: EqCurve, userProfileName: String?): InputEqSummary = when {
    !curve.enabled -> InputEqSummary.Off
    isBundledEqProfileId(profileId) -> InputEqSummary.Reference
    profileId != null && userProfileName != null -> InputEqSummary.User(userProfileName)
    profileId == null && curve.points.isEmpty() && curve.trimDb == 0.0 -> InputEqSummary.Flat
    else -> InputEqSummary.Custom
}

private val FREQUENCY_DECADES = ln(EqPoint.MAX_FREQUENCY_HZ / EqPoint.MIN_FREQUENCY_HZ)
private val Q_DECADES = ln(EqPoint.MAX_Q / EqPoint.MIN_Q)

/** 20 Hz to 20 kHz on a log scale as 0 to 1, for the graph's x axis and the frequency slider. */
internal fun frequencyFraction(frequencyHz: Double): Float =
    (ln(frequencyHz.coerceIn(EqPoint.FREQUENCY_RANGE_HZ) / EqPoint.MIN_FREQUENCY_HZ) / FREQUENCY_DECADES).toFloat()

internal fun fractionFrequency(fraction: Float): Double =
    EqPoint.MIN_FREQUENCY_HZ * (EqPoint.MAX_FREQUENCY_HZ / EqPoint.MIN_FREQUENCY_HZ).pow(fraction.toDouble().coerceIn(0.0, 1.0))

/** Q 0.1 to 10 on a log scale as 0 to 1, for the width slider. */
internal fun qFraction(q: Double): Float = (ln(q.coerceIn(EqPoint.Q_RANGE) / EqPoint.MIN_Q) / Q_DECADES).toFloat()

internal fun fractionQ(fraction: Float): Double =
    EqPoint.MIN_Q * (EqPoint.MAX_Q / EqPoint.MIN_Q).pow(fraction.toDouble().coerceIn(0.0, 1.0))

private fun significant(value: Double, digits: Int): Double = BigDecimal(value).round(MathContext(digits)).toDouble()

/** A dragged frequency kept to three significant digits, so the readout and the stored value agree. */
internal fun roundEqFrequency(frequencyHz: Double): Double = significant(frequencyHz, 3).coerceIn(EqPoint.FREQUENCY_RANGE_HZ)

/** A dragged gain kept to 0.1 dB. */
internal fun roundEqGain(gainDb: Double): Double = ((gainDb * 10).roundToLong() / 10.0).coerceIn(EqPoint.GAIN_RANGE_DB) + 0.0

/** A dragged Q kept to two significant digits. */
internal fun roundEqQ(q: Double): Double = significant(q, 2).coerceIn(EqPoint.Q_RANGE)

/** "63.5 Hz", "120 Hz", "1.25 kHz", "10 kHz": three significant digits, kHz from 1 kHz up. */
internal fun formatEqFrequency(frequencyHz: Double, locale: Locale = Locale.getDefault()): String {
    val rounded = significant(frequencyHz, 3)
    val format = NumberFormat.getNumberInstance(locale).apply { isGroupingUsed = false; minimumFractionDigits = 0 }
    return if (rounded < 1_000.0) format.apply { maximumFractionDigits = 1 }.format(rounded) + " Hz"
    else format.apply { maximumFractionDigits = 2 }.format(rounded / 1_000.0) + " kHz"
}

/** "+3.0 dB", "-9.5 dB", "0.0 dB": one decimal, signed except at zero. */
internal fun formatEqGain(gainDb: Double, locale: Locale = Locale.getDefault()): String {
    val tenths = (gainDb * 10).roundToLong()
    val text = String.format(locale, "%.1f", abs(tenths) / 10.0)
    return when {
        tenths > 0 -> "+$text dB"
        tenths < 0 -> "-$text dB"
        else -> "$text dB"
    }
}

/** "0.70", "1.10", "10.00". */
internal fun formatEqQ(q: Double, locale: Locale = Locale.getDefault()): String = String.format(locale, "%.2f", q)

/** The response the graph draws and the curve's sample rate for it; the read loops run at 48 kHz. */
internal const val EQ_GRAPH_SAMPLE_RATE_HZ = 48_000

/** The spectrum's floor on the graph: -90 dBFS sits on the bottom edge, 0 dBFS on the top. */
internal const val EQ_SPECTRUM_FLOOR_DB = -90f

/**
 * The graph's plot rectangle in pixels and its two axes: frequency on a log scale across, gain from +18 dB at the
 * top to -18 dB at the bottom. Nodes sit at their frequency and gain; a type without gain sits on the 0 dB line.
 */
internal class EqGraphGeometry(val left: Float, val top: Float, val width: Float, val height: Float) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height

    fun x(frequencyHz: Double): Float = left + width * frequencyFraction(frequencyHz)

    fun frequency(x: Float): Double = fractionFrequency(if (width > 0f) (x - left) / width else 0f)

    fun y(gainDb: Double): Float =
        top + height * ((EqPoint.MAX_GAIN_DB - gainDb.coerceIn(EqPoint.GAIN_RANGE_DB)) / (EqPoint.MAX_GAIN_DB - EqPoint.MIN_GAIN_DB)).toFloat()

    fun gain(y: Float): Double =
        (EqPoint.MAX_GAIN_DB - (if (height > 0f) (y - top) / height else 0.5f) * (EqPoint.MAX_GAIN_DB - EqPoint.MIN_GAIN_DB))
            .coerceIn(EqPoint.GAIN_RANGE_DB)

    /** A spectrum level in dBFS: 0 at the top, [EQ_SPECTRUM_FLOOR_DB] and below on the bottom. */
    fun spectrumY(levelDb: Float): Float = top + height * (levelDb.coerceIn(EQ_SPECTRUM_FLOOR_DB, 0f) / EQ_SPECTRUM_FLOOR_DB)

    fun nodeX(point: EqPoint): Float = x(point.frequencyHz)

    fun nodeY(point: EqPoint): Float = y(if (point.type.hasGain) point.gainDb else 0.0)

    /** The node nearest ([x], [y]) within [radius], or null. On a tie the later point, drawn on top, wins. */
    fun nodeAt(points: List<EqPoint>, x: Float, y: Float, radius: Float): Int? {
        var best: Int? = null
        var bestDistance = Float.MAX_VALUE
        points.forEachIndexed { index, point ->
            val distance = hypot(nodeX(point) - x, nodeY(point) - y)
            if (distance <= radius && distance <= bestDistance) {
                best = index
                bestDistance = distance
            }
        }
        return best
    }

    /** [point] moved to ([x], [y]): frequency always, gain only for a type that has one; rounded like the readouts. */
    fun moved(point: EqPoint, x: Float, y: Float): EqPoint = point.coerced(
        frequencyHz = roundEqFrequency(frequency(x)),
        gainDb = if (point.type.hasGain) roundEqGain(gain(y)) else point.gainDb,
    )
}

/** The highest level each band of the "after" layer reached since the last reset, on the bands it was measured on. */
internal data class EqPeakHold(val bandCentresHz: List<Float>, val peakDb: List<Float>)

/**
 * [spectrum] folded into the held peaks: per band the louder of the held and the new level. A new band layout (another
 * sample rate, or the first snapshot) starts over from [spectrum], since the old peaks belong to other frequencies.
 */
internal fun EqPeakHold?.including(spectrum: AudioSpectrum): EqPeakHold {
    val bands = spectrum.bandCentresHz
    val levels = spectrum.outputDb.take(bands.size)
    if (this == null || bandCentresHz != bands || peakDb.size != levels.size) return EqPeakHold(bands, levels)
    return EqPeakHold(bands, List(levels.size) { maxOf(peakDb[it], levels[it]) })
}

/** What the clip line under the graph says, if anything. */
internal enum class EqClipNotice { INPUT, RECORDED, BOTH }

/** The clip line for the service's latches: the microphone before the equaliser, the recorded signal after it. */
internal fun eqClipNotice(inputClipped: Boolean, recordedClipped: Boolean): EqClipNotice? = when {
    inputClipped && recordedClipped -> EqClipNotice.BOTH
    inputClipped -> EqClipNotice.INPUT
    recordedClipped -> EqClipNotice.RECORDED
    else -> null
}

/**
 * The listen card's state: the shared listen controls' (AudioListenModel.kt), resting while the snippet loops, since
 * the microphone then rests and live listening has nothing to play.
 */
internal fun eqListenUiState(
    settings: CameraSettings,
    status: AudioListeningStatus,
    outputs: List<AudioListeningDevice>,
    snippetPlaying: Boolean = false,
): ListenUiState = listenUiState(settings, status, outputs, paused = snippetPlaying)

/** The snippet audition as the page shows it: what the service is doing and how long the kept snippet is. */
internal enum class EqSnippetPhase { IDLE, RECORDING, PLAYING }

internal data class EqSnippetUiState(
    val phase: EqSnippetPhase = EqSnippetPhase.IDLE,
    /** The kept snippet's length; 0 when there is none. */
    val snippetMs: Int = 0,
    /** While recording, what has been captured so far. */
    val recordedMs: Int = 0,
    val maxMs: Int = EQ_SNIPPET_MAX_MS,
    /** The last record or play request failed. */
    val failed: Boolean = false,
)

internal fun eqSnippetUiState(audition: EqAuditionState): EqSnippetUiState = EqSnippetUiState(
    phase = when (audition.phase) {
        EqAuditionPhase.IDLE -> EqSnippetPhase.IDLE
        EqAuditionPhase.RECORDING -> EqSnippetPhase.RECORDING
        EqAuditionPhase.PLAYING -> EqSnippetPhase.PLAYING
    },
    snippetMs = audition.snippetMs,
    recordedMs = audition.recordedMs,
    maxMs = audition.maxMs,
    failed = audition.failed,
)

/** What the snippet section offers. */
internal enum class EqSnippetView {
    /** Nothing kept: Record snippet. */
    EMPTY,

    /** Recording: the progress and Stop. */
    RECORDING,

    /** A snippet kept: Play loop, Record again and Discard. */
    READY,

    /** Looping through the curve: the note that the microphone is paused, and Stop. */
    PLAYING,
}

internal fun eqSnippetView(state: EqSnippetUiState): EqSnippetView = when (state.phase) {
    EqSnippetPhase.RECORDING -> EqSnippetView.RECORDING
    EqSnippetPhase.PLAYING -> EqSnippetView.PLAYING
    EqSnippetPhase.IDLE -> if (state.snippetMs > 0) EqSnippetView.READY else EqSnippetView.EMPTY
}

/**
 * Whole seconds recorded so far, rounded down (0 s until a full second is in), so the polite live region announces
 * once a second rather than at every progress report; never past the cap.
 */
internal fun eqSnippetRecordedSeconds(state: EqSnippetUiState): Int = (state.recordedMs.coerceIn(0, state.maxMs)) / 1_000

/** The cap in whole seconds, for "of 10 s" and the help line. */
internal fun eqSnippetMaxSeconds(state: EqSnippetUiState): Int = state.maxMs / 1_000

/** The kept snippet's length for "Play loop (7 s)": rounded, and at least 1 s for the shortest kept snippet. */
internal fun eqSnippetSeconds(state: EqSnippetUiState): Int = ((state.snippetMs + 500) / 1_000).coerceAtLeast(1)

/** The source of the spectrum changes when playback starts or stops, so the held peaks start over then. */
internal fun eqSnippetSourceChanged(previous: EqSnippetPhase, next: EqSnippetPhase): Boolean =
    (previous == EqSnippetPhase.PLAYING) != (next == EqSnippetPhase.PLAYING)
