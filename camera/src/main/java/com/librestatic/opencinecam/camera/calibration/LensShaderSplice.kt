/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera.calibration

/**
 * Textual splice that puts the lens colour correction at the camera sample of a fragment shader.
 * It runs after the YCbCr variant, so the sample is either the plain RGB sample or the shader's own
 * YCbCr conversion. The formulas are those of [CalibrationTransfer]. With no splice the shaders are
 * untouched, so their pinned hashes do not move.
 */
object LensShaderSplice {
    const val FUNCTION = "lensCorrect"
    private const val MAIN = "void main() {"
    private const val RGB_SAMPLE = "texture(uTexture, vTexCoord).rgb"
    private const val YCBCR_SAMPLE = "clamp(uYcbcrToRgb * (texture(uTexture, vTexCoord).rgb - uYcbcrOffset), 0.0, 1.0)"
    private const val PASSTHROUGH_ASSIGNMENT = "outColor = texture(uTexture, vTexCoord);"

    private val SDR_CODEC = """
        vec3 lensDecode(vec3 e) {
            e = clamp(e, 0.0, 1.0);
            bvec3 low = lessThan(e, vec3(0.081));
            return mix(pow((e + 0.099) / 1.099, vec3(1.0 / 0.45)), e / 4.5, low);
        }
        vec3 lensEncode(vec3 x) {
            x = clamp(x, 0.0, 1.0);
            bvec3 low = lessThan(x, vec3(0.018));
            return mix(1.099 * pow(x, vec3(0.45)) - 0.099, 4.5 * x, low);
        }
    """.trimIndent()

    private val HLG_CODEC = """
        vec3 lensDecode(vec3 e) {
            e = clamp(e, 0.0, 1.0);
            bvec3 low = lessThanEqual(e, vec3(0.5));
            return mix((exp((e - 0.55991073) / 0.17883277) + 0.28466892) / 12.0, (e * e) / 3.0, low);
        }
        vec3 lensEncode(vec3 x) {
            x = clamp(x, 0.0, 1.0);
            bvec3 low = lessThanEqual(x, vec3(1.0 / 12.0));
            return mix(0.17883277 * log(max(12.0 * x - 0.28466892, 1e-6)) + 0.55991073, sqrt(3.0 * x), low);
        }
    """.trimIndent()

    private fun declarations(curve: TransferCurve): String = """
        uniform float uLensEv;
        uniform mat3 uLensMatrix;
        uniform vec3 uLensGains;
        ${(if (curve == TransferCurve.SDR) SDR_CODEC else HLG_CODEC).replace("\n", "\n        ")}
        vec3 $FUNCTION(vec3 signal) {
            vec3 lin = lensDecode(signal);
            return lensEncode(max(exp2(uLensEv) * (uLensMatrix * (uLensGains * lin)), vec3(0.0)));
        }

    """.trimIndent() + "\n"

    /** [source] is a complete fragment shader whose input signal has transfer [curve]. */
    fun splice(source: String, curve: TransferCurve): String {
        check(source.split(MAIN).size == 2) { "Lens splice anchor drifted: $MAIN" }
        val withSample = when {
            source.contains(YCBCR_SAMPLE) -> replaceOnce(source, YCBCR_SAMPLE, "$FUNCTION($YCBCR_SAMPLE)")
            source.contains(RGB_SAMPLE) -> replaceOnce(source, RGB_SAMPLE, "$FUNCTION($RGB_SAMPLE)")
            source.contains(PASSTHROUGH_ASSIGNMENT) -> replaceOnce(
                source, PASSTHROUGH_ASSIGNMENT,
                "vec4 lensRaw = texture(uTexture, vTexCoord);\n    outColor = vec4($FUNCTION(lensRaw.rgb), lensRaw.a);",
            )
            else -> error("Lens splice found no camera sample")
        }
        return withSample.replace(MAIN, declarations(curve) + MAIN)
    }

    private fun replaceOnce(source: String, anchor: String, replacement: String): String {
        check(source.split(anchor).size == 2) { "Lens splice anchor is not unique: $anchor" }
        return source.replace(anchor, replacement)
    }
}
