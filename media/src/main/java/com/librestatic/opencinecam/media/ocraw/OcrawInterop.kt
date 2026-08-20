// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 OpenCineCam contributors

package com.librestatic.opencinecam.media.ocraw

data class OcrawInteropCase(
    val name: String,
    val passed: Boolean,
    val detail: String,
)

/** Deterministic crash, corruption, truncation, and forward-compatibility checks. */
object OcrawInteropValidator {
    fun run(header: OcrawHeader, chunks: List<OcrawChunk>): List<OcrawInteropCase> {
        require(chunks.size <= 0xffff) { "too many interoperability chunks" }
        require(chunks.none { it.sequence == Long.MAX_VALUE }) { "sequence leaves no room for extension chunk" }
        val complete = OcrawCodec.encodeContainer(header, chunks)
        val cases = ArrayList<OcrawInteropCase>()
        val completeVerification = OcrawDesktopReader(complete).verify()
        cases += OcrawInteropCase(
            "complete-interoperability",
            completeVerification.valid && completeVerification.recoveredChunkCount == chunks.size,
            completeVerification.errors.joinToString(),
        )

        val truncated = complete.copyOf((complete.size - 1).coerceAtLeast(0))
        val truncatedScan = OcrawDesktopReader(truncated).scan()
        cases += OcrawInteropCase(
            "truncation-recovery",
            truncatedScan.staleEvidence && truncatedScan.chunks.size == (chunks.size - 1).coerceAtLeast(0),
            "recovered=${truncatedScan.chunks.size}",
        )

        val bitFlipped = complete.copyOf()
        if (chunks.isNotEmpty()) {
            val firstPayloadOrCrc = OcrawCodec.HEADER_BYTES + if (chunks.first().payload.isNotEmpty()) {
                OcrawCodec.CHUNK_HEADER_BYTES
            } else {
                OcrawCodec.CHUNK_HEADER_BYTES - Int.SIZE_BYTES
            }
            bitFlipped[firstPayloadOrCrc] = (bitFlipped[firstPayloadOrCrc].toInt() xor 0x01).toByte()
        }
        val bitFlipScan = OcrawDesktopReader(bitFlipped).scan()
        cases += OcrawInteropCase(
            "bit-flip-detected",
            chunks.isEmpty() || bitFlipScan.staleEvidence,
            "stale=${bitFlipScan.staleEvidence}",
        )

        val interruptedWriter = OcrawAppendJournal(maxChunks = chunks.size.coerceAtLeast(1), journalInterval = 2)
        val appendStatuses = chunks.mapIndexed { index, chunk -> interruptedWriter.append(index + 1L, chunk) }
        val interrupted = interruptedWriter.interrupt(header)
        val interruptedScan = OcrawDesktopReader(interrupted.bytes).scan()
        cases += OcrawInteropCase(
            "crash-interruption-recovery",
            interrupted.outcome == OcrawWriteOutcome.INTERRUPTED &&
                appendStatuses.all { it == OcrawAppendStatus.APPENDED } &&
                interruptedScan.chunks.size == chunks.size &&
                !interruptedScan.chunks.any { it.knownId() == OcrawChunkId.END },
            "chunks=${interruptedScan.chunks.size}",
        )

        val unknown = OcrawChunk(0x7fff, (chunks.maxOfOrNull { it.sequence } ?: 0) + 1, 0,
            OcrawTimeBase(1, 1), byteArrayOf(0x55))
        val unknownScan = OcrawDesktopReader(OcrawCodec.encodeContainer(header, chunks + unknown)).scan()
        cases += OcrawInteropCase(
            "unknown-chunk-forward-read",
            unknownScan.chunks.lastOrNull()?.wireId == unknown.wireId && !unknownScan.staleEvidence,
            "wireId=${unknownScan.chunks.lastOrNull()?.wireId}",
        )

        val largePayload = ByteArray(OcrawCodec.MAX_CHUNK_PAYLOAD)
        val large = OcrawChunk(OcrawChunkId.RAW_FRAME.wireId, 1, 0, OcrawTimeBase(1, 1), largePayload)
        val largeScan = OcrawDesktopReader(OcrawCodec.encodeContainer(header, listOf(large))).scan()
        cases += OcrawInteropCase(
            "bounded-large-chunk",
            largeScan.chunks.size == 1 && !largeScan.staleEvidence,
            "bytes=${largePayload.size}",
        )
        return cases
    }
}
