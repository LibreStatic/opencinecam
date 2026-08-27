#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 OpenCineCam contributors

"""Validate OpenCineCam's documentation and implementation-plan graph.

The parser intentionally supports only the manifest subset emitted by this
repository. It has no third-party dependency and avoids the host XML parser.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter
from pathlib import Path


REQUIRED_DOCS = (
    "README.md",
    "LICENSE",
    "docs/requirements.md",
    "docs/terminology.md",
    "docs/architecture.md",
    "docs/technical-closure.md",
    "docs/references.md",
    "docs/risks.md",
    "docs/testing-strategy.md",
    "docs/security-and-privacy.md",
    "docs/device-validation.md",
    "docs/adr/README.md",
    "docs/plans/README.md",
    "docs/plans/manifest.yaml",
    "docs/plans/TRACEABILITY.md",
    "docs/plans/PLAN-QUALITY-REPORT.md",
)
ALLOWED_STATUSES = {
    "Draft", "Ready", "ConditionalReady", "InProgress", "Blocked",
    "Done", "Superseded", "Cancelled",
}
LIST_FIELDS = {
    "depends_on", "blocks", "requirements", "adrs", "risks",
    "conditional_gates",
}


def parse_inline_list(value: str) -> list[str]:
    value = value.strip()
    if value == "[]":
        return []
    if not (value.startswith("[") and value.endswith("]")):
        raise ValueError(f"invalid inline list: {value}")
    inner = value[1:-1].strip()
    return [] if not inner else [item.strip().strip('"') for item in inner.split(",")]


def parse_manifest(path: Path) -> list[dict[str, object]]:
    records: list[dict[str, object]] = []
    current: dict[str, object] | None = None
    active_list: str | None = None
    for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not raw.strip() or raw.strip() == "plans:":
            continue
        match = re.match(r"^  - id: (OCC-PLAN-\d{3})$", raw)
        if match:
            if current:
                records.append(current)
            current = {"id": match.group(1)}
            active_list = None
            continue
        if current is None:
            raise ValueError(f"{path}:{number}: content before first plan")
        list_item = re.match(r'^      - "(.*)"$', raw)
        if list_item and active_list:
            cast_list = current.setdefault(active_list, [])
            assert isinstance(cast_list, list)
            cast_list.append(list_item.group(1))
            continue
        field = re.match(r"^    ([a-z_]+):\s*(.*)$", raw)
        if not field:
            raise ValueError(f"{path}:{number}: unsupported YAML subset: {raw}")
        key, value = field.groups()
        active_list = None
        if key in LIST_FIELDS:
            if value:
                current[key] = parse_inline_list(value)
            else:
                current[key] = []
                active_list = key
        elif key == "revision":
            current[key] = int(value)
        else:
            current[key] = value.strip('"')
    if current:
        records.append(current)
    return records


def cycle_nodes(records: list[dict[str, object]]) -> list[str]:
    graph = {str(r["id"]): list(r.get("depends_on", [])) for r in records}
    visiting: set[str] = set()
    visited: set[str] = set()
    stack: list[str] = []

    def visit(node: str) -> list[str] | None:
        if node in visiting:
            return stack[stack.index(node):] + [node]
        if node in visited:
            return None
        visiting.add(node)
        stack.append(node)
        for dependency in graph.get(node, []):
            found = visit(dependency)
            if found:
                return found
        stack.pop()
        visiting.remove(node)
        visited.add(node)
        return None

    for node in graph:
        found = visit(node)
        if found:
            return found
    return []


def extract_ids(path: Path, pattern: str) -> set[str]:
    return set(re.findall(pattern, path.read_text(encoding="utf-8")))


def acceptance_states(text: str) -> list[str]:
    """Return only the binary checks from a plan's acceptance section."""
    match = re.search(
        r"^## 16\. Acceptance Criteria\s*$([\s\S]*?)(?=^## 17\.)",
        text,
        re.MULTILINE,
    )
    if not match:
        return []
    return re.findall(r"^- \[([ xX])\]", match.group(1), re.MULTILINE)


def execution_evidence_paths(text: str) -> list[str]:
    match = re.search(r"^- Evidence:\s*(.*)$", text, re.MULTILINE)
    return [] if not match else re.findall(r"`([^`]+)`", match.group(1))


