/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import org.junit.Assert.*
import org.junit.Test

class ProxyPolicyTest {
    private val defaults = ProxyPolicy()
    private val ample = ProxyConditions(100, true, Long.MAX_VALUE)

    @Test fun defaultsAreExplicitAndAllSupportedCombinationsAreValid() {
        assertEquals(ProxyPolicy(false, 20, 256), defaults)
        for (charging in listOf(false, true)) for (battery in listOf(0, 10, 20, 30, 50)) {
            for (space in listOf(64, 256, 512, 1024, 2048)) {
                val policy = ProxyPolicy(charging, battery, space)
                assertEquals(charging, policy.requireCharging)
                assertEquals(battery, policy.minimumBatteryPercent)
                assertEquals(space, policy.reserveSpaceMiB)
                assertNull(proxyWaitReason(policy, ample))
            }
        }
        assertNull(proxyWaitReason(defaults, ProxyConditions(20, false, 268_435_456L)))
    }

    @Test fun unsupportedPolicyValuesRejectInsteadOfClamping() {
        for (percent in listOf(Int.MIN_VALUE, -1, 1, 9, 11, 19, 21, 29, 31, 49, 51, 100, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { ProxyPolicy(minimumBatteryPercent = percent) }
        }
        for (space in listOf(Int.MIN_VALUE, -1, 0, 63, 65, 255, 257, 511, 513, 1023, 1025, 2047, 2049, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { ProxyPolicy(reserveSpaceMiB = space) }
        }
    }

    @Test fun batteryThresholdsAreInclusiveAndChargingDoesNotBypassLowBattery() {
        for (threshold in listOf(0, 10, 20, 30, 50)) {
            val policy = ProxyPolicy(minimumBatteryPercent = threshold)
            assertNull(proxyWaitReason(policy, ample.copy(batteryPercent = threshold)))
            assertNull(proxyWaitReason(policy, ample.copy(batteryPercent = threshold + 1)))
            if (threshold > 0) assertEquals(ProxyWaitReason.BATTERY,
                proxyWaitReason(policy, ample.copy(batteryPercent = threshold - 1)))
        }
        assertEquals(ProxyWaitReason.BATTERY, proxyWaitReason(ProxyPolicy(requireCharging = true), ample.copy(batteryPercent = 19)))
        assertNull(proxyWaitReason(defaults, ample.copy(charging = false)))
    }

    @Test fun missingOrInvalidBatteryIsUnknownEvenWhenThresholdIsZero() {
        for (value in listOf(null, -1, 101, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertEquals(ProxyWaitReason.BATTERY_UNKNOWN, proxyWaitReason(defaults, ample.copy(batteryPercent = value)))
            assertEquals(ProxyWaitReason.BATTERY_UNKNOWN,
                proxyWaitReason(ProxyPolicy(minimumBatteryPercent = 0), ample.copy(batteryPercent = value)))
        }
    }

    @Test fun chargingUnknownOnlyBlocksWhenRequiredAndNeverMasqueradesAsUnplugged() {
        assertNull(proxyWaitReason(defaults, ample.copy(charging = null)))
        val required = defaults.copy(requireCharging = true)
        assertEquals(ProxyWaitReason.BATTERY_UNKNOWN, proxyWaitReason(required, ample.copy(charging = null)))
        assertEquals(ProxyWaitReason.CHARGING, proxyWaitReason(required, ample.copy(charging = false)))
        assertNull(proxyWaitReason(required, ample.copy(charging = true)))
    }

    @Test fun reservesUseBinaryMiBAndSpaceIncludesWorkingBytesAtExactBoundary() {
        val bytes = mapOf(64 to 67_108_864L, 256 to 268_435_456L, 512 to 536_870_912L,
            1024 to 1_073_741_824L, 2048 to 2_147_483_648L)
        for ((mib, reserve) in bytes) for (working in listOf(0L, 1L, 987_654_321L)) {
            val policy = ProxyPolicy(reserveSpaceMiB = mib)
            val required = reserve + working
            assertEquals(ProxyWaitReason.STORAGE, proxyWaitReason(policy, ample.copy(availableBytes = required - 1), working))
            assertNull(proxyWaitReason(policy, ample.copy(availableBytes = required), working))
            assertNull(proxyWaitReason(policy, ample.copy(availableBytes = required + 1), working))
        }
    }

    @Test fun storageMissingOrNegativeIsUnknownButZeroIsKnownInsufficient() {
        for (available in listOf(null, -1L, Long.MIN_VALUE)) {
            assertEquals(ProxyWaitReason.STORAGE_UNKNOWN, proxyWaitReason(defaults, ample.copy(availableBytes = available)))
        }
        assertEquals(ProxyWaitReason.STORAGE, proxyWaitReason(defaults, ample.copy(availableBytes = 0)))
    }

    @Test fun overflowingSpaceRequirementFailsClosedWithoutWrappingOrThrowing() {
        val lastRepresentable = Long.MAX_VALUE - 268_435_456L
        assertNull(proxyWaitReason(defaults, ample, lastRepresentable))
        assertEquals(ProxyWaitReason.STORAGE, proxyWaitReason(defaults, ample.copy(availableBytes = Long.MAX_VALUE - 1), lastRepresentable))
        assertEquals(ProxyWaitReason.STORAGE, proxyWaitReason(defaults, ample, lastRepresentable + 1))
        assertEquals(ProxyWaitReason.STORAGE, proxyWaitReason(defaults, ample, Long.MAX_VALUE))
        for (negative in listOf(-1L, Long.MIN_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { proxyWaitReason(defaults, ample, negative) }
            assertThrows(IllegalArgumentException::class.java) { proxyWaitReason(defaults, ProxyConditions(null, null, null), negative) }
        }
    }

    @Test fun simultaneousFailuresHaveStableExplicitPriority() {
        val policy = defaults.copy(requireCharging = true)
        assertEquals(ProxyWaitReason.BATTERY_UNKNOWN, proxyWaitReason(policy, ProxyConditions(null, false, 0)))
        assertEquals(ProxyWaitReason.BATTERY_UNKNOWN, proxyWaitReason(policy, ProxyConditions(1, null, 0)))
        assertEquals(ProxyWaitReason.CHARGING, proxyWaitReason(policy, ProxyConditions(1, false, null)))
        assertEquals(ProxyWaitReason.BATTERY, proxyWaitReason(policy, ProxyConditions(1, true, null)))
        assertEquals(ProxyWaitReason.STORAGE_UNKNOWN, proxyWaitReason(policy, ProxyConditions(20, true, null)))
        assertEquals(ProxyWaitReason.STORAGE, proxyWaitReason(policy, ProxyConditions(20, true, 1)))
    }
}
