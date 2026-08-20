#!/usr/bin/env python3
"""Evaluate a versioned OpenCineCam device-soak evidence manifest.

The tool only evaluates supplied observations. It never infers a connected
device or synthesizes passing hardware evidence.
"""

# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 OpenCineCam contributors

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


SCENARIOS = (
    "LIFECYCLE",
    "FOLD_TRANSITION",
    "CAMERA_DISCONNECT",
    "THERMAL",
    "LOW_SPACE",
    "FILE_EVIDENCE",
)
REQUIRED_DURATION_SECONDS = 30 * 60
MAX_OBSERVATIONS = 128
OBSERVATION_STATUSES = {"PASS", "FAIL", "UNKNOWN", "NOT_RUN"}


def non_negative_int(value: object) -> int | None:
    if isinstance(value, bool):
        return None
    try:
        parsed = int(value)
    except (TypeError, ValueError, OverflowError):
        return None
    if isinstance(value, float) and not value.is_integer():
        return None
    return parsed if parsed >= 0 else None


def evaluate(manifest: dict[str, object]) -> dict[str, object]:
    target = manifest.get("target")
    parsed_duration = non_negative_int(manifest.get("durationSeconds", 0))
    duration = parsed_duration if parsed_duration is not None else 0
    raw_observations = manifest.get("observations", [])
    observations = raw_observations if isinstance(raw_observations, list) else []
    names: list[str] = []
    malformed_observation = not isinstance(raw_observations, list)
    invalid_status = False
    for item in observations:
        if not isinstance(item, dict):
            malformed_observation = True
            continue
        scenario = item.get("scenario")
        if not isinstance(scenario, str):
            malformed_observation = True
        else:
            names.append(scenario)
        if item.get("status", "UNKNOWN") not in OBSERVATION_STATUSES:
            invalid_status = True
    missing = sorted(set(SCENARIOS) - set(names))
    unknown_scenarios = sorted(set(names) - set(SCENARIOS))
    failures: list[str] = []
    protocol_version = non_negative_int(target.get("protocolVersion", 0)) if isinstance(target, dict) else None
    target_valid = (
        isinstance(target, dict)
        and isinstance(target.get("fingerprint"), str)
        and bool(str(target.get("fingerprint")).strip())
        and isinstance(target.get("profileId"), str)
        and bool(str(target.get("profileId")).strip())
        and protocol_version is not None
        and protocol_version > 0
    )
    if not target_valid:
        failures.append("physical target not identified")
    if parsed_duration is None:
        failures.append("invalid durationSeconds")
    if duration < REQUIRED_DURATION_SECONDS:
        failures.append("30-minute soak requirement not met")
    if not isinstance(raw_observations, list):
        failures.append("observations must be a list")
    if len(observations) > MAX_OBSERVATIONS:
        failures.append("too many observations")
    if missing:
        failures.append("missing scenarios: " + ", ".join(missing))
    if unknown_scenarios:
        failures.append("unknown scenarios: " + ", ".join(unknown_scenarios))
    if len(names) != len(set(names)):
        failures.append("duplicate scenario observations")
    for item in observations:
        if not isinstance(item, dict):
            failures.append("malformed observation")
            continue
        scenario = item.get("scenario", "UNKNOWN")
        status = item.get("status", "UNKNOWN")
        message = item.get("safeMessage", "evidence not run or unknown")
        if status == "FAIL":
            failures.append(f"{scenario}: {message}")
        elif status in {"UNKNOWN", "NOT_RUN"}:
            failures.append(f"{scenario}: evidence not run or unknown")
        elif status != "PASS":
            failures.append(f"{scenario}: invalid status {status}")
    hard_failure = any(
        isinstance(item, dict) and item.get("status") == "FAIL" for item in observations
    )
    unknown = any(
        isinstance(item, dict) and item.get("status") not in {"PASS", "FAIL"}
        for item in observations
    )
    structurally_invalid = (
        parsed_duration is None
        or malformed_observation
        or invalid_status
        or len(observations) > MAX_OBSERVATIONS
    )
    if (
        not target_valid
        or duration < REQUIRED_DURATION_SECONDS
        or missing
        or unknown_scenarios
        or structurally_invalid
    ):
        status = "NOT_RUN"
    elif hard_failure or len(names) != len(set(names)):
        status = "FAILED"
    elif unknown:
        status = "NOT_RUN"
    else:
        status = "QUALIFIED"
    return {
        "status": status,
        "target": target,
        "durationSeconds": duration,
        "observations": observations,
        "missingScenarios": missing,
        "failures": failures,
    }


def write_bundle(report: dict[str, object], bundle_dir: Path) -> list[str]:
    """Write a deterministic, privacy-safe evidence bundle and return files.

    A supplied but missing evidence path is an incomplete observation, so the
    report is downgraded to NOT_RUN before its summary is serialized.
    """
    bundle_dir.mkdir(parents=True, exist_ok=True)
    for stale in [bundle_dir / "summary.json", bundle_dir / "bundle-index.json", *bundle_dir.glob("scenario-*.json")]:
        stale.unlink(missing_ok=True)
    missing_evidence: list[str] = []
    for observation in report["observations"]:
        if not isinstance(observation, dict) or not observation.get("evidencePath"):
            continue
        if not Path(str(observation["evidencePath"])).is_file():
            missing_evidence.append(f"{observation.get('scenario', 'UNKNOWN')}: evidence path missing")
    if missing_evidence:
        report["status"] = "NOT_RUN"
        report["failures"] = [*report["failures"], *missing_evidence]
    written = ["summary.json"]
    summary = {
        "schema": "opencinecam-soak-v1",
        "status": report["status"],
        "target": report["target"],
        "durationSeconds": report["durationSeconds"],
        "missingScenarios": report["missingScenarios"],
        "failures": report["failures"],
        "observationCount": len(report["observations"]),
    }
    (bundle_dir / "summary.json").write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    for index, observation in enumerate(report["observations"]):
        scenario = str(observation.get("scenario", "UNKNOWN")) if isinstance(observation, dict) else "UNKNOWN"
        safe = "".join(char.lower() if char.isalnum() else "_" for char in scenario).strip("_") or "unknown"
        filename = f"scenario-{index:02d}-{safe}.json"
        item = dict(observation) if isinstance(observation, dict) else {"value": observation}
        evidence_path = item.pop("evidencePath", None)
        if evidence_path:
            path = Path(str(evidence_path))
            if path.is_file():
                item["evidenceSha256"] = hashlib.sha256(path.read_bytes()).hexdigest()
            else:
                item["evidenceStatus"] = "MISSING"
        (bundle_dir / filename).write_text(json.dumps(item, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        written.append(filename)
    (bundle_dir / "bundle-index.json").write_text(json.dumps({"schema": "opencinecam-soak-v1", "files": written}, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return written + ["bundle-index.json"]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--bundle-dir", type=Path)
    args = parser.parse_args()
    if not args.input.is_file():
        parser.error("input manifest must exist")
    report = evaluate(json.loads(args.input.read_text(encoding="utf-8")))
    if args.bundle_dir:
        report["bundleFiles"] = write_bundle(report, args.bundle_dir)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(report, sort_keys=True))
    return 0 if report["status"] == "QUALIFIED" else 2


if __name__ == "__main__":
    raise SystemExit(main())
