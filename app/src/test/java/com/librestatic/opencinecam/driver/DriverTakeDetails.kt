/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.driver

import androidx.exifinterface.media.ExifInterface
import com.librestatic.opencinecam.AudioMetadata
import com.librestatic.opencinecam.DetailFile
import com.librestatic.opencinecam.OcrawSummary
import com.librestatic.opencinecam.TakeDetailInputs
import com.librestatic.opencinecam.media.ocraw.OcrawFrameHeader
import com.librestatic.opencinecam.media.ocraw.OcrawFrameIndex
import com.librestatic.opencinecam.media.ocraw.OcrawHeader
import com.librestatic.opencinecam.media.ocraw.OcrawStreamFrame
import com.librestatic.opencinecam.media.ocraw.OcrawTimeBase
import com.librestatic.opencinecam.parseClipSidecarFacts
import com.librestatic.opencinecam.playback.ClipMetadata
import com.librestatic.opencinecam.storage.LocalMediaKind
import com.librestatic.opencinecam.storage.LocalMediaTake
import com.librestatic.opencinecam.storage.drivePath
import com.librestatic.opencinecam.storage.parseOcLogSidecar

// What the inspector's readers would have found in each sample take's files, fed to the real buildTakeDetails.

private const val Storage = "/storage/emulated/0"

private fun files(take: LocalMediaTake, folder: String) = take.originals.map { DetailFile(it, "$Storage/$folder/${it.name}") }

/** Take 1: a LOG take with its OCLog sidecar, a probe of the file and a full clip sidecar with the camera block. */
internal fun logTakeInputs(take: LocalMediaTake) = TakeDetailInputs(LocalMediaKind.VIDEO,
    files(take, "DCIM/OpenCineCam/${take.primary.name.substringBeforeLast('.')}"),
    clip = parseClipSidecarFacts(LogClipJson),
    video = ClipMetadata(3840, 2160, 24.0, "HEVC 10-bit", 10, "OCLog2 · BT.2020", 83_000_000L),
    mediaStoreBitrate = 142_650_000L,
    log = parseOcLogSidecar("""{"schema":"opencinecam-oclog-sidecar-v2","videoUri":"${take.primary.uri}",
        "transform":{"curve":"OCLog2","gamut":"BT.2020","version":"2.1"},"encoding":{"range":"full","codecName":"HEVC","profile":"Main10"}}""",
        take.primary.uri),
    audio = AudioMetadata("audio/mp4a-latm", 48_000, 2, null, bitRate = 256_000, durationUs = 83_000_000L))

/** Take 7: a plain recording with no clip sidecar, so everything comes from probing the file. */
internal fun probedTakeInputs(take: LocalMediaTake) = TakeDetailInputs(LocalMediaKind.VIDEO, files(take, "DCIM/OpenCineCam"),
    video = ClipMetadata(1920, 1080, 29.97, "H.264", 8, "SDR · BT.709", 42_000_000L), mediaStoreBitrate = 121_900_000L,
    audio = AudioMetadata("audio/mp4a-latm", 48_000, 2, null, bitRate = 192_000, durationUs = 42_000_000L))

/** A take on the recording drive: paths are the drive's name and the document's path, the bit rate the container's. */
internal fun driveTakeInputs(take: LocalMediaTake) = TakeDetailInputs(LocalMediaKind.VIDEO,
    take.originals.map { DetailFile(it, drivePath("WD Elements", it.uri)) },
    video = ClipMetadata(3840, 2160, 25.0, "HEVC 10-bit", 10, "SDR · BT.709", 96_000_000L), mediaStoreBitrate = 150_200_000L,
    audio = AudioMetadata("audio/raw", 48_000, 2, 24, durationUs = 96_000_000L))

/** A separate recorder track: [wav] is 24-bit PCM, otherwise AAC in MP4. */
internal fun audioTakeInputs(take: LocalMediaTake, wav: Boolean) = TakeDetailInputs(LocalMediaKind.AUDIO, files(take, "Music/OpenCineCam"),
    audio = if (wav) AudioMetadata("audio/raw", 48_000, 2, 24, durationUs = 75_000_000L)
        else AudioMetadata("audio/mp4a-latm", 48_000, 1, null, bitRate = 128_000, durationUs = 62_000_000L))

