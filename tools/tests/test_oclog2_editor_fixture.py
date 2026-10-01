# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 OpenCineCam contributors

from __future__ import annotations

import json
import tempfile
import unittest
from array import array
from pathlib import Path

from tools import oclog2_editor_fixture as fixture


def signal_image(full: bool, bt2020: bool) -> fixture.ExrImage:
    """A render whose every patch decodes the coded values under the given interpretation."""
    values = []
    for patch in fixture.SIGNAL_PATCHES:
        rgb = fixture.ycbcr_to_rgb(*patch, full=full, bt2020=bt2020)
        values.append(tuple(min(max(v, 0.0), 1.0) for v in rgb) if not full else rgb)
    red, green, blue = array("f"), array("f"), array("f")
    for y in range(fixture.HEIGHT):
        row = [values[fixture.signal_patch_index(x, y)] for x in range(fixture.WIDTH)]
        red.extend(v[0] for v in row)
        green.extend(v[1] for v in row)
        blue.extend(v[2] for v in row)
    return fixture.ExrImage(fixture.WIDTH, fixture.HEIGHT, red, green, blue, "float", "test")


class OcLog2EditorFixtureTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        planes = fixture.still_planes("forward")
        cls.exact_forward = [array("f", (fixture.encode(value) for value in plane)) for plane in planes]

    def test_exr_round_trips_through_native_reader(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "small.exr"
            red, green, blue = array("f", [0.0, 0.5, -1.0, 4.0]), array("f", [0.1, 0.2, 0.3, 0.4]), array("f", [1.0, 0.0, 1e-7, 0.9])
            fixture.write_exr(path, red, green, blue, width=2, height=2)
            image = fixture.read_exr(path)
        self.assertEqual((2, 2, "float", "native"), (image.width, image.height, image.pixel_type, image.decoder))
        self.assertEqual(list(red), list(image.red))
        self.assertEqual(list(green), list(image.green))
        self.assertEqual(list(blue), list(image.blue))

    def test_stills_cover_domain_boundaries_and_rotate_channels(self) -> None:
        red, green, blue = fixture.still_planes("forward")
        self.assertEqual(fixture.WIDTH * fixture.HEIGHT, len(red))
        self.assertEqual(0.0, red[0])
        self.assertEqual(1.0, red[fixture.DENSE_ROWS[1] * fixture.WIDTH - 1])
        self.assertNotEqual(red[1000], green[1000])
        self.assertLess(min(red), 0.0)
        self.assertGreater(max(red), 1.0)
        codes = fixture.still_planes("inverse")[0]
        self.assertAlmostEqual(fixture.BLACK, codes[0], places=6)

    def test_exact_forward_render_passes(self) -> None:
        image = fixture.ExrImage(fixture.WIDTH, fixture.HEIGHT, *self.exact_forward, "float", "test")
        result = fixture.compare_transform("forward-dctl", image)
        self.assertEqual("PASS", result["status"], result["reasons"])
        self.assertLess(result["maxAbsError"], 1e-7)
        self.assertEqual(0, result["monotonicViolations"])

    def test_near_black_error_and_unclamped_boundary_fail(self) -> None:
        red = array("f", self.exact_forward[0])
        red[fixture.NEAR_FLOOR_ROWS[0] * fixture.WIDTH + 5] += 1e-4
        patch_row = fixture.PATCH_ROWS[0] * fixture.WIDTH
        red[patch_row + fixture.WIDTH - 1] = 1.2  # 4.0 input left unclamped
        image = fixture.ExrImage(fixture.WIDTH, fixture.HEIGHT, red, *self.exact_forward[1:], "float", "test")
        result = fixture.compare_transform("forward-lut1d", image)
        self.assertEqual("FAIL", result["status"])
        self.assertIn("in-domain-error-above-tolerance", result["reasons"])
        self.assertIn("boundary-clamp-mismatch", result["reasons"])
        self.assertEqual("R", result["worstSample"]["channel"])

    def test_signal_full_range_bt2020_passes(self) -> None:
        result = fixture.compare_signal(signal_image(full=True, bt2020=True))
        self.assertEqual("PASS", result["status"], result["reasons"])
        self.assertTrue(result["subBlackPreserved"])
        self.assertTrue(result["superWhitePreserved"])

    def test_signal_read_as_video_levels_is_diagnosed(self) -> None:
        result = fixture.compare_signal(signal_image(full=False, bt2020=True))
        self.assertEqual("FAIL", result["status"])
        self.assertEqual("limited-bt2020", result["bestInterpretation"])
        self.assertIn("decoded-as-limited-bt2020", result["reasons"])
        self.assertFalse(result["subBlackPreserved"])

    def test_signal_read_with_bt709_matrix_is_diagnosed(self) -> None:
        result = fixture.compare_signal(signal_image(full=True, bt2020=False))
        self.assertEqual("FAIL", result["status"])
        self.assertEqual("full-bt709", result["bestInterpretation"])

    def test_record_requires_every_case(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            paths = []
            for case in fixture.REQUIRED_CASES:
                path = root / f"{case}.json"
                body = {"schema": fixture.CASE_SCHEMA, "case": case, "status": "PASS", "reasons": []}
                if case in fixture.TRANSFORM_CASES:
                    body["maxAbsError"] = 3e-6
                path.write_text(json.dumps(body))
                paths.append(path)
            record = fixture.build_record(paths, "davinci-resolve", "20.2", "studio")
            self.assertEqual("PASS", record["status"])
            self.assertTrue(record["independent"])
            self.assertEqual(3e-6, record["maxAbsError"])
            self.assertEqual("NOT_RUN", record["caseStatus"]["forward-ocio"])
            self.assertEqual({"dctl", "lut1d", "lut3d"}, set(record["implementations"]))

            self.assertEqual("NOT_RUN", fixture.build_record(paths[:-1], "davinci-resolve", "20.2", "free")["status"])

            failed = json.loads(paths[0].read_text())
            failed.update(status="FAIL", maxAbsError=1e-3)
            paths[0].write_text(json.dumps(failed))
            record = fixture.build_record(paths[:-1], "davinci-resolve", "20.2", "free")
            self.assertEqual("FAIL", record["status"])
            self.assertEqual(1e-3, record["maxAbsError"])


if __name__ == "__main__":
    unittest.main()
