/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCineLogLensShaderTest {
    private val c = OpenCineLogGpuPipeline.Companion

    /** Pinned before the lens splice existed: with no table the linked sources must stay byte-identical. */
    @Test fun defaultShaderSourcesKeepTheirPinnedHashes() {
        assertEquals("66ba3a4d5c432c935110f4639e64eb894b3a334a7397ce80fe139f9b787233aa", c.transformSha256(OpenCineLogSourcePath.HLG10_BT2020, true))
        assertEquals("41e87f366a5e89a0e78e5775147d080a0d82e54ec6b2c4afbb46a86e49107ffa", c.transformSha256(OpenCineLogSourcePath.HLG10_BT2020, false))
        assertEquals("25db8970358056bc48d4aa3ed1540979af82764c58bae9a76d56a250cce9a0f8", c.transformSha256(OpenCineLogSourcePath.SDR_BT709_ISP, true))
        assertEquals("bdb5d9f3db71d3d6d37a065e9d1a4d7f660e4c8ce98ceb2fa701dc71e10f997b", c.transformSha256(OpenCineLogSourcePath.SDR_BT709_ISP, false))
        assertEquals("fd2eb5d668f1c6ad365357cf3af518d6562107652b5bc65c91abbb2f36f1a0a8", c.hlgSignalSha256(true))
        assertEquals("ae644ef2092d976258f2451e1d1f0dbf1439036031b31d163b7d8c3578f47bff", c.hlgSignalSha256(false))
        assertEquals("7c50e86a83ead229140a072a2025c03efbc11f5e87d612ecc9ec5b79b4969fe9", c.pqSignalSha256(true))
        assertEquals("6d72428139ec2c37d6b44f8da6e34873fbda30df49bc312f24a259d1a2db8cfc", c.pqSignalSha256(false))
        assertEquals("63371d0625cc2f43129514d6baba7c9a85509b386581537ea112472d28c43731", c.passthroughSha256())
    }

    private fun lensSources(): List<Pair<String, String>> = buildList {
        for (path in OpenCineLogSourcePath.values()) for (y in listOf(true, false)) add("transform $path $y" to c.transformShader(path, y, lens = true))
        for (y in listOf(true, false)) { add("hlg $y" to c.hlgSignalShader(y, lens = true)); add("pq $y" to c.pqSignalShader(y, lens = true)) }
        add("passthrough" to c.passthroughShader(lens = true))
    }

    @Test fun splicedVariantsDifferFromTheDefaults() {
        assertNotEquals(c.transformSha256(OpenCineLogSourcePath.SDR_BT709_ISP, true), c.transformSha256(OpenCineLogSourcePath.SDR_BT709_ISP, true, lens = true))
        assertNotEquals(c.passthroughSha256(), c.passthroughSha256(lens = true))
    }

    @Test fun everySplicedVariantDefinesLensCorrectOnceAndNoRawSampleRemains() {
        for ((name, source) in lensSources()) {
            assertEquals("$name definitions", 1, Regex("""vec3 lensCorrect\(""").findAll(source).count())
            assertEquals("$name calls", 1, Regex("""lensCorrect\(""").findAll(source).count() - 1)
            assertEquals("$name uniforms", 1, Regex("""uniform mat3 uLensMatrix;""").findAll(source).count())
            // Every sample of the camera texture sits inside the lensCorrect call (or its passthrough temporary).
            val samples = Regex("""texture\(uTexture, vTexCoord\)""").findAll(source).toList()
            assertEquals("$name samples", 1, samples.size)
            val line = source.lines().first { "texture(uTexture, vTexCoord)" in it }
            assertTrue("$name: $line", "lensCorrect(" in line || "lensRaw" in line)
            assertEquals("$name has one main", 1, source.split("void main() {").size - 1)
            assertTrue("$name declares before main", source.indexOf("vec3 lensCorrect(") < source.indexOf("void main() {"))
            assertFalse("$name keeps no mat3-transposing", "transpose" in source)
        }
    }

    @Test fun eachProgramDecodesWithItsOwnSourceCurve() {
        val hlgDecode = "0.55991073"
        val sdrDecode = "1.099"
        val expectHlg = setOf("transform HLG10_BT2020 true", "transform HLG10_BT2020 false", "hlg true", "hlg false", "pq true", "pq false")
        for ((name, source) in lensSources()) {
            val block = source.substringAfter("vec3 lensDecode(").substringBefore("vec3 lensCorrect(")
            val isHlg = name in expectHlg
            assertEquals("$name decode curve", isHlg, hlgDecode in block)
            assertEquals("$name encode curve", !isHlg, sdrDecode in block)
        }
    }

    @Test fun ycbcrVariantsWrapTheirOwnConversion() {
        val src = c.transformShader(OpenCineLogSourcePath.HLG10_BT2020, true, lens = true)
        assertTrue("lensCorrect(clamp(uYcbcrToRgb * (texture(uTexture, vTexCoord).rgb - uYcbcrOffset), 0.0, 1.0))" in src)
    }
}