def validate_root(root: Path) -> tuple[list[str], dict[str, int | str]]:
    errors: list[str] = []
    for relative in REQUIRED_DOCS:
        if not (root / relative).is_file():
            errors.append(f"missing required file: {relative}")

    manifest_path = root / "docs/plans/manifest.yaml"
    if not manifest_path.is_file():
        return errors, {}
    try:
        records = parse_manifest(manifest_path)
    except (OSError, ValueError) as exc:
        return errors + [str(exc)], {}

    ids = [str(r["id"]) for r in records]
    if len(ids) != len(set(ids)):
        errors.append("duplicate plan ID")
    if len(records) < 60:
        errors.append(f"expected at least 60 plans, found {len(records)}")
    numeric_ids = sorted(int(plan_id.rsplit("-", 1)[1]) for plan_id in ids)
    if numeric_ids != list(range(1, numeric_ids[-1] + 1)):
        errors.append("plan IDs must remain contiguous and monotonically increasing")
    known = set(ids)
    statuses = Counter(str(r.get("status")) for r in records)
    if statuses["Draft"]:
        errors.append(f"unresolved Draft plans remain: {statuses['Draft']}")

    requirements = extract_ids(root / "docs/requirements.md", r"OCC-[A-Z]+-\d{3}")
    adrs = extract_ids(root / "docs/adr/README.md", r"ADR-\d{4}")
    risks = extract_ids(root / "docs/risks.md", r"RISK-\d{3}")
    traceability = (root / "docs/plans/TRACEABILITY.md").read_text(encoding="utf-8")
    referenced_adrs: set[str] = set()
    referenced_risks: set[str] = set()

    for record in records:
        plan_id = str(record["id"])
        status = str(record.get("status"))
        if status not in ALLOWED_STATUSES:
            errors.append(f"{plan_id}: invalid status {status}")
        dependencies = list(record.get("depends_on", []))
        missing_dependencies = set(dependencies) - known
        if missing_dependencies:
            errors.append(f"{plan_id}: missing dependencies {sorted(missing_dependencies)}")
        if plan_id in dependencies:
            errors.append(f"{plan_id}: self dependency")
        for key in ("requirements", "adrs", "risks"):
            if not record.get(key):
                errors.append(f"{plan_id}: empty {key}")
        if set(record.get("requirements", [])) - requirements:
            errors.append(f"{plan_id}: unknown requirement reference")
        if set(record.get("adrs", [])) - adrs:
            errors.append(f"{plan_id}: unknown ADR reference")
        if set(record.get("risks", [])) - risks:
            errors.append(f"{plan_id}: unknown risk reference")
        referenced_adrs.update(record.get("adrs", []))
        referenced_risks.update(record.get("risks", []))
        if status == "ConditionalReady" and not record.get("conditional_gates"):
            errors.append(f"{plan_id}: ConditionalReady without gate")
        if status == "Ready" and record.get("conditional_gates"):
            errors.append(f"{plan_id}: Ready plan has conditional gate")

        relative = str(record.get("path", ""))
        plan_path = root / relative
        if not plan_path.is_file():
            errors.append(f"{plan_id}: missing path {relative}")
            continue
        text = plan_path.read_text(encoding="utf-8")
        if f"plan_id: {plan_id}" not in text:
            errors.append(f"{plan_id}: front matter ID mismatch")
        if f"status: {status}" not in text:
            errors.append(f"{plan_id}: front matter status mismatch")
        for section in range(1, 22):
            if not re.search(rf"^## {section}\. ", text, re.MULTILINE):
                errors.append(f"{plan_id}: missing section {section}")
        if re.search(r"\b(TBD|TODO)\b", text, re.IGNORECASE):
            errors.append(f"{plan_id}: unresolved marker")
        checks = acceptance_states(text)
        if not checks:
            errors.append(f"{plan_id}: no binary acceptance criteria")
        if status == "Done" and any(state == " " for state in checks):
            errors.append(f"{plan_id}: Done plan has unchecked acceptance criteria")
        if status == "Done":
            evidence_paths = execution_evidence_paths(text)
            if not evidence_paths:
                errors.append(f"{plan_id}: Done plan has no execution evidence")
            for evidence_path in evidence_paths:
                if not (root / evidence_path).is_file():
                    errors.append(f"{plan_id}: missing evidence file {evidence_path}")

    cycle = cycle_nodes(records)
    if cycle:
        errors.append("dependency cycle: " + " -> ".join(cycle))
    missing_trace = sorted(req for req in requirements if req not in traceability)
    if missing_trace:
        errors.append(f"traceability missing {len(missing_trace)} requirements")
    if adrs - referenced_adrs:
        errors.append(f"orphan ADRs: {sorted(adrs - referenced_adrs)}")
    if risks - referenced_risks:
        errors.append(f"orphan risks: {sorted(risks - referenced_risks)}")

    schema_dir = root / "docs/schemas"
    schema_files = sorted(schema_dir.glob("*.schema.json"))
    if len(schema_files) < 6:
        errors.append(f"expected at least 6 schemas, found {len(schema_files)}")
    for schema in schema_files:
        try:
            json.loads(schema.read_text(encoding="utf-8"))
        except json.JSONDecodeError as exc:
            errors.append(f"{schema.relative_to(root)}: invalid JSON: {exc}")

    status_by_id = {str(r["id"]): str(r.get("status")) for r in records}
    next_plan = next(
        (
            str(r["id"])
            for r in records
            if str(r.get("status")) in {"Ready", "ConditionalReady"}
            and all(status_by_id.get(str(dep)) in {"Done", "Superseded"} for dep in r.get("depends_on", []))
        ),
        "none",
    )
    stats: dict[str, int | str] = {
        "requirements": len(requirements),
        "adrs": len(adrs),
        "risks": len(risks),
        "plans": len(records),
        "ready": statuses["Ready"],
        "conditional_ready": statuses["ConditionalReady"],
        "in_progress": statuses["InProgress"],
        "done": statuses["Done"],
        "superseded": statuses["Superseded"],
        "blocked": statuses["Blocked"],
        "traceability_coverage": "100%" if not missing_trace else "incomplete",
        "dependency_cycles": "none" if not cycle else "present",
        "next_plan": next_plan,
    }
    return errors, stats


