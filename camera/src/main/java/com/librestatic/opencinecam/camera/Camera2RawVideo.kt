/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import com.librestatic.opencinecam.core.model.QuirkEffect
import android.util.Log
import android.util.Size
import android.view.Surface
import com.librestatic.opencinecam.camera.Camera2PreviewEngine.RawTake
import com.librestatic.opencinecam.camera.Camera2PreviewEngine.RawVideoStream
import com.librestatic.opencinecam.camera.Camera2PreviewEngine.Companion.TAG

/**
 * cameraExecutor: the RAW10 reader of a RAW_VIDEO session, after the graph pre-check. Null (and
 * reported through [Camera2PreviewListener.onRawVideoUnavailable]) when the camera cannot carry
 * [config]. Any stream left from an earlier session of this camera is retired first.
 */
internal fun Camera2PreviewEngine.openRawVideoStream(descriptor: Camera2CameraDescriptor, config: RawVideoSessionConfig): RawVideoStream? {
    rawVideoStream?.let { retireRawVideoStream(it, RawVideoReasons.SESSION_CLOSED) }
    val owner = listener ?: return null
    val characteristics = try {
        manager.getCameraCharacteristics(descriptor.cameraId)
    } catch (failure: Exception) {
        rawVideoUnavailable("Camera ${descriptor.cameraId} characteristics are unavailable: ${failure.message}")
        return null
    }
    val streamMap = runCatching { characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) }.getOrNull()
    val raw10Sizes = runCatching {
        streamMap?.getOutputSizes(ImageFormat.RAW10)?.map { it.width to it.height }
    }.getOrNull().orEmpty()
    val fpsRanges = descriptor.targetFpsRanges.map { it.lower to it.upper }
    val declaredMinFrameDurationNs = runCatching {
        streamMap?.getOutputMinFrameDuration(ImageFormat.RAW10, Size(config.rawWidth, config.rawHeight))
    }.getOrNull()?.takeIf { it > 0 }
    val qtiOverride = deviceQuirks.resolve(descriptor.quirkCameraId).applies(QuirkEffect.QTI_RAW_FRAME_RATE_OVERRIDE)
    val decision = rawVideoGraphDecision(config, raw10Sizes, fpsRanges, declaredMinFrameDurationNs, qtiOverride)
    if (decision is RawVideoGraphDecision.Rejected) {
        Log.w(TAG, "RAW video graph rejected on camera ${descriptor.cameraId}: ${decision.detail}")
        rawVideoUnavailable(decision.detail)
        return null
    }
    val reader = try {
        ImageReader.newInstance(config.rawWidth, config.rawHeight, ImageFormat.RAW10, RAW_VIDEO_MAX_IMAGES)
    } catch (failure: RuntimeException) {
        rawVideoUnavailable("The RAW10 reader could not be created: ${failure.message}")
        return null
    }
    val stream = RawVideoStream(
        config, reader, reader.surface, descriptor.cameraId, characteristics, descriptor, owner, declaredMinFrameDurationNs, qtiOverride,
    )
    reader.setOnImageAvailableListener({ drainRawVideoReader(stream) }, rawVideoHandler)
    rawVideoStream = stream
    val plan = rawVideoFrameRatePlan(fpsRanges, config.fps, declaredMinFrameDurationNs, qtiOverride)
    Log.i(TAG, "RAW video stream ${config.rawWidth}x${config.rawHeight} @${config.fps} on camera ${descriptor.cameraId}, " +
        "graph range ${(decision as RawVideoGraphDecision.Accepted).fpsRange}, AE range ${plan.aeRange}, " +
        "declared min frame $declaredMinFrameDurationNs ns, QTI override ${plan.vendorFrameDurationNs ?: "off"}")
    return stream
}

/** cameraExecutor: reports the RAW_VIDEO graph as unavailable; RAW is never silently dropped. */
internal fun Camera2PreviewEngine.rawVideoUnavailable(message: String) {
    listener?.onRawVideoUnavailable(RawVideoReasons.GRAPH_REJECTED, message)
}

/**
 * cameraExecutor: unbinds [stream]; its take, if any, ends with [reason] as a stream failure,
 * and the reader closes on the RAW thread after any consumer call in progress. Idempotent.
 */
internal fun Camera2PreviewEngine.retireRawVideoStream(stream: RawVideoStream, reason: String) {
    stream.live = false
    if (rawVideoStream !== stream) return
    rawVideoStream = null
    stream.session = null
    val retire = Runnable {
        rawTake.get()?.takeIf { it.stream === stream }?.let { endRawTake(it, reason, streamFailure = true) }
        stream.reader.setOnImageAvailableListener(null, null)
        runCatching { stream.reader.close() }
    }
    if (!rawVideoHandler.post(retire)) retire.run()
}

/** Camera loss ends a RAW take with [reason] before any generic failure handling runs. */
internal fun Camera2PreviewEngine.endRawTakeOnCameraLoss(reason: String) {
    val stream = rawVideoStream ?: return
    stream.live = false
    val take = rawTake.get()?.takeIf { it.stream === stream } ?: return
    rawVideoHandler.post { endRawTake(take, reason, streamFailure = true) }
}

/**
 * RAW thread: drains [stream]'s reader in order. A take's frames go to its consumer, which copies
 * them; every image is closed right after, and between takes at once. Never acquireLatestImage:
 * a take must see every frame (ADR-0019).
 */
