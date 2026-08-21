---
plan_id: OCC-PLAN-034
title: "HLG10 Main10 candidate graph"
status: Done
revision: 1
milestone: M10
intended_executor: GPT-5.6 Luna
execution_mode: implementation
depends_on:
  - OCC-PLAN-008
  - OCC-PLAN-017
  - OCC-PLAN-020
  - OCC-PLAN-033
blocks:
  - OCC-PLAN-035
  - OCC-PLAN-036
requirements:
  - OCC-COLOR-001
  - OCC-CODEC-001
  - OCC-TEST-001
  - OCC-COLOR-002
adrs:
  - ADR-0007
  - ADR-0010
  - ADR-0018
  - ADR-0026
risks:
  - RISK-004
  - RISK-005
  - RISK-008
estimated_sessions: 1
expected_repo_state: buildable
created_by: GPT-5.6 Sol
---
# OCC-PLAN-034: HLG10 Main10 candidate graph

## 1. Objective

Build the API33+ HLG10/BT.2020/Main10 candidate decision table and exact pass/fail unsupported reporting. The observable outcome is a buildable repository with passing automated checks and versioned evidence.

## 2. Why This Plan Exists

This is the bounded M10 slice that converts accepted architecture into executable behavior without reopening ownership, evidence, privacy, or fallback decisions.

## 3. Prerequisites

OCC-PLAN-008, OCC-PLAN-017, OCC-PLAN-020, OCC-PLAN-033

Conditional gate: Input: API 33+ device report with Camera2 HLG10 and a qualifying HEVC Main10 Surface encoder. Pass: execute HLG branch and record graph/file evidence. Fail: implement the exact unsupported reason and expose no recordable HLG mode.

## 4. Required Reading

- `docs/requirements.md`: OCC-COLOR-001
- `docs/requirements.md`: OCC-CODEC-001
- `docs/requirements.md`: OCC-TEST-001
- `docs/requirements.md`: OCC-COLOR-002
- `docs/adr/`: ADR-0007
- `docs/adr/`: ADR-0010
- `docs/adr/`: ADR-0018
- `docs/adr/`: ADR-0026
- `docs/architecture.md` and `docs/testing-strategy.md`.

## 5. Inputs

Outputs and execution records of every dependency; canonical schemas; current toolchain catalog; exact capability/profile/fingerprint evidence for hardware branches.

## 6. Deliverables

- Build the API33+ HLG10/BT.2020/Main10 candidate decision table and exact pass/fail unsupported reporting.
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
2. Add the smallest production interfaces and immutable models required for: Build the API33+ HLG10/BT.2020/Main10 candidate decision table and exact pass/fail unsupported reporting.
3. Implement the success path with stable IDs, explicit ownership, bounded buffers, and declared time/units.
4. Implement every failure and unsupported branch from the gate; do not catch and discard errors.
5. Add deterministic fakes/fixtures first, then Android or physical-device coverage for contracts that cannot be proven on host.
6. Run all commands, inspect produced reports/files, and retain only privacy-approved evidence references.
7. Update schemas, requirements/ADR links, manifest status, acceptance marks, and execution record without renumbering IDs.

## 11. Expected File and Module Changes

Primary paths: `camera/src/main/hdr/, media/src/main/video/, app/src/androidTest/`. Create no module until this plan owns executable behavior in it.

## 12. State and Data-Flow Changes

Inputs flow through immutable domain commands/configuration into the owning subsystem, then emit typed state/evidence. Queues are bounded; cancellation closes owned resources in reverse order; serialized data uses the accepted schema version.

## 13. Error and Fallback Behavior

Input: API 33+ device report with Camera2 HLG10 and a qualifying HEVC Main10 Surface encoder. Pass: execute HLG branch and record graph/file evidence. Fail: implement the exact unsupported reason and expose no recordable HLG mode. Stable failures include component, code, severity, recoverability, correlation ID, and safe user message. Strict stops/rejects on required invariant failure; Adaptive uses only ADR-0015 transitions.

