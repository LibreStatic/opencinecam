/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.metrics.LogSessionId
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.AssetLoader
import androidx.media3.transformer.Codec
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import com.librestatic.opencinecam.camera.AacCodecCalibrator
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ExoPlayerAssetLoader
import androidx.media3.transformer.InAppMp4Muxer
import androidx.media3.transformer.SampleConsumer
import androidx.media3.transformer.TransformationRequest
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Observed library report, not independent acceptance of pixels, timestamps or bitrate. */
internal data class ProxyTranscodeResult(
    val videoMimeType: String,
    val audioMimeType: String?,
    val videoEncoderName: String,
    val audioEncoderName: String?,
    val videoConversionProcess: Int,
    val audioConversionProcess: Int,
    val width: Int,
    val height: Int,
    val videoFrameCount: Int,
    val approximateDurationMs: Long,
    val requestedVideoBitrate: Int,
    val averageVideoBitrate: Int,
    val averageAudioBitrate: Int,
    val fileSizeBytes: Long,
    val sidecarCalibration: com.librestatic.opencinecam.camera.AacCodecCalibration? = null,
)

/** One local, read-only source to one privately owned candidate. Publication belongs to caller.
 * All Media3 access, including cancellation, uses its application looper. A cancelled coroutine
 * waits for Media3 cancellation and looper exit before its candidate can be deleted/reused.
 * Source HDR is never implicitly tone-mapped; audio encoding is prohibited, not merely reported.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class ProxyTranscoder(context: Context) {
    private val context = context.applicationContext

    suspend fun transcode(source: Uri, output: File, width: Int, height: Int, videoBitrate: Int,
        sidecar: ProxySidecarExportInput? = null): ProxyTranscodeResult {
        var ownedOutput = false
        try {
            return withContext(Dispatchers.IO) {
                require(source.scheme in setOf("content", "file")) { "Proxy requires a local readable source" }
                require(width in 2..8192 && height in 2..8192 && width % 2 == 0 && height % 2 == 0) {
                    "Proxy dimensions must be positive even supported dimensions"
                }
                require(width.toLong() * height <= 4096L * 2160 && videoBitrate > 0)
                require(!output.exists()) { "Proxy candidate already exists" }
                val sourceInfo = inspectSource(source, sidecar != null)
                val audioMime = sourceInfo.audioMime
                val sidecarExport = sidecar?.let { input ->
                    require(audioMime == null) { "Sidecar composition cannot replace embedded audio" }
                    proxyPcmSampleType(input.pcm.pcmEncoding) // Fail closed for unobserved representations.
                    require(input.timeline.sampleRateHz == input.pcm.sampleRateHz && input.timeline.pcmFrames == input.pcm.frames &&
                        input.timeline.videoEndUs == sourceInfo.videoDurationUs)
                    val job = currentCoroutineContext()
                    val config = AacCodecCalibrator.selectConfig(input.pcm.sampleRateHz, input.pcm.channels, 192_000, 16_384)
                    ProxySidecarExport(input, AacCodecCalibrator.qualify(config, isCancelled = { !job[kotlinx.coroutines.Job]!!.isActive }))
                }
                currentCoroutineContext().ensureActive()
                check(output.createNewFile()) { "Proxy candidate already exists" }
                ownedOutput = true
                val report = export(source, output, width, height, videoBitrate, sourceInfo.videoDurationUs, sidecarExport)
                currentCoroutineContext().ensureActive()
                // Disjoint sequences make Media3's aggregate conversion classifier ambiguous.
                // Observe actual decoder creation through our owned factory instead.
                val videoTranscoded = if (sidecarExport == null)
                    report.videoConversionProcess == ExportResult.CONVERSION_PROCESS_TRANSCODED
                else sidecarExport.videoDecoderCreated
                check(report.videoMimeType == MimeTypes.VIDEO_H264 && report.videoEncoderName != null &&
                    videoTranscoded) {
                    "Proxy video was not transcoded to AVC: mime=${report.videoMimeType} encoder=${report.videoEncoderName} conversion=${report.videoConversionProcess} frames=${report.videoFrameCount}"
                }
                check(report.colorInfo?.colorTransfer == C.COLOR_TRANSFER_SDR) { "Proxy output is not explicitly SDR" }
                if (sidecarExport == null) check(report.audioMimeType == audioMime && report.audioEncoderName == null &&
                    report.audioConversionProcess == if (audioMime == null) ExportResult.CONVERSION_PROCESS_NA
                    else ExportResult.CONVERSION_PROCESS_TRANSMUXED) { "Proxy audio was changed or dropped" }
                else {
                    // createEncoder + exact PCM input/CSD/EOS counts below prove AAC encoding;
                    // raw WAV has no decoder and is misclassified as transmuxed in ExportResult.
                    check(report.audioMimeType == MimeTypes.AUDIO_AAC && report.audioEncoderName != null) { "Sidecar was not encoded to AAC" }
                    sidecarExport.finalizeCandidate(output)
                }
                check(output.isFile && output.length() > 0 && report.videoFrameCount > 0) { "Proxy output is empty" }
                ProxyTranscodeResult(requireNotNull(report.videoMimeType), report.audioMimeType,
                    requireNotNull(report.videoEncoderName), report.audioEncoderName,
                    report.videoConversionProcess, report.audioConversionProcess, report.width, report.height,
                    report.videoFrameCount, report.approximateDurationMs, videoBitrate,
                    report.averageVideoBitrate, report.averageAudioBitrate, output.length(), sidecarExport?.calibration)
            }
        } catch (failure: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) {
                if (ownedOutput && output.exists() && !output.delete()) {
                    failure.addSuppressed(IllegalStateException("Retired proxy candidate could not be removed: $output"))
                }
            }
            throw failure
        }
    }

    private data class SourceInfo(val audioMime: String?, val videoDurationUs: Long)

    private suspend fun inspectSource(source: Uri, sidecar: Boolean): SourceInfo {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, source, null)
            val formats = (0 until extractor.trackCount).map(extractor::getTrackFormat)
            val video = formats.filter { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            val audio = formats.filter { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            require(video.size == 1 && audio.size <= 1 && video.size + audio.size == formats.size) {
                "Proxy requires one video and at most one audio track; other tracks would be lost"
            }
            val track = video.single()
            val transfer = if (track.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) track.getInteger(MediaFormat.KEY_COLOR_TRANSFER) else null
            require(transfer !in setOf(MediaFormat.COLOR_TRANSFER_HLG, MediaFormat.COLOR_TRANSFER_ST2084) &&
                !track.containsKey(MediaFormat.KEY_HDR_STATIC_INFO)) { "HDR proxy conversion is not supported" }
            // Missing platform tags do not authorize SDR interpretation. Media3 extracts its own
            // color information and retains HDR by default; every fallback is rejected below.
            require(track.getString(MediaFormat.KEY_MIME) != "video/dolby-vision") { "HDR proxy conversion is not supported" }
            require(track.containsKey(MediaFormat.KEY_DURATION)) { "Proxy source video duration is unknown" }
            val durationUs = if (sidecar) probeProxyVideoEndUs(context, source) else track.getLong(MediaFormat.KEY_DURATION)
            require(durationUs > 0) { "Proxy source video duration must be positive" }
            extractor.selectTrack(formats.indexOf(track))
            var samples = 0
            var maxPtsUs = -1L
            while (extractor.sampleTime >= 0) {
                currentCoroutineContext().ensureActive()
                require(++samples <= 250_000) { "Proxy source exceeds sample inspection bound" }
                maxPtsUs = maxOf(maxPtsUs, extractor.sampleTime)
                require(extractor.sampleTime < durationUs) { "Proxy source sample exceeds declared duration" }
                if (!extractor.advance()) break
            }
            require(samples > 0 && maxPtsUs < durationUs) { "Proxy source duration must include every video sample" }
            return SourceInfo(audio.singleOrNull()?.getString(MediaFormat.KEY_MIME), durationUs)
        } finally { extractor.release() }
    }

    private suspend fun export(source: Uri, output: File, width: Int, height: Int, bitrate: Int, videoDurationUs: Long, sidecar: ProxySidecarExport?): ExportResult {
        val thread = HandlerThread("proxy-transcoder-${UUID.randomUUID()}").apply { start() }
        val handler = Handler(thread.looper)
        val outcome = CompletableDeferred<ExportResult>()
        var transformer: Transformer? = null // Only accessed on handler's looper.
        try {
            check(handler.post {
                try {
                    val delegate = DefaultEncoderFactory.Builder(context)
                        .setEnableFallback(false).setEnableFormatFallback(false)
                        .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
                        .build()
                    val noAudioEncoding = object : Codec.EncoderFactory by delegate {
                        // Kotlin delegation does not forward Java default methods. Preserve the
                        // explicit bitrate request even for AVC with unchanged output dimensions.
                        override fun videoNeedsEncoding(): Boolean = delegate.videoNeedsEncoding()
                        override fun audioNeedsEncoding(): Boolean = sidecar != null
                        override fun createForAudioEncoding(format: Format, logSessionId: LogSessionId?): Codec {
                            return sidecar?.createEncoder(context, format)
                                ?: throw ExportException.createForUnexpected(IllegalArgumentException("Proxy must preserve encoded audio; recoding is prohibited"))
                        }
                    }
                    val nativeDecoders = DefaultDecoderFactory.Builder(context)
                        .setEnableDecoderFallback(false).build()
                    val serialSurfaceDecoder = object : Codec.DecoderFactory by nativeDecoders {
                        override fun createForVideoDecoding(format: Format, outputSurface: Surface,
                            requestSdrToneMapping: Boolean, logSessionId: LogSessionId?): Codec {
                            val native = nativeDecoders.createForVideoDecoding(format, outputSurface,
                                requestSdrToneMapping, logSessionId)
                            if (sidecar != null) {
                                check(!sidecar.videoDecoderCreated) { "Unexpected second sidecar video decoder" }
                                sidecar.videoDecoderCreated = true
                            }
                            return object : Codec by native {
                                // A SurfaceTexture can replace unacquired images despite API29's
                                // allow-frame-drop=0. Use Media3's own pending-frame backpressure:
                                // release the next decoded image only after the previous image is
                                // acquired by the frame processor. No sleeps, FPS cap or PTS edits.
                                override fun getMaxPendingFrameCount(): Int = 1
                            }
                        }
                    }
                    val nativeAssetLoaders = ExoPlayerAssetLoader.Factory(context, serialSurfaceDecoder, Clock.DEFAULT)
                    val displayGeometryAssetLoaders = AssetLoader.Factory { editedItem, looper, assetListener, settings ->
                        nativeAssetLoaders.createAssetLoader(editedItem, looper, object : AssetLoader.Listener {
                            override fun onDurationUs(durationUs: Long) = assetListener.onDurationUs(durationUs)
                            override fun onTrackCount(trackCount: Int) = assetListener.onTrackCount(trackCount)
                            override fun onTrackAdded(inputFormat: Format, supportedOutputTypes: Int): Boolean =
                                assetListener.onTrackAdded(inputFormat, supportedOutputTypes)
                            override fun onError(exportException: ExportException) = assetListener.onError(exportException)
                            override fun onOutputFormat(format: Format): SampleConsumer? {
                                val quarterTurn = format.rotationDegrees % 180 == 90 || format.rotationDegrees % 180 == -90
                                val displayFormat = if (MimeTypes.isVideo(format.sampleMimeType) && quarterTurn) {
                                    val sar = format.pixelWidthHeightRatio
                                    require(sar.isFinite() && sar > 0f) { "Proxy video pixel aspect ratio must be finite and positive" }
                                    val rotatedSar = 1f / sar
                                    require(rotatedSar.isFinite() && rotatedSar > 0f) { "Proxy rotated pixel aspect ratio must be finite and positive" }
                                    // Media3 1.11 VideoEncoderGraphInput.applyDecoderRotation swaps
                                    // dimensions for quarter turns but leaves SAR unchanged. Its
                                    // frame processor then applies SAR along the wrong axis. Invert
                                    // only this decoded-display descriptor, before that swap. The
                                    // decoder input, source track, samples and color stay untouched.
                                    if (sar == 1f) format else format.buildUpon()
                                        .setPixelWidthHeightRatio(rotatedSar).build()
                                } else format
                                return assetListener.onOutputFormat(displayFormat)
                            }
                        }, settings)
                    }
                    val listener = object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) { outcome.complete(exportResult) }
                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            outcome.completeExceptionally(exportException)
                        }
                        override fun onFallbackApplied(composition: Composition, originalTransformationRequest: TransformationRequest,
                            fallbackTransformationRequest: TransformationRequest) {
                            outcome.completeExceptionally(IllegalStateException("Proxy fallback rejected: $originalTransformationRequest -> $fallbackTransformationRequest"))
                        }
                    }
                    val muxer = InAppMp4Muxer.Factory().setVideoDurationUs(videoDurationUs)
                        .setAttemptStreamableOutputEnabled(false)
                    val transformerBuilder = Transformer.Builder(context).setLooper(thread.looper)
                        .setAssetLoaderFactory(displayGeometryAssetLoaders)
                        .setEncoderFactory(noAudioEncoding).setVideoMimeType(MimeTypes.VIDEO_H264)
                        .setMuxerFactory(sidecar?.wrapMuxer(muxer) ?: muxer)
                        .addListener(listener)
                    if (sidecar != null) transformerBuilder.setAudioMimeType(MimeTypes.AUDIO_AAC)
                    transformer = transformerBuilder.build()
                    val item = EditedMediaItem.Builder(MediaItem.fromUri(source))
                        .setEffects(Effects(emptyList(), listOf(Presentation.createForWidthAndHeight(
                            width, height, Presentation.LAYOUT_SCALE_TO_FIT)))).build()
                    if (sidecar == null) requireNotNull(transformer).start(item, output.absolutePath)
                    else {
                        val audio = EditedMediaItem.Builder(MediaItem.fromUri(sidecar.input.uri))
                            .setRemoveVideo(true).setEffects(Effects(listOf(sidecar.audioProcessor), emptyList())).build()
                        val composition = Composition.Builder(
                            EditedMediaItemSequence.withVideoFrom(listOf(item)),
                            EditedMediaItemSequence.withAudioFrom(listOf(audio))).build()
                        requireNotNull(transformer).start(composition, output.absolutePath)
                    }
                } catch (failure: Throwable) { outcome.completeExceptionally(failure) }
            }) { "Proxy looper did not accept export" }
            return outcome.await()
        } finally {
            withContext(NonCancellable) {
                val retired = CompletableDeferred<Unit>()
                val posted = handler.post {
                    try { transformer?.cancel(); retired.complete(Unit) }
                    catch (failure: Throwable) { retired.completeExceptionally(failure) }
                    finally { thread.quitSafely() }
                }
                try {
                    check(posted) { "Proxy looper did not accept retirement" }
                    retired.await()
                } finally {
                    thread.quitSafely()
                    withContext(Dispatchers.IO) { thread.join() }
                }
            }
        }
    }
}
