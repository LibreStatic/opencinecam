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
        listOf(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_HEVC).forEach { mime ->
            val accepted = hardwareSurfaceEncoders(mime).any { capabilities ->
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
