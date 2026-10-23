/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera.calibration

import com.librestatic.opencinecam.core.model.CalibrationSet
import com.librestatic.opencinecam.core.model.LensCalibrationCodec
import com.librestatic.opencinecam.core.model.LensCorrection

/**
 * One lens' correction in the form the GL pipeline uploads: `corrected = 2^ev * M * (gains * lin)`
 * and the UV matrix that moves the sampling window.
 */
class LensEntry(
    val physicalId: String?,
    /** Column-major 3x3, the order `glUniformMatrix3fv(transpose = false)` expects: index = column * 3 + row. */
    val colorMatrix: FloatArray,
    val gains: FloatArray,
    val evOffset: Float,
    /** Column-major 4x4 acting on (u, v, 0, 1), see [LensAlignment.uvMatrix]. */
    val uvMatrix: FloatArray,
) {
    init {
        require(colorMatrix.size == 9 && gains.size == 3 && uvMatrix.size == 16)
    }
}

/**
 * The per-lens corrections of a [CalibrationSet], prepared for the GPU. The geometry margin is
 * uniform: every entry, the reference and the unknown-lens default included, uses the same
 * [sessionZoom], so a lens switch never changes the framing.
 */
class LensCorrectionTable private constructor(
    private val entries: Map<String, LensEntry>,
    /** Identity colour (and the session zoom, when alignment is on) for an unknown or missing physical id. */
    val defaultEntry: LensEntry,
    val setSha256: String,
    val alignmentEnabled: Boolean,
    /** The common zoom (>= 1) every entry applies; exactly 1 when alignment is off. */
    val sessionZoom: Float,
) {
    val physicalIds: Set<String> get() = entries.keys

    fun entryFor(physicalId: String?): LensEntry = physicalId?.let { entries[it] } ?: defaultEntry

    /** The entry that leaves the frame untouched, for readbacks that must stay uncorrected. */
    val identityEntry: LensEntry get() = IDENTITY_ENTRY

    companion object {
        val IDENTITY_MATRIX3: FloatArray get() = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val IDENTITY_MATRIX4: FloatArray get() = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        val IDENTITY_ENTRY = LensEntry(null, IDENTITY_MATRIX3, floatArrayOf(1f, 1f, 1f), 0f, IDENTITY_MATRIX4)

        /** Row-major (as the model stores it) to column-major. */
        fun columnMajor(rowMajor: List<Float>): FloatArray {
            require(rowMajor.size == 9)
            return FloatArray(9) { i -> rowMajor[(i % 3) * 3 + i / 3] }
        }

        fun build(set: CalibrationSet, alignmentEnabled: Boolean, aspect: Float): LensCorrectionTable {
            val safeAspect = aspect.takeIf { it.isFinite() && it > 0f } ?: 1f
            fun required(l: LensCorrection) =
                LensAlignment.requiredZoom(l.align.tx, l.align.ty, l.align.scale, l.align.rotationRad, safeAspect)
            val sessionZoom = if (alignmentEnabled) set.lenses.maxOfOrNull(::required)?.coerceAtLeast(1f) ?: 1f else 1f
            val built = LinkedHashMap<String, LensEntry>()
            for (lens in set.lenses) {
                val uv = if (alignmentEnabled) {
                    LensAlignment.uvMatrix(
                        lens.align.tx, lens.align.ty, lens.align.scale, lens.align.rotationRad,
                        extraZoom = sessionZoom / required(lens), aspect = safeAspect,
                    )
                } else IDENTITY_MATRIX4
                built[lens.physicalId] = LensEntry(
                    lens.physicalId, columnMajor(lens.colorMatrix), lens.gains.toFloatArray(), lens.evOffset, uv,
                )
            }
            val defaultUv = if (alignmentEnabled) {
                LensAlignment.uvMatrix(0f, 0f, 1f, 0f, extraZoom = sessionZoom, aspect = safeAspect)
            } else IDENTITY_MATRIX4
            val default = LensEntry(null, IDENTITY_MATRIX3, floatArrayOf(1f, 1f, 1f), 0f, defaultUv)
            return LensCorrectionTable(built, default, LensCalibrationCodec.sha256(set), alignmentEnabled, sessionZoom)
        }
    }
}

/** Column-major 4x4 product `lhs * rhs` (rhs acts first), the same convention as `android.opengl.Matrix.multiplyMM`. */
fun multiplyColumnMajor4(lhs: FloatArray, rhs: FloatArray, result: FloatArray = FloatArray(16)): FloatArray {
    require(lhs.size >= 16 && rhs.size >= 16 && result.size >= 16)
    require(result !== lhs && result !== rhs)
    for (col in 0 until 4) for (row in 0 until 4) {
        var sum = 0f
        for (k in 0 until 4) sum += lhs[k * 4 + row] * rhs[col * 4 + k]
        result[col * 4 + row] = sum
    }
    return result
}

/**
 * Thread-safe ring of recent `sensor timestamp -> active physical camera id` pairs, written from
 * the capture callback thread and read on the GL thread.
 */
class PhysicalTimeline(private val capacity: Int = DEFAULT_CAPACITY) {
    class Lookup(val physicalId: String?, val exact: Boolean)

    private val timestamps = LongArray(capacity)
    private val ids = arrayOfNulls<String>(capacity)
    private var count = 0
    private var next = 0

    init { require(capacity > 0) }

    @Synchronized fun note(timestampNs: Long, physicalId: String?) {
        timestamps[next] = timestampNs
        ids[next] = physicalId
        next = (next + 1) % capacity
        if (count < capacity) count++
    }

    /** The id noted for exactly [timestampNs]; otherwise the one with the greatest timestamp below it, else the latest noted id (non-exact). */
    @Synchronized fun lookup(timestampNs: Long): Lookup {
        if (count == 0) return Lookup(null, false)
        var bestIndex = -1
        for (i in 0 until count) {
            val ts = timestamps[i]
            if (ts == timestampNs) return Lookup(ids[i], true)
            if (ts < timestampNs && (bestIndex < 0 || ts > timestamps[bestIndex])) bestIndex = i
        }
        if (bestIndex >= 0) return Lookup(ids[bestIndex], false)
        return Lookup(ids[(next - 1 + capacity) % capacity], false)
    }

    companion object { const val DEFAULT_CAPACITY = 64 }
}

/** What a take records about the correction it was encoded with. */
data class LensCorrectionEvidence(
    val setSha256: String,
    val splicedTransformSha256: String,
    val alignment: Boolean,
    /** Frames encoded per physical id; frames with no known id count under [UNKNOWN_LENS]. */
    val perLensFrameCounts: Map<String, Long>,
    /** Encoded frames whose physical id came from a non-exact timestamp lookup. */
    val fallbackFrames: Long,
) {
    companion object { const val UNKNOWN_LENS = "unknown" }
}

/** The table frozen at REC start plus the counters of the frames encoded with it. */
class LensTakeUsage(val table: LensCorrectionTable, val splicedTransformSha256: String) {
    private val counts = LinkedHashMap<String, Long>()
    private var fallback = 0L

    @Synchronized fun record(physicalId: String?, exact: Boolean) {
        val key = physicalId ?: LensCorrectionEvidence.UNKNOWN_LENS
        counts[key] = (counts[key] ?: 0L) + 1L
        if (!exact) fallback++
    }

    @Synchronized fun evidence() = LensCorrectionEvidence(
        table.setSha256, splicedTransformSha256, table.alignmentEnabled, LinkedHashMap(counts), fallback,
    )
}
