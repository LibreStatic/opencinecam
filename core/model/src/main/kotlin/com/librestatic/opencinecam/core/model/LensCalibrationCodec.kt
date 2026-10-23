/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.core.model

import java.security.MessageDigest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs

/** The outcome of reading one lens calibration document. */
sealed interface LensCalibrationDecoding {
    data class Decoded(val set: CalibrationSet) : LensCalibrationDecoding

    data class Invalid(val reason: String) : LensCalibrationDecoding
}

/**
 * Canonical JSON for [CalibrationSet]. Encoding is deterministic (fixed key order, lenses sorted by
 * physical id, flags sorted by name), so [sha256] is stable. Decoding is strict: anything outside the
 * documented ranges is reported as [LensCalibrationDecoding.Invalid], never partially applied.
 * Unknown extra keys are ignored.
 */
object LensCalibrationCodec {
    fun encode(set: CalibrationSet): String {
        val lenses = set.lenses.sortedBy { it.physicalId }.joinToString(",", "[", "]") { lens ->
            val q = lens.quality
            "{" + listOf(
                "physicalId" to str(lens.physicalId),
                "colorMatrix" to floats(lens.colorMatrix),
                "gains" to floats(lens.gains),
                "evOffset" to num(lens.evOffset),
                "align" to "{" + listOf(
                    "tx" to num(lens.align.tx),
                    "ty" to num(lens.align.ty),
                    "scale" to num(lens.align.scale),
                    "rotationRad" to num(lens.align.rotationRad),
                ).obj() + "}",
                "quality" to "{" + listOf(
                    "colorResidualDeltaE" to num(q.colorResidualDeltaE),
                    "alignResidualPx" to num(q.alignResidualPx),
                    "cellsUsed" to q.cellsUsed.toString(),
                    "score" to num(q.score),
                    "flags" to q.flags.map { it.name }.sorted().joinToString(",", "[", "]") { str(it) },
                ).obj() + "}",
            ).obj() + "}"
        }
        val hint = set.illuminantHint?.let {
            "{" + listOf(
                "cctKelvin" to (it.cctKelvin?.let(::num) ?: "null"),
                "awbGains" to (it.awbGains?.let(::floats) ?: "null"),
            ).obj() + "}"
        } ?: "null"
        return "{" + listOf(
            "schema" to set.schema.toString(),
            "fingerprint" to str(set.fingerprint),
            "logicalId" to str(set.logicalId),
            "sessionKind" to str(set.sessionKind.name),
            "referencePhysicalId" to str(set.referencePhysicalId),
            "sourcePath" to str(set.sourcePath.name),
            "createdAtMs" to set.createdAtMs.toString(),
            "illuminantHint" to hint,
            "lenses" to lenses,
        ).obj() + "}"
    }

