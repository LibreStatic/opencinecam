/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.rawvideo

import android.app.ActivityManager
import android.content.Context

/** What bound a [RawRingPlan]. */
enum class RawRingLimit {
    /** The PLAN-071 default: [RawRingBudget.targetBytes] (4 GiB, 2 GiB or min(1 GiB, 1/8 of total RAM) by total RAM). */
    TARGET,

    /** `availMem - threshold` was smaller than the target. */
    AVAILABLE_MEMORY,
}

/** A RAM ring sized for one RAW video mode entry; [ringBytes] is what the qualification record stores. */
data class RawRingPlan(
    val slotBytes: Int,
    val slotCount: Int,
    val targetBytes: Long,
    val memoryCapBytes: Long,
    val limitedBy: RawRingLimit,
) {
    val ringBytes: Long get() = slotBytes.toLong() * slotCount
}

/**
 * PLAN-071 decision 4: the ring is sized at arm time from `ActivityManager.MemoryInfo`. The default
 * ([targetBytes]) grows with total RAM: 4 GiB on 16 GB phones, 2 GiB on 12 GB phones, otherwise
 * min(1 GiB, 1/8 of total RAM). It never exceeds `availMem - threshold`, so arming RAW video cannot
 * by itself push the device into low-memory killing. Pure; the device numbers come in as arguments.
 */
object RawRingBudget {
    /** The target of a device below [LARGE_TOTAL_MEM_BYTES]: min(this, totalMem / 8). */
    const val MAX_RING_BYTES: Long = 1L shl 30

    /** 12 GB phones (the S24 Ultra) report about 11 GiB of total RAM or a little more. */
    const val LARGE_TOTAL_MEM_BYTES: Long = 11L shl 30
    const val LARGE_RING_BYTES: Long = 2L shl 30

    /** 16 GB phones (the Razr Fold) report about 15 GiB of total RAM. */
    const val XLARGE_TOTAL_MEM_BYTES: Long = 14L shl 30
    const val XLARGE_RING_BYTES: Long = 4L shl 30

    /** Fewer slots than this is not a ring worth recording into. */
    const val MIN_SLOTS = 4

    /** 4 GiB from [XLARGE_TOTAL_MEM_BYTES], 2 GiB from [LARGE_TOTAL_MEM_BYTES], otherwise min(1 GiB, totalMem / 8). */
    fun targetBytes(totalMem: Long): Long = when {
        totalMem >= XLARGE_TOTAL_MEM_BYTES -> XLARGE_RING_BYTES
        totalMem >= LARGE_TOTAL_MEM_BYTES -> LARGE_RING_BYTES
        else -> minOf(MAX_RING_BYTES, (totalMem / 8).coerceAtLeast(0))
    }

    fun memoryCapBytes(availMem: Long, threshold: Long): Long = (availMem - threshold).coerceAtLeast(0)

    /** The plan for frames of [slotBytes], or null when fewer than [MIN_SLOTS] fit. */
    fun plan(totalMem: Long, availMem: Long, threshold: Long, slotBytes: Int): RawRingPlan? {
        require(slotBytes > 0) { "slotBytes must be positive" }
        val target = targetBytes(totalMem)
        val cap = memoryCapBytes(availMem, threshold)
        val budget = minOf(target, cap)
        val slots = budget / slotBytes
        if (slots < MIN_SLOTS) return null
        return RawRingPlan(
            slotBytes = slotBytes,
            slotCount = slots.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            targetBytes = target,
            memoryCapBytes = cap,
            limitedBy = if (cap < target) RawRingLimit.AVAILABLE_MEMORY else RawRingLimit.TARGET,
        )
    }

    fun plan(memory: ActivityManager.MemoryInfo, slotBytes: Int): RawRingPlan? =
        plan(memory.totalMem, memory.availMem, memory.threshold, slotBytes)

    /** Reads the current memory state; call right before [RawVideoRecorder.arm]. */
    fun plan(context: Context, slotBytes: Int): RawRingPlan? {
        val memory = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        return plan(memory, slotBytes)
    }
}
