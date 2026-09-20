/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

/** User-declared signal contract, never inferred from a filename or a LUT title. */
enum class LutSignalDomain { SDR_BT709_CODE, OCLOG2_CODE }
enum class LutTransformKind { TECHNICAL, CREATIVE }
data class MonitorLut(val cube: CubeLut, val kind: LutTransformKind, val input: LutSignalDomain,
    val output: LutSignalDomain = LutSignalDomain.SDR_BT709_CODE) {
    init { require(output == LutSignalDomain.SDR_BT709_CODE) }
}

/** LUT input is the pre-assist source; scopes continue to measure it, not this output. */
fun monitorLutCompatible(lut: MonitorLut, passthroughSdr: Boolean): Boolean =
    lut.input == if (passthroughSdr) LutSignalDomain.SDR_BT709_CODE else LutSignalDomain.OCLOG2_CODE

enum class OperatorLutState { DISABLED, WAITING_FOR_GPU, INCOMPATIBLE_DOMAIN, ACTIVE, FAILED }
data class OperatorLutStatus(val hash: String? = null, val state: OperatorLutState = OperatorLutState.DISABLED,
    val detail: String? = null, val selectionId: String? = null)

fun monitorLutIdentity(lut: MonitorLut?): String? = lut?.let { "${it.cube.sha256}:${it.kind}:${it.input}:${it.output}" }

/** Frozen transform applied to the encoded pixels; do not apply this LUT again on playback. */
class BakedLutEvidence(lut: MonitorLut) {
    val hash: String = lut.cube.sha256
    val kind: LutTransformKind = lut.kind
    val input: LutSignalDomain = lut.input
    val output: LutSignalDomain = lut.output
    val size: Int = lut.cube.size
    val selectionId: String = requireNotNull(monitorLutIdentity(lut))
    val domainMin: List<Float> = java.util.Collections.unmodifiableList(lut.cube.domainMin.toList())
    val domainMax: List<Float> = java.util.Collections.unmodifiableList(lut.cube.domainMax.toList())
    val baked: Boolean = true
    val shader: String = "GLES3_SOURCE_CODE_LUT_V1"
    val interpolation: String = "TRILINEAR_8_TEXEL_FETCH"
    val tablePrecision: String = "RGBA16F_NATIVE_HALF"
}
