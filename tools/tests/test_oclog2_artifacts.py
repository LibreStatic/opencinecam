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
ARTIFACTS = ROOT / "docs" / "color" / "oclog2-v2"

try:
    import numpy
    import PyOpenColorIO
except ImportError:  # Optional consumer check; the format assertions below need neither.
    numpy = None
    PyOpenColorIO = None


def parse_cube(path: Path) -> tuple[list[str], list[str], list[tuple[str, ...]], list[str]]:
    """Split a .cube into leading comments, header lines, keyword tuples, and table rows."""
    comments: list[str] = []
    header: list[str] = []
    rows: list[str] = []
    for line in path.read_text().splitlines():
        if not line.strip():
            continue
        if line.startswith("#"):
            if header or rows:
                raise AssertionError(f"comment after the header in {path.name}: {line}")
            comments.append(line)
        elif line.lstrip()[0] in "+-.0123456789":
            rows.append(line)
        else:
            if rows:
                raise AssertionError(f"keyword after table data in {path.name}: {line}")
            header.append(line)
    keywords = [tuple(line.split()) for line in header]
    return comments, header, keywords, rows


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

    def test_1d_cube_is_an_adobe_cube_1_0_one_dimensional_file(self) -> None:
        # Adobe Cube LUT Specification 1.0, sections 5.5, 5.6 and 6.2.
        _, _, keywords, rows = parse_cube(ARTIFACTS / "oclog2-v2-4096.cube")
        names = [keyword[0] for keyword in keywords]
        self.assertEqual(len(names), len(set(names)))
        self.assertEqual({"TITLE", "LUT_1D_SIZE", "DOMAIN_MIN", "DOMAIN_MAX"}, set(names))
        self.assertIn(("LUT_1D_SIZE", str(generator.LUT_1D_SIZE)), keywords)
        self.assertIn(("DOMAIN_MIN", "0.0", "0.0", "0.0"), keywords)
        self.assertIn(("DOMAIN_MAX", "1.0", "1.0", "1.0"), keywords)
        self.assertEqual(generator.LUT_1D_SIZE, len(rows))

    def test_combined_cube_uses_the_resolve_shaper_plus_3d_layout(self) -> None:
        # Adobe Cube 1.0 permits one 1D or one 3D table per file. The combined shaper file follows
        # DaVinci Resolve's documented layout, which OpenColorIO reads as resolve_cube.
        comments, header, keywords, rows = parse_cube(ARTIFACTS / "oclog2-v2-17.cube")
        self.assertTrue(comments)
        self.assertEqual(
            [
                f"LUT_1D_SIZE {generator.LUT_1D_SIZE}",
                "LUT_1D_INPUT_RANGE 0.0 1.0",
                f"LUT_3D_SIZE {generator.LUT_3D_SIZE}",
                "LUT_3D_INPUT_RANGE 0.0 1.0",
            ],
            header,
        )
        self.assertFalse({"TITLE", "DOMAIN_MIN", "DOMAIN_MAX"} & {keyword[0] for keyword in keywords})
        self.assertEqual(generator.LUT_1D_SIZE + generator.LUT_3D_SIZE ** 3, len(rows))
        shaper = [float(row.split()[0]) for row in rows[:generator.LUT_1D_SIZE]]
        # The shaper output feeds the 3D lattice, so it must stay inside LUT_3D_INPUT_RANGE.
        self.assertGreaterEqual(min(shaper), 0.0)
        self.assertLessEqual(max(shaper), 1.0)
        for index, row in enumerate(rows[generator.LUT_1D_SIZE:]):
            red, green, blue = (float(component) * (generator.LUT_3D_SIZE - 1) for component in row.split())
            expected = (
                index % generator.LUT_3D_SIZE,
                (index // generator.LUT_3D_SIZE) % generator.LUT_3D_SIZE,
                index // generator.LUT_3D_SIZE ** 2,
            )
            self.assertEqual(expected, (round(red), round(green), round(blue)))

    def test_ocio_config_has_the_entries_ocio_v2_requires_to_load(self) -> None:
        config = (ARTIFACTS / "oclog2-v2.ocio").read_text()
        self.assertTrue(config.startswith("ocio_profile_version: 2\n"))
        self.assertIn("!<Rule> {name: Default, colorspace: scene_linear_bt2020}", config)
        self.assertIn("displays:", config)

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


@unittest.skipUnless(PyOpenColorIO and numpy, "PyOpenColorIO and numpy are not importable")
class OcLog2OcioConsumerTest(unittest.TestCase):
    """Evaluates the committed artifacts through OpenColorIO's CPU path against the analytic curve."""

    TOLERANCE = 2e-5

    @staticmethod
    def encode(values: "numpy.ndarray") -> "numpy.ndarray":
        clamped = numpy.clip(values, 0.0, 1.0)
        return 0.10 + 0.80 * numpy.log1p(50.0 * clamped) / math.log(51.0)

    def max_error(self, processor: "PyOpenColorIO.Processor") -> float:
        inputs = numpy.concatenate([
            numpy.linspace(0.0, 1.0, 65_536),
            numpy.logspace(-9, -1, 2_000),
            numpy.array([-1.0, -1e-6, 1.0 + 1e-6, 1.5, 100.0]),
        ])
        # Rotate each channel so a swapped channel or a cross-channel leak fails.
        rgb = numpy.stack([numpy.roll(inputs, shift * len(inputs) // 3) for shift in range(3)], axis=1)
        pixels = rgb.astype(numpy.float32)
        processor.getDefaultCPUProcessor().applyRGB(pixels)
        return float(numpy.max(numpy.abs(pixels.astype(numpy.float64) - self.encode(rgb))))

    def file_processor(self, name: str, interpolation: object) -> "PyOpenColorIO.Processor":
        transform = PyOpenColorIO.FileTransform(src=str(ARTIFACTS / name), interpolation=interpolation)
        return PyOpenColorIO.Config.CreateRaw().getProcessor(transform)

    def test_1d_cube_evaluates_within_tolerance(self) -> None:
        processor = self.file_processor("oclog2-v2-4096.cube", PyOpenColorIO.INTERP_LINEAR)
        self.assertEqual(["Lut1DTransform"], [type(op).__name__ for op in processor.createGroupTransform()])
        self.assertLessEqual(self.max_error(processor), self.TOLERANCE)

    def test_combined_cube_applies_shaper_and_lattice_within_tolerance(self) -> None:
        for interpolation in (PyOpenColorIO.INTERP_LINEAR, PyOpenColorIO.INTERP_TETRAHEDRAL):
            with self.subTest(interpolation=interpolation):
                processor = self.file_processor("oclog2-v2-17.cube", interpolation)
                ops = [type(op).__name__ for op in processor.createGroupTransform()]
                self.assertEqual(["Lut1DTransform", "Lut3DTransform"], ops)
                self.assertLessEqual(self.max_error(processor), self.TOLERANCE)

    def test_config_loads_validates_and_encodes_within_tolerance(self) -> None:
        config = PyOpenColorIO.Config.CreateFromFile(str(ARTIFACTS / "oclog2-v2.ocio"))
        config.validate()
        processor = config.getProcessor("scene_linear_bt2020", "oclog2_bt2020")
        self.assertLessEqual(self.max_error(processor), self.TOLERANCE)


if __name__ == "__main__":
    unittest.main()
