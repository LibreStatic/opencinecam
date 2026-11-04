#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 OpenCineCam contributors

import importlib.util
import struct
import sys
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).parents[2] / "desktop-tools" / "ocraw_recovery.py"
SPEC = importlib.util.spec_from_file_location("ocraw_recovery", MODULE_PATH)
assert SPEC and SPEC.loader
ocraw = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = ocraw
SPEC.loader.exec_module(ocraw)


def chunk(wire_id: int, sequence: int, payload: bytes) -> bytes:
    header = struct.pack("<HHQQQQII", wire_id, 0, sequence, sequence, 1, 48_000, len(payload), ocraw.crc32c(payload))
    return header + payload


def cbor_text_map(values: dict[str, str]) -> bytes:
    def head(major: int, length: int) -> bytes:
        if length < 24:
            return bytes([(major << 5) | length])
        if length <= 0xFF:
            return bytes([(major << 5) | 24, length])
        return bytes([(major << 5) | 25]) + length.to_bytes(2, "big")

    out = head(5, len(values))
    for key in sorted(values):
        for text in (key, values[key]):
            encoded = text.encode("utf-8")
            out += head(3, len(encoded)) + encoded
    return out


def raw10_frame(width: int, height: int, frame_number: int, version: int = 1) -> bytes:
    row_bytes = width * 5 // 4
    data = bytes((frame_number + i) & 0xFF for i in range(row_bytes * height))
    header = struct.pack("<HHIIIIIQQI", version, 0, width, height, row_bytes, 8, 4,
                         1_000_000 * frame_number, frame_number, len(data))
    return header.ljust(64, b"\x00") + data


def index_payload(entries: list[tuple[int, int, int]]) -> bytes:
    return struct.pack("<I", len(entries)) + b"".join(struct.pack("<QQQ", *entry) for entry in entries)


def video_take(frames: int = 3) -> tuple[bytes, list[int]]:
    """A cleanly closed take as OcrawStreamWriter lays it out; returns it and the chunk end offsets."""
    data = ocraw.MAGIC + struct.pack("<HH", 1, 0) + bytes(20)
    ends: list[int] = []
    entries: list[tuple[int, int, int]] = []
    sequence = 0

    def append(wire_id: int, payload: bytes, indexed: bool = True) -> None:
        nonlocal data, sequence
        if indexed:
            entries.append((sequence, len(data), sequence))
        data += chunk(wire_id, sequence, payload)
        sequence += 1
        ends.append(len(data))

    append(ocraw.METADATA, cbor_text_map({"cfa": "RGGB", "whiteLevel": "1023"}))
    for number in range(frames):
        frame_sequence = sequence
        append(ocraw.RAW_FRAME, raw10_frame(16, 4, number))
        append(ocraw.FRAME_METADATA, cbor_text_map({"sequence": str(frame_sequence), "iso": "400"}))
    append(ocraw.PERIODIC_INDEX, index_payload(entries), indexed=False)
    append(ocraw.FINAL_INDEX, index_payload(entries), indexed=False)
    append(ocraw.END, b"", indexed=False)
    return data, ends


class OcrawRecoveryTest(unittest.TestCase):
    def test_scan_and_rebuild_index(self):
        data = ocraw.MAGIC + struct.pack("<HH", 1, 0) + bytes(20)
        data += chunk(0x0001, 1, b"meta") + chunk(0x0020, 2, b"\x01\x00\x02\x00")
        result = ocraw.scan(data)
        self.assertFalse(result.stale_evidence)
        self.assertEqual([1, 2], [entry["sequence"] for entry in ocraw.rebuild_index(result)])
        self.assertEqual(b"\x01\x00\x02\x00", ocraw.extract_pcm16(result))

    def test_truncated_and_corrupt_payloads_are_stale(self):
        data = ocraw.MAGIC + struct.pack("<HH", 1, 0) + bytes(20) + chunk(0x0001, 1, b"meta")
        self.assertTrue(ocraw.scan(data[:-1]).stale_evidence)
        corrupt = bytearray(data)
        corrupt[-1] ^= 1
        self.assertTrue(ocraw.scan(bytes(corrupt)).stale_evidence)

    def test_invalid_version_is_rejected(self):
        data = ocraw.MAGIC + struct.pack("<HH", 2, 0) + bytes(20)
        result = ocraw.scan(data)
        self.assertIsNone(result.header)
        self.assertFalse(ocraw.verify(result)["valid"])

    def test_crc32c_check_value(self):
        self.assertEqual(0xE3069283, ocraw.crc32c(b"123456789"))
        self.assertEqual(0x8A9136AA, ocraw.crc32c(bytes(32)))

    def test_raw_video_take_reports_frames_and_metadata(self):
        data, _ = video_take()
        result = ocraw.scan(data)
        report = ocraw.verify(result)
        self.assertTrue(report["valid"], report["errors"])
        self.assertTrue(report["hasFinalIndex"])
        self.assertTrue(report["hasEndChunk"])
        self.assertEqual(3, report["frameCount"])
        self.assertEqual({"16x4": 3}, report["frameDimensions"])
        self.assertEqual({"raw10": 3}, report["frameEncodings"])
        self.assertEqual(3, report["frameMetadataCount"])
        self.assertEqual(1, report["periodicIndexCount"])
        self.assertEqual({"cfa": "RGGB", "whiteLevel": "1023"}, report["metadata"])
        frame = next(chunk for chunk in result.chunks if chunk.wire_id == ocraw.RAW_FRAME)
        self.assertEqual(64, len(frame.payload))
        self.assertEqual(64 + 20 * 4, frame.payload_length)
        header = ocraw.decode_frame_header(frame.payload)
        self.assertEqual((16, 4, 20, "raw10"), (header["width"], header["height"], header["rowBytes"],
                                                header["encoding"]))

    def test_truncated_take_keeps_every_complete_frame(self):
        data, ends = video_take()
        frame_ends = [end for end, wire in zip(ends, [1, 16, 64, 16, 64, 16, 64, 48, 49, 255]) if wire == 16]
        for cut in range(ocraw.HEADER_BYTES, len(data), 37):
            result = ocraw.scan(data[:cut])
            expected = sum(1 for end in frame_ends if end <= cut)
            self.assertEqual(expected, ocraw.summarize_frames(result)["frameCount"], cut)
            self.assertEqual(max([ocraw.HEADER_BYTES] + [end for end in ends if end <= cut]),
                             result.last_valid_offset)
            self.assertEqual(cut not in ends and cut != ocraw.HEADER_BYTES, result.stale_evidence)

    def test_scan_file_streams_from_disk(self):
        data, _ = video_take(frames=5)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "take.ocraw"
            path.write_bytes(data)
            from_file = ocraw.scan_file(path)
        self.assertEqual(ocraw.scan(data), from_file)
        self.assertEqual(5, ocraw.verify(from_file)["frameCount"])

    def test_unreadable_frame_header_is_reported(self):
        data = ocraw.MAGIC + struct.pack("<HH", 1, 0) + bytes(20)
        data += chunk(ocraw.RAW_FRAME, 0, raw10_frame(16, 2, 0, version=9))
        report = ocraw.verify(ocraw.scan(data))
        self.assertEqual(1, report["frameCount"])
        self.assertEqual([0], report["invalidFrameHeaders"])
        self.assertEqual({}, report["frameDimensions"])
        corrupt = bytearray(data)
        corrupt[-3] ^= 0x40
        self.assertTrue(ocraw.scan(bytes(corrupt)).stale_evidence)


if __name__ == "__main__":
    unittest.main()
