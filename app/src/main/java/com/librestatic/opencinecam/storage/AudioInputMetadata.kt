/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.storage

import com.librestatic.opencinecam.media.audio.AudioInputKey
import com.librestatic.opencinecam.media.audio.AudioInputRouteGuard
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
