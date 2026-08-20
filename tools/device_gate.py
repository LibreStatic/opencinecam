#!/usr/bin/env python3
"""Collect exact, non-promotional Android device gate evidence.

The collector records identity and API-level facts only. Capability promotion
still requires the plan-specific instrumentation and file evidence.
"""

# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 OpenCineCam contributors

from __future__ import annotations

import argparse
import json
import re
import subprocess
from collections.abc import Callable
from pathlib import Path


Runner = Callable[[list[str]], str]


def parse_devices(output: str) -> list[dict[str, str]]:
    devices: list[dict[str, str]] = []
    for line in output.splitlines():
        if not line.strip() or line.startswith("*") or line.startswith("adb server"):
            continue
        fields = line.split()
        if len(fields) < 2 or fields[0] == "List":
            continue
        details = {key: value for key, value in (item.split(":", 1) for item in fields[2:] if ":" in item)}
        devices.append({"serial": fields[0], "state": fields[1], **details})
    return devices


def parse_getprop(output: str) -> dict[str, str]:
    values: dict[str, str] = {}
    for key, value in re.findall(r"^\[([^]]+)\]: \[([^]]*)\]$", output, re.MULTILINE):
        values[key] = value
    return values


def collect(run: Runner, serial: str | None = None) -> dict[str, object]:
    devices = parse_devices(run(["adb", "devices", "-l"]))
    if serial is not None:
        devices = [device for device in devices if device["serial"] == serial]
    if not devices:
        return {
            "status": "NOT_RUN",
            "reason": "selected Android device not connected" if serial else "no connected Android device",
            "devices": [],
            "capabilityPromotion": "not evaluated",
        }
    reports: list[dict[str, object]] = []
    for device in devices:
        if device["state"] != "device":
            reports.append({**device, "status": "NOT_RUN", "reason": f"device state is {device['state']}"})
            continue
        props = parse_getprop(run(["adb", "-s", device["serial"], "shell", "getprop"]))
        api_level = props.get("ro.build.version.sdk")
        api_level_valid = bool(api_level and api_level.isdecimal() and int(api_level) > 0)
        report = {
            **device,
            "apiLevel": api_level,
            "fingerprint": props.get("ro.build.fingerprint"),
            "manufacturer": props.get("ro.product.manufacturer"),
            "model": props.get("ro.product.model"),
            "status": "READY" if api_level_valid and props.get("ro.build.fingerprint") else "UNKNOWN",
            "capabilityPromotion": "not evaluated",
        }
        reports.append(report)
    statuses = {str(report["status"]) for report in reports}
    overall = "READY" if "READY" in statuses else "UNKNOWN" if "UNKNOWN" in statuses else "NOT_RUN"
    return {"status": overall, "devices": reports, "capabilityPromotion": "not evaluated"}


def subprocess_runner(command: list[str]) -> str:
    return subprocess.check_output(command, text=True, stderr=subprocess.STDOUT)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--serial", help="collect exactly one adb transport serial")
    args = parser.parse_args()
    try:
        report = collect(subprocess_runner, args.serial)
    except (OSError, subprocess.CalledProcessError) as error:
        report = {
            "status": "NOT_RUN",
            "reason": f"adb unavailable: {error}",
            "devices": [],
            "capabilityPromotion": "not evaluated",
        }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(report, sort_keys=True))
    return 0 if report["status"] == "READY" else 2


if __name__ == "__main__":
    raise SystemExit(main())
