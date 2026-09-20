/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

/** Maps normalized top-down sample coordinates to operator preview; no sensor rotation twice. */
fun monitoringDisplayPoint(x: Float, y: Float, domain: MonitoringSignalDomain,
    sensorOrientation: Int, displayDegrees: Int, frontFacing: Boolean): Pair<Float, Float> {
    require(sensorOrientation in listOf(0, 90, 180, 270) && displayDegrees in listOf(0, 90, 180, 270))
    val rotation = if (domain == MonitoringSignalDomain.ISP_YUV_ESTIMATED_SDR)
        (sensorOrientation + if (frontFacing) displayDegrees else 360 - displayDegrees) % 360
    else if (frontFacing) displayDegrees else (360 - displayDegrees) % 360
    val point = when (rotation) { 90 -> 1f - y to x; 180 -> 1f - x to 1f - y; 270 -> y to 1f - x; else -> x to y }
    return (if (frontFacing) 1f - point.first else point.first) to point.second
}

/** Uses the same aspect-fit quad as the live GPU preview, including anamorphic letterboxing. */
fun monitoringPreviewScale(sourceWidth: Int, sourceHeight: Int, targetWidth: Int, targetHeight: Int,
    sensorOrientation: Int, displayDegrees: Int, frontFacing: Boolean, squeezeFactor: Float): Pair<Float, Float> {
    val geometry = OpenCineLogPreviewGeometryCalculator.calculate(sourceWidth, sourceHeight, targetWidth, targetHeight,
        sensorOrientation, displayDegrees, frontFacing, squeezeFactor)
    return geometry.scaleX to geometry.scaleY
}
