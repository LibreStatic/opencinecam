/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */
package com.librestatic.opencinecam.service

import java.text.NumberFormat
import java.util.Locale

/** The separate WAV/FLAC file a finished take wrote beside its video. */
internal data class SavedSidecarAudio(val bitDepthBits: Int, val container: String, val sampleRateHz: Int)

/**
 * The audio part of the "saved" message: the sidecar file when there is one, else the in-file AAC
 * track named by [activeLabel], else nothing (a silent take, e.g. time-lapse).
 */
internal fun savedAudioSuffix(sidecar: SavedSidecarAudio?, activeLabel: String, locale: Locale): String =
    sidecar?.let { " + ${it.bitDepthBits}-bit ${it.container} ${kiloHertzText(it.sampleRateHz, locale)} kHz" }
        ?: if (activeLabel.startsWith("AAC")) " + $activeLabel" else ""

/** A sample rate in kHz with one decimal, in the reader's number format ("48.0" or "48,0"). */
internal fun kiloHertzText(sampleRateHz: Int, locale: Locale): String = String.format(locale, "%.1f", sampleRateHz / 1000.0)

/** A ratio such as a zoom factor with at most two decimals and at least one ("2.0", "2,25"). */
internal fun ratioText(value: Float, locale: Locale): String = NumberFormat.getNumberInstance(locale)
    .apply { minimumFractionDigits = 1; maximumFractionDigits = 2; isGroupingUsed = false }.format(value.toDouble())
