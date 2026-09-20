/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import com.librestatic.opencinecam.camera.DigitalRecordingGain
import org.json.JSONObject

/** Stored only with a completed PCM-producing route, never a claim about analog input gain. */
internal fun recordingGainJson(gain: DigitalRecordingGain): JSONObject = JSONObject()
    .put("manualEnabled", gain.enabled)
    .put("appliedDigitalDb", if (gain.enabled) gain.decibels else 0)
    .put("stage", "RECORDING_PCM_BEFORE_ENCODING")
    .put("analogInputGain", JSONObject.NULL)
    .put("listeningVolume", JSONObject.NULL)
    .put("agcExcludedByManual", gain.enabled)
    .put("integerOverflow", "SATURATE")
    .put("floatHeadroom", "PRESERVED_FINITE")
