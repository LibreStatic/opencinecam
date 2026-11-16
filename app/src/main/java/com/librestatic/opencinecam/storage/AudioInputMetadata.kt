/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.storage

import com.librestatic.opencinecam.camera.PcmInputEvidence
import com.librestatic.opencinecam.camera.toJson
import com.librestatic.opencinecam.media.audio.AudioInputKey
import com.librestatic.opencinecam.media.audio.AudioInputRouteGuard
import com.librestatic.opencinecam.media.audio.PcmInputPath
import org.json.JSONArray
import org.json.JSONObject

/** Adds the requested input and every observed route change to a `.audio.json` sidecar (OCC-AUDIO-012). */
internal fun JSONObject.putAudioInputRoute(requested: AudioInputKey?, guard: AudioInputRouteGuard?): JSONObject {
    put("requestedInput", requested?.let { key ->
        JSONObject().put("type", key.type).put("productName", key.productName).put("address", key.address)
    } ?: JSONObject.NULL)
    put("routeChanges", JSONArray().apply {
        guard?.routeChanges()?.forEach { change ->
            put(JSONObject()
                .put("elapsedRealtimeMs", change.elapsedRealtimeMs)
                .put("deviceId", change.deviceId ?: JSONObject.NULL)
                .put("type", change.type ?: JSONObject.NULL)
                .put("productName", change.label ?: JSONObject.NULL)
                .put("reason", change.reason))
        }
    })
    return this
}

/**
 * Adds what the take's microphone path actually was (PLAN-074 decision 11): the API that delivered
 * the PCM, why it is not the one the source asked for, the bits it delivered and the equaliser curve
 * applied (null when flat), with its profile's id when it has one. Nothing is added when [evidence] is unknown.
 */
internal fun JSONObject.putPcmInputEvidence(evidence: PcmInputEvidence?): JSONObject {
    if (evidence == null) return this
    put("inputPath", evidence.inputPath)
    put("inputFallbackReason", evidence.inputFallbackReason ?: JSONObject.NULL)
    put("captureBitDepth", evidence.captureBitDepth)
    put("equaliser", evidence.equaliser?.let { JSONObject(it.toJson()) } ?: JSONObject.NULL)
    evidence.equaliserProfileId?.let { put("equaliserProfileId", it) }
    return this
}

/** What the take record says about processing it cannot see; the low-latency input has no public effect state. */
internal fun audioInputDisclosure(evidence: PcmInputEvidence?): String =
    if (evidence?.inputPath == PcmInputPath.AAUDIO_LOW_LATENCY.name) {
        "Low-latency AAudio input without platform effects; OEM signal processing outside public APIs may still exist."
    } else "Public AudioRecord state; OEM signal processing outside public APIs may still exist."