    /** Lowercase hex SHA-256 of [encode]. */
    fun sha256(set: CalibrationSet): String =
        MessageDigest.getInstance("SHA-256").digest(encode(set).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun decode(text: String): LensCalibrationDecoding = try {
        val root = Json.parseToJsonElement(text) as? JsonObject ?: return invalid("document must be a JSON object")
        val schema = root.int("schema")
        require(schema == CalibrationSet.SCHEMA) { "unsupported schema $schema" }
        val lenses = root.array("lenses").map { lens(it as? JsonObject ?: throw IllegalArgumentException("a lens must be an object")) }
        require(lenses.isNotEmpty()) { "no lenses" }
        require(lenses.map { it.physicalId }.toSet().size == lenses.size) { "duplicate physicalId" }
        val reference = root.nonBlank("referencePhysicalId")
        require(lenses.any { it.physicalId == reference }) { "reference lens has no correction" }
        LensCalibrationDecoding.Decoded(
            CalibrationSet(
                schema = schema,
                fingerprint = root.nonBlank("fingerprint"),
                logicalId = root.nonBlank("logicalId"),
                sessionKind = root.enum<CalibrationSessionKind>("sessionKind"),
                referencePhysicalId = reference,
                sourcePath = root.enum<CalibrationSourcePath>("sourcePath"),
                createdAtMs = root.long("createdAtMs"),
                illuminantHint = (root["illuminantHint"]?.takeIf { it !is JsonNull })?.let { hint ->
                    val obj = hint as? JsonObject ?: throw IllegalArgumentException("illuminantHint must be an object or null")
                    IlluminantHint(
                        cctKelvin = obj["cctKelvin"]?.takeIf { it !is JsonNull }?.let { number(it, "cctKelvin") }
                            ?.also { require(it > 0f) { "cctKelvin must be positive" } },
                        awbGains = obj["awbGains"]?.takeIf { it !is JsonNull }?.let { floatList(it, "awbGains", 4) },
                    )
                },
                lenses = lenses,
            ),
        )
    } catch (failure: SerializationException) {
        invalid(failure.message ?: "malformed JSON")
    } catch (failure: IllegalArgumentException) {
        invalid(failure.message ?: "invalid calibration")
    }

    private fun lens(o: JsonObject): LensCorrection {
        val matrix = floatList(o["colorMatrix"], "colorMatrix", 9)
        for (row in 0 until 3) {
            val sum = matrix[row * 3] + matrix[row * 3 + 1] + matrix[row * 3 + 2]
            require(abs(sum - 1f) <= ROW_SUM_TOLERANCE) { "colorMatrix row $row does not sum to 1" }
        }
        val gains = floatList(o["gains"], "gains", 3)
        require(gains.all { it > 0f }) { "gains must be positive" }
        val ev = number(o["evOffset"], "evOffset")
        require(abs(ev) <= MAX_EV) { "evOffset out of range" }
        val align = o.obj("align")
        val scale = number(align["scale"], "scale")
        require(scale in 0.5f..2f) { "scale out of range" }
        val q = o.obj("quality")
        val score = number(q["score"], "score")
        require(score in 0f..1f) { "score out of range" }
        val cells = q.int("cellsUsed")
        require(cells >= 0) { "cellsUsed must not be negative" }
        return LensCorrection(
            physicalId = o.nonBlank("physicalId"),
            colorMatrix = matrix,
            gains = gains,
            evOffset = ev,
            align = Similarity(
                tx = number(align["tx"], "tx"),
                ty = number(align["ty"], "ty"),
                scale = scale,
                rotationRad = number(align["rotationRad"], "rotationRad"),
            ),
            quality = CalibrationQuality(
                colorResidualDeltaE = number(q["colorResidualDeltaE"], "colorResidualDeltaE"),
                alignResidualPx = number(q["alignResidualPx"], "alignResidualPx"),
                cellsUsed = cells,
                score = score,
                flags = q.array("flags").map { element ->
                    val name = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
                    CalibrationFlag.entries.firstOrNull { it.name == name }
                        ?: throw IllegalArgumentException("unknown flag '$name'")
                }.toSet(),
            ),
        )
    }

    private const val MAX_EV = 4f
    private const val ROW_SUM_TOLERANCE = 0.02f

    private fun invalid(reason: String) = LensCalibrationDecoding.Invalid(reason)

    private fun List<Pair<String, String>>.obj() = joinToString(",") { (k, v) -> "${str(k)}:$v" }

    private fun str(value: String) = JsonPrimitive(value).toString()

    private fun num(value: Float): String {
        require(value.isFinite()) { "non-finite number" }
        // Normalise negative zero so equal values encode equally.
        return (if (value == 0f) 0f else value).toString()
    }

    private fun floats(values: List<Float>) = values.joinToString(",", "[", "]") { num(it) }

    private fun number(element: kotlinx.serialization.json.JsonElement?, name: String): Float {
        val p = element as? JsonPrimitive
        require(p != null && p !is JsonNull && !p.isString) { "'$name' must be a number" }
        val d = p.content.toDoubleOrNull() ?: throw IllegalArgumentException("'$name' must be a number")
        val f = d.toFloat()
        require(d.isFinite() && f.isFinite()) { "'$name' must be finite" }
        return f
    }

    private fun floatList(element: kotlinx.serialization.json.JsonElement?, name: String, size: Int): List<Float> {
        val array = element as? JsonArray ?: throw IllegalArgumentException("'$name' must be an array")
        require(array.size == size) { "'$name' must have $size entries" }
        return array.map { number(it, name) }
    }

    private fun JsonObject.obj(name: String) =
        this[name] as? JsonObject ?: throw IllegalArgumentException("'$name' must be an object")

    private fun JsonObject.array(name: String) =
        this[name] as? JsonArray ?: throw IllegalArgumentException("'$name' must be an array")

    private fun JsonObject.int(name: String): Int {
        val f = number(this[name], name)
        require(f == Math.rint(f.toDouble()).toFloat() && abs(f) < 1e9f) { "'$name' must be an integer" }
        return f.toInt()
    }

    private fun JsonObject.long(name: String): Long {
        val p = this[name] as? JsonPrimitive
        require(p != null && p !is JsonNull && !p.isString) { "'$name' must be an integer" }
        return p.content.toLongOrNull() ?: throw IllegalArgumentException("'$name' must be an integer")
    }

    private fun JsonObject.nonBlank(name: String): String {
        val p = this[name] as? JsonPrimitive
        val s = p?.takeIf { it.isString }?.content
        require(!s.isNullOrBlank()) { "'$name' must be a non-blank string" }
        return s
    }

    private inline fun <reified E : Enum<E>> JsonObject.enum(name: String): E {
        val s = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return enumValues<E>().firstOrNull { it.name == s } ?: throw IllegalArgumentException("unknown $name '$s'")
    }
}
