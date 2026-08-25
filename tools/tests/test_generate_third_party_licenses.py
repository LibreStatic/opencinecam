#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 OpenCineCam contributors

import importlib.util
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).parents[1] / "generate_third_party_licenses.py"
SPEC = importlib.util.spec_from_file_location("generate_third_party_licenses", MODULE_PATH)
assert SPEC and SPEC.loader
licenses = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(licenses)


class ThirdPartyLicenseUnitTest(unittest.TestCase):
    def test_only_release_runtime_is_included(self):
        with tempfile.TemporaryDirectory() as directory:
            lockfile = Path(directory) / "gradle.lockfile"
            lockfile.write_text(
                "androidx.core:core:1.0=debugRuntimeClasspath,releaseRuntimeClasspath\n"
                "junit:junit:4.13.2=debugUnitTestRuntimeClasspath\n",
                encoding="utf-8",
            )
            document = licenses.generate(lockfile)
            self.assertEqual(len(document["components"]), 1)
            self.assertEqual(document["components"][0]["licenseId"], "Apache-2.0")

    def test_unknown_group_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            lockfile = Path(directory) / "gradle.lockfile"
            lockfile.write_text(
                "com.example:unknown:1.0=releaseRuntimeClasspath\n",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ValueError, "No reviewed license rule"):
                licenses.generate(lockfile)


if __name__ == "__main__":
    unittest.main()
