---
plan_id: OCC-PLAN-048
title: "CPU GPU references LUT OCIO and DCTL"
status: ConditionalReady
revision: 1
milestone: M14
intended_executor: GPT-5.6 Luna
execution_mode: implementation
depends_on:
  - OCC-PLAN-038
  - OCC-PLAN-047
blocks:
  - OCC-PLAN-049
requirements:
  - OCC-COLOR-001
  - OCC-RAW-001
  - OCC-TEST-001
  - OCC-COLOR-002
adrs:
  - ADR-0018
  - ADR-0021
  - ADR-0022
  - ADR-0026
risks:
  - RISK-005
  - RISK-015
  - RISK-020
estimated_sessions: 1
expected_repo_state: buildable
created_by: GPT-5.6 Sol
---
# OCC-PLAN-048: CPU GPU references LUT OCIO and DCTL

## 1. Objective

Implement reference CPU/GPU transforms, inverse, LUTs, OCIO, and DCTL with version/hash metadata and numeric tolerances. The observable outcome is a buildable repository with passing automated checks and versioned evidence.

## 2. Why This Plan Exists

This is the bounded M14 slice that converts accepted architecture into executable behavior without reopening ownership, evidence, privacy, or fallback decisions.

## 3. Prerequisites

OCC-PLAN-038, OCC-PLAN-047

Conditional gate: Input: accepted OpenCine Log v1 specification inputs and verified RAW/P010/ISP provenance fixtures. Pass: publish and integrate only passing provenance branches. Fail: keep Flat8/HLG names and expose no OpenCine Log mode.

## 4. Required Reading

- `docs/requirements.md`: OCC-COLOR-001
- `docs/requirements.md`: OCC-RAW-001
- `docs/requirements.md`: OCC-TEST-001
- `docs/requirements.md`: OCC-COLOR-002
- `docs/adr/`: ADR-0018
- `docs/adr/`: ADR-0021
- `docs/adr/`: ADR-0022
- `docs/adr/`: ADR-0026
- `docs/architecture.md` and `docs/testing-strategy.md`.

## 5. Inputs

Outputs and execution records of every dependency; canonical schemas; current toolchain catalog; exact capability/profile/fingerprint evidence for hardware branches.

## 6. Deliverables

- Implement reference CPU/GPU transforms, inverse, LUTs, OCIO, and DCTL with version/hash metadata and numeric tolerances.
- Unit/instrumentation/device or host fixtures that prove the behavior without elevating unsupported evidence.
- Updated execution record, traceability links, and local evidence references.

## 7. In Scope

The behavior in Objective and Deliverables, its narrow interfaces, failure states, tests, documentation updates, and required migration of directly owned data.

## 8. Out of Scope

Unlisted milestone features, vendor/private APIs, cloud/network behavior, unrelated UI redesign, silent configuration fallback, and architecture changes without a superseding ADR.

## 9. Architectural Constraints

Follow the listed ADRs. Keep Unknown explicit, resources uniquely owned and bounded, the main thread free of capture work, clip invariants immutable, data local, and every fallback/user-visible discrepancy recorded.

## 10. Implementation Steps

1. Verify dependency execution records, repository build, JDK 17, and the conditional input when present.
2. Add the smallest production interfaces and immutable models required for: Implement reference CPU/GPU transforms, inverse, LUTs, OCIO, and DCTL with version/hash metadata and numeric tolerances.
3. Implement the success path with stable IDs, explicit ownership, bounded buffers, and declared time/units.
4. Implement every failure and unsupported branch from the gate; do not catch and discard errors.
5. Add deterministic fakes/fixtures first, then Android or physical-device coverage for contracts that cannot be proven on host.
6. Run all commands, inspect produced reports/files, and retain only privacy-approved evidence references.
7. Update schemas, requirements/ADR links, manifest status, acceptance marks, and execution record without renumbering IDs.

## 11. Expected File and Module Changes

Primary paths: `docs/color/, native-pipeline/src/main/cpp/, desktop-tools/`. Create no module until this plan owns executable behavior in it.

## 12. State and Data-Flow Changes

Inputs flow through immutable domain commands/configuration into the owning subsystem, then emit typed state/evidence. Queues are bounded; cancellation closes owned resources in reverse order; serialized data uses the accepted schema version.

## 13. Error and Fallback Behavior

Input: accepted OpenCine Log v1 specification inputs and verified RAW/P010/ISP provenance fixtures. Pass: publish and integrate only passing provenance branches. Fail: keep Flat8/HLG names and expose no OpenCine Log mode. Stable failures include component, code, severity, recoverability, correlation ID, and safe user message. Strict stops/rejects on required invariant failure; Adaptive uses only ADR-0015 transitions.

## 14. Tests

- Unit test the success, unsupported, cancellation, duplicate command, stale evidence, and cleanup paths for this deliverable.
- Instrument lifecycle/descriptor/permission behavior when Android owns the contract.
- Use a physical device only for hardware claims and persist failing as well as passing validation JSON.
- Assert Strict performs no silent transition and Adaptive emits an event for every permitted transition.

## 15. Commands to Run

- `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew lint test`
- `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew assembleDebug`
- `ffprobe -v error -show_streams -show_format <recorded-fixture>`

Record skips as failures or explicit not-run evidence; never report an unexecuted command as passing.

## 16. Acceptance Criteria

- [ ] The exact Objective behavior exists and is reachable only through its accepted capability/policy gate.
- [ ] Success, unsupported, failure, cancellation, and cleanup tests pass.
- [ ] No protected signal-path property changes silently and Unknown remains distinct from unsupported.
- [ ] Required commands pass, or the plan is marked Blocked with the real output and unblock condition.
- [ ] Repository buildability, manifest links, schemas, and privacy constraints remain valid.

## 17. Evidence to Record

Command transcripts, test reports, graph/config IDs, validation JSON, relevant log/sidecar, file metadata, benchmark windows, and hashes where practical. Device evidence includes exact fingerprint and protocol version.

## 18. Rollback and Recovery

Revert only this plan's changes, remove generated outputs, close/delete partial media and fixtures through their owners, restore dependency-plan schema versions, rerun baseline tests, and leave the plan Blocked with diagnostics if the accepted architecture cannot be implemented.

## 19. Risks and Mitigations

Tracked risks: RISK-005, RISK-015, RISK-020. Mitigation is the accepted capability gate, bounded ownership, explicit failure, independent validation, and fail branch documented above.

## 20. Completion Update

Mark individual criteria, update `manifest.yaml`, `TRACEABILITY.md`, affected schema/examples, capability/profile evidence, and downstream conditional gates. Revise an ADR before deviating from it.

## 21. Execution Record

- Status: ConditionalReady (host contract implemented; provenance gate pending)
- Started: 2026-08-20T08:05:00-03:00
- Completed:
- Executor: GPT-5.6 Luna
- Commits:
- Evidence: `evidence/plan-048/gradle-build.log`, `evidence/plan-048/python-tests.log`, `evidence/plan-048/format.log`, `evidence/plan-048/validator.log`, `evidence/plan-048/ffprobe.log`, `evidence/plan-048/provenance-gate.json`, `evidence/plan-048/provenance-gate.log`
- Deviations: Host reference CPU/GPU transforms, inverse round-trip, bounded LUT, metadata hash, and DCTL descriptor contracts are tested. No accepted Log provenance fixture exists, so no OpenCine Log mode is integrated or promoted.
- Follow-up plans: Execute the pass branch only after OCC-PLAN-047's conditional inputs are accepted.
