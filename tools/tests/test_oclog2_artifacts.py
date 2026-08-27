# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 OpenCineCam contributors

from __future__ import annotations

import hashlib
import importlib.util
import json
import math
import tempfile
import unittest
from decimal import Decimal
from pathlib import Path


ROOT = Path(__file__).parents[2]
MODULE_PATH = ROOT / "tools" / "generate_oclog2_artifacts.py"
SPEC = importlib.util.spec_from_file_location("generate_oclog2_artifacts", MODULE_PATH)
assert SPEC and SPEC.loader
generator = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(generator)


class OcLog2ArtifactTest(unittest.TestCase):
    def test_normative_boundaries_middle_gray_and_inverse(self) -> None:
        self.assertEqual(Decimal("0.10"), generator.encode_decimal(Decimal(0)))
        self.assertEqual(Decimal("0.90"), generator.encode_decimal(Decimal(1)))
        self.assertAlmostEqual(0.568501975027536, float(generator.encode_decimal(Decimal("0.18"))), places=14)
        for index in range(1001):
            value = Decimal(index) / Decimal(1000)
            decoded = generator.decode_decimal(generator.encode_decimal(value))
            self.assertLessEqual(abs(decoded - value), Decimal("1e-45"))

    def test_curve_is_strictly_monotonic_and_rejects_out_of_range(self) -> None:
        values = [generator.encode_decimal(Decimal(index) / Decimal(1000)) for index in range(1001)]
        self.assertTrue(all(right > left for left, right in zip(values, values[1:])))
        for invalid in (Decimal("-0.0001"), Decimal("1.0001")):
            with self.assertRaises(ValueError):
                generator.encode_decimal(invalid)
        for invalid in (Decimal("0.0999"), Decimal("0.9001")):
            with self.assertRaises(ValueError):
                generator.decode_decimal(invalid)

    def test_committed_artifacts_are_reproducible_and_hash_pinned(self) -> None:
        output = ROOT / "docs" / "color" / "oclog2-v2"
        self.assertTrue(generator.check(output))
        manifest = json.loads((output / "manifest.json").read_text())
        for name, identity in manifest["artifacts"].items():
            payload = (output / name).read_bytes()
            self.assertEqual(identity["bytes"], len(payload))
            self.assertEqual(identity["sha256"], hashlib.sha256(payload).hexdigest())

    def test_1d_lut_linear_interpolation_stays_within_declared_tolerance(self) -> None:
        path = ROOT / "docs" / "color" / "oclog2-v2" / "oclog2-v2-4096.cube"
        rows = [line for line in path.read_text().splitlines() if line and line[0].isdigit()]
        lut = [float(row.split()[0]) for row in rows]
        self.assertEqual(generator.LUT_1D_SIZE, len(lut))
        for index in range(1, 10_000, 17):
            value = index / 10_000
            scaled = value * (len(lut) - 1)
            lower = min(int(scaled), len(lut) - 2)
            fraction = scaled - lower
            interpolated = lut[lower] * (1 - fraction) + lut[lower + 1] * fraction
            reference = float(generator.encode_decimal(Decimal(str(value))))
            self.assertLessEqual(abs(interpolated - reference), 2e-5)

    def test_3d_artifact_uses_dense_shaper_and_identity_lattice(self) -> None:
        path = ROOT / "docs" / "color" / "oclog2-v2" / "oclog2-v2-17.cube"
        rows = [line for line in path.read_text().splitlines() if line and line[0].isdigit()]
        shaper = [[float(component) for component in row.split()] for row in rows[:generator.LUT_1D_SIZE]]
        lattice = rows[generator.LUT_1D_SIZE:]
        self.assertEqual(generator.LUT_1D_SIZE, len(shaper))
        self.assertEqual(generator.LUT_3D_SIZE ** 3, len(lattice))
        self.assertTrue(all(row[0] == row[1] == row[2] for row in shaper))
        self.assertEqual("0.000000000000000 0.000000000000000 0.000000000000000", lattice[0])
        self.assertEqual("1.000000000000000 1.000000000000000 1.000000000000000", lattice[-1])
        for index in range(1, 10_000, 31):
            value = index / 10_000
            scaled = value * (len(shaper) - 1)
            lower = min(int(scaled), len(shaper) - 2)
            fraction = scaled - lower
            interpolated = shaper[lower][0] * (1 - fraction) + shaper[lower + 1][0] * fraction
            reference = float(generator.encode_decimal(Decimal(str(value))))
            self.assertLessEqual(abs(interpolated - reference), 2e-5)

    def test_vectors_are_external_values_not_runtime_generated_assertions(self) -> None:
        vectors = json.loads((ROOT / "docs" / "color" / "oclog2-v2" / "vectors.json").read_text())
        self.assertEqual("Python Decimal analytic equation", vectors["reference"]["method"])
        self.assertEqual(50, vectors["reference"]["precision"])
        self.assertEqual(hashlib.sha256(MODULE_PATH.read_bytes()).hexdigest(), vectors["reference"]["generatorSha256"])
        self.assertGreaterEqual(len(vectors["vectors"]), 7)
        for vector in vectors["vectors"]:
            expected = 0.10 + 0.80 * math.log1p(50 * vector["sceneLinear"]) / math.log(51)
            self.assertAlmostEqual(expected, vector["oclog2"], places=14)
            self.assertAlmostEqual(vector["sceneLinear"], vector["inverseSceneLinear"], places=14)

    def test_generation_has_no_environment_or_timestamp_input(self) -> None:
        with tempfile.TemporaryDirectory() as first, tempfile.TemporaryDirectory() as second:
            generator.generate(Path(first))
            generator.generate(Path(second))
            self.assertEqual(
                {path.name: path.read_bytes() for path in Path(first).iterdir()},
                {path.name: path.read_bytes() for path in Path(second).iterdir()},
            )


if __name__ == "__main__":
    unittest.main()
