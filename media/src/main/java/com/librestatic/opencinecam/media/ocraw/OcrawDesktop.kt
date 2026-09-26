// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 OpenCineCam contributors

package com.librestatic.opencinecam.media.ocraw

import com.librestatic.opencinecam.media.raw.DngFixture
import com.librestatic.opencinecam.media.raw.RawFrame
import com.librestatic.opencinecam.media.raw.RawStillDng
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class OcrawVerification(
    val valid: Boolean,
    val recoveredChunkCount: Int,
    val lastValidOffset: Int,
    val staleEvidence: Boolean,
    val hasFinalIndex: Boolean,
    val hasEndChunk: Boolean,
    val errors: List<String>,
)

/** Host-side reader and recovery facade used by desktop tools and fixtures. */
class OcrawDesktopReader(private val encoded: ByteArray) {
    fun scan(): OcrawScanResult = OcrawCodec.scanContainer(encoded)

    fun rebuildIndex(): List<OcrawIndexEntry> {
        val scan = scan()
        val entries = ArrayList<OcrawIndexEntry>(scan.chunks.size)
        var offset = OcrawCodec.HEADER_BYTES
        scan.chunks.forEach { chunk ->
            if (chunk.knownId() !in setOf(OcrawChunkId.PERIODIC_INDEX, OcrawChunkId.FINAL_INDEX, OcrawChunkId.END)) {
                entries += OcrawIndexEntry(chunk.sequence, offset.toLong(), chunk.timestampTicks)
            }
            offset += OcrawCodec.encodeChunk(chunk).size
        }
        return entries
    }

    fun verify(): OcrawVerification {
        val scan = scan()
        val errors = ArrayList<String>()
        if (scan.header == null) errors += "invalid or truncated header"
        if (scan.staleEvidence) errors += "stale or truncated evidence"
        var previousSequence = -1L
        var finalIndex: List<OcrawIndexEntry>? = null
        var hasEnd = false
        scan.chunks.forEach { chunk ->
            if (chunk.sequence <= previousSequence) errors += "non-monotonic sequence"
            previousSequence = chunk.sequence
            when (chunk.knownId()) {
                OcrawChunkId.FINAL_INDEX -> {
                    try {
                        finalIndex = OcrawIndexCodec.decode(chunk.payload)
                    } catch (error: IllegalArgumentException) {
                        errors += "invalid final index: ${error.message}"
                    }
                }
                OcrawChunkId.END -> hasEnd = true
                else -> Unit
            }
        }
        if (hasEnd && scan.chunks.lastOrNull()?.knownId() != OcrawChunkId.END) {
            errors += "end marker is not final"
        }
        val rebuilt = rebuildIndex()
        if (finalIndex != null && finalIndex != rebuilt) errors += "final index does not match scan"
        return OcrawVerification(
            valid = errors.isEmpty(),
            recoveredChunkCount = scan.chunks.size,
            lastValidOffset = scan.lastValidOffset,
            staleEvidence = scan.staleEvidence,
            hasFinalIndex = finalIndex != null,
            hasEndChunk = hasEnd,
            errors = errors.toList(),
        )
    }

    fun extractPcm16(): ShortArray {
        val payload = scan().chunks
            .filter { it.knownId() == OcrawChunkId.AUDIO_PCM16 }
            .flatMap { it.payload.asIterable() }
            .toByteArray()
        require(payload.size % 2 == 0) { "PCM16 payload has an odd byte count" }
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        return ShortArray(payload.size / 2) { buffer.short }
    }

    fun exportDng(frame: RawFrame): DngFixture = RawStillDng.createFixture(frame)
}

/** Stateless entry points suitable for a command-line wrapper. */
object OcrawRecoveryCli {
    fun recover(encoded: ByteArray): OcrawScanResult = OcrawDesktopReader(encoded).scan()

    fun verify(encoded: ByteArray): OcrawVerification = OcrawDesktopReader(encoded).verify()

    fun rebuildIndex(encoded: ByteArray): List<OcrawIndexEntry> = OcrawDesktopReader(encoded).rebuildIndex()
}
