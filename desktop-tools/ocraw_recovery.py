#!/usr/bin/env python3
"""Recover, verify, index, and extract PCM from an OpenCine RAW v1 file.

This dependency-free CLI intentionally mirrors the documented wire contract so
desktop tooling can inspect a partially written file without importing Android
classes. It never treats a recovered prefix as a finalized recording.

The file is streamed chunk by chunk, so multi-gigabyte RAW video takes are
never loaded whole; RAW frame payloads are CRC-checked and then dropped, only
their 64-byte frame header is kept.
"""

# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 OpenCineCam contributors

from __future__ import annotations

import argparse
import io
import json
import struct
from dataclasses import dataclass
from pathlib import Path
from typing import BinaryIO


MAGIC = b"OCRAW\x00\x01\x00"
HEADER_BYTES = 32
CHUNK_HEADER_BYTES = 44
MAX_CHUNK_PAYLOAD = 64 * 1024 * 1024
METADATA = 0x0001
RAW_FRAME = 0x0010
AUDIO_PCM16 = 0x0020
PERIODIC_INDEX = 0x0030
FINAL_INDEX = 0x0031
FRAME_METADATA = 0x0040
END = 0x00FF
FRAME_HEADER_BYTES = 64
FRAME_HEADER_VERSION = 1
ENCODINGS = {0: "raw10", 1: "lj92", 2: "bayer-block-pack", 3: "bayer-block-pack-quantised"}
READ_BLOCK = 4 * 1024 * 1024


@dataclass(frozen=True)
class Chunk:
    wire_id: int
    flags: int
    sequence: int
    timestamp_ticks: int
    time_base_numerator: int
    time_base_denominator: int
    # Whole payload, except RAW_FRAME chunks keep only their 64-byte frame header.
    payload: bytes
    offset: int
    payload_length: int = -1


@dataclass(frozen=True)
class Scan:
    header: dict[str, object] | None
    chunks: tuple[Chunk, ...]
    last_valid_offset: int
    stale_evidence: bool


def _crc_table() -> list[int]:
    table = []
    for value in range(256):
        crc = value
        for _ in range(8):
            crc = (crc >> 1) ^ 0x82F63B78 if crc & 1 else crc >> 1
        table.append(crc)
    return table


_CRC_TABLE = _crc_table()


def crc32c_update(crc: int, payload: bytes) -> int:
    """Advances a raw (pre-inversion) CRC32C state; start from 0xFFFFFFFF."""
    table = _CRC_TABLE
    for value in payload:
        crc = (crc >> 8) ^ table[(crc ^ value) & 0xFF]
    return crc


def crc32c(payload: bytes) -> int:
    return crc32c_update(0xFFFFFFFF, payload) ^ 0xFFFFFFFF


def decode_index(payload: bytes) -> list[dict[str, int]]:
    if len(payload) < 4:
        raise ValueError("truncated index payload")
    count = struct.unpack_from("<I", payload)[0]
    expected = 4 + count * 24
    if expected != len(payload):
        raise ValueError("invalid index payload")
    entries = []
    for offset in range(4, len(payload), 24):
        sequence, file_offset, timestamp = struct.unpack_from("<QQQ", payload, offset)
        entries.append({"sequence": sequence, "fileOffset": file_offset, "timestampTicks": timestamp})
    return entries


def _cbor_length(data: bytes, offset: int) -> tuple[int, int, int]:
    if offset >= len(data):
        raise ValueError("truncated CBOR")
    initial = data[offset]
    major, additional = initial >> 5, initial & 0x1F
    if additional < 24:
        return major, additional, offset + 1
    sizes = {24: 1, 25: 2, 26: 4, 27: 8}
    if additional not in sizes:
        raise ValueError("unsupported CBOR length")
    end = offset + 1 + sizes[additional]
    if end > len(data):
        raise ValueError("truncated CBOR")
    return major, int.from_bytes(data[offset + 1:end], "big"), end


def decode_text_map(payload: bytes) -> dict[str, str]:
    """Decodes the canonical CBOR map of text keys to text values used by metadata chunks."""
    major, count, offset = _cbor_length(payload, 0)
    if major != 5:
        raise ValueError("metadata must be a CBOR map")
    texts: list[str] = []
    for _ in range(count * 2):
        major, length, offset = _cbor_length(payload, offset)
        if major != 3 or offset + length > len(payload):
            raise ValueError("metadata entries must be text")
        texts.append(payload[offset:offset + length].decode("utf-8"))
        offset += length
    if offset != len(payload):
        raise ValueError("trailing metadata bytes")
    return dict(zip(texts[0::2], texts[1::2]))


