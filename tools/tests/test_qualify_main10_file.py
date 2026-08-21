#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 OpenCineCam contributors

import importlib.util
import sys
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).parents[1] / "qualify_main10_file.py"
SPEC = importlib.util.spec_from_file_location("qualify_main10_file", MODULE_PATH)
assert SPEC and SPEC.loader
qualifier = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = qualifier
SPEC.loader.exec_module(qualifier)


class QualifyMain10FileTest(unittest.TestCase):
    def test_main10_hlg_signaling_passes(self):
        result = qualifier.validate_file_signal(
            {
                "codec_name": "hevc",
                "profile": "Main 10",
                "color_space": "bt2020nc",
                "color_transfer": "arib-std-b67",
                "pix_fmt": "yuv420p10le",
            }
        )
        self.assertEqual("PASS", result["status"])

    def test_incomplete_or_mismatched_signaling_never_passes(self):
        self.assertEqual("UNKNOWN", qualifier.validate_file_signal(None)["status"])
        self.assertEqual(
            "FAIL",
            qualifier.validate_file_signal(
                {
                    "codec_name": "hevc",
                    "profile": "Main",
                    "color_space": "bt2020nc",
                    "color_transfer": "arib-std-b67",
                    "pix_fmt": "yuv420p10le",
                }
            )["status"],
        )

    def test_controlled_gradient_is_ten_bit_but_scene_samples_are_diagnostic(self):
        gradient = qualifier.controlled_gradient()
        self.assertEqual("PASS", gradient["status"])
        self.assertEqual(10, gradient["measuredBits"])
        observed = qualifier.measure_distinct_bits([0, 1, 2])
        self.assertEqual("FAIL", observed["status"])


if __name__ == "__main__":
    unittest.main()
