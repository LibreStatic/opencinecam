/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import android.content.Context
import android.media.AudioManager
import android.os.Build
import com.librestatic.opencinecam.camera.AndroidCodecAudioMetadataSource
import com.librestatic.opencinecam.camera.CodecAudioMetadata
import com.librestatic.opencinecam.camera.CodecAudioProbe
import com.librestatic.opencinecam.camera.CodecAudioReportCache
import com.librestatic.opencinecam.camera.CameraInventory
import com.librestatic.opencinecam.camera.codecAudioValidityKey
import com.librestatic.opencinecam.camera.listEncoderNames
import java.io.File

/**
 * The encoder and audio report for this device. It is kept in `filesDir` and probed again only when
 * the build, the camera characteristics, the encoder list or a probe version changes.
 */
internal fun probeCodecAudio(context: Context, inventory: List<CameraInventory>): CodecAudioMetadata {
    val app = context.applicationContext
    val key = codecAudioValidityKey(Build.FINGERPRINT, characteristicsDigest(inventory), listEncoderNames())
    val cache = CodecAudioReportCache(AtomicFileCodecAudioReportStore(File(app.filesDir, "codec-audio-report.json")))
    return CodecAudioProbe(AndroidCodecAudioMetadataSource(app.getSystemService(AudioManager::class.java)), cache).probe(key)
}
