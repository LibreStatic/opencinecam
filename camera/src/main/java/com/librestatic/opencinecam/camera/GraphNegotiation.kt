/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import com.librestatic.opencinecam.core.model.FailureCode
import com.librestatic.opencinecam.core.model.FailureSeverity
import com.librestatic.opencinecam.core.model.GraphId
import com.librestatic.opencinecam.core.model.Knowledge
import com.librestatic.opencinecam.core.model.Recoverability
import com.librestatic.opencinecam.core.model.StableFailure

data class GraphOutput(
    val id: String,
    val kind: Kind,
    val size: StreamSize,
    val required: Boolean = true,
) {
    enum class Kind { PREVIEW, ENCODER, RAW, ANALYSIS }

    init {
        require(id.isNotBlank()) { "graph output ID must not be blank" }
    }
}

data class CaptureGraphRequest(
    val graphId: GraphId,
    val cameraId: String,
    val physicalCameraId: String?,
    val outputs: List<GraphOutput>,
    val fps: Int,
    val dynamicRange: String = "SDR",
    val colorSpace: String? = null,
) {
    init {
        require(cameraId.isNotBlank()) { "camera ID must not be blank" }
        require(physicalCameraId == null || physicalCameraId.isNotBlank()) {
            "physical camera ID must not be blank"
        }
        require(outputs.isNotEmpty()) { "graph must have at least one output" }
        require(outputs.map { it.id }.distinct().size == outputs.size) { "graph output IDs must be unique" }
        require(fps > 0) { "graph FPS must be positive" }
        require(dynamicRange.isNotBlank()) { "dynamic range must not be blank" }
    }
}

data class ResolvedCaptureGraph(
    val request: CaptureGraphRequest,
    val outputs: List<GraphOutput>,
)

sealed interface GraphNegotiationOutcome {
    data class Accepted(val graph: ResolvedCaptureGraph) : GraphNegotiationOutcome
    data class Rejected(val failure: StableFailure) : GraphNegotiationOutcome
}

class GraphNegotiator(private val correlationId: String = "graph-negotiation") {
    fun negotiate(request: CaptureGraphRequest, camera: CameraIdentity, streams: StreamCapabilityReport): GraphNegotiationOutcome {
        if (camera.cameraId != request.cameraId || streams.cameraId != request.cameraId) {
            return rejected(FailureCode.ROUTE_MISMATCH, "Graph camera does not match probed camera.")
        }
        request.physicalCameraId?.let { physicalId ->
            if (physicalId !in camera.publicPhysicalIds) {
                return rejected(
                    FailureCode.ROUTE_MISMATCH,
                    "Requested physical camera route is not publicly available.",
                    mapOf("physicalCameraId" to physicalId),
                )
            }
        }
        for (output in request.outputs) {
            val sizes = when (output.kind) {
                GraphOutput.Kind.RAW -> streams.rawSizes
                GraphOutput.Kind.PREVIEW, GraphOutput.Kind.ENCODER, GraphOutput.Kind.ANALYSIS -> streams.previewSizes
            }
            when (sizes) {
                Knowledge.Unknown -> return rejected(FailureCode.UNKNOWN_CAPABILITY, "Required ${output.kind} sizes are unknown.")
                is Knowledge.Unsupported -> return rejected(FailureCode.UNSUPPORTED_CAPABILITY, sizes.reason)
                is Knowledge.Known -> if (output.size !in sizes.value) {
                    return rejected(FailureCode.UNSUPPORTED_CAPABILITY, "Requested ${output.kind} size is unsupported.")
                }
            }
        }
        when (val ranges = streams.targetFpsRanges) {
            Knowledge.Unknown -> return rejected(FailureCode.UNKNOWN_CAPABILITY, "Target FPS ranges are unknown.")
            is Knowledge.Unsupported -> return rejected(FailureCode.UNSUPPORTED_CAPABILITY, ranges.reason)
            is Knowledge.Known -> if (ranges.value.none { request.fps in it }) {
                return rejected(FailureCode.UNSUPPORTED_CAPABILITY, "Requested FPS is outside advertised ranges.")
            }
        }
        if (request.dynamicRange != "SDR") {
            when (val profiles = streams.dynamicRangeProfiles) {
                Knowledge.Unknown -> return rejected(FailureCode.UNKNOWN_CAPABILITY, "Dynamic-range profiles are unknown.")
                is Knowledge.Unsupported -> return rejected(FailureCode.UNSUPPORTED_CAPABILITY, profiles.reason)
                is Knowledge.Known -> if (request.dynamicRange !in profiles.value) {
                    return rejected(FailureCode.UNSUPPORTED_CAPABILITY, "Requested dynamic range is unsupported.")
                }
            }
        }
        request.colorSpace?.let { colorSpace ->
            when (val profiles = streams.colorSpaceProfiles) {
                Knowledge.Unknown -> return rejected(FailureCode.UNKNOWN_CAPABILITY, "Color-space profiles are unknown.")
                is Knowledge.Unsupported -> return rejected(FailureCode.UNSUPPORTED_CAPABILITY, profiles.reason)
                is Knowledge.Known -> if (colorSpace !in profiles.value) {
                    return rejected(FailureCode.UNSUPPORTED_CAPABILITY, "Requested color space is unsupported.")
                }
            }
        }
        return GraphNegotiationOutcome.Accepted(ResolvedCaptureGraph(request, request.outputs.toList()))
    }

