#!/usr/bin/env python3
"""Recover, verify, index, and extract PCM from an OpenCine RAW v1 file.

This dependency-free CLI intentionally mirrors the documented wire contract so
desktop tooling can inspect a partially written file without importing Android
classes. It never treats a recovered prefix as a finalized recording.
"""

# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 OpenCineCam contributors

from __future__ import annotations

import argparse
import json
import struct
from dataclasses import dataclass
from pathlib import Path


MAGIC = b"OCRAW\x00\x01\x00"
HEADER_BYTES = 32
CHUNK_HEADER_BYTES = 44
MAX_CHUNK_PAYLOAD = 64 * 1024 * 1024
PERIODIC_INDEX = 0x0030
FINAL_INDEX = 0x0031
END = 0x00FF


@dataclass(frozen=True)
class Chunk:
    wire_id: int
    flags: int
    sequence: int
    timestamp_ticks: int
    time_base_numerator: int
    time_base_denominator: int
    payload: bytes
    offset: int


@dataclass(frozen=True)
class Scan:
    header: dict[str, object] | None
    chunks: tuple[Chunk, ...]
    last_valid_offset: int
    stale_evidence: bool


def crc32c(payload: bytes) -> int:
    crc = 0xFFFFFFFF
    for value in payload:
        crc ^= value
        for _ in range(8):
            crc = (crc >> 1) ^ 0x82F63B78 if crc & 1 else crc >> 1
    return (~crc) & 0xFFFFFFFF


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


def scan(data: bytes) -> Scan:
    if len(data) < HEADER_BYTES or data[:8] != MAGIC:
        return Scan(None, (), 0, True)
    version, flags = struct.unpack_from("<HH", data, 8)
    if version != 1:
        return Scan(None, (), 0, True)
    header = {"version": version, "flags": flags, "uuid": data[12:28].hex()}
    chunks: list[Chunk] = []
    offset = HEADER_BYTES
    stale = False
    while offset < len(data):
        if len(data) - offset < CHUNK_HEADER_BYTES:
            stale = True
            break
        fields = struct.unpack_from("<HHQQQQII", data, offset)
        wire_id, chunk_flags, sequence, timestamp, numerator, denominator, payload_length, expected_crc = fields
        if numerator <= 0 or denominator <= 0 or payload_length > MAX_CHUNK_PAYLOAD:
            stale = True
            break
        end = offset + CHUNK_HEADER_BYTES + payload_length
        if end > len(data):
            stale = True
            break
        payload = data[offset + CHUNK_HEADER_BYTES:end]
        if crc32c(payload) != expected_crc:
            stale = True
            break
        chunks.append(Chunk(wire_id, chunk_flags, sequence, timestamp, numerator, denominator, payload, offset))
        offset = end
    return Scan(header, tuple(chunks), offset, stale)


def rebuild_index(result: Scan) -> list[dict[str, int]]:
    return [
        {"sequence": chunk.sequence, "fileOffset": chunk.offset, "timestampTicks": chunk.timestamp_ticks}
        for chunk in result.chunks
        if chunk.wire_id not in {PERIODIC_INDEX, FINAL_INDEX, END}
    ]


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
    return {
        "valid": not errors,
        "recoveredChunkCount": len(result.chunks),
        "lastValidOffset": result.last_valid_offset,
        "staleEvidence": result.stale_evidence,
        "hasFinalIndex": final_index is not None,
        "hasEndChunk": end_seen,
        "errors": errors,
    }


def extract_pcm16(result: Scan) -> bytes:
    payload = b"".join(chunk.payload for chunk in result.chunks if chunk.wire_id == 0x0020)
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
    result = scan(args.input.read_bytes())
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