## 14. Tests

- Unit test the success, unsupported, cancellation, duplicate command, stale evidence, and cleanup paths for this deliverable.
- Instrument lifecycle/descriptor/permission behavior when Android owns the contract.
- Use a physical device only for hardware claims and persist failing as well as passing validation JSON.
- Assert Strict performs no silent transition and Adaptive emits an event for every permitted transition.

## 15. Commands to Run

- `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew lint test`
- `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew assembleDebug`
- `adb devices -l && JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew connectedCheck`
- `ffprobe -v error -show_streams -show_format <recorded-fixture>` once OCC-PLAN-035 produces the first HLG recording; this candidate-only plan records explicit `NOT_RUN` evidence and OCC-PLAN-036 owns file/effective-precision qualification.

Record skips as failures or explicit not-run evidence; never report an unexecuted command as passing.

## 16. Acceptance Criteria

- [x] The exact Objective behavior exists and is reachable only through its accepted capability/policy gate.
- [x] Success, unsupported, failure, cancellation, and cleanup tests pass.
- [x] No protected signal-path property changes silently and Unknown remains distinct from unsupported.
- [x] Required commands pass, with candidate-only `ffprobe` explicitly recorded as `NOT_RUN` and owned by OCC-PLAN-035/036 once media exists.
- [x] Repository buildability, manifest links, schemas, and privacy constraints remain valid.

## 17. Evidence to Record

Command transcripts, test reports, graph/config IDs, validation JSON, relevant log/sidecar, file metadata, benchmark windows, and hashes where practical. Device evidence includes exact fingerprint and protocol version.

## 18. Rollback and Recovery

Revert only this plan's changes, remove generated outputs, close/delete partial media and fixtures through their owners, restore dependency-plan schema versions, rerun baseline tests, and leave the plan Blocked with diagnostics if the accepted architecture cannot be implemented.

## 19. Risks and Mitigations

Tracked risks: RISK-004, RISK-005, RISK-008. Mitigation is the accepted capability gate, bounded ownership, explicit failure, independent validation, and fail branch documented above.

## 20. Completion Update

Mark individual criteria, update `manifest.yaml`, `TRACEABILITY.md`, affected schema/examples, capability/profile evidence, and downstream conditional gates. Revise an ADR before deviating from it.

## 21. Execution Record

- Status: Done
- Started: 2026-08-20T07:39:00-03:00
- Completed: 2026-08-20T10:30:52-03:00
- Executor: GPT-5.6 Luna
- Commits:
- Evidence: `evidence/plan-034/gradle-build.log`, `evidence/plan-034/python-tests.log`, `evidence/plan-034/format.log`, `evidence/plan-034/validator.log`, `evidence/plan-034/device-gate.log`, `evidence/plan-034/device-gate.json`, `evidence/plan-034/device-gate-collector.log`, `evidence/plan-034/connected-check.log`, `evidence/plan-034/hlg-connected-test.log`, `evidence/plan-034/hlg-device-logcat.log`, `evidence/plan-034/hlg-device-gate.json`, `evidence/plan-034/hlg-evidence-extract.log`, `evidence/plan-034/window-state-before-retry.log`, `evidence/plan-034/ffprobe.log`, `evidence/plan-034/SHA256SUMS`
- Deviations: The exact reference-device fingerprint was selected through one adb transport. Physical-device evidence was collected on a local reference device and is not kept in the repository. This plan does not promote HLG recording, file signaling, or effective precision: OCC-PLAN-035 owns the recorded graph and OCC-PLAN-036 owns independent file/precision proof. Initial aggregate UI runs failed only while the secure keyguard was active; the unlocked rerun passed.
- Follow-up plans: Execute OCC-PLAN-035 with camera/session/preview/recording evidence on the same fingerprint, then OCC-PLAN-036 with independent HLG/Main10 file and controlled-gradient precision validation.
