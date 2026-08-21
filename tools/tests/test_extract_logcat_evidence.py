#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 OpenCineCam contributors

import base64
import importlib.util
import json
import sys
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).parents[1] / "extract_logcat_evidence.py"
SPEC = importlib.util.spec_from_file_location("extract_logcat_evidence", MODULE_PATH)
assert SPEC and SPEC.loader
extractor = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = extractor
SPEC.loader.exec_module(extractor)


class ExtractLogcatEvidenceTest(unittest.TestCase):
    def test_reassembles_ordered_parts(self):
        document = {"schema": "fixture-v1", "status": "PASS"}
        encoded = base64.b64encode(json.dumps(document).encode()).decode()
        midpoint = len(encoded) // 2
        transcript = (
            f"I OCC_HLG_GATE: json-part=1/2:{encoded[:midpoint]}\n"
            f"I OCC_HLG_GATE: json-part=2/2:{encoded[midpoint:]}\n"
        )
        self.assertEqual(document, extractor.extract(transcript))

    def test_rejects_missing_part(self):
        with self.assertRaisesRegex(ValueError, "missing evidence parts"):
            extractor.extract("I OCC_HLG_GATE: json-part=1/2:e30=\n")

    def test_reassembles_custom_tag(self):
        document = {"schema": "opencinecam-apv-capability-v1", "status": "UNSUPPORTED"}
        encoded = base64.b64encode(json.dumps(document).encode()).decode()
        transcript = f"I OCC_APV_EVIDENCE: json-part=1/1:{encoded}\n"
        self.assertEqual(document, extractor.extract(transcript, "OCC_APV_EVIDENCE"))


if __name__ == "__main__":
    unittest.main()