internal fun Camera2PreviewEngine.drainRawVideoReader(stream: RawVideoStream) {
    while (true) {
        val image = try { stream.reader.acquireNextImage() } catch (_: IllegalStateException) { null } ?: return
        val take = rawTake.get()?.takeIf { it.stream === stream }
        if (take == null) { image.close(); continue }
        val timestampNs = image.timestamp
        val outcome = try {
            take.state.onFrame(timestampNs) { take.consumer.onRawFrame(image) }
        } finally {
            image.close()
        }
        when (outcome) {
            is RawVideoTakeState.FrameOutcome.Delivered -> if (outcome.first) {
                runCatching { take.stream.listener.onRawVideoFirstFrame(timestampNs) }
            }
            is RawVideoTakeState.FrameOutcome.Ended -> settleRawTake(take, outcome.ending)
            RawVideoTakeState.FrameOutcome.Discarded -> Unit
        }
    }
}

/**
 * RAW thread: ends [take] with [reason] unless its ending is already decided; a stream failure or
 * a consumer fault also cuts a pending result drain short (see [RawVideoTakeState]).
 */
internal fun Camera2PreviewEngine.endRawTake(take: RawTake, reason: String?, streamFailure: Boolean) {
    val ending = take.state.end(reason, streamFailure) ?: return
    settleRawTake(take, ending)
}

/**
 * RAW thread: [take]'s ending was just decided. It finishes now, or, while it drains the results
 * of its delivered frames, when [forwardRawResult] sees the last one or the drain times out.
 * [take] stays in [rawTake] meanwhile, so no other take starts and no frame is taken.
 */
internal fun Camera2PreviewEngine.settleRawTake(take: RawTake, ending: RawTakeEnding) {
    if (take.state.settled) {
        finishRawTake(take, ending)
        return
    }
    Log.i(TAG, "RAW take ending: reason=${ending.reason ?: "stop"} frames=${take.state.framesDelivered}; draining results")
    // A no-op once the drain is over: drainTimedOut then returns null.
    rawVideoHandler.postDelayed({ take.state.drainTimedOut()?.let { finishRawTake(take, it) } }, RAW_TAKE_RESULT_DRAIN_TIMEOUT_MS)
}

/** RAW thread: [take] is over; called exactly once, when its state settled. The consumer hears nothing after. */
internal fun Camera2PreviewEngine.finishRawTake(take: RawTake, ending: RawTakeEnding) {
    rawTake.compareAndSet(take, null)
    Log.i(TAG, "RAW take ended: reason=${ending.reason ?: "stop"} frames=${take.state.framesDelivered}")
    if (ending.notifyStreamFailure) {
        runCatching { take.consumer.onRawStreamFailed(requireNotNull(ending.reason)) }
            .onFailure { Log.w(TAG, "RAW consumer failed in onRawStreamFailed", it) }
    }
    runCatching { take.stream.listener.onRawVideoStopped(ending.reason) }
}

/**
 * cameraExecutor: hands a repeating-request result to the take on the RAW thread, while it is
 * active and, once it ended, while it drains the results of the frames it delivered; the result
 * of its last frame (or a later one) finishes it.
 */
internal fun Camera2PreviewEngine.forwardRawResult(result: TotalCaptureResult) {
    val take = rawTake.get() ?: return
    rawVideoHandler.post {
        if (rawTake.get() !== take) return@post
        val sensorTimestampNs = result.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP)
        when (val outcome = take.state.onResult(sensorTimestampNs)) {
            RawVideoTakeState.ResultOutcome.Discarded -> Unit
            is RawVideoTakeState.ResultOutcome.Finished -> finishRawTake(take, outcome.ending)
            is RawVideoTakeState.ResultOutcome.Forward -> {
                val failed = try {
                    take.consumer.onRawResult(result)
                    false
                } catch (failure: Exception) {
                    Log.w(TAG, "RAW consumer failed in onRawResult", failure)
                    true
                }
                when {
                    outcome.finish != null -> finishRawTake(take, outcome.finish)
                    failed -> endRawTake(take, RawVideoReasons.CONSUMER_FAILED, streamFailure = false)
                }
            }
        }
    }
}

/** cameraExecutor: a lost buffer on the RAW10 surface is a missing frame, which ends the take. */
internal fun Camera2PreviewEngine.noteRawBufferLost(target: Surface) {
    val stream = rawVideoStream ?: return
    if (target !== stream.surface) return
    val take = rawTake.get()?.takeIf { it.stream === stream } ?: return
    Log.w(TAG, "RAW10 buffer lost during a take")
    rawVideoHandler.post { endRawTake(take, RawVideoReasons.FRAME_LOST, streamFailure = true) }
}

/**
 * PLAN-076: the viewfinder stream of a graph without a video or LOG profile: in a RAW_VIDEO session
 * [Camera2CameraDescriptor.rawVideoViewfinderSize] (the RAW output's aspect, so the viewfinder shows its
 * whole field), in every other mode [Camera2CameraDescriptor.previewSize].
 */
internal fun Camera2PreviewEngine.baseViewfinderSize(descriptor: Camera2CameraDescriptor): Size =
    if (activeRawVideo != null) descriptor.rawVideoViewfinderSize else descriptor.previewSize

/** The RAW_VIDEO frame-rate plan for [descriptor] at the requested rate (PLAN-071). */
internal fun Camera2PreviewEngine.rawVideoFrameRatePlanFor(descriptor: Camera2CameraDescriptor): RawVideoFrameRatePlan {
    val stream = rawVideoStream?.takeIf { it.cameraId == descriptor.cameraId }
    return rawVideoFrameRatePlan(
        descriptor.targetFpsRanges.map { it.lower to it.upper },
        requestedTargetFps,
        stream?.declaredMinFrameDurationNs,
        stream?.qtiFrameRateOverride == true,
    )
}
