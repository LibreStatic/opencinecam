/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import com.librestatic.opencinecam.core.model.FailureCode
import com.librestatic.opencinecam.core.model.GraphId
import com.librestatic.opencinecam.core.model.Knowledge
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphNegotiationTest {
    @Test
    fun completeGraphCreatesSessionAndPromotesKnownPhysicalRoute() {
        val session = FakeSession(Knowledge.Known("physical-0"))
        val result = GraphSessionController().create(request(), camera(), streams(), GraphSessionFactory { session })

        assertEquals(GraphSessionStage.PROMOTED, result.stage)
        assertEquals("physical-0", (result.activePhysicalCameraId as Knowledge.Known).value)
        assertNotNull(result.session)
        assertNull(result.failure)
    }

    @Test
    fun unknownOrUnsupportedCapabilitiesAreNotPromoted() {
        val unknown = streams().copy(targetFpsRanges = Knowledge.Unknown)
        val unknownResult = GraphSessionController().create(request(), camera(), unknown, GraphSessionFactory { error("must not create") })
        assertEquals(FailureCode.UNKNOWN_CAPABILITY, unknownResult.failure?.code)

        val unsupported = streams().copy(previewSizes = Knowledge.Known(listOf(StreamSize(640, 480))))
        val unsupportedResult = GraphSessionController().create(request(), camera(), unsupported, GraphSessionFactory { error("must not create") })
        assertEquals(FailureCode.UNSUPPORTED_CAPABILITY, unsupportedResult.failure?.code)
    }

    @Test
    fun strictRejectsUnknownActiveRouteAdaptiveDisclosesLogicalPromotion() {
        val strict = GraphSessionController(policy = GraphNegotiationPolicy.STRICT)
        val strictSession = FakeSession(Knowledge.Unknown)
        val strictResult = strict.create(request(), camera(), streams(), GraphSessionFactory { strictSession })
        assertEquals(GraphSessionStage.FAILED, strictResult.stage)
        assertEquals(FailureCode.ROUTE_MISMATCH, strictResult.failure?.code)
        assertTrue(strictSession.closed)

        val adaptive = GraphSessionController(policy = GraphNegotiationPolicy.ADAPTIVE)
        val adaptiveResult = adaptive.create(request(), camera(), streams(), GraphSessionFactory { FakeSession(Knowledge.Unknown) })
        assertEquals(GraphSessionStage.PROMOTED, adaptiveResult.stage)
        assertTrue(adaptiveResult.disclosure!!.contains("not reported"))
    }

    @Test
    fun mismatchedRouteClosesSessionAndCancellationSkipsCreation() {
        val mismatched = FakeSession(Knowledge.Known("physical-1"))
        val result = GraphSessionController().create(request(), camera(), streams(), GraphSessionFactory { mismatched })
        assertEquals(FailureCode.ROUTE_MISMATCH, result.failure?.code)
        assertTrue(mismatched.closed)

        val created = AtomicBoolean(false)
        val cancelled = GraphSessionController().create(
            request(),
            camera(),
            streams(),
            GraphSessionFactory { created.set(true); FakeSession(Knowledge.Known("physical-0")) },
            GraphCancellation { true },
        )
        assertEquals(GraphSessionStage.CANCELLED, cancelled.stage)
        assertTrue(!created.get())
    }

    private fun request() = CaptureGraphRequest(
        graphId = GraphId("graph-1"),
        cameraId = "0",
        physicalCameraId = "physical-0",
        outputs = listOf(
            GraphOutput("preview", GraphOutput.Kind.PREVIEW, StreamSize(1920, 1080)),
            GraphOutput("raw", GraphOutput.Kind.RAW, StreamSize(1920, 1080)),
        ),
        fps = 30,
        dynamicRange = "SDR",
    )

    private fun camera() = CameraIdentity(
        cameraId = "0",
        lensFacing = Knowledge.Known("back"),
        hardwareLevel = Knowledge.Known("full"),
        activeArray = Knowledge.Unknown,
        focalLengthsMillimeters = Knowledge.Known(listOf(4f)),
        publicPhysicalIds = setOf("physical-0", "physical-1"),
        capabilities = setOf("logical-multi-camera"),
    )

    private fun streams() = StreamCapabilityReport(
        cameraId = "0",
        previewSizes = Knowledge.Known(listOf(StreamSize(1920, 1080))),
        rawSizes = Knowledge.Known(listOf(StreamSize(1920, 1080))),
        highSpeedSizes = Knowledge.Unknown,
        highSpeedFpsRanges = Knowledge.Unknown,
        targetFpsRanges = Knowledge.Known(listOf(24..60)),
        manualSensor = Knowledge.Known(true),
        manualPostProcessing = Knowledge.Known(true),
        dynamicRangeProfiles = Knowledge.Known(setOf("SDR")),
        colorSpaceProfiles = Knowledge.Known(setOf("BT2020")),
    )

    private class FakeSession(private val active: Knowledge<String>) : GraphSession {
        var closed = false
        override fun activePhysicalCameraId(): Knowledge<String> = active
        override fun close() {
            closed = true
        }
    }
}