    private fun rejected(code: FailureCode, message: String, details: Map<String, String> = emptyMap()) =
        GraphNegotiationOutcome.Rejected(
            StableFailure(
                component = "graph-negotiation",
                code = code,
                severity = if (code == FailureCode.UNKNOWN_CAPABILITY) FailureSeverity.WARNING else FailureSeverity.ERROR,
                recoverability = when (code) {
                    FailureCode.UNKNOWN_CAPABILITY -> Recoverability.USER_ACTION
                    FailureCode.UNSUPPORTED_CAPABILITY -> Recoverability.UNSUPPORTED
                    else -> Recoverability.USER_ACTION
                },
                correlationId = correlationId,
                userMessage = message,
                details = details,
            ),
        )
}

enum class GraphNegotiationPolicy { STRICT, ADAPTIVE }

fun interface GraphCancellation {
    fun isCancelled(): Boolean
}

object GraphNeverCancelled : GraphCancellation {
    override fun isCancelled(): Boolean = false
}

interface GraphSession : AutoCloseable {
    fun activePhysicalCameraId(): Knowledge<String>
}

fun interface GraphSessionFactory {
    fun create(graph: ResolvedCaptureGraph): GraphSession
}

enum class GraphSessionStage { NEGOTIATED, SESSION_CREATED, PROMOTED, FAILED, CANCELLED }

data class GraphSessionResult(
    val stage: GraphSessionStage,
    val graph: ResolvedCaptureGraph? = null,
    val session: GraphSession? = null,
    val activePhysicalCameraId: Knowledge<String> = Knowledge.Unknown,
    val disclosure: String? = null,
    val failure: StableFailure? = null,
)