/** A still's EXIF: [dng] is the RAW photo with its colour filter pattern, otherwise the processed JPEG. */
internal fun photoTakeInputs(take: LocalMediaTake, dng: Boolean): TakeDetailInputs {
    val tags = buildMap {
        put(ExifInterface.TAG_PIXEL_X_DIMENSION, "4080"); put(ExifInterface.TAG_PIXEL_Y_DIMENSION, "3060")
        put(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, if (dng) "200" else "100")
        put(ExifInterface.TAG_EXPOSURE_TIME, if (dng) "0.008333" else "0.004")
        put(ExifInterface.TAG_F_NUMBER, "1.7"); put(ExifInterface.TAG_FOCAL_LENGTH, "6300/1000"); put(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, "24")
        put(ExifInterface.TAG_WHITE_BALANCE, if (dng) "1" else "0"); put(ExifInterface.TAG_FLASH, "16")
        put(ExifInterface.TAG_MAKE, "motorola"); put(ExifInterface.TAG_MODEL, "razr 50 ultra")
        put(ExifInterface.TAG_DATETIME_ORIGINAL, if (dng) "2026:09:30 18:15:22" else "2026:09:30 18:14:10")
        put(ExifInterface.TAG_EXPOSURE_BIAS_VALUE, if (dng) "0/1" else "-1/3"); put(ExifInterface.TAG_SOFTWARE, "OpenCineCam 1.1.0")
        if (dng) { put(ExifInterface.TAG_BITS_PER_SAMPLE, "16"); put(ExifInterface.TAG_CFA_PATTERN, "2,0,2,0,0,1,1,2") }
        else put(ExifInterface.TAG_COLOR_SPACE, "1")
    }
    return TakeDetailInputs(LocalMediaKind.PHOTO, files(take, "DCIM/OpenCineCam"), exif = tags::get)
}

/**
 * A RAW take's summary from a constructed frame index: [compressed] is a near-lossless 3840×2160 take at
 * 30 fps that lost two frames; otherwise a clean full-sensor RAW10 take at 24 fps.
 */
internal fun rawTakeInputs(take: LocalMediaTake, compressed: Boolean): TakeDetailInputs {
    val width = if (compressed) 3840 else 4080
    val height = if (compressed) 2160 else 3060
    val fps = if (compressed) 30 else 24
    val count = if (compressed) 990 else 1259
    val intervalNs = 1_000_000_000L / fps
    val numbers = (0 until count).map { if (compressed && it >= 600) it + 2L else it.toLong() }
    val encoding = if (compressed) OcrawFrameHeader.ENCODING_BLOCK_PACK_QUANTISED else OcrawFrameHeader.ENCODING_RAW10
    val dataLength = if (compressed) (take.primary.sizeBytes / count).toInt() else width * 10 / 8 * height
    val frames = numbers.mapIndexed { i, number ->
        val timestamp = 5_000_000_000L + number * intervalNs
        OcrawStreamFrame(i.toLong(), 4_096L + i * dataLength.toLong(), timestamp, OcrawFrameHeader(width, height, width * 10 / 8, 0, 0,
            timestamp, number, dataLength, encoding))
    }
    val metadata = mapOf("fps" to "$fps/1", "encoding" to if (compressed) "bpq" else "raw10", "orientation" to "0", "cfa" to "RGGB",
        "blackLevel" to "64 64 64 64", "whiteLevel" to "1023", "cameraId" to "0", "model" to "motorola razr 50 ultra", "appVersion" to "1.1.0",
        "fingerprint" to "motorola/blanc_g/blanc:15/V1UUI35H.14-14-2/7c41a9:user/release-keys", "focalLength" to "6.3", "aperture" to "1.7",
        "audioSampleRate" to "48000", "audioChannels" to "2", "audioSource" to "CAMCORDER", "audioDroppedSamples" to "0")
    val perFrame = mapOf(0L to mapOf("iso" to "400", "exposureNs" to "20833333"), (count / 2).toLong() to mapOf("iso" to "640", "exposureNs" to "20833333"),
        (count - 1).toLong() to mapOf("iso" to "800", "exposureNs" to "16666667"))
    val index = OcrawFrameIndex(OcrawHeader(ByteArray(16)), take.primary.sizeBytes, take.primary.sizeBytes, tornTail = false, frames = frames,
        metadata = metadata, frameMetadata = perFrame, timeBase = OcrawTimeBase(1, 1_000_000_000), hasEnd = true, errors = emptyList())
    return TakeDetailInputs(LocalMediaKind.RAW_VIDEO, files(take, "Documents/OpenCineCam/RAW"),
        raw = OcrawSummary.from(metadata, index, take.primary.sizeBytes))
}

