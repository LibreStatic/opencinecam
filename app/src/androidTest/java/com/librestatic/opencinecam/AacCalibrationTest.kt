/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.camera.AacCalibrationConfig
import com.librestatic.opencinecam.camera.AacCodecCalibrator
import java.io.File
import java.nio.ByteBuffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AacCalibrationTest {
    @Test fun nativeDecoderCalibratesBothInputBoundariesAndRetainsIndependentEvidence() {
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val manifest = JSONArray()
        for ((rate, channels) in listOf(48000 to 1, 44100 to 2)) {
            val minimum = AudioRecord.getMinBufferSize(rate, if (channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            assertTrue(minimum > 0)
            val config = AacCodecCalibrator.selectConfig(rate, channels, 96000 * channels, minimum * 2)
            val name = config.codecName
            val started = android.os.SystemClock.elapsedRealtime()
            val result = AacCodecCalibrator.qualify(config) { evidence ->
                val stem = "aac-cal-$rate-$channels-${evidence.sourceFrames}"
                File(directory, "$stem.input.pcm").writeBytes(evidence.sourcePcm)
                File(directory, "$stem.native.pcm").writeBytes(evidence.decodedPcm)
                val muxer = MediaMuxer(File(directory, "$stem.m4a").path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                var muxStarted = false
                try {
                    val track = muxer.addTrack(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels).apply {
                        setByteBuffer("csd-0", ByteBuffer.wrap(evidence.codecSpecificData))
                    })
                    muxer.start(); muxStarted = true
                    for (packet in evidence.packets) muxer.writeSampleData(track, ByteBuffer.wrap(packet.data), MediaCodec.BufferInfo().apply {
                        set(0, packet.data.size, packet.ptsUs, packet.flags)
                    })
                } finally { try { if (muxStarted) muxer.stop() } finally { muxer.release() } }
                manifest.put(JSONObject().put("stem", stem).put("encoder", name).put("decoder", evidence.measurement.decoderName)
                    .put("rate", rate).put("channels", channels).put("inputFrames", evidence.sourceFrames)
                    .put("paddingFrames", evidence.paddingFrames).put("primingFrames", evidence.measurement.primingFrames)
                    .put("nativeSignalLagFrames", evidence.measurement.decoderSignalLagFrames)
                    .put("nativeFirstPtsUs", evidence.measurement.decoderFirstPtsUs)
                    .put("firstEncodedPtsUs", evidence.measurement.firstEncodedPtsUs)
                    .put("minimumCorrelation", evidence.measurement.minimumCorrelation)
                    .put("packets", evidence.packets.size))
            }
            assertEquals(config, result.config)
            assertTrue(result.minimumCorrelation >= 0.98)
            val cachedAt = android.os.SystemClock.elapsedRealtime()
            assertEquals(result, AacCodecCalibrator.qualify(config))
            assertTrue(android.os.SystemClock.elapsedRealtime() - cachedAt < 100)
            android.util.Log.i("AacCalibrationProbe", "config=$config result=$result elapsedMs=${android.os.SystemClock.elapsedRealtime() - started}")
        }
        File(directory, "aac-calibration.json").writeText(manifest.toString(2))
    }
}