def write_quality_report(root: Path, stats: dict[str, int | str], errors: list[str]) -> None:
    outcome = "PASS" if not errors else "FAIL"
    remaining = (
        "Physical-device HLG10/effective precision, RAW throughput, OEM audio/ISP behavior, "
        "OCLog2 interchange/provenance, APV availability, fold posture, thermal endurance, "
        "and release certification."
    )
    lines = [
        "# Plan Quality Report", "", f"- Result: **{outcome}**",
        f"- Requirements: {stats.get('requirements', 0)}",
        f"- ADRs: {stats.get('adrs', 0)}", f"- Risks: {stats.get('risks', 0)}",
        f"- Plans: {stats.get('plans', 0)}", f"- Done: {stats.get('done', 0)}",
        f"- Superseded: {stats.get('superseded', 0)}",
        f"- InProgress: {stats.get('in_progress', 0)}", f"- Ready: {stats.get('ready', 0)}",
        f"- ConditionalReady: {stats.get('conditional_ready', 0)}",
        f"- Blocked: {stats.get('blocked', 0)}",
        f"- Traceability coverage: {stats.get('traceability_coverage', 'unknown')}",
        f"- Dependency cycles: {stats.get('dependency_cycles', 'unknown')}",
        f"- Remaining external conditions: {remaining}",
        f"- Current execution starts at: {stats.get('next_plan', 'none')}", "",
        "## Checks", "",
    ]
    if errors:
        lines.extend(f"- FAIL: {error}" for error in errors)
    else:
        lines.extend([
            f"- PASS: required files and {len(schema_files)} JSON schemas exist and parse.",
            "- PASS: IDs, paths, dependencies, references, and statuses resolve.",
            "- PASS: dependency graph is acyclic and every plan has 21 sections.",
            "- PASS: all accepted requirements, ADRs, and risks are traceable.",
            "- PASS: ConditionalReady plans carry deterministic gates.",
        ])
    (root / "docs/plans/PLAN-QUALITY-REPORT.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path("."))
    parser.add_argument("--write-report", action="store_true")
    args = parser.parse_args()
    errors, stats = validate_root(args.root.resolve())
    if args.write_report:
        write_quality_report(args.root.resolve(), stats, errors)
    if errors:
        for error in errors:
            print(f"ERROR: {error}")
        return 1
    print(json.dumps(stats, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