private val LogClipJson = """
    {"schemaVersion":"1.0.0","recordingId":"7f3c9a2e-41d8-4b6f-9e0a-2c5d8b1f6a43","createdAt":"2026-09-30T18:40:12Z",
     "intent":{"recordingPolicy":{"knowledge":"known","value":"STRICT"},"captureMode":{"knowledge":"known","value":"LOG"},
       "requestedSize":{"knowledge":"known","value":{"width":3840,"height":2160}},"requestedFps":{"knowledge":"known","value":24},
       "projectRate":{"knowledge":"known","value":"24"},
       "videoCodecPreference":{"knowledge":"known","value":"HEVC"},"videoBitrateMbps":{"knowledge":"known","value":150},
       "audio":{"enabled":{"knowledge":"known","value":true},"requestedFormat":{"knowledge":"known","value":"AAC"},"sampleRateHz":48000,"channels":2},
       "lutSelectionId":{"knowledge":"known","value":"oclog2-to-rec709"},"timecodeEnabled":{"knowledge":"known","value":true}},
     "resolvedGraph":{"cameraId":{"knowledge":"known","value":"0"},
       "recordingRoute":{"knowledge":"known","value":"MediaCodec"},
       "video":{"codecMime":{"knowledge":"known","value":"video/hevc"},"encoderName":{"knowledge":"known","value":"c2.qti.hevc.encoder"},
         "hardwareAccelerated":{"knowledge":"known","value":true},"softwareEncoder":false,
         "encodedSize":{"knowledge":"known","value":{"width":3840,"height":2160}},"fps":{"knowledge":"known","value":24},
         "containerRotationDegrees":{"knowledge":"known","value":0}},
       "color":{"knowledge":"known","value":{"kind":"OCLog2","transfer":"OCLog2","gamut":"BT.2020","profile":"HEVC Main10"}},
       "audio":{"storage":"EMBEDDED_AAC","sampleRateHz":48000},
       "deviceProfile":{"knowledge":"known","value":{"profileId":"blanc","status":"ACTIVE","appliedQuirkIds":["qti-hfr-session","qti-raw-60"],"buildCheck":"VERIFIED"}},
       "camera":{"knowledge":"known","value":{"cameraId":"0","focalLengthMm":6.3,"focalLength35mm":24.1,"aperture":1.7,
         "exposureMode":{"requested":"SHUTTER","reported":"MANUAL"},"iso":{"start":400,"end":800,"min":400,"max":1250},
         "exposureTimeNs":{"start":20833333,"end":20833333,"min":20833333,"max":20833333},
         "whiteBalance":{"mode":"KELVIN","kelvin":5600,"reportedCctStart":5400,"reportedCctEnd":5450,"tint":0},
         "focus":{"afMode":"CONTINUOUS_VIDEO","distanceDiopters":0.5},"zoom":{"start":1.0,"end":2.0},
         "stabilization":{"ois":"ON","eis":"OFF"},"processing":{"noiseReduction":"FAST","edge":"FAST","toneMap":"FAST"}}}},
     "events":[],"validation":[],"media":{"displayName":"OCC_TAKE_A001_S12_T03.mp4","durationMs":83000,"encodedFrames":1992}}
""".trimIndent()
