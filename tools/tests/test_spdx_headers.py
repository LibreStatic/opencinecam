# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 OpenCineCam contributors

import subprocess
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DIRECTORIES = [
    "app", "camera", "media", "core", "tools", "store",
    "baselineprofile", "desktop-tools", "native-pipeline",
]
SUFFIXES = (".kt", ".kts", ".py", ".sh")
HEADER_LINES = 20


class SpdxHeaderTest(unittest.TestCase):
    def test_tracked_sources_carry_an_spdx_header(self):
        listing = subprocess.run(
            ["git", "ls-files", "--", *DIRECTORIES],
            cwd=ROOT, check=True, capture_output=True, text=True,
        ).stdout.splitlines()
        missing = []
        for name in listing:
            if not name.endswith(SUFFIXES):
                continue
            head = (ROOT / name).read_text(encoding="utf-8").splitlines()[:HEADER_LINES]
            if not any("SPDX-License-Identifier" in line for line in head):
                missing.append(name)
        self.assertEqual([], missing, "files without an SPDX-License-Identifier header")


if __name__ == "__main__":
    unittest.main()
