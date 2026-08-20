#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 OpenCineCam contributors

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).parents[1] / "soak_matrix.py"
SPEC = importlib.util.spec_from_file_location("soak_matrix", MODULE_PATH)
assert SPEC and SPEC.loader
soak = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = soak
SPEC.loader.exec_module(soak)


def complete(status="PASS"):
    return [
        {"scenario": scenario, "status": status, "safeMessage": "fixture"}
        for scenario in soak.SCENARIOS
    ]


class SoakMatrixTest(unittest.TestCase):
    def test_complete_manifest_qualifies(self):
        report = soak.evaluate(
            {
                "target": {"fingerprint": "fixture/device", "protocolVersion": 1, "profileId": "p1"},
                "durationSeconds": soak.REQUIRED_DURATION_SECONDS,
                "observations": complete(),
            }
        )
        self.assertEqual("QUALIFIED", report["status"])

    def test_missing_device_is_not_run(self):
        report = soak.evaluate({"durationSeconds": 1, "observations": complete()})
        self.assertEqual("NOT_RUN", report["status"])
        self.assertIn("physical target not identified", report["failures"])

    def test_failed_observation_is_failed(self):
        report = soak.evaluate(
            {
                "target": {"fingerprint": "fixture/device", "protocolVersion": 1, "profileId": "p1"},
                "durationSeconds": soak.REQUIRED_DURATION_SECONDS,
                "observations": complete("FAIL"),
            }
        )
        self.assertEqual("FAILED", report["status"])

    def test_unknown_scenario_cannot_qualify(self):
        observations = complete()
        observations[-1] = {"scenario": "VENDOR_PRIVATE", "status": "PASS"}
        report = soak.evaluate(
            {
                "target": {"fingerprint": "fixture/device", "protocolVersion": 1, "profileId": "p1"},
                "durationSeconds": soak.REQUIRED_DURATION_SECONDS,
                "observations": observations,
            }
        )
        self.assertEqual("NOT_RUN", report["status"])
        self.assertTrue(any("unknown scenarios" in failure for failure in report["failures"]))

    def test_unknown_status_cannot_qualify(self):
        observations = complete()
        observations[0]["status"] = "PASSING"
        report = soak.evaluate(
            {
                "target": {"fingerprint": "fixture/device", "protocolVersion": 1, "profileId": "p1"},
                "durationSeconds": soak.REQUIRED_DURATION_SECONDS,
                "observations": observations,
            }
        )
        self.assertEqual("NOT_RUN", report["status"])
        self.assertTrue(any("invalid status" in failure for failure in report["failures"]))

    def test_malformed_duration_and_scenario_do_not_crash(self):
        observations = complete()
        observations[0]["scenario"] = {"not": "hashable"}
        report = soak.evaluate(
            {
                "target": {"fingerprint": "fixture/device", "protocolVersion": 1, "profileId": "p1"},
                "durationSeconds": "not-a-number",
                "observations": observations,
            }
        )
        self.assertEqual("NOT_RUN", report["status"])
        self.assertIn("invalid durationSeconds", report["failures"])

    def test_bundle_is_deterministic_and_hashes_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            evidence = root / "fixture.json"
            evidence.write_text("fixture\n", encoding="utf-8")
            report = soak.evaluate(
                {
                    "target": {"fingerprint": "fixture/device", "protocolVersion": 1, "profileId": "p1"},
                    "durationSeconds": soak.REQUIRED_DURATION_SECONDS,
                    "observations": [
                        *complete()[:-1],
                        {"scenario": "FILE_EVIDENCE", "status": "PASS", "evidencePath": str(evidence)},
                    ],
                }
            )
            files = soak.write_bundle(report, root / "bundle")
            self.assertIn("summary.json", files)
            self.assertIn("bundle-index.json", files)
            scenario = (root / "bundle" / "scenario-05-file_evidence.json").read_text(encoding="utf-8")
            self.assertIn("evidenceSha256", scenario)

    def test_missing_supplied_evidence_cannot_qualify_bundle(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            report = soak.evaluate(
                {
                    "target": {"fingerprint": "fixture/device", "protocolVersion": 1, "profileId": "p1"},
                    "durationSeconds": soak.REQUIRED_DURATION_SECONDS,
                    "observations": [
                        *complete()[:-1],
                        {
                            "scenario": "FILE_EVIDENCE",
                            "status": "PASS",
                            "evidencePath": str(root / "missing.json"),
                        },
                    ],
                }
            )
            self.assertEqual("QUALIFIED", report["status"])
            soak.write_bundle(report, root / "bundle")
            self.assertEqual("NOT_RUN", report["status"])
            self.assertTrue(any("evidence path missing" in failure for failure in report["failures"]))


if __name__ == "__main__":
    unittest.main()
