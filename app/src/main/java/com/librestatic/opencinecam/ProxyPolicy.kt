/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

/** Admission policy only; evaluating it never starts, cancels or mutates a proxy job. */
data class ProxyPolicy(
    val requireCharging: Boolean = false,
    val minimumBatteryPercent: Int = 20,
    val reserveSpaceMiB: Int = 256,
) {
    init {
        require(minimumBatteryPercent in listOf(0, 10, 20, 30, 50)) { "Unsupported proxy battery threshold" }
        require(reserveSpaceMiB in listOf(64, 256, 512, 1024, 2048)) { "Unsupported proxy storage reserve" }
    }
}

/** Absent or out-of-range observations are unknown, never evidence that a gate passed. */
data class ProxyConditions(val batteryPercent: Int?, val charging: Boolean?, val availableBytes: Long?)
/** Policy gates, then temporary media ownership held by REC, a WebDAV transfer or another proxy action. */
enum class ProxyWaitReason { BATTERY_UNKNOWN, CHARGING, BATTERY, STORAGE_UNKNOWN, STORAGE, CAPTURE_ACTIVE, TRANSFER_ACTIVE, MEDIA_BUSY }

/** Deterministic first failing gate: battery observation, charging, battery level, storage.
 * Unknown charging blocks only when charging is required. Battery remains a required
 * observation even at threshold0. Available space must cover both reserve and working bytes.
 * An unrepresentable combined requirement cannot be met by any Long-sized observation.
 */
fun proxyWaitReason(policy: ProxyPolicy, conditions: ProxyConditions,
    requiredWorkingBytes: Long = 0): ProxyWaitReason? {
    require(requiredWorkingBytes >= 0) { "Proxy working-space requirement must be nonnegative" }
    val battery = conditions.batteryPercent?.takeIf { it in 0..100 }
        ?: return ProxyWaitReason.BATTERY_UNKNOWN
    if (policy.requireCharging) {
        val charging = conditions.charging ?: return ProxyWaitReason.BATTERY_UNKNOWN
        if (!charging) return ProxyWaitReason.CHARGING
    }
    if (battery < policy.minimumBatteryPercent) return ProxyWaitReason.BATTERY
    val available = conditions.availableBytes?.takeIf { it >= 0 }
        ?: return ProxyWaitReason.STORAGE_UNKNOWN
    val reserve = policy.reserveSpaceMiB.toLong() * 1024L * 1024L
    if (requiredWorkingBytes > Long.MAX_VALUE - reserve) return ProxyWaitReason.STORAGE
    return if (available < reserve + requiredWorkingBytes) ProxyWaitReason.STORAGE else null
}
