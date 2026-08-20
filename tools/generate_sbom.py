#!/usr/bin/env python3
"""Generate a deterministic SPDX 2.3 inventory from a Gradle lockfile."""

# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 OpenCineCam contributors

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


def parse_lockfile(path: Path) -> list[tuple[str, str, str]]:
    modules: list[tuple[str, str, str]] = []
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or ":" not in line:
            continue
        coordinate = line.split("=", 1)[0].strip()
        parts = coordinate.split(":")
        if len(parts) != 3:
            continue
        group, name, version = parts
        modules.append((group, name, version))
    return sorted(set(modules))


def generate(lockfile: Path) -> dict[str, object]:
    modules = parse_lockfile(lockfile)
    namespace = "https://librestatic.com/opencinecam/sbom/"
    document_name = "OpenCineCam dependency inventory"
    packages = []
    relationships = []
    for index, (group, name, version) in enumerate(modules, start=1):
        package_id = f"SPDXRef-Package-{index:04d}"
        packages.append(
            {
                "SPDXID": package_id,
                "name": f"{group}:{name}",
                "versionInfo": version,
                "downloadLocation": f"https://repo1.maven.org/maven2/{group.replace('.', '/')}/{name}/{version}/",
                "licenseConcluded": "NOASSERTION",
                "licenseDeclared": "NOASSERTION",
                "externalRefs": [
                    {
                        "referenceCategory": "PACKAGE-MANAGER",
                        "referenceType": "purl",
                        "referenceLocator": f"pkg:maven/{group}/{name}@{version}",
                    }
                ],
            }
        )
        relationships.append(
            {
                "spdxElementId": "SPDXRef-OpenCineCam",
                "relatedSpdxElement": package_id,
                "relationshipType": "DEPENDS_ON",
            }
        )
    checksum = hashlib.sha256(lockfile.read_bytes()).hexdigest()
    return {
        "spdxVersion": "SPDX-2.3",
        "dataLicense": "CC0-1.0",
        "SPDXID": "SPDXRef-DOCUMENT",
        "name": document_name,
        "documentNamespace": namespace + checksum,
        "creationInfo": {
            "created": "1970-01-01T00:00:00Z",
            "creators": ["Tool: OpenCineCam generate_sbom.py"],
        },
        "packages": [
            {
                "SPDXID": "SPDXRef-OpenCineCam",
                "name": "OpenCineCam",
                "versionInfo": "0.1.0",
                "downloadLocation": "NOASSERTION",
                "licenseConcluded": "Apache-2.0",
                "licenseDeclared": "Apache-2.0",
            },
            *packages,
        ],
        "relationships": relationships,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--lockfile", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    document = generate(args.lockfile)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(document, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"wrote {args.output} ({len(document['packages']) - 1} dependencies)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
