/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** The Main10 takes that are checked after publication. */
enum class Main10TakeKind { HLG, LOG }

/** Why effective precision is not measured by the app: a camera scene cannot prove it (PLAN-036). */
const val PRECISION_REQUIRES_CONTROLLED_CAPTURE = "requires-controlled-capture"

/**
 * The newest Main10 file check (PLAN-036): the container signalling of a published take, judged on its own. Effective
 * precision is a separate question that needs a controlled chart, so production never claims a result for it.
 */
data class Main10FileCheck(
    val kind: Main10TakeKind,
    /** Display name of the checked file, when the take names it. */
    val takeName: String?,
    val checkedAtMs: Long,
    val fileSignal: QualificationResult,
    val effectivePrecision: EffectivePrecisionReport,
)

object Main10FileChecks {
    /**
     * Judges one published take. A null [signal] (file unreadable) is UNKNOWN, never a pass. HLG takes must signal
     * BT.2020 HLG; LOG takes carry no transfer tag (the OCLog2 sidecar is authoritative), so only HEVC Main10 is judged.
     * Effective precision is always UNKNOWN here: no controlled chart is available to a field take.
     */
    fun evaluate(kind: Main10TakeKind, signal: Main10FileSignal?, takeName: String?, nowMs: Long): Main10FileCheck {
        val file = when {
            signal == null -> QualificationResult(QualificationStatus.UNKNOWN, "file-signal-unreadable", "the published file could not be read")
            kind == Main10TakeKind.HLG -> Main10FileQualifier.validateSignal(signal)
            else -> Main10FileQualifier.validateMain10Profile(signal)
        }
        return Main10FileCheck(kind, takeName, nowMs, file,
            EffectivePrecisionReport(QualificationStatus.UNKNOWN, null, 0, PRECISION_REQUIRES_CONTROLLED_CAPTURE))
    }
}

/** Where the latest check is kept between runs. */
interface Main10FileCheckStore {
    fun load(): Main10FileCheck?
    fun save(check: Main10FileCheck)
}

object Main10FileCheckCodec {
    fun encode(check: Main10FileCheck): String = Json.encodeToString(JsonElement.serializer(), buildJsonObject {
        put("schema", "opencinecam.main10-file-check.v1")
        put("kind", check.kind.name)
        check.takeName?.let { put("takeName", it) }
        put("checkedAtMs", check.checkedAtMs)
        put("fileSignal", buildJsonObject {
            put("status", check.fileSignal.status.name)
            put("reasonCode", check.fileSignal.reasonCode)
            put("detail", check.fileSignal.detail)
        })
        put("effectivePrecision", buildJsonObject {
            put("status", check.effectivePrecision.status.name)
            check.effectivePrecision.measuredBits?.let { put("measuredBits", it) }
            put("sampleCount", check.effectivePrecision.sampleCount)
            put("reasonCode", check.effectivePrecision.reasonCode)
        })
    })

    /** Null for anything that is not a check this version wrote. */
    fun decode(text: String): Main10FileCheck? = runCatching {
        val root = Json.parseToJsonElement(text).jsonObject
        require(root.getValue("schema").jsonPrimitive.content == "opencinecam.main10-file-check.v1")
        val signal = root.getValue("fileSignal").jsonObject
        val precision = root.getValue("effectivePrecision").jsonObject
        Main10FileCheck(
            kind = Main10TakeKind.valueOf(root.getValue("kind").jsonPrimitive.content),
            takeName = root["takeName"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull,
            checkedAtMs = requireNotNull(root.getValue("checkedAtMs").jsonPrimitive.longOrNull),
            fileSignal = QualificationResult(
                QualificationStatus.valueOf(signal.getValue("status").jsonPrimitive.content),
                signal.getValue("reasonCode").jsonPrimitive.content,
                signal.getValue("detail").jsonPrimitive.content,
            ),
            effectivePrecision = EffectivePrecisionReport(
                QualificationStatus.valueOf(precision.getValue("status").jsonPrimitive.content),
                precision["measuredBits"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.intOrNull,
                requireNotNull(precision.getValue("sampleCount").jsonPrimitive.intOrNull),
                precision.getValue("reasonCode").jsonPrimitive.content,
            ),
        )
    }.getOrNull()
}

/**
 * Runs the check after a Main10 take is published, on [executor] and never on the camera or main thread. It only reads
 * the published file: it never blocks, retries, rewrites or deletes the take, and any failure becomes an UNKNOWN check.
 */
class Main10FileCheckRunner(
    private val store: Main10FileCheckStore,
    private val readSignal: (String) -> Main10FileSignal?,
    private val executor: Executor,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun schedule(uri: String, kind: Main10TakeKind, takeName: String?) {
        try {
            executor.execute {
                val signal = runCatching { readSignal(uri) }.getOrNull()
                runCatching { store.save(Main10FileChecks.evaluate(kind, signal, takeName, clock())) }
            }
        } catch (_: RejectedExecutionException) {
            // Shutting down: the take is already published; the check is evidence only.
        }
    }
}
