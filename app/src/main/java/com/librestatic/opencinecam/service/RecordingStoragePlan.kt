/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.service

import com.librestatic.opencinecam.CameraSettings
import com.librestatic.opencinecam.CaptureMode
import com.librestatic.opencinecam.camera.RecordingDataRate
import com.librestatic.opencinecam.timelapseProjectRate
import com.librestatic.opencinecam.videoProjectRate
import com.librestatic.opencinecam.core.model.Knowledge
import com.librestatic.opencinecam.core.model.PolicyMode
import com.librestatic.opencinecam.media.audio.AudioOutputFormat
import com.librestatic.opencinecam.media.storage.StorageSpaceCheck
import com.librestatic.opencinecam.media.storage.calculateStorageReserve
import com.librestatic.opencinecam.media.storage.checkStorageSpace

/** Bits per second the take's audio adds: the AAC bitrate, or uncompressed PCM for WAV and (conservatively) FLAC. */
internal fun audioBitsPerSecond(settings: CameraSettings, audioRequested: Boolean): Long = when {
    !audioRequested -> 0L
    settings.audioOutputFormat == AudioOutputFormat.AAC_MP4 -> settings.audioBitrateKbps * 1_000L
    else -> settings.audioSampleRateHz.toLong() * settings.audioChannels * settings.audioBitDepth.bits
}

/**
 * Bytes per minute the take is projected to write, from the bitrates the encoders are actually
 * given ([RecordingDataRate]). A constrained high-speed take may run on the MediaRecorder or the
 * GPU path depending on later conditions, so the larger of the two is projected.
 */
internal fun projectedTakeBytesPerMinute(
    mode: CaptureMode,
    settings: CameraSettings,
    targetFps: Int,
    constrainedHighSpeed: Boolean,
    audioRequested: Boolean,
): Long {
    val nominal = settings.videoBitrateMbps * 1_000_000
    val videoBps = when {
        mode == CaptureMode.TIME_LAPSE -> {
            val rate = settings.timelapseProjectRate
            RecordingDataRate.timelapseVideoBps(nominal, rate.numerator.toDouble() / rate.denominator, settings.timelapseIntervalMs)
        }
        // Constrained high-speed runs the direct recorder at the capture rate, off-speed or not.
        mode == CaptureMode.VIDEO && constrainedHighSpeed -> maxOf(
            RecordingDataRate.recorderVideoBps(nominal, targetFps), RecordingDataRate.gpuVideoBps(nominal, targetFps)).toLong()
        mode == CaptureMode.VIDEO && settings.videoOffSpeed -> {
            val rate = settings.videoProjectRate
            RecordingDataRate.gpuVideoBps(nominal, kotlin.math.ceil(rate.numerator.toDouble() / rate.denominator).toInt().coerceAtLeast(1)).toLong()
        }
        else -> RecordingDataRate.gpuVideoBps(nominal, targetFps).toLong()
    }
    val audio = if (mode == CaptureMode.TIME_LAPSE || (mode == CaptureMode.VIDEO && settings.videoOffSpeed)) 0L
        else audioBitsPerSecond(settings, audioRequested)
    return RecordingDataRate.bytesPerMinute(videoBps, audio)
}

/** What starting a take does about the free space on the volume it writes to (ADR-0013). */
internal sealed interface StorageAdmission {
    data object Admit : StorageAdmission
    /** Free space could not be read; Adaptive starts anyway and warns. */
    data object AdmitUnread : StorageAdmission
    data class Refuse(val errorCode: String, val requiredBytes: Long, val availableBytes: Long?) : StorageAdmission
}

internal const val STORAGE_RESERVE_INSUFFICIENT = "storage-reserve-insufficient"
internal const val STORAGE_UNKNOWN = "storage-unknown"

/**
 * Below max(1 GiB, two minutes of the projected data) no take starts, in either policy. Unreadable
 * free space refuses in Strict and starts with a warning in Adaptive.
 */
internal fun admitRecordingStorage(policy: PolicyMode, projectedBytesPerMinute: Long, freeBytes: Long?): StorageAdmission {
    val reserve = calculateStorageReserve(projectedBytesPerMinute.coerceAtLeast(0L))
    return when (checkStorageSpace(if (freeBytes == null) Knowledge.Unknown else Knowledge.Known(freeBytes), reserve)) {
        is StorageSpaceCheck.Sufficient -> StorageAdmission.Admit
        is StorageSpaceCheck.Insufficient -> StorageAdmission.Refuse(STORAGE_RESERVE_INSUFFICIENT, reserve.requiredBytes, freeBytes)
        StorageSpaceCheck.Unknown ->
            if (policy == PolicyMode.STRICT) StorageAdmission.Refuse(STORAGE_UNKNOWN, reserve.requiredBytes, null) else StorageAdmission.AdmitUnread
    }
}
