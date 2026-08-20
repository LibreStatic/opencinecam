#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 OpenCineCam contributors

import importlib.util
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).parents[1] / "validate_plan_system.py"
SPEC = importlib.util.spec_from_file_location("plan_validator", MODULE_PATH)
assert SPEC and SPEC.loader
validator = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(validator)


class ValidatorUnitTest(unittest.TestCase):
    def test_parse_inline_list(self):
        self.assertEqual(validator.parse_inline_list("[]"), [])
        self.assertEqual(
            validator.parse_inline_list("[OCC-PLAN-001, OCC-PLAN-002]"),
            ["OCC-PLAN-001", "OCC-PLAN-002"],
        )

    def test_detects_cycle(self):
        records = [
            {"id": "OCC-PLAN-001", "depends_on": ["OCC-PLAN-002"]},
            {"id": "OCC-PLAN-002", "depends_on": ["OCC-PLAN-001"]},
        ]
        self.assertEqual(
            validator.cycle_nodes(records),
            ["OCC-PLAN-001", "OCC-PLAN-002", "OCC-PLAN-001"],
        )

    def test_accepts_acyclic_graph(self):
        records = [
            {"id": "OCC-PLAN-001", "depends_on": []},
            {"id": "OCC-PLAN-002", "depends_on": ["OCC-PLAN-001"]},
        ]
        self.assertEqual(validator.cycle_nodes(records), [])

    def test_acceptance_states_are_scoped_to_acceptance_section(self):
        text = """## 16. Acceptance Criteria

- [x] complete
- [ ] pending

## 17. Evidence to Record

- [x] unrelated
"""
        self.assertEqual(validator.acceptance_states(text), ["x", " "])

    def test_execution_evidence_paths(self):
        text = "- Evidence: `evidence/one.log`, `evidence/two.json`\n"
        self.assertEqual(
            validator.execution_evidence_paths(text),
            ["evidence/one.log", "evidence/two.json"],
        )


if __name__ == "__main__":
    unittest.main()
