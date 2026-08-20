/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.core.model

enum class PreviewScaleMode { FIT, CROP }

data class PreviewTransform(
    val scaleX: Float,
    val scaleY: Float,
    val rotationDegrees: Int,
    val mirrorX: Boolean,
) {
    init {
        require(scaleX > 0f && scaleY > 0f) { "preview scale must be positive" }
        require(rotationDegrees in 0..359 && rotationDegrees % 90 == 0) {
            "preview rotation must be a normalized right angle"
        }
    }
}

/** Computes only the view transform; recording orientation and metadata remain separate. */
fun calculatePreviewTransform(
    sourceWidth: Int,
    sourceHeight: Int,
    viewWidth: Int,
    viewHeight: Int,
    scaleMode: PreviewScaleMode,
    sensorOrientationDegrees: Int,
    displayRotationDegrees: Int,
    mirrorX: Boolean,
): PreviewTransform {
    require(sourceWidth > 0 && sourceHeight > 0 && viewWidth > 0 && viewHeight > 0) {
        "preview dimensions must be positive"
    }
    require(sensorOrientationDegrees % 90 == 0 && displayRotationDegrees % 90 == 0) {
        "preview orientations must be right angles"
    }
    val rotation = ((sensorOrientationDegrees - displayRotationDegrees) % 360 + 360) % 360
    val rotatedWidth = if (rotation == 90 || rotation == 270) sourceHeight else sourceWidth
    val rotatedHeight = if (rotation == 90 || rotation == 270) sourceWidth else sourceHeight
    val widthRatio = viewWidth.toFloat() / rotatedWidth
    val heightRatio = viewHeight.toFloat() / rotatedHeight
    val scale = when (scaleMode) {
        PreviewScaleMode.FIT -> minOf(widthRatio, heightRatio)
        PreviewScaleMode.CROP -> maxOf(widthRatio, heightRatio)
    }
    return PreviewTransform(
        scaleX = rotatedWidth * scale / viewWidth,
        scaleY = rotatedHeight * scale / viewHeight,
        rotationDegrees = rotation,
        mirrorX = mirrorX,
    )
}

data class PreviewOverlay(
    val id: String,
    val contentDescription: String,
    val normalizedLeft: Float,
    val normalizedTop: Float,
    val normalizedRight: Float,
    val normalizedBottom: Float,
) {
    init {
        require(id.isNotBlank() && contentDescription.isNotBlank()) { "overlay identity must not be blank" }
        require(normalizedLeft in 0f..1f && normalizedTop in 0f..1f)
        require(normalizedRight in 0f..1f && normalizedBottom in 0f..1f)
        require(normalizedLeft <= normalizedRight && normalizedTop <= normalizedBottom) {
            "overlay bounds must be ordered"
        }
    }
}

sealed interface PreviewSurfaceEvent {
    data class Attached(val surfaceId: String) : PreviewSurfaceEvent
    data class Ready(val surfaceId: String) : PreviewSurfaceEvent
    data class Detached(val surfaceId: String) : PreviewSurfaceEvent
    data class Rejected(val failure: StableFailure) : PreviewSurfaceEvent
}

/** Lifecycle-only surface session. It never owns camera resources or overlay state. */
class PreviewSurfaceSession(private val correlationId: String = "preview-surface") {
    private var attachedId: String? = null
    private var ready = false

    fun attach(surfaceId: String): PreviewSurfaceEvent {
        if (surfaceId.isBlank()) return reject(FailureCode.INVALID_COMMAND, "Surface ID must not be blank.")
        if (attachedId != null) return reject(FailureCode.DUPLICATE_COMMAND, "A preview surface is already attached.")
        attachedId = surfaceId
        ready = false
        return PreviewSurfaceEvent.Attached(surfaceId)
    }

    fun markReady(surfaceId: String): PreviewSurfaceEvent {
        if (attachedId != surfaceId) return reject(FailureCode.STALE_EVIDENCE, "Preview surface is no longer attached.")
        ready = true
        return PreviewSurfaceEvent.Ready(surfaceId)
    }

    fun detach(surfaceId: String): PreviewSurfaceEvent {
        if (attachedId != surfaceId) return reject(FailureCode.STALE_EVIDENCE, "Preview surface is no longer attached.")
        attachedId = null
        ready = false
        return PreviewSurfaceEvent.Detached(surfaceId)
    }

    /** SurfaceView can lose its underlying Surface while the view remains attached. */
    fun markLost(surfaceId: String): PreviewSurfaceEvent {
        if (attachedId != surfaceId) return reject(FailureCode.STALE_EVIDENCE, "Preview surface is no longer attached.")
        ready = false
        return PreviewSurfaceEvent.Detached(surfaceId)
    }

    fun isAttached(surfaceId: String): Boolean = attachedId == surfaceId

    fun isReady(surfaceId: String): Boolean = attachedId == surfaceId && ready

    private fun reject(code: FailureCode, message: String) = PreviewSurfaceEvent.Rejected(
        StableFailure(
            component = "preview-surface",
            code = code,
            severity = FailureSeverity.WARNING,
            recoverability = if (code == FailureCode.STALE_EVIDENCE) Recoverability.RETRYABLE else Recoverability.USER_ACTION,
            correlationId = correlationId,
            userMessage = message,
        ),
    )
}
