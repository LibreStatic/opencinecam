#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 OpenCineCam contributors

import importlib.util
import struct
import sys
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


if __name__ == "__main__":
    unittest.main()
