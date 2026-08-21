#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 OpenCineCam contributors

import importlib.util
import struct
import sys
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).parents[1] / "validate_dng.py"
SPEC = importlib.util.spec_from_file_location("validate_dng", MODULE_PATH)
assert SPEC and SPEC.loader
dng = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = dng
SPEC.loader.exec_module(dng)


def fixture() -> bytes:
    # Minimal little-endian TIFF/DNG with one 2x2, 16-bit strip.
    entries = [
        (256, 4, 1, struct.pack("<I", 2)),
        (257, 4, 1, struct.pack("<I", 2)),
        (258, 3, 1, struct.pack("<H", 16) + b"\0\0"),
        (273, 4, 1, struct.pack("<I", 200)),
        (279, 4, 1, struct.pack("<I", 8)),
        (33421, 3, 2, struct.pack("<HH", 2, 2)),
        (33422, 1, 4, bytes((2, 1, 1, 0))),
        (50706, 1, 4, bytes((1, 4, 0, 0))),
    ]
    data = bytearray(b"II" + struct.pack("<H", 42) + struct.pack("<I", 8))
    data += struct.pack("<H", len(entries))
    for tag, type_id, count, value in entries:
        data += struct.pack("<HHI", tag, type_id, count) + value
    data += struct.pack("<I", 0)
    data.extend(b"\0" * (200 - len(data)))
    data.extend(struct.pack("<HHHH", 1, 2, 3, 4))
    return bytes(data)


class ValidateDngTest(unittest.TestCase):
    def test_reads_required_tags_and_payload(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "fixture.dng"
            path.write_bytes(fixture())
            report = dng.read_dng(path)
        self.assertEqual("PASS", report["status"])
        self.assertEqual(8, report["payloadBytes"])
        self.assertEqual([], report["missingRequiredTags"])

    def test_long_arrays_are_summarized(self):
        self.assertEqual({"count": 17, "first": [0, 1, 2, 3], "last": [13, 14, 15, 16]}, dng._summarize(list(range(17))))


if __name__ == "__main__":
    unittest.main()
