#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 OpenCineCam contributors

import importlib.util
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).parents[1] / "generate_sbom.py"
SPEC = importlib.util.spec_from_file_location("generate_sbom", MODULE_PATH)
assert SPEC and SPEC.loader
sbom = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(sbom)


class SbomUnitTest(unittest.TestCase):
    def test_parse_lockfile_is_sorted_and_deduplicated(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "gradle.lockfile"
            path.write_text(
                "# generated\norg.example:z:2.0=debugRuntimeClasspath\n"
                "org.example:a:1.0=debugRuntimeClasspath\n"
                "org.example:z:2.0=releaseRuntimeClasspath\n",
                encoding="utf-8",
            )
            self.assertEqual(
                sbom.parse_lockfile(path),
                [("org.example", "a", "1.0"), ("org.example", "z", "2.0")],
            )

    def test_document_is_deterministic(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "gradle.lockfile"
            path.write_text("org.example:a:1.0=debugRuntimeClasspath\n", encoding="utf-8")
            first = sbom.generate(path)
            second = sbom.generate(path)
            self.assertEqual(first, second)
            self.assertEqual(first["spdxVersion"], "SPDX-2.3")


if __name__ == "__main__":
    unittest.main()
