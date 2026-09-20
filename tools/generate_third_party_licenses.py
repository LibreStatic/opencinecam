#!/usr/bin/env python3
"""Generate the offline license catalog shipped in the Android app."""

# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 OpenCineCam contributors

from __future__ import annotations

import argparse
import json
from pathlib import Path


LICENSE_RULES = {
    "androidx.": ("Apache-2.0", "Apache License 2.0", "licenses/Apache-2.0.txt"),
    "com.google.guava": ("Apache-2.0", "Apache License 2.0", "licenses/Apache-2.0.txt"),
    "org.jetbrains": ("Apache-2.0", "Apache License 2.0", "licenses/Apache-2.0.txt"),
    "org.jspecify": ("Apache-2.0", "Apache License 2.0", "licenses/Apache-2.0.txt"),
}


def parse_release_runtime(lockfile: Path) -> list[tuple[str, str, str]]:
    modules: set[tuple[str, str, str]] = set()
    for raw in lockfile.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        coordinate, configurations = line.split("=", 1)
        if "releaseRuntimeClasspath" not in configurations.split(","):
            continue
        parts = coordinate.split(":")
        if len(parts) == 3:
            modules.add((parts[0], parts[1], parts[2]))
    return sorted(modules)


def license_for(group: str, name: str) -> tuple[str, str, str]:
    if (group, name) in {
        ("com.google.code.findbugs", "jsr305"),
        ("com.google.errorprone", "error_prone_annotations"),
        ("com.google.j2objc", "j2objc-annotations"),
    }:
        return ("Apache-2.0", "Apache License 2.0", "licenses/Apache-2.0.txt")
    if (group, name) == ("org.checkerframework", "checker-qual"):
        return ("MIT", "MIT License (Checker Framework annotations)", "licenses/checker-qual-MIT.txt")
    for prefix, license_details in LICENSE_RULES.items():
        if group.startswith(prefix):
            return license_details
    raise ValueError(f"No reviewed license rule for dependency group: {group}")


def generate(lockfile: Path) -> dict[str, object]:
    components = []
    for group, name, version in parse_release_runtime(lockfile):
        license_id, license_name, license_asset = license_for(group, name)
        components.append(
            {
                "group": group,
                "name": name,
                "version": version,
                "licenseId": license_id,
                "licenseName": license_name,
                "licenseTextAsset": license_asset,
            }
        )
    return {"schemaVersion": 1, "components": components}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--lockfile", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    rendered = json.dumps(generate(args.lockfile), indent=2, sort_keys=True) + "\n"
    if args.check:
        if not args.output.exists() or args.output.read_text(encoding="utf-8") != rendered:
            raise SystemExit(f"stale third-party license catalog: {args.output}")
        print(f"verified {args.output}")
        return 0
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(rendered, encoding="utf-8")
    print(f"wrote {args.output} ({len(generate(args.lockfile)['components'])} components)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
