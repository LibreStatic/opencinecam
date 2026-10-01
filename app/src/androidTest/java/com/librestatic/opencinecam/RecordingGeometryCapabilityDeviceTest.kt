/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only gate for the baked portrait dimensions used by the shared GPU recorder. */
@RunWith(AndroidJUnit4::class)
class RecordingGeometryCapabilityDeviceTest {
    @Test fun atLeastOneHardwareSurfaceEncoderAcceptsLandscapeAndPortrait1080p() {
        val mimes = listOf(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_HEVC)
        val encoders = mimes.associateWith { hardwareSurfaceEncoders(it) }.filterValues { it.isNotEmpty() }
        // Emulators only ship software (c2.android/goldfish) encoders; there is nothing to gate there.
        org.junit.Assume.assumeTrue("No hardware Surface video encoder is advertised on this device; run on a physical device", encoders.isNotEmpty())
        encoders.forEach { (mime, candidates) ->
            val accepted = candidates.any { capabilities ->
                capabilities.areSizeAndRateSupported(1920, 1080, 30.0) &&
                    capabilities.areSizeAndRateSupported(1080, 1920, 30.0)
            }
            assertTrue("No $mime hardware Surface encoder accepts both 1920×1080 and 1080×1920 at 30 fps.", accepted)
        }
    }

    private fun hardwareSurfaceEncoders(mime: String) =
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.asSequence()
            .filter { it.isEncoder && !it.isAlias && it.isHardwareAccelerated }
            .filter { it.supportedTypes.any { type -> type.equals(mime, true) } }
            .mapNotNull { info ->
                val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull() ?: return@mapNotNull null
                if (MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface !in caps.colorFormats) return@mapNotNull null
                caps.videoCapabilities
            }
            .toList()
}
