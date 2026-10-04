# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 OpenCineCam contributors

from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path


ROOT = Path(__file__).parents[2]
MODULE_PATH = ROOT / "tools" / "generate_lightforge_luts.py"
SPEC = importlib.util.spec_from_file_location("generate_lightforge_luts", MODULE_PATH)
assert SPEC and SPEC.loader
generator = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(generator)
ARTIFACTS = ROOT / "docs" / "color" / "lightforge"
LIGHTFORGE_MAX_BYTES = 8 * 1024 * 1024  # LutRepository.MaxBytes


class LightforgeLutTest(unittest.TestCase):
    def test_cubes_load_in_lightforge(self) -> None:
        for name, _ in generator.TIERS.values():
            path = ARTIFACTS / name
            with self.subTest(name=name):
                self.assertLessEqual(path.stat().st_size, LIGHTFORGE_MAX_BYTES)
                text = path.read_text()
                self.assertNotIn("LUT_1D_SIZE", text)  # Lightforge rejects a second size keyword
                self.assertIn(f"LUT_3D_SIZE {generator.SIZE}\n", text)
                rows = generator.parse_rows(text)
                self.assertEqual(generator.SIZE**3, len(rows))
                self.assertTrue(all(len(row) == 3 and all(0 <= v <= 1 for v in row) for row in rows))

    def test_neutral_axis_rises_and_grey_lands_mid(self) -> None:
        for tier, grey in (("hlg", generator.HLG_GREY), ("sdr", 0.18)):
            with self.subTest(tier=tier):
                ramp = [generator.look((c / 100,) * 3, tier)[1] for c in range(10, 91)]
                self.assertTrue(all(b >= a for a, b in zip(ramp, ramp[1:])))
                code = generator.oclog2_encode(grey)
                out = generator.encode709(generator.look((code,) * 3, tier)[1])
                self.assertAlmostEqual(0.42, out, delta=0.02)

    def test_committed_cubes_match_generator(self) -> None:
        for tier, (name, _) in generator.TIERS.items():
            with self.subTest(name=name):
                expected = generator.parse_rows(generator.render(tier))
                actual = generator.parse_rows((ARTIFACTS / name).read_text())
                worst = max(abs(e - a) for er, ar in zip(expected, actual) for e, a in zip(er, ar))
                self.assertLessEqual(worst, 2e-5)


if __name__ == "__main__":
    unittest.main()
