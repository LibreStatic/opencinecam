---
plan_id: OCC-PLAN-042
title: "RAW throughput benchmark and device gate"
status: Done
revision: 1
milestone: M12
intended_executor: GPT-5.6 Luna
execution_mode: implementation
depends_on:
  - OCC-PLAN-012
  - OCC-PLAN-041
blocks:
  - OCC-PLAN-046
  - OCC-PLAN-047
  - OCC-PLAN-057
requirements:
  - OCC-RAW-001
  - OCC-CAM-001
  - OCC-STORAGE-001
  - OCC-RAW-002
adrs:
  - ADR-0007
  - ADR-0013
  - ADR-0019
  - ADR-0026
risks:
  - RISK-003
  - RISK-007
  - RISK-009
  - RISK-014
estimated_sessions: 1
expected_repo_state: buildable
created_by: GPT-5.6 Sol
---
# OCC-PLAN-042: RAW throughput benchmark and device gate

## 1. Objective

Measure graph/storage throughput for 60 seconds, enforce zero loss and p01 1.25× margin, and persist qualification evidence. The observable outcome is a buildable repository with passing automated checks and versioned evidence.

## 2. Why This Plan Exists

This is the bounded M12 slice that converts accepted architecture into executable behavior without reopening ownership, evidence, privacy, or fallback decisions.

## 3. Prerequisites

OCC-PLAN-012, OCC-PLAN-041

Conditional gate: Input: public Camera2 RAW capability/stream and destination benchmark. Pass: execute RAW branch with zero lost frames and p01 throughput at least 1.25 times stream rate. Fail: retain applicable RAW still/unsupported reporting and expose no RAW-video mode.

## 4. Required Reading

- `docs/requirements.md`: OCC-RAW-001
- `docs/requirements.md`: OCC-CAM-001
- `docs/requirements.md`: OCC-STORAGE-001
- `docs/requirements.md`: OCC-RAW-002
- `docs/adr/`: ADR-0007
- `docs/adr/`: ADR-0013
- `docs/adr/`: ADR-0019
- `docs/adr/`: ADR-0026
- `docs/architecture.md` and `docs/testing-strategy.md`.

## 5. Inputs

Outputs and execution records of every dependency; canonical schemas; current toolchain catalog; exact capability/profile/fingerprint evidence for hardware branches.

## 6. Deliverables

- Measure graph/storage throughput for 60 seconds, enforce zero loss and p01 1.25× margin, and persist qualification evidence.
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
2. Add the smallest production interfaces and immutable models required for: Measure graph/storage throughput for 60 seconds, enforce zero loss and p01 1.25× margin, and persist qualification evidence.
3. Implement the success path with stable IDs, explicit ownership, bounded buffers, and declared time/units.
4. Implement every failure and unsupported branch from the gate; do not catch and discard errors.
5. Add deterministic fakes/fixtures first, then Android or physical-device coverage for contracts that cannot be proven on host.
6. Run all commands, inspect produced reports/files, and retain only privacy-approved evidence references.
7. Update schemas, requirements/ADR links, manifest status, acceptance marks, and execution record without renumbering IDs.

## 11. Expected File and Module Changes

Primary paths: `camera/src/main/raw/, media/src/main/raw/, app/src/androidTest/`. Create no module until this plan owns executable behavior in it.

## 12. State and Data-Flow Changes

Inputs flow through immutable domain commands/configuration into the owning subsystem, then emit typed state/evidence. Queues are bounded; cancellation closes owned resources in reverse order; serialized data uses the accepted schema version.

## 13. Error and Fallback Behavior

Input: public Camera2 RAW capability/stream and destination benchmark. Pass: execute RAW branch with zero lost frames and p01 throughput at least 1.25 times stream rate. Fail: retain applicable RAW still/unsupported reporting and expose no RAW-video mode. Stable failures include component, code, severity, recoverability, correlation ID, and safe user message. Strict stops/rejects on required invariant failure; Adaptive uses only ADR-0015 transitions.

## 14. Tests

- Unit test the success, unsupported, cancellation, duplicate command, stale evidence, and cleanup paths for this deliverable.
- Instrument lifecycle/descriptor/permission behavior when Android owns the contract.
- Use a physical device only for hardware claims and persist failing as well as passing validation JSON.
- Assert Strict performs no silent transition and Adaptive emits an event for every permitted transition.

## 15. Commands to Run

- `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew lint test`
- `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew assembleDebug`
- `adb devices -l && JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew connectedCheck`

Record skips as failures or explicit not-run evidence; never report an unexecuted command as passing.

## 16. Acceptance Criteria

- [x] The exact Objective behavior exists and is reachable only through its accepted capability/policy gate.
- [x] Success, unsupported, failure, cancellation, and cleanup tests pass.
- [x] No protected signal-path property changes silently and Unknown remains distinct from unsupported.
- [x] Required commands pass, with the physical 60-second RAW10 gate recorded as a truthful fail branch.
- [x] Repository buildability, manifest links, schemas, and privacy constraints remain valid.

## 17. Evidence to Record

Command transcripts, test reports, graph/config IDs, validation JSON, relevant log/sidecar, file metadata, benchmark windows, and hashes where practical. Device evidence includes exact fingerprint and protocol version.

## 18. Rollback and Recovery

Revert only this plan's changes, remove generated outputs, close/delete partial media and fixtures through their owners, restore dependency-plan schema versions, rerun baseline tests, and leave the plan Blocked with diagnostics if the accepted architecture cannot be implemented.

## 19. Risks and Mitigations

Tracked risks: RISK-003, RISK-007, RISK-009, RISK-014. Mitigation is the accepted capability gate, bounded ownership, explicit failure, independent validation, and fail branch documented above.

## 20. Completion Update

Mark individual criteria, update `manifest.yaml`, `TRACEABILITY.md`, affected schema/examples, capability/profile evidence, and downstream conditional gates. Revise an ADR before deviating from it.

## 21. Execution Record

- Status: Done
- Started: 2026-08-20T07:48:00-03:00
- Completed: 2026-08-20T12:24:40-03:00
- Executor: GPT-5.6 Luna
- Commits:
- Evidence: `evidence/plan-042/gradle-build-final.log`, `evidence/plan-042/python-tests-final.log`, `evidence/plan-042/format-final.log`, `evidence/plan-042/raw-throughput-connected-test.log`, `evidence/plan-042/raw-throughput-instrument-2.log`, `evidence/plan-042/raw-throughput-logcat-2.log`, `evidence/plan-042/raw-throughput.json`, `evidence/plan-042/connected-check-final.log`, `evidence/plan-042/device-gate-final.json`, `evidence/plan-042/device-gate-final.log`, `evidence/plan-042/validator-final.log`, `evidence/plan-042/SHA256SUMS`
- Deviations: Physical-device evidence was collected on a local reference device and is not kept in the repository. The physical 60-second RAW10 graph/storage gate took the accepted fail branch: it retains RAW still/packed support and exposes no RAW-video mode. Host success/failure/unknown lifecycle tests and the full six-test connected suite pass.
- Follow-up plans: Continue with OCC-PLAN-044 append-only RAW writer journaling and recovery; do not promote RAW-video mode without a future qualifying throughput result.
