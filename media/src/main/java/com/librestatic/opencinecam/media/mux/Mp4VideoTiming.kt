/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.media.mux

/** What the app needs from a recorded video-only MP4 to place an audio track against it. */
class Mp4VideoTiming(
    val sampleCount: Long,
    /** Presentation time of the last-presented sample on the file's timeline (after the track's edit list), microseconds. */
    val lastSampleTimeUs: Long,
    /** Track duration on the file's timeline, microseconds. */
    val durationUs: Long,
    /** The video track's media timescale (`mdhd`). */
    val timescale: Int,
)

/** Reads the timing of the file's first video track. Throws [Mp4FormatException] when the file has none or it cannot be read. */
fun readMp4VideoTiming(file: Mp4Bytes): Mp4VideoTiming {
    val movie = readMovie(file, findSingleMoov(scanTopLevelBoxes(file)))
    val video = movie.tracks.firstOrNull { it.handlerType == "vide" } ?: throw Mp4FormatException("no-video-track")
    val data = movie.bytes
    val mdia = findChild(data, video.trak, "mdia") ?: throw Mp4FormatException("bad-video-track", "no mdia")
    val mdhd = findChild(data, mdia, "mdhd") ?: throw Mp4FormatException("bad-video-track", "no mdhd")
    val mediaTimescale = parseMediaTimescale(data, mdhd)
    if (mediaTimescale > Int.MAX_VALUE) throw Mp4FormatException("bad-timescale", "mdhd timescale $mediaTimescale")
    val stbl = findPath(data, mdia, "minf", "stbl") ?: throw Mp4FormatException("bad-video-track", "no stbl")
    val stts = findChild(data, stbl, "stts") ?: throw Mp4FormatException("bad-video-track", "no stts")
    val ctts = findChild(data, stbl, "ctts")
    val elst = findChild(data, video.trak, "edts")?.let { findChild(data, it, "elst") }

    val sttsEntries = tableEntries(data, stts, 8)
    val sttsBase = stts.payload + 8
    var sampleCount = 0L
    var mediaDuration = 0L
    for (i in 0 until sttsEntries) {
        val count = u32(data, sttsBase + i * 8)
        sampleCount += count
        mediaDuration += count * u32(data, sttsBase + i * 8 + 4)
    }

    val lastPresentation = if (sampleCount == 0L) 0L else lastPresentationTime(data, sttsBase, sttsEntries, ctts)

    // The first non-empty edit's media time is the timeline's zero; empty edits before it delay the whole track.
    var emptyShift = 0L
    var mediaZero = 0L
    if (elst != null) {
        val version = elstVersion(data, elst)
        val entrySize = if (version == 1) 20 else 12
        val entries = tableEntries(data, elst, entrySize)
        val base = elst.payload + 8
        for (i in 0 until entries) {
            val at = base + i * entrySize
            val segmentDuration = if (version == 1) u64(data, at) else u32(data, at)
            val mediaTime = if (version == 1) u64(data, at + 8) else s32(data, at + 4).toLong()
            if (mediaTime == -1L) {
                emptyShift += segmentDuration
            } else {
                mediaZero = mediaTime
                break
            }
        }
    }
    val shiftUs = rescale(emptyShift, movie.header.timescale, 1_000_000)
    return Mp4VideoTiming(
        sampleCount = sampleCount,
        lastSampleTimeUs = rescale(lastPresentation - mediaZero, mediaTimescale, 1_000_000) + shiftUs,
        durationUs = rescale(mediaDuration - mediaZero, mediaTimescale, 1_000_000) + shiftUs,
        timescale = mediaTimescale.toInt(),
    )
}

/** The largest decode time plus composition offset, walking `stts` and `ctts` run by run. */
private fun lastPresentationTime(data: ByteArray, sttsBase: Int, sttsEntries: Int, ctts: Mp4Child?): Long {
    val cttsEntries = if (ctts == null) 0 else tableEntries(data, ctts, 8)
    val cttsSigned = ctts != null && u8(data, ctts.payload) == 1
    val cttsBase = (ctts?.payload ?: 0) + 8
    var latest = Long.MIN_VALUE
    var decodeTime = 0L
    var sttsIndex = 0
    var sttsLeft = 0L
    var delta = 0L
    var cttsIndex = 0
    var cttsLeft = 0L
    var offset = 0L
    while (true) {
        while (sttsLeft == 0L && sttsIndex < sttsEntries) {
            sttsLeft = u32(data, sttsBase + sttsIndex * 8)
            delta = u32(data, sttsBase + sttsIndex * 8 + 4)
            sttsIndex++
        }
        if (sttsLeft == 0L) break
        while (cttsLeft == 0L && cttsIndex < cttsEntries) {
            cttsLeft = u32(data, cttsBase + cttsIndex * 8)
            val at = cttsBase + cttsIndex * 8 + 4
            offset = if (cttsSigned) s32(data, at).toLong() else u32(data, at)
            cttsIndex++
        }
        // A ctts shorter than stts leaves the remaining samples without an offset.
        val runOffset = if (cttsLeft == 0L) 0L else offset
        val run = if (cttsLeft == 0L) sttsLeft else minOf(sttsLeft, cttsLeft)
        // Within a run the decode time only grows, so its last sample presents latest.
        latest = maxOf(latest, decodeTime + (run - 1) * delta + runOffset)
        decodeTime += run * delta
        sttsLeft -= run
        if (cttsLeft > 0L) cttsLeft -= run
    }
    return if (latest == Long.MIN_VALUE) 0L else latest
}

private fun elstVersion(data: ByteArray, elst: Mp4Child): Int {
    elst.requirePayload(8)
    val version = u8(data, elst.payload)
    if (version > 1) throw Mp4FormatException("elst-version", "elst version $version")
    return version
}

/** A full box table's entry count, checked against the box's size. */
internal fun tableEntries(data: ByteArray, table: Mp4Child, entrySize: Int): Int {
    table.requirePayload(8)
    val count = u32(data, table.payload + 4)
    if (count * entrySize > table.size - table.headerSize - 8) throw Mp4FormatException("box-truncated", "'${table.type}' declares $count entries")
    return count.toInt()
}
