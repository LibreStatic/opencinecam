#!/usr/bin/env python3
"""Reassemble versioned OpenCineCam evidence emitted in bounded logcat parts."""

# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 OpenCineCam contributors

from __future__ import annotations

import argparse
import base64
import json
import re
from pathlib import Path


DEFAULT_TAG = "OCC_HLG_GATE"


def extract(text: str, tag: str = DEFAULT_TAG) -> dict[str, object]:
    if not tag or not re.fullmatch(r"[A-Za-z0-9_.-]+", tag):
        raise ValueError("evidence tag contains unsupported characters")
    part = re.compile(rf"{re.escape(tag)}:\s+json-part=(\d+)/(\d+):([A-Za-z0-9+/=]+)")
    matches = part.findall(text)
    if not matches:
        raise ValueError(f"no {tag} evidence parts found")
    totals = {int(total) for _, total, _ in matches}
    if len(totals) != 1:
        raise ValueError("inconsistent evidence part totals")
    total = totals.pop()
    parts: dict[int, str] = {}
    for raw_index, _, payload in matches:
        index = int(raw_index)
        if index in parts:
            raise ValueError(f"duplicate evidence part {index}")
        parts[index] = payload
    expected = set(range(1, total + 1))
    if set(parts) != expected:
        raise ValueError(f"missing evidence parts: {sorted(expected - set(parts))}")
    encoded = "".join(parts[index] for index in sorted(parts))
    decoded = base64.b64decode(encoded, validate=True).decode("utf-8")
    document = json.loads(decoded)
    if not isinstance(document, dict) or not document.get("schema"):
        raise ValueError("evidence root must be a versioned JSON object")
    return document


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--tag", default=DEFAULT_TAG)
    args = parser.parse_args()
    if not args.input.is_file():
        parser.error("input logcat transcript must exist")
    document = extract(args.input.read_text(encoding="utf-8"), args.tag)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(document, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps({"schema": document["schema"], "status": "EXTRACTED"}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
