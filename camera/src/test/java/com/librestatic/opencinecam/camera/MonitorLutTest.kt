/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera
import org.junit.Assert.*
import org.junit.Test
class MonitorLutTest {
    private fun cube() = CubeLut.parse(("LUT_3D_SIZE 2\n" + (0..7).joinToString("\n") { "${it and 1} ${(it shr 1) and 1} ${(it shr 2) and 1}" } + "\n").toByteArray())
    @Test fun exactPreAssistDomainNotFilenameControlsActivation() {
        for (kind in LutTransformKind.entries) for (input in LutSignalDomain.entries) {
            val lut = MonitorLut(cube(),kind,input)
            assertEquals(input == LutSignalDomain.SDR_BT709_CODE,monitorLutCompatible(lut,true))
            assertEquals(input == LutSignalDomain.OCLOG2_CODE,monitorLutCompatible(lut,false))
        }
    }
    @Test fun declarationChangesInvalidateIdentityEvenWhenSourceBytesMatch() {
        val source = cube(); val original = MonitorLut(source,LutTransformKind.TECHNICAL,LutSignalDomain.OCLOG2_CODE)
        assertNotEquals(monitorLutIdentity(original),monitorLutIdentity(original.copy(input=LutSignalDomain.SDR_BT709_CODE)))
        assertNotEquals(monitorLutIdentity(original),monitorLutIdentity(original.copy(kind=LutTransformKind.CREATIVE)))
        assertEquals(monitorLutIdentity(original),monitorLutIdentity(original.copy()))
    }
    @Test fun hdrOrLogOutputIsNotSilentlyTreatedAsDisplaySdr() {
        assertThrows(IllegalArgumentException::class.java) { MonitorLut(cube(),LutTransformKind.TECHNICAL,
            LutSignalDomain.SDR_BT709_CODE,LutSignalDomain.OCLOG2_CODE) }
    }

    @Test fun bakedEvidenceFreezesExactSourceAndDomainDeclaration() {
        val source = CubeLut.parse(("DOMAIN_MIN -1 0 -2\nDOMAIN_MAX 2 3 4\nLUT_3D_SIZE 2\n" +
            List(8) { "0.25 0.5 0.75" }.joinToString("\n") + "\n").toByteArray())
        val lut = MonitorLut(source, LutTransformKind.TECHNICAL, LutSignalDomain.OCLOG2_CODE)
        val evidence = BakedLutEvidence(lut)
        assertEquals(source.sha256, evidence.hash)
        assertEquals(monitorLutIdentity(lut), evidence.selectionId)
        assertEquals(lut.kind, evidence.kind)
        assertEquals(lut.input, evidence.input)
        assertEquals(LutSignalDomain.SDR_BT709_CODE, evidence.output)
        assertEquals(2, evidence.size)
        assertEquals(listOf(-1f, 0f, -2f), evidence.domainMin)
        assertEquals(listOf(2f, 3f, 4f), evidence.domainMax)
        assertTrue(evidence.baked)
        assertEquals("GLES3_SOURCE_CODE_LUT_V1", evidence.shader)
        assertEquals("TRILINEAR_8_TEXEL_FETCH", evidence.interpolation)
        assertEquals("RGBA16F_NATIVE_HALF", evidence.tablePrecision)
    }

    @Test fun bakedEvidenceCollectionsAndSourceCopiesCannotRewriteTheTake() {
        val source = cube()
        val lut = MonitorLut(source, LutTransformKind.CREATIVE, LutSignalDomain.SDR_BT709_CODE)
        val evidence = BakedLutEvidence(lut)
        source.domainMin.fill(-100f); source.domainMax.fill(100f); source.values.fill(0f)
        assertThrows(UnsupportedOperationException::class.java) { (evidence.domainMin as MutableList<Float>)[0] = -1f }
        assertThrows(UnsupportedOperationException::class.java) { (evidence.domainMax as MutableList<Float>).clear() }
        assertEquals(listOf(0f, 0f, 0f), evidence.domainMin)
        assertEquals(listOf(1f, 1f, 1f), evidence.domainMax)
        val later = lut.copy(kind = LutTransformKind.TECHNICAL, input = LutSignalDomain.OCLOG2_CODE)
        assertNotEquals(monitorLutIdentity(later), evidence.selectionId)
        assertEquals(monitorLutIdentity(lut), evidence.selectionId)
    }

    @Test fun bakedEvidenceDistinguishesSameBytesWithDifferentSignalSemantics() {
        val source = cube()
        val identities = LutSignalDomain.entries.flatMap { input -> LutTransformKind.entries.map { kind ->
            BakedLutEvidence(MonitorLut(source, kind, input)).selectionId
        } }
        assertEquals(4, identities.toSet().size)
        assertTrue(identities.all { it.startsWith(source.sha256 + ":") })
    }
}
