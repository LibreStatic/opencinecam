/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.driver

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.librestatic.opencinecam.CameraUiState
import com.librestatic.opencinecam.capture.RawCropActions
import com.librestatic.opencinecam.capture.RawVideoConfigKeys
import com.librestatic.opencinecam.capture.RawVideoConfigOption
import com.librestatic.opencinecam.capture.RawVideoCropPreview
import com.librestatic.opencinecam.capture.RawVideoCropStatus
import com.librestatic.opencinecam.capture.RawVideoCropUi
import com.librestatic.opencinecam.capture.RawVideoLeftOutReason
import com.librestatic.opencinecam.storage.RawSavedCrop

/*
 * PLAN-076 delivery 3b: a fake capture service for the crop screens. It selects, judges and saves RAW video windows the
 * way the service's gate reads them, by a rule instead of qualification records, and lists the selection like
 * withRawVideoGate does. Test source set only.
 */

/** The fake gate's rule for a window at a rate: refused above this RAW10 rate (storage too slow). */
private const val DRIVER_REFUSED_ABOVE = 900_000_000L

/** The fake gate's rule: risky above this RAW10 rate, with a [DRIVER_RISKY_BURST_SECONDS] burst. */
private const val DRIVER_RISKY_ABOVE = 480_000_000L

/** How long a risky window's take lasts before frames may drop, as a rested Razr-like phone measures it. */
private const val DRIVER_RISKY_BURST_SECONDS = 75L

/** The rate the fake camera cannot deliver RAW at (its camera limit). */
private const val DRIVER_CAMERA_LIMIT_FPS = 240

/**
 * The RAW video selection, saved crops and verdicts of a fake capture service on a [sensorWidth] x [sensorHeight] RAW
 * output whose qualified configurations are [fixed]. [wanted] is the setting (empty for Automatic, which picks [autoKey]).
 * A fixed configuration keeps its own status; any other window is judged by its rate: [DRIVER_CAMERA_LIMIT_FPS] is the
 * camera limit, above [DRIVER_REFUSED_ABOVE] the storage refuses it, above [DRIVER_RISKY_ABOVE] it is risky, else it passes.
 */
internal class DriverRawCropService(
    private val fixed: List<RawVideoConfigOption>,
    private val customRates: List<Int>,
    saved: List<RawSavedCrop>,
    wanted: String,
    private val autoKey: String = "full@30",
    private val sensorWidth: Int = 4080,
    private val sensorHeight: Int = 3060,
) : RawCropActions {
    var wanted by mutableStateOf(wanted)
        private set
    var saved by mutableStateOf(saved)
        private set
    private var nextId by mutableIntStateOf(saved.size + 1)

    private val selectedKey: String get() = wanted.ifEmpty { autoKey }

    /** [base] with this service's RAW video selection, window, custom option, custom rates and saved crops. */
    fun state(base: CameraUiState): CameraUiState {
        val key = selectedKey
        val selection = RawVideoConfigKeys.parse(key, "0", sensorWidth, sensorHeight) ?: return base
        val config = selection.config
        val custom = if (fixed.any { it.key == key }) null else verdict(key)?.let { preview ->
            RawVideoConfigOption.of(config, risky = preview.status == RawVideoCropStatus.RISKY, burstSeconds = preview.burstSeconds)
                .copy(key = key, isCustom = true)
        }
        val crop = selection.crop
        return base.copy(
            rawVideoOptions = fixed + listOfNotNull(custom),
            rawVideoSelectedKey = key,
            rawVideoAutoKey = autoKey,
            rawVideoCrop = RawVideoCropUi(sensorWidth, sensorHeight, crop.left, crop.top, crop.width, crop.height),
            rawVideoCustomRates = customRates,
            rawVideoSavedCrops = saved,
            targetFps = config.fps, targetVideoWidth = config.cropWidth, targetVideoHeight = config.cropHeight,
            recordingWidth = config.cropWidth, recordingHeight = config.cropHeight,
        )
    }

    override fun select(key: String): Boolean {
        if (key.isNotEmpty()) {
            val preview = verdict(key) ?: return false
            if (preview.status == RawVideoCropStatus.REFUSED) return false
        }
        wanted = key
        return true
    }

    override fun verdict(key: String): RawVideoCropPreview? {
        val config = RawVideoConfigKeys.parse(key, "0", sensorWidth, sensorHeight)?.config ?: return null
        val option = RawVideoConfigOption.of(config)
        fixed.firstOrNull { it.key == key }?.let { fixedOption ->
            return RawVideoCropPreview(key, fixedOption.bytesPerSecond, fixedOption.capacityBytesPerSecond,
                if (fixedOption.isRisky) RawVideoCropStatus.RISKY else RawVideoCropStatus.PASSED,
                burstSeconds = fixedOption.burstSeconds, achievedFps = fixedOption.achievedFps)
        }
        val bytes = option.bytesPerSecond
        val refused = when {
            config.fps >= DRIVER_CAMERA_LIMIT_FPS -> RawVideoLeftOutReason.CAMERA_LIMIT
            bytes > DRIVER_REFUSED_ABOVE -> RawVideoLeftOutReason.STORAGE
            else -> null
        }
        val status = when {
            refused != null -> RawVideoCropStatus.REFUSED
            bytes > DRIVER_RISKY_ABOVE -> RawVideoCropStatus.RISKY
            else -> RawVideoCropStatus.PASSED
        }
        return RawVideoCropPreview(key, bytes, option.capacityBytesPerSecond, status,
            burstSeconds = DRIVER_RISKY_BURST_SECONDS.takeIf { status == RawVideoCropStatus.RISKY }, refusedReason = refused)
    }

    override fun save(name: String, key: String): Boolean {
        val trimmed = name.trim()
        val crop = RawVideoConfigKeys.parse(key, "0", sensorWidth, sensorHeight)?.crop ?: return false
        if (trimmed.isEmpty()) return false
        saved = saved + RawSavedCrop("driver-${nextId++}", trimmed, sensorWidth, sensorHeight, crop.left, crop.top, crop.width, crop.height)
        return true
    }

    override fun rename(id: String, name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || saved.none { it.id == id }) return false
        saved = saved.map { if (it.id == id) it.copy(name = trimmed) else it }
        return true
    }

    override fun delete(id: String): Boolean {
        if (saved.none { it.id == id }) return false
        saved = saved.filterNot { it.id == id }
        return true
    }
}
