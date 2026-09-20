/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import java.security.MessageDigest
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

/** Adobe Cube LUT Specification 1.0 (2013), sections 5 and 7, red-fastest order.
 * Primary Adobe-authored specification mirror:
 * https://kono.phpage.fr/images/a/a1/Adobe-cube-lut-specification-1.0.pdf
 * Original: https://wwwimages2.adobe.com/content/dam/acom/en/products/speedgrade/cc/pdfs/cube-lut-specification-1.0.pdf
 * This monitor intentionally supports 2..33, SDR output and trilinear, not the full Adobe feature set.
 */
class CubeLutTest {
    private fun cube(size: Int = 2, header: String = "", transform: (Float, Float, Float) -> String = { r, g, b -> "$r $g $b" }): String =
        buildString {
            append(header); append("LUT_3D_SIZE $size\n")
            for (b in 0 until size) for (g in 0 until size) for (r in 0 until size)
                append(transform(r.toFloat() / (size - 1), g.toFloat() / (size - 1), b.toFloat() / (size - 1))).append('\n')
        }
    private fun parse(text: String) = CubeLut.parse(text.toByteArray(Charsets.UTF_8))
    private fun rejects(text: String) = rejects(text.toByteArray(Charsets.UTF_8))
    private fun rejects(bytes: ByteArray) { try { CubeLut.parse(bytes); fail("Expected rejected Cube") } catch (_: IllegalArgumentException) { } }
    private fun rgb(expected: FloatArray, actual: FloatArray) = assertArrayEquals(expected, actual, 0.000001f)

