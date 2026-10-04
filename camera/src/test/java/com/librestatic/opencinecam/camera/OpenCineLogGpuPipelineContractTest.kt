/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import android.util.Size
import kotlin.math.exp
import kotlin.math.pow

class OpenCineLogGpuPipelineContractTest {
    @Test
    fun productionShaderIsPinnedToTheSidecarIdentity() {
        assertEquals(
            "66ba3a4d5c432c935110f4639e64eb894b3a334a7397ce80fe139f9b787233aa",
            OpenCineLogGpuPipeline.TRANSFORM_SHA256,
        )
        assertEquals(
            "25db8970358056bc48d4aa3ed1540979af82764c58bae9a76d56a250cce9a0f8",
            OpenCineLogGpuPipeline.SDR_TRANSFORM_SHA256,
        )
        assertNotEquals(OpenCineLogGpuPipeline.TRANSFORM_SHA256, OpenCineLogGpuPipeline.SDR_TRANSFORM_SHA256)
        // Driver-sampler fallback identities, recorded so qualification can name and reject them.
        assertEquals(
            "41e87f366a5e89a0e78e5775147d080a0d82e54ec6b2c4afbb46a86e49107ffa",
            OpenCineLogGpuPipeline.transformSha256(OpenCineLogSourcePath.HLG10_BT2020, shaderYcbcr = false),
        )
        assertEquals(
            "bdb5d9f3db71d3d6d37a065e9d1a4d7f660e4c8ce98ceb2fa701dc71e10f997b",
            OpenCineLogGpuPipeline.transformSha256(OpenCineLogSourcePath.SDR_BT709_ISP, shaderYcbcr = false),
        )
    }

    @Test
    fun shaderYcbcrVariantOnlySwapsTheSampler() {
        for (path in OpenCineLogSourcePath.entries) {
            val driver = OpenCineLogGpuPipeline.transformShader(path, shaderYcbcr = false)
            val shader = OpenCineLogGpuPipeline.transformShader(path, shaderYcbcr = true)
            assertTrue(shader.contains("#extension GL_EXT_YUV_target : require"))
            assertTrue(shader.contains("uniform __samplerExternal2DY2YEXT uTexture;"))
            assertFalse(shader.contains("samplerExternalOES"))
            assertEquals(1, shader.split("uYcbcrToRgb * (texture(uTexture, vTexCoord).rgb - uYcbcrOffset)").size - 1)
            // Everything after the sampled value is the same transform body.
            assertEquals(driver.substringAfter("texture(uTexture, vTexCoord).rgb"), shader.substringAfter("uYcbcrOffset), 0.0, 1.0)"))
        }
    }

    @Test
    fun sourceDataSpaceMustMatchTheTierShader() {
        val bt2020HlgFull = 168165376 // DataSpace.DATASPACE_BT2020_HLG
        val bt2020HlgLimited = 302383104 // DataSpace.DATASPACE_BT2020_ITU_HLG
        val bt2020PqFull = 163971072 // DataSpace.DATASPACE_BT2020_PQ
        val bt709Limited = 281083904 // DataSpace.DATASPACE_BT709
        val jfif = 146931712 // DataSpace.DATASPACE_JFIF
        assertTrue(OpenCineLogSourcePath.HLG10_BT2020.acceptsDataSpace(bt2020HlgFull))
        assertTrue(OpenCineLogSourcePath.HLG10_BT2020.acceptsDataSpace(bt2020HlgLimited))
        assertFalse(OpenCineLogSourcePath.HLG10_BT2020.acceptsDataSpace(bt2020PqFull))
        assertFalse(OpenCineLogSourcePath.HLG10_BT2020.acceptsDataSpace(bt709Limited))
        assertFalse(OpenCineLogSourcePath.HLG10_BT2020.acceptsDataSpace(0))
        assertTrue(OpenCineLogSourcePath.SDR_BT709_ISP.acceptsDataSpace(bt709Limited))
        assertTrue(OpenCineLogSourcePath.SDR_BT709_ISP.acceptsDataSpace(jfif))
        assertTrue(OpenCineLogSourcePath.SDR_BT709_ISP.acceptsDataSpace(0))
        assertFalse(OpenCineLogSourcePath.SDR_BT709_ISP.acceptsDataSpace(bt2020HlgFull))
        assertFalse(OpenCineLogSourcePath.SDR_BT709_ISP.acceptsDataSpace(bt2020PqFull))
    }

    @Test
    fun thermalSuspensionSkipsScopeReadbackAndResumesOnCadence() {
        assertTrue(scopeAnalysisDue(suspended = false, nowMs = 1_100, lastAnalysisAtMs = 1_000, periodMs = 100))
        assertEquals(false, scopeAnalysisDue(suspended = false, nowMs = 1_099, lastAnalysisAtMs = 1_000, periodMs = 100))
        assertEquals(false, scopeAnalysisDue(suspended = true, nowMs = 9_000, lastAnalysisAtMs = 1_000, periodMs = 100))
        assertTrue(scopeAnalysisDue(suspended = false, nowMs = 9_000, lastAnalysisAtMs = 1_000, periodMs = 100))
    }

