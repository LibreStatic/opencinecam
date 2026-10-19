/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */
package com.librestatic.opencinecam.service

/** The separate WAV/FLAC file a finished take wrote beside its video. */
internal data class SavedSidecarAudio(val bitDepthBits: Int, val container: String, val sampleRateHz: Int)

/**
 * The audio part of the "saved" message: the sidecar file when there is one, else the in-file AAC
 * track named by [activeLabel], else nothing (a silent take, e.g. time-lapse).
 */
internal fun savedAudioSuffix(sidecar: SavedSidecarAudio?, activeLabel: String): String =
    sidecar?.let { " + ${it.bitDepthBits}-bit ${it.container} ${it.sampleRateHz / 1000.0} kHz" }
        ?: if (activeLabel.startsWith("AAC")) " + $activeLabel" else ""
