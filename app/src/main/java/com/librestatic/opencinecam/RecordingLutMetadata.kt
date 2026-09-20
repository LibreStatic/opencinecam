/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import com.librestatic.opencinecam.camera.BakedLutEvidence
import org.json.JSONArray
import org.json.JSONObject

/** Describes the transformation already present in the pixels; never an instruction to reapply it. */
internal fun recordingLutJson(evidence: BakedLutEvidence): JSONObject = JSONObject()
    .put("baked", evidence.baked).put("reapplyInEditor", false)
    .put("selectionId", evidence.selectionId).put("originalCubeSha256", evidence.hash)
    .put("kind", evidence.kind.name).put("input", evidence.input.name).put("output", evidence.output.name)
    .put("size", evidence.size).put("domainMin", JSONArray(evidence.domainMin)).put("domainMax", JSONArray(evidence.domainMax))
    .put("interpolation", evidence.interpolation).put("tablePrecision", evidence.tablePrecision).put("shader", evidence.shader)
    .put("colorPrimaries", "BT.709").put("colorTransfer", "SDR video").put("sampleRange", "limited")