    @Test fun identityCornersAndInteriorRemainIdentity() {
        val lut = parse(cube())
        assertEquals(2, lut.size); assertNull(lut.title)
        for (r in listOf(0f, .17f, 1f)) for (g in listOf(0f, .43f, 1f)) for (b in listOf(0f, .81f, 1f))
            rgb(floatArrayOf(r, g, b), lut.sample(r, g, b))
        rgb(floatArrayOf(0f, 0f, 0f), lut.domainMin); rgb(floatArrayOf(1f, 1f, 1f), lut.domainMax)
    }
    @Test fun redChangesFastestAndOutputAxesAreNotTransposed() {
        val lut = parse(cube(transform = { r, g, b -> "$b $r $g" }))
        rgb(floatArrayOf(0f, 1f, 0f), lut.values.copyOfRange(3, 6))
        rgb(floatArrayOf(.7f, .2f, .4f), lut.sample(.2f, .4f, .7f))
    }
    @Test fun nonlinearTableUsesEightCornerTrilinearWeights() {
        val lut = parse(cube(3, transform = { r, g, b -> "${r*r} ${g*g} ${b*b}" }))
        rgb(floatArrayOf(.125f, .625f, .25f), lut.sample(.25f, .75f, .5f))
        val product = parse(cube(transform = { r, g, b -> "${r*g*b} ${r*g} ${g*b}" }))
        rgb(floatArrayOf(.024f, .08f, .12f), product.sample(.2f, .4f, .3f))
    }
    @Test fun everySingleBrightCornerHasItsCorrectWeight() {
        for (corner in 0..7) {
            val lut = parse(cube(transform = { r, g, b ->
                val index = r.toInt() + 2*g.toInt() + 4*b.toInt()
                if (index == corner) "1 1 1" else "0 0 0"
            }))
            val weight = (if (corner and 1 == 0) .8f else .2f) *
                (if (corner and 2 == 0) .6f else .4f) * (if (corner and 4 == 0) .3f else .7f)
            rgb(floatArrayOf(weight, weight, weight), lut.sample(.2f, .4f, .7f))
        }
    }
    @Test fun arbitraryDomainsNormalizeAndClampPerAxis() {
        val lut = parse(cube(header = "DOMAIN_MAX 2 20 .5\nDOMAIN_MIN -2 10 -.5\n"))
        rgb(floatArrayOf(.75f, .5f, .25f), lut.sample(1f, 15f, -.25f))
        rgb(floatArrayOf(0f, 1f, 0f), lut.sample(-Float.MAX_VALUE, Float.MAX_VALUE, -1f))
    }
    @Test fun domainLimitDoesNotOverflowInterpolation() {
        val lut = parse(cube(header = "DOMAIN_MIN -1000000 -1000000 -1000000\nDOMAIN_MAX 1000000 1000000 1000000\n"))
        rgb(floatArrayOf(.5f, .5f, .5f), lut.sample(0f, 0f, 0f))
    }
    @Test fun commentsCrLfQuotedHashAndWhitespaceAreAccepted() {
        val text = cube(header = "  # comment\n TITLE \"A # title\" # inline\nDOMAIN_MIN\t0 0 0\n")
            .replace("0.0 0.0 0.0", "\t+0e0 .0 0. # row")
            .replace("\n", "\r\n")
        val lut = parse(text)
        assertEquals("A # title", lut.title)
        rgb(floatArrayOf(.1f, .2f, .3f), lut.sample(.1f, .2f, .3f))
    }
    @Test fun sizes32And33HaveExactBoundedCountsAndRoundTrip() {
        for (size in listOf(32, 33)) {
            val input = cube(size).toByteArray()
            assertTrue(input.size <= CubeLut.MAX_BYTES)
            val lut = CubeLut.parse(input)
            assertEquals(size * size * size * 3, lut.values.size)
            assertEquals(if (size == 33) 431244 else 393216, lut.values.size * 4)
            rgb(floatArrayOf(.21f, .56f, .89f), lut.sample(.21f, .56f, .89f))
            assertArrayEquals(lut.values, CubeLut.parse(lut.export()).values, 0f)
        }
    }
    @Test fun exportIsCanonicalLocaleIndependentAndPreservesExactMapping() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRANCE)
            val lut = parse(cube(3, "TITLE \"Mapping\"\nDOMAIN_MIN -1 -.5 0\nDOMAIN_MAX 1 1 2\n",
                transform = { r,g,b -> "${1-r} ${g*b} ${r*b}" }))
            val restored = CubeLut.parse(lut.export())
            assertEquals(lut.title, restored.title)
            assertArrayEquals(lut.domainMin, restored.domainMin, 0f)
            assertArrayEquals(lut.domainMax, restored.domainMax, 0f)
            assertArrayEquals(lut.values, restored.values, 0f)
            assertArrayEquals(lut.export(), restored.export())
            rgb(lut.sample(.12f, .44f, .99f), restored.sample(.12f, .44f, .99f))
        } finally { Locale.setDefault(previous) }
    }
    @Test fun hashIdentifiesExactSourceNotCanonicalMapping() {
        val source = cube().toByteArray()
        val lut = CubeLut.parse(source)
        val expected = MessageDigest.getInstance("SHA-256").digest(source).joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(expected, lut.sha256)
        val alternate = parse("# another source\n" + cube())
        assertNotEquals(lut.sha256, alternate.sha256)
        assertArrayEquals(lut.values, alternate.values, 0f)
    }
    @Test fun inputArraysReturnedArraysAndSamplesAreDefensive() {
        val input = cube().toByteArray(); val lut = CubeLut.parse(input)
        val hash = lut.sha256
        input.fill(0); lut.values.fill(0f); lut.domainMin.fill(1f); lut.domainMax.fill(2f)
        lut.sample(1f, 1f, 1f).fill(0f); lut.export().fill(0)
        rgb(floatArrayOf(.2f, .4f, .6f), lut.sample(.2f, .4f, .6f))
        assertEquals(hash, lut.sha256)
        rgb(floatArrayOf(0f, 0f, 0f), lut.domainMin)
    }
    @Test fun invalidAndOverflowingSizesRejectBeforeAllocation() {
        for (size in listOf("0", "1", "34", "256", "2147483647", "999999999999999999999", "2.0", "-2", "2e0"))
            rejects("LUT_3D_SIZE $size\n")
    }
    @Test fun missingExtraAndMalformedRowsAreRejected() {
        val valid = cube()
        rejects(""); rejects("# no table\n"); rejects(valid.substringBeforeLast("1.0 1.0 1.0"))
        rejects(valid + "0 0 0\n"); rejects(valid.replaceFirst("0.0 0.0 0.0", "0 0"))
        rejects(valid.replaceFirst("0.0 0.0 0.0", "0 0 0 0")); rejects("0 0 0\n" + valid)
    }
    @Test fun duplicateLateUnknownAndShaperHeadersAreRejected() {
        for (header in listOf("LUT_3D_SIZE 2\n", "LUT_1D_SIZE 2\n", "LUT_3D_INPUT_RANGE 0 1\n", "BOGUS 1\n")) rejects(header + cube())
        for (header in listOf("TITLE \"x\"\n", "DOMAIN_MIN 0 0 0\n", "DOMAIN_MAX 1 1 1\n")) {
            rejects(header + header + cube()); rejects(cube() + header)
        }
        rejects(cube() + "LUT_3D_SIZE 2\n")
    }
    @Test fun titlesMustBeQuotedBoundedAndWithoutEscapedQuoteSyntax() {
        for (title in listOf("unquoted", "\"missing", "\"a\" trailing", "\"a\"b\"", "\"a\tb\"")) rejects("TITLE $title\n" + cube())
        rejects("TITLE \"${"a".repeat(243)}\"\n" + cube())
        assertEquals("", parse("TITLE \"\"\n" + cube()).title)
        assertEquals(242, parse("TITLE \"${"a".repeat(242)}\"\n" + cube()).title!!.length)
    }
    @Test fun numbersRejectNonfiniteHexSuffixCommaAndOutOfRangeWithoutClipping() {
        for (bad in listOf("NaN", "Infinity", "-Infinity", "1e309", "0x1p0", "1f", "0,5", "--1", "1e", "-0.00001", "1.00000000001", "1e-100", "1e-9999", "-1e-9999"))
            rejects(cube().replaceFirst("0.0 0.0 0.0", "$bad 0 0"))
    }
    @Test fun domainsRejectUnorderedUnrepresentableOrUnboundedRanges() {
        for (header in listOf("DOMAIN_MIN 1 0 0", "DOMAIN_MAX 0 1 1", "DOMAIN_MIN 2 0 0",
            "DOMAIN_MAX 1000001 1 1", "DOMAIN_MIN -1000001 0 0", "DOMAIN_MAX NaN 1 1",
            "DOMAIN_MIN 1 0 0\nDOMAIN_MAX 1.000000001 1 1")) rejects(header + "\n" + cube())
    }
    @Test fun invalidUtf8ControlsBomAndOversizedLinesAreRejected() {
        rejects(byteArrayOf(0xc3.toByte(), 0x28)); rejects(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + cube().toByteArray())
        for (control in listOf('\u0000', '\u000b', '\u007f')) rejects("# $control\n" + cube())
        rejects("#" + "x".repeat(250) + "\n" + cube())
        rejects(ByteArray(CubeLut.MAX_BYTES + 1) { 32 })
    }
    @Test fun exactByteLimitIsAcceptedWithoutUnboundedRows() {
        val base = cube().toByteArray()
        val bytes = ByteArray(CubeLut.MAX_BYTES) { 10 }
        base.copyInto(bytes)
        assertEquals(2, CubeLut.parse(bytes).size)
    }
    @Test fun nonfiniteSamplingIsRejectedRatherThanHiddenByClamp() {
        val lut = parse(cube())
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            try { lut.sample(bad, 0f, 0f); fail("nonfinite input accepted") } catch (_: IllegalArgumentException) { }
        }
    }
}
