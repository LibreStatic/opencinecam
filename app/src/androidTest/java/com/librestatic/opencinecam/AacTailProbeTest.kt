/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Real AAC diagnostic: known PCM, actual packets, both legal EOS forms. No microphone fidelity claim. */
class AacTailProbeTest {
    @Test fun retainKnownWaveformAndActualPacketsForEachAdvertisedAacEncoder() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val names = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter {
            it.isEncoder && !it.isAlias && it.supportedTypes.any { type -> type == MediaFormat.MIMETYPE_AUDIO_AAC }
        }.map { it.name }
        assertTrue("No advertised AAC encoder", names.isNotEmpty())
        val results = JSONArray()
        for ((ordinal, name) in names.withIndex()) {
            val configurations = listOf(Triple(48000, 1, 52661), Triple(48000, 1, 52224), Triple(44100, 2, 52661))
            for ((rate, channels, inputFrames) in configurations) {
                for (paddingFrames in listOf(0, 4096)) {
                    for (eosOnData in listOf(false, true)) {
                        results.put(runProbe(context.cacheDir, ordinal, name, rate, channels, inputFrames, paddingFrames, eosOnData))
                    }
                }
            }
        }
        File(context.cacheDir, "aac-tail-probe.json").writeText(results.toString(2))
    }

    @Test fun audioWindowKeepsActualVideoPacketsAndPresentationTiming() {
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val audioName = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.first {
            it.isEncoder && !it.isAlias && MediaFormat.MIMETYPE_AUDIO_AAC in it.supportedTypes
        }.name
        val audio = runProbe(directory, 0, audioName, 48000, 1, 52661, 4096, false)
        val videoFile = File(directory, "aac-window-video.mp4")
        encodeVideo(videoFile)
        val output = File(directory, "aac-window-av.mp4")
        val muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val inputs = listOf(videoFile, File(directory, audio.getString("stem") + ".m4a"))
            .map { file -> android.media.MediaExtractor().apply { setDataSource(file.path); selectTrack(0) } }
        var started = false
        try {
            val tracks = inputs.map { muxer.addTrack(it.getTrackFormat(0)) }
            muxer.start(); started = true
            val data = ByteBuffer.allocateDirect(256 * 1024)
            while (inputs.any { it.sampleTime >= 0 }) {
                val index = inputs.indices.filter { inputs[it].sampleTime >= 0 }
                    .minBy { inputs[it].sampleTime + if (it == 1) 250123 else 0 }
                val source = inputs[index]
                data.clear()
                val size = source.readSampleData(data, 0)
                check(size > 0 && size <= data.capacity())
                muxer.writeSampleData(tracks[index], data, MediaCodec.BufferInfo().apply {
                    set(0, size, source.sampleTime + if (index == 1) 250123 else 0,
                        if (source.sampleFlags and android.media.MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                })
                source.advance()
            }
        } finally {
            inputs.forEach { it.release() }
            try { if (started) muxer.stop() } finally { muxer.release() }
        }
        val original = File(directory, "aac-window-av.original.mp4")
        output.copyTo(original, overwrite = true)
        java.io.RandomAccessFile(output, "rw").use { file ->
            val access = object : com.librestatic.opencinecam.camera.ProjectMp4File {
                override val size: Long get() = file.length()
                override fun read(offset: Long, length: Int): ByteArray = ByteArray(length).also { file.seek(offset); file.readFully(it) }
                override fun write(offset: Long, bytes: ByteArray) { file.seek(offset); file.write(bytes) }
            }
            com.librestatic.opencinecam.camera.finalizeAacSourceWindow(access,
                com.librestatic.opencinecam.camera.AacSourceWindow(48000, 52661, 2048, audio.getJSONArray("packets").length().toLong(), 250123))
        }
        File(directory, "aac-window-av.json").writeText(JSONObject().put("audioStem", audio.getString("stem"))
            .put("sourceFrames", 52661).put("fixturePrimingFrames", 2048).put("offsetUs", 250123).toString(2))
    }

    private fun encodeVideo(output: File) {
        // Reuse the actual GLES/Surface recording fixture instead of assuming a byte-buffer layout.
        val stopped = java.util.concurrent.CountDownLatch(1)
        val saved = java.util.concurrent.atomic.AtomicBoolean(false)
        SubjectPreviewGpuTest.Fixture().use { fixture ->
            android.os.ParcelFileDescriptor.open(output, android.os.ParcelFileDescriptor.MODE_CREATE or
                android.os.ParcelFileDescriptor.MODE_TRUNCATE or android.os.ParcelFileDescriptor.MODE_READ_WRITE).use { descriptor ->
                val frame = com.librestatic.opencinecam.camera.RecordingFrameSize(128, 96)
                val geometry = com.librestatic.opencinecam.camera.RecordingGeometry(
                    com.librestatic.opencinecam.camera.RecordingGeometryMode.COMPATIBLE, 0, 0, 0, 0, 0, frame, frame, frame)
                assertTrue(fixture.pipeline.startRecording(descriptor, 12000000, geometry,
                    timelapse = com.librestatic.opencinecam.camera.TimelapseCapture(100000000,
                        com.librestatic.opencinecam.camera.CaptureFrameRate(30), 18),
                    onTimelapseProgress = { if (it.submittedFrames == 18L) fixture.pipeline.stopRecording() },
                    onStarted = {}, onStopped = { success, _ -> saved.set(success); stopped.countDown() }))
                val deadline = SystemClock.elapsedRealtime() + 15000
                while (stopped.count > 0 && SystemClock.elapsedRealtime() < deadline) {
                    fixture.sourceFrame(); SystemClock.sleep(120)
                }
                assertTrue("Video fixture callback: ${fixture.failures}", stopped.await(2, java.util.concurrent.TimeUnit.SECONDS))
                assertTrue(fixture.failures.toString(), saved.get())
            }
        }
    }

    private fun runProbe(
        directory: File,
        ordinal: Int,
        name: String,
        rate: Int,
        channels: Int,
        inputFrames: Int,
        paddingFrames: Int,
        eosOnData: Boolean,
    ): JSONObject {
        val stem = "aac-tail-$ordinal-$rate-$channels-$inputFrames-$paddingFrames-${if (eosOnData) "data" else "empty"}"
        val codecFrames = inputFrames + paddingFrames
        val pcm = ByteBuffer.allocate(codecFrames * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        // A swept signal gives unambiguous correlation at the beginning and the final partial block.
        for (frame in 0 until inputFrames) {
            for (channel in 0 until channels) {
                val phase = 2.0 * Math.PI * ((317.0 + channel * 223) * frame / rate + (1900.0 + channel * 113) * frame.toDouble() * frame / (2.0 * rate * inputFrames))
                pcm.putShort((sin(phase) * 12000).toInt().toShort())
            }
        }
        File(directory, "$stem.input.pcm").writeBytes(pcm.array().copyOf(inputFrames * channels * 2))
        val codec = MediaCodec.createByCodecName(name)
        val muxer = MediaMuxer(File(directory, "$stem.m4a").path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        var muxerStarted = false
        var track = -1
        var framesQueued = 0
        var inputEos = false
        var outputEos = false
        val packets = JSONArray()
        var outputFormat = ""
        try {
            codec.configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 96000 * channels)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096)
            }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start(); started = true
            val deadline = SystemClock.elapsedRealtime() + 15000
            val info = MediaCodec.BufferInfo()
            while (!outputEos) {
                check(SystemClock.elapsedRealtime() < deadline) { "AAC probe timed out: $name / $eosOnData" }
                if (!inputEos) {
                    val index = codec.dequeueInputBuffer(0)
                    if (index >= 0) {
                        val buffer = requireNotNull(codec.getInputBuffer(index)).apply { clear() }
                        val frameBytes = channels * 2
                        val frames = minOf(buffer.capacity() / frameBytes, 2048, codecFrames - framesQueued)
                        buffer.put(pcm.array(), framesQueued * frameBytes, frames * frameBytes)
                        inputEos = frames == 0 || (eosOnData && framesQueued + frames == codecFrames)
                        codec.queueInputBuffer(index, 0, frames * frameBytes, framesQueued * 1000000L / rate,
                            if (inputEos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                        framesQueued += frames
                    }
                }
                when (val index = codec.dequeueOutputBuffer(info, 1000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(!muxerStarted)
                        outputFormat = codec.outputFormat.toString()
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start(); muxerStarted = true
                    }
                    else -> if (index >= 0) {
                        try {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                check(muxerStarted)
                                val buffer = requireNotNull(codec.getOutputBuffer(index))
                                buffer.position(info.offset); buffer.limit(info.offset + info.size)
                                muxer.writeSampleData(track, buffer, info)
                                packets.put(JSONObject().put("size", info.size).put("ptsUs", info.presentationTimeUs).put("flags", info.flags))
                            }
                            outputEos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        } finally { codec.releaseOutputBuffer(index, false) }
                    }
                }
            }
            assertEquals(codecFrames, framesQueued)
            assertTrue(packets.length() > 0)
        } finally {
            try { if (started) codec.stop() } finally { codec.release() }
            try { if (muxerStarted) muxer.stop() } finally { muxer.release() }
        }
        // The 2048-frame delay is an explicit fixture parameter measured by the previous native
        // waveform matrix. Production has no guessed delay or automatic padding policy.
        val copy = File(directory, "$stem.window.m4a")
        File(directory, "$stem.m4a").copyTo(copy, overwrite = true)
        val before = copy.readBytes()
        var windowResult: com.librestatic.opencinecam.camera.AacSourceWindowResult? = null
        java.io.RandomAccessFile(copy, "rw").use { file ->
            val access = object : com.librestatic.opencinecam.camera.ProjectMp4File {
                override val size: Long get() = file.length()
                override fun read(offset: Long, length: Int): ByteArray = ByteArray(length).also { file.seek(offset); file.readFully(it) }
                override fun write(offset: Long, bytes: ByteArray) { file.seek(offset); file.write(bytes) }
            }
            val window = com.librestatic.opencinecam.camera.AacSourceWindow(rate, inputFrames.toLong(), 2048,
                packets.length().toLong(), if (eosOnData) 250123 else 0)
            if (paddingFrames > 0) windowResult = com.librestatic.opencinecam.camera.finalizeAacSourceWindow(access, window)
            else {
                try {
                    com.librestatic.opencinecam.camera.finalizeAacSourceWindow(access, window)
                    fail("Truncated AAC must not be presented as a complete source window")
                } catch (expected: IllegalArgumentException) {
                    assertArrayEquals(before, copy.readBytes())
                }
            }
        }
        var extractorDurationUs: Long? = null
        var extractorFirstPtsUs: Long? = null
        if (windowResult != null) {
            val extractor = android.media.MediaExtractor()
            try {
                extractor.setDataSource(copy.path)
                assertEquals(1, extractor.trackCount)
                val format = extractor.getTrackFormat(0)
                extractorDurationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else null
                extractor.selectTrack(0)
                extractorFirstPtsUs = extractor.sampleTime
            } finally { extractor.release() }
        }
        return JSONObject().put("stem", stem).put("encoder", name).put("eosOnData", eosOnData)
            .put("fixturePrimingFrames", 2048).put("windowApplied", windowResult != null)
            .put("windowOffsetUs", if (eosOnData) 250123 else 0)
            .put("windowMovieTimescale", windowResult?.movieTimescale ?: JSONObject.NULL)
            .put("windowDurationTicks", windowResult?.presentationDurationTicks ?: JSONObject.NULL)
            .put("extractorDurationUs", extractorDurationUs ?: JSONObject.NULL)
            .put("extractorFirstPtsUs", extractorFirstPtsUs ?: JSONObject.NULL)
            .put("inputFrames", inputFrames).put("rate", rate).put("channels", channels)
            // Experimental zeros, not captured PCM, not a production padding/trim policy.
            .put("experimentalPaddingFrames", paddingFrames).put("codecInputFrames", codecFrames)
            .put("outputEos", outputEos).put("format", outputFormat).put("packets", packets)
    }
}