def decode_frame_header(payload: bytes) -> dict[str, int | str]:
    """Decodes the 64-byte RAW_FRAME header; raises ValueError when it is not v1."""
    if len(payload) < FRAME_HEADER_BYTES:
        raise ValueError("truncated frame header")
    (version, encoding, width, height, row_bytes, crop_left, crop_top,
     timestamp, frame_number, data_length) = struct.unpack_from("<HHIIIIIQQI", payload)
    if version != FRAME_HEADER_VERSION:
        raise ValueError(f"unsupported frame header version {version}")
    if width <= 0 or height <= 0 or row_bytes <= 0:
        raise ValueError("invalid frame dimensions")
    if encoding == 0 and (width % 4 or row_bytes != width * 5 // 4 or data_length != row_bytes * height):
        raise ValueError("invalid RAW10 geometry")
    return {
        "version": version,
        "encoding": ENCODINGS.get(encoding, f"unknown-{encoding}"),
        "width": width,
        "height": height,
        "rowBytes": row_bytes,
        "cropLeft": crop_left,
        "cropTop": crop_top,
        "sensorTimestampNs": timestamp,
        "frameNumber": frame_number,
        "dataLength": data_length,
    }


def scan_stream(stream: BinaryIO, size: int | None = None, verify_raw_crc: bool = True) -> Scan:
    """Scans a seekable binary stream chunk by chunk, stopping at the first invalid chunk.

    With `verify_raw_crc` false, RAW_FRAME payloads are bounds-checked but not
    read past their frame header or CRC-checked, which skips the slow
    pure-Python CRC32C for callers that accept that risk; every other chunk is
    still verified.
    """
    if size is None:
        stream.seek(0, io.SEEK_END)
        size = stream.tell()
    stream.seek(0)
    head = stream.read(HEADER_BYTES)
    if len(head) < HEADER_BYTES or head[:8] != MAGIC:
        return Scan(None, (), 0, True)
    version, flags = struct.unpack_from("<HH", head, 8)
    if version != 1:
        return Scan(None, (), 0, True)
    header = {"version": version, "flags": flags, "uuid": head[12:28].hex()}
    chunks: list[Chunk] = []
    offset = HEADER_BYTES
    stale = False
    while offset < size:
        if size - offset < CHUNK_HEADER_BYTES:
            stale = True
            break
        stream.seek(offset)
        fields = struct.unpack("<HHQQQQII", stream.read(CHUNK_HEADER_BYTES))
        wire_id, chunk_flags, sequence, timestamp, numerator, denominator, payload_length, expected_crc = fields
        if numerator <= 0 or denominator <= 0 or payload_length > MAX_CHUNK_PAYLOAD:
            stale = True
            break
        end = offset + CHUNK_HEADER_BYTES + payload_length
        if end > size:
            stale = True
            break
        if wire_id == RAW_FRAME and not verify_raw_crc:
            payload = stream.read(min(FRAME_HEADER_BYTES, payload_length))
        elif wire_id == RAW_FRAME:
            crc = 0xFFFFFFFF
            kept = b""
            remaining = payload_length
            while remaining:
                block = stream.read(min(READ_BLOCK, remaining))
                if not block:
                    break
                if not kept:
                    kept = block[:FRAME_HEADER_BYTES]
                crc = crc32c_update(crc, block)
                remaining -= len(block)
            if remaining or (crc ^ 0xFFFFFFFF) != expected_crc:
                stale = True
                break
            payload = kept
        else:
            payload = stream.read(payload_length)
            if len(payload) != payload_length or crc32c(payload) != expected_crc:
                stale = True
                break
        chunks.append(Chunk(wire_id, chunk_flags, sequence, timestamp, numerator, denominator, payload, offset,
                            payload_length))
        offset = end
    return Scan(header, tuple(chunks), offset, stale)


def scan(data: bytes) -> Scan:
    return scan_stream(io.BytesIO(data), len(data))


def scan_file(path: Path) -> Scan:
    with path.open("rb") as stream:
        return scan_stream(stream)


def rebuild_index(result: Scan) -> list[dict[str, int]]:
    return [
        {"sequence": chunk.sequence, "fileOffset": chunk.offset, "timestampTicks": chunk.timestamp_ticks}
        for chunk in result.chunks
        if chunk.wire_id not in {PERIODIC_INDEX, FINAL_INDEX, END}
    ]


def summarize_frames(result: Scan) -> dict[str, object]:
    """Frame count, dimensions and encodings of the RAW_FRAME chunks, plus metadata."""
    dimensions: dict[str, int] = {}
    encodings: dict[str, int] = {}
    invalid: list[int] = []
    frames = 0
    frame_metadata = 0
    metadata: dict[str, str] = {}
    errors: list[str] = []
    for chunk in result.chunks:
        if chunk.wire_id == RAW_FRAME:
            frames += 1
            try:
                frame = decode_frame_header(chunk.payload)
                if frame["dataLength"] != chunk.payload_length - FRAME_HEADER_BYTES:
                    raise ValueError("frame data length disagrees with the chunk")
            except ValueError:
                invalid.append(chunk.sequence)
                continue
            key = f"{frame['width']}x{frame['height']}"
            dimensions[key] = dimensions.get(key, 0) + 1
            encodings[str(frame["encoding"])] = encodings.get(str(frame["encoding"]), 0) + 1
        elif chunk.wire_id == METADATA:
            try:
                metadata.update(decode_text_map(chunk.payload))
            except (ValueError, UnicodeDecodeError) as error:
                errors.append(f"metadata {chunk.sequence}: {error}")
        elif chunk.wire_id == FRAME_METADATA:
            try:
                if "sequence" not in decode_text_map(chunk.payload):
                    raise ValueError("missing sequence")
                frame_metadata += 1
            except (ValueError, UnicodeDecodeError) as error:
                errors.append(f"frame metadata {chunk.sequence}: {error}")
    return {
        "frameCount": frames,
        "frameDimensions": dimensions,
        "frameEncodings": encodings,
        "invalidFrameHeaders": invalid,
        "frameMetadataCount": frame_metadata,
        "metadata": metadata,
        "periodicIndexCount": sum(1 for chunk in result.chunks if chunk.wire_id == PERIODIC_INDEX),
        "payloadErrors": errors,
    }


def verify(result: Scan) -> dict[str, object]:
    errors: list[str] = []
    if result.header is None:
        errors.append("invalid or truncated header")
    if result.stale_evidence:
        errors.append("stale or truncated evidence")
    previous = -1
    final_index: list[dict[str, int]] | None = None
    end_seen = False
    for chunk in result.chunks:
        if chunk.sequence <= previous:
            errors.append("non-monotonic sequence")
        previous = chunk.sequence
        if chunk.wire_id == FINAL_INDEX:
            try:
                final_index = decode_index(chunk.payload)
            except ValueError as error:
                errors.append(f"invalid final index: {error}")
        if chunk.wire_id == END:
            end_seen = True
    if end_seen and result.chunks[-1].wire_id != END:
        errors.append("end marker is not final")
    rebuilt = rebuild_index(result)
    if final_index is not None and final_index != rebuilt:
        errors.append("final index does not match scan")
    # Payload problems inside CRC-valid chunks are reported, not container errors,
    # matching the Kotlin readers.
    summary = summarize_frames(result)
    return {
        "valid": not errors,
        "recoveredChunkCount": len(result.chunks),
        "lastValidOffset": result.last_valid_offset,
        "staleEvidence": result.stale_evidence,
        "hasFinalIndex": final_index is not None,
        "hasEndChunk": end_seen,
        "frameCount": summary["frameCount"],
        "frameDimensions": summary["frameDimensions"],
        "frameEncodings": summary["frameEncodings"],
        "frameMetadataCount": summary["frameMetadataCount"],
        "periodicIndexCount": summary["periodicIndexCount"],
        "invalidFrameHeaders": summary["invalidFrameHeaders"],
        "metadata": summary["metadata"],
        "payloadErrors": summary["payloadErrors"],
        "errors": errors,
    }


def extract_pcm16(result: Scan) -> bytes:
    payload = b"".join(chunk.payload for chunk in result.chunks if chunk.wire_id == AUDIO_PCM16)
    if len(payload) % 2:
        raise ValueError("PCM16 payload has an odd byte count")
    return payload


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=Path)
    parser.add_argument("--pcm16-output", type=Path)
    parser.add_argument("--index-output", type=Path)
    args = parser.parse_args()
    if not args.input.is_file():
        parser.error("input must be a file")
    result = scan_file(args.input)
    report = verify(result)
    report["header"] = result.header
    report["index"] = rebuild_index(result)
    print(json.dumps(report, sort_keys=True))
    if args.index_output:
        args.index_output.write_text(json.dumps(report["index"], sort_keys=True) + "\n", encoding="utf-8")
    if args.pcm16_output:
        args.pcm16_output.write_bytes(extract_pcm16(result))
    return 0 if report["valid"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
