/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.capture

import com.librestatic.opencinecam.camera.RawVideoConfig
import com.librestatic.opencinecam.rawvideo.RawQualificationSpecs
import com.librestatic.opencinecam.rawvideo.RawVideoCrop

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
    /**
     * For a risky option, the storage was never tested at its rate: a camera-only qualification stored it
     * ([com.librestatic.opencinecam.rawvideo.RawQualificationRisk.REASON_STORAGE_NOT_TESTED]), so it has no burst either.
     */
    val storageUntested: Boolean = false,
    /**
     * PLAN-076: a window of the operator's (size, place or both) that is not one of the qualified configurations; its
     * [key] carries the origin when it is not centred. Judged against the qualified ones, never picked by Automatic.
     */
    val isCustom: Boolean = false,
) {
    /**
     * A block-pack (`+bp`) option: the one compression that records near-lossless where the camera
     * allows it ([CameraUiState.rawVideoNearLosslessAvailable], [CameraSettings.rawVideoNearLossless]).
     */
    val isBlockPack: Boolean get() = key.endsWith(RawVideoConfigKeys.BLOCK_PACK_SUFFIX)

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

    /**
     * An estimate of the rate reaching storage, in decimal MB/s, for a compressed option; null for an
     * uncompressed one. It uses the conservative [COMPRESSED_CAPACITY_RATIO], so it is shown beside the
     * uncompressed rate and never used for throughput (PLAN-071 decision 1).
     */
    val estimatedWrittenMegabytesPerSecond: Double?
        get() = if (isCompressed) capacityBytesPerSecond / 1_000_000.0 else null

    companion object {
        /** Compression ratio assumed for compressed capacity estimates (never for throughput). */
        const val COMPRESSED_CAPACITY_RATIO = 1.8

        fun of(
            config: RawVideoConfig,
            risky: Boolean = false,
            burstSeconds: Long? = null,
            achievedFps: Double? = null,
            storageUntested: Boolean = false,
        ): RawVideoConfigOption = RawVideoConfigOption(
            key = RawVideoConfigKeys.key(config),
            label = "${config.cropWidth}×${config.cropHeight} · ${config.fps} fps" + if (config.isCompressed) RAW_LABEL_COMPRESSED_SUFFIX else "",
            bytesPerSecond = config.streamBytesPerSecond,
            isCrop = RawVideoConfigKeys.isCrop(config),
            isCompressed = config.isCompressed,
            isRisky = risky,
            burstSeconds = burstSeconds.takeIf { risky },
            achievedFps = achievedFps.takeIf { com.librestatic.opencinecam.rawvideo.RawQualificationFrameRate.isReducedRate(it, config.fps) },
            storageUntested = storageUntested && risky,
        )
    }
}

/** PLAN-076: where a RAW video window sits in its `sensorWidth x sensorHeight` RAW output. */
data class RawVideoCropUi(val sensorWidth: Int, val sensorHeight: Int, val left: Int, val top: Int, val width: Int, val height: Int)

/** PLAN-076: what the gate says about a window of the operator's, as the crop editor reads it out. */
enum class RawVideoCropStatus { PASSED, RISKY, REFUSED }

/**
 * PLAN-076: the live read-out of a window the operator is shaping: its canonical [key], the rates of a
 * [RawVideoConfigOption] of it, its [status] with the burst ([RawVideoConfigOption.burstSeconds]) of a risky one, the
 * reduced rate its covering configurations achieved, and why a refused one is refused.
 */
data class RawVideoCropPreview(
    val key: String,
    val bytesPerSecond: Long,
    val capacityBytesPerSecond: Long,
    val status: RawVideoCropStatus,
    val burstSeconds: Long? = null,
    val achievedFps: Double? = null,
    val refusedReason: RawVideoLeftOutReason? = null,
)

/** Why a judged RAW video configuration is not offered ([RawVideoLeftOutOption]), as the chooser and Settings say it. */
enum class RawVideoLeftOutReason {
    /** The run failed or was skipped because the phone was too hot: cooling down and qualifying again may pass it. */
    HEAT,
    /** The camera does not deliver RAW at this frame rate. */
    CAMERA_LIMIT,
    /** The storage could not keep up, as its sweep alone showed. */
    STORAGE,
    /** Any other failure or skip. */
    NOT_QUALIFIED,
}

/**
 * A RAW video configuration the qualification of the shown storage judged but the chooser does not offer: [option]
 * carries its key, label, crop and block-pack facts (never chosen), [fps] its target rate and [reason] why it is out.
 */
data class RawVideoLeftOutOption(val option: RawVideoConfigOption, val fps: Int, val reason: RawVideoLeftOutReason)

/** A RAW video choice resolved on one camera: the [config] and where its window sits in the RAW output ([crop]). */
data class RawVideoSelection(val config: RawVideoConfig, val crop: RawVideoCrop)

