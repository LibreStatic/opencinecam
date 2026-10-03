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
    enum class Kind { PREVIEW, ENCODER, RAW, ANALYSIS, STILL }

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
                // JPEG/HEIC sizes come from their own format lists, which the probe does not report.
                GraphOutput.Kind.STILL -> continue
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

    /**
     * Ordered fallback graphs for one session: [request] unchanged first, then with its optional
     * (`required = false`) outputs dropped cumulatively in [dropOrder]. A candidate is skipped only
     * when the probe positively excludes one of its sizes; Unknown (or a missing report) allows it,
     * so no graph that opens today is rejected up front. Only ANALYSIS (YUV_420_888 list) and RAW
     * sizes are checked: the preview surface is PRIVATE and still sizes come from their own format
     * lists, neither of which the probe reports. If every candidate is excluded the last one is kept
     * so the caller still makes its real attempt and reports its usual failure.
     */
    fun fallbackGraphs(
        request: CaptureGraphRequest,
        streams: StreamCapabilityReport?,
        dropOrder: List<GraphOutput.Kind>,
    ): List<CaptureGraphRequest> {
        val candidates = mutableListOf(request)
        var outputs = request.outputs
        for (kind in dropOrder) {
            val kept = outputs.filterNot { it.kind == kind && !it.required }
            if (kept.size == outputs.size) continue
            outputs = kept
            candidates += request.copy(outputs = kept)
        }
        return candidates.filter { admits(it, streams) }.ifEmpty { listOf(candidates.last()) }
    }

    private fun admits(request: CaptureGraphRequest, streams: StreamCapabilityReport?): Boolean {
        if (streams == null || streams.cameraId != request.cameraId) return true
        return request.outputs.all { output ->
            val sizes = when (output.kind) {
                GraphOutput.Kind.ANALYSIS -> streams.previewSizes
                GraphOutput.Kind.RAW -> streams.rawSizes
                else -> return@all true
            }
            when (sizes) {
                Knowledge.Unknown -> true
                is Knowledge.Unsupported -> false
                is Knowledge.Known -> output.size in sizes.value
            }
        }
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

/** Drop order of Camera2PreviewEngine.configureSession: scopes first, then DNG; preview and still stay. */
val PREVIEW_GRAPH_DROP_ORDER = listOf(GraphOutput.Kind.ANALYSIS, GraphOutput.Kind.RAW)

/**
 * The regular preview graph exactly as Camera2PreviewEngine.configureSession assembles it (the
 * HFR and LOG graphs are built elsewhere). Photo graphs carry [still] (JPEG or HEIC) and, unless
 * HEIC, [raw]; video graphs carry neither. [analysis] is the optional YUV scope stream. Output IDs
 * are the keys the engine maps back to its OutputConfigurations.
 */
fun previewGraphRequest(
    cameraId: String,
    fps: Int,
    preview: StreamSize,
    still: StreamSize?,
    raw: StreamSize?,
    analysis: StreamSize?,
): CaptureGraphRequest = CaptureGraphRequest(
    graphId = GraphId("preview-$cameraId"),
    cameraId = cameraId,
    physicalCameraId = null,
    outputs = listOfNotNull(
        GraphOutput(PREVIEW_GRAPH_PREVIEW, GraphOutput.Kind.PREVIEW, preview),
        still?.let { GraphOutput(PREVIEW_GRAPH_STILL, GraphOutput.Kind.STILL, it) },
        raw?.let { GraphOutput(PREVIEW_GRAPH_RAW, GraphOutput.Kind.RAW, it, required = false) },
        analysis?.let { GraphOutput(PREVIEW_GRAPH_ANALYSIS, GraphOutput.Kind.ANALYSIS, it, required = false) },
    ),
    fps = fps,
)

const val PREVIEW_GRAPH_PREVIEW = "preview"
const val PREVIEW_GRAPH_STILL = "still"
const val PREVIEW_GRAPH_RAW = "raw"
const val PREVIEW_GRAPH_ANALYSIS = "analysis"

/**
 * Walks the fallback graphs of Camera2PreviewEngine.configureSession. [attempt] configures graph
 * `index` and submits its session, returning false when the session pre-check ruled it out
 * ([skipBySessionPreCheck]). Everything it does may throw: CameraService rejects the graph, or a
 * retry re-wraps a preview surface the UI abandoned meanwhile (a density or rotation change racing
 * the camera open). A throw moves on to the next graph and, after the last one, is reported.
 * Nothing escapes [start] or [configureFailed]: both run on the camera executor (onOpened,
 * onConfigureFailed), where an uncaught exception kills the process.
 */
class PreviewGraphFallback(
    private val graphCount: Int,
    private val ownsGraph: () -> Boolean,
    private val onRetry: (rejected: Int, reason: String) -> Unit,
    private val onFailure: (code: String, message: String) -> Unit,
    private val attempt: PreviewGraphFallback.(index: Int) -> Boolean,
) {
    fun start() = run(0)

    fun hasFallback(index: Int): Boolean = index + 1 < graphCount

    /** CameraCaptureSession.StateCallback.onConfigureFailed for graph [index]. */
    fun configureFailed(index: Int) {
        if (!ownsGraph()) return
        if (hasFallback(index)) retry(index, "configure failed")
        else onFailure("preview-session-failed", "Camera preview configuration failed.")
    }

    private fun run(index: Int) {
        val submitted = try {
            attempt(index)
        } catch (failure: Exception) {
            if (!ownsGraph()) return
            if (hasFallback(index)) retry(index, failure.message ?: failure.javaClass.simpleName)
            else onFailure("preview-session-exception", failure.message ?: "Camera preview configuration failed.")
            return
        }
        if (!submitted) retry(index, "isSessionConfigurationSupported=false")
    }

    private fun retry(rejected: Int, reason: String) {
        onRetry(rejected, reason)
        run(rejected + 1)
    }
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
