#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
import importlib.util
import math
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("analyze_aac_tail", Path(__file__).parents[1] / "analyze_aac_tail.py")
analyzer = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(analyzer)


class AnalyzeAacTailTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = [int(12000 * math.sin(2 * math.pi * (0.013 * i + 0.0000003 * i * i))) for i in range(16384)]

    def test_delay_and_removed_tail_are_not_confused_with_padding(self):
        result = analyzer.analyze_channel(self.source, [0] * 32 + self.source[:-73], max_lag=64)
        self.assertEqual(32, result["measuredLagFrames"])
        self.assertEqual(73, result["missingInputTailFrames"])
        self.assertTrue(result["signalMatched"])
        self.assertFalse(result["sourceCoverageVerified"])

    def test_equal_lengths_do_not_prove_completeness(self):
        result = analyzer.analyze_channel(self.source, [0] * 32 + self.source[:-32], max_lag=64)
        self.assertEqual(32, result["missingInputTailFrames"])
        self.assertFalse(result["sourceCoverageVerified"])

    def test_full_source_with_extra_samples_is_not_gapless(self):
        result = analyzer.analyze_channel(self.source, [0] * 32 + self.source + [0] * 19, max_lag=64)
        self.assertTrue(result["sourceCoverageVerified"])
        self.assertEqual(19, result["trailingDecodedFrames"])
        self.assertFalse(result["gaplessTrimVerified"])

    def test_missing_start_is_detected(self):
        result = analyzer.analyze_channel(self.source, self.source[17:] + [0] * 64, max_lag=64)
        self.assertEqual(-17, result["measuredLagFrames"])
        self.assertEqual(17, result["missingInputStartFrames"])
        self.assertFalse(result["sourceCoverageVerified"])

    def test_silence_never_matches_a_real_signal(self):
        result = analyzer.analyze_channel(self.source, [0] * len(self.source), max_lag=64)
        self.assertFalse(result["signalMatched"])
        self.assertFalse(result["sourceCoverageVerified"])

    def test_short_probe_requires_a_different_measurement(self):
        with self.assertRaises(ValueError):
            analyzer.analyze_channel([1] * 100, [1] * 100)

    def test_pcm_requires_whole_frames_and_deinterleaves_channels(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "source.pcm"
            path.write_bytes(bytes([1, 0, 2, 0, 3, 0, 4, 0]))
            self.assertEqual([[1, 3], [2, 4]], [list(c) for c in analyzer.read_pcm(path, 2)])
            path.write_bytes(b"123456")
            with self.assertRaises(ValueError):
                analyzer.read_pcm(path, 2)

    def test_manifest_stem_stays_inside_probe_directory(self):
        with self.assertRaises(ValueError):
            analyzer.analyze_record(Path("."), {"stem": "../source"})


if __name__ == "__main__":
    unittest.main()
