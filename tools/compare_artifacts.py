#!/usr/bin/env python3
"""Compare two release artifacts without exposing signing material."""

# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 OpenCineCam contributors

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


def digest(path: Path) -> str:
    hasher = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            hasher.update(block)
    return hasher.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--reference", type=Path, required=True)
    parser.add_argument("--candidate", type=Path, required=True)
    args = parser.parse_args()
    if not args.reference.is_file() or not args.candidate.is_file():
        parser.error("both artifacts must exist")
    reference = digest(args.reference)
    candidate = digest(args.candidate)
    result = {
        "reference": str(args.reference),
        "candidate": str(args.candidate),
        "referenceSha256": reference,
        "candidateSha256": candidate,
        "identical": reference == candidate,
    }
    print(json.dumps(result, sort_keys=True))
    return 0 if result["identical"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