/** Creates a session only after complete graph validation, then audits the active physical route. */
class GraphSessionController(
    private val negotiator: GraphNegotiator = GraphNegotiator(),
    private val policy: GraphNegotiationPolicy = GraphNegotiationPolicy.STRICT,
    private val correlationId: String = "graph-session",
) {
    fun create(
        request: CaptureGraphRequest,
        camera: CameraIdentity,
        streams: StreamCapabilityReport,
        factory: GraphSessionFactory,
        cancellation: GraphCancellation = GraphNeverCancelled,
    ): GraphSessionResult {
        if (cancellation.isCancelled()) {
            return GraphSessionResult(
                stage = GraphSessionStage.CANCELLED,
                failure = failure(FailureCode.CANCELLATION, Recoverability.CANCELLED, "Graph creation was cancelled."),
            )
        }
        return when (val negotiation = negotiator.negotiate(request, camera, streams)) {
            is GraphNegotiationOutcome.Rejected -> GraphSessionResult(GraphSessionStage.FAILED, failure = negotiation.failure)
            is GraphNegotiationOutcome.Accepted -> createSession(negotiation.graph, factory, cancellation)
        }
    }

    private fun createSession(
        graph: ResolvedCaptureGraph,
        factory: GraphSessionFactory,
        cancellation: GraphCancellation,
    ): GraphSessionResult {
        if (cancellation.isCancelled()) {
            return GraphSessionResult(GraphSessionStage.CANCELLED, graph, failure = failure(
                FailureCode.CANCELLATION,
                Recoverability.CANCELLED,
                "Graph creation was cancelled.",
            ))
        }
        val session = try {
            factory.create(graph)
        } catch (_: UnsupportedOperationException) {
            return GraphSessionResult(GraphSessionStage.FAILED, graph, failure = failure(
                FailureCode.UNSUPPORTED_CAPABILITY,
                Recoverability.UNSUPPORTED,
                "The requested graph session is unsupported.",
            ))
        } catch (_: Throwable) {
            return GraphSessionResult(GraphSessionStage.FAILED, graph, failure = failure(
                FailureCode.SESSION_CONFIGURATION_FAILED,
                Recoverability.RETRYABLE,
                "The graph session could not be configured.",
            ))
        }
        val active = runCatching { session.activePhysicalCameraId() }.getOrElse {
            session.close()
            return GraphSessionResult(GraphSessionStage.FAILED, graph, failure = failure(
                FailureCode.CAPTURE_OPEN_FAILED,
                Recoverability.RETRYABLE,
                "The active physical route could not be audited.",
            ))
        }
        val requestedPhysical = graph.request.physicalCameraId
        if (requestedPhysical != null) {
            when (active) {
                Knowledge.Unknown -> if (policy == GraphNegotiationPolicy.STRICT) {
                    session.close()
                    return GraphSessionResult(
                        stage = GraphSessionStage.FAILED,
                        graph = graph,
                        activePhysicalCameraId = active,
                        failure = failure(
                        FailureCode.ROUTE_MISMATCH,
                        Recoverability.USER_ACTION,
                        "The required physical route could not be verified.",
                        ),
                    )
                }
                is Knowledge.Unsupported -> {
                    session.close()
                    return GraphSessionResult(
                        stage = GraphSessionStage.FAILED,
                        graph = graph,
                        activePhysicalCameraId = active,
                        failure = failure(
                        FailureCode.UNSUPPORTED_CAPABILITY,
                        Recoverability.UNSUPPORTED,
                        active.reason,
                        ),
                    )
                }
                is Knowledge.Known -> if (active.value != requestedPhysical) {
                    session.close()
                    return GraphSessionResult(
                        stage = GraphSessionStage.FAILED,
                        graph = graph,
                        activePhysicalCameraId = active,
                        failure = failure(
                        FailureCode.ROUTE_MISMATCH,
                        Recoverability.USER_ACTION,
                        "The active physical route differs from the requested route.",
                        ),
                    )
                }
            }
        }
        return GraphSessionResult(
            stage = GraphSessionStage.PROMOTED,
            graph = graph,
            session = session,
            activePhysicalCameraId = active,
            disclosure = if (requestedPhysical != null && active is Knowledge.Unknown) {
                "Physical route is not reported; recording uses the selected logical camera."
            } else {
                null
            },
        )
    }

    private fun failure(code: FailureCode, recoverability: Recoverability, message: String) = StableFailure(
        component = "graph-session",
        code = code,
        severity = if (code == FailureCode.UNSUPPORTED_CAPABILITY) FailureSeverity.WARNING else FailureSeverity.ERROR,
        recoverability = recoverability,
        correlationId = correlationId,
        userMessage = message,
    )
}