    @Test
    fun hlgDecodeThenOcLog2MatchesReferenceCurve() {
        val hlgCodes = listOf(0.0, 0.25, 0.5, 0.75, 1.0)
        val results = hlgCodes.map { OpenCineLog2Curve.encode(inverseHlg(it)) }
        assertEquals(OpenCineLog2Curve.BLACK_CODE, results.first(), 1e-9)
        assertEquals(OpenCineLog2Curve.WHITE_CODE, results.last(), 1e-9)
        assertTrue(results.zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun monitoringAssistIsNumericallySeparateFromFlatLog() {
        val linear = inverseHlg(0.75)
        val flat = OpenCineLog2Curve.encode(linear)
        val assisted = rec709Oetf(OpenCineLog2Curve.decode(flat))
        assertNotEquals(flat, assisted, 1e-3)
        assertEquals(linear, OpenCineLog2Curve.decode(flat), 1e-9)
    }

    @Test
    fun ocLog2ReservesBlackPedestalAndHighlightHeadroom() {
        assertEquals(0.10, OpenCineLog2Curve.encode(0.0), 1e-12)
        assertEquals(0.90, OpenCineLog2Curve.encode(1.0), 1e-12)
        listOf(0.0, 0.01, 0.18, 0.5, 1.0).forEach { linear ->
            assertEquals(linear, OpenCineLog2Curve.decode(OpenCineLog2Curve.encode(linear)), 1e-9)
        }
    }

    @Test
    fun ispHfrBranchDisclosesStandardRangeAndNeverClaimsTenBitSource() {
        val source = OpenCineLogSourcePath.SDR_BT709_ISP
        assertTrue(source.highSpeedDerived)
        assertEquals("STANDARD", source.dynamicRange)
        assertEquals("BT709_ASSUMED", source.colorSpace)
        assertTrue(source.sourcePrecision.contains("not claimed"))
        assertTrue(!OpenCineLogSourcePath.HLG10_BT2020.highSpeedDerived)
        assertThrows(IllegalArgumentException::class.java) {
            Camera2LogProfile(Size(1920, 1080), 120, OpenCineLogSourcePath.HLG10_BT2020, true)
        }
        Camera2LogProfile(Size(1920, 1080), 120, OpenCineLogSourcePath.SDR_BT709_ISP, true)
    }

    @Test
    fun runtimeCapabilityDoesNotImplyQualification() {
        val experimental = Camera2LogProfile(
            Size(1920, 1080),
            30,
            OpenCineLogSourcePath.HLG10_BT2020,
            false,
        )
        val descriptor = descriptorWith(listOf(experimental))

        assertTrue(descriptor.supportsOpenCineLog)
        assertTrue(!descriptor.hasVerifiedOpenCineLog)
        assertTrue(!descriptor.allOpenCineLogProfilesVerified)
        assertEquals(OpenCineLogQualificationStage.EXPERIMENTAL, experimental.qualificationStage)
        assertEquals(OpenCineLogQualificationPolicy.NOT_RUN_REASON, experimental.qualificationReason)
    }

    @Test
    fun verifiedProfileRequiresExactEvidenceIdentity() {
        assertThrows(IllegalArgumentException::class.java) {
            Camera2LogProfile(
                Size(1920, 1080),
                30,
                OpenCineLogSourcePath.HLG10_BT2020,
                false,
                qualificationStage = OpenCineLogQualificationStage.VERIFIED,
            )
        }
        val verified = Camera2LogProfile(
            Size(1920, 1080),
            30,
            OpenCineLogSourcePath.HLG10_BT2020,
            false,
            qualificationStage = OpenCineLogQualificationStage.VERIFIED,
            qualificationReason = "qualified-exact-tuple",
            qualificationEvidenceId = "plan-061/device-0/1920x1080-30-hlg10",
        )
        assertTrue(verified.isVerified)
        val verifiedDescriptor = descriptorWith(listOf(verified))
        assertTrue(verifiedDescriptor.hasVerifiedOpenCineLog)
        assertTrue(verifiedDescriptor.allOpenCineLogProfilesVerified)
        assertTrue(!descriptorWith(listOf(verified, Camera2LogProfile(
            Size(1280, 720),
            30,
            OpenCineLogSourcePath.HLG10_BT2020,
            false,
        ))).allOpenCineLogProfilesVerified)
    }

    @Test
    fun inverseRec709IsMonotonicBeforeOcLogEncoding() {
        val codes = listOf(0.0, 0.04, 0.081, 0.5, 1.0)
        val linear = codes.map(::inverseRec709)
        assertEquals(0.0, linear.first(), 1e-12)
        assertEquals(1.0, linear.last(), 1e-12)
        assertTrue(linear.zipWithNext().all { (a, b) -> b > a })
        assertTrue(linear.map(OpenCineLog2Curve::encode).zipWithNext().all { (a, b) -> b > a })
    }

    private fun inverseHlg(code: Double): Double = if (code <= 0.5) {
        code * code / 3.0
    } else {
        (exp((code - 0.55991073) / 0.17883277) + 0.28466892) / 12.0
    }.coerceIn(0.0, 1.0)

    private fun rec709Oetf(linear: Double): Double = if (linear < 0.018) {
        4.5 * linear
    } else {
        1.099 * linear.pow(0.45) - 0.099
    }

    private fun inverseRec709(code: Double): Double = if (code < 0.081) {
        code / 4.5
    } else {
        ((code + 0.099) / 1.099).pow(1.0 / 0.45)
    }

    private fun descriptorWith(logProfiles: List<Camera2LogProfile>) = Camera2CameraDescriptor(
        cameraId = "0",
        lensFacing = 1,
        focalLengthsMm = emptyList(),
        previewSize = Size(1920, 1080),
        jpegSize = null,
        rawSize = null,
        analysisSize = null,
        sensorOrientation = 90,
        sensitivityRange = null,
        exposureTimeRangeNs = null,
        aeCompensationRange = null,
        aeCompensationStep = 0f,
        minimumFocusDistance = null,
        supportsRaw = false,
        flashAvailable = false,
        targetFpsRanges = emptyList(),
        availableFixedFps = emptyList(),
        videoProfiles = emptyList(),
        logProfiles = logProfiles,
    )
}
