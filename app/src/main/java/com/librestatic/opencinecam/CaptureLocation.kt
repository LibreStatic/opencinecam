/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import kotlinx.serialization.json.*

/** Permission precision is not a claim of physical accuracy; every fix carries reported accuracy. */
enum class LocationPermissionPrecision { APPROXIMATE, PRECISE }
enum class CaptureLocationStatus { AVAILABLE, NO_PERMISSION, INACTIVE, UNAVAILABLE, STALE }
data class CaptureLocationFix(val latitude: Double, val longitude: Double, val accuracyMeters: Float,
    val epochMillis: Long, val permissionPrecision: LocationPermissionPrecision) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0 && longitude.isFinite() && longitude in -180.0..180.0)
        require(accuracyMeters.isFinite() && accuracyMeters >= 0 && epochMillis > 0)
    }
}
data class CaptureLocationSnapshot(val status: CaptureLocationStatus, val fix: CaptureLocationFix? = null, val ageMillis: Long? = null) {
    init {
        require((status == CaptureLocationStatus.AVAILABLE) == (fix != null))
        require(if (fix != null) ageMillis != null && ageMillis in 0..120_000 else ageMillis == null)
    }
}

/** Omitted entirely when disabled. Never serialize coordinates for unavailable/stale/revoked input. */
fun captureLocationJson(value: CaptureLocationSnapshot): JsonObject = buildJsonObject {
    put("schema", "opencinecam.capture-location.v1"); put("status", value.status.name)
    value.fix?.let { fix ->
        put("latitude", fix.latitude); put("longitude", fix.longitude); put("accuracyMeters", fix.accuracyMeters)
        put("epochMillis", fix.epochMillis); put("permissionPrecision", fix.permissionPrecision.name)
        put("ageMillis", requireNotNull(value.ageMillis))
    }
}

internal fun admittedCaptureLocation(enabled: Boolean, permission: LocationPermissionPrecision?, active: Boolean,
    fix: CaptureLocationFix?, fixElapsedNanos: Long, nowElapsedNanos: Long): CaptureLocationSnapshot? {
    if (!enabled) return null
    if (permission == null) return CaptureLocationSnapshot(CaptureLocationStatus.NO_PERMISSION)
    if (!active) return CaptureLocationSnapshot(CaptureLocationStatus.INACTIVE)
    if (fix == null || fix.permissionPrecision != permission) return CaptureLocationSnapshot(CaptureLocationStatus.UNAVAILABLE)
    if (fixElapsedNanos < 0 || nowElapsedNanos < fixElapsedNanos) return CaptureLocationSnapshot(CaptureLocationStatus.STALE)
    val ageMillis = (nowElapsedNanos - fixElapsedNanos) / 1_000_000
    if (ageMillis > 120_000) return CaptureLocationSnapshot(CaptureLocationStatus.STALE)
    return CaptureLocationSnapshot(CaptureLocationStatus.AVAILABLE, fix, ageMillis)
}
