/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Independent decoded grids and raw/normalized hashes; AAC losslessness is not claimed. */
class ProxyPcmProbeDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun wavPcm16PreservesExactMonoAndStereoGridAndNonSilentBytes() = runBlocking<Unit> {
        val directory = directory()
        try {
            for ((rate, channels, frames) in listOf(Triple(44_100, 1, 8_197), Triple(48_000, 2, 12_347))) {
                val pcm = pcm16(frames, channels)
                val file = File(directory, "known-$rate-$channels.wav")
                writeWav(file, pcm, rate, channels)
                val before = sha(file.readBytes())
                val result = withTimeout(20_000) { probeProxyPcm(context, Uri.fromFile(file)) }
                assertPcm(result, rate, channels, frames, pcm)
                assertEquals(before, sha(file.readBytes()))
                Log.i("E17ProxyPcmProbe", "WAV rate=$rate channels=$channels frames=$frames decodedSha=${result.decodedSha256} exactInputPcm16=true originalUnchanged=true")
            }
        } finally { remove(directory) }
    }

    @Test fun actualFlacEncoderStereoPartialFinalBlockDecodesToExactInputPcm16() = runBlocking<Unit> {
        val directory = directory()
        try {
            val rate = 48_000
            val channels = 2
            val frames = 12_347
            val pcm = pcm16(frames, channels)
            val file = File(directory, "known-stereo.flac")
            val encoder = encodeFlac(file, pcm, rate, channels)
            val before = sha(file.readBytes())
            val result = withTimeout(20_000) { probeProxyPcm(context, Uri.fromFile(file)) }
            assertPcm(result, rate, channels, frames, pcm)
            assertEquals(before, sha(file.readBytes()))
            // A second independent call must reacquire and release its own extractor/decoder.
            assertEquals(result, withTimeout(20_000) { probeProxyPcm(context, Uri.fromFile(file)) })
            assertEquals(before, sha(file.readBytes()))
            Log.i("E17ProxyPcmProbe", "FLAC encoder=$encoder rate=$rate channels=$channels frames=$frames decodedSha=${result.decodedSha256} exactInputPcm16=true partialFinalBlock=true originalUnchanged=true successorEqual=true")
        } finally { remove(directory) }
    }

    @Test fun invalidEmptyAndNonAudioSourcesRejectWithoutChangingBytesThenValidWavSucceeds() = runBlocking<Unit> {
        val directory = directory()
        try {
            val empty = File(directory, "empty.wav").apply { writeBytes(byteArrayOf()) }
            val invalid = File(directory, "invalid.wav").apply { writeBytes("RIFF-invalid-WAVE".toByteArray()) }
            val zeroFrames = File(directory, "zero-frames.wav")
            writeWav(zeroFrames, byteArrayOf(), 48_000, 2)
            val videoBytes = instrumentation.context.assets.open("e1-video.mp4").use { it.readBytes() }
            assertEquals(5404, videoBytes.size)
            assertEquals("a32963539eefe4fc0d14e76770d51e49c25108a31b9eda9176a022a94a9a99b6", sha(videoBytes))
            val video = File(directory, "non-audio.mp4").apply { writeBytes(videoBytes) }
            for (file in listOf(empty, invalid, zeroFrames, video)) {
                val before = file.readBytes()
                var rejected: Exception? = null
                try { withTimeout(20_000) { probeProxyPcm(context, Uri.fromFile(file)) } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { rejected = failure }
                assertNotNull("Must reject ${file.name}", rejected)
                assertArrayEquals(before, file.readBytes())
                Log.i("E17ProxyPcmProbe", "REJECT name=${file.name} type=${rejected!!.javaClass.name} message=${rejected.message} originalUnchanged=true")
            }
            val pcm = pcm16(1_031, 2)
            val good = File(directory, "successor.wav")
            writeWav(good, pcm, 32_000, 2)
            assertPcm(withTimeout(20_000) { probeProxyPcm(context, Uri.fromFile(good)) }, 32_000, 2, 1_031, pcm)
        } finally { remove(directory) }
    }

    @Test fun wav24BitIndependentProbeObservesRawAndNormalizedHashes() = highDepth(false, ProxyPcmSampleType.S24_LE)
    @Test fun wavFloatIndependentProbeObservesRawAndNormalizedHashes() = highDepth(false, ProxyPcmSampleType.F32_LE)
    @Test fun flac24BitIndependentProbeCountsActualDecoderGrid() = highDepth(true, ProxyPcmSampleType.S24_LE)

    private fun highDepth(flac: Boolean, type: ProxyPcmSampleType) = runBlocking<Unit> {
        val signal = com.librestatic.opencinecam.camera.aacCalibrationSignal(48347, 48000, 2)
        val base = ByteBuffer.allocate(signal.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { signal.forEach { putShort(it) } }.array()
        val file = File(context.cacheDir, "depth-probe-${java.util.UUID.randomUUID()}.${if (flac) "flac" else "wav"}")
        try {
            val raw = writeProxyDepthFixture(file, base, flac, type)
            val result = withTimeout(20000) { probeProxyPcm(context, Uri.fromFile(file)) }
            assertEquals(48347L, result.frames); assertEquals(48000, result.sampleRateHz); assertEquals(2, result.channels)
            val normalized = normalizeProxyPcm16(ByteBuffer.wrap(raw), type)
            val expected = ByteArray(normalized.remaining()).also { normalized.get(it) }
            // API30 WAV extraction itself converts24/float toPCM16. The observation describes
            // returned decoder bytes, not the file header. Compare either representation exactly.
            val observedType = proxyPcmSampleType(result.pcmEncoding)
            if (observedType == ProxyPcmSampleType.S16_LE) assertEquals(sha(expected), result.decodedSha256)
            else { assertEquals(type, observedType); assertEquals(sha(raw), result.decodedSha256) }
            assertEquals(sha(expected), result.exportPcm16Sha256)
            android.util.Log.i("E17PcmDepth", "flac=$flac sourceType=$type observed=$result sourceRawSha=${sha(raw)}")
        } finally { check(file.delete()) }
    }

    private fun assertPcm(result: ProxyPcmObservation, rate: Int, channels: Int, frames: Int, pcm: ByteArray) {
        assertEquals(rate, result.sampleRateHz)
        assertEquals(channels, result.channels)
        assertEquals(AudioFormat.ENCODING_PCM_16BIT, result.pcmEncoding)
        assertEquals(frames.toLong(), result.frames)
        assertEquals(sha(pcm), result.decodedSha256)
    }
    internal fun pcm16(frames: Int, channels: Int): ByteArray = ByteBuffer.allocate(frames * channels * 2)
        .order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(frames) { frame -> repeat(channels) { channel ->
                // Channel-distinct signed samples cover both extrema, quiet detail and block boundaries.
                val value = when (frame % 257) {
                    0 -> if (channel == 0) Short.MIN_VALUE.toInt() else Short.MAX_VALUE.toInt()
                    1 -> if (channel == 0) -1 else 1
                    else -> ((frame * 7919 + channel * 19001) and 0xffff) - 32768
                }
                putShort(value.toShort())
            } }
        }.array()

    internal fun writeWav(file: File, pcm: ByteArray, rate: Int, channels: Int) {
        file.outputStream().use { stream ->
            val header = createWavHeader(pcm.size.toLong(), rate, 16, channels, false)
            val bytes = ByteArray(header.remaining()); header.get(bytes)
            stream.write(bytes); stream.write(pcm)
        }
    }

    /** Real platform encoder; only elementary-stream framing and known total-samples metadata are supplied. */
    internal fun encodeFlac(file: File, pcm: ByteArray, rate: Int, channels: Int): String {
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_FLAC)
        var started = false
        try {
            val name = codec.name
            codec.configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_FLAC, rate, channels).apply {
                setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                setInteger(MediaFormat.KEY_COMPLEXITY, 5)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096 * channels * 2)
            }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start(); started = true
            val stream = ByteArrayOutputStream()
            var header: ByteArray? = null
            var submitted = 0
            var inputEnded = false
            var outputEnded = false
            var packets = 0
            val frameBytes = channels * 2
            val info = MediaCodec.BufferInfo()
            val deadline = SystemClock.elapsedRealtime() + 20_000
            fun observeHeader(value: ByteBuffer) {
                val copy = value.duplicate()
                val bytes = ByteArray(copy.remaining()); copy.get(bytes)
                if (header == null) header = flacHeader(bytes)
            }
            while (!outputEnded) {
                check(SystemClock.elapsedRealtime() < deadline) { "Fixture FLAC encoder stalled" }
                if (!inputEnded) {
                    val index = codec.dequeueInputBuffer(1_000)
                    if (index >= 0) {
                        val buffer = requireNotNull(codec.getInputBuffer(index)); buffer.clear()
                        val count = minOf(pcm.size - submitted, buffer.capacity() / frameBytes * frameBytes, 4096 * frameBytes)
                        val ptsUs = submitted.toLong() / frameBytes * 1_000_000L / rate
                        if (submitted == pcm.size) {
                            codec.queueInputBuffer(index, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            check(count > 0)
                            buffer.put(pcm, submitted, count)
                            codec.queueInputBuffer(index, 0, count, ptsUs, 0)
                            submitted += count
                        }
                    }
                }
                when (val index = codec.dequeueOutputBuffer(info, 1_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> codec.outputFormat.getByteBuffer("csd-0")?.let(::observeHeader)
                    MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> if (index >= 0) {
                        try {
                            if (info.size > 0) {
                                val buffer = requireNotNull(codec.getOutputBuffer(index)).duplicate()
                                buffer.position(info.offset); buffer.limit(info.offset + info.size)
                                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) observeHeader(buffer)
                                else {
                                    check(header != null) { "FLAC packet before STREAMINFO" }
                                    val bytes = ByteArray(buffer.remaining()); buffer.get(bytes)
                                    stream.write(bytes); packets++
                                }
                            }
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        } finally { codec.releaseOutputBuffer(index, false) }
                    }
                }
            }
            assertEquals(pcm.size, submitted)
            assertTrue(inputEnded)
            assertTrue("Multiple real FLAC packets including final partial block", packets > 1)
            val prefix = requireNotNull(header)
            // STREAMINFO packs rate/channels/bits and total sample frames at bytes18..25.
            // Preserve every encoder bit except its unknown total-samples field, filled from input.
            val frames = (pcm.size / frameBytes).toLong()
            var packed = 0L
            for (i in 18..25) packed = (packed shl 8) or (prefix[i].toLong() and 255)
            assertEquals(rate.toLong(), packed ushr 44)
            assertEquals(channels.toLong(), ((packed ushr 41) and 7) + 1)
            assertEquals(16L, ((packed ushr 36) and 31) + 1)
            val nativeTotal = packed and ((1L shl 36) - 1)
            check(nativeTotal == 0L || nativeTotal == frames) { "Encoder STREAMINFO sample total contradicts submitted frames" }
            prefix[21] = ((prefix[21].toInt() and 0xf0) or ((frames ushr 32).toInt() and 15)).toByte()
            for (i in 0..3) prefix[22 + i] = (frames ushr (24 - i * 8)).toByte()
            file.outputStream().use { it.write(prefix); stream.writeTo(it) }
            Log.i("E17ProxyPcmProbe", "FLAC fixture encoder=$name packets=$packets inputFrames=$frames actualElementaryPackets=true streamInfoNativeTotal=$nativeTotal streamInfoTotalFilledFromInput=true")
            return name
        } finally {
            try { if (started) codec.stop() } finally { codec.release() }
        }
    }

    private fun flacHeader(bytes: ByteArray): ByteArray {
        val marker = "fLaC".toByteArray(Charsets.US_ASCII)
        val result = when {
            bytes.size >= 4 && bytes.copyOfRange(0, 4).contentEquals(marker) -> bytes.copyOf()
            bytes.size == 34 -> marker + byteArrayOf(0x80.toByte(), 0, 0, 34) + bytes
            bytes.size == 38 -> marker + bytes
            else -> error("Unexpected native FLAC header size ${bytes.size}")
        }
        check(result.size >= 42 && result[4].toInt() and 0x7f == 0)
        check(result[5] == 0.toByte() && result[6] == 0.toByte() && result[7] == 34.toByte())
        return result
    }
    private fun directory() = File(context.cacheDir, "e17-pcm-probe-${UUID.randomUUID()}").also { check(it.mkdir()) }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).proxyHex()
    private fun remove(directory: File) {
        // Calls return/throw only after the production probe finally releases its native owners.
        check(directory.listFiles()!!.all { it.isFile && it.delete() })
        check(directory.delete())
    }
}
