/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import com.librestatic.opencinecam.camera.RawVideoConfig

/**
 * OCC-PLAN-071: one qualified RAW video configuration as the capture UI lists it. [key] is the
 * persisted choice ([CameraSettings.rawVideoConfig]); [label] reads like "4080×3060 · 30 fps", with
 * [RAW_LABEL_COMPRESSED_SUFFIX] (" · Compressed", localized when shown) for a lossless-compressed
 * ("Save space") variant: block-pack, or LJ92 on older records.
 */
data class RawVideoConfigOption(
    val key: String,
    val label: String,
    /**
     * The uncompressed RAW10 rate, also for a compressed option: compression is never counted on for
     * throughput (PLAN-071 decision 1), so this is the rate the configuration was qualified at.
     */
    val bytesPerSecond: Long,
    /** True when the configuration writes a centre crop of the sensor (a narrower field of view). */
    val isCrop: Boolean,
    /** True for a compressed variant (block-pack or LJ92), which writes frames losslessly compressed. */
    val isCompressed: Boolean = false,
    /**
     * True when the storage is too slow for this rate ([com.librestatic.opencinecam.rawvideo.RawQualificationRisk]):
     * choosable by hand with a warning, never picked by Automatic.
     */
    val isRisky: Boolean = false,
    /**
     * For a risky option, how long a take lasts before the storage's write cache fills and frames may
     * drop, measured on a rested phone ([com.librestatic.opencinecam.camera.RawVideoQualificationRecord.burstSeconds]);
     * null when unknown or not limited by the cache.
     */
    val burstSeconds: Long? = null,
    /**
     * The rate the camera achieved when it is below the target (a reduced-rate pass, PLAN-071), to a
     * tenth; null when it reaches the target or is unknown. Like a risky one, a reduced-rate option is
     * choosable by hand and never picked by Automatic.
     */
    val achievedFps: Double? = null,
) {
    /** The camera delivers this configuration under its target rate ([achievedFps]). */
    val isReducedRate: Boolean get() = achievedFps != null

    /** Decimal megabytes per second, as the qualification report states rates. */
    val megabytesPerSecond: Double get() = bytesPerSecond / 1_000_000.0

    /**
     * The rate to estimate recording capacity (minutes left) with. A compressed option assumes a
     * conservative [COMPRESSED_CAPACITY_RATIO], below the ratios measured in daylight (block-pack 2.67,
     * LJ92 2.0–2.9), because the ratio falls with ISO and scene noise (block-pack 1.48 at ISO 14000);
     * an estimate that runs short is worse than one that runs long.
     */
    val capacityBytesPerSecond: Long
        get() = if (isCompressed) (bytesPerSecond / COMPRESSED_CAPACITY_RATIO).toLong() else bytesPerSecond

    companion object {
        /** Compression ratio assumed for compressed capacity estimates (never for throughput). */
        const val COMPRESSED_CAPACITY_RATIO = 1.8

        fun of(config: RawVideoConfig, risky: Boolean = false, burstSeconds: Long? = null, achievedFps: Double? = null): RawVideoConfigOption = RawVideoConfigOption(
            key = RawVideoConfigKeys.key(config),
            label = "${config.cropWidth}×${config.cropHeight} · ${config.fps} fps" + if (config.isCompressed) RAW_LABEL_COMPRESSED_SUFFIX else "",
            bytesPerSecond = config.streamBytesPerSecond,
            isCrop = RawVideoConfigKeys.isCrop(config),
            isCompressed = config.isCompressed,
            isRisky = risky,
            burstSeconds = burstSeconds.takeIf { risky },
            achievedFps = achievedFps.takeIf { com.librestatic.opencinecam.rawvideo.RawQualificationFrameRate.isReducedRate(it, config.fps) },
        )
    }
}

/**
 * The stable key of a RAW video configuration on one camera: `full@30` for the whole sensor, or
 * `1920x1080@60` for a centre crop, with [BLOCK_PACK_SUFFIX] (`full@30+bp`) for the block-pack
 * variant or [LJ92_SUFFIX] (`full@30+lj92`) for the LJ92 one. The camera and its sensor size are implied by the camera the key is resolved on, so a
 * preset carries the same choice across devices. An empty key means "no choice yet": the qualified
 * configuration with the highest data rate.
 */
object RawVideoConfigKeys {
    const val FULL = "full"
    const val BLOCK_PACK_SUFFIX = "+" + RawVideoConfig.ENCODING_BLOCK_PACK
    const val LJ92_SUFFIX = "+" + RawVideoConfig.ENCODING_LJ92
    private val PATTERN = Regex("^(full|[1-9][0-9]{0,4}x[1-9][0-9]{0,4})@[1-9][0-9]{0,3}(\\+bp|\\+lj92)?$")

    fun isCrop(config: RawVideoConfig): Boolean =
        config.cropWidth != config.sensorWidth || config.cropHeight != config.sensorHeight

    fun key(config: RawVideoConfig): String =
        (if (isCrop(config)) "${config.cropWidth}x${config.cropHeight}" else FULL) + "@" + config.fps +
            when (config.encoding) {
                RawVideoConfig.ENCODING_BLOCK_PACK -> BLOCK_PACK_SUFFIX
                RawVideoConfig.ENCODING_LJ92 -> LJ92_SUFFIX
                else -> ""
            }

    /** True for the empty key and for every well-formed one; the settings store keeps nothing else. */
    fun isValid(key: String): Boolean = key.isEmpty() || PATTERN.matches(key)
}