/**
 * The stable key of a RAW video configuration on one camera: `full@30` for the whole sensor, or
 * `1920x1080@60` for a centre crop, with [BLOCK_PACK_SUFFIX] (`full@30+bp`) for the block-pack
 * variant or [LJ92_SUFFIX] (`full@30+lj92`) for the LJ92 one. A crop may name its origin after the
 * size, X11-geometry style (`1920x816+1080+1122@24`); without one it is centred, and a centred origin
 * is never written. The camera and its sensor size are implied by the camera the key is resolved on, so a
 * preset carries the same choice across devices. An empty key means "no choice yet": the qualified
 * configuration with the highest data rate.
 */
object RawVideoConfigKeys {
    const val FULL = "full"
    const val BLOCK_PACK_SUFFIX = "+" + RawVideoConfig.ENCODING_BLOCK_PACK
    const val LJ92_SUFFIX = "+" + RawVideoConfig.ENCODING_LJ92
    private val PATTERN = Regex(
        "^(full|[1-9][0-9]{0,4}x[1-9][0-9]{0,4}(\\+(0|[1-9][0-9]{0,4})\\+(0|[1-9][0-9]{0,4}))?)@[1-9][0-9]{0,3}(\\+bp|\\+lj92)?$",
    )

    /** The origin of a crop key: `+left+top` right before the `@`. */
    private val ORIGIN = Regex("\\+([0-9]+)\\+([0-9]+)@")

    fun isCrop(config: RawVideoConfig): Boolean =
        config.cropWidth != config.sensorWidth || config.cropHeight != config.sensorHeight

    fun key(config: RawVideoConfig): String =
        (if (isCrop(config)) "${config.cropWidth}x${config.cropHeight}" else FULL) + "@" + config.fps +
            when (config.encoding) {
                RawVideoConfig.ENCODING_BLOCK_PACK -> BLOCK_PACK_SUFFIX
                RawVideoConfig.ENCODING_LJ92 -> LJ92_SUFFIX
                else -> ""
            }

    /**
     * The key of [config] with its window at [crop]: [key] when [crop] is centred ([RawVideoCrop.isCentredIn]),
     * else with the origin after the size (`1920x816+1080+1122@24`). [crop] must have [config]'s size and fit its sensor.
     */
    fun key(config: RawVideoConfig, crop: RawVideoCrop): String {
        require(crop.width == config.cropWidth && crop.height == config.cropHeight) { "crop size must match the configuration" }
        require(crop.left + crop.width <= config.sensorWidth && crop.top + crop.height <= config.sensorHeight) { "crop must fit the sensor" }
        val plain = key(config)
        if (crop.isCentredIn(config)) return plain
        val at = plain.indexOf('@')
        return plain.substring(0, at) + "+${crop.left}+${crop.top}" + plain.substring(at)
    }

    /**
     * [key] resolved on camera [cameraId] whose RAW10 output is [sensorWidth] x [sensorHeight], or null when it is
     * empty or malformed, or the window cannot be written there: a width that is not a multiple of 4, an odd height, a
     * left that is not a multiple of 4, an odd top, a window outside the sensor or under [RawVideoCrop.MIN_WIDTH] x
     * [RawVideoCrop.MIN_HEIGHT] (or the sensor, when smaller). Without an origin the window is centred.
     */
    fun parse(key: String, cameraId: String, sensorWidth: Int, sensorHeight: Int): RawVideoSelection? {
        if (!PATTERN.matches(key)) return null
        val origin = ORIGIN.find(key)
        val spec = origin?.let { key.replaceRange(it.range, "@") } ?: key
        val config = RawQualificationSpecs.parse(spec, cameraId, sensorWidth, sensorHeight)?.config ?: return null
        val width = config.cropWidth
        val height = config.cropHeight
        if (height % 2 != 0) return null
        if (width < minOf(RawVideoCrop.MIN_WIDTH, sensorWidth) || height < minOf(RawVideoCrop.MIN_HEIGHT, sensorHeight)) return null
        val crop = if (origin == null) RawVideoCrop.centred(config) else {
            val left = origin.groupValues[1].toInt()
            val top = origin.groupValues[2].toInt()
            if (left % 4 != 0 || top % 2 != 0 || left + width > sensorWidth || top + height > sensorHeight) return null
            RawVideoCrop(left, top, width, height)
        }
        return RawVideoSelection(config, crop)
    }

    /**
     * [key] without its origin (`1920x816+1080+1122@24` reads `1920x816@24`): what the camera session depends on. Two
     * keys that differ only in where the window sits run the same session; only the ring re-arms (PLAN-076).
     */
    fun withoutOrigin(key: String): String = ORIGIN.find(key)?.let { key.replaceRange(it.range, "@") } ?: key

    /** True when the selection going from [before] to [after] needs another camera session: anything but the origin changed. */
    fun sessionChanged(before: String?, after: String?): Boolean = before?.let(::withoutOrigin) != after?.let(::withoutOrigin)

    /** True for the empty key and for every well-formed one; the settings store keeps nothing else. */
    fun isValid(key: String): Boolean = key.isEmpty() || PATTERN.matches(key)
}
