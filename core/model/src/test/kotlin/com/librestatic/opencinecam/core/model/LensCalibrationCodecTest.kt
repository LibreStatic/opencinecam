/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LensCalibrationCodecTest {
    private val quality = CalibrationQuality(1.5f, 0.75f, 20, 0.9f, setOf(CalibrationFlag.LOW_TEXTURE, CalibrationFlag.CLIPPED))
    private val tele = LensCorrection(
        physicalId = "3",
        colorMatrix = listOf(1.1f, -0.05f, -0.05f, 0f, 1f, 0f, 0.02f, -0.02f, 1f),
        gains = listOf(1.05f, 1f, 0.95f),
        evOffset = -0.25f,
        align = Similarity(0.01f, -0.02f, 1.1f, 0.003f),
        quality = quality,
    )
    private val set = CalibrationSet(
        fingerprint = "google/pixel/\"x\"", logicalId = "0", sessionKind = CalibrationSessionKind.LOGICAL,
        referencePhysicalId = "2", sourcePath = CalibrationSourcePath.HLG, createdAtMs = 1_700_000_000_000L,
        illuminantHint = IlluminantHint(5600f, listOf(2f, 1f, 1f, 1.5f)),
        lenses = listOf(tele, LensCorrection.identity("2")),
    )

    private fun decoded(text: String) = (LensCalibrationCodec.decode(text) as LensCalibrationDecoding.Decoded).set
    private fun assertInvalid(text: String) =
        assertTrue(text, LensCalibrationCodec.decode(text) is LensCalibrationDecoding.Invalid)

    private fun mutate(from: String, to: String): String {
        val text = LensCalibrationCodec.encode(set)
        assertTrue("$from not in document", from in text)
        return text.replaceFirst(from, to)
    }

    @Test fun roundTrips() {
        val back = decoded(LensCalibrationCodec.encode(set))
        assertEquals(set.copy(lenses = set.lenses.sortedBy { it.physicalId }), back)
        assertEquals(tele, back.correctionFor("3"))
        assertNull(back.correctionFor("9"))
    }

    @Test fun roundTripsWithoutHint() {
        val bare = set.copy(illuminantHint = null)
        assertNull(decoded(LensCalibrationCodec.encode(bare)).illuminantHint)
        val partial = set.copy(illuminantHint = IlluminantHint(null, null))
        assertEquals(partial.illuminantHint, decoded(LensCalibrationCodec.encode(partial)).illuminantHint)
    }

    @Test fun encodingIsDeterministicAndOrderIndependent() {
        val reversed = set.copy(lenses = set.lenses.reversed())
        assertEquals(LensCalibrationCodec.encode(set), LensCalibrationCodec.encode(reversed))
        assertEquals(LensCalibrationCodec.sha256(set), LensCalibrationCodec.sha256(reversed))
        assertTrue(Regex("[0-9a-f]{64}").matches(LensCalibrationCodec.sha256(set)))
        assertNotEquals(LensCalibrationCodec.sha256(set), LensCalibrationCodec.sha256(set.copy(createdAtMs = 1L)))
    }

    @Test fun identityHelpers() {
        val id = LensCorrection.identity("2")
        assertTrue(id.isIdentity)
        assertEquals(1f, id.exposureScale, 1e-6f)
        assertFalse(tele.isIdentity)
        assertFalse(id.copy(evOffset = 0.1f).isIdentity)
        assertFalse(id.copy(align = Similarity(tx = 0.1f)).isIdentity)
        assertTrue(Similarity.IDENTITY.isIdentity)
        assertEquals(2f, id.copy(evOffset = 1f).exposureScale, 1e-6f)
    }

    @Test fun ignoresUnknownKeys() {
        decoded(mutate("\"schema\":1", "\"schema\":1,\"extra\":{\"a\":1}"))
    }

    @Test fun rejectsMalformed() {
        assertInvalid("not json")
        assertInvalid("[]")
        assertInvalid("{}")
    }

    @Test fun rejectsWrongSchema() {
        assertInvalid(mutate("\"schema\":1", "\"schema\":2"))
    }

    @Test fun rejectsWrongArraySizes() {
        assertInvalid(mutate("\"gains\":[1.05,1.0,0.95]", "\"gains\":[1.05,1.0]"))
        assertInvalid(mutate("\"awbGains\":[2.0,1.0,1.0,1.5]", "\"awbGains\":[2.0,1.0,1.0]"))
        assertInvalid(mutate("\"colorMatrix\":[1.1,", "\"colorMatrix\":[1.1,1.0,"))
    }

    @Test fun rejectsNonFiniteAndNonNumbers() {
        assertInvalid(mutate("\"evOffset\":-0.25", "\"evOffset\":NaN"))
        assertInvalid(mutate("\"evOffset\":-0.25", "\"evOffset\":\"NaN\""))
        assertInvalid(mutate("\"evOffset\":-0.25", "\"evOffset\":1e999"))
        assertInvalid(mutate("\"evOffset\":-0.25", "\"evOffset\":Infinity"))
    }

    @Test fun rejectsOutOfRangeValues() {
        assertInvalid(mutate("\"gains\":[1.05,", "\"gains\":[0.0,"))
        assertInvalid(mutate("\"gains\":[1.05,", "\"gains\":[-1.0,"))
        assertInvalid(mutate("\"evOffset\":-0.25", "\"evOffset\":4.5"))
        assertInvalid(mutate("\"scale\":1.1", "\"scale\":2.5"))
        assertInvalid(mutate("\"scale\":1.1", "\"scale\":0.4"))
        assertInvalid(mutate("\"score\":0.9", "\"score\":1.5"))
        assertInvalid(mutate("\"cellsUsed\":20", "\"cellsUsed\":-1"))
        assertInvalid(mutate("\"colorMatrix\":[1.1,", "\"colorMatrix\":[2.1,"))
    }

    @Test fun rejectsBadIds() {
        assertInvalid(mutate("\"physicalId\":\"3\"", "\"physicalId\":\"\""))
        assertInvalid(mutate("\"physicalId\":\"3\"", "\"physicalId\":\"2\""))
        assertInvalid(mutate("\"fingerprint\":\"google/pixel/\\\"x\\\"\"", "\"fingerprint\":\" \""))
        assertInvalid(mutate("\"logicalId\":\"0\"", "\"logicalId\":\"\""))
        assertInvalid(mutate("\"referencePhysicalId\":\"2\"", "\"referencePhysicalId\":\"7\""))
        assertInvalid(LensCalibrationCodec.encode(set.copy(lenses = emptyList())))
    }

    @Test fun rejectsUnknownEnums() {
        assertInvalid(mutate("\"LOGICAL\"", "\"WIDE\""))
        assertInvalid(mutate("\"HLG\"", "\"PQ\""))
        assertInvalid(mutate("\"CLIPPED\"", "\"BLURRY\""))
    }
}
